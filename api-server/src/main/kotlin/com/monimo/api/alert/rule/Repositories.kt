package com.monimo.api.alert.rule

import com.monimo.api.alert.channel.AlertChannel
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AlertRuleRepository : JpaRepository<AlertRule, Long> {

    // 제외된 서비스의 규칙은 없는 것으로 본다
    @Query(
        """
        select r from AlertRule r join fetch r.application a
        where r.alertRuleUuid = :uuid and a.deletedAt is null
        """,
    )
    fun findActive(@Param("uuid") alertRuleUuid: UUID): AlertRule?

    // 최신 생성 순(id 내림차순) 커서 페이징. 필터가 null 이면 걸지 않는다
    @Query(
        """
        select r from AlertRule r join fetch r.application a
        where a.deletedAt is null
          and (:serviceName is null or a.name = :serviceName)
          and (:enabled is null or r.enabled = :enabled)
          and (:severity is null or r.severity = :severity)
          and (:beforeId is null or r.id < :beforeId)
        order by r.id desc
        """,
    )
    fun findPage(
        @Param("serviceName") serviceName: String?,
        @Param("enabled") enabled: Boolean?,
        @Param("severity") severity: Severity?,
        @Param("beforeId") beforeId: Long?,
        pageable: Pageable,
    ): List<AlertRule>

    // 연결 교체를 직렬화하려고 규칙 행을 잡는다. 큐 잠금과 같이 네이티브 FOR UPDATE (ADR #42 가드레일 ①)
    @Query(value = "SELECT id FROM alert_rules WHERE id = :id FOR UPDATE", nativeQuery = true)
    fun lockById(@Param("id") id: Long): Long?
}

interface AlertRuleChannelRepository : JpaRepository<AlertRuleChannel, Long> {

    // 규칙 여러 개의 연결 채널을 한 번에 (목록 N+1 방지). 연결된 순서대로
    @Query(
        """
        select rc.alertRuleId, c from AlertRuleChannel rc, AlertChannel c
        where c.id = rc.alertChannelId and rc.alertRuleId in :ruleIds
        order by rc.id
        """,
    )
    fun findChannelsOf(@Param("ruleIds") ruleIds: Collection<Long>): List<Array<Any>>

    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from AlertRuleChannel rc where rc.alertRuleId = :ruleId")
    fun deleteByRuleId(@Param("ruleId") ruleId: Long): Int
}

// 규칙 id → 연결 채널 목록
fun AlertRuleChannelRepository.channelsByRule(ruleIds: Collection<Long>): Map<Long, List<AlertChannel>> {
    if (ruleIds.isEmpty()) return emptyMap()
    return findChannelsOf(ruleIds).groupBy({ it[0] as Long }, { it[1] as AlertChannel })
}
