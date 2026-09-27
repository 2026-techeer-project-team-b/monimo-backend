package com.monimo.collector.inbound.otlp

import com.monimo.collector.support.TestInfraConfig
import com.monimo.common.kafka.RawSignal
import io.grpc.ManagedChannelBuilder
import io.kotest.core.spec.style.BehaviorSpec
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
// 포트 0 = 빈 포트 아무거나. 테스트끼리 · 로컬 4317 과 겹치지 않는다.
@SpringBootTest(properties = ["monimo.collector.otlp.grpc.port=0"])
@Import(TestInfraConfig::class)
class OtlpGrpcReceiverTest(
    server: OtlpGrpcServer,
    counter: OtlpReceiveCounter,
    environment: Environment,
) : BehaviorSpec({

    val channel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
    afterSpec { channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS) }

    // raw 토픽을 읽는 시험용 소비자. 적재 처리기가 할 일을 여기서 흉내 낸다
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

    // 키가 signal 인 메시지가 올 때까지 최대 10초 기다린다
    fun receivedFromRaw(signal: RawSignal): ByteArray {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(500)).forEach { if (it.key() == signal.key) return it.value() }
        }
        error("raw 토픽에 ${signal.key} 메시지가 10초 안에 안 옴")
    }

    Given("OTLP gRPC 수신을 켠 수집기") {
        When("스팬 2개를 TraceService.Export 로 보내면") {
            val before = counter.count(OtlpReceiveCounter.Signal.TRACES)
            val request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(
                    ResourceSpans.newBuilder().addScopeSpans(
                        ScopeSpans.newBuilder()
                            .addSpans(Span.newBuilder().setName("GET /orders"))
                            .addSpans(Span.newBuilder().setName("SELECT orders")),
                    ),
                )
                .build()
            TraceServiceGrpc.newBlockingStub(channel).export(request)

            Then("traces 받은 건수가 2 늘어난다") {
                counter.count(OtlpReceiveCounter.Signal.TRACES) - before shouldBe 2.0
            }

            Then("raw 토픽에 키 traces · 값은 받은 protobuf 그대로 들어간다") {
                ExportTraceServiceRequest.parseFrom(receivedFromRaw(RawSignal.TRACES)) shouldBe request
            }
        }

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

            Then("raw 토픽에 키 metrics · 값은 받은 protobuf 그대로 들어간다") {
                ExportMetricsServiceRequest.parseFrom(receivedFromRaw(RawSignal.METRICS)) shouldBe request
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

            Then("raw 토픽에 키 logs · 값은 받은 protobuf 그대로 들어간다") {
                ExportLogsServiceRequest.parseFrom(receivedFromRaw(RawSignal.LOGS)) shouldBe request
            }
        }
    }
})
