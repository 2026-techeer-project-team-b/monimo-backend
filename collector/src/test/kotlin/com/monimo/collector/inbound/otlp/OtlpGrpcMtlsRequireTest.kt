package com.monimo.collector.inbound.otlp

import com.monimo.collector.support.OtlpTestClient
import com.monimo.collector.support.TestCerts
import com.monimo.collector.support.TestCertsConfig
import com.monimo.collector.support.TestInfraConfig
import com.monimo.common.kafka.RawHeaders
import com.monimo.common.kafka.RawSignal
import io.grpc.Status
import io.kotest.assertions.nondeterministic.eventually
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
import kotlin.time.Duration.Companion.seconds

// 요구 수준 require (#159, 전환 2단계) : 우리 기관 도장이 찍힌 신분증을 낸 손님만 통과한다.
// 신분증이 없거나 · 다른 기관 도장이거나 · 기간이 지났으면 악수 단계에서 끊긴다. 통과한 손님의 세 신호는 평문 때와 똑같이
// raw 토픽에 그대로 들어가고 `#149` 의 주소 메모도 그대로 붙는다 : TLS 를 수집기가 직접 끝내므로 상대 주소가 바뀌지 않는다
@SpringBootTest(
    properties = [
        "monimo.collector.otlp.grpc.port=0",
        "monimo.collector.otlp.grpc.tls.client-auth=require",
        "monimo.collector.sampling.ratio=1.0",
        "monimo.collector.health-check.paths=",
    ],
)
@Import(TestInfraConfig::class, TestCertsConfig::class)
class OtlpGrpcMtlsRequireTest(
    server: OtlpGrpcServer,
    gauge: OtlpConnectionGauge,
    environment: Environment,
) : BehaviorSpec({

    val ours = TestCerts.ours
    val loopback = listOf("127.0.0.1", "0:0:0:0:0:0:0:1")

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

    // 한 손님이 세 신호를 잇달아 보내므로 한 번의 poll 에 세 메시지가 같이 올 수 있다. 읽은 것을 버리지 않고 모아 두고 키로 찾는다
    val pending = ArrayDeque<ConsumerRecord<String, ByteArray>>()
    fun receivedFromRaw(signal: RawSignal): ConsumerRecord<String, ByteArray> {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            pending.firstOrNull { it.key() == signal.key }?.let { pending.remove(it); return it }
            consumer.poll(Duration.ofMillis(500)).forEach { pending.addLast(it) }
        }
        error("raw 토픽에 ${signal.key} 메시지가 10초 안에 안 옴")
    }

    fun peerAddressOf(record: ConsumerRecord<String, ByteArray>): String? =
        record.headers().lastHeader(RawHeaders.PEER_ADDRESS)?.value()?.toString(Charsets.UTF_8)

    Given("손님 신분증을 반드시 요구하는 수집기 (require)") {

        When("우리 기관 신분증(shop-order)을 낸 손님이 세 신호를 보내면") {
            val channel = OtlpTestClient.tls(server.port, ours.ca, ours.clientCertificate("shop-order"), ours.clientKey("shop-order"))

            val traces = OtlpTestClient.oneSpan()
            TraceServiceGrpc.newBlockingStub(channel).export(traces)
            val metrics = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(
                    ResourceMetrics.newBuilder().addScopeMetrics(
                        ScopeMetrics.newBuilder().addMetrics(Metric.newBuilder().setName("jvm.memory.used")),
                    ),
                )
                .build()
            MetricsServiceGrpc.newBlockingStub(channel).export(metrics)
            val logs = ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addLogRecords(LogRecord.newBuilder())))
                .build()
            LogsServiceGrpc.newBlockingStub(channel).export(logs)

            val received = RawSignal.entries.associateWith { receivedFromRaw(it) }

            Then("세 신호가 raw 토픽에 받은 protobuf 그대로 들어간다 : 평문 때와 같다") {
                ExportTraceServiceRequest.parseFrom(received.getValue(RawSignal.TRACES).value()) shouldBe traces
                ExportMetricsServiceRequest.parseFrom(received.getValue(RawSignal.METRICS).value()) shouldBe metrics
                ExportLogsServiceRequest.parseFrom(received.getValue(RawSignal.LOGS).value()) shouldBe logs
            }

            Then("#149 의 주소 메모가 세 신호에 그대로 붙는다 : TLS 를 켜도 수집기가 보는 상대 주소는 손님 주소다") {
                received.values.forEach { loopback shouldContain peerAddressOf(it) }
            }

            Then("붙어 있는 수 게이지의 mtls 칸이 연결 1 · 에이전트 1 이고, 연결을 끊으면 0 으로 돌아온다") {
                gauge.connections(OtlpConnectionGauge.Auth.MTLS) shouldBe 1
                gauge.agents(OtlpConnectionGauge.Auth.MTLS) shouldBe 1
                gauge.connections(OtlpConnectionGauge.Auth.TLS) shouldBe 0
                gauge.connections(OtlpConnectionGauge.Auth.PLAINTEXT) shouldBe 0
                channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS)
                // 끊김은 서버 쪽에서 비동기로 알아챈다
                eventually(5.seconds) {
                    gauge.connections(OtlpConnectionGauge.Auth.MTLS) shouldBe 0
                    gauge.agents(OtlpConnectionGauge.Auth.MTLS) shouldBe 0
                }
            }
        }

        When("신분증 없이 TLS 로만 붙는 손님이 보내면") {
            val channel = OtlpTestClient.tls(server.port, ours.ca)
            val rejected = OtlpTestClient.rejectedWith(channel)
            channel.shutdownNow()

            Then("악수 단계에서 끊긴다 (UNAVAILABLE)") {
                rejected shouldBe Status.Code.UNAVAILABLE
            }
        }

        When("다른 기관 도장이 찍힌 신분증을 낸 손님이 보내면") {
            val other = TestCerts.other
            // 수집기 신분증은 우리 기관 것이라 손님 쪽 검사(trust)는 우리 기관으로, 손님이 내는 신분증만 다른 기관 것으로 둔다
            val channel = OtlpTestClient.tls(server.port, ours.ca, other.clientCertificate("shop-order"), other.clientKey("shop-order"))
            val rejected = OtlpTestClient.rejectedWith(channel)
            channel.shutdownNow()

            Then("악수 단계에서 끊긴다 : 이름이 같아도(shop-order) 도장이 다르면 안 된다") {
                rejected shouldBe Status.Code.UNAVAILABLE
            }
        }

        When("기간이 지난 신분증(expired)을 낸 손님이 보내면") {
            val channel = OtlpTestClient.tls(server.port, ours.ca, ours.clientCertificate("expired"), ours.clientKey("expired"))
            val rejected = OtlpTestClient.rejectedWith(channel)
            channel.shutdownNow()

            Then("악수 단계에서 끊긴다 : 우리 기관 도장이어도 기간이 지나면 안 된다. 갱신은 파일을 바꾸고 재시작이다") {
                rejected shouldBe Status.Code.UNAVAILABLE
            }
        }

        When("평문으로 붙는 손님이 보내면 (TLS 켜기 전 설정 그대로인 에이전트)") {
            val channel = OtlpTestClient.plaintext(server.port)
            val rejected = OtlpTestClient.rejectedWith(channel)
            channel.shutdownNow()

            Then("끊긴다 : 수집기가 TLS 로 바뀌면 평문 에이전트는 설정을 바꾸기 전까지 못 붙는다") {
                rejected shouldBe Status.Code.UNAVAILABLE
            }
        }

        When("거절당한 손님들이 지나간 뒤") {
            Then("게이지는 한 칸도 안 움직였다 : 악수에서 끊긴 연결은 준비된 적이 없어 세지 않는다. 거절은 이 게이지로 못 센다") {
                OtlpConnectionGauge.Auth.entries.forEach { gauge.connections(it) shouldBe 0; gauge.agents(it) shouldBe 0 }
            }
        }
    }
})
