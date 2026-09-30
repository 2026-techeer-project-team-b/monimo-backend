package com.monimo.ingester.transform

import com.google.protobuf.ByteString
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.resource.v1.Resource
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import io.opentelemetry.proto.trace.v1.Status
import java.time.Instant

// 변환 규칙만 보는 단위 테스트. 스프링도 컨테이너도 안 띄워서 1초 안에 끝난다.
// 이게 가능한 이유는 SpanTranslator 가 프레임워크와 저장소를 모르는 순수 코드이기 때문이다
class SpanTranslatorTest : BehaviorSpec({

    fun attr(key: String, value: String): KeyValue =
        KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build()

    // 스팬 하나가 든 요청을 만든다. resource 꼬리표와 스팬 꼬리표를 따로 받는다
    fun requestOf(
        span: Span.Builder,
        resourceAttrs: List<KeyValue> = listOf(attr("service.name", "shop-order")),
    ): ExportTraceServiceRequest =
        ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(
                ResourceSpans.newBuilder()
                    .setResource(Resource.newBuilder().addAllAttributes(resourceAttrs))
                    .addScopeSpans(ScopeSpans.newBuilder().addSpans(span)),
            )
            .build()

    val traceId = ByteString.copyFrom(ByteArray(16) { 0xAB.toByte() })
    val spanId = ByteString.copyFrom(ByteArray(8) { 0x01 })

    Given("서버 스팬 하나") {
        val span = Span.newBuilder()
            .setTraceId(traceId)
            .setSpanId(spanId)
            .setName("POST /orders")
            .setKind(Span.SpanKind.SPAN_KIND_SERVER)
            .setStartTimeUnixNano(1_700_000_000_123_456_789L)
            .setEndTimeUnixNano(1_700_000_000_923_456_789L)
            .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR))
            .addAttributes(attr("http.response.status_code", "500"))
            .addAttributes(attr("http.route", "/orders"))

        When("우리 모델로 옮기면") {
            val row = SpanTranslator.toRows(requestOf(span)).single()

            Then("ID 는 소문자 16진수가 된다") {
                row.traceId shouldBe "ab".repeat(16)
                row.spanId shouldBe "01".repeat(8)
            }

            Then("부모가 없으면 빈 글자다 (CH 관례: 없음은 NULL 이 아니라 '')") {
                row.parentSpanId shouldBe ""
            }

            Then("시작 시각은 나노초까지 남는다") {
                row.startTime shouldBe Instant.ofEpochSecond(1_700_000_000L, 123_456_789L)
            }

            Then("걸린 시간은 끝 - 시작이다") {
                row.durationNs shouldBe 800_000_000L
            }

            Then("서비스 이름은 resource 에서, 종류와 상태는 CH Enum 글자로 온다") {
                row.serviceName shouldBe "shop-order"
                row.spanKind shouldBe "SERVER"
                row.statusCode shouldBe "ERROR"
            }

            Then("HTTP 응답 코드는 꼬리표에서 숫자로 꺼낸다") {
                row.httpStatus shouldBe 500
            }

            Then("스팬 꼬리표는 그대로 남는다") {
                row.attributes shouldContain ("http.route" to "/orders")
            }
        }
    }

    Given("파드 식별자가 여럿 섞인 resource") {
        val span = Span.newBuilder().setTraceId(traceId).setSpanId(spanId).setName("x")

        When("service.instance.id 와 k8s.pod.name 이 다 있으면") {
            val row = SpanTranslator.toRows(
                requestOf(
                    span,
                    listOf(
                        attr("service.name", "shop-order"),
                        attr("k8s.pod.name", "pod-뒤"),
                        attr("service.instance.id", "instance-앞"),
                    ),
                ),
            ).single()

            Then("표준인 service.instance.id 를 먼저 쓴다") {
                row.agentId shouldBe "instance-앞"
            }
        }

        When("service.instance.id 가 없으면") {
            val row = SpanTranslator.toRows(
                requestOf(span, listOf(attr("service.name", "shop-order"), attr("k8s.pod.name", "pod-이름"))),
            ).single()

            Then("k8s.pod.name 으로 내려간다") {
                row.agentId shouldBe "pod-이름"
            }
        }

        When("셋 다 없으면") {
            val row = SpanTranslator.toRows(requestOf(span)).single()

            Then("빈 글자로 둔다 (CH LowCardinality 는 NULL 을 싫어한다)") {
                row.agentId shouldBe ""
            }
        }
    }

    Given("호출 대상이 있는 CLIENT 스팬") {
        val span = Span.newBuilder()
            .setTraceId(traceId).setSpanId(spanId).setName("POST")
            .setKind(Span.SpanKind.SPAN_KIND_CLIENT)
            .addAttributes(attr("server.address", "shop-payment"))
            .addAttributes(attr("server.port", "8080"))

        When("주소와 포트가 있으면") {
            val row = SpanTranslator.toRows(requestOf(span)).single()

            Then("주소:포트 로 합친다") {
                row.peerAddress shouldBe "shop-payment:8080"
            }

            Then("peer.service 는 에이전트가 안 넣어 주면 빈 글자다 (서버맵 매핑은 이슈 I)") {
                row.peerService shouldBe ""
            }
        }
    }

    Given("카나리 표식이 붙은 스팬") {
        val span = Span.newBuilder().setTraceId(traceId).setSpanId(spanId).setName("POST /orders")

        When("trace_state 에 monimon=canary 가 있으면") {
            val row = SpanTranslator.toRows(requestOf(span.clone().setTraceState("monimon=canary"))).single()

            Then("꼬리표에 표식을 남긴다 — CH 에 trace_state 컬럼이 없어 여기서 옮겨 적는다 (ADR #41)") {
                row.attributes shouldContain (SpanRow.CANARY_KEY to "true")
            }
        }

        When("다른 항목과 섞여 있으면") {
            val row = SpanTranslator.toRows(requestOf(span.clone().setTraceState("vendor=abc, monimon=canary"))).single()

            Then("그래도 남긴다") {
                row.attributes shouldContain (SpanRow.CANARY_KEY to "true")
            }
        }

        When("비슷하지만 다른 표식이면") {
            val row = SpanTranslator.toRows(requestOf(span.clone().setTraceState("monimon=canaryx"))).single()

            Then("남기지 않는다") {
                row.attributes shouldNotContainKey SpanRow.CANARY_KEY
            }
        }

        When("표식이 없으면") {
            val row = SpanTranslator.toRows(requestOf(span)).single()

            Then("남기지 않는다") {
                row.attributes shouldNotContainKey SpanRow.CANARY_KEY
            }
        }
    }

    Given("서비스가 둘 섞인 요청") {
        When("resource 두 개에 스팬이 하나씩 있으면") {
            val request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(
                    ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder().addAttributes(attr("service.name", "shop-order")))
                        .addScopeSpans(ScopeSpans.newBuilder().addSpans(Span.newBuilder().setName("a"))),
                )
                .addResourceSpans(
                    ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder().addAttributes(attr("service.name", "shop-payment")))
                        .addScopeSpans(ScopeSpans.newBuilder().addSpans(Span.newBuilder().setName("b"))),
                )
                .build()

            Then("각 스팬이 자기 resource 의 서비스 이름을 가져간다") {
                SpanTranslator.toRows(request).map { it.serviceName } shouldBe listOf("shop-order", "shop-payment")
            }
        }
    }
})
