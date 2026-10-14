package com.monimo.ingester.job.dlq

import com.monimo.common.kafka.RawHeaders
import com.monimo.ingester.inbound.kafka.FailureClass
import com.monimo.ingester.inbound.kafka.RawErrorHandlerConfig
import org.apache.kafka.common.header.Header
import org.apache.kafka.common.header.Headers

// 레코드 하나를 보고 "되넣을까 · 건너뛸까 · 멈출까" 와 "머리말 중 무엇을 가져갈까" 를 정하는 곳.
//
// 스프링도 Kafka 연결도 모르는 순수 코드로 떼어 둔 이유 : 이 판정이 이 잡의 주장 전부인데
// 컨테이너를 띄워 시험하면 느리고, 토폴로지가 실제와 달라 거짓 안심을 준다(#96 에서 실제로 그랬다).
// RawErrorHandlerConfig.backOffFor 를 companion 으로 꺼내 둔 것과 같은 이유다.
object DlqReprocessPlan {

    // 가져갈 머리말 셋. 나머지(kafka_dlt-* 열 장)는 버린다.
    //
    //   x-dlq-attempt         회차를 넘어 **이어져야 하는 유일한 값.** 버리면 횟수가 매번 1 로 돌아가
    //                         영원히 왕복한다 (실측 : 가져가면 attempt=2 · 버리면 attempt=1)
    //   x-dlq-reason          독성인지 보고 건너뛸 단서
    //   network.peer.address  수집기가 gRPC 연결에서 읽어 붙인 보낸 쪽 주소 (ADR #63). 데이터 안에 없어서
    //                         버리면 복원할 데가 없다. 다만 그 메시지로 **처음 등록되는 파드**만 영향을 받는다 :
    //                         이미 등록된 파드는 PostgresAgentRegistry.needsWrite() 가 멀쩡한 주소를 지키게 해 둔다
    //
    // 버리는 이유 셋 : ① 적재 처리기가 읽지 않는다 ② 다시 실패하면 스프링이 새로 붙여 준다
    // ③ 멀쩡한 raw 토픽에 스택 추적이 7일 동안 실려 다닌다 (실측 : 값 406바이트에 머리말 6,033바이트,
    //    그중 스택이 5,144바이트. 셋만 가져가면 65바이트가 된다)
    val KEPT_HEADERS: Set<String> = setOf(
        RawErrorHandlerConfig.HEADER_ATTEMPT,
        RawErrorHandlerConfig.HEADER_REASON,
        RawHeaders.PEER_ADDRESS,
    )

    // 되넣기 전에 내리는 판정. 셋뿐이다
    enum class Decision {
        REPLAY, // raw 로 다시 보낸다
        SKIP_POISON, // 데이터가 틀린 것이라 되넣어도 또 실패한다. 건너뛰고 커밋한다 (그 데이터는 복구 불가다)
        STOP_EXHAUSTED, // 너무 여러 번 왕복했다. 커밋하지 않고 그 자리에서 끝낸다 = 사람을 부른다
    }

    fun decide(reason: String?, attempt: Int, maxAttempt: Int): Decision = when {
        // 횟수를 먼저 본다. 독성이 아니어도 계속 실패하는 것은 사람이 봐야 한다
        attempt >= maxAttempt -> Decision.STOP_EXHAUSTED
        reason == POISON -> Decision.SKIP_POISON
        else -> Decision.REPLAY
    }

    // 몇 번째인가. 머리말이 없으면 0 이다 (수집기가 넣은 평범한 메시지 · 손으로 넣은 메시지)
    fun attemptOf(headers: Headers): Int =
        headers.lastHeader(RawErrorHandlerConfig.HEADER_ATTEMPT)?.value()?.let { String(it).toIntOrNull() } ?: 0

    // 어느 분류로 왔나. poison · transient · unknown 중 하나이거나 null
    fun reasonOf(headers: Headers): String? =
        headers.lastHeader(RawErrorHandlerConfig.HEADER_REASON)?.value()?.let { String(it) }

    // 가져갈 머리말만 고른다. 순서는 원래 순서를 지킨다
    fun keep(headers: Headers): List<Header> = headers.filter { it.key() in KEPT_HEADERS }

    // 이 메시지가 **원래 raw 에 발행된** 시각. 되넣은 구간을 찍을 때 쓴다.
    //
    // DLQ 레코드 자신의 timestamp 를 쓰면 안 된다 : 그것은 DLQ 로 **옮겨 적은** 시각이라
    // 실패하기까지 기다린 시간(일시 장애면 최대 10분)만큼 뒤로 밀려 있다. 실측으로 46분이 벌어졌다
    // (원래 발행 19:32 · DLQ 기록 20:18). 그 시각으로 재집계 구간을 잡으면 **엉뚱한 분**을 보게 된다.
    //
    // 스프링이 넣어 주는 값이고 **8바이트 정수**다(글자가 아니다). 길이가 8 이 아니면 안 읽은 것으로 본다.
    // 이것도 데이터 안의 시각은 아니다 : 진짜 시각은 protobuf 안에 있고 여기서 풀지 않는다.
    // 에이전트 배치 전송 주기(기본 5초)만큼 차이가 날 수 있어 **구간을 넉넉히** 잡으라고 로그에 적는다
    fun originalTimestamp(headers: Headers): Long? =
        headers.lastHeader(HEADER_ORIGINAL_TS)?.value()
            ?.takeIf { it.size == Long.SIZE_BYTES }
            ?.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

    const val HEADER_ORIGINAL_TS = "kafka_dlt-original-timestamp" // 스프링 DeadLetterPublishingRecoverer 가 붙이는 이름

    private val POISON = FailureClass.POISON.name.lowercase() // RawErrorHandlerConfig 가 적는 글자와 같은 식으로 만든다
}
