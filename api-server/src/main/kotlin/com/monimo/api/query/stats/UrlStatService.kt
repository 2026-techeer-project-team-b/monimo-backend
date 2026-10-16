package com.monimo.api.query.stats

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.CursorCodec
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.stats.dto.UrlStatCursor
import com.monimo.api.query.stats.dto.UrlStatResponse
import com.monimo.api.query.support.MonitoredServices
import org.springframework.stereotype.Service
import kotlin.math.round

// 호출이 많은 URL 부터. 요청이 없으면 빈 목록
@Service
class UrlStatService(
    private val urlStatRepository: UrlStatRepository,
    private val monitoredServices: MonitoredServices,
) {

    fun list(serviceName: String, agentKey: String?, range: TimeRange, cursor: String?, limit: Int): ApiResponse<List<UrlStatResponse>> {
        monitoredServices.require(serviceName)
        val rows = urlStatRepository.find(serviceName, agentKey, range, cursor?.let { CursorCodec.decode<UrlStatCursor>(it) }, limit)
        val page = CursorCodec.page(rows, limit) { UrlStatCursor(it.cnt, it.spanName) }
        return ApiResponse(page.data.map { it.toResponse() }, page.page)
    }

    private fun UrlStatRow.toResponse() = UrlStatResponse(
        spanName = spanName,
        cnt = cnt,
        errCnt = errCnt,
        errorRate = if (cnt == 0L) 0.0 else round(errCnt.toDouble() / cnt * 10_000) / 10_000,
        p50Ms = p50Ms,
        p95Ms = p95Ms,
        p99Ms = p99Ms,
    )
}
