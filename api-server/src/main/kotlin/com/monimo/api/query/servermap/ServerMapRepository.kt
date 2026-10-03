package com.monimo.api.query.servermap

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.servermap.dto.ServerMapEdge
import com.monimo.api.query.servermap.dto.ServerMapNode
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

// 간선은 server_map_1m(SummingMergeTree), 노드는 service_health_1m(AggregatingMergeTree)에서 읽는다
// 결과 별칭을 원래 컬럼 이름(cnt 등)과 다르게 짓는다. 같으면 뒤의 sum(cnt) 가 합계를 다시 합치려다 실패한다
@Repository
class ServerMapRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {

    fun findEdges(serviceName: String?, range: TimeRange): List<ServerMapEdge> {
        val serviceFilter = if (serviceName != null) "AND (caller_service = :serviceName OR callee_service = :serviceName)" else ""
        // SummingMergeTree 는 합치기가 언제 끝날지 몰라 같은 간선이 여러 줄일 수 있다. 읽을 때 sum 으로 다시 합친다
        val sql = """
            SELECT
                caller_service,
                callee_service,
                toString(callee_kind)   AS kind,
                sum(cnt)                AS total_cnt,
                sum(err_cnt)            AS total_err,
                sum(sum_duration_ns)    AS total_dur_ns
            FROM server_map_1m
            WHERE ts_min >= toDateTime(:from) AND ts_min < toDateTime(:to) $serviceFilter
            GROUP BY caller_service, callee_service, callee_kind
            ORDER BY caller_service, callee_service
        """.trimIndent()
        return jdbc.query(sql, params(serviceName, range)) { rs, _ ->
            val cnt = rs.getLong("total_cnt")
            ServerMapEdge(
                callerService = rs.getString("caller_service"),
                calleeService = rs.getString("callee_service"),
                calleeKind = rs.getString("kind"),
                cnt = cnt,
                errCnt = rs.getLong("total_err"),
                avgDurationMs = averageMs(rs.getLong("total_dur_ns"), cnt),
            )
        }
    }

    fun findNodes(range: TimeRange): List<ServerMapNode> {
        val sql = """
            SELECT
                service_name,
                countMerge(cnt)     AS total_cnt,
                sumMerge(err_cnt)   AS total_err
            FROM service_health_1m
            WHERE ts_min >= toDateTime(:from) AND ts_min < toDateTime(:to)
            GROUP BY service_name
            ORDER BY service_name
        """.trimIndent()
        return jdbc.query(sql, params(null, range)) { rs, _ ->
            ServerMapNode(rs.getString("service_name"), rs.getLong("total_cnt"), rs.getLong("total_err"))
        }
    }

    private fun params(serviceName: String?, range: TimeRange) = mapOf(
        "from" to range.from.epochSecond,
        "to" to range.to.epochSecond,
        "serviceName" to serviceName,
    )

    // 소수 첫째 자리까지 (명세 예: 214.7)
    private fun averageMs(totalNs: Long, cnt: Long): Double =
        if (cnt == 0L) 0.0 else Math.round(totalNs.toDouble() / cnt / 100_000) / 10.0
}
