package com.monimo.notifier.delivery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

// notification_history. 한 줄 = 논리 발송 1건의 최종 결과 (제안). result 는 ERD 원문 SUCCESS / FAIL
@Entity
@Table(name = "notification_history")
class NotificationHistoryEntity(
    @Column(name = "notification_uuid", nullable = false, unique = true)
    val notificationUuid: UUID,

    @Column(name = "alert_event_id", nullable = false)
    val alertEventId: Long,

    @Column(name = "alert_channel_id", nullable = false)
    val alertChannelId: Long,

    @Column(nullable = false, length = 20)
    val result: String,

    @Column(name = "retry_count", nullable = false)
    val retryCount: Int,

    @Column
    val response: String?,

    @Column(name = "sent_at", nullable = false)
    val sentAt: Instant,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
