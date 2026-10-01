package com.monimo.ingester.transform

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.logs.v1.LogRecord

// OTLP 로그 요청을 우리 모델(LogRow) 목록으로 옮긴다. 스프링도 ClickHouse 도 모르는 순수 코드다.
object LogTranslator {

    private const val THREAD_NAME_KEY = "thread.name" // OTel Java 에이전트가 스레드 이름을 넣는 꼬리표 (설정이 꺼져 있으면 없다)

    fun toRows(request: ExportLogsServiceRequest): List<LogRow> =
        request.resourceLogsList.flatMap { resourceLogs ->
            val origin = resourceLogs.resource.origin()
            resourceLogs.scopeLogsList.flatMap { scopeLogs ->
                // 로거 이름은 scope(계측 라이브러리) 이름에 들어온다. OTel Java 의 로그 appender 가 그렇게 보낸다
                val logger = scopeLogs.scope.name
                scopeLogs.logRecordsList.map { record -> toRow(record, origin, logger) }
            }
        }

    private fun toRow(record: LogRecord, origin: Origin, logger: String): LogRow {
        val attributes = record.attributesList.toStringMap()
        return LogRow(
            traceId = record.traceId.toHex(),
            spanId = record.spanId.toHex(),
            // 앱이 찍은 시각이 없으면(0) 수집기가 받은 시각을 쓴다. 둘 다 0 이면 1970 년이 되지만 그런 레코드는 OTel 이 안 보낸다
            ts = (if (record.timeUnixNano != 0L) record.timeUnixNano else record.observedTimeUnixNano).nanosToInstant(),
            serviceName = origin.serviceName,
            agentId = origin.agentId,
            logger = logger,
            thread = attributes[THREAD_NAME_KEY] ?: "",
            level = levelOf(record),
            message = record.body.asString(),
            attributes = attributes,
        )
    }

    // 글자 등급(severity_text)이 있으면 대문자로. 없으면 숫자 등급(severity_number)의 구간으로 정한다.
    // OTel 은 1~24 를 여섯 구간으로 나눈다: 1~4 TRACE · 5~8 DEBUG · 9~12 INFO · 13~16 WARN · 17~20 ERROR · 21~24 FATAL
    private fun levelOf(record: LogRecord): String {
        if (record.severityText.isNotBlank()) return record.severityText.trim().uppercase()
        return when (record.severityNumberValue) {
            in 1..4 -> "TRACE"
            in 5..8 -> "DEBUG"
            in 9..12 -> "INFO"
            in 13..16 -> "WARN"
            in 17..20 -> "ERROR"
            in 21..24 -> "FATAL"
            else -> "" // 0 = 등급 없음
        }
    }
}
