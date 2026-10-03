package com.monimo.api.config

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

interface ApplicationRepository : JpaRepository<Application, Long> {
    fun findByName(name: String): Application?
    fun findByApplicationUuidAndDeletedAtIsNull(applicationUuid: UUID): Application?

    // name 오름차순 커서 페이징. after 가 null 이면 첫 쪽
    @Query(
        """
        select a from Application a
        where a.deletedAt is null and (:after is null or a.name > :after)
        order by a.name asc
        """,
    )
    fun findActiveAfter(@Param("after") after: String?, pageable: Pageable): List<Application>
}

interface ApplicationConfigRepository : JpaRepository<ApplicationConfig, Long> {
    fun findByApplicationId(applicationId: Long): ApplicationConfig?

    // version 이 기대값과 같을 때만 고친다(CAS). 고친 줄 수가 0 이면 그 사이 남이 먼저 고쳤다는 뜻
    // 읽고 → 비교하고 → 쓰면 그 사이에 끼어들 틈이 생긴다. 한 문장이라야 틈이 없다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update ApplicationConfig c
           set c.samplingRate = :samplingRate,
               c.version = c.version + 1,
               c.updatedBy = :updatedBy,
               c.updatedAt = :updatedAt
         where c.applicationId = :applicationId and c.version = :expectedVersion
        """,
    )
    fun updateIfVersionMatches(
        @Param("applicationId") applicationId: Long,
        @Param("expectedVersion") expectedVersion: Int,
        @Param("samplingRate") samplingRate: BigDecimal,
        @Param("updatedBy") updatedBy: Long,
        @Param("updatedAt") updatedAt: Instant,
    ): Int
}
