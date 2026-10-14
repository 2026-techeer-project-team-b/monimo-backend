package com.monimo.api.query.metric

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.metric.dto.MetricPoint
import com.monimo.api.query.support.Rollup
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

// 지표를 step 칸으로 묶어 읽는다. 원본은 값 그대로, 롤업 표는 중간 상태(AggregateFunction)라 *Merge 로 푼다
@Repository
class MetricRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {
    private val mapper = jacksonObjectMapper()

    // 파드 → 속성 조합 → 시각 순
    fun findPoints(serviceName: String, metricName: String, agentKey: String?, range: TimeRange, step: Int, rollup: Rollup): List<MetricRow> {
        val source = sourceOf(rollup)
        val agentFilter = if (agentKey != null) "AND agent_id = :agentKey" else ""
        val sql = """
            SELECT
                agent_id,
                toString(series_hash)                                          AS series_key,
                intDiv(toUnixTimestamp(${source.timeColumn}), :step) * :step    AS bucket,
                ${source.avg}   AS avg_val,
                ${source.min}   AS min_val,
                ${source.max}   AS max_val,
                ${source.last}  AS last_val,
                max(is_counter) AS counter
            FROM ${source.table}
            WHERE service_name = :serviceName AND metric_name = :metricName
              AND ${source.timeColumn} >= toDateTime(:fromSec) AND ${source.timeColumn} < toDateTime(:toSec) $agentFilter
            GROUP BY agent_id, series_hash, bucket
            ORDER BY agent_id, series_hash, bucket
        """.trimIndent()
        val params = mapOf(
            "serviceName" to serviceName,
            "metricName" to metricName,
            "agentKey" to agentKey,
            "step" to step,
            "fromSec" to range.from.epochSecond,
            "toSec" to range.to.epochSecond,
        )
        return jdbc.query(sql, params) { rs, _ ->
            MetricRow(
                agentKey = rs.getString("agent_id"),
                seriesKey = rs.getString("series_key"),
                point = MetricPoint(
                    tsMin = Instant.ofEpochSecond(rs.getLong("bucket")),
                    avgV = rs.getDouble("avg_val"),
                    minV = rs.getDouble("min_val"),
                    maxV = rs.getDouble("max_val"),
                    lastV = rs.getDouble("last_val"),
                ),
                counter = rs.getInt("counter") == 1,
            )
        }
    }

    // 누적 줄의 첫 칸을 빼려면 범위 앞의 마지막 값이 필요하다. since ~ before 사이에서 파드 × 속성 조합마다 하나
    fun findLastBefore(serviceName: String, metricName: String, agentKey: String?, since: Instant, before: Instant, rollup: Rollup): Map<Pair<String, String>, Double> {
        val source = sourceOf(rollup)
        val agentFilter = if (agentKey != null) "AND agent_id = :agentKey" else ""
        val sql = """
            SELECT agent_id, series_key, argMax(last_val, t) AS prev_val
            FROM (
                SELECT agent_id, toString(series_hash) AS series_key, ${source.timeColumn} AS t, ${source.last} AS last_val
                FROM ${source.table}
                WHERE service_name = :serviceName AND metric_name = :metricName
                  AND ${source.timeColumn} >= toDateTime(:sinceSec) AND ${source.timeColumn} < toDateTime(:beforeSec) $agentFilter
                GROUP BY agent_id, series_hash, t
            )
            GROUP BY agent_id, series_key
        """.trimIndent()
        val params = mapOf(
            "serviceName" to serviceName,
            "metricName" to metricName,
            "agentKey" to agentKey,
            "sinceSec" to since.epochSecond,
            "beforeSec" to before.epochSecond,
        )
        return jdbc.query(sql, params) { rs, _ ->
            (rs.getString("agent_id") to rs.getString("series_key")) to rs.getDouble("prev_val")
        }.toMap()
    }

    // 롤업 표에는 attributes 가 없고 series_hash 만 있다. 속성은 원본 표에서 같은 해시로 찾는다.
    // 원본 보관 기간이 지난 줄은 여기 없어 속성이 비게 된다
    fun findAttributes(serviceName: String, metricName: String, seriesKeys: Set<String>): Map<String, Map<String, String>> {
        if (seriesKeys.isEmpty()) return emptyMap()
        val sql = """
            SELECT toString(series_hash) AS series_key, toJSONString(any(attributes)) AS attributes_json
            FROM metrics_raw
            WHERE service_name = :serviceName AND metric_name = :metricName AND toString(series_hash) IN (:seriesKeys)
            GROUP BY series_hash
        """.trimIndent()
        val params = mapOf("serviceName" to serviceName, "metricName" to metricName, "seriesKeys" to seriesKeys)
        return jdbc.query(sql, params) { rs, _ ->
            rs.getString("series_key") to
                mapper.readTree(rs.getString("attributes_json")).properties().associate { (key, value) -> key to value.asText() }
        }.toMap()
    }

    private fun sourceOf(rollup: Rollup): Source = when (rollup) {
        Rollup.RAW -> Source("metrics_raw", "ts", "avg(value)", "min(value)", "max(value)", "argMax(value, ts)")
        Rollup.MINUTE -> Source("metrics_1m", "ts_min", "avgMerge(avg_v)", "minMerge(min_v)", "maxMerge(max_v)", "argMaxMerge(last_v)")
        Rollup.HOUR -> Source("metrics_1h", "ts_hour", "avgMerge(avg_v)", "minMerge(min_v)", "maxMerge(max_v)", "argMaxMerge(last_v)")
    }

    private data class Source(
        val table: String,
        val timeColumn: String,
        val avg: String,
        val min: String,
        val max: String,
        val last: String,
    )

    companion object {
        fun tableOf(rollup: Rollup): String = when (rollup) {
            Rollup.RAW -> "metrics_raw"
            Rollup.MINUTE -> "metrics_1m"
            Rollup.HOUR -> "metrics_1h"
        }
    }
}

// 한 줄(파드 × 속성 조합)의 점 하나. series_hash 는 UInt64 라 글자로 들고 다닌다. counter = 켜진 뒤 총합(is_counter = 1)
data class MetricRow(
    val agentKey: String,
    val seriesKey: String,
    val point: MetricPoint,
    val counter: Boolean = false,
)
