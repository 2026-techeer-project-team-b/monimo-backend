package com.monimo.api.query.health

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.health.dto.ServiceHealthResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

// service_health_1m(AggregatingMergeTree)을 step 버킷으로 다시 합쳐 읽는다. 중간 상태라 반드시 *Merge 로 푼다
@Repository
class ServiceHealthRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {

    fun find(serviceName: String?, range: TimeRange, step: Int): List<ServiceHealthResponse> {
        val serviceFilter = if (serviceName != null) "AND service_name = :serviceName" else ""
        // 시간은 유닉스 초로 주고받아 서버 시간대와 무관하게 한다. dur_q 는 나노초라 ms 로 바꾼다
        val sql = """
            SELECT
                intDiv(toUnixTimestamp(ts_min), :step) * :step        AS bucket,
                service_name,
                countMerge(cnt)                                      AS cnt,
                sumMerge(err_cnt)                                    AS err_cnt,
                sumMerge(cnt_4xx)                                    AS cnt_4xx,
                sumMerge(cnt_5xx)                                    AS cnt_5xx,
                quantilesTDigestMerge(0.5, 0.95, 0.99)(dur_q)        AS q,
                toInt64(round(q[1] / 1000000))                       AS p50_ms,
                toInt64(round(q[2] / 1000000))                       AS p95_ms,
                toInt64(round(q[3] / 1000000))                       AS p99_ms
            FROM service_health_1m
            WHERE ts_min >= toDateTime(:from) AND ts_min < toDateTime(:to) $serviceFilter
            GROUP BY bucket, service_name
            ORDER BY bucket, service_name
        """.trimIndent()
        val params = mapOf(
            "step" to step,
            "from" to range.from.epochSecond,
            "to" to range.to.epochSecond,
            "serviceName" to serviceName,
        )
        return jdbc.query(sql, params) { rs, _ ->
            ServiceHealthResponse(
                tsMin = Instant.ofEpochSecond(rs.getLong("bucket")),
                serviceName = rs.getString("service_name"),
                cnt = rs.getLong("cnt"),
                errCnt = rs.getLong("err_cnt"),
                cnt4xx = rs.getLong("cnt_4xx"),
                cnt5xx = rs.getLong("cnt_5xx"),
                p50Ms = rs.getLong("p50_ms"),
                p95Ms = rs.getLong("p95_ms"),
                p99Ms = rs.getLong("p99_ms"),
            )
        }
    }
}
