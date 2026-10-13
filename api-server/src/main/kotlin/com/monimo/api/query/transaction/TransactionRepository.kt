package com.monimo.api.query.transaction

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.transaction.dto.TransactionCursor
import com.monimo.api.query.transaction.dto.TransactionSearch
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

// transactions(서비스가 받은 요청 = 1줄, ADR #52)에서 조건에 맞는 요청을 느린 순으로 읽는다
@Repository
class TransactionRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {

    // 다음 쪽이 있는지 알 수 있게 limit 보다 한 줄 더 읽어 돌려준다
    fun find(search: TransactionSearch, range: TimeRange, cursor: TransactionCursor?, limit: Int): List<TransactionRow> {
        val filters = buildList {
            if (search.agentKey != null) add("AND agent_id = :agentKey")
            if (search.minDurationMs != null) add("AND duration_ms >= :minMs")
            if (search.maxDurationMs != null) add("AND duration_ms <= :maxMs")
            if (search.isError != null) add("AND is_error = :isError")
            if (cursor != null) add("AND (duration_ms, toUnixTimestamp64Milli(start_time), trace_id) < (:cursorDur, :cursorTs, :cursorId)")
        }.joinToString("\n  ")
        val sql = """
            SELECT
                trace_id,
                toUnixTimestamp64Milli(start_time)  AS start_ms,
                duration_ms                         AS dur_ms,
                service_name,
                agent_id,
                span_name                           AS name,
                is_error                            AS failed,
                http_status                         AS status
            FROM transactions
            WHERE service_name = :serviceName
              AND start_time >= fromUnixTimestamp64Milli(:fromMs) AND start_time < fromUnixTimestamp64Milli(:toMs)
              $filters
            ORDER BY duration_ms DESC, start_time DESC, trace_id DESC
            LIMIT :fetch
        """.trimIndent()
        val params = mapOf(
            "serviceName" to search.serviceName,
            "fromMs" to range.from.toEpochMilli(),
            "toMs" to range.to.toEpochMilli(),
            "agentKey" to search.agentKey,
            "minMs" to search.minDurationMs,
            "maxMs" to search.maxDurationMs,
            "isError" to search.isError?.let { if (it) 1 else 0 },
            "cursorDur" to cursor?.dur,
            "cursorTs" to cursor?.ts,
            "cursorId" to cursor?.id,
            "fetch" to limit + 1,
        )
        return jdbc.query(sql, params) { rs, _ ->
            TransactionRow(
                traceId = rs.getString("trace_id"),
                startMs = rs.getLong("start_ms"),
                durationMs = rs.getLong("dur_ms"),
                serviceName = rs.getString("service_name"),
                agentKey = rs.getString("agent_id"),
                spanName = rs.getString("name"),
                isError = rs.getInt("failed") == 1,
                httpStatus = rs.getInt("status").takeIf { it != 0 },
            )
        }
    }
}

// transactions 에서 읽은 한 줄. 시각은 커서를 만들 수 있게 ms 숫자로 둔다
data class TransactionRow(
    val traceId: String,
    val startMs: Long,
    val durationMs: Long,
    val serviceName: String,
    val agentKey: String,
    val spanName: String,
    val isError: Boolean,
    val httpStatus: Int?,
)
