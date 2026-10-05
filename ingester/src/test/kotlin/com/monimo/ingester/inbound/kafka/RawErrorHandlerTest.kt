package com.monimo.ingester.inbound.kafka

import com.monimo.common.kafka.RawSignal
import com.monimo.ingester.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.kafka.support.KafkaHeaders
import java.nio.ByteBuffer
import java.time.Duration

// 진짜 Kafka 로 "독성 메시지는 재시도 없이 raw.dlq 로 가고, 뒤가 막히지 않는다" 를 본다 (ADR #51).
// 일시 장애(ClickHouse 정지 → 10분 대기 → 복구) 는 컨테이너를 멈춰야 해서 여기서 안 하고 가이드의 수동 확인 2번에서 본다.
@SpringBootTest
@Import(TestInfraConfig::class)
class RawErrorHandlerTest(
    environment: Environment,
    dlqCounter: DlqCounter,
    consumeCounter: RawConsumeCounter,
) : BehaviorSpec({

    val bootstrap = environment.getRequiredProperty("spring.kafka.bootstrap-servers")
    // 토픽은 TestInfraConfig 가 compose 와 같은 모양(raw 3 · raw.dlq 1)으로 만들어 뒀다

    val producer = KafkaProducer<String, ByteArray>(
        mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java,
        ),
    )
    // raw.dlq 를 읽는 쪽. 적재 처리기와 다른 group-id 를 써야 책갈피가 섞이지 않는다
    val dlqReader = KafkaConsumer<String, ByteArray>(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG to "dlq-test-reader",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
        ),
    ).apply { subscribe(listOf("raw.dlq")) }
    afterSpec { producer.close(); dlqReader.close() }

    // raw.dlq 에 레코드가 올 때까지 최대 20초 기다린다
    fun awaitDlq(): ConsumerRecord<String, ByteArray>? {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val records = dlqReader.poll(Duration.ofMillis(500))
            if (!records.isEmpty) return records.first()
        }
        return null
    }

    fun awaitConsumed(signal: RawSignal, expected: Double) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && consumeCounter.count(signal) < expected) Thread.sleep(200)
        consumeCounter.count(signal) shouldBe expected
    }

    fun header(record: ConsumerRecord<*, *>, key: String): String? = record.headers().lastHeader(key)?.value()?.let { String(it) }

    Given("에러 핸들러가 붙은 적재 처리기") {

        When("키는 traces 인데 값이 protobuf 가 아닌 바이트를 raw 의 파티션 1 에 넣으면") {
            // 0x0A = 1번 필드 · 길이 있는 타입, 0x7F = 길이 127 이라고 선언. 그런데 뒤에 바이트가 없다 → 잘린 메시지 → 파싱 실패.
            // 파티션 1 로 보내는 이유 : raw.dlq 는 파티션이 1개(0번)뿐이라 "원본과 같은 번호" 가 없다. verifyPartition 이 그걸 비워
            // Kafka 가 고르게 해야 들어간다. 처음 구현은 이 경로가 깨져 있었다
            val poison = byteArrayOf(0x0A, 0x7F)
            val before = dlqCounter.count(FailureClass.POISON)
            producer.send(ProducerRecord(RawSignal.TOPIC, 1, RawSignal.TRACES.key, poison)).get()
            val dlqRecord = awaitDlq()

            Then("재시도 없이 raw.dlq 로 간다") {
                dlqRecord.shouldNotBeNull()
                dlqRecord.topic() shouldBe "raw.dlq"
                dlqRecord.key() shouldBe RawSignal.TRACES.key // 키 · 값은 꺼낸 그대로
                dlqRecord.value().toList() shouldBe poison.toList()
            }

            Then("원본 파티션은 1 이었고 raw.dlq 에서는 0 번에 들어갔다 : verifyPartition 이 번호를 비워 Kafka 가 골랐다") {
                ByteBuffer.wrap(dlqRecord!!.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value()).int shouldBe 1
                dlqRecord.partition() shouldBe 0
            }

            Then("원본이 어디서 왔는지 자동 헤더가 붙는다") {
                header(dlqRecord!!, KafkaHeaders.DLT_ORIGINAL_TOPIC) shouldBe RawSignal.TOPIC
                header(dlqRecord, KafkaHeaders.DLT_ORIGINAL_OFFSET).shouldNotBeNull()
                header(dlqRecord, KafkaHeaders.DLT_EXCEPTION_FQCN).shouldNotBeNull()
            }

            Then("우리 헤더 둘이 붙는다 : 처음이라 x-dlq-attempt 는 1, 분류는 poison") {
                header(dlqRecord!!, RawErrorHandlerConfig.HEADER_ATTEMPT) shouldBe "1"
                header(dlqRecord, RawErrorHandlerConfig.HEADER_REASON) shouldBe "poison"
            }

            Then("DLQ 카운터가 poison 으로 1 오른다") {
                dlqCounter.count(FailureClass.POISON) shouldBe before + 1
            }
        }

        When("그 뒤에 정상 트레이스를 넣으면") {
            val before = consumeCounter.count(RawSignal.TRACES)
            val request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder().addScopeSpans(ScopeSpans.newBuilder().addSpans(Span.newBuilder().setName("GET /after-poison"))))
                .build()
            producer.send(ProducerRecord(RawSignal.TOPIC, RawSignal.TRACES.key, request.toByteArray())).get()

            Then("독성 하나가 뒤를 막지 않고 정상 소비된다") {
                awaitConsumed(RawSignal.TRACES, before + 1)
            }
        }
    }
})
