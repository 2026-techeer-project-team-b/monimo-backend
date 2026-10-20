package com.monimo.collector.filter

import com.monimo.common.log.LogSeverity
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.logs.v1.ResourceLogs
import io.opentelemetry.proto.logs.v1.ScopeLogs
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component

// 받은 로그에서 하한 미만 등급을 빼낸다. Kafka 에 넣기 전에 한 번 거친다 (#215).
//
// 왜 버리나: 우리 모듈 다섯에 에이전트를 붙이자(#213) DEBUG · TRACE 가 1분에 3,900건 들어왔다.
//   저장할 가치가 없는데 logs 표를 채우고, 수집기 자신의 "받았다" DEBUG 는 자기 입력으로 돌아와 되먹임이 된다.
//
// 거르는 기준: 등급을 common 의 LogSeverity 로 정하고 그 등급이 하한보다 낮으면 버린다. 판정 순서는 ADR #59 와
//   같다 : severity_number 가 규약 안(1~24)이면 번호가 정본이고, 0 이거나 규약 밖이면 severity_text 를 별칭 표로
//   본다. 번호를 먼저 보는 이유는 번호가 규약으로 고정되어 있고(TRACE 1~4 · DEBUG 5~8 · INFO 9~12 ·
//   WARN 13~16 · ERROR 17~20 · FATAL 21~24) 이름은 로거마다 다르기 때문이다(WARN · WARNING).
//   등급을 끝내 못 정하면(모르는 글자 · 빈 글자) 남긴다. 무엇인지 모르는 것을 버리면 안 된다
//
// 글자까지 보는 이유 (#236): 처음에는 번호가 0 이면 그냥 남겼다. 그런데 적재 처리기는 0 이면 글자로 등급을 정해서
//   「0 + DEBUG」가 하한을 빠져나가 logs 표에 DEBUG 로 쌓였다. 같은 규약의 빈칸을 두 모듈이 다르게 메운 것이라
//   판정을 common 한 곳으로 모았다
@Component
@EnableConfigurationProperties(LogLevelProperties::class)
class LogLevelFilter(
    private val properties: LogLevelProperties,
    registry: MeterRegistry,
) {

    // 하한의 번호. null 이면 꺼진 것. 잘못된 이름은 기동 때 터뜨린다 (조용히 안 거르면 홍수가 다시 온다)
    private val threshold: Int? = properties.minLevel.trim().takeIf { it.isNotEmpty() }?.let { name ->
        LogSeverity.FIRST_NUMBER[name.uppercase()]
            ?: throw IllegalArgumentException("monimo.collector.log.min-level 은 ${LogSeverity.NAMES} 중 하나여야 한다: $name")
    }

    // 버린 수를 등급 이름별로 센다. 하한 미만 등급만 미리 등록하므로 가짓수가 등급 수로 묶인다
    private val droppedByLevel: Map<String, Counter> = LogSeverity.FIRST_NUMBER.filterValues { it < (threshold ?: 0) }.keys.associateWith { level ->
        Counter.builder(HealthCheckFilter.METRIC)
            .description("로그 하한 미만이라 버린 로그 레코드 수")
            .baseUnit("records")
            .tag("reason", REASON_LOG_LEVEL)
            .tag("level", level)
            .register(registry)
    }

    init {
        if (threshold == null) {
            log.info("로그 하한: 꺼짐 (min-level 이 비어 있어 로그를 버리지 않는다)")
        } else {
            log.info(
                "로그 하한: 등급이 {}({}) 미만인 로그 레코드를 버린다 (등급은 severity_number, 없거나 규약 밖이면 severity_text)",
                properties.minLevel.uppercase(),
                threshold,
            )
        }
    }

    // 하한 미만을 뺀 새 요청을 만든다. 전부 미만이었으면 레코드가 하나도 없는 요청이 나온다.
    // 버릴 게 없으면 받은 객체를 그대로 돌려준다 (수집기는 받은 바이트를 그대로 Kafka 에 넣는 약속, HealthCheckFilter 와 같은 이유)
    fun drop(request: ExportLogsServiceRequest): ExportLogsServiceRequest {
        val min = threshold ?: return request
        if (request.resourceLogsList.none { rl -> rl.scopeLogsList.any { it.logRecordsList.any { r -> isBelow(r, min) } } }) {
            return request
        }

        val result = ExportLogsServiceRequest.newBuilder()
        for (resourceLogs in request.resourceLogsList) {
            val resourceBuilder = ResourceLogs.newBuilder(resourceLogs).clearScopeLogs()
            for (scopeLogs in resourceLogs.scopeLogsList) {
                val (drop, keep) = scopeLogs.logRecordsList.partition { isBelow(it, min) }
                drop.forEach { record -> droppedByLevel[levelNameOf(record)]?.increment() }
                if (keep.isNotEmpty()) {
                    resourceBuilder.addScopeLogs(ScopeLogs.newBuilder(scopeLogs).clearLogRecords().addAllLogRecords(keep))
                }
            }
            if (resourceBuilder.scopeLogsCount > 0) result.addResourceLogs(resourceBuilder)
        }
        return result.build()
    }

    // 하한 미만인가. 등급을 못 정하면(모르는 글자 · 빈 글자) 남긴다.
    // 구간의 첫 번호로 비교해도 번호로 비교한 것과 결과가 같다 : 하한은 늘 구간의 첫 번호(1 · 5 · 9 · 13 · 17 · 21)다
    private fun isBelow(record: LogRecord, min: Int): Boolean {
        val first = LogSeverity.FIRST_NUMBER[levelNameOf(record)] ?: return false
        return first < min
    }

    // 판정된 등급 이름. 카운터 꼬리표도 이것을 쓴다 : 숫자로 버린 것과 글자로 버린 것이 같은 칸에서 세어진다
    private fun levelNameOf(record: LogRecord): String =
        LogSeverity.nameOf(record.severityNumberValue, record.severityText)

    companion object {
        const val REASON_LOG_LEVEL = "log_level"

        private val log = LoggerFactory.getLogger(LogLevelFilter::class.java)
    }
}
