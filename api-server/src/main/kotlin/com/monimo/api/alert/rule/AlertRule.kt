package com.monimo.api.alert.rule

import com.monimo.api.config.Application
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// alert_rules 표. 주인: API 서버. 탐지는 읽기만 한다
@Entity
@Table(name = "alert_rules")
class AlertRule(
    @Column(name = "alert_rule_uuid", nullable = false, unique = true)
    val alertRuleUuid: UUID,

    // 규칙이 붙은 서비스는 바꾸지 않는다 (PUT 대상 아님)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false, updatable = false)
    val application: Application,

    @Column(nullable = false, length = 200)
    var name: String,

    // MetricKind.code (예: 5XX_RATE)
    @Column(name = "metric_kind", nullable = false, length = 30)
    var metricKind: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    var operator: AlertOperator,

    @Column(nullable = false, precision = 12, scale = 4)
    var threshold: BigDecimal,

    @Column(name = "window_sec", nullable = false)
    var windowSec: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var severity: Severity,

    @Column(nullable = false)
    var enabled: Boolean,

    // 조건(metric_kind · operator · threshold · window_sec)을 고칠 때마다 +1. 탐지가 수정 전 조건으로 낸 평가를 버리는 기준
    @Column(nullable = false)
    var version: Int,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}

// alert_rule_channels 표. 규칙이 터지면 보낼 채널
@Entity
@Table(name = "alert_rule_channels")
class AlertRuleChannel(
    @Column(name = "alert_rule_id", nullable = false)
    val alertRuleId: Long,

    @Column(name = "alert_channel_id", nullable = false)
    val alertChannelId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
