package com.monimo.detector.alert.record

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

enum class OutboxTransition { FIRING, RESOLVED }

// notification_outbox (제안 A안). 탐지는 PENDING 으로 넣기만 한다. 이후 상태 컬럼은 알림이 바꾼다
@Entity
@Table(name = "notification_outbox")
class NotificationOutboxEntity(
    @Column(name = "alert_event_id", nullable = false)
    val alertEventId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    val transition: OutboxTransition,

    @Column(name = "alert_channel_id", nullable = false)
    val alertChannelId: Long,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    val payload: Map<String, Any?>,

    @Column(nullable = false, length = 12)
    val status: String,

    @Column(name = "next_attempt_at", nullable = false)
    val nextAttemptAt: Instant,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    companion object {
        fun pending(alertEventId: Long, transition: OutboxTransition, alertChannelId: Long, payload: Map<String, Any?>, now: Instant) =
            NotificationOutboxEntity(alertEventId, transition, alertChannelId, payload, "PENDING", now, now, now)
    }
}
