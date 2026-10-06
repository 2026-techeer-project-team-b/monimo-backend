package com.monimo.api.query.transaction

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.support.NanoTime
import com.monimo.api.query.transaction.dto.ScatterPoint
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

// transactions(루트 스팬 = 요청 하나)에서 스캐터 점을 읽는다
@Repository
class ScatterRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {

    // 구간 안 요청 수와 가장 느린 응답시간. 격자를 짤 때 세로 범위로 쓴다
    fun summarize(serviceName: String, agentKey: String?, range: TimeRange): ScatterSummary =
        jdbc.query(
            """
            SELECT count() AS total_cnt, max(duration_ms) AS max_ms
            FROM transactions
            WHERE ${where(agentKey)}
            """.trimIndent(),
            params(serviceName, agentKey, range),
        ) { rs, _ -> ScatterSummary(rs.getLong("total_cnt"), rs.getLong("max_ms")) }.single()

    fun findAll(serviceName: String, agentKey: String?, range: TimeRange, limit: Int): List<ScatterPoint> =
        jdbc.query(
            """
            SELECT trace_id, toUnixTimestamp64Milli(start_time) AS start_ms, duration_ms AS dur_ms, is_error AS failed,
                   http_status AS status, span_name AS name, agent_id
            FROM transactions
            WHERE ${where(agentKey)}
            ORDER BY start_time, trace_id
            LIMIT :limit
            """.trimIndent(),
            params(serviceName, agentKey, range) + ("limit" to limit),
        ) { rs, _ -> toPoint(rs) }

    // (시간 칸 × 응답시간 칸 × 성공/실패) 격자마다 가장 느린 요청 하나를 대표로 고른다.
    // 응답시간 칸은 로그 간격이다 — 요청 대부분이 몰린 빠른 쪽을 촘촘히, 드문 느린 쪽을 넓게 나눈다
    fun findBucketed(serviceName: String, agentKey: String?, range: TimeRange, cells: Int, maxDurationMs: Long): List<ScatterPoint> =
        jdbc.query(
            """
            SELECT rep.1 AS trace_id, rep.2 AS start_ms, rep.3 AS dur_ms, failed, rep.4 AS status, rep.5 AS name, rep.6 AS agent_id
            FROM (
                SELECT
                    intDiv((toUnixTimestamp64Milli(start_time) - :fromMs) * :cells, :spanMs)      AS x_cell,
                    toUInt32(floor(:cells * log(1 + duration_ms) / log(2 + :maxMs)))              AS y_cell,
                    is_error                                                                      AS failed,
                    argMax(tuple(trace_id, toUnixTimestamp64Milli(start_time), duration_ms, http_status, span_name, agent_id), duration_ms) AS rep
                FROM transactions
                WHERE ${where(agentKey)}
                GROUP BY x_cell, y_cell, failed
            )
            ORDER BY start_ms, trace_id
            """.trimIndent(),
            params(serviceName, agentKey, range) + mapOf(
                "cells" to cells,
                "spanMs" to range.duration.toMillis(),
                "maxMs" to maxDurationMs,
            ),
        ) { rs, _ -> toPoint(rs) }

    private fun where(agentKey: String?): String =
        "service_name = :serviceName AND start_time >= fromUnixTimestamp64Milli(:fromMs) AND start_time < fromUnixTimestamp64Milli(:toMs)" +
            if (agentKey != null) " AND agent_id = :agentKey" else ""

    private fun params(serviceName: String, agentKey: String?, range: TimeRange): Map<String, Any?> = mapOf(
        "serviceName" to serviceName,
        "agentKey" to agentKey,
        "fromMs" to range.from.toEpochMilli(),
        "toMs" to range.to.toEpochMilli(),
    )

    private fun toPoint(rs: ResultSet) = ScatterPoint(
        traceId = rs.getString("trace_id"),
        startTime = NanoTime.formatMillis(rs.getLong("start_ms")),
        durationMs = rs.getLong("dur_ms"),
        isError = rs.getInt("failed") == 1,
        httpStatus = rs.getInt("status").takeIf { it != 0 },
        spanName = rs.getString("name"),
        agentKey = rs.getString("agent_id"),
    )
}

data class ScatterSummary(
    val totalCount: Long,
    val maxDurationMs: Long,
)
