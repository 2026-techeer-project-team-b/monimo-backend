package com.monimo.api.alert.channel

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
import java.util.UUID

enum class ChannelType { SLACK, EMAIL, WEBHOOK, PAGERDUTY }

// alert_channels 표. 주인: API 서버. config 는 알림 서비스가 발송할 때 그대로 읽는다 (비밀값 평문, 암호화는 후속)
@Entity
@Table(name = "alert_channels")
class AlertChannel(
    @Column(name = "alert_channel_uuid", nullable = false, unique = true)
    val alertChannelUuid: UUID,

    @Column(nullable = false, length = 100)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var type: ChannelType,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var config: Map<String, Any?>,

    @Column(nullable = false)
    var enabled: Boolean,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
