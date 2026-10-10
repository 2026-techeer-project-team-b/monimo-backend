package com.monimo.notifier.delivery

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

// 선점한 작업 한 건. 트랜잭션이 끝난 뒤에도 쓰려고 값만 복사해 둔다
data class Claim(
    val outboxId: Long,
    val token: UUID,
    val alertEventId: Long,
    val alertChannelId: Long,
    val payload: Map<String, Any?>,
    val attemptCount: Int,
    val createdAt: Instant,
) {
    // 그룹 키 (D15): 같은 채널 · 같은 서비스
    val groupKey: Pair<Long, String> get() = alertChannelId to (payload["service_name"]?.toString() ?: "")
}

// 짧은 트랜잭션 두 개. 외부 호출은 이 둘 사이, 트랜잭션 밖에서 한다
@Component
class OutboxClaimer(
    private val outbox: OutboxRowRepository,
    private val history: NotificationHistoryRepository,
    private val clock: Clock,
    private val props: DeliveryProperties,
) {

    // ① 선점: 네이티브 FOR UPDATE SKIP LOCKED 로 줄을 잡고 IN_FLIGHT + 임대를 적은 뒤 바로 커밋한다.
    //    커밋하면 행 잠금은 풀리지만, 다른 워커의 조회 조건(PENDING 이거나 임대 만료)에 걸리지 않으므로 가져가지 못한다
    @Transactional
    fun claim(): List<Claim> {
        val now = clock.instant()
        outbox.cancelResolvedBeforeSend(now)
        val rows = outbox.lockDue(now, now.minus(props.groupWait), props.batchSize)
        // 발화 알림이 영구 실패 · 재시도 한도로 끝났는데 복구가 생긴 경우, 사람은 "터졌다"를 못 받은 채 "풀렸다"를 받는다.
        // 복구를 숨기면 장애가 있었다는 사실 자체가 사라지므로 보내되, 발화 알림이 닿지 못했다고 적는다 (D18-보강)
        val resolvedIds = rows.filter { it.transition == "RESOLVED" }.map { it.id }
        val firingFailed = if (resolvedIds.isEmpty()) emptySet() else outbox.resolvedWhoseFiringFailed(resolvedIds).toSet()
        return rows.map { row ->
            val token = UUID.randomUUID()
            row.status = DeliveryStatus.IN_FLIGHT
            row.claimToken = token
            row.leaseUntil = now.plus(props.lease)
            row.updatedAt = now
            val payload = if (row.id in firingFailed) row.payload + (FIRING_UNDELIVERED to true) else row.payload
            Claim(row.id, token, row.alertEventId, row.alertChannelId, payload, row.attemptCount, row.createdAt)
        }
    }

    // ② 결과 기록: 내 claim_token 이 아직 유효할 때만. 끝난 작업(SENT · FAILED)이면 같은 트랜잭션에서 이력 1줄
    @Transactional
    fun finish(claim: Claim, outcome: Finish): Boolean {
        val now = clock.instant()
        val updated = outbox.finishIfOwner(
            id = claim.outboxId,
            token = claim.token,
            status = outcome.status,
            attempted = if (outcome.attempted) 1 else 0,
            nextAttemptAt = outcome.nextAttemptAt ?: now,
            lastError = outcome.error,
            now = now,
        )
        if (updated == 0) return false  // 늦은 완료: 임대가 끝나 다른 워커가 가져갔다. 결과를 버린다
        if (outcome.status == DeliveryStatus.SENT || outcome.status == DeliveryStatus.FAILED) {
            val attempts = claim.attemptCount + (if (outcome.attempted) 1 else 0)
            history.save(
                NotificationHistoryEntity(
                    notificationUuid = UUID.randomUUID(),
                    alertEventId = claim.alertEventId,
                    alertChannelId = claim.alertChannelId,
                    result = if (outcome.status == DeliveryStatus.SENT) "SUCCESS" else "FAIL",
                    retryCount = maxOf(attempts - 1, 0),
                    response = outcome.response ?: outcome.error,
                    sentAt = now,
                    createdAt = now,
                ),
            )
        }
        return true
    }
}

// 작업 한 건을 어떻게 끝낼지. attempted = 실제로 외부를 호출했나 (서킷 OPEN 재예약 · 채널 꺼짐은 false)
data class Finish(
    val status: DeliveryStatus,
    val attempted: Boolean,
    val nextAttemptAt: Instant? = null,
    val response: String? = null,
    val error: String? = null,
)

// 복구 알림 payload 에 붙이는 표시 (DB 에는 쓰지 않는다. 보낼 때만 계산)
const val FIRING_UNDELIVERED = "firing_undelivered"
