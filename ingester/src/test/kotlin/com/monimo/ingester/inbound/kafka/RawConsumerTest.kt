package com.monimo.ingester.inbound.kafka

import com.clickhouse.client.api.Client
import com.google.protobuf.ByteString
import com.monimo.common.kafka.RawSignal
import com.monimo.ingester.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.logs.v1.ResourceLogs
import io.opentelemetry.proto.logs.v1.ScopeLogs
import io.opentelemetry.proto.metrics.v1.Metric
import io.opentelemetry.proto.metrics.v1.ResourceMetrics
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.metrics.v1.ScopeMetrics
import io.opentelemetry.proto.resource.v1.Resource
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import org.apache.kafka.clients.producer.KafkaProducer // Kafka 에 넣는 도구
import org.apache.kafka.clients.producer.ProducerConfig // 설정 키 이름들
import org.apache.kafka.clients.producer.ProducerRecord // 넣을 메시지 한 개 (토픽 · 키 · 값)
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate

// 수집기 테스트가 소비자를 만들어 확인했다면, 여기서는 반대로 생산자를 만들어 넣는다.
// 수집기가 넣는 것과 같은 모양(키 = 신호 글자 · 값 = protobuf 바이트)으로 직접 넣고, 적재 처리기가 풀어 세는지 본다.
@SpringBootTest
@Import(TestInfraConfig::class) // 진짜 Kafka · ClickHouse · PostgreSQL 컨테이너를 띄워 붙인다
class RawConsumerTest(
    counter: RawConsumeCounter, // 카운터 빈을 생성자로 받아 값을 확인한다
    environment: Environment, // Kafka 주소를 읽는다 (컨테이너라 주소가 매번 다르다)
    jdbc: JdbcTemplate, // 파드가 agents 표에 등록됐는지 본다
    clickHouse: Client, // peer_service 가 채워져 들어갔는지 본다
) : BehaviorSpec({

    // 수집기 역할을 하는 생산자
    val producer = KafkaProducer<String, ByteArray>(
        mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to environment.getRequiredProperty("spring.kafka.bootstrap-servers"),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java, // 키는 글자로
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java, // 값은 바이트 그대로
        ),
    )
    afterSpec { producer.close() } // 테스트 다 끝나면 닫기

    // 소비는 다른 스레드에서 일어나므로 넣자마자 확인되지 않는다. 카운터가 목표만큼 오를 때까지 최대 10초 기다린다
    fun awaitCount(signal: RawSignal, expected: Double) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && counter.count(signal) < expected) {
            Thread.sleep(200)
        }
        counter.count(signal) shouldBe expected // 10초 안에 안 오르면 여기서 실패한다
    }

    // 만든 요청을 protobuf 바이트로 바꿔 raw 토픽에 넣는다. .get() = 브로커가 받을 때까지 기다린다
    fun sendToRaw(signal: RawSignal, payload: ByteArray) {
        producer.send(ProducerRecord(RawSignal.TOPIC, signal.key, payload)).get()
    }

    fun attr(key: String, value: String): KeyValue =
        KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()

    // agents 에 줄이 생길 때까지 최대 10초 기다린다 (등록도 소비와 같은 다른 스레드에서 일어난다)
    fun awaitAgent(agentKey: String): Int {
        val deadline = System.currentTimeMillis() + 10_000
        fun rows() = jdbc.queryForObject("SELECT count(*) FROM agents WHERE agent_key = ?", Int::class.java, agentKey)!!
        while (System.currentTimeMillis() < deadline && rows() == 0) Thread.sleep(200)
        return rows()
    }

    Given("raw 토픽을 구독하는 적재 처리기") {
        When("스팬 2개가 든 트레이스 바이트를 키 traces 로 넣으면") {
            val request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(
                    ResourceSpans.newBuilder().addScopeSpans(
                        ScopeSpans.newBuilder()
                            .addSpans(Span.newBuilder().setName("GET /orders"))
                            .addSpans(Span.newBuilder().setName("SELECT orders")),
                    ),
                )
                .build()
            sendToRaw(RawSignal.TRACES, request.toByteArray())

            Then("풀어서 스팬 2건으로 센다") {
                awaitCount(RawSignal.TRACES, 2.0)
            }
        }

        // 아래 두 블록은 같은 방식. 신호와 개수만 다르다
        When("메트릭 1개가 든 바이트를 키 metrics 로 넣으면") {
            val request = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(
                    ResourceMetrics.newBuilder().addScopeMetrics(
                        ScopeMetrics.newBuilder().addMetrics(Metric.newBuilder().setName("jvm.memory.used")),
                    ),
                )
                .build()
            sendToRaw(RawSignal.METRICS, request.toByteArray())

            Then("풀어서 메트릭 1건으로 센다") {
                awaitCount(RawSignal.METRICS, 1.0)
            }
        }

        When("로그 레코드 3개가 든 바이트를 키 logs 로 넣으면") {
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
            sendToRaw(RawSignal.LOGS, request.toByteArray())

            Then("풀어서 로그 레코드 3건으로 센다") {
                awaitCount(RawSignal.LOGS, 3.0)
            }
        }
    }

    Given("감시 대상으로 등록된 서비스의 에이전트") {
        val service = "shop-consumer-${System.nanoTime()}"
        val agentKey = "$service-pod-1"
        jdbc.update("INSERT INTO applications (name) VALUES (?)", service)

        When("그 서비스 이름과 파드 식별자가 담긴 트레이스를 넣으면") {
            val request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(
                    ResourceSpans.newBuilder()
                        .setResource(
                            Resource.newBuilder()
                                .addAttributes(attr("service.name", service))
                                .addAttributes(attr("service.instance.id", agentKey))
                                .addAttributes(attr("host.name", "node-7"))
                                .addAttributes(attr("process.runtime.version", "17.0.9")),
                        )
                        .addScopeSpans(ScopeSpans.newBuilder().addSpans(Span.newBuilder().setName("POST /orders"))),
                )
                .build()
            sendToRaw(RawSignal.TRACES, request.toByteArray())

            Then("적재와 함께 파드가 agents 표에 등록된다") {
                awaitAgent(agentKey) shouldBe 1
            }

            Then("환경 정보도 같이 채워진다") {
                val row = jdbc.queryForMap("SELECT hostname, jvm_version FROM agents WHERE agent_key = ?", agentKey)
                row["hostname"] shouldBe "node-7"
                row["jvm_version"] shouldBe "17.0.9"
            }
        }
    }

    Given("호출 대상이 감시 중인 서비스인 CLIENT 스팬 — 에이전트는 server.address 만 넣고 peer.service 는 안 넣는다") {
        val callee = "shop-order-${System.nanoTime()}" // applications 에 등록된 피호출 서비스
        val traceId = ByteArray(16) { 0x5A }
        jdbc.update("INSERT INTO applications (name) VALUES (?)", callee)
        Thread.sleep(300) // 서비스 목록 캐시(테스트 TTL 200ms)가 새 이름을 보게

        When("server.address 가 그 서비스인 트레이스를 넣으면") {
            val request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(
                    ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder().addAttributes(attr("service.name", "shop-gateway")))
                        .addScopeSpans(
                            ScopeSpans.newBuilder().addSpans(
                                Span.newBuilder()
                                    .setTraceId(ByteString.copyFrom(traceId))
                                    .setSpanId(ByteString.copyFrom(ByteArray(8) { 0x01 }))
                                    .setName("GET /orders")
                                    .setKind(Span.SpanKind.SPAN_KIND_CLIENT)
                                    // 시각을 안 넣으면 1970 년이 되어 spans 표 TTL(93일)에 걸려 CH 가 넣는 즉시 버린다
                                    .setStartTimeUnixNano(System.currentTimeMillis() * 1_000_000)
                                    .setEndTimeUnixNano(System.currentTimeMillis() * 1_000_000 + 5_000_000)
                                    .addAttributes(attr("server.address", callee))
                                    .addAttributes(attr("server.port", "8080")),
                            ),
                        ),
                )
                .build()
            sendToRaw(RawSignal.TRACES, request.toByteArray())

            Then("peer_service 가 서비스 이름으로 채워져 ClickHouse 에 들어간다") {
                val hex = "5a".repeat(16)
                var row: List<String> = emptyList()
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline && row.isEmpty()) {
                    row = clickHouse.queryAll("SELECT peer_address, peer_service FROM monimo.spans WHERE trace_id = '$hex'")
                        .firstOrNull()?.let { listOf(it.getString(1), it.getString(2)) } ?: emptyList()
                    if (row.isEmpty()) Thread.sleep(200)
                }
                row shouldBe listOf("$callee:8080", callee)
            }
        }
    }
})
