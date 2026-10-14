package com.monimo.api.query.metric

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

// metrics_1m 에서 GC 시간 합 · 횟수의 1분 마지막 값(켜진 뒤 총합)을 파드 × GC 종류마다 시간순으로 읽는다.
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
                toString(series_hash)      AS series_key,
                toUnixTimestamp(ts_min)    AS minute_ts,
                argMaxMerge(last_v)        AS total
            FROM metrics_1m
            WHERE metric_name IN (:metricNames)
              AND ts_min >= toDateTime(:sinceSec) AND ts_min < toDateTime(:toSec) $serviceFilter
            GROUP BY service_name, agent_id, metric_name, series_hash, ts_min
            HAVING max(is_counter) = 1
            ORDER BY service_name, agent_id, metric_name, series_hash, ts_min
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

// 한 파드 · 한 GC 종류 · 1분의 켜진 뒤 총합
data class GcTotal(
    val serviceName: String,
    val agentKey: String,
    val metricName: String,
    val seriesKey: String,
    val tsMin: Instant,
    val total: Double,
)
