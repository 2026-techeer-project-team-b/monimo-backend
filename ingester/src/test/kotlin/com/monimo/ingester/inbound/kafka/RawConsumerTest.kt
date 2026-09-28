package com.monimo.ingester.inbound.kafka

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
import io.opentelemetry.proto.metrics.v1.ScopeMetrics
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

// 수집기 테스트가 소비자를 만들어 확인했다면, 여기서는 반대로 생산자를 만들어 넣는다.
// 수집기가 넣는 것과 같은 모양(키 = 신호 글자 · 값 = protobuf 바이트)으로 직접 넣고, 적재 처리기가 풀어 세는지 본다.
@SpringBootTest
@Import(TestInfraConfig::class) // 진짜 Kafka · ClickHouse · PostgreSQL 컨테이너를 띄워 붙인다
class RawConsumerTest(
    counter: RawConsumeCounter, // 카운터 빈을 생성자로 받아 값을 확인한다
    environment: Environment, // Kafka 주소를 읽는다 (컨테이너라 주소가 매번 다르다)
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
})
