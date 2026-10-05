package com.monimo.common.kafka // 이 파일의 소속. 폴더 경로와 같아야 한다. 다른 모듈은 import 해서 쓴다

// Kafka raw 토픽의 임시 메시지 형식 
// 값 = 수집기가 받은 OTLP Export*ServiceRequest 의 protobuf 바이트 그대로 · 키 = 신호 이름.
// 적재 처리기는 키를 보고 어느 protobuf 로 풀지 정한다.
//
// 키가 traces · metrics · logs 어느 것도 아닐 때. IllegalArgumentException 의 한 종류지만 타입을 따로 둔 이유 :
// 적재 처리기가 이 예외만 "독성(재시도 소용없음)" 으로 분류한다. IllegalArgumentException 을 통째로 잡으면 변환기의
// 다른 IAE 까지 걸린다. 키는 같은 enum 에서 나오므로 이게 나는 경우는 수집기 · 적재 처리기 배포 버전이 어긋났을 때뿐이다
class UnknownRawKeyException(key: String) : IllegalArgumentException("모르는 raw 키: $key")

enum class RawSignal(val key: String) {//val key는 이 클래스에 들어올 매개변수
  TRACES("traces"), //항목 : TRACES, 위 매개변수 key 값이 traces
  METRICS("metrics"), //항목 : METRICS, 위 매개변수 key 값이 metrics
  LOGS("logs"); //항목 : LOGS, 위 매개변수 key 값이 traclogses

  companion object {//항목 하나가 아닌 RawSignal 클래스 속에서 하나 
    const val TOPIC = "raw" // 토픽 이름 상수. 수집기 · 적재 처리기가 같은 글자를 쓰게 한 곳에

    fun fromKey(key: String): RawSignal = // 결과값으로 RawSignal속 항목을 내보내는 함수
      entries.firstOrNull {it.key == key } ?: throw //entries를 하나하나 보면서 key 가 같은 첫 것 찾는
UnknownRawKeyException(key)// 예외 처리. 전용 타입이라 적재 처리기가 "데이터가 틀렸다" 로 분류할 수 있다 (ADR #51)
  }
}
