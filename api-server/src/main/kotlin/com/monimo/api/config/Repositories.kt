package com.monimo.api.config

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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
}
