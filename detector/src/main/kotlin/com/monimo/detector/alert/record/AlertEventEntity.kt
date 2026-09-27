package com.monimo.detector.alert.record

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// 공개 API 의 경보 state. 탐지 내부의 NORMAL / PENDING 은 여기에 없다
enum class AlertEventState { FIRING, RESOLVED }

// alert_events. 규칙 조건은 발화 당시 값을 복사해 둔다 (규칙을 고쳐도 과거 사건이 바뀌지 않게)
@Entity
@Table(name = "alert_events")
class AlertEventEntity(
    @Column(name = "alert_event_uuid", nullable = false, unique = true)
    val alertEventUuid: UUID,

    @Column(name = "alert_rule_id", nullable = false)
    val alertRuleId: Long,

    @Column(name = "agent_id")
    val agentId: Long?,

    @Column(nullable = false, length = 64)
    val fingerprint: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var state: AlertEventState,

    @Column(name = "observed_value", precision = 12, scale = 4)
    val observedValue: BigDecimal?,

    @Column(name = "fired_at", nullable = false)
    val firedAt: Instant,

    @Column(name = "resolved_at")
    var resolvedAt: Instant?,

    @Column(name = "rule_version", nullable = false)
    val ruleVersion: Int,

    @Column(name = "metric_kind", nullable = false, length = 30)
    val metricKind: String,

    @Column(nullable = false, length = 5)
    val operator: String,

    @Column(nullable = false, precision = 12, scale = 4)
    val threshold: BigDecimal,

    @Column(name = "window_sec", nullable = false)
    val windowSec: Int,

    @Column(nullable = false, length = 20)
    val severity: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
