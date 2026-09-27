package com.monimo.common.kafka

// Kafka raw 토픽의 임시 메시지 형식 (개발환경 9단계에서 확정한다).
// 값 = 수집기가 받은 OTLP Export*ServiceRequest 의 protobuf 바이트 그대로 · 키 = 신호 이름.
// 적재 처리기는 키를 보고 어느 protobuf 로 풀지 정한다.
enum class RawSignal(val key: String) {
    TRACES("traces"),
    METRICS("metrics"),
    LOGS("logs");

    companion object {
        const val TOPIC = "raw"

        // Kafka 에서 읽은 키(글자)를 항목으로 되돌린다. 모르는 키면 바로 실패시킨다 (조용히 넘기지 않는다)
        fun fromKey(key: String): RawSignal =
            entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("모르는 raw 키: $key")
    }
}
