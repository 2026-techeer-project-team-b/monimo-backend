package com.monimo.api.config.agent

import com.monimo.api.config.Application
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

// ip 는 INET 이라 그대로 읽으면 PGobject 가 온다. host() 로 넷마스크 없는 주소 문자열을 꺼낸다
// 제외된 서비스(deleted_at)의 파드는 보이지 않는다 — 서비스 목록 · 상세와 같은 규칙
private const val AGENT_LIST = """
    select a.agent_uuid       as agentUuid,
           p.application_uuid as applicationUuid,
           p.name             as serviceName,
           a.agent_key        as agentKey,
           a.hostname         as hostname,
           host(a.ip)         as ip,
           a.jvm_version      as jvmVersion,
           a.agent_version    as agentVersion,
           a.status           as status,
           a.first_seen_at    as firstSeenAt,
           a.updated_at       as updatedAt
      from agents a
      join applications p on p.id = a.application_id
     where p.deleted_at is null
"""

// 거르는 조건은 쿼리마다 따로 붙인다. 공용 글자에 두면 단건 조회가 쓰지도 않는 :serviceName 을 바인딩해야 한다
private const val AGENT_FILTER = """
    and (:serviceName is null or p.name = :serviceName)
    and (:status is null or a.status = :status)
"""

// agents 표는 수집 파트 소유다 (ADR #39 — 등록은 적재 처리기, status 갱신은 탐지).
// Entity 를 만들지 않고 네이티브 쿼리로 읽기만 한다 (알림 파트 #70 과 같은 방식).
// 타입이 Application 인 것은 Spring Data 가 관리되는 타입을 요구하기 때문이고, 이 표를 쓴다는 뜻이 아니다
interface AgentRepository : Repository<Application, Long> {

    @Query(value = "$AGENT_LIST $AGENT_FILTER and (:after is null or a.agent_key > :after) order by a.agent_key", nativeQuery = true)
    fun findPage(
        @Param("serviceName") serviceName: String?,
        @Param("status") status: String?,
        @Param("after") after: String?,
        pageable: Pageable,
    ): List<AgentRow>

    @Query(value = "$AGENT_LIST and a.agent_uuid = :agentUuid", nativeQuery = true)
    fun findOne(@Param("agentUuid") agentUuid: UUID): AgentRow?

    @Query(value = "select count(*) from agents where application_id = :applicationId", nativeQuery = true)
    fun countByApplicationId(@Param("applicationId") applicationId: Long): Int
}

// 네이티브 조회 결과를 받는 창. 위 별칭과 이름이 같아야 한다
interface AgentRow {
    val agentUuid: UUID
    val applicationUuid: UUID
    val serviceName: String
    val agentKey: String
    val hostname: String?
    val ip: String?
    val jvmVersion: String?
    val agentVersion: String?
    val status: String
    val firstSeenAt: Instant?
    val updatedAt: Instant
}
