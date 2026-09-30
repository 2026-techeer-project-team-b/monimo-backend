package com.monimo.api.alert.channel

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AlertChannelRepository : JpaRepository<AlertChannel, Long> {
    fun findByAlertChannelUuid(alertChannelUuid: UUID): AlertChannel?
    fun findByAlertChannelUuidIn(alertChannelUuids: Collection<UUID>): List<AlertChannel>

    // 최신 등록 순(id 내림차순) 커서 페이징. 필터가 null 이면 걸지 않는다
    @Query(
        """
        select c from AlertChannel c
        where (:type is null or c.type = :type)
          and (:enabled is null or c.enabled = :enabled)
          and (:beforeId is null or c.id < :beforeId)
        order by c.id desc
        """,
    )
    fun findPage(
        @Param("type") type: ChannelType?,
        @Param("enabled") enabled: Boolean?,
        @Param("beforeId") beforeId: Long?,
        pageable: Pageable,
    ): List<AlertChannel>
}
