package com.monimo.collector.outbound.kafka

import com.monimo.common.kafka.RawSignal
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

// raw 토픽으로 내보내는 문. 수집기가 데이터를 밖으로 보내는 곳은 여기 하나다.
// 직렬화(String 키 · ByteArray 값)와 acks 는 application.yml 의 producer 설정이 정한다.
@Component
class RawProducer(private val kafkaTemplate: KafkaTemplate<String, ByteArray>) {

    // 보내기를 시작만 하고 바로 돌아온다. 저장이 끝났는지는 돌려준 CompletableFuture 로 확인한다 (OtlpResponder 가 본다)
    fun send(signal: RawSignal, payload: ByteArray): CompletableFuture<SendResult<String, ByteArray>> =
        kafkaTemplate.send(RawSignal.TOPIC, signal.key, payload)
}
