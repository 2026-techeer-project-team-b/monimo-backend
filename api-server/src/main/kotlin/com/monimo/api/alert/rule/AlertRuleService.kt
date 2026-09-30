package com.monimo.api.alert.rule

import com.monimo.api.alert.DbTime
import com.monimo.api.alert.channel.AlertChannel
import com.monimo.api.alert.channel.AlertChannelRepository
import com.monimo.api.alert.rule.dto.AlertRuleResponse
import com.monimo.api.alert.rule.dto.CreateAlertRuleRequest
import com.monimo.api.alert.rule.dto.UpdateAlertRuleRequest
import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.CursorCodec
import com.monimo.api.config.ApplicationRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

// 규칙 목록 · 생성 · 상세 · 수정 · 켜고 끄기 · 채널 연결 조회 · 교체. 삭제 문은 명세에 없다 (끄고 이력은 남긴다)
@Service
class AlertRuleService(
    private val rules: AlertRuleRepository,
    private val links: AlertRuleChannelRepository,
    private val channels: AlertChannelRepository,
    private val applications: ApplicationRepository,
) {
    // 목록 커서. 숫자 id 를 밖에 내보내지 않으려고 마지막 규칙 UUID 를 담는다
    data class Cursor(val alertRuleUuid: UUID)

    @Transactional
    fun create(request: CreateAlertRuleRequest): Pair<AlertRule, List<AlertChannel>> {
        val kind = metricKindOf(request.metricKind)
        val app = applications.findByApplicationUuidAndDeletedAtIsNull(request.applicationUuid)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "서비스를 찾을 수 없습니다.")
        val linked = resolveChannels(request.channelUuids)
        val at = DbTime.now()
        val rule = rules.saveAndFlush(
            AlertRule(
                UUID.randomUUID(), app, nameOf(request.name), kind.code, request.operator, thresholdOf(kind, request.threshold),
                windowOf(request.windowSec), request.severity, request.enabled, FIRST_VERSION, at, at,
            ),
        )
        link(rule, linked)
        return rule to linked
    }

    @Transactional(readOnly = true)
    fun list(serviceName: String?, enabled: Boolean?, severity: Severity?, cursor: String?, limit: Int): ApiResponse<List<AlertRuleResponse>> {
        val beforeId = cursor?.let {
            rules.findActive(CursorCodec.decode<Cursor>(it).alertRuleUuid)?.id
                ?: throw ApiException(ErrorCode.INVALID_REQUEST, "cursor 값이 올바르지 않습니다.")
        }
        val rows = rules.findPage(serviceName, enabled, severity, beforeId, PageRequest.of(0, limit + 1))
        return CursorCodec.page(rows.map { AlertRuleResponse.from(it, channels = null) }, limit) { Cursor(it.alertRuleUuid) }
    }

    @Transactional(readOnly = true)
    fun get(alertRuleUuid: UUID): Pair<AlertRule, List<AlertChannel>> {
        val rule = find(alertRuleUuid)
        return rule to channelsOf(rule)
    }

    // 조건이 바뀌면 version +1. 이름 · 심각도만 바꾸면 평가 결과가 달라지지 않으니 그대로 둔다
    @Transactional
    fun update(alertRuleUuid: UUID, request: UpdateAlertRuleRequest): Pair<AlertRule, List<AlertChannel>> {
        val rule = find(alertRuleUuid)
        val kind = metricKindOf(request.metricKind)
        val threshold = thresholdOf(kind, request.threshold)
        val window = windowOf(request.windowSec)
        val conditionChanged = rule.metricKind != kind.code || rule.operator != request.operator ||
            rule.threshold.compareTo(threshold) != 0 || rule.windowSec != window
        rule.name = nameOf(request.name)
        rule.metricKind = kind.code
        rule.operator = request.operator
        rule.threshold = threshold
        rule.windowSec = window
        rule.severity = request.severity
        if (conditionChanged) rule.version += 1
        rule.updatedAt = DbTime.now()
        return rule to channelsOf(rule)
    }

    // 같은 값을 여러 번 보내도 결과가 같다. 값이 그대로면 updated_at 도 그대로 둔다
    @Transactional
    fun setEnabled(alertRuleUuid: UUID, enabled: Boolean): AlertRule {
        val rule = find(alertRuleUuid)
        if (rule.enabled != enabled) {
            rule.enabled = enabled
            rule.updatedAt = DbTime.now()
        }
        return rule
    }

    @Transactional(readOnly = true)
    fun getChannels(alertRuleUuid: UUID): Pair<AlertRule, List<AlertChannel>> = get(alertRuleUuid)

    // 연결 목록 통째 교체. 하나라도 없거나 겹치면 아무것도 바꾸지 않는다. 빈 배열 = 연결 해제
    @Transactional
    fun replaceChannels(alertRuleUuid: UUID, channelUuids: List<UUID>): Pair<AlertRule, List<AlertChannel>> {
        val rule = find(alertRuleUuid)
        val linked = resolveChannels(channelUuids)
        // 두 요청이 동시에 교체하면 지우고 넣는 사이가 겹쳐 UNIQUE 위반이 날 수 있다. 규칙 행을 잡아 한 줄로 세운다
        val ruleId = rule.id!!
        rules.lockById(ruleId)
        links.deleteByRuleId(ruleId)
        link(rule, linked)
        return rule to linked
    }

    private fun find(alertRuleUuid: UUID): AlertRule =
        rules.findActive(alertRuleUuid) ?: throw ApiException(ErrorCode.NOT_FOUND, "규칙을 찾을 수 없습니다.")

    private fun channelsOf(rule: AlertRule): List<AlertChannel> = links.channelsByRule(listOf(rule.id!!))[rule.id].orEmpty()

    // 요청 순서를 지킨다. 배열 안 중복은 409, 없는 채널은 404
    private fun resolveChannels(uuids: List<UUID>): List<AlertChannel> {
        if (uuids.size != uuids.toSet().size) throw ApiException(ErrorCode.RULE_CHANNEL_DUPLICATE, "channel_uuids 에 같은 채널이 두 번 있습니다.")
        val found = channels.findByAlertChannelUuidIn(uuids).associateBy { it.alertChannelUuid }
        return uuids.map { found[it] ?: throw ApiException(ErrorCode.NOT_FOUND, "채널을 찾을 수 없습니다: $it") }
    }

    private fun link(rule: AlertRule, linked: List<AlertChannel>) {
        val at = DbTime.now()
        links.saveAll(linked.map { AlertRuleChannel(rule.id!!, it.id!!, at) })
    }

    private fun nameOf(value: String): String {
        val name = value.trim()
        if (name.isEmpty() || name.length > NAME_MAX) throw invalid("name 은 1자 이상 ${NAME_MAX}자 이하여야 합니다.")
        return name
    }

    private fun metricKindOf(code: String): MetricKind =
        MetricKind.of(code) ?: throw invalid("metric_kind 는 ${MetricKind.codes} 중 하나여야 합니다.")

    // NUMERIC(12,4): 소수 4자리 · 정수부 8자리까지. 비율 규칙은 0~100 (%)
    private fun thresholdOf(kind: MetricKind, value: BigDecimal): BigDecimal {
        if (value.stripTrailingZeros().scale() > 4 || value.abs() >= THRESHOLD_LIMIT) {
            throw invalid("threshold 는 소수 4자리 · 절댓값 1억 미만이어야 합니다.")
        }
        if (kind.percent && (value < BigDecimal.ZERO || value > HUNDRED)) {
            throw invalid("${kind.code} 의 threshold 는 0 이상 100 이하(%)여야 합니다.")
        }
        return value
    }

    // 탐지가 1분 버킷으로 평가하므로 60초 단위
    private fun windowOf(value: Int?): Int {
        if (value == null || value !in WINDOW_MIN..WINDOW_MAX || value % WINDOW_MIN != 0) {
            throw invalid("window_sec 은 ${WINDOW_MIN}~${WINDOW_MAX} 사이 ${WINDOW_MIN}의 배수여야 합니다.")
        }
        return value
    }

    private fun invalid(message: String) = ApiException(ErrorCode.INVALID_REQUEST, message)

    private companion object {
        const val NAME_MAX = 200
        const val FIRST_VERSION = 1
        const val WINDOW_MIN = 60
        const val WINDOW_MAX = 3600
        val THRESHOLD_LIMIT = BigDecimal("100000000")
        val HUNDRED = BigDecimal(100)
    }
}
