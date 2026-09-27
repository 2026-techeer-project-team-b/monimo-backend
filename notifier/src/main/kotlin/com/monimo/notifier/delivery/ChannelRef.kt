package com.monimo.notifier.delivery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

// alert_channels 읽기 전용 (주인: API 서버). 보내기 직전에 켜짐 · 설정을 다시 읽는다
@Entity
@Immutable
@Table(name = "alert_channels")
class ChannelRef(
    @Id
    val id: Long,

    @Column(nullable = false, length = 20)
    val type: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    val config: Map<String, Any?>,

    @Column(nullable = false)
    val enabled: Boolean,
)
