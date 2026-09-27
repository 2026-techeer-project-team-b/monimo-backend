package com.monimo.detector.alert.record

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AlertEvaluationStateRepository : JpaRepository<AlertEvaluationStateEntity, Long> {

    // 처음 보는 fingerprint 면 NORMAL 줄을 만든다. 두 탐지가 동시에 만들려 해도 UNIQUE 충돌 대신 한쪽은 아무 일도 안 한다
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO alert_evaluation_states (fingerprint, alert_rule_id, agent_id, phase, rule_version, updated_at)
            VALUES (:fingerprint, :alertRuleId, :agentId, 'NORMAL', :ruleVersion, :now)
            ON CONFLICT (fingerprint) DO NOTHING
        """,
    )
    fun insertIfAbsent(
        @Param("fingerprint") fingerprint: String,
        @Param("alertRuleId") alertRuleId: Long,
        @Param("agentId") agentId: Long?,
        @Param("ruleVersion") ruleVersion: Int,
        @Param("now") now: Instant,
    ): Int

    // SELECT … FOR UPDATE. 같은 fingerprint 를 평가하는 다른 트랜잭션은 커밋될 때까지 여기서 기다린다.
    // ADR #42 가드레일 ①: 선점 · 잠금은 JPA 락 어노테이션이 아니라 네이티브 쿼리로 SQL 을 그대로 드러낸다
    @Query(nativeQuery = true, value = "SELECT * FROM alert_evaluation_states WHERE fingerprint = :fingerprint FOR UPDATE")
    fun lockByFingerprint(@Param("fingerprint") fingerprint: String): AlertEvaluationStateEntity?
}

interface AlertEventRepository : JpaRepository<AlertEventEntity, Long> {
    fun findByAlertEventUuid(alertEventUuid: UUID): AlertEventEntity?
}

interface NotificationOutboxRepository : JpaRepository<NotificationOutboxEntity, Long> {

    // 복구 알림은 발화 알림을 받은 채널로 보낸다 (그 사이 규칙-채널 연결이 바뀌어도 "시작만 받고 끝은 못 받는" 채널이 없게)
    fun findByAlertEventIdAndTransition(alertEventId: Long, transition: OutboxTransition): List<NotificationOutboxEntity>
}
