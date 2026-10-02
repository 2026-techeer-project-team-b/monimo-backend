package com.monimo.api.alert.event

import com.monimo.api.alert.rule.Severity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AlertEventRepository : JpaRepository<AlertEvent, Long> {

    // 제외된 서비스의 사건은 없는 것으로 본다 (규칙 API 와 같게)
    @Query(
        """
        select e from AlertEvent e join fetch e.alertRule r join fetch r.application a
        where e.alertEventUuid = :uuid and a.deletedAt is null
        """,
    )
    fun findActive(@Param("uuid") alertEventUuid: UUID): AlertEvent?

    // 최근 발화 순(fired_at, id 내림차순) 커서 페이징. 인덱스 alert_events_fired_at 과 같은 순서. 필터가 null 이면 걸지 않는다.
    // 시각 인자는 null 로 넘기면 PG 가 타입을 못 정해 실패하므로, 범위 · 커서가 없을 때는 서비스가 끝 값(EventBounds)을 채운다
    @Query(
        """
        select e from AlertEvent e join fetch e.alertRule r join fetch r.application a
        where a.deletedAt is null
          and e.state = :state
          and (:serviceName is null or a.name = :serviceName)
          and (:severity is null or e.severity = :severity)
          and e.firedAt >= :from and e.firedAt < :to
          and (e.firedAt < :afterFiredAt or (e.firedAt = :afterFiredAt and e.id < :afterId))
        order by e.firedAt desc, e.id desc
        """,
    )
    fun findPage(
        @Param("state") state: EventState,
        @Param("serviceName") serviceName: String?,
        @Param("severity") severity: Severity?,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        @Param("afterFiredAt") afterFiredAt: Instant,
        @Param("afterId") afterId: Long,
        pageable: Pageable,
    ): List<AlertEvent>

    // 파드 단위 사건의 파드 UUID · 이름. agents 표(수집 파트)는 Entity 없이 읽기만 한다
    @Query(value = "SELECT id, agent_uuid, agent_key FROM agents WHERE id IN (:ids)", nativeQuery = true)
    fun findAgents(@Param("ids") ids: Collection<Long>): List<Array<Any>>
}

interface NotificationHistoryRepository : JpaRepository<NotificationHistory, Long> {

    fun findByNotificationUuidAndAlertEventId(notificationUuid: UUID, alertEventId: Long): NotificationHistory?

    // 최근 발송 순. 인덱스 notification_history_event (alert_event_id, sent_at DESC, id DESC) 를 그대로 탄다
    @Query(
        """
        select h from NotificationHistory h join fetch h.alertChannel
        where h.alertEventId = :eventId
          and (h.sentAt < :afterSentAt or (h.sentAt = :afterSentAt and h.id < :afterId))
        order by h.sentAt desc, h.id desc
        """,
    )
    fun findPage(
        @Param("eventId") eventId: Long,
        @Param("afterSentAt") afterSentAt: Instant,
        @Param("afterId") afterId: Long,
        pageable: Pageable,
    ): List<NotificationHistory>
}

// 범위 · 커서가 없을 때 쓰는 끝 값. PG timestamptz 가 담을 수 있는 범위 안에서 고른다
object EventBounds {
    val EARLIEST: Instant = Instant.EPOCH
    val LATEST: Instant = Instant.parse("9999-12-31T00:00:00Z")
}

data class AgentRef(val agentUuid: UUID, val agentKey: String)

// agent_id → 파드 UUID · 이름 (목록 N+1 방지)
fun AlertEventRepository.agentsOf(events: Collection<AlertEvent>): Map<Long, AgentRef> {
    val ids = events.mapNotNull { it.agentId }.toSet()
    if (ids.isEmpty()) return emptyMap()
    return findAgents(ids).associate { (it[0] as Number).toLong() to AgentRef(it[1] as UUID, it[2] as String) }
}
