package com.monimo.collector.inbound.otlp

import com.monimo.collector.support.TestInfraConfig
import com.monimo.common.kafka.RawHeaders
import com.monimo.common.kafka.RawSignal
import io.grpc.ManagedChannelBuilder
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.logs.v1.ResourceLogs
import io.opentelemetry.proto.logs.v1.ScopeLogs
import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.metrics.v1.ResourceMetrics
import io.opentelemetry.proto.metrics.v1.ScopeMetrics
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

// 연결 약속 검증: OTLP gRPC 로 보낸 스팬 · 메트릭 · 로그를 수집기가 받아 세고, raw 토픽에 protobuf 그대로 넣는다.
// 보낸 쪽 주소도 봉투 메모(헤더)로 같이 붙는다 (`#149`, ADR `#63`). 값 바이트는 그대로고 메모만 늘어난다.
// 포트 0 = 빈 포트 아무거나. 테스트끼리 · 로컬 4317 과 겹치지 않는다.
// 샘플링 비율 1.0 = 전부 통과. 이 테스트가 보는 것은 "받은 것이 raw 에 그대로 들어가는가" 라서
// 샘플링이 끼어들면 안 된다. 샘플링 자체는 TraceSamplerTest 가 확인한다
@SpringBootTest(
    properties = [
        "monimo.collector.otlp.grpc.port=0", // 스프링을 실제로 띄운다. 포트만 테스트용으로 덮어쓴다
        "monimo.collector.sampling.ratio=1.0",
        // 거르기도 끈다. 지금은 테스트 스팬에 url.path 가 없어 안 걸리지만, 그건 우연이다.
        // 이 테스트가 보는 것은 "받은 바이트가 그대로 Kafka 에 들어가나" 라서 중간에서 빼는 것이 없어야 한다
        "monimo.collector.health-check.paths=",
        // 로그 하한은 기본(INFO) 그대로 둔다. 맨 앞 로그 블록의 레코드는 등급 0(미지정)이라 하한에 안 걸리고,
        // 뒤의 두 블록이 하한이 수신 경로에 끼어 있는지를 본다
    ],
)
@Import(TestInfraConfig::class) // 진짜 PostgreSQL · Kafka 컨테이너를 띄워 붙인다 (Testcontainers)
class OtlpGrpcReceiverTest(
    server: OtlpGrpcServer, // 생성자로 스프링 빈을 받는다 (실제로 잡은 포트를 알려면 필요)
    counter: OtlpReceiveCounter, // 받은 건수 확인용
    environment: Environment, // 설정값 읽기용 (Kafka 주소)
) : BehaviorSpec({ // Kotest 스타일. Given · When · Then 이 블록 그대로다

    // 에이전트 역할. 수집기의 gRPC 포트로 연결한다. usePlaintext = 암호화 없이(로컬이라)
    val channel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
    afterSpec { channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS) } // 테스트 다 끝나면 연결 닫기

    // raw 토픽을 읽는 시험용 소비자. 적재 처리기가 할 일을 여기서 흉내 낸다
    // <String, ByteArray> = 키는 글자 · 값은 바이트 (수집기가 넣은 모양과 같아야 한다)
    val consumer = KafkaConsumer<String, ByteArray>(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to environment.getRequiredProperty("spring.kafka.bootstrap-servers"),
            ConsumerConfig.GROUP_ID_CONFIG to "test-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
        ),
    ).also { it.subscribe(listOf(RawSignal.TOPIC)) }
    afterSpec { consumer.close() }

    // 키가 signal 인 메시지가 올 때까지 최대 10초 기다린다.
    // 발행이 비동기라 보낸 직후에는 아직 안 와 있을 수 있어서 기다리는 코드가 필요하다
    fun receivedFromRaw(signal: RawSignal): ConsumerRecord<String, ByteArray> {
        val deadline = System.currentTimeMillis() + 10_000 // 지금부터 10초 뒤 시각 (10_000 = 10000, 밑줄은 읽기용)
        while (System.currentTimeMillis() < deadline) { // 시간이 남아 있는 동안 반복
            // poll = 0.5초 동안 온 메시지들을 가져온다. 그중 키가 맞는 첫 메시지를 돌려주고 함수 종료
            consumer.poll(Duration.ofMillis(500)).forEach { if (it.key() == signal.key) return it }
        }
        error("raw 토픽에 ${signal.key} 메시지가 10초 안에 안 옴") // 10초 안에 못 찾으면 테스트 실패
    }

    // 반대 확인 : 3초 동안 키가 signal 인 메시지가 안 와야 한다. "안 온다" 는 기다려 봐야만 알 수 있어서
    // 기다리는 시간은 정상 발행이 끝나고도 남을 만큼(위 receivedFromRaw 가 보통 1초 안에 받는다)으로 잡았다
    fun nothingFromRaw(signal: RawSignal) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(500)).forEach { if (it.key() == signal.key) error("raw 토픽에 ${signal.key} 메시지가 와 있음") }
        }
    }

    // 등급 번호 + 이름을 채운 로그 레코드. 번호는 OTLP 규약(DEBUG 5 · INFO 9) 이고, 번호가 0 이거나 규약 밖이면
    // 하한 판정이 이름 쪽으로 넘어간다 (#236)
    fun logRecord(severityNumber: Int, severityText: String): LogRecord =
        LogRecord.newBuilder().setSeverityNumberValue(severityNumber).setSeverityText(severityText).build()

    // 봉투 메모에서 주소를 꺼낸다. 적재 처리기가 하는 것과 같은 방식(마지막 것 · UTF-8)
    fun peerAddressOf(record: ConsumerRecord<String, ByteArray>): String? =
        record.headers().lastHeader(RawHeaders.PEER_ADDRESS)?.value()?.toString(Charsets.UTF_8)

    // 테스트는 localhost 로 붙으므로 되돌이 주소가 나온다. IPv4 · IPv6 중 어느 쪽으로 붙을지는
    // 플랫폼과 이름 풀이에 달려 있어 둘 다 받는다. 여기서 보는 것은 "연결에서 읽은 값이 메모로 붙는가" 다
    val loopback = listOf("127.0.0.1", "0:0:0:0:0:0:0:1")

    Given("OTLP gRPC 수신을 켠 수집기") {
        When("스팬 2개를 TraceService.Export 로 보내면") {
            val before = counter.count(OtlpReceiveCounter.Signal.TRACES) // 보내기 전 건수를 먼저 적어 둔다
            // 에이전트가 보낼 데이터를 손으로 만든다. resource → scope → span 3겹 구조 그대로
            val request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(
                    ResourceSpans.newBuilder().addScopeSpans(
                        ScopeSpans.newBuilder()
                            .addSpans(Span.newBuilder().setName("GET /orders"))
                            .addSpans(Span.newBuilder().setName("SELECT orders")),
                    ),
                )
                .build()
            // 실제로 보낸다. blockingStub = 응답이 올 때까지 기다리는 방식 (테스트에서는 이게 편하다)
            TraceServiceGrpc.newBlockingStub(channel).export(request)

            Then("traces 받은 건수가 2 늘어난다") {
                counter.count(OtlpReceiveCounter.Signal.TRACES) - before shouldBe 2.0 // 스팬 2개를 보냈으니 2 증가
            }

            Then("raw 토픽에 키 traces · 값은 받은 protobuf 그대로 · 보낸 쪽 주소가 메모로 붙는다") {
                // 읽은 바이트를 다시 풀어(parseFrom) 처음 보낸 요청과 같은지 본다 = 중간에 건드리지 않았다는 증거
                val record = receivedFromRaw(RawSignal.TRACES)
                ExportTraceServiceRequest.parseFrom(record.value()) shouldBe request
                // 메모는 값 바이트를 건드리지 않는다. 위 단언이 그것을 증명하고 아래가 메모를 본다
                loopback shouldContain peerAddressOf(record)
            }
        }

        // 아래 두 블록은 트레이스와 같은 방식. 신호와 개수만 다르다
        When("메트릭 1개를 MetricsService.Export 로 보내면") {
            val before = counter.count(OtlpReceiveCounter.Signal.METRICS)
            val request = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(
                    ResourceMetrics.newBuilder().addScopeMetrics(
                        ScopeMetrics.newBuilder().addMetrics(Metric.newBuilder().setName("jvm.memory.used")),
                    ),
                )
                .build()
            MetricsServiceGrpc.newBlockingStub(channel).export(request)

            Then("metrics 받은 건수가 1 늘어난다") {
                counter.count(OtlpReceiveCounter.Signal.METRICS) - before shouldBe 1.0
            }

            Then("raw 토픽에 키 metrics · 값은 받은 protobuf 그대로 · 보낸 쪽 주소가 메모로 붙는다") {
                val record = receivedFromRaw(RawSignal.METRICS)
                ExportMetricsServiceRequest.parseFrom(record.value()) shouldBe request
                // 메모는 값 바이트를 건드리지 않는다. 위 단언이 그것을 증명하고 아래가 메모를 본다
                loopback shouldContain peerAddressOf(record)
            }
        }

        When("로그 레코드 3개를 LogsService.Export 로 보내면") {
            val before = counter.count(OtlpReceiveCounter.Signal.LOGS)
            val request = ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(
                    ResourceLogs.newBuilder().addScopeLogs(
                        ScopeLogs.newBuilder()
                            .addLogRecords(LogRecord.newBuilder())
                            .addLogRecords(LogRecord.newBuilder())
                            .addLogRecords(LogRecord.newBuilder()),
                    ),
                )
                .build()
            LogsServiceGrpc.newBlockingStub(channel).export(request)

            Then("logs 받은 건수가 3 늘어난다") {
                counter.count(OtlpReceiveCounter.Signal.LOGS) - before shouldBe 3.0
            }

            Then("raw 토픽에 키 logs · 값은 받은 protobuf 그대로 · 보낸 쪽 주소가 메모로 붙는다") {
                val record = receivedFromRaw(RawSignal.LOGS)
                ExportLogsServiceRequest.parseFrom(record.value()) shouldBe request
                // 메모는 값 바이트를 건드리지 않는다. 위 단언이 그것을 증명하고 아래가 메모를 본다
                loopback shouldContain peerAddressOf(record)
            }
        }

        // 아래 두 블록은 로그 하한(`#215`, 기본 INFO)이 수신 경로에 실제로 끼어 있는지 본다.
        // 판정 자체는 LogLevelFilterTest 가 보고, 여기서는 "걸러진 결과가 Kafka 로 가는가" 만 본다
        When("INFO 미만(DEBUG) 로그 레코드 2개만 보내면") {
            val before = counter.count(OtlpReceiveCounter.Signal.LOGS)
            val request = ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(
                    ResourceLogs.newBuilder().addScopeLogs(
                        ScopeLogs.newBuilder()
                            .addLogRecords(logRecord(5, "DEBUG"))
                            .addLogRecords(logRecord(5, "DEBUG")),
                    ),
                )
                .build()
            LogsServiceGrpc.newBlockingStub(channel).export(request) // 예외 없이 돌아오면 응답 OK

            Then("logs 받은 건수는 거르기 전 숫자로 2 늘어난다") {
                counter.count(OtlpReceiveCounter.Signal.LOGS) - before shouldBe 2.0
            }

            Then("raw 토픽에는 logs 메시지가 새로 오지 않는다") {
                nothingFromRaw(RawSignal.LOGS)
            }
        }

        When("DEBUG 1개 · INFO 1개를 함께 보내면") {
            val info = logRecord(9, "INFO")
            val request = ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(
                    ResourceLogs.newBuilder().addScopeLogs(
                        ScopeLogs.newBuilder()
                            .addLogRecords(logRecord(5, "DEBUG"))
                            .addLogRecords(info),
                    ),
                )
                .build()
            LogsServiceGrpc.newBlockingStub(channel).export(request)

            Then("raw 토픽의 logs 메시지를 풀면 INFO 만 들어 있다") {
                val record = receivedFromRaw(RawSignal.LOGS)
                val stored = ExportLogsServiceRequest.parseFrom(record.value())
                stored.resourceLogsList.flatMap { it.scopeLogsList }.flatMap { it.logRecordsList } shouldBe listOf(info)
            }
        }

        // #236 : 번호가 0 이면 하한이 번호로는 판정할 수 없다. 글자로 넘어가는 길이 수신 경로에도 끼어 있는지 본다
        When("번호 0 · 글자 DEBUG 인 레코드와 번호 0 · 글자 INFO 인 레코드를 함께 보내면") {
            val info = logRecord(0, "INFO")
            val request = ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(
                    ResourceLogs.newBuilder().addScopeLogs(
                        ScopeLogs.newBuilder()
                            .addLogRecords(logRecord(0, "DEBUG"))
                            .addLogRecords(info),
                    ),
                )
                .build()
            LogsServiceGrpc.newBlockingStub(channel).export(request)

            Then("raw 토픽의 logs 메시지를 풀면 글자가 INFO 인 것만 들어 있다") {
                val record = receivedFromRaw(RawSignal.LOGS)
                val stored = ExportLogsServiceRequest.parseFrom(record.value())
                stored.resourceLogsList.flatMap { it.scopeLogsList }.flatMap { it.logRecordsList } shouldBe listOf(info)
            }
        }
    }
})
