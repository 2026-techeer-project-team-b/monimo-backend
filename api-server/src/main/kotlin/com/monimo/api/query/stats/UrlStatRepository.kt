package com.monimo.api.query.stats

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.stats.dto.UrlStatCursor
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

// url_stats_1m 의 1분 · 파드별 중간 상태를 URL 마다 다시 합친다. 받은 요청(SERVER 스팬)만 들어 있다
@Repository
class UrlStatRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {

    fun find(serviceName: String, agentKey: String?, range: TimeRange, cursor: UrlStatCursor?, limit: Int): List<UrlStatRow> {
        val agentFilter = if (agentKey != null) "AND agent_id = :agentKey" else ""
        val cursorFilter = if (cursor != null) "HAVING total_cnt < :cursorCnt OR (total_cnt = :cursorCnt AND span_name > :cursorName)" else ""
        // 별칭을 원래 칸 이름(cnt · err_cnt)과 같게 두면 집계 안에서 서로를 가리켜 ILLEGAL_AGGREGATION 이 난다
        val sql = """
            SELECT
                span_name,
                countMerge(cnt)                                     AS total_cnt,
                sumMerge(err_cnt)                                   AS total_err,
                quantilesTDigestMerge(0.5, 0.95, 0.99)(dur_q)       AS q,
                toInt64(round(q[1] / 1000000))                      AS p50_ms,
                toInt64(round(q[2] / 1000000))                      AS p95_ms,
                toInt64(round(q[3] / 1000000))                      AS p99_ms
            FROM url_stats_1m
            WHERE service_name = :serviceName
              AND ts_min >= toDateTime(:fromSec) AND ts_min < toDateTime(:toSec) $agentFilter
            GROUP BY span_name
            $cursorFilter
            ORDER BY total_cnt DESC, span_name ASC
            LIMIT :rowLimit
        """.trimIndent()
        val params = mapOf(
            "serviceName" to serviceName,
            "agentKey" to agentKey,
            "fromSec" to range.from.epochSecond,
            "toSec" to range.to.epochSecond,
            "cursorCnt" to cursor?.cnt,
            "cursorName" to cursor?.name,
            "rowLimit" to limit + 1,
        )
        return jdbc.query(sql, params) { rs, _ ->
            UrlStatRow(
                spanName = rs.getString("span_name"),
                cnt = rs.getLong("total_cnt"),
                errCnt = rs.getLong("total_err"),
                p50Ms = rs.getLong("p50_ms"),
                p95Ms = rs.getLong("p95_ms"),
                p99Ms = rs.getLong("p99_ms"),
            )
        }
    }
}

data class UrlStatRow(
    val spanName: String,
    val cnt: Long,
    val errCnt: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
)
