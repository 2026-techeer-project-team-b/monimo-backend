package com.monimo.api.alert.channel

import com.monimo.api.alert.channel.dto.AlertChannelResponse
import com.monimo.api.alert.channel.dto.AlertChannelSummaryResponse
import com.monimo.api.alert.channel.dto.ChannelEnabledRequest
import com.monimo.api.alert.channel.dto.ChannelEnabledResponse
import com.monimo.api.alert.channel.dto.CreateAlertChannelRequest
import com.monimo.api.alert.channel.dto.UpdateAlertChannelRequest
import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.PageLimit
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 알림 채널 5개 문 (API 명세 #8~#12: 목록 VIEWER+, 나머지 ADMIN — 상세에 config 가 있어서)
@RestController
@RequestMapping("/api/v1/alert-channels")
class AlertChannelController(
    private val channelService: AlertChannelService,
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) type: ChannelType?,
        @RequestParam(required = false) enabled: Boolean?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<List<AlertChannelSummaryResponse>> = channelService.list(type, enabled, cursor, PageLimit.of(limit))

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateAlertChannelRequest): ApiResponse<AlertChannelResponse> =
        ApiResponse.of(AlertChannelResponse.from(channelService.create(request)))

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{alertChannelUuid}")
    fun get(@PathVariable alertChannelUuid: UUID): ApiResponse<AlertChannelResponse> =
        ApiResponse.of(AlertChannelResponse.from(channelService.get(alertChannelUuid)))

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{alertChannelUuid}")
    fun update(
        @PathVariable alertChannelUuid: UUID,
        @RequestBody request: UpdateAlertChannelRequest,
    ): ApiResponse<AlertChannelResponse> =
        ApiResponse.of(AlertChannelResponse.from(channelService.update(alertChannelUuid, request)))

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{alertChannelUuid}/enabled")
    fun setEnabled(
        @PathVariable alertChannelUuid: UUID,
        @RequestBody request: ChannelEnabledRequest,
    ): ApiResponse<ChannelEnabledResponse> {
        val enabled = request.enabled ?: throw ApiException(ErrorCode.INVALID_REQUEST, "enabled 가 필요합니다.")
        val channel = channelService.setEnabled(alertChannelUuid, enabled)
        return ApiResponse.of(ChannelEnabledResponse(channel.alertChannelUuid, channel.enabled, channel.updatedAt))
    }
}
