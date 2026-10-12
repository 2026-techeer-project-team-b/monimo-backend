package com.monimo.api.query.canary

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

// 카나리 표식(attributes['monimo.canary'], 적재가 trace_state 에서 옮겨 적는다 — ADR #41)이 있는 스팬 중 가장 최근 것을 찾는다
@Repository
class CanaryRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {

    // since 이후에 카나리가 없으면 null
    fun findLatest(serviceName: String?, since: Instant): CanarySignal? {
        val serviceFilter = if (serviceName != null) "AND service_name = :serviceName" else ""
        val sql = """
            SELECT
                count()                                   AS span_cnt,
                argMax(service_name, start_time)          AS latest_service,
                toUnixTimestamp64Nano(max(start_time))    AS last_ns
            FROM spans
            WHERE mapContains(attributes, '$CANARY_KEY')
              AND start_time >= fromUnixTimestamp64Milli(:sinceMs) $serviceFilter
        """.trimIndent()
        val params = mapOf("sinceMs" to since.toEpochMilli(), "serviceName" to serviceName)
        return jdbc.query(sql, params) { rs, _ ->
            if (rs.getLong("span_cnt") == 0L) {
                null
            } else {
                CanarySignal(rs.getString("latest_service"), Instant.ofEpochSecond(0, rs.getLong("last_ns")))
            }
        }.single()
    }

    companion object {
        // 적재 처리기 SpanRow.CANARY_KEY 와 같은 글자여야 한다
        const val CANARY_KEY = "monimo.canary"
    }
}

data class CanarySignal(
    val serviceName: String,
    val at: Instant,
)
