package com.monimo.detector.alert.evaluate

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

// 1분 버킷들에서 규칙이 볼 값 하나를 계산한다 (순수 함수). null = 판정 불가(데이터 없음)
object BucketValues {
    val SUPPORTED = setOf("5XX_RATE", "4XX_RATE", "P95_LATENCY")
    val BUCKET: Duration = Duration.ofMinutes(1)

    // bucketEnd 에 끝나는 1분 버킷을 판정한다. 그 1분의 줄이 없으면(요청 0건이거나 아직 적재 전) 판정 불가
    fun valueAt(metricKind: String, windowSec: Int, bucketEnd: Instant, buckets: Map<Instant, HealthBucket>): BigDecimal? {
        val judged = buckets[bucketEnd.minus(BUCKET)] ?: return null
        return when (metricKind) {
            "5XX_RATE" -> rate(windowOf(windowSec, bucketEnd, buckets)) { it.cnt5xx }
            "4XX_RATE" -> rate(windowOf(windowSec, bucketEnd, buckets)) { it.cnt4xx }
            // 여러 분의 p95 를 평균 내면 p95 가 아니다. 1분 p95 를 버킷마다 판정하고 "N번 연속"은 상태머신이 센다
            "P95_LATENCY" -> if (judged.cnt > 0) BigDecimal.valueOf(judged.p95Ms) else null
            else -> null
        }
    }

    // [bucketEnd - window, bucketEnd) 에 있는 줄만. 없는 분은 건너뛴다 (0 으로 채우지 않는다)
    private fun windowOf(windowSec: Int, bucketEnd: Instant, buckets: Map<Instant, HealthBucket>): List<HealthBucket> {
        val from = bucketEnd.minusSeconds(windowSec.toLong())
        return buckets.values.filter { !it.start.isBefore(from) && it.start.isBefore(bucketEnd) }
    }

    // 비율 = 가중 합. 1분 비율들의 평균은 트래픽 차이를 무시해서 틀린다. 결과는 % (규칙 threshold 와 같은 단위)
    private fun rate(rows: List<HealthBucket>, errors: (HealthBucket) -> Long): BigDecimal? {
        val total = rows.sumOf { it.cnt }
        if (total == 0L) return null
        return BigDecimal.valueOf(rows.sumOf(errors) * 100).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
    }

    // now 기준으로 판정할 버킷 끝 시각들 (오래된 순). 완료된 버킷 중 maxStaleness 안에 있는 것
    fun settledBucketEnds(now: Instant, settleDelay: Duration, maxStaleness: Duration): List<Instant> {
        val latest = floorToMinute(now.minus(settleDelay))
        val oldest = now.minus(maxStaleness)
        return generateSequence(latest) { it.minus(BUCKET) }.takeWhile { it.isAfter(oldest) }.toList().reversed()
    }

    private fun floorToMinute(t: Instant): Instant = Instant.ofEpochSecond(Math.floorDiv(t.epochSecond, 60L) * 60)
}
