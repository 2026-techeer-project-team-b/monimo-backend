package com.monimo.ingester.transform

import com.monimo.common.log.LogSeverity
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

    // 등급은 숫자(severity_number)로 정한다. 글자(severity_text)는 숫자가 없을 때만 본다 (ADR #59).
    // 판정 규칙과 별칭 표는 common 의 LogSeverity 한 곳에 있다 : 수집기의 로그 하한(#215)도 같은 표를 봐야
    // 「숫자 0 + 글자 DEBUG」가 한쪽만 빠져나가는 일이 없다 (#236). 여기서는 proto 에서 두 칸을 꺼내 넘기기만 한다
    private fun levelOf(record: LogRecord): String =
        LogSeverity.nameOf(record.severityNumberValue, record.severityText)
}
