package com.monimo.api.query.metric

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.metric.dto.MetricNameResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

// metrics_raw 에서 지표 이름마다 붙은 속성 키를 모은다. 이름 · 키 모두 글자 순
@Repository
class MetricNameRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {
    private val mapper = jacksonObjectMapper()

    fun find(serviceName: String, range: TimeRange): List<MetricNameResponse> {
        val sql = """
            SELECT metric_name, toJSONString(arraySort(groupUniqArrayArray(mapKeys(attributes)))) AS keys_json
            FROM metrics_raw
            WHERE service_name = :serviceName AND ts >= toDateTime(:fromSec) AND ts < toDateTime(:toSec)
            GROUP BY metric_name
            ORDER BY metric_name
        """.trimIndent()
        val params = mapOf(
            "serviceName" to serviceName,
            "fromSec" to range.from.epochSecond,
            "toSec" to range.to.epochSecond,
        )
        return jdbc.query(sql, params) { rs, _ ->
            MetricNameResponse(rs.getString("metric_name"), mapper.readTree(rs.getString("keys_json")).map { it.asText() })
        }
    }
}
