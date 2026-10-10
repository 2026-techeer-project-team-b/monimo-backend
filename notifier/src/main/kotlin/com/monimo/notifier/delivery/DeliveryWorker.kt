package com.monimo.notifier.delivery

import com.monimo.notifier.channel.ChannelType
import com.monimo.notifier.channel.NotificationSender
import com.monimo.notifier.channel.OutboundMessage
import com.monimo.notifier.channel.SendResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

// 발송 워커 한 바퀴: 선점(트랜잭션) → 채널 호출(트랜잭션 밖) → 결과 기록(트랜잭션)
@Component
class DeliveryWorker(
    private val claimer: OutboxClaimer,
    private val channels: ChannelRefRepository,
    senders: List<NotificationSender>,
    private val retryPolicy: RetryPolicy,
    private val breaker: ChannelCircuitBreaker,
    private val props: DeliveryProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val senderByType: Map<ChannelType, NotificationSender> = senders.associateBy { it.type }

    // 이번 바퀴에 끝낸 작업 수. 0 이면 할 일이 없었다
    fun pollOnce(): Int {
        val claims = claimer.claim()
        // 그룹(채널, 서비스)마다 한 번 보낸다 (docs/alert/50-grouping.md D17). 너무 크면 maxGroupSize 로 나눈다
        claims.groupBy { it.groupKey }.values
            .flatMap { it.chunked(props.maxGroupSize) }
            .forEach { process(it) }
        return claims.size
    }

    fun process(group: List<Claim>) {
        val finishes = decide(group)
        group.forEach { claim ->
            val finish = finishes.getValue(claim.outboxId)
            val owned = claimer.finish(claim, finish)
            if (!owned) log.warn("늦은 완료 무시: outbox={} 임대가 끝나 다른 워커가 가져갔다 (결과={})", claim.outboxId, finish.status)
        }
    }

    // 그룹 하나 = 채널 호출 1번. 결과 · 재시도 판단 · 서킷은 호출 기준이고, 결과는 줄마다 같은 값으로 적는다
    private fun decide(group: List<Claim>): Map<Long, Finish> {
        fun all(finish: Finish) = group.associate { it.outboxId to finish }
        // 그룹은 같은 채널이다. 보내기 직전에 채널을 다시 본다. 발화 뒤 채널이 꺼졌으면 보내지 않는다
        val channel = channels.findById(group.first().alertChannelId).orElse(null)
        if (channel == null || !channel.enabled) {
            return all(Finish(DeliveryStatus.CANCELLED, attempted = false, error = "채널이 꺼져 있음"))
        }
        val sender = senderByType[ChannelType.valueOf(channel.type)]
            // 어댑터가 없는 채널을 성공으로 치지 않는다 (FN-30: Slack 우선, 나머지는 이후)
            ?: return all(Finish(DeliveryStatus.FAILED, attempted = false, error = "${channel.type} 어댑터 미구현"))

        // 서킷 OPEN 이면 호출하지 않고 재예약한다. 실제 호출이 아니므로 시도 횟수를 올리지 않는다 (E8)
        val permit = breaker.acquire(channel.id)
        if (permit is ChannelCircuitBreaker.Permit.Rejected) return group.associate { it.outboxId to deferred(it, permit) }

        val result = sender.send(OutboundMessage(group.map { it.payload }), channel.config)
        when (result) {
            is SendResult.Accepted, is SendResult.Permanent -> breaker.onResponded(channel.id)
            is SendResult.Retryable, is SendResult.Unknown -> breaker.onFailure(channel.id)
        }
        return when (result) {
            is SendResult.Accepted -> all(Finish(DeliveryStatus.SENT, attempted = true, response = result.response))
            is SendResult.Permanent -> all(Finish(DeliveryStatus.FAILED, attempted = true, error = result.reason))
            // Unknown 도 다시 보낸다: 경보는 "안 간 것"이 "두 번 간 것"보다 나쁘다고 본다 (중복 가능성은 docs/alert/30-delivery.md)
            is SendResult.Retryable, is SendResult.Unknown -> {
                val reason = if (result is SendResult.Retryable) result.reason else (result as SendResult.Unknown).reason
                val retryAfter = (result as? SendResult.Retryable)?.retryAfter
                // 그룹이 다음에도 같이 나가도록 한 번만 판단한다: 가장 많이 시도한 줄 · 가장 오래된 줄 기준
                val attempts = group.maxOf { it.attemptCount } + 1
                val oldest = group.minOf { it.createdAt }
                when (val d = retryPolicy.decide(attempts, oldest, clock.instant(), retryAfter)) {
                    is RetryPolicy.Decision.RetryAt -> all(Finish(DeliveryStatus.PENDING, attempted = true, nextAttemptAt = d.at, error = reason))
                    RetryPolicy.Decision.GiveUp -> all(Finish(DeliveryStatus.FAILED, attempted = true, error = "재시도 한도 도달: $reason"))
                }
            }
        }
    }

    // OPEN 중에도 max-age 는 지킨다. 채널이 살아났을 때 낡은 경보가 한꺼번에 쏟아지지 않게
    private fun deferred(claim: Claim, permit: ChannelCircuitBreaker.Permit.Rejected): Finish =
        if (Duration.between(claim.createdAt, permit.retryAt) > props.maxAge) {
            Finish(DeliveryStatus.FAILED, attempted = false, error = "서킷 ${permit.state} 중 최대 나이 초과")
        } else {
            Finish(DeliveryStatus.PENDING, attempted = false, nextAttemptAt = permit.retryAt, error = "서킷 ${permit.state}: 채널 연속 실패로 호출 보류")
        }
}
