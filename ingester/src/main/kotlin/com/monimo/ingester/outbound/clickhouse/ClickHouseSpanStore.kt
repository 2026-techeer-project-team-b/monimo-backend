package com.monimo.ingester.outbound.clickhouse

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.insert.InsertSettings
import com.monimo.ingester.transform.SpanRow
import com.monimo.ingester.transform.SpanStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// SpanStore 의 ClickHouse 구현(어댑터). 도메인이 정한 규칙을 ClickHouse 로 지키는 한 가지 방법이다.
// 도메인(transform)은 이 파일을 모른다. 반대로 이 파일만 도메인을 안다 — 의존이 안쪽으로만 향한다.
//
// 넣는 방식: 한 메시지의 스팬을 모아 한 번에 보낸다. ClickHouse 는 한 줄씩 넣으면 작은 조각이 쌓여
// 병합 비용이 커지므로, 배치로 넣는 것이 권장 방식이다.
@Component
class ClickHouseSpanStore(private val client: Client) : SpanStore {

    override fun save(rows: List<SpanRow>) {
        if (rows.isEmpty()) return

        // JSONEachRow = 한 줄에 JSON 하나. 사람이 읽을 수 있어 문제가 생겼을 때 들여다보기 쉽다
        val payload = rows.joinToString("\n") { it.toJsonLine() }
        client.insert(TABLE, ByteArrayInputStream(payload.toByteArray()), FORMAT, InsertSettings()).get().use { response ->
            log.debug("spans 적재: {}줄 (쓴 바이트 {})", rows.size, response.writtenBytes)
        }
    }

    private fun SpanRow.toJsonLine(): String = buildString {
        append('{')
        appendField("trace_id", traceId); append(',')
        appendField("span_id", spanId); append(',')
        appendField("parent_span_id", parentSpanId); append(',')
        // DateTime64(9) 는 "2026-09-30 01:02:03.123456789" 모양을 받는다. 시간대는 UTC 로 고정한다
        appendField("start_time", START_TIME.format(startTime.atOffset(ZoneOffset.UTC))); append(',')
        append("\"duration_ns\":").append(durationNs); append(',')
        appendField("service_name", serviceName); append(',')
        appendField("agent_id", agentId); append(',')
        appendField("span_name", spanName); append(',')
        appendField("span_kind", spanKind); append(',')
        appendField("status_code", statusCode); append(',')
        append("\"http_status\":").append(httpStatus); append(',')
        appendField("peer_address", peerAddress); append(',')
        appendField("peer_service", peerService); append(',')
        append("\"attributes\":").append(attributes.toJsonObject())
        append('}')
    }

    private fun StringBuilder.appendField(name: String, value: String) {
        append('"').append(name).append("\":").append(value.quoted())
    }

    private fun Map<String, String>.toJsonObject(): String =
        entries.joinToString(",", "{", "}") { (key, value) -> "${key.quoted()}:${value.quoted()}" }

    // JSON 문자열로 감싼다. 따옴표 · 역슬래시 · 줄바꿈이 값 안에 있으면 한 줄 JSON 이 깨지므로 escape 한다
    private fun String.quoted(): String = buildString {
        append('"')
        this@quoted.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private companion object {
        const val TABLE = "spans"
        const val FORMAT_NAME = "JSONEachRow"
        val FORMAT = com.clickhouse.data.ClickHouseFormat.valueOf(FORMAT_NAME)
        val START_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS")
        val log = LoggerFactory.getLogger(ClickHouseSpanStore::class.java)
    }
}
