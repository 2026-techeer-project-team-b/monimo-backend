package com.monimo.api.query.metric

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

// metrics_raw 에서 GC 시간 합 · 횟수의 1분 마지막 값(켜진 뒤 총합)과 그 값이 찍힌 시각을 파드 × GC 종류마다 시간순으로 읽는다.
// metrics_1m 은 마지막 값만 남고 시각이 없어, 두 값 사이가 실제로 몇 초였는지 몰라 원본을 읽는다.
// 총합 표시(is_counter = 1)가 있는 줄만 본다. #189 전에 들어온 줄은 표시가 없어 빠진다
@Repository
class GcTimeRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {

    fun findTotals(serviceName: String?, since: Instant, to: Instant): List<GcTotal> {
        val serviceFilter = if (serviceName != null) "AND service_name = :serviceName" else ""
        val sql = """
            SELECT
                service_name,
                agent_id,
                metric_name,
                toString(series_hash)                AS series_key,
                toUnixTimestamp(toStartOfMinute(ts)) AS minute_ts,
                toUnixTimestamp(max(ts))             AS last_ts,
                argMax(value, ts)                    AS total
            FROM metrics_raw
            WHERE metric_name IN (:metricNames)
              AND ts >= toDateTime(:sinceSec) AND ts < toDateTime(:toSec) $serviceFilter
            GROUP BY service_name, agent_id, metric_name, series_hash, minute_ts
            HAVING max(is_counter) = 1
            ORDER BY service_name, agent_id, metric_name, series_hash, minute_ts
        """.trimIndent()
        val params = mapOf(
            "metricNames" to listOf(TIME_METRIC, COUNT_METRIC),
            "sinceSec" to since.epochSecond,
            "toSec" to to.epochSecond,
            "serviceName" to serviceName,
        )
        return jdbc.query(sql, params) { rs, _ ->
            GcTotal(
                serviceName = rs.getString("service_name"),
                agentKey = rs.getString("agent_id"),
                metricName = rs.getString("metric_name"),
                seriesKey = rs.getString("series_key"),
                tsMin = Instant.ofEpochSecond(rs.getLong("minute_ts")),
                lastTs = Instant.ofEpochSecond(rs.getLong("last_ts")),
                total = rs.getDouble("total"),
            )
        }
    }

    companion object {
        // OTel jvm.gc.duration 히스토그램을 편 줄. 단위는 초
        const val TIME_METRIC = "jvm.gc.duration.sum"
        const val COUNT_METRIC = "jvm.gc.duration.count"
    }
}

// 한 파드 · 한 GC 종류 · 1분의 켜진 뒤 총합. lastTs 는 그 총합이 찍힌 시각(그 1분 안의 마지막 점)
data class GcTotal(
    val serviceName: String,
    val agentKey: String,
    val metricName: String,
    val seriesKey: String,
    val tsMin: Instant,
    val lastTs: Instant,
    val total: Double,
)
