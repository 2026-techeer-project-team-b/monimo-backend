package com.monimo.notifier.delivery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

// 발송 작업 상태. 공개 사건 상태(FIRING/RESOLVED)와 다른 축이다
enum class DeliveryStatus { PENDING, IN_FLIGHT, SENT, FAILED, CANCELLED }

// notification_outbox 를 알림 쪽에서 본 모양. 줄은 탐지가 만들고, 알림은 상태 컬럼만 바꾼다 (A안 컬럼 단위 소유)
@Entity
@Table(name = "notification_outbox")
class OutboxRow(
    @Id
    val id: Long,

    @Column(name = "alert_event_id", nullable = false, updatable = false)
    val alertEventId: Long,

    @Column(nullable = false, updatable = false, length = 10)
    val transition: String,

    @Column(name = "alert_channel_id", nullable = false, updatable = false)
    val alertChannelId: Long,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    val payload: Map<String, Any?>,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    var status: DeliveryStatus,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant,

    @Column(name = "lease_until")
    var leaseUntil: Instant?,

    @Column(name = "claim_token")
    var claimToken: UUID?,

    @Column(name = "last_error")
    var lastError: String?,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)
