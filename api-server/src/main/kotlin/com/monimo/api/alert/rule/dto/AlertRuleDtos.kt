package com.monimo.api.alert.rule.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.monimo.api.alert.channel.AlertChannel
import com.monimo.api.alert.channel.ChannelType
import com.monimo.api.alert.rule.AlertOperator
import com.monimo.api.alert.rule.AlertRule
import com.monimo.api.alert.rule.Severity
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// metric_kind 는 5XX_RATE 처럼 숫자로 시작해 문자열로 받고 서비스에서 검사한다
data class CreateAlertRuleRequest(
    val applicationUuid: UUID,
    val name: String,
    val metricKind: String,
    val operator: AlertOperator,
    val threshold: BigDecimal,
    val windowSec: Int?,
    val severity: Severity,
    val enabled: Boolean = true,
    val channelUuids: List<UUID> = emptyList(),
)

// 조건 · 이름 · 심각도 전체 교체. 서비스 · 켜짐 · 연결 채널은 각자의 문으로만 바꾼다
data class UpdateAlertRuleRequest(
    val name: String,
    val metricKind: String,
    val operator: AlertOperator,
    val threshold: BigDecimal,
    val windowSec: Int?,
    val severity: Severity,
)

// Boolean 을 null 허용으로 받는다. 빠뜨리면 false 로 채워져 규칙이 몰래 꺼지는 것을 막으려고
data class RuleEnabledRequest(
    val enabled: Boolean?,
)

data class RuleChannelsRequest(
    val channelUuids: List<UUID>?,
)

// 규칙 응답에 싣는 채널 요약. config 는 싣지 않는다 (VIEWER+ 도 본다)
data class RuleChannelResponse(
    val alertChannelUuid: UUID,
    val name: String,
    val type: ChannelType,
    val enabled: Boolean,
) {
    companion object {
        fun from(c: AlertChannel) = RuleChannelResponse(c.alertChannelUuid, c.name, c.type, c.enabled)
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AlertRuleResponse(
    val alertRuleUuid: UUID,
    val applicationUuid: UUID,
    val serviceName: String,
    val name: String,
    val metricKind: String,
    val operator: AlertOperator,
    // NUMERIC(12,4). BigDecimal 을 그대로 내보내면 1.0000 · 1E+1 처럼 나와 숫자로 바꾼다
    val threshold: Double,
    val windowSec: Int,
    val severity: Severity,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    // 목록에서는 싣지 않는다 (명세 #1 예시)
    val channels: List<RuleChannelResponse>?,
) {
    companion object {
        fun from(r: AlertRule, channels: List<AlertChannel>?) = AlertRuleResponse(
            r.alertRuleUuid, r.application.applicationUuid, r.application.name, r.name, r.metricKind, r.operator,
            r.threshold.toDouble(), r.windowSec, r.severity, r.enabled, r.createdAt, r.updatedAt,
            channels?.map(RuleChannelResponse::from),
        )
    }
}

data class RuleEnabledResponse(
    val alertRuleUuid: UUID,
    val enabled: Boolean,
    val updatedAt: Instant,
)

data class RuleChannelsResponse(
    val alertRuleUuid: UUID,
    val channels: List<RuleChannelResponse>,
)
