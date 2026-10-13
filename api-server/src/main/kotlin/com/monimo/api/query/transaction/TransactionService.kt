package com.monimo.api.query.transaction

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.CursorCodec
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.support.MonitoredServices
import com.monimo.api.query.support.NanoTime
import com.monimo.api.query.transaction.dto.TransactionCursor
import com.monimo.api.query.transaction.dto.TransactionResponse
import com.monimo.api.query.transaction.dto.TransactionSearch
import org.springframework.stereotype.Service

@Service
class TransactionService(
    private val transactionRepository: TransactionRepository,
    private val monitoredServices: MonitoredServices,
) {

    fun list(search: TransactionSearch, range: TimeRange, cursor: String?, limit: Int): ApiResponse<List<TransactionResponse>> {
        validateDuration(search.minDurationMs, search.maxDurationMs)
        monitoredServices.require(search.serviceName)
        val rows = transactionRepository.find(search, range, cursor?.let { CursorCodec.decode<TransactionCursor>(it) }, limit)
        val page = CursorCodec.page(rows, limit) { TransactionCursor(it.durationMs, it.startMs, it.traceId) }
        return ApiResponse(page.data.map { it.toResponse() }, page.page)
    }

    // 음수는 값 모양이 틀린 것(400), min 이 max 보다 크면 from > to 처럼 범위가 뒤집힌 것(422)
    private fun validateDuration(min: Long?, max: Long?) {
        if ((min != null && min < 0) || (max != null && max < 0)) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "min_duration_ms · max_duration_ms 는 0 이상이어야 합니다.")
        }
        if (min != null && max != null && min > max) {
            throw ApiException(ErrorCode.UNPROCESSABLE, "min_duration_ms 는 max_duration_ms 보다 클 수 없습니다.")
        }
    }

    private fun TransactionRow.toResponse() = TransactionResponse(
        traceId = traceId,
        startTime = NanoTime.formatMillis(startMs),
        durationMs = durationMs,
        serviceName = serviceName,
        agentKey = agentKey,
        spanName = spanName,
        isError = isError,
        httpStatus = httpStatus,
    )
}
