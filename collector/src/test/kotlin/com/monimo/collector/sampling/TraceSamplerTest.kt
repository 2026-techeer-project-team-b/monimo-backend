package com.monimo.collector.sampling

import com.google.protobuf.ByteString
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import kotlin.random.Random

// 샘플링 규칙만 확인하는 단위 테스트. 스프링도 컨테이너도 띄우지 않아 빠르다.
// SimpleMeterRegistry = 메모리에만 값을 쌓는 가벼운 계량기 창고 (테스트용)
class TraceSamplerTest : BehaviorSpec({

    // 비율을 바꿔 가며 샘플러를 만드는 도우미
    fun samplerOf(ratio: Double) = TraceSampler(SamplingProperties(ratio = ratio), SimpleMeterRegistry())

    // 스팬 하나 만들기. traceId 는 16바이트, traceState 는 W3C 형식 문자열
    fun span(traceId: ByteArray = Random.nextBytes(16), traceState: String = ""): Span =
        Span.newBuilder()
            .setTraceId(ByteString.copyFrom(traceId))
            .setTraceState(traceState)
            .setName("GET /orders")
            .build()

    // 스팬 여러 개가 든 요청 만들기
    fun requestOf(vararg spans: Span): ExportTraceServiceRequest =
        ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(
                ResourceSpans.newBuilder().addScopeSpans(ScopeSpans.newBuilder().addAllSpans(spans.toList())),
            )
            .build()

    Given("비율 1.0 (전부 통과)") {
        val sampler = samplerOf(1.0)

        When("스팬 3개를 넣으면") {
            val result = sampler.sample(requestOf(span(), span(), span()))

            Then("3개가 그대로 남는다") {
                result.spanCount() shouldBe 3
            }
        }
    }

    Given("비율 0.0 (전부 버림)") {
        val sampler = samplerOf(0.0)

        When("스팬 3개를 넣으면") {
            val result = sampler.sample(requestOf(span(), span(), span()))

            Then("하나도 안 남는다") {
                result.spanCount() shouldBe 0
            }

            Then("빈 resource 껍데기도 남기지 않는다") {
                result.resourceSpansCount shouldBe 0
            }
        }

        When("카나리 표시가 붙은 스팬을 넣으면") {
            val canary = span(traceState = "monimon=canary")
            val result = sampler.sample(requestOf(canary, span(), span()))

            Then("비율이 0이어도 카나리만 남는다 (ADR #41)") {
                result.spanCount() shouldBe 1
            }
        }

        When("카나리 표시가 다른 항목과 섞여 있으면") {
            val canary = span(traceState = "vendor=abc, monimon=canary")

            Then("그래도 통과시킨다 (trace_state 는 쉼표로 이어 붙인 목록이다)") {
                sampler.keep(canary) shouldBe true
            }
        }

        When("비슷하지만 다른 표시가 붙어 있으면") {
            Then("통과시키지 않는다") {
                sampler.keep(span(traceState = "monimon=canaryx")) shouldBe false
                sampler.keep(span(traceState = "other=canary")) shouldBe false
            }
        }
    }

    Given("비율 0.5") {
        val sampler = samplerOf(0.5)

        When("같은 trace ID 를 가진 스팬 2개를 각각 물어보면") {
            val traceId = Random.nextBytes(16)
            val first = sampler.keep(span(traceId))
            val second = sampler.keep(span(traceId))

            Then("결정이 같다 — 한 trace 의 스팬은 같이 남거나 같이 버려진다") {
                first shouldBe second
            }
        }

        When("서로 다른 trace ID 1000개를 물어보면") {
            val kept = (1..1000).count { sampler.keep(span()) }

            Then("대략 절반이 남는다 (300~700)") {
                kept shouldBeGreaterThan 300
                kept shouldBeLessThan 700
            }
        }
    }

    Given("계량기") {
        val registry = SimpleMeterRegistry()
        val sampler = TraceSampler(SamplingProperties(ratio = 0.0), registry)

        When("스팬 2개가 전부 버려지면") {
            sampler.sample(requestOf(span(), span()))

            Then("dropped 태그로 2건이 잡힌다") {
                registry.get(TraceSampler.METRIC).tag("outcome", "dropped").counter().count() shouldBe 2.0
            }
        }

        When("카나리가 섞여 있으면") {
            sampler.sample(requestOf(span(traceState = "monimon=canary")))

            Then("kept 가 늘어난다") {
                registry.get(TraceSampler.METRIC).tag("outcome", "kept").counter().count() shouldBeGreaterThan 0.0
            }
        }
    }
})
