package com.monimo.collector.inbound.otlp

import com.monimo.collector.support.TestInfraConfig
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
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.concurrent.TimeUnit

// 연결 약속 검증: OTLP gRPC 로 보낸 스팬 · 메트릭 · 로그를 수집기가 받아 센다.
// 포트 0 = 빈 포트 아무거나. 테스트끼리 · 로컬 4317 과 겹치지 않는다.
@SpringBootTest(properties = ["monimo.collector.otlp.grpc.port=0"])
@Import(TestInfraConfig::class)
class OtlpGrpcReceiverTest(
    server: OtlpGrpcServer,
    counter: OtlpReceiveCounter,
) : BehaviorSpec({

    val channel = ManagedChannelBuilder.forAddress("localhost", server.port).usePlaintext().build()
    afterSpec { channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS) }

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
        }
    }
})
