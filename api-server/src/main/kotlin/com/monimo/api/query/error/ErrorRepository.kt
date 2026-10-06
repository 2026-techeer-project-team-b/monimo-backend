package com.monimo.api.query.error

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.error.dto.ErrorCursor
import com.monimo.api.query.error.dto.ErrorSearch
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

// spans 에서 실패한(status_code = ERROR) 스팬을 시간 역순으로 읽는다. 예외는 이름이 exception 인 첫 이벤트에서 꺼낸다
@Repository
class ErrorRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {

    // 다음 쪽이 있는지 알 수 있게 limit 보다 한 줄 더 읽어 돌려준다
    fun find(search: ErrorSearch, range: TimeRange, cursor: ErrorCursor?, limit: Int): List<ErrorRow> {
        val filters = buildList {
            if (search.agentKey != null) add("AND agent_id = :agentKey")
            if (search.httpStatus != null) add("AND http_status = :httpStatus")
            if (search.exceptionType != null) add("AND ex_type = :exceptionType")
            if (cursor != null) add("AND (toUnixTimestamp64Nano(start_time), span_id) < (:cursorTs, :cursorId)")
        }.joinToString("\n  ")
        // 이벤트가 없으면 ex_idx 가 0 이고, 배열의 0번은 빈 값이라 ex_type 은 '' 가 된다
        val sql = """
            WITH arrayFirstIndex(n -> n = 'exception', events.name)    AS ex_idx,
                 events.attributes[ex_idx]['exception.type']            AS ex_type,
                 events.attributes[ex_idx]['exception.message']         AS ex_message
            SELECT
                trace_id,
                span_id,
                toUnixTimestamp64Nano(start_time)  AS start_ns,
                duration_ns,
                service_name,
                agent_id,
                span_name,
                toString(span_kind)                AS kind,
                toString(status_code)              AS status,
                http_status,
                ex_type,
                ex_message
            FROM spans
            WHERE service_name = :serviceName
              AND status_code = 'ERROR'
              AND start_time >= fromUnixTimestamp64Milli(:fromMs) AND start_time < fromUnixTimestamp64Milli(:toMs)
              $filters
            ORDER BY start_time DESC, span_id DESC
            LIMIT 1 BY trace_id, span_id
            LIMIT :fetch
        """.trimIndent()
        val params = mapOf(
            "serviceName" to search.serviceName,
            "fromMs" to range.from.toEpochMilli(),
            "toMs" to range.to.toEpochMilli(),
            "agentKey" to search.agentKey,
            "httpStatus" to search.httpStatus,
            "exceptionType" to search.exceptionType,
            "cursorTs" to cursor?.ts,
            "cursorId" to cursor?.id,
            "fetch" to limit + 1,
        )
        return jdbc.query(sql, params) { rs, _ ->
            ErrorRow(
                traceId = rs.getString("trace_id"),
                spanId = rs.getString("span_id"),
                startNs = rs.getLong("start_ns"),
                durationNs = rs.getLong("duration_ns"),
                serviceName = rs.getString("service_name"),
                agentKey = rs.getString("agent_id"),
                spanName = rs.getString("span_name"),
                spanKind = rs.getString("kind"),
                statusCode = rs.getString("status"),
                httpStatus = rs.getInt("http_status").takeIf { it != 0 },
                exceptionType = rs.getString("ex_type").ifEmpty { null },
                exceptionMessage = rs.getString("ex_message").ifEmpty { null },
            )
        }
    }
}

// spans 에서 읽은 실패 스팬 한 줄. 시각은 커서를 만들 수 있게 나노초 숫자로 둔다
data class ErrorRow(
    val traceId: String,
    val spanId: String,
    val startNs: Long,
    val durationNs: Long,
    val serviceName: String,
    val agentKey: String,
    val spanName: String,
    val spanKind: String,
    val statusCode: String,
    val httpStatus: Int?,
    val exceptionType: String?,
    val exceptionMessage: String?,
)
