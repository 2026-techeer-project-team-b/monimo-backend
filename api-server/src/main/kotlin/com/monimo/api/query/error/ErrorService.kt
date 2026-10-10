package com.monimo.api.query.error

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.CursorCodec
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.error.dto.ErrorCursor
import com.monimo.api.query.error.dto.ErrorSearch
import com.monimo.api.query.error.dto.ErrorSpanResponse
import com.monimo.api.query.error.dto.ErrorTimelineResponse
import com.monimo.api.query.support.MonitoredServices
import com.monimo.api.query.support.NanoTime
import com.monimo.api.query.support.Rollup
import org.springframework.stereotype.Service

@Service
class ErrorService(
    private val errorRepository: ErrorRepository,
    private val monitoredServices: MonitoredServices,
) {

    fun list(search: ErrorSearch, range: TimeRange, cursor: String?, limit: Int): ApiResponse<List<ErrorSpanResponse>> {
        monitoredServices.require(search.serviceName)
        val rows = errorRepository.find(search, range, cursor?.let { CursorCodec.decode<ErrorCursor>(it) }, limit)
        val page = CursorCodec.page(rows, limit) { ErrorCursor(it.startNs, it.spanId) }
        return ApiResponse(page.data.map { it.toResponse() }, page.page)
    }

    fun timeline(serviceName: String, range: TimeRange, step: Int): ErrorTimelineResponse {
        Rollup.requireMinuteStep(step)
        monitoredServices.require(serviceName)
        return ErrorTimelineResponse(step, errorRepository.timeline(serviceName, range, step))
    }

    private fun ErrorRow.toResponse() = ErrorSpanResponse(
        traceId = traceId,
        spanId = spanId,
        startTime = NanoTime.format(startNs),
        durationNs = durationNs,
        serviceName = serviceName,
        agentKey = agentKey,
        spanName = spanName,
        spanKind = spanKind,
        statusCode = statusCode,
        httpStatus = httpStatus,
        exceptionType = exceptionType,
        exceptionMessage = exceptionMessage,
    )
}
