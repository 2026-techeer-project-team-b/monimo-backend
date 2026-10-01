package com.monimo.ingester.outbound.clickhouse

import com.clickhouse.client.api.Client
import com.monimo.ingester.transform.MetricRow
import com.monimo.ingester.transform.MetricStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// MetricStore 의 ClickHouse 구현(어댑터). metrics_raw 에 넣으면 MV 가 metrics_1m · metrics_1h 를 자동으로 채운다
@Component
class ClickHouseMetricStore(private val client: Client) : MetricStore {

    override fun save(rows: List<MetricRow>) {
        if (rows.isEmpty()) return
        val written = client.insertJsonEachRow(TABLE, rows.map { it.toJsonLine() })
        log.debug("metrics_raw 적재: {}줄 (쓴 바이트 {})", rows.size, written)
    }

    private fun MetricRow.toJsonLine(): String = jsonLine {
        field("service_name", serviceName)
        field("agent_id", agentId)
        field("metric_name", metricName)
        number("series_hash", seriesHash) // UInt64. ULong 의 toString 은 부호 없는 숫자 글자
        map("attributes", attributes)
        field("ts", ClickHouseTime.seconds(ts)) // DateTime(초). 초 아래는 여기서 잘린다
        number("value", value)
    }

    private companion object {
        const val TABLE = "metrics_raw"
        val log = LoggerFactory.getLogger(ClickHouseMetricStore::class.java)
    }
}
