package com.monimo.api.query.agent

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.agent.dto.ActiveAgentResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

// spans · metrics_raw 에서 파드별 가장 최근 시각을 각각 뽑아, 둘 중 더 최근 것을 고른다. 파드당 한 줄
@Repository
class ActiveAgentRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {

    fun find(serviceName: String?, range: TimeRange): List<ActiveAgentResponse> {
        val serviceFilter = if (serviceName != null) "AND service_name = :serviceName" else ""
        // agent_id 가 빈 줄은 파드를 알 수 없어 뺀다. 시각은 나노초 숫자로 맞춰 두 표를 비교한다 (metrics_raw 는 초 단위)
        val sql = """
            SELECT
                service_name,
                agent_id,
                argMax(src, last_ns)    AS source_table,
                max(last_ns)            AS last_signal_ns
            FROM (
                SELECT service_name, agent_id, 'spans' AS src, toUnixTimestamp64Nano(max(start_time)) AS last_ns
                FROM spans
                WHERE start_time >= fromUnixTimestamp64Milli(:fromMs) AND start_time < fromUnixTimestamp64Milli(:toMs)
                  AND agent_id != '' $serviceFilter
                GROUP BY service_name, agent_id
                UNION ALL
                SELECT service_name, agent_id, 'metrics_raw' AS src, toInt64(toUnixTimestamp(max(ts))) * 1000000000 AS last_ns
                FROM metrics_raw
                WHERE ts >= toDateTime(:fromSec) AND ts < fromUnixTimestamp64Milli(:toMs)
                  AND agent_id != '' $serviceFilter
                GROUP BY service_name, agent_id
            )
            GROUP BY service_name, agent_id
            ORDER BY service_name, agent_id
        """.trimIndent()
        val params = mapOf(
            "fromMs" to range.from.toEpochMilli(),
            "toMs" to range.to.toEpochMilli(),
            "fromSec" to range.from.epochSecond,
            "serviceName" to serviceName,
        )
        return jdbc.query(sql, params) { rs, _ ->
            ActiveAgentResponse(
                serviceName = rs.getString("service_name"),
                agentKey = rs.getString("agent_id"),
                lastSignalAt = Instant.ofEpochSecond(0, rs.getLong("last_signal_ns")),
                source = rs.getString("source_table"),
            )
        }
    }
}
