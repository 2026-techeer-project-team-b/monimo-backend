package com.monimo.ingester.transform

import com.google.protobuf.ByteString
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.InstrumentationScope
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.logs.v1.LogRecord
import io.opentelemetry.proto.logs.v1.ResourceLogs
import io.opentelemetry.proto.logs.v1.ScopeLogs
import io.opentelemetry.proto.logs.v1.SeverityNumber
import io.opentelemetry.proto.resource.v1.Resource
import java.time.Instant

// 변환 규칙만 보는 단위 테스트. 스프링도 컨테이너도 안 띄운다
class LogTranslatorTest : BehaviorSpec({

    fun attr(key: String, value: String): KeyValue =
        KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()

    fun requestOf(record: LogRecord.Builder, logger: String = "com.monimo.shop.order.OrderService"): ExportLogsServiceRequest =
        ExportLogsServiceRequest.newBuilder()
            .addResourceLogs(
                ResourceLogs.newBuilder()
                    .setResource(Resource.newBuilder().addAttributes(attr("service.name", "shop-order")))
                    .addScopeLogs(
                        ScopeLogs.newBuilder()
                            .setScope(InstrumentationScope.newBuilder().setName(logger))
                            .addLogRecords(record),
                    ),
            )
            .build()

    val at = 1_700_000_000_123_456_789L

    Given("INFO 로그 한 줄 — 글자 등급 · 스레드 · trace ID 전부 있음") {
        val record = LogRecord.newBuilder()
            .setTimeUnixNano(at)
            .setSeverityText("Info") // OTel Java 는 "INFO", telemetrygen 은 "Info" 로 보낸다
            .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
            .setBody(AnyValue.newBuilder().setStringValue("주문 생성 완료"))
            .setTraceId(ByteString.copyFrom(ByteArray(16) { 0xAB.toByte() }))
            .setSpanId(ByteString.copyFrom(ByteArray(8) { 0x01 }))
            .addAttributes(attr("thread.name", "http-nio-8080-exec-3"))
            .addAttributes(attr("order.id", "o-123"))

        When("우리 모델로 옮기면") {
            val row = LogTranslator.toRows(requestOf(record)).single()

            Then("로거는 scope 이름에서, 스레드는 thread.name 꼬리표에서 온다") {
                row.logger shouldBe "com.monimo.shop.order.OrderService"
                row.thread shouldBe "http-nio-8080-exec-3"
            }

            Then("등급은 숫자로 정한다 (글자가 Info 든 INFO 든 같은 값)") {
                row.level shouldBe "INFO"
            }

            Then("본문 · 시각 · ID") {
                row.message shouldBe "주문 생성 완료"
                row.ts shouldBe Instant.ofEpochSecond(1_700_000_000L, 123_456_789L)
                row.traceId shouldBe "ab".repeat(16)
                row.spanId shouldBe "01".repeat(8)
            }

            Then("꼬리표는 전부 attributes 에 남는다 (thread.name 포함)") {
                row.attributes shouldBe mapOf("thread.name" to "http-nio-8080-exec-3", "order.id" to "o-123")
            }
        }
    }

    Given("글자 등급이 없고 숫자 등급만 17 (ERROR 구간 시작)") {
        val record = LogRecord.newBuilder().setTimeUnixNano(at).setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_ERROR)

        Then("구간표로 ERROR 가 된다") {
            LogTranslator.toRows(requestOf(record)).single().level shouldBe "ERROR"
        }
    }

    // 등급은 숫자가 정본이고 글자는 숫자가 없을 때만 본다 (#144 · ADR #59).
    // 로깅 도구마다 글자가 달라서(java.util.logging 은 WARNING · SEVERE, Python 은 CRITICAL) 글자를 그대로 넣으면
    // 같은 뜻이 다른 값으로 쌓이고, 등급으로 세거나 거를 때 조용히 몇 줄이 빠진다
    Given("숫자가 13 으로 같고 글자만 WARN 과 WARNING 으로 다른 로그 둘") {
        val warn = LogRecord.newBuilder().setTimeUnixNano(at)
            .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_WARN).setSeverityText("WARN")
        val warning = LogRecord.newBuilder().setTimeUnixNano(at)
            .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_WARN).setSeverityText("WARNING")

        Then("둘 다 WARN 이 된다 : 숫자가 있으면 글자를 아예 안 본다") {
            LogTranslator.toRows(requestOf(warn)).single().level shouldBe "WARN"
            LogTranslator.toRows(requestOf(warning)).single().level shouldBe "WARN"
        }
    }

    Given("글자와 숫자가 어긋난 로그 : 글자는 WARN 인데 숫자는 17 (ERROR)") {
        val record = LogRecord.newBuilder().setTimeUnixNano(at)
            .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_ERROR).setSeverityText("WARN")

        Then("숫자가 이긴다 (규약이 비교 가능한 값으로 정의한 쪽이 숫자다)") {
            LogTranslator.toRows(requestOf(record)).single().level shouldBe "ERROR"
        }
    }

    Given("숫자가 없고 글자만 있는 로그 (직접 OTLP 를 만들어 보내는 쪽)") {
        fun levelOfText(text: String): String =
            LogTranslator.toRows(requestOf(LogRecord.newBuilder().setTimeUnixNano(at).setSeverityText(text))).single().level

        Then("java.util.logging 이름을 표준 여섯으로 맞춘다") {
            levelOfText("WARNING") shouldBe "WARN"
            levelOfText("SEVERE") shouldBe "ERROR"
            levelOfText("FINEST") shouldBe "TRACE"
        }

        // 이름만 보고 구간을 짐작하면 틀리는 자리들이다. 리뷰 조사가 규약 부록 B 로 다섯 개를 잡아 줬다
        Then("FINER 는 TRACE 가 아니라 DEBUG 다 (JUL 에서 TRACE 구간은 FINEST 하나뿐)") {
            levelOfText("FINER") shouldBe "DEBUG"
            levelOfText("FINE") shouldBe "DEBUG"
            levelOfText("CONFIG") shouldBe "DEBUG"
        }

        Then("syslog 의 Alert 는 FATAL 이 아니라 ERROR 다 (FATAL 은 Emergency 하나뿐)") {
            levelOfText("ALERT") shouldBe "ERROR"
            levelOfText("CRIT") shouldBe "ERROR"
            levelOfText("EMERG") shouldBe "FATAL"
            levelOfText("EMERGENCY") shouldBe "FATAL"
            levelOfText("ERR") shouldBe "ERROR"
            levelOfText("NOTICE") shouldBe "INFO"
        }

        Then("VERBOSE 는 TRACE 가 아니라 DEBUG 다 (Windows 이벤트 로그 · ETW)") {
            levelOfText("VERBOSE") shouldBe "DEBUG"
            levelOfText("INFORMATION") shouldBe "INFO"
            levelOfText("INFORMATIONAL") shouldBe "INFO"
        }

        Then("zap 의 Panic 은 FATAL 이 아니라 ERROR 다 (FATAL 은 Fatal 하나뿐)") {
            levelOfText("PANIC") shouldBe "ERROR"
            levelOfText("DPANIC") shouldBe "ERROR"
            levelOfText("FATAL") shouldBe "FATAL"
        }

        // CRIT 은 syslog 낱말이라 ERROR 로 정해지는데, 풀어 쓴 CRITICAL 은 출처마다 구간이 달라 갈라진다
        Then("CRITICAL 은 출처끼리 구간이 달라 더 심한 쪽으로 두고, 짧은 CRIT 은 syslog 기준 ERROR 다") {
            levelOfText("CRITICAL") shouldBe "FATAL"
            levelOfText("CRIT") shouldBe "ERROR"
        }

        Then("대소문자와 앞뒤 공백은 무시한다") {
            levelOfText("  warning  ") shouldBe "WARN"
            levelOfText("Info") shouldBe "INFO"
        }

        Then("표에 없는 글자는 그대로 넣지 않고 빈 글자로 둔다 (level 의 값 범위를 여섯 + 빈 글자로 닫는다)") {
            levelOfText("MY_CUSTOM_LEVEL") shouldBe ""
            levelOfText("") shouldBe ""
        }
    }

    Given("숫자 구간의 경계값 전부") {
        fun levelOfNumber(number: Int): String =
            LogTranslator.toRows(
                requestOf(LogRecord.newBuilder().setTimeUnixNano(at).setSeverityNumberValue(number)),
            ).single().level

        Then("여섯 구간이 각각 처음과 끝에서 같은 이름을 낸다") {
            listOf(1 to "TRACE", 4 to "TRACE", 5 to "DEBUG", 8 to "DEBUG", 9 to "INFO", 12 to "INFO").forEach { (n, name) ->
                levelOfNumber(n) shouldBe name
            }
            listOf(13 to "WARN", 16 to "WARN", 17 to "ERROR", 20 to "ERROR", 21 to "FATAL", 24 to "FATAL").forEach { (n, name) ->
                levelOfNumber(n) shouldBe name
            }
        }

        Then("0 과 규약 밖의 값은 글자도 없으면 빈 글자다") {
            levelOfNumber(0) shouldBe ""
            levelOfNumber(99) shouldBe ""
        }
    }

    // 위 Then 은 글자를 안 붙여서 "글자 쪽으로 넘어갔다" 를 못 본다 : 숫자 쪽이 그냥 빈 글자를 돌려줘도 통과한다.
    // 넘김이 실제로 일어나는지는 글자를 붙여야 보인다. proto3 의 열거형은 열려 있어서 99 가 잘리지 않고 그대로 들어온다
    Given("숫자가 규약 밖(99)인데 글자는 표에 있는 로그") {
        val record = LogRecord.newBuilder().setTimeUnixNano(at)
            .setSeverityNumberValue(99).setSeverityText("WARNING")

        Then("숫자를 못 알아보면 글자 쪽으로 넘어가 별칭 표를 탄다") {
            LogTranslator.toRows(requestOf(record)).single().level shouldBe "WARN"
        }
    }

    Given("숫자가 0 인데 글자는 표에 있는 로그") {
        val record = LogRecord.newBuilder().setTimeUnixNano(at).setSeverityText("SEVERE")

        Then("같은 길로 넘어간다") {
            LogTranslator.toRows(requestOf(record)).single().level shouldBe "ERROR"
        }
    }

    Given("앱이 찍은 시각이 없고(0) 수집기가 받은 시각만 있음") {
        val record = LogRecord.newBuilder().setObservedTimeUnixNano(at).setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_WARN)

        Then("받은 시각을 쓴다") {
            LogTranslator.toRows(requestOf(record)).single().ts shouldBe Instant.ofEpochSecond(1_700_000_000L, 123_456_789L)
        }
    }

    Given("본문이 글자가 아니라 숫자, trace ID 없음") {
        val record = LogRecord.newBuilder().setTimeUnixNano(at).setBody(AnyValue.newBuilder().setIntValue(42))

        When("옮기면") {
            val row = LogTranslator.toRows(requestOf(record)).single()

            Then("본문은 글자로, 없는 ID 는 빈 글자로") {
                row.message shouldBe "42"
                row.traceId shouldBe ""
                row.spanId shouldBe ""
                row.thread shouldBe ""
            }
        }
    }
})
