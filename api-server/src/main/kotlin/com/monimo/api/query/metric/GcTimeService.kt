package com.monimo.api.query.metric

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.metric.dto.GcTimeResponse
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

// GC 종류별 총합을 각각 1분 증가량으로 바꾼 뒤 파드 · 1분마다 더한다. 거꾸로 더하고 빼면 한 줄만 재시작해도 틀어진다
@Service
class GcTimeService(
    private val gcTimeRepository: GcTimeRepository,
) {

    fun list(serviceName: String?, range: TimeRange): List<GcTimeResponse> {
        // 첫 1분도 뺄 수 있게 앞쪽을, 마지막 1분이 비었을 때 메울 수 있게 뒤쪽을 더 읽는다
        val totals = gcTimeRepository.findTotals(
            serviceName,
            range.from.minusSeconds(LOOKBACK_SEC),
            range.to.plusSeconds(MAX_GAP_SEC),
        )
        val increases = totals.groupBy { listOf(it.serviceName, it.agentKey, it.metricName, it.seriesKey) }.values
            .flatMap { perMinute(it) }
            .filter { !it.minute.isBefore(range.from) && it.minute.isBefore(range.to) }

        return increases.groupBy { Triple(it.line.serviceName, it.line.agentKey, it.minute) }
            .map { (key, rows) ->
                GcTimeResponse(
                    serviceName = key.first,
                    agentKey = key.second,
                    tsMin = key.third,
                    gcTimeMs = toMillis(rows.filter { it.line.metricName == GcTimeRepository.TIME_METRIC }.sumOf { it.increase }),
                    gcCount = rows.filter { it.line.metricName == GcTimeRepository.COUNT_METRIC }.sumOf { it.increase }.roundToLong(),
                )
            }
            .sortedWith(compareBy<GcTimeResponse>({ it.tsMin }, { it.serviceName }, { it.agentKey }))
    }

    // 한 시계열의 1분 마지막 값들을 1분 증가량으로 바꾼다. 바로 앞 값과 빼되, 두 값이 실제로 몇 초 떨어졌는지로 나눠 60초치로 맞춘다.
    // 에이전트가 60초마다 보내면 보내는 시각이 분 경계를 넘나들어 어떤 1분은 점이 없고 다음 1분에 점이 둘 생긴다.
    // 그때 그냥 빼면 다음 1분에 2분치가 들어가 오탐이 나고, 빈 1분은 판정 불가가 되어 연속 위반이 끊긴다.
    // 그래서 ① 증가량을 실제 간격으로 나누고 ② 두 점 사이에 통째로 들어간 빈 1분에도 같은 값을 채운다.
    // 간격이 MAX_GAP_SEC 보다 길면 전송이 실제로 빠진 것이라 짐작하지 않고 버린다(빈 1분도 안 채운다).
    // 뺀 값이 음수면 재시작(총합이 0 부터 다시)이라 그 1분도 버린다
    private fun perMinute(line: List<GcTotal>): List<Increase> =
        line.zipWithNext().flatMap { (before, current) ->
            val elapsed = Duration.between(before.lastTs, current.lastTs).seconds
            val increase = current.total - before.total
            if (elapsed <= 0 || elapsed > MAX_GAP_SEC || increase < 0) return@flatMap emptyList()
            val perMinute = increase * MINUTE_SEC / elapsed
            // before 와 current 사이의 빈 1분들 + current 의 1분
            generateSequence(before.tsMin.plusSeconds(MINUTE_SEC)) { it.plusSeconds(MINUTE_SEC) }
                .takeWhile { !it.isAfter(current.tsMin) }
                .map { minute -> Increase(current, minute, perMinute) }
                .toList()
        }

    // 한 시계열(line 은 그 시계열의 아무 줄)의 1분 증가량
    private data class Increase(val line: GcTotal, val minute: Instant, val increase: Double)

    // 초 → ms. 빼기에서 생긴 소수 끝자리 잡음(400.00000000000034)을 µs 자리에서 자른다
    private fun toMillis(seconds: Double): Double = (seconds * 1_000_000).roundToLong() / 1000.0

    companion object {
        // 첫 1분의 앞 값을 찾는 거리. 에이전트가 길게는 60초마다 보내 5분이면 넉넉하다
        const val LOOKBACK_SEC = 300L

        // 두 점 사이를 1분 증가량으로 나눠 믿는 최대 간격. 60초 전송이 밀렸다 따라잡으면 두 점이 최대 약 120초 떨어지고,
        // 거기에 시각 흔들림을 더했다. 이보다 길면 전송이 빠진 것이다
        const val MAX_GAP_SEC = 150L

        private const val MINUTE_SEC = 60L
    }
}
