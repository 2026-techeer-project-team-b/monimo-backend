package com.monimo.common.kafka // 이 파일의 소속. 폴더 경로와 같아야 한다. 다른 모듈은 import 해서 쓴다

// Kafka raw 토픽의 임시 메시지 형식 
// 값 = 수집기가 받은 OTLP Export*ServiceRequest 의 protobuf 바이트 그대로 · 키 = 신호 이름.
// 적재 처리기는 키를 보고 어느 protobuf 로 풀지 정한다.
//
enum class RawSignal(val key: String) {//val key는 이 클래스에 들어올 매개변수
  TRACES("traces"), //항목 : TRACES, 위 매개변수 key 값이 traces
  METRICS("metrics"), //항목 : METRICS, 위 매개변수 key 값이 metrics
  LOGS("logs"); //항목 : LOGS, 위 매개변수 key 값이 traclogses

  companion object {//항목 하나가 아닌 RawSignal 클래스 속에서 하나 
    const val TOPIC = "raw" // 토픽 이름 상수. 수집기 · 적재 처리기가 같은 글자를 쓰게 한 곳에

    fun fromKey(key: String): RawSignal = // 결과값으로 RawSignal속 항목을 내보내는 함수
      entries.firstOrNull {it.key == key } ?: throw //entries를 하나하나 보면서 key 가 같은 첫 것 찾는
IllegalArgumentException("모르는 raw 키: $key")// 예외 처리
  }
}
