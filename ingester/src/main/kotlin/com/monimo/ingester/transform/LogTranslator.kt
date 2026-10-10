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

    // 등급은 숫자(severity_number)로 정한다. 글자(severity_text)는 숫자가 없을 때만 본다 (ADR #59).
    //
    // 왜 숫자가 정본인가: OTel 규약이 severity_text 를 "출처가 부르는 이름 그대로" 인 자유 문자열로 정의한다.
    // 그래서 로깅 도구마다 글자가 다르다 : Logback · Log4j2 는 WARN · ERROR, java.util.logging 은 WARNING · SEVERE,
    // Python 은 WARNING · CRITICAL. 글자를 그대로 넣으면 같은 뜻이 다른 값으로 쌓여 등급으로 세거나 거를 때
    // 조용히 몇 줄을 빠뜨린다. 숫자는 1~24 를 여섯 구간으로 나눈 정규화된 값이라 그 문제가 없다.
    //
    // 글자와 숫자가 어긋나면 숫자가 이긴다. 규약은 둘이 어긋날 때 누가 이기는지를 안 정해 뒀고,
    // "크기를 비교할 때는 숫자를 쓰라" 까지만 말한다. 그 문장을 근거로 우리가 숫자를 정본으로 고른 것이다.
    private fun levelOf(record: LogRecord): String =
        fromNumber(record.severityNumberValue) ?: fromText(record.severityText)

    // OTel 규약의 구간표. 구간마다 네 단(WARN · WARN2 · WARN3 · WARN4)이 있는데 우리는 구간 이름 하나로 모은다:
    // logs.level 은 거르는 칸이라 여섯으로 충분하고, LowCardinality(String) 이라 값의 종류가 적어야 한다
    private fun fromNumber(number: Int): String? = when (number) {
        in 1..4 -> "TRACE"
        in 5..8 -> "DEBUG"
        in 9..12 -> "INFO"
        in 13..16 -> "WARN"
        in 17..20 -> "ERROR"
        in 21..24 -> "FATAL"
        else -> null // 0 = 등급 없음. 규약 밖의 값도 여기로 온다
    }

    // 숫자가 없을 때만 쓴다. 우리가 붙이는 OTel Java 에이전트는 늘 숫자를 채우므로, 이 길은 직접 OTLP 를 만들어
    // 보내는 쪽(다른 언어 · 손으로 만든 전송)에만 걸린다.
    // 표에 없는 글자는 그대로 넣지 않고 빈 글자로 둔다 : LogRow.level 이 약속한 값 범위를 여섯 + 빈 글자로 닫아야
    // 조회 쪽이 고를 목록을 알 수 있다. 본문(message)은 그대로 남으므로 내용이 사라지지는 않는다
    // uppercase() 는 기본 로케일이 아니라 Locale.ROOT 를 쓴다(stdlib 구현). 터키어 로케일에서 i 가 점 있는 대문자로 바뀌어
    // INFO 와 안 맞는 문제는 생기지 않는다. 옛 toUpperCase() 를 쓰면 그 문제가 생긴다
    private fun fromText(text: String): String = ALIASES[text.trim().uppercase()] ?: ""

    // OTel 규약 부록 B(출처별 예시 매핑)의 일곱 열에 실제로 있는 이름만 옮겼다. 완전한 목록이 아니다.
    // 각 이름은 그 출처가 배정받은 숫자의 **구간**으로 맞춘다. 이름만 보고 구간을 짐작하면 틀린다 (아래 주석 참고)
    private val ALIASES = mapOf(
        // 표준 여섯 이름 자신 (Log4j · Logback · SLF4J)
        "TRACE" to "TRACE",
        "DEBUG" to "DEBUG",
        "INFO" to "INFO",
        "WARN" to "WARN",
        "ERROR" to "ERROR",
        "FATAL" to "FATAL",
        // java.util.logging. Spring Boot 가 SLF4J 로 넘기지 못한 라이브러리 로그가 이 이름으로 온다.
        // FINEST 만 TRACE(1) 이고 FINER · FINE · CONFIG 는 셋 다 DEBUG 구간(5 · 6 · 7)이다
        "FINEST" to "TRACE",
        "FINER" to "DEBUG",
        "FINE" to "DEBUG",
        "CONFIG" to "DEBUG",
        "WARNING" to "WARN",
        "SEVERE" to "ERROR",
        // syslog(RFC 5424) 의 우선순위 낱말. 숫자가 작을수록 심각해서 이름 순서와 구간이 뒤집혀 보인다 :
        // Emergency(0) 만 FATAL(21) 이고 Alert(1) · Critical(2) · Error(3) 는 셋 다 ERROR 구간(19 · 18 · 17)이다.
        // CRIT 은 아래 CRITICAL 과 달리 출처가 syslog 하나로 정해져 모호하지 않다
        "EMERGENCY" to "FATAL",
        "EMERG" to "FATAL",
        "ALERT" to "ERROR",
        "CRIT" to "ERROR",
        "ERR" to "ERROR",
        "NOTICE" to "INFO",
        // Windows 이벤트 로그 · ETW · .NET. VERBOSE 는 TRACE 가 아니라 DEBUG(5) 다
        "VERBOSE" to "DEBUG",
        "INFORMATION" to "INFO",
        "INFORMATIONAL" to "INFO",
        // Go 의 zap. Panic 과 Dpanic 은 FATAL 이 아니라 ERROR 구간(19 · 18)이고 Fatal 만 FATAL(21) 이다
        "PANIC" to "ERROR",
        "DPANIC" to "ERROR",
        // CRITICAL 만 출처끼리 구간이 다르다 : .NET 과 ETW 는 FATAL(21), syslog 와 Windows 이벤트 로그는 ERROR 구간(18) 이다.
        // 글자만 보고는 출처를 모른다. 더 심한 쪽으로 둔다 : Python 과 .NET 에서 CRITICAL 이 가장 높은 등급이고,
        // syslog 쪽의 더 심한 둘(Alert · Emergency)은 위에서 따로 받고 있다
        "CRITICAL" to "FATAL",
    )
}
