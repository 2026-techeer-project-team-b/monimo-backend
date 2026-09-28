package com.monimo.api.alert.channel.dto

import com.monimo.api.alert.channel.AlertChannel
import com.monimo.api.alert.channel.ChannelConfigPolicy
import com.monimo.api.alert.channel.ChannelType
import java.time.Instant
import java.util.UUID

// config 는 유형마다 모양이 달라 Map 으로 받고 ChannelConfigPolicy 가 검사한다
data class CreateAlertChannelRequest(
    val name: String,
    val type: ChannelType,
    val config: Map<String, Any?>?,
    val enabled: Boolean = true,
)

// 전체 교체. 비밀값 자리에 응답으로 받은 가린 값을 그대로 보내면 기존 값을 유지한다
data class UpdateAlertChannelRequest(
    val name: String,
    val type: ChannelType,
    val config: Map<String, Any?>?,
)

// Boolean 을 null 허용으로 받는다. 빠뜨리면 false 로 채워져 채널이 몰래 꺼지는 것을 막으려고
data class ChannelEnabledRequest(
    val enabled: Boolean?,
)

// 목록 (VIEWER+). config 는 싣지 않는다
data class AlertChannelSummaryResponse(
    val alertChannelUuid: UUID,
    val name: String,
    val type: ChannelType,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(c: AlertChannel) = AlertChannelSummaryResponse(c.alertChannelUuid, c.name, c.type, c.enabled, c.createdAt, c.updatedAt)
    }
}

// 등록 · 상세 · 수정 (ADMIN). config 비밀값은 가려서 나간다
data class AlertChannelResponse(
    val alertChannelUuid: UUID,
    val name: String,
    val type: ChannelType,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val config: Map<String, Any?>,
) {
    companion object {
        fun from(c: AlertChannel) = AlertChannelResponse(
            c.alertChannelUuid, c.name, c.type, c.enabled, c.createdAt, c.updatedAt, ChannelConfigPolicy.mask(c.type, c.config),
        )
    }
}

data class ChannelEnabledResponse(
    val alertChannelUuid: UUID,
    val enabled: Boolean,
    val updatedAt: Instant,
)
