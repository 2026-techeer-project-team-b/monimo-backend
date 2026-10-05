package com.monimo.ingester.inbound.kafka

import com.clickhouse.client.api.ConnectionInitiationException // 서버에 닿지 못함 (DNS · 연결 거부 · 연결 타임아웃)
import com.clickhouse.client.api.ServerException // 서버가 응답했고 거절함. getCode() 에 ClickHouse 에러코드
import com.google.protobuf.InvalidProtocolBufferException // Kafka 에서 꺼낸 바이트가 protobuf 가 아님

// 적재 실패 한 건이 세 종류 중 무엇인지 가른다 (ADR #51). 분류가 곧 "얼마나 기다리나" 를 정한다.
enum class FailureClass {
    POISON, // 데이터가 틀렸다. 다시 넣어도 똑같이 실패한다 → 재시도 없이 raw.dlq
    TRANSIENT, // 저장소가 지금 못 받는다. 기다리면 된다 → 10분
    UNKNOWN, // 둘 다 아니다. 안전한 쪽(재시도)으로 기울인다 → 1분
}

// 예외를 받아 FailureClass 를 돌려준다. 상태가 없어 object 다.
//
// 분류를 "뒤집어" 둔 것이 핵심이다 (ADR #51 채택 ④):
//   라이브러리(client-v2)는 "재시도해도 되는 코드 14개" 를 들고 있고 그 밖은 전부 false 를 준다.
//   그걸 그대로 쓰면 모르는 코드가 전부 DLQ 로 가서, ClickHouse 가 새 장애 코드를 추가할 때마다 우리가 알아채서 목록을 고쳐야 한다.
//   그래서 우리는 반대로 "DLQ 로 보낼 코드" 만 들고(POISON_CODES), 거기 없으면 재시도 쪽으로 보낸다.
//   들어야 하는 목록이 "JSON 한 줄을 표에 꽂다가 틀리는 방식" 이라 짧고 잘 안 바뀐다.
//
// 예외는 ListenerExecutionFailedException 으로 한 겹 싸여 오고, 그 안이 ConnectionInitiationException 이고,
// 또 그 안이 UnknownHostException 이다. 그래서 원인 사슬을 따라 내려가며 처음 아는 것을 잡는다.
object FailureClassifier {

    // DLQ 로 보낼 것. 우리가 드는 유일한 목록이다. 전부 "데이터가 틀렸다" 는 뜻의 ClickHouse 에러코드.
    //   117 INCORRECT_DATA · 27 CANNOT_PARSE_INPUT_ASSERTION_FAILED (JSON 구조 자체가 깨짐) · 53 TYPE_MISMATCH
    //   41 CANNOT_PARSE_DATETIME · 72 CANNOT_PARSE_NUMBER
    //   319 UNKNOWN_STATUS_OF_INSERT : 라이브러리는 "재시도해도 됨" 이라 하지만 우리는 뺀다. "넣었는지 모른다" 라서
    //       재시도하면 중복 적재가 되고, spans 에 멱등 키가 없어 집계 MV 가 두 번 센다 (ADR #51 채택 ③)
    val POISON_CODES: Set<Int> = setOf(117, 27, 53, 41, 72, 319)

    // 라이브러리 목록에 없지만 성격상 일시 장애라 긴 쪽으로 올리는 것. 빠뜨려도 UNKNOWN(1분) 으로 떨어지므로 유실은 아니다.
    //   243 NOT_ENOUGH_SPACE (디스크 꽉 참) · 745 SERVER_OVERLOADED · 439 CANNOT_SCHEDULE_TASK · 565 TOO_MANY_PARTITIONS
    val TRANSIENT_CODES: Set<Int> = setOf(243, 745, 439, 565)

    fun classify(failure: Throwable): FailureClass {
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth < MAX_DEPTH) { // 원인 사슬을 따라 내려간다. 순환 방지로 깊이를 제한
            when (current) {
                is InvalidProtocolBufferException -> return FailureClass.POISON // 바이트가 protobuf 가 아니다
                is ConnectionInitiationException -> return FailureClass.TRANSIENT // 서버에 닿지 못했다 (우리가 재현한 바로 그 예외)
                is ServerException -> return classifyServer(current)
            }
            current = current.cause
            depth++
        }
        return FailureClass.UNKNOWN // 아는 게 하나도 안 나왔다. 안전한 쪽으로
    }

    // 서버가 거절한 경우. 코드를 본다. 우리 목록이 라이브러리 판단보다 앞선다 (319 때문)
    private fun classifyServer(e: ServerException): FailureClass = when {
        e.code in POISON_CODES -> FailureClass.POISON
        e.code in TRANSIENT_CODES -> FailureClass.TRANSIENT
        e.isRetryable -> FailureClass.TRANSIENT // 라이브러리가 "재시도해도 됨" 이라 한 나머지 (3 · 107 · 164 · 202 · 203 · 209 · 210 · 241 · 242 · 252 · 285 · 425 · 999)
        else -> FailureClass.UNKNOWN // 모르는 코드. DLQ 가 아니라 재시도 쪽으로 (뒤집기)
    }

    private const val MAX_DEPTH = 10
}
