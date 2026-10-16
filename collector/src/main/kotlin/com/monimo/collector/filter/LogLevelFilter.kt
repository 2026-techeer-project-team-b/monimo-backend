package com.monimo.collector.filter

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
// 거르는 기준: OTLP 의 severity_number 가 하한의 번호보다 작으면 버린다. 등급 이름(severity_text)이 아니라
//   번호를 보는 이유는 번호가 규약으로 고정되어 있고(TRACE 1~4 · DEBUG 5~8 · INFO 9~12 · WARN 13~16 ·
//   ERROR 17~20 · FATAL 21~24) 이름은 로거마다 다르기 때문이다(WARN · WARNING). 번호가 0(미지정)이면 남긴다.
//   무엇인지 모르는 것을 버리면 안 된다
@Component
@EnableConfigurationProperties(LogLevelProperties::class)
class LogLevelFilter(
    private val properties: LogLevelProperties,
    registry: MeterRegistry,
) {

    // 하한의 번호. null 이면 꺼진 것. 잘못된 이름은 기동 때 터뜨린다 (조용히 안 거르면 홍수가 다시 온다)
    private val threshold: Int? = properties.minLevel.trim().takeIf { it.isNotEmpty() }?.let { name ->
        LEVELS[name.uppercase()] ?: throw IllegalArgumentException("monimo.collector.log.min-level 은 ${LEVELS.keys} 중 하나여야 한다: $name")
    }

    // 버린 수를 등급 이름별로 센다. 하한 미만 등급만 미리 등록하므로 가짓수가 등급 수로 묶인다
    private val droppedByLevel: Map<String, Counter> = LEVELS.filterValues { it < (threshold ?: 0) }.keys.associateWith { level ->
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
            log.info("로그 하한: severity_number 가 {}({}) 미만인 로그 레코드를 버린다", properties.minLevel.uppercase(), threshold)
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

    // 0(미지정)은 남긴다
    private fun isBelow(record: LogRecord, min: Int): Boolean {
        val number = record.severityNumberValue
        return number != 0 && number < min
    }

    // 번호 → 등급 이름 (카운터 꼬리표용). 규약의 4칸 묶음이라 (번호-1)/4
    private fun levelNameOf(record: LogRecord): String = LEVEL_NAMES.getOrElse((record.severityNumberValue - 1) / 4) { "UNKNOWN" }

    companion object {
        const val REASON_LOG_LEVEL = "log_level"

        // OTLP severity_number 규약의 각 등급 첫 번호
        private val LEVEL_NAMES = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL")
        val LEVELS: Map<String, Int> = LEVEL_NAMES.withIndex().associate { (i, name) -> name to i * 4 + 1 }

        private val log = LoggerFactory.getLogger(LogLevelFilter::class.java)
    }
}
