package com.monimo.api.query.agent

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

// metrics_1m 에서 파드의 jvm.thread.count 가장 최근 1분을 읽는다
@Repository
class ActiveThreadsRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {

    // since 이후에 값이 없으면 null.
    // OTel 은 이 지표를 데몬 여부 · 스레드 상태별 줄로 나눠 보내므로, 줄마다 마지막 값을 구해 더해야 파드의 스레드 수다
    fun findLatest(serviceName: String, agentKey: String, since: Instant): ThreadCount? {
        val sql = """
            SELECT toUnixTimestamp(ts_min) AS minute_ts, sum(series_last) AS thread_total
            FROM (
                SELECT ts_min, argMaxMerge(last_v) AS series_last
                FROM metrics_1m
                WHERE service_name = :serviceName AND agent_id = :agentKey AND metric_name = '$METRIC_NAME'
                  AND ts_min >= toDateTime(:sinceSec)
                GROUP BY ts_min, series_hash
            )
            GROUP BY ts_min
            ORDER BY ts_min DESC
            LIMIT 1
        """.trimIndent()
        val params = mapOf("serviceName" to serviceName, "agentKey" to agentKey, "sinceSec" to since.epochSecond)
        return jdbc.query(sql, params) { rs, _ ->
            ThreadCount(Instant.ofEpochSecond(rs.getLong("minute_ts")), Math.round(rs.getDouble("thread_total")))
        }.firstOrNull()
    }

    companion object {
        const val METRIC_NAME = "jvm.thread.count"
    }
}

data class ThreadCount(
    val tsMin: Instant,
    val count: Long,
)
