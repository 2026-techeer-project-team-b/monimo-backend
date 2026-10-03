package com.monimo.api.alert.event

import com.monimo.api.alert.event.dto.AlertEventDetail
import com.monimo.api.alert.event.dto.AlertEventSummary
import com.monimo.api.alert.event.dto.NotificationResponse
import com.monimo.api.alert.rule.Severity
import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.PageLimit
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

// 경보 사건 3개 문 (API 명세 #14~#16). 모두 읽기라 VIEWER+
@RestController
@RequestMapping("/api/v1/alert-events")
class AlertEventController(
    private val eventService: AlertEventService,
) {

    @GetMapping
    fun list(
        @RequestParam(name = "service_name", required = false) serviceName: String?,
        @RequestParam(required = false, defaultValue = "FIRING") state: EventState,
        @RequestParam(required = false) severity: Severity?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<List<AlertEventSummary>> =
        eventService.list(serviceName, state, severity, from, to, cursor, PageLimit.of(limit))

    @GetMapping("/{alertEventUuid}")
    fun get(@PathVariable alertEventUuid: UUID): ApiResponse<AlertEventDetail> = ApiResponse.of(eventService.get(alertEventUuid))

    @GetMapping("/{alertEventUuid}/notifications")
    fun notifications(
        @PathVariable alertEventUuid: UUID,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<List<NotificationResponse>> = eventService.notifications(alertEventUuid, cursor, PageLimit.of(limit))
}
