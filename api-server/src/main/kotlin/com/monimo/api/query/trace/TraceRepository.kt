package com.monimo.api.query.trace

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

// trace_id 로 spans 를 평면 조회한다 (bloom_filter 인덱스). Map · Nested 는 JDBC 타입 대신 JSON 글자로 받아 푼다
@Repository
class TraceRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {
    private val mapper = jacksonObjectMapper()

    fun findSpans(traceId: String): List<SpanRecord> {
        // 적재가 같은 스팬을 두 번 넣어도(Kafka 재전송) 트리에 한 번만 나오도록 span_id 당 한 줄
        val sql = """
            SELECT
                span_id,
                parent_span_id,
                service_name,
                agent_id,
                span_name,
                toString(span_kind)                  AS span_kind,
                toUnixTimestamp64Nano(start_time)    AS start_ns,
                duration_ns,
                toString(status_code)                AS status_code,
                http_status,
                toJSONString(attributes)             AS attributes_json,
                toJSONString(arrayMap(
                    (t, n, a) -> CAST((toUnixTimestamp64Nano(t), toString(n), CAST(a, 'Map(String, String)')),
                                      'Tuple(ts Int64, name String, attributes Map(String, String))'),
                    events.ts, events.name, events.attributes))  AS events_json
            FROM spans
            WHERE trace_id = :traceId
            ORDER BY start_time
            LIMIT 1 BY span_id
        """.trimIndent()
        return jdbc.query(sql, mapOf("traceId" to traceId)) { rs, _ ->
            SpanRecord(
                spanId = rs.getString("span_id"),
                parentSpanId = rs.getString("parent_span_id").ifEmpty { null },
                serviceName = rs.getString("service_name"),
                agentKey = rs.getString("agent_id"),
                spanName = rs.getString("span_name"),
                spanKind = rs.getString("span_kind"),
                startNs = rs.getLong("start_ns"),
                durationNs = rs.getLong("duration_ns"),
                statusCode = rs.getString("status_code"),
                httpStatus = rs.getInt("http_status").takeIf { it != 0 },
                attributes = toStringMap(mapper.readTree(rs.getString("attributes_json"))),
                events = mapper.readTree(rs.getString("events_json")).map {
                    SpanEventRecord(it["ts"].asLong(), it["name"].asText(), toStringMap(it["attributes"]))
                },
            )
        }
    }

    private fun toStringMap(node: JsonNode): Map<String, String> =
        node.properties().associate { (key, value) -> key to value.asText() }
}
