package com.monimo.collector.outbound.kafka // 나가는 문(outbound) 폴더. 들어오는 문은 inbound

import com.monimo.common.kafka.RawHeaders // 봉투 메모(헤더) 이름 약속
import com.monimo.common.kafka.RawSignal // 우리가 만든 약속(토픽 이름 · 키)
import org.apache.kafka.clients.producer.ProducerRecord // 넣을 메시지 한 개 (토픽 · 키 · 값 · 헤더)
import org.apache.kafka.common.header.internals.RecordHeader // 메모 한 쌍 (이름 = 바이트)
import org.springframework.kafka.core.KafkaTemplate // 스프링 자체에 "Kafka 로 보내는 도구"
import org.springframework.kafka.support.SendResult // 보내기 결과(토픽 · 파티션 · 오프셋)
import org.springframework.stereotype.Component // "스프링이 이 객체를 만들어 관리한다" 표시
import java.util.concurrent.CompletableFuture // 나중에 결과가 들어올 상자

// raw 토픽으로 내보내는 문. 수집기가 데이터를 밖으로 보내는 곳은 여기 하나다.
// 직렬화(String 키 · ByteArray 값)와 acks 는 application.yml 의 producer 설정이 정한다.
@Component//스프링이 시작할 때 하나 만들어 두고 필요한 곳에 넣어 준다(객체 하나를)
class RawProducer(private val kafkaTemplate: KafkaTemplate<String, ByteArray>) { // 괄호 = 생성자. 스프링이 Kafka 도구를 넣어 준다 (private 이라 밖에서는 못 꺼낸다)

    // 보내기를 시작만 하고 바로 돌아온다. 저장이 끝났는지는 돌려준 CompletableFuture 로 확인한다 (OtlpResponder 가 본다)
    //
    // peerAddress = 이 신호를 보낸 파드의 주소. 값(payload)에는 안 넣고 **봉투 메모(헤더)** 로 붙인다 :
    //   값은 받은 protobuf 바이트 그대로여야 한다(ADR `#16`). 안에 끼우려면 풀고 다시 조립해야 하는데
    //   수집기는 지표 · 로그를 아예 열지 않아서 그 둘에 새 비용이 생긴다 (ADR `#63`).
    // null 이면 메모를 아예 안 붙인다. 적재 처리기는 메모가 없으면 주소 칸을 건드리지 않는다
    // 기본값을 두지 않는다 : 깜빡하면 메모가 조용히 빠져 화면 칸이 비는데 그걸 알아챌 방법이 없다
    fun send(signal: RawSignal, payload: ByteArray, peerAddress: String?): CompletableFuture<SendResult<String, ByteArray>> {
        val headers = peerAddress?.let { listOf(RecordHeader(RawHeaders.PEER_ADDRESS, it.toByteArray())) } // 값은 UTF-8 바이트 (Kafka 헤더는 byte[] 다)
        // 파티션 · 타임스탬프는 null 로 둬서 Kafka 가 고르게 한다. 키 · 값은 전과 같다
        return kafkaTemplate.send(ProducerRecord(RawSignal.TOPIC, null, null, signal.key, payload, headers))
    }
}
