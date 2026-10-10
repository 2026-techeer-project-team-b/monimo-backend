package com.monimo.notifier.delivery

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface OutboxRowRepository : JpaRepository<OutboxRow, Long> {

    // 지금 보낼 작업 + 임대가 끝난(워커가 멈춘) 작업. SKIP LOCKED: 다른 워커가 잡고 있는 줄은 기다리지 않고 건너뛴다.
    // ADR #42 가드레일 ①: 발송 대기 큐 선점은 JPA 락 어노테이션이 아니라 네이티브 FOR UPDATE SKIP LOCKED.
    // 그룹 단위 선점 (docs/alert/50-grouping.md D16 · D18):
    //   ready  = 생긴 지 group_wait 가 지난 줄(또는 임대가 끝난 줄)이 하나라도 있는 (채널, 서비스) 그룹
    //   그 그룹의 보낼 수 있는 줄을 늦게 들어온 형제까지 한꺼번에 잡는다. 묶음 상태는 이 줄들 자체라 재시작해도 남는다 (E9)
    //   RESOLVED 는 같은 사건 · 채널의 FIRING 이 아직 끝나지 않았으면 잡지 않는다 (E10: 복구가 발화보다 먼저 가지 않게)
    // 그룹이 이어서 나오도록 (채널, 서비스, id) 순으로 정렬한다
    @Query(
        nativeQuery = true,
        value = """
            WITH ready AS (
                SELECT DISTINCT alert_channel_id, COALESCE(payload->>'service_name', '') AS svc
                FROM notification_outbox
                WHERE (status = 'PENDING' AND next_attempt_at <= :now AND created_at <= :groupBefore)
                   OR (status = 'IN_FLIGHT' AND lease_until < :now)
            )
            SELECT o.* FROM notification_outbox o
            JOIN ready r ON r.alert_channel_id = o.alert_channel_id AND r.svc = COALESCE(o.payload->>'service_name', '')
            WHERE ((o.status = 'PENDING' AND o.next_attempt_at <= :now) OR (o.status = 'IN_FLIGHT' AND o.lease_until < :now))
              AND NOT (o.transition = 'RESOLVED' AND EXISTS (
                  SELECT 1 FROM notification_outbox f
                  WHERE f.alert_event_id = o.alert_event_id AND f.alert_channel_id = o.alert_channel_id
                    AND f.transition = 'FIRING' AND f.status IN ('PENDING', 'IN_FLIGHT')))
            ORDER BY o.alert_channel_id, COALESCE(o.payload->>'service_name', ''), o.id
            LIMIT :limit
            FOR UPDATE OF o SKIP LOCKED
        """,
    )
    fun lockDue(@Param("now") now: Instant, @Param("groupBefore") groupBefore: Instant, @Param("limit") limit: Int): List<OutboxRow>

    // 발송 전 복구 (D18): 한 번도 보내지 않은 FIRING 에 RESOLVED 가 생겼으면 둘 다 보내지 않는다.
    // 사람에게 "터졌다 · 풀렸다"를 연달아 보내지 않으려는 것. 이력(notification_history)은 남기지 않는다 (CANCELLED 와 같음)
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            WITH pairs AS (
                SELECT f.id AS fid, r.id AS rid
                FROM notification_outbox f
                JOIN notification_outbox r ON r.alert_event_id = f.alert_event_id AND r.alert_channel_id = f.alert_channel_id
                WHERE f.transition = 'FIRING' AND f.status = 'PENDING' AND f.attempt_count = 0
                  AND r.transition = 'RESOLVED' AND r.status = 'PENDING'
                FOR UPDATE OF f, r SKIP LOCKED
            )
            UPDATE notification_outbox SET status = 'CANCELLED', last_error = '발송 전 복구 — 보내지 않음', updated_at = :now
            WHERE id IN (SELECT fid FROM pairs UNION SELECT rid FROM pairs)
        """,
    )
    fun cancelResolvedBeforeSend(@Param("now") now: Instant): Int

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
