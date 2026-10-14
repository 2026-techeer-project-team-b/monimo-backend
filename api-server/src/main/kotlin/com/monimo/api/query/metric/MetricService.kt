package com.monimo.api.query.metric

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.metric.dto.MetricPoint
import com.monimo.api.query.metric.dto.MetricSeriesLine
import com.monimo.api.query.metric.dto.MetricSeriesResponse
import com.monimo.api.query.support.CounterDelta
import com.monimo.api.query.support.MonitoredServices
import com.monimo.api.query.support.Rollup
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

// step 으로 읽을 표를 고르고(60 미만 원본 · 60 이상 1분 · 3600 이상 1시간), 파드 × 속성 조합마다 한 줄로 묶는다
@Service
class MetricService(
    private val metricRepository: MetricRepository,
    private val monitoredServices: MonitoredServices,
) {

    fun series(serviceName: String, metricName: String, agentKey: String?, from: Instant, to: Instant, requestedStep: Int?): MetricSeriesResponse {
        // 가장 긴 상한으로 먼저 거르고, 표가 정해지면 그 표의 상한으로 다시 본다
        val widest = TimeRange.of(from, to, max = MAX_RANGE.getValue(Rollup.HOUR))
        val step = requestedStep ?: autoStep(widest.duration)
        val rollup = Rollup.of(step)
        requireAligned(step, rollup)
        val range = TimeRange.of(from, to, max = MAX_RANGE.getValue(rollup))
        if (range.duration.seconds / step > MAX_POINTS) {
            throw ApiException(ErrorCode.TIME_RANGE_TOO_WIDE, "한 줄에 점이 ${MAX_POINTS}개를 넘습니다. step 을 키우거나 범위를 줄이세요.")
        }
        monitoredServices.require(serviceName)

        val rows = metricRepository.findPoints(serviceName, metricName, agentKey, range, step, rollup)
        val attributes = metricRepository.findAttributes(serviceName, metricName, rows.map { it.seriesKey }.toSet())
        // 조회가 파드 → 속성 조합 → 시각 순으로 줘서 groupBy 가 그 순서를 지킨다
        val grouped = rows.groupBy { it.agentKey to it.seriesKey }
        val prev = if (grouped.values.any { line -> line.any { it.counter } }) {
            val since = range.from.minusSeconds(maxOf(step.toLong(), COUNTER_LOOKBACK_SEC))
            metricRepository.findLastBefore(serviceName, metricName, agentKey, since, range.from, rollup)
        } else {
            emptyMap()
        }
        val lines = grouped.map { (key, points) ->
            val counter = points.any { it.counter }
            val values = if (counter) increases(points, prev[key]) else points.map { it.point }
            MetricSeriesLine(key.first, attributes[key.second].orEmpty(), counter, values)
        }
        return MetricSeriesResponse(metricName, MetricRepository.tableOf(rollup), step, lines)
    }

    // 총합 줄은 칸마다 마지막 값끼리 빼서 늘어난 양을 네 값에 똑같이 넣는다
    private fun increases(points: List<MetricRow>, prev: Double?): List<MetricPoint> =
        CounterDelta.of(points, prev) { it.point.lastV }.map { (row, delta) -> MetricPoint(row.point.tsMin, delta, delta, delta, delta) }

    // step 이 없으면 점이 AUTO_POINTS 를 넘지 않는 가장 작은 간격을 고른다
    private fun autoStep(duration: Duration): Int =
        AUTO_STEPS.firstOrNull { duration.seconds / it <= AUTO_POINTS } ?: AUTO_STEPS.last()

    // 롤업 표는 1분 · 1시간 칸으로 이미 묶여 있어 그 배수로만 다시 묶을 수 있다
    private fun requireAligned(step: Int, rollup: Rollup) {
        val unit = when (rollup) {
            Rollup.RAW -> return
            Rollup.MINUTE -> 60
            Rollup.HOUR -> 3600
        }
        if (step % unit != 0) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "step 이 $unit 이상이면 $unit 의 배수여야 합니다.")
        }
    }

    companion object {
        // 표마다 조회 상한이 다르다. 촘촘한 표일수록 짧게 (#19 에서 기본 7일로 두고 여기서 확정)
        val MAX_RANGE: Map<Rollup, Duration> = mapOf(
            Rollup.RAW to TimeRange.DEFAULT_MAX,
            Rollup.MINUTE to Duration.ofDays(90),
            Rollup.HOUR to Duration.ofDays(365),
        )

        // 한 줄에 줄 수 있는 점의 상한
        const val MAX_POINTS = 2000

        // step 을 자동으로 고를 때 넘지 않게 할 점 수와 후보 간격(초)
        const val AUTO_POINTS = 400
        val AUTO_STEPS = listOf(60, 300, 900, 1800, 3600, 10800, 21600, 43200, 86400)

        // 총합 줄의 첫 칸에 쓸 앞 값을 찾는 거리(초). 에이전트가 길게는 60초마다 보내 step 이 작아도 5분은 본다
        const val COUNTER_LOOKBACK_SEC = 300L
    }
}
