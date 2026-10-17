package com.monimo.collector.filter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.logs.v1.ResourceLogs
import io.opentelemetry.proto.logs.v1.ScopeLogs

// 로그 하한 거르기 규칙만 확인하는 단위 테스트. 스프링도 컨테이너도 띄우지 않는다 (HealthCheckFilterTest 와 같은 꼴)
class LogLevelFilterTest : BehaviorSpec({

    fun filterOf(minLevel: String): Pair<LogLevelFilter, SimpleMeterRegistry> {
        val registry = SimpleMeterRegistry()
        return LogLevelFilter(LogLevelProperties(minLevel), registry) to registry
    }

    fun SimpleMeterRegistry.droppedCount(): Double =
        find(HealthCheckFilter.METRIC).tag("reason", LogLevelFilter.REASON_LOG_LEVEL).counters().sumOf { it.count() }

    fun SimpleMeterRegistry.droppedCount(level: String): Double =
        find(HealthCheckFilter.METRIC).tag("level", level).counters().sumOf { it.count() }

    // severity_number 만 다른 레코드. 0 = 미지정
    fun record(severity: Int): LogRecord = LogRecord.newBuilder().setSeverityNumberValue(severity).build()

    fun requestOf(vararg records: LogRecord): ExportLogsServiceRequest = ExportLogsServiceRequest.newBuilder()
        .addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addAllLogRecords(records.toList())))
        .build()

    fun ExportLogsServiceRequest.records(): List<Int> =
        resourceLogsList.flatMap { rl -> rl.scopeLogsList.flatMap { sl -> sl.logRecordsList.map { it.severityNumberValue } } }

    val trace = 1
    val debug = 5
    val info = 9
    val warn = 13

    Given("하한이 INFO 인 필터") {
        When("DEBUG · INFO · WARN · 미지정(0) 이 섞여 오면") {
            val (filter, registry) = filterOf("INFO")
            val result = filter.drop(requestOf(record(debug), record(info), record(warn), record(0), record(trace)))

            Then("DEBUG · TRACE 만 빠지고 INFO · WARN · 미지정은 남는다") {
                result.records() shouldBe listOf(info, warn, 0)
            }

            Then("버린 수를 등급별로 센다") {
                registry.droppedCount() shouldBe 2.0
                registry.droppedCount("DEBUG") shouldBe 1.0
                registry.droppedCount("TRACE") shouldBe 1.0
            }
        }

        When("버릴 게 하나도 없으면") {
            val (filter, _) = filterOf("INFO")
            val request = requestOf(record(info), record(warn))

            Then("받은 객체를 그대로 돌려준다 (바이트를 다시 만들지 않는 약속)") {
                (filter.drop(request) === request) shouldBe true
            }
        }

        When("전부 하한 미만이면") {
            val (filter, _) = filterOf("INFO")
            val result = filter.drop(requestOf(record(debug), record(trace)))

            Then("레코드도 scope · resource 껍데기도 없는 요청이 나온다") {
                result.records() shouldBe emptyList()
                result.resourceLogsCount shouldBe 0
            }
        }
    }

    Given("하한이 WARN 인 필터") {
        When("INFO 가 오면") {
            val (filter, registry) = filterOf("warn")
            val result = filter.drop(requestOf(record(info), record(warn)))

            Then("INFO 도 빠진다 (등급 이름은 대소문자를 안 가린다)") {
                result.records() shouldBe listOf(warn)
                registry.droppedCount("INFO") shouldBe 1.0
            }
        }
    }

    Given("하한이 빈 필터 (필터 꺼짐)") {
        When("TRACE 가 와도") {
            val (filter, registry) = filterOf("  ")
            val request = requestOf(record(trace))

            Then("그대로 남고 카운터도 없다") {
                (filter.drop(request) === request) shouldBe true
                registry.droppedCount() shouldBe 0.0
            }
        }
    }

    Given("하한 이름이 틀린 설정") {
        Then("기동 때 터진다 (조용히 안 거르면 홍수가 다시 온다)") {
            shouldThrow<IllegalArgumentException> { filterOf("VERBOSE") }
        }
    }
})
