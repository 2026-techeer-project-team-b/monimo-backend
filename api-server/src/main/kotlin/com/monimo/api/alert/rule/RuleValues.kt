package com.monimo.api.alert.rule

// metric_kind 값은 숫자로 시작해(5XX_RATE) enum 이름으로 못 쓴다. 표 · JSON 에는 code 문자열 그대로 둔다 (ADR #40)
enum class MetricKind(val code: String, val percent: Boolean = false) {
    RATE_5XX("5XX_RATE", percent = true),
    RATE_4XX("4XX_RATE", percent = true),
    P95_LATENCY("P95_LATENCY"),
    CPU("CPU"),
    HEAP("HEAP"),
    GC_TIME("GC_TIME"),
    AGENT_DOWN("AGENT_DOWN"),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun of(code: String): MetricKind? = byCode[code]

        val codes: String = entries.joinToString(" · ") { it.code }
    }
}

enum class AlertOperator { GT, GTE, LT, LTE }

enum class Severity { INFO, WARNING, CRITICAL }
