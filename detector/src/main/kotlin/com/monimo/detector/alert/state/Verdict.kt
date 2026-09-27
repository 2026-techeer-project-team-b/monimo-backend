package com.monimo.detector.alert.state

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

// alert_rules.operator. 값이 기준선을 "넘으면" 위반이다
enum class AlertOperator {
    GT, GTE, LT, LTE;

    fun isViolated(value: BigDecimal, threshold: BigDecimal): Boolean {
        val cmp = value.compareTo(threshold)
        return when (this) {
            GT -> cmp > 0
            GTE -> cmp >= 0
            LT -> cmp < 0
            LTE -> cmp <= 0
        }
    }
}

enum class UnknownReason {
    QUERY_FAILED,  // API 서버 내부 조회가 실패함
    NO_DATA,       // 버킷에 표본이 없음 (cnt = 0). 오류율 0% 로 치환하지 않는다
    STALE,         // 버킷이 너무 오래됨. 집계 · 수집 지연을 정상으로 오판하지 않으려는 것
}

// 버킷 하나를 판정한 결과. 상태머신은 값 자체가 아니라 이 판정만 본다
sealed interface Verdict {
    val bucketEnd: Instant

    data class Violating(override val bucketEnd: Instant, val value: BigDecimal) : Verdict
    data class Ok(override val bucketEnd: Instant, val value: BigDecimal) : Verdict
    data class Unknown(override val bucketEnd: Instant, val reason: UnknownReason) : Verdict
}

// 규칙 조건 + 신선도 한도로 관측값을 판정한다. value 가 null 이면 데이터 없음
data class Condition(
    val operator: AlertOperator,
    val threshold: BigDecimal,
    val maxStaleness: Duration,
) {
    fun judge(bucketEnd: Instant, value: BigDecimal?, now: Instant): Verdict = when {
        Duration.between(bucketEnd, now) > maxStaleness -> Verdict.Unknown(bucketEnd, UnknownReason.STALE)
        value == null -> Verdict.Unknown(bucketEnd, UnknownReason.NO_DATA)
        operator.isViolated(value, threshold) -> Verdict.Violating(bucketEnd, value)
        else -> Verdict.Ok(bucketEnd, value)
    }
}
