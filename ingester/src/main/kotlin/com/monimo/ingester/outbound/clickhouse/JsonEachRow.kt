package com.monimo.ingester.outbound.clickhouse

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.insert.InsertSettings
import com.clickhouse.data.ClickHouseFormat
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ClickHouse 에 JSONEachRow 로 넣을 때 세 저장소(스팬 · 메트릭 · 로그)가 같이 쓰는 도구들.
// JSONEachRow = 한 줄에 JSON 하나. 사람이 읽을 수 있어 문제가 생겼을 때 들여다보기 쉽다.

// 모아서 한 번에 넣는다. 한 줄씩 넣으면 ClickHouse 가 작은 조각을 너무 많이 만들어 병합 비용이 커진다.
// 돌려주는 값은 쓴 바이트 수 (로그용)
internal fun Client.insertJsonEachRow(table: String, lines: List<String>): Long {
    val payload = lines.joinToString("\n").toByteArray()
    return insert(table, ByteArrayInputStream(payload), ClickHouseFormat.JSONEachRow, InsertSettings()).get()
        .use { it.writtenBytes }
}

// CH 시각 컬럼은 정밀도마다 받는 글자 모양이 다르다. 시간대는 UTC 로 고정한다
internal object ClickHouseTime {
    private val SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") // DateTime
    private val MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS") // DateTime64(3)
    private val NANOS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS") // DateTime64(9)

    fun seconds(at: Instant): String = SECONDS.format(at.atOffset(ZoneOffset.UTC))
    fun millis(at: Instant): String = MILLIS.format(at.atOffset(ZoneOffset.UTC))
    fun nanos(at: Instant): String = NANOS.format(at.atOffset(ZoneOffset.UTC))
}

// 한 줄 JSON 을 조립하는 도구. 쓰는 쪽은 { field · number · 끝 } 만 신경 쓴다
internal fun jsonLine(build: JsonLineBuilder.() -> Unit): String =
    JsonLineBuilder().apply(build).finish()

internal class JsonLineBuilder {
    private val sb = StringBuilder("{")
    private var first = true

    private fun comma() {
        if (first) first = false else sb.append(',')
    }

    // "name":"value" — 글자 값. 따옴표 · 역슬래시 · 줄바꿈이 값 안에 있으면 한 줄 JSON 이 깨지므로 escape 한다
    fun field(name: String, value: String) {
        comma(); sb.append('"').append(name).append("\":").append(value.jsonQuoted())
    }

    // "name":123 — 숫자 값. 따옴표 없이 그대로 (Long · Int · Double · ULong 전부 toString 이 JSON 숫자 모양)
    fun number(name: String, value: Any) {
        comma(); sb.append('"').append(name).append("\":").append(value.toString())
    }

    // "name":{"k":"v",...} — CH Map(String, String) 컬럼
    fun map(name: String, value: Map<String, String>) {
        comma(); sb.append('"').append(name).append("\":").append(value.toJsonObject())
    }

    // "name":["a","b"] — 글자 배열. CH Nested 의 한 열(events.name 등)이 이 모양이다. 빈 목록이면 []
    fun array(name: String, values: List<String>) {
        comma(); sb.append('"').append(name).append("\":").append(values.joinToString(",", "[", "]") { it.jsonQuoted() })
    }

    // "name":[{"k":"v"},{...}] — Map 배열. Nested 안의 Map 열(events.attributes)
    fun arrayOfMaps(name: String, values: List<Map<String, String>>) {
        comma(); sb.append('"').append(name).append("\":").append(values.joinToString(",", "[", "]") { it.toJsonObject() })
    }

    fun finish(): String = sb.append('}').toString()
}

internal fun Map<String, String>.toJsonObject(): String =
    entries.joinToString(",", "{", "}") { (key, value) -> "${key.jsonQuoted()}:${value.jsonQuoted()}" }

internal fun String.jsonQuoted(): String = buildString {
    append('"')
    this@jsonQuoted.forEach { ch ->
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
