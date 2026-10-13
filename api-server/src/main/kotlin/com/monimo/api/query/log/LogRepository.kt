package com.monimo.api.query.log

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.log.dto.LogCursor
import com.monimo.api.query.log.dto.LogSearch
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

// logs 를 시각 역순으로 읽는다. 같은 ms 안의 순서는 줄 내용의 해시로 고정해 쪽마다 같은 순서가 나오게 한다
@Repository
class LogRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {
    private val mapper = jacksonObjectMapper()

    // 다음 쪽이 있는지 알 수 있게 limit 보다 한 줄 더 읽어 돌려준다
    fun find(search: LogSearch, range: TimeRange, cursor: LogCursor?, limit: Int): List<LogRow> {
        val filters = buildList {
            if (search.serviceName != null) add("AND service_name = :serviceName")
            if (search.agentKey != null) add("AND agent_id = :agentKey")
            if (search.levels.isNotEmpty()) add("AND level IN (:levels)")
            if (search.loggerPrefix != null) add("AND startsWith(logger, :loggerPrefix)")
            if (search.traceId != null) add("AND trace_id = :traceId")
            if (search.text != null) add("AND positionCaseInsensitiveUTF8(message, :text) > 0")
            // 커서 시각과 같은 ms 의 줄도 다시 읽고, 그중 이미 준 만큼을 OFFSET 으로 건너뛴다
            if (cursor != null) add("AND ts <= fromUnixTimestamp64Milli(:cursorTs)")
        }.joinToString("\n  ")
        val sql = """
            SELECT
                toUnixTimestamp64Milli(ts)     AS ts_ms,
                service_name,
                agent_id,
                level,
                logger,
                thread,
                message,
                trace_id,
                span_id,
                toJSONString(attributes)       AS attributes_json
            FROM logs
            WHERE ts >= fromUnixTimestamp64Milli(:fromMs) AND ts < fromUnixTimestamp64Milli(:toMs)
              $filters
            ORDER BY ts DESC, cityHash64(service_name, agent_id, logger, thread, message, trace_id, span_id) DESC
            LIMIT :fetch OFFSET :skip
        """.trimIndent()
        val params = mapOf(
            "fromMs" to range.from.toEpochMilli(),
            "toMs" to range.to.toEpochMilli(),
            "serviceName" to search.serviceName,
            "agentKey" to search.agentKey,
            "levels" to search.levels,
            "loggerPrefix" to search.loggerPrefix,
            "traceId" to search.traceId,
            "text" to search.text,
            "cursorTs" to cursor?.ts,
            "fetch" to limit + 1,
            "skip" to (cursor?.skip ?: 0),
        )
        return jdbc.query(sql, params) { rs, _ ->
            LogRow(
                tsMs = rs.getLong("ts_ms"),
                serviceName = rs.getString("service_name"),
                agentKey = rs.getString("agent_id"),
                level = rs.getString("level"),
                logger = rs.getString("logger"),
                thread = rs.getString("thread"),
                message = rs.getString("message"),
                traceId = rs.getString("trace_id"),
                spanId = rs.getString("span_id"),
                attributes = mapper.readTree(rs.getString("attributes_json")).properties().associate { (key, value) -> key to value.asText() },
            )
        }
    }
}

// logs 에서 읽은 한 줄. 시각은 커서를 만들 수 있게 ms 숫자로 둔다
data class LogRow(
    val tsMs: Long,
    val serviceName: String,
    val agentKey: String,
    val level: String,
    val logger: String,
    val thread: String,
    val message: String,
    val traceId: String,
    val spanId: String,
    val attributes: Map<String, String>,
)
