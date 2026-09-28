package com.monimo.api.alert.channel

import com.monimo.api.alert.channel.dto.AlertChannelSummaryResponse
import com.monimo.api.alert.channel.dto.CreateAlertChannelRequest
import com.monimo.api.alert.channel.dto.UpdateAlertChannelRequest
import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.CursorCodec
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

// 채널 등록 · 목록 · 상세 · 수정 · 켜고 끄기. 삭제 문은 명세에 없다 (끄고 이력은 남긴다)
@Service
class AlertChannelService(
    private val channels: AlertChannelRepository,
) {
    // 목록 커서. 숫자 id 를 밖에 내보내지 않으려고 마지막 채널 UUID 를 담는다
    data class Cursor(val alertChannelUuid: UUID)

    @Transactional
    fun create(request: CreateAlertChannelRequest): AlertChannel {
        val now = Instant.now()
        return channels.save(
            AlertChannel(
                UUID.randomUUID(), nameOf(request.name), request.type, ChannelConfigPolicy.validate(request.type, request.config),
                request.enabled, now, now,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun list(type: ChannelType?, enabled: Boolean?, cursor: String?, limit: Int): ApiResponse<List<AlertChannelSummaryResponse>> {
        val beforeId = cursor?.let { find(CursorCodec.decode<Cursor>(it).alertChannelUuid, invalidCursor = true).id }
        val rows = channels.findPage(type, enabled, beforeId, PageRequest.of(0, limit + 1))
        return CursorCodec.page(rows.map(AlertChannelSummaryResponse::from), limit) { Cursor(it.alertChannelUuid) }
    }

    @Transactional(readOnly = true)
    fun get(alertChannelUuid: UUID): AlertChannel = find(alertChannelUuid)

    @Transactional
    fun update(alertChannelUuid: UUID, request: UpdateAlertChannelRequest): AlertChannel {
        val channel = find(alertChannelUuid)
        val incoming = if (request.type == channel.type) {
            ChannelConfigPolicy.restoreSecrets(channel.type, channel.config, request.config)
        } else {
            request.config
        }
        channel.config = ChannelConfigPolicy.validate(request.type, incoming)
        channel.name = nameOf(request.name)
        channel.type = request.type
        channel.updatedAt = Instant.now()
        return channel
    }

    // 같은 값을 여러 번 보내도 결과가 같다. 값이 그대로면 updated_at 도 그대로 둔다
    @Transactional
    fun setEnabled(alertChannelUuid: UUID, enabled: Boolean): AlertChannel {
        val channel = find(alertChannelUuid)
        if (channel.enabled != enabled) {
            channel.enabled = enabled
            channel.updatedAt = Instant.now()
        }
        return channel
    }

    private fun find(alertChannelUuid: UUID, invalidCursor: Boolean = false): AlertChannel =
        channels.findByAlertChannelUuid(alertChannelUuid)
            ?: throw if (invalidCursor) {
                ApiException(ErrorCode.INVALID_REQUEST, "cursor 값이 올바르지 않습니다.")
            } else {
                ApiException(ErrorCode.NOT_FOUND, "채널을 찾을 수 없습니다.")
            }

    private fun nameOf(value: String): String {
        val name = value.trim()
        if (name.isEmpty() || name.length > NAME_MAX) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "name 은 1자 이상 ${NAME_MAX}자 이하여야 합니다.")
        }
        return name
    }

    private companion object {
        const val NAME_MAX = 100
    }
}
