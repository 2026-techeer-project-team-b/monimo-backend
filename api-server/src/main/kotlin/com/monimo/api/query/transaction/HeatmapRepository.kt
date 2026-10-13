package com.monimo.api.query.transaction

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.transaction.dto.HeatmapCell
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

// heatmap_1m(SummingMergeTree)을 step 칸으로 다시 묶는다. 합치기가 끝나기 전에는 같은 칸이 여러 줄이라 읽을 때 sum 으로 합친다
@Repository
class HeatmapRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {

    fun find(serviceName: String, range: TimeRange, step: Int): List<HeatmapCell> {
        val sql = """
            SELECT
                intDiv(toUnixTimestamp(ts_min), :step) * :step  AS bucket,
                latency_bucket                                  AS latency,
                is_error                                        AS failed,
                sum(cnt)                                        AS total_cnt
            FROM heatmap_1m
            WHERE service_name = :serviceName
              AND ts_min >= toDateTime(:fromSec) AND ts_min < toDateTime(:toSec)
            GROUP BY bucket, latency, failed
            ORDER BY bucket, latency, failed
        """.trimIndent()
        val params = mapOf(
            "serviceName" to serviceName,
            "step" to step,
            "fromSec" to range.from.epochSecond,
            "toSec" to range.to.epochSecond,
        )
        return jdbc.query(sql, params) { rs, _ ->
            HeatmapCell(
                tsMin = Instant.ofEpochSecond(rs.getLong("bucket")),
                latencyBucket = rs.getInt("latency"),
                isError = rs.getInt("failed") == 1,
                cnt = rs.getLong("total_cnt"),
            )
        }
    }
}
