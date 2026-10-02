package com.monimo.api.alert.event

import com.monimo.api.alert.event.dto.AlertEventDetail
import com.monimo.api.alert.event.dto.AlertEventSummary
import com.monimo.api.alert.event.dto.NotificationResponse
import com.monimo.api.alert.rule.Severity
import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.CursorCodec
import com.monimo.api.common.web.TimeRange
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

// 경보 사건 목록 · 상세 · 전송 이력 (API 명세 #14~#16). 사건은 탐지가, 이력은 알림이 쓰고 여기는 읽기만 한다
@Service
@Transactional(readOnly = true)
class AlertEventService(
    private val events: AlertEventRepository,
    private val histories: NotificationHistoryRepository,
) {
    // 커서는 숫자 id 를 밖에 내보내지 않으려고 마지막 줄 UUID 만 담고, 정렬 위치(시각 · id)는 다시 읽어 찾는다
    data class EventCursor(val alertEventUuid: UUID)
    data class NotificationCursor(val notificationUuid: UUID)

    fun list(
        serviceName: String?,
        state: EventState,
        severity: Severity?,
        from: Instant?,
        to: Instant?,
        cursor: String?,
        limit: Int,
    ): ApiResponse<List<AlertEventSummary>> {
        val range = rangeOf(from, to)
        val after = cursor?.let {
            events.findActive(CursorCodec.decode<EventCursor>(it).alertEventUuid) ?: throw invalidCursor()
        }
        val rows = events.findPage(
            state, serviceName, severity, range?.from ?: EventBounds.EARLIEST, range?.to ?: EventBounds.LATEST,
            after?.firedAt ?: EventBounds.LATEST, after?.id ?: Long.MAX_VALUE, PageRequest.of(0, limit + 1),
        )
        val agents = events.agentsOf(rows)
        return CursorCodec.page(rows.map { AlertEventSummary.from(it, agents[it.agentId]) }, limit) { EventCursor(it.alertEventUuid) }
    }

    fun get(alertEventUuid: UUID): AlertEventDetail {
        val event = find(alertEventUuid)
        return AlertEventDetail.from(event, events.agentsOf(listOf(event))[event.agentId])
    }

    // 사건이 없으면 404, 사건은 있는데 보낸 기록이 없으면 빈 배열
    fun notifications(alertEventUuid: UUID, cursor: String?, limit: Int): ApiResponse<List<NotificationResponse>> {
        val eventId = find(alertEventUuid).id
        val after = cursor?.let {
            histories.findByNotificationUuidAndAlertEventId(CursorCodec.decode<NotificationCursor>(it).notificationUuid, eventId)
                ?: throw invalidCursor()
        }
        val rows = histories.findPage(eventId, after?.sentAt ?: EventBounds.LATEST, after?.id ?: Long.MAX_VALUE, PageRequest.of(0, limit + 1))
        return CursorCodec.page(rows.map(NotificationResponse::from), limit) { NotificationCursor(it.notificationUuid) }
    }

    private fun find(alertEventUuid: UUID): AlertEvent =
        events.findActive(alertEventUuid) ?: throw ApiException(ErrorCode.NOT_FOUND, "경보 사건을 찾을 수 없습니다.")

    // from · to 는 둘 다 주거나 둘 다 빼야 한다. 한쪽만 오면 어느 범위를 뜻하는지 모호해 400
    private fun rangeOf(from: Instant?, to: Instant?): TimeRange? = when {
        from == null && to == null -> null
        from == null || to == null -> throw ApiException(ErrorCode.INVALID_REQUEST, "from 과 to 는 함께 보내야 합니다.")
        else -> TimeRange.of(from, to, MAX_RANGE)
    }

    private fun invalidCursor() = ApiException(ErrorCode.INVALID_REQUEST, "cursor 값이 올바르지 않습니다.")

    private companion object {
        // 사건은 PG 에 보관 기한 없이 남는다. 화면 기본 상한 7일로는 지난 장애 회고가 어려워 1분 지표 보관 기간(90일)에 맞춘다
        val MAX_RANGE: Duration = Duration.ofDays(90)
    }
}
