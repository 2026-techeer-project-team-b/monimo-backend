package com.monimo.common

import com.monimo.common.kafka.RawSignal
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.logs.v1.SeverityNumber
import io.opentelemetry.proto.metrics.v1.Metric

// raw 토픽 메시지의 **값** 약속을 못박는 시험 (개발환경 9단계 · ADR `#65`).
//
// 약속은 하나다 : 값은 수집기가 받은 OTLP `Export*ServiceRequest` 의 protobuf 바이트 **그대로**다.
// 우리 형식으로 감싸지 않고, 꼬리표를 더하거나 빼지 않는다. 메타는 봉투 겉면(Kafka 헤더)에 붙인다
// (`RawMessageFormatTest` 가 그 글자를 고정한다).
//
// 왜 손으로 만든 데이터가 아니라 **쇼핑몰 실데이터**인가 : 가짜 발신기(telemetrygen)가 보내는 모양은
// 실제 에이전트와 다르다. 꼬리표 가짓수 · 히스토그램 · 로그 등급 칸이 전부 다르고, 적재 처리기의 변환 규칙
// (`#64` 히스토그램 펴기 · `#144` 로그 등급)은 **실데이터 모양**을 전제로 짜여 있다.
// 그래서 표본은 쇼핑몰이 실제로 흘린 메시지를 떠서 레포에 둔다. 뜬 방법은 `resources/fixtures/README.md` 에 있다.
class OtlpProtobufRoundTripTest : BehaviorSpec({

    fun fixture(signal: RawSignal): ByteArray =
        OtlpProtobufRoundTripTest::class.java.getResourceAsStream("/fixtures/${signal.key}.pb")
            ?.readBytes()
            ?: error("표본이 없다: fixtures/${signal.key}.pb (resources/fixtures/README.md 를 보고 다시 뜬다)")

    // 신호마다 protobuf 타입이 다르다. 풀기 · 다시 쓰기를 같은 모양으로 쓰려고 한 자리에 모았다
    fun parse(signal: RawSignal, bytes: ByteArray): Any = when (signal) {
        RawSignal.TRACES -> ExportTraceServiceRequest.parseFrom(bytes)
        RawSignal.METRICS -> ExportMetricsServiceRequest.parseFrom(bytes)
        RawSignal.LOGS -> ExportLogsServiceRequest.parseFrom(bytes)
    }

    fun serialize(parsed: Any): ByteArray = when (parsed) {
        is ExportTraceServiceRequest -> parsed.toByteArray()
        is ExportMetricsServiceRequest -> parsed.toByteArray()
        is ExportLogsServiceRequest -> parsed.toByteArray()
        else -> error("모르는 타입: ${parsed::class}")
    }

    // 3겹 구조(resource → scope → 기록)의 가장 바깥, resource 꼬리표
    fun resourceAttributes(signal: RawSignal, bytes: ByteArray): List<KeyValue> = when (signal) {
        RawSignal.TRACES -> ExportTraceServiceRequest.parseFrom(bytes).resourceSpansList.flatMap { it.resource.attributesList }
        RawSignal.METRICS -> ExportMetricsServiceRequest.parseFrom(bytes).resourceMetricsList.flatMap { it.resource.attributesList }
        RawSignal.LOGS -> ExportLogsServiceRequest.parseFrom(bytes).resourceLogsList.flatMap { it.resource.attributesList }
    }

    fun attribute(signal: RawSignal, key: String): String =
        resourceAttributes(signal, fixture(signal)).first { it.key == key }.value.stringValue

    Given("쇼핑몰이 실제로 흘린 raw 메시지 세 신호") {

        RawSignal.entries.forEach { signal ->

            When("${signal.key} 를 풀었다가 다시 protobuf 로 쓰면") {
                val original = fixture(signal)
                val rewritten = serialize(parse(signal, original))

                Then("바이트가 한 글자도 안 바뀐다") {
                    // 여기가 "그대로" 약속의 핵심이다. 수집기가 받은 바이트를 Kafka 에 그대로 넣고
                    // 적재 처리기가 그대로 푸는 것이 성립한다는 뜻이고, 중간에 감싸거나 고치면 이 단언이 깨진다
                    rewritten.contentEquals(original) shouldBe true
                }

                Then("다시 읽은 값이 처음 값과 같다") {
                    parse(signal, rewritten) shouldBe parse(signal, original)
                }
            }

            When("${signal.key} 의 resource 꼬리표를 보면") {
                Then("쇼핑몰 서비스 이름이 들어 있다 : 가짜 발신기 데이터가 아니라는 증거") {
                    attribute(signal, "service.name") shouldStartWith "shop-"
                }

                Then("파드 이름표(`service.instance.id`)가 들어 있다") {
                    // 적재 처리기가 `agents` 명부의 키로 쓰는 칸이다(`#58` · `#66`). 쇼핑몰이 compose 에서 넣어 준다(`#30`).
                    // 이 칸이 비면 에이전트가 기동 때 만든 UUID 가 들어가 재시작마다 명부 줄이 하나 늘어난다
                    attribute(signal, "service.instance.id") shouldStartWith "shop-"
                }
            }
        }

        When("트레이스 표본을 보면") {
            val request = ExportTraceServiceRequest.parseFrom(fixture(RawSignal.TRACES))

            Then("스팬이 하나 이상이고 계측 범위 이름이 붙어 있다") {
                request.resourceSpansList.flatMap { it.scopeSpansList }.sumOf { it.spansCount } shouldBeGreaterThan 0
                request.resourceSpansList.flatMap { it.scopeSpansList }.map { it.scope.name }.all { it.isNotEmpty() } shouldBe true
            }
        }

        When("지표 표본을 보면") {
            val metrics = ExportMetricsServiceRequest.parseFrom(fixture(RawSignal.METRICS))
                .resourceMetricsList.flatMap { it.scopeMetricsList }.flatMap { it.metricsList }

            Then("히스토그램이 섞여 있다 : `#64` 의 펴기 규칙이 전제하는 모양") {
                // 가짜 데이터는 jvm.gc.duration 을 값 하나로 넣지만 실데이터는 히스토그램이다(AGENTS §6).
                // 적재 처리기가 `.count` · `.sum` 으로 펴는 규칙이 이 모양을 전제로 짜여 있어 표본에 꼭 있어야 한다
                metrics.map { it.dataCase } shouldContain Metric.DataCase.HISTOGRAM
            }

            Then("게이지와 합계도 같이 들어 있다") {
                metrics.map { it.dataCase } shouldContain Metric.DataCase.GAUGE
                metrics.map { it.dataCase } shouldContain Metric.DataCase.SUM
            }
        }

        When("로그 표본을 보면") {
            val records = ExportLogsServiceRequest.parseFrom(fixture(RawSignal.LOGS))
                .resourceLogsList.flatMap { it.scopeLogsList }.flatMap { it.logRecordsList }

            Then("등급 숫자(`severity_number`)가 들어 있다 : `#144` 가 정본으로 쓰는 칸") {
                records.size shouldBeGreaterThan 0
                records.all { it.severityNumber != SeverityNumber.SEVERITY_NUMBER_UNSPECIFIED } shouldBe true
            }
        }
    }
})
