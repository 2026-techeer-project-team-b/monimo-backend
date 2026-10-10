package com.monimo.api.query.transaction

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.support.MonitoredServices
import com.monimo.api.query.transaction.dto.ScatterResponse
import org.springframework.stereotype.Service
import kotlin.math.sqrt

// 요청 수가 limit 이하면 전부(raw), 넘으면 격자로 접어(bucketed) 돌려준다. 어느 쪽인지는 서버가 정한다 (FN-47)
@Service
class ScatterService(
    private val scatterRepository: ScatterRepository,
    private val monitoredServices: MonitoredServices,
) {

    fun get(serviceName: String, agentKey: String?, range: TimeRange, limit: Int): ScatterResponse {
        monitoredServices.require(serviceName)
        val summary = scatterRepository.summarize(serviceName, agentKey, range)
        if (summary.totalCount <= limit) {
            return ScatterResponse(MODE_RAW, summary.totalCount, scatterRepository.findAll(serviceName, agentKey, range, limit))
        }
        // 격자는 가로 × 세로 × (성공 · 실패) 두 겹이라 한 변을 sqrt(limit / 2) 로 잡으면 점이 limit 을 넘지 않는다
        val cells = sqrt(limit / 2.0).toInt().coerceAtLeast(1)
        val points = scatterRepository.findBucketed(serviceName, agentKey, range, cells, summary.maxDurationMs)
        return ScatterResponse(MODE_BUCKETED, summary.totalCount, points)
    }

    companion object {
        const val MODE_RAW = "raw"
        const val MODE_BUCKETED = "bucketed"
        const val DEFAULT_LIMIT = 5000
        const val MAX_LIMIT = 20000
    }
}
