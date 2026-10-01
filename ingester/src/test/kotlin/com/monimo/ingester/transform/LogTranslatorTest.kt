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

            Then("등급은 글자 등급을 대문자로") {
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
