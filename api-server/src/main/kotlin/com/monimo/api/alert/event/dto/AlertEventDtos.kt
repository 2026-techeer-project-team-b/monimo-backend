package com.monimo.api.alert.event.dto

import com.monimo.api.alert.channel.ChannelType
import com.monimo.api.alert.event.AgentRef
import com.monimo.api.alert.event.AlertEvent
import com.monimo.api.alert.event.EventState
import com.monimo.api.alert.event.NotificationHistory
import com.monimo.api.alert.event.NotificationResult
import com.monimo.api.alert.rule.AlertOperator
import com.monimo.api.alert.rule.Severity
import java.time.Instant
import java.util.UUID

// 사건 목록(#14) 한 줄. agent_* · resolved_at 은 비어 있으면 null 로 내보낸다 (명세 예시)
data class AlertEventSummary(
    val alertEventUuid: UUID,
    val alertRuleUuid: UUID,
    val ruleName: String,
    val serviceName: String,
    val agentUuid: UUID?,
    val agentKey: String?,
    val fingerprint: String,
    val state: EventState,
    // 발화 당시 심각도 (규칙을 고쳐도 그대로)
    val severity: Severity,
    val observedValue: Double?,
    val firedAt: Instant,
    val resolvedAt: Instant?,
) {
    companion object {
        fun from(e: AlertEvent, agent: AgentRef?) = AlertEventSummary(
            e.alertEventUuid, e.alertRule.alertRuleUuid, e.alertRule.name, e.alertRule.application.name,
            agent?.agentUuid, agent?.agentKey, e.fingerprint, e.state, e.severity, e.observedValue?.toDouble(),
            e.firedAt, e.resolvedAt,
        )
    }
}

// 사건 상세(#15). 조건은 발화 당시 스냅샷이라 지금 규칙과 다를 수 있다
data class AlertEventDetail(
    val alertEventUuid: UUID,
    val alertRuleUuid: UUID,
    val ruleName: String,
    val serviceName: String,
    val agentUuid: UUID?,
    val agentKey: String?,
    val fingerprint: String,
    val state: EventState,
    val severity: Severity,
    val observedValue: Double?,
    val firedAt: Instant,
    val resolvedAt: Instant?,
    val threshold: Double,
    val operator: AlertOperator,
    val windowSec: Int,
    val metricKind: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(e: AlertEvent, agent: AgentRef?) = AlertEventDetail(
            e.alertEventUuid, e.alertRule.alertRuleUuid, e.alertRule.name, e.alertRule.application.name,
            agent?.agentUuid, agent?.agentKey, e.fingerprint, e.state, e.severity, e.observedValue?.toDouble(),
            e.firedAt, e.resolvedAt, e.threshold.toDouble(), e.operator, e.windowSec, e.metricKind, e.createdAt,
        )
    }
}

// 전송 이력(#16) 한 줄
data class NotificationResponse(
    val notificationUuid: UUID,
    val alertChannelUuid: UUID,
    val channelName: String,
    val type: ChannelType,
    val result: NotificationResult,
    val retryCount: Int,
    val response: String?,
    val sentAt: Instant,
) {
    companion object {
        fun from(h: NotificationHistory) = NotificationResponse(
            h.notificationUuid, h.alertChannel.alertChannelUuid, h.alertChannel.name, h.alertChannel.type,
            h.result, h.retryCount, h.response, h.sentAt,
        )
    }
}
