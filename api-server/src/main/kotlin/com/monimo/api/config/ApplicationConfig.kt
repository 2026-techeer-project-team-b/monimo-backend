package com.monimo.api.config

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// application_configs 표. 서비스당 한 줄, 등록과 동시에 만든다 (수집기가 바로 읽는다)
@Entity
@Table(name = "application_configs")
class ApplicationConfig(
    @Column(name = "application_config_uuid", nullable = false, unique = true)
    val applicationConfigUuid: UUID,

    @Column(name = "application_id", nullable = false, unique = true)
    val applicationId: Long,

    // 0 ~ 1. 0.0100 = 100건 중 1건
    @Column(name = "sampling_rate", nullable = false, precision = 5, scale = 4)
    var samplingRate: BigDecimal,

    // 고칠 때마다 +1. PUT 의 expected_version 과 비교한다
    @Column(nullable = false)
    var version: Int,

    // 마지막으로 고친 users.id. 처음 만든 뒤 아무도 안 고쳤으면 null
    @Column(name = "updated_by")
    var updatedBy: Long?,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    companion object {
        val DEFAULT_SAMPLING_RATE: BigDecimal = BigDecimal("0.0100")
        const val FIRST_VERSION = 1
    }
}
