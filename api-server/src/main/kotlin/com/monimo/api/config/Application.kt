package com.monimo.api.config

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// applications 표. 감시 대상 서비스. name 은 CH service_name 과 같은 글자라 등록 뒤 바꾸지 않는다
@Entity
@Table(name = "applications")
class Application(
    @Column(name = "application_uuid", nullable = false, unique = true)
    val applicationUuid: UUID,

    @Column(nullable = false, unique = true, length = 100)
    val name: String,

    @Column(name = "display_name", length = 200)
    var displayName: String?,

    @Column(columnDefinition = "text")
    var description: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    // 감시 대상에서 제외한 시각. 감시 중이면 null. 규칙 · 파드가 FK 로 참조해 줄은 지우지 않는다
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,

    // 속한 시스템. 수집기가 같은 값끼리 샘플링 비율의 최댓값을 묶는다 (#205 · #206)
    @Column(nullable = false, length = 50)
    var system: String = DEFAULT_SYSTEM,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    companion object {
        const val DEFAULT_SYSTEM = "shop"
    }
}
