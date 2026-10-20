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

    // 등급 두 칸만 다른 레코드. 숫자 0 = 미지정, 글자는 안 주면 빈 글자
    fun record(severity: Int, text: String = ""): LogRecord =
        LogRecord.newBuilder().setSeverityNumberValue(severity).setSeverityText(text).build()

    fun requestOf(vararg records: LogRecord): ExportLogsServiceRequest = ExportLogsServiceRequest.newBuilder()
        .addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addAllLogRecords(records.toList())))
        .build()

    fun ExportLogsServiceRequest.records(): List<Int> =
        resourceLogsList.flatMap { rl -> rl.scopeLogsList.flatMap { sl -> sl.logRecordsList.map { it.severityNumberValue } } }

    // 숫자가 같고 글자만 다른 레코드를 가릴 때는 레코드 자체로 본다
    fun ExportLogsServiceRequest.all(): List<LogRecord> =
        resourceLogsList.flatMap { rl -> rl.scopeLogsList.flatMap { it.logRecordsList } }

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

    // 여기가 #236 이 고친 자리다. 전에는 숫자가 0 이면 글자를 안 보고 전부 남겨서, 적재 처리기가 글자로 DEBUG 라고
    // 판정하는 줄이 하한을 빠져나가 logs 표에 쌓였다
    Given("하한이 INFO 이고 숫자가 규약 밖인(0 · 99) 줄이 섞여 오면") {
        val keptBlah = record(0, "blah") // 표에 없는 글자 : 무엇인지 모르는 것은 안 버린다
        val keptEmpty = record(0, "") // 숫자도 글자도 없다
        val keptInfoText = record(0, "INFO")
        val keptInfoNumber = record(info, "INFO")
        val (filter, registry) = filterOf("INFO")
        val result = filter.drop(
            requestOf(
                record(0, "DEBUG"),
                record(99, "DEBUG"),
                record(0, "FINE"), // java.util.logging 의 DEBUG 구간 별칭
                keptBlah,
                keptEmpty,
                keptInfoText,
                keptInfoNumber,
            ),
        )

        Then("글자가 하한 미만을 가리키는 셋은 버린다") {
            result.all() shouldBe listOf(keptBlah, keptEmpty, keptInfoText, keptInfoNumber)
        }

        Then("버린 수는 판정된 이름(DEBUG) 칸에서 센다") {
            registry.droppedCount() shouldBe 3.0
            registry.droppedCount("DEBUG") shouldBe 3.0
        }
    }

    Given("하한이 WARN 이고 숫자가 0 인 로그") {
        When("글자가 WARNING 이면") {
            val (filter, _) = filterOf("WARN")
            val request = requestOf(record(0, "WARNING"))

            Then("하한과 같은 구간이라 남는다 (받은 객체를 그대로 돌려준다)") {
                (filter.drop(request) === request) shouldBe true
            }
        }

        When("글자가 NOTICE(INFO 구간) 면") {
            val (filter, registry) = filterOf("WARN")
            val result = filter.drop(requestOf(record(0, "NOTICE")))

            Then("INFO 로 세어 버린다") {
                result.records() shouldBe emptyList()
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
