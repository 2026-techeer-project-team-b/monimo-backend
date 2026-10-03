package com.monimo.api.alert.event

import com.monimo.api.alert.channel.AlertChannel
import com.monimo.api.alert.rule.AlertOperator
import com.monimo.api.alert.rule.AlertRule
import com.monimo.api.alert.rule.Severity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class EventState { FIRING, RESOLVED }

// alert_events 표. 주인: 탐지. API 서버는 읽기만 한다 (@Immutable 이라 실수로 고쳐도 UPDATE 가 나가지 않는다)
@Entity
@Immutable
@Table(name = "alert_events")
class AlertEvent(
    @Id
    val id: Long,

    @Column(name = "alert_event_uuid", nullable = false)
    val alertEventUuid: UUID,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_rule_id", nullable = false)
    val alertRule: AlertRule,

    // 파드 단위 규칙일 때만 채워진다. 서비스 단위(5XX · 4XX · P95)는 null
    @Column(name = "agent_id")
    val agentId: Long?,

    @Column(nullable = false, length = 64)
    val fingerprint: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val state: EventState,

    @Column(name = "observed_value", precision = 12, scale = 4)
    val observedValue: BigDecimal?,

    @Column(name = "fired_at", nullable = false)
    val firedAt: Instant,

    @Column(name = "resolved_at")
    val resolvedAt: Instant?,

    // 아래는 발화 당시 규칙 스냅샷. 규칙을 고쳐도 과거 사건 상세가 바뀌지 않는다
    @Column(name = "rule_version", nullable = false)
    val ruleVersion: Int,

    @Column(name = "metric_kind", nullable = false, length = 30)
    val metricKind: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    val operator: AlertOperator,

    @Column(nullable = false, precision = 12, scale = 4)
    val threshold: BigDecimal,

    @Column(name = "window_sec", nullable = false)
    val windowSec: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val severity: Severity,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

enum class NotificationResult { SUCCESS, FAIL }

// notification_history 표. 주인: 알림. 한 줄 = 사건 × 채널 논리 발송 1건의 최종 결과, retry_count = 시도 수 - 1
@Entity
@Immutable
@Table(name = "notification_history")
class NotificationHistory(
    @Id
    val id: Long,

    @Column(name = "notification_uuid", nullable = false)
    val notificationUuid: UUID,

    @Column(name = "alert_event_id", nullable = false)
    val alertEventId: Long,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_channel_id", nullable = false)
    val alertChannel: AlertChannel,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val result: NotificationResult,

    @Column(name = "retry_count", nullable = false)
    val retryCount: Int,

    // 알림이 저장할 때 500자로 자른 공급자 응답. 비밀값은 들어 있지 않다
    @Column(columnDefinition = "text")
    val response: String?,

    @Column(name = "sent_at", nullable = false)
    val sentAt: Instant,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)
