package com.monimo.collector.outbound.kafka // 나가는 문(outbound) 폴더. 들어오는 문은 inbound

import com.monimo.common.kafka.RawSignal // 우리가 만든 약속(토픽 이름 · 키)
import org.springframework.kafka.core.KafkaTemplate // 스프링 자체에 "Kafka 로 보내는 도구"
import org.springframework.kafka.support.SendResult // 보내기 결과(토픽 · 파티션 · 오프셋)
import org.springframework.stereotype.Component // "스프링이 이 객체를 만들어 관리한다" 표시
import java.util.concurrent.CompletableFuture // 나중에 결과가 들어올 상자

// raw 토픽으로 내보내는 문. 수집기가 데이터를 밖으로 보내는 곳은 여기 하나다.
// 직렬화(String 키 · ByteArray 값)와 acks 는 application.yml 의 producer 설정이 정한다.
@Component//스프링이 시작할 때 하나 만들어 두고 필요한 곳에 넣어 준다(객체 하나를)
class RawProducer(private val kafkaTemplate: KafkaTemplate<String, ByteArray>) { // 괄호 = 생성자. 스프링이 Kafka 도구를 넣어 준다 (private 이라 밖에서는 못 꺼낸다)

    // 보내기를 시작만 하고 바로 돌아온다. 저장이 끝났는지는 돌려준 CompletableFuture 로 확인한다 (OtlpResponder 가 본다)
    fun send(signal: RawSignal, payload: ByteArray): CompletableFuture<SendResult<String, ByteArray>> // 반환 값 타입  
    = kafkaTemplate.send(RawSignal.TOPIC, signal.key, payload)//토픽(raw), 키(3개 목록), 값 순서 + 진짜 반환 값
}
