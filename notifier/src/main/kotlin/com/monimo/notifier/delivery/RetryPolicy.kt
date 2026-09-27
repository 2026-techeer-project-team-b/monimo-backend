package com.monimo.notifier.delivery

import java.time.Duration
import java.time.Instant
import kotlin.math.min

// 재시도 판단 한 곳. 워커를 sleep 으로 붙잡지 않고 next_attempt_at 으로 "나중에 다시 꺼내라"고 적는다
class RetryPolicy(
    private val base: Duration,
    private val cap: Duration,
    private val maxAttempts: Int,   // 실제 외부 호출 횟수 상한 (첫 호출 포함)
    private val maxAge: Duration,   // 작업이 생긴 뒤 이 시간이 지나면 더 보내지 않는다 (낡은 경보 폭주 방지)
    private val random: () -> Double = Math::random,
) {
    sealed interface Decision {
        data class RetryAt(val at: Instant) : Decision
        data object GiveUp : Decision
    }

    // attempts = 방금 끝난 호출까지 센 실제 호출 수
    fun decide(attempts: Int, createdAt: Instant, now: Instant, retryAfter: Duration?): Decision {
        if (attempts >= maxAttempts) return Decision.GiveUp
        // Full jitter: 0 ~ min(cap, base × 2^(n-1)) 사이 무작위. 여러 워커가 같은 순간 몰려 재시도하지 않게
        val ceilingMs = min(cap.toMillis().toDouble(), base.toMillis() * Math.pow(2.0, (attempts - 1).toDouble()))
        val jitter = Duration.ofMillis((random() * ceilingMs).toLong())
        // 공급자가 Retry-After 를 주면 그보다 일찍 보내지 않는다
        val delay = if (retryAfter != null && retryAfter > jitter) retryAfter else jitter
        val at = now.plus(delay)
        if (Duration.between(createdAt, at) > maxAge) return Decision.GiveUp
        return Decision.RetryAt(at)
    }
}
