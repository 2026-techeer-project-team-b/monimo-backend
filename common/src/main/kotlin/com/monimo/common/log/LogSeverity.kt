package com.monimo.common.log

// 로그 한 줄의 등급 이름을 정하는 규칙 한 곳. 수집기(하한 거르기 #215 · #236)와 적재 처리기(LogRow.level #144)가
// 같은 표를 본다.
//
// 왜 common 인가: 같은 규약을 두 모듈이 각자 메우다 갈렸다. 수집기는 "숫자가 0 이면 모르는 것이니 남긴다",
//   적재 처리기는 "숫자가 없으면 글자를 본다" 였다. 그래서 「숫자 0 + 글자 DEBUG」가 하한을 빠져나가 DEBUG 로
//   쌓였다(#236). 표를 복사해 두면 한쪽만 고쳐져 다시 갈린다
//
// 왜 proto 를 안 받나: 들어오는 것을 숫자와 글자 둘로만 두어 common 이 OTLP 타입에 묶이지 않게 한다.
//   꺼내는 일(record.severityNumberValue · record.severityText)은 부르는 쪽에 둔다
//
// 판정 순서는 ADR #59 그대로다: 숫자가 정본, 숫자가 없거나 규약 밖이면 글자, 모르는 글자는 빈 글자
object LogSeverity {

    // 못 정했을 때의 값. logs.level 의 값 범위를 여섯 + 빈 글자로 닫는다 (ADR #59 ④)
    const val UNKNOWN: String = ""

    // OTel severity_number 규약의 여섯 구간 이름. 순서가 곧 심각도 순서다
    val NAMES: List<String> = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL")

    // 이름 → 그 구간의 첫 번호 (TRACE 1 · DEBUG 5 · INFO 9 · WARN 13 · ERROR 17 · FATAL 21).
    // 하한을 비교할 때 쓴다: 하한은 늘 구간의 첫 번호라 "번호끼리 비교" 와 "구간끼리 비교" 의 결과가 같다
    val FIRST_NUMBER: Map<String, Int> = NAMES.withIndex().associate { (i, name) -> name to i * 4 + 1 }

    // 등급 이름을 정한다. 여섯 중 하나 또는 UNKNOWN 이 나온다.
    //
    // 왜 숫자가 정본인가: OTel 규약이 severity_text 를 "출처가 부르는 이름 그대로" 인 자유 문자열로 정의한다.
    // 그래서 로깅 도구마다 글자가 다르다 : Logback · Log4j2 는 WARN · ERROR, java.util.logging 은 WARNING · SEVERE,
    // Python 은 WARNING · CRITICAL. 글자를 그대로 쓰면 같은 뜻이 다른 값이 되어 등급으로 세거나 거를 때
    // 조용히 몇 줄을 빠뜨린다. 숫자는 1~24 를 여섯 구간으로 나눈 정규화된 값이라 그 문제가 없다.
    //
    // 글자와 숫자가 어긋나면 숫자가 이긴다. 규약은 둘이 어긋날 때 누가 이기는지를 안 정해 뒀고,
    // "크기를 비교할 때는 숫자를 쓰라" 까지만 말한다. 그 문장을 근거로 우리가 숫자를 정본으로 고른 것이다
    fun nameOf(severityNumber: Int, severityText: String): String =
        fromNumber(severityNumber) ?: fromText(severityText)

    // OTel 규약의 구간표. 구간마다 네 단(WARN · WARN2 · WARN3 · WARN4)이 있는데 우리는 구간 이름 하나로 모은다:
    // logs.level 은 거르는 칸이라 여섯으로 충분하고, LowCardinality(String) 이라 값의 종류가 적어야 한다
    private fun fromNumber(number: Int): String? = when (number) {
        in 1..4 -> "TRACE"
        in 5..8 -> "DEBUG"
        in 9..12 -> "INFO"
        in 13..16 -> "WARN"
        in 17..20 -> "ERROR"
        in 21..24 -> "FATAL"
        else -> null // 0 = 등급 없음. 규약 밖의 값(음수 · 25 이상)도 여기로 온다
    }

    // 숫자가 없을 때만 쓴다. 우리가 붙이는 OTel Java 에이전트는 늘 숫자를 채우므로, 이 길은 직접 OTLP 를 만들어
    // 보내는 쪽(다른 언어 · 손으로 만든 전송)에만 걸린다.
    // 표에 없는 글자는 그대로 넣지 않고 빈 글자로 둔다 : LogRow.level 이 약속한 값 범위를 여섯 + 빈 글자로 닫아야
    // 조회 쪽이 고를 목록을 알 수 있다. 본문(message)은 그대로 남으므로 내용이 사라지지는 않는다.
    // 거를 때도 빈 글자는 남긴다 : 무엇인지 모르는 것을 버리면 안 된다 (#236 선택지 ③)
    // uppercase() 는 기본 로케일이 아니라 Locale.ROOT 를 쓴다(stdlib 구현). 터키어 로케일에서 i 가 점 있는 대문자로 바뀌어
    // INFO 와 안 맞는 문제는 생기지 않는다. 옛 toUpperCase() 를 쓰면 그 문제가 생긴다
    private fun fromText(text: String): String = ALIASES[text.trim().uppercase()] ?: UNKNOWN

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
