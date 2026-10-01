package com.monimo.ingester.outbound.clickhouse

import com.clickhouse.client.api.Client
import com.monimo.ingester.transform.LogRow
import com.monimo.ingester.transform.LogStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// LogStore 의 ClickHouse 구현(어댑터)
@Component
class ClickHouseLogStore(private val client: Client) : LogStore {

    override fun save(rows: List<LogRow>) {
        if (rows.isEmpty()) return
        val written = client.insertJsonEachRow(TABLE, rows.map { it.toJsonLine() })
        log.debug("logs 적재: {}줄 (쓴 바이트 {})", rows.size, written)
    }

    private fun LogRow.toJsonLine(): String = jsonLine {
        field("trace_id", traceId)
        field("span_id", spanId)
        field("ts", ClickHouseTime.millis(ts)) // DateTime64(3)
        field("service_name", serviceName)
        field("agent_id", agentId)
        field("logger", logger)
        field("thread", thread)
        field("level", level)
        field("message", message)
        map("attributes", attributes)
    }

    private companion object {
        const val TABLE = "logs"
        val log = LoggerFactory.getLogger(ClickHouseLogStore::class.java)
    }
}
