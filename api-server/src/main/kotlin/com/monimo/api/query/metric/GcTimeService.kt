package com.monimo.api.query.metric

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.metric.dto.GcTimeResponse
import com.monimo.api.query.support.CounterDelta
import org.springframework.stereotype.Service
import kotlin.math.roundToLong

// GC 종류별 총합을 각각 1분 증가량으로 뺀 뒤 파드 · 1분마다 더한다. 거꾸로 더하고 빼면 한 줄만 재시작해도 틀어진다
@Service
class GcTimeService(
    private val gcTimeRepository: GcTimeRepository,
) {

    fun list(serviceName: String?, range: TimeRange): List<GcTimeResponse> {
        // 첫 1분도 뺄 수 있게 앞쪽을 더 읽는다
        val totals = gcTimeRepository.findTotals(serviceName, range.from.minusSeconds(LOOKBACK_SEC), range.to)
        val increases = totals.groupBy { listOf(it.serviceName, it.agentKey, it.metricName, it.seriesKey) }.values
            .flatMap { line -> CounterDelta.of(line, null) { it.total } }
            .filter { (row, _) -> !row.tsMin.isBefore(range.from) }

        return increases.groupBy { (row, _) -> Triple(row.serviceName, row.agentKey, row.tsMin) }
            .map { (key, rows) ->
                GcTimeResponse(
                    serviceName = key.first,
                    agentKey = key.second,
                    tsMin = key.third,
                    gcTimeMs = toMillis(rows.filter { it.first.metricName == GcTimeRepository.TIME_METRIC }.sumOf { it.second }),
                    gcCount = rows.filter { it.first.metricName == GcTimeRepository.COUNT_METRIC }.sumOf { it.second }.roundToLong(),
                )
            }
            .sortedWith(compareBy<GcTimeResponse>({ it.tsMin }, { it.serviceName }, { it.agentKey }))
    }

    // 초 → ms. 빼기에서 생긴 소수 끝자리 잡음(400.00000000000034)을 µs 자리에서 자른다
    private fun toMillis(seconds: Double): Double = (seconds * 1_000_000).roundToLong() / 1000.0

    companion object {
        // 첫 1분의 앞 값을 찾는 거리. 에이전트가 길게는 60초마다 보내 5분이면 넉넉하다
        const val LOOKBACK_SEC = 300L
    }
}
