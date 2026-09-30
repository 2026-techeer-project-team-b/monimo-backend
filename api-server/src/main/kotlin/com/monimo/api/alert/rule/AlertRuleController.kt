package com.monimo.api.alert.rule

import com.monimo.api.alert.rule.dto.AlertRuleResponse
import com.monimo.api.alert.rule.dto.CreateAlertRuleRequest
import com.monimo.api.alert.rule.dto.RuleChannelResponse
import com.monimo.api.alert.rule.dto.RuleChannelsRequest
import com.monimo.api.alert.rule.dto.RuleChannelsResponse
import com.monimo.api.alert.rule.dto.RuleEnabledRequest
import com.monimo.api.alert.rule.dto.RuleEnabledResponse
import com.monimo.api.alert.rule.dto.UpdateAlertRuleRequest
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

// 경보 규칙 7개 문 (API 명세 #1~#7: 조회 3개 VIEWER+, 바꾸는 4개 ADMIN)
@RestController
@RequestMapping("/api/v1/alert-rules")
class AlertRuleController(
    private val ruleService: AlertRuleService,
) {

    @GetMapping
    fun list(
        @RequestParam(name = "service_name", required = false) serviceName: String?,
        @RequestParam(required = false) enabled: Boolean?,
        @RequestParam(required = false) severity: Severity?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<List<AlertRuleResponse>> = ruleService.list(serviceName, enabled, severity, cursor, PageLimit.of(limit))

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateAlertRuleRequest): ApiResponse<AlertRuleResponse> {
        val (rule, channels) = ruleService.create(request)
        return ApiResponse.of(AlertRuleResponse.from(rule, channels))
    }

    @GetMapping("/{alertRuleUuid}")
    fun get(@PathVariable alertRuleUuid: UUID): ApiResponse<AlertRuleResponse> {
        val (rule, channels) = ruleService.get(alertRuleUuid)
        return ApiResponse.of(AlertRuleResponse.from(rule, channels))
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{alertRuleUuid}")
    fun update(
        @PathVariable alertRuleUuid: UUID,
        @RequestBody request: UpdateAlertRuleRequest,
    ): ApiResponse<AlertRuleResponse> {
        val (rule, channels) = ruleService.update(alertRuleUuid, request)
        return ApiResponse.of(AlertRuleResponse.from(rule, channels))
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{alertRuleUuid}/enabled")
    fun setEnabled(
        @PathVariable alertRuleUuid: UUID,
        @RequestBody request: RuleEnabledRequest,
    ): ApiResponse<RuleEnabledResponse> {
        val enabled = request.enabled ?: throw ApiException(ErrorCode.INVALID_REQUEST, "enabled 가 필요합니다.")
        val rule = ruleService.setEnabled(alertRuleUuid, enabled)
        return ApiResponse.of(RuleEnabledResponse(rule.alertRuleUuid, rule.enabled, rule.updatedAt))
    }

    @GetMapping("/{alertRuleUuid}/channels")
    fun getChannels(@PathVariable alertRuleUuid: UUID): ApiResponse<RuleChannelsResponse> {
        val (rule, channels) = ruleService.getChannels(alertRuleUuid)
        return ApiResponse.of(RuleChannelsResponse(rule.alertRuleUuid, channels.map(RuleChannelResponse::from)))
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{alertRuleUuid}/channels")
    fun replaceChannels(
        @PathVariable alertRuleUuid: UUID,
        @RequestBody request: RuleChannelsRequest,
    ): ApiResponse<RuleChannelsResponse> {
        val uuids = request.channelUuids ?: throw ApiException(ErrorCode.INVALID_REQUEST, "channel_uuids 가 필요합니다.")
        val (rule, channels) = ruleService.replaceChannels(alertRuleUuid, uuids)
        return ApiResponse.of(RuleChannelsResponse(rule.alertRuleUuid, channels.map(RuleChannelResponse::from)))
    }
}
