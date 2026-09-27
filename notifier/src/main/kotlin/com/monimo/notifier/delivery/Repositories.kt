package com.monimo.notifier.delivery

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface OutboxRowRepository : JpaRepository<OutboxRow, Long> {

    // 지금 보낼 작업 + 임대가 끝난(워커가 멈춘) 작업. SKIP LOCKED: 다른 워커가 잡고 있는 줄은 기다리지 않고 건너뛴다.
    // ADR #42 가드레일 ①: 발송 대기 큐 선점은 JPA 락 어노테이션이 아니라 네이티브 FOR UPDATE SKIP LOCKED
    @Query(
        nativeQuery = true,
        value = """
            SELECT * FROM notification_outbox
            WHERE (status = 'PENDING' AND next_attempt_at <= :now)
               OR (status = 'IN_FLIGHT' AND lease_until < :now)
            ORDER BY next_attempt_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
    )
    fun lockDue(@Param("now") now: Instant, @Param("limit") limit: Int): List<OutboxRow>

    // 결과 기록. 내가 잡은 임대(claim_token)가 아직 유효할 때만 바뀐다. 0 이면 임대가 끝나 다른 워커가 가져간 것
    @Modifying
    @Query(
        "UPDATE OutboxRow o SET o.status = :status, o.attemptCount = o.attemptCount + :attempted, " +
            "o.nextAttemptAt = :nextAttemptAt, o.lastError = :lastError, o.leaseUntil = null, o.claimToken = null, o.updatedAt = :now " +
            "WHERE o.id = :id AND o.claimToken = :token AND o.status = com.monimo.notifier.delivery.DeliveryStatus.IN_FLIGHT",
    )
    fun finishIfOwner(
        @Param("id") id: Long,
        @Param("token") token: UUID,
        @Param("status") status: DeliveryStatus,
        @Param("attempted") attempted: Int,
        @Param("nextAttemptAt") nextAttemptAt: Instant,
        @Param("lastError") lastError: String?,
        @Param("now") now: Instant,
    ): Int

    @Query("SELECT count(o) FROM OutboxRow o WHERE o.status IN :statuses")
    fun countByStatusIn(@Param("statuses") statuses: Collection<DeliveryStatus>): Long

    @Query("SELECT min(o.createdAt) FROM OutboxRow o WHERE o.status IN :statuses")
    fun oldestCreatedAt(@Param("statuses") statuses: Collection<DeliveryStatus>): Instant?
}

interface NotificationHistoryRepository : JpaRepository<NotificationHistoryEntity, Long>

interface ChannelRefRepository : JpaRepository<ChannelRef, Long>
