package com.monimo.ingester.outbound.clickhouse

import com.clickhouse.client.api.Client
import com.monimo.ingester.transform.SpanRow
import com.monimo.ingester.transform.SpanStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// SpanStore 의 ClickHouse 구현(어댑터). 도메인이 정한 규칙을 ClickHouse 로 지키는 한 가지 방법이다.
// 도메인(transform)은 이 파일을 모른다. 반대로 이 파일만 도메인을 안다 — 의존이 안쪽으로만 향한다.
// JSON 조립 · insert 호출은 세 저장소가 같이 쓰는 JsonEachRow.kt 에 있다
@Component
class ClickHouseSpanStore(private val client: Client) : SpanStore {

    override fun save(rows: List<SpanRow>) {
        if (rows.isEmpty()) return
        val written = client.insertJsonEachRow(TABLE, rows.map { it.toJsonLine() })
        log.debug("spans 적재: {}줄 (쓴 바이트 {})", rows.size, written)
    }

    private fun SpanRow.toJsonLine(): String = jsonLine {
        field("trace_id", traceId)
        field("span_id", spanId)
        field("parent_span_id", parentSpanId)
        field("start_time", ClickHouseTime.nanos(startTime)) // DateTime64(9)
        number("duration_ns", durationNs)
        field("service_name", serviceName)
        field("agent_id", agentId)
        field("span_name", spanName)
        field("span_kind", spanKind)
        field("status_code", statusCode)
        number("http_status", httpStatus)
        field("peer_address", peerAddress)
        field("peer_service", peerService)
        map("attributes", attributes)
        // Nested 컬럼은 배열 세 개로 저장된다. 키도 "events.ts" 처럼 점이 든 글자 셋이고, 세 배열 길이가 같아야 insert 가 된다.
        // 한 목록(events)에서 map 세 번으로 뽑으므로 길이는 저절로 같다. 따로 만들면 어긋날 수 있다
        array("events.ts", events.map { ClickHouseTime.nanos(it.ts) }) // DateTime64(9) — start_time 과 같은 포맷
        array("events.name", events.map { it.name })
        arrayOfMaps("events.attributes", events.map { it.attributes })
    }

    private companion object {
        const val TABLE = "spans"
        val log = LoggerFactory.getLogger(ClickHouseSpanStore::class.java)
    }
}
