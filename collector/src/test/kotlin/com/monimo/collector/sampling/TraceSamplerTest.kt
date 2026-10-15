package com.monimo.collector.sampling

import com.google.protobuf.ByteString
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.resource.v1.Resource
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import kotlin.random.Random

// 샘플링 규칙만 확인하는 단위 테스트. 스프링도 컨테이너도 띄우지 않아 빠르다.
// SimpleMeterRegistry = 메모리에만 값을 쌓는 가벼운 계량기 창고 (테스트용)
//
// 비율은 PG 에서 오므로(ADR #53) 포트 자리에 가짜를 끼운다. PG 조회 쪽 동작은
// PostgresSamplingRateSourceTest 가 따로 본다
class TraceSamplerTest : BehaviorSpec({

    // 비율 하나를 그대로 주는 가짜 공급자. 묶음 하나에 줄 하나면 그 묶음의 선이 그 값이고,
    // 기본 선(미등록 · 이름 없는 요청)도 같은 값으로 둬서 resource 가 없는 요청에도 같은 선이 걸린다
    fun fixedRate(ratio: Double) =
        SamplingRateSource { SamplingRates.of(mapOf("shop-gateway" to ServiceRate("shop", ratio)), ratio) }

    // 비율을 바꿔 가며 샘플러를 만드는 도우미
    fun samplerOf(ratio: Double) = TraceSampler(SamplingProperties(), fixedRate(ratio), SimpleMeterRegistry())

    // 스팬 하나 만들기. traceId 는 16바이트, traceState 는 W3C 형식 문자열
    fun span(traceId: ByteArray = Random.nextBytes(16), traceState: String = ""): Span =
        Span.newBuilder()
            .setTraceId(ByteString.copyFrom(traceId))
            .setTraceState(traceState)
            .setName("GET /orders")
            .build()

    // resource 에 service.name 을 붙인 묶음 하나. 서비스 이름은 스팬이 아니라 한 겹 위에 있다
    fun resourceSpansOf(serviceName: String, vararg spans: Span): ResourceSpans.Builder =
        ResourceSpans.newBuilder()
            .setResource(
                Resource.newBuilder().addAttributes(
                    KeyValue.newBuilder()
                        .setKey("service.name")
                        .setValue(AnyValue.newBuilder().setStringValue(serviceName)),
                ),
            )
            .addScopeSpans(ScopeSpans.newBuilder().addAllSpans(spans.toList()))

    fun serviceNameIn(resourceSpans: ResourceSpans): String =
        resourceSpans.resource.attributesList.first { it.key == "service.name" }.value.stringValue

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

    Given("공급자가 주는 비율이 중간에 바뀌면") {
        // upperBound 를 생성 시점에 한 번 계산해 두면 이 시험이 깨진다 (ADR #53 구현 주의)
        var ratio = 0.0
        val sampler = TraceSampler(
            SamplingProperties(),
            SamplingRateSource { SamplingRates.of(mapOf("shop-gateway" to ServiceRate("shop", ratio)), ratio) },
            SimpleMeterRegistry(),
        )

        When("0.0 일 때 스팬 3개를 넣으면") {
            Then("하나도 안 남는다") {
                sampler.sample(requestOf(span(), span(), span())).spanCount() shouldBe 0
            }
        }

        When("공급자가 1.0 으로 바뀐 뒤 다시 넣으면") {
            ratio = 1.0

            Then("3개가 그대로 남는다") {
                sampler.sample(requestOf(span(), span(), span())).spanCount() shouldBe 3
            }
        }
    }

    Given("등록 안 된 서비스에서 온 스팬") {
        val registry = SimpleMeterRegistry()
        // 등록된 쇼핑몰은 전부 통과, 미등록은 기본 선 0.0(전부 버림)으로 둬서 결과가 갈리는 것을 본다
        val sampler = TraceSampler(
            SamplingProperties(),
            SamplingRateSource { SamplingRates.of(mapOf("shop-gateway" to ServiceRate("shop", 1.0)), 0.0) },
            registry,
        )

        // resource 에 service.name 을 붙인 요청. 서비스 이름은 스팬이 아니라 한 겹 위에 있다
        fun requestFrom(serviceName: String, vararg spans: Span): ExportTraceServiceRequest =
            ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(
                    ResourceSpans.newBuilder()
                        .setResource(
                            Resource.newBuilder().addAttributes(
                                KeyValue.newBuilder()
                                    .setKey("service.name")
                                    .setValue(AnyValue.newBuilder().setStringValue(serviceName)),
                            ),
                        )
                        .addScopeSpans(ScopeSpans.newBuilder().addAllSpans(spans.toList())),
                )
                .build()

        When("목록에 없는 이름으로 2개를 보내면") {
            val result = sampler.sample(requestFrom("stranger-app", span(), span()))

            Then("기본 선으로 걸린다 : 묶음을 모르니 남의 묶음 값을 빌리지 않는다 (ADR #72)") {
                result.spanCount() shouldBe 0
            }

            Then("미등록 카운터가 2건 센다") {
                registry.get("${TraceSampler.METRIC}.unknown_service").counter().count() shouldBe 2.0
            }
        }

        When("목록에 있는 이름으로 보내면") {
            val before = registry.get("${TraceSampler.METRIC}.unknown_service").counter().count()
            val result = sampler.sample(requestFrom("shop-gateway", span()))

            Then("미등록 카운터가 안 늘어난다") {
                registry.get("${TraceSampler.METRIC}.unknown_service").counter().count() shouldBe before
            }

            Then("그 묶음의 선으로 걸린다") {
                result.spanCount() shouldBe 1
            }
        }
    }

    // 이 이슈(#206 · ADR #72)의 본 동작. 묶음마다 선을 따로 고르는지 본다
    Given("쇼핑몰은 0.0 · 모니모는 1.0 인데 한 요청에 둘이 섞여 오면") {
        val sampler = TraceSampler(
            SamplingProperties(),
            SamplingRateSource {
                SamplingRates.of(
                    mapOf(
                        "shop-order" to ServiceRate("shop", 0.0),
                        "monimo-api-server" to ServiceRate("monimo", 1.0),
                    ),
                    fallback = 0.0,
                )
            },
            SimpleMeterRegistry(),
        )

        // resource 묶음 두 개가 든 요청 하나. 규격상 이렇게 섞여 올 수 있다
        fun mixedRequest(): ExportTraceServiceRequest =
            ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(resourceSpansOf("shop-order", span(), span(), span()))
                .addResourceSpans(resourceSpansOf("monimo-api-server", span(), span()))
                .build()

        When("샘플러를 지나면") {
            val result = sampler.sample(mixedRequest())

            Then("모니모 스팬만 남는다 : 쇼핑몰은 자기 선(0.0)으로 버려졌다") {
                result.spanCount() shouldBe 2
            }

            Then("남은 묶음의 서비스 이름이 모니모다 : resource 속성은 그대로 따라간다") {
                result.resourceSpansCount shouldBe 1
                serviceNameIn(result.getResourceSpans(0)) shouldBe "monimo-api-server"
            }
        }
    }

    Given("같은 묶음의 서로 다른 서비스가 같은 trace_id 로 오면") {
        // 둘 다 shop 묶음이라 선은 0.5 하나다. 트레이스가 쪼개지지 않는 것이 ADR #53 의 약속
        val sampler = TraceSampler(
            SamplingProperties(),
            SamplingRateSource {
                SamplingRates.of(
                    mapOf(
                        "shop-gateway" to ServiceRate("shop", 0.5),
                        "shop-order" to ServiceRate("shop", 0.01),
                    ),
                    fallback = 0.0,
                )
            },
            SimpleMeterRegistry(),
        )

        fun sampleOne(serviceName: String, traceId: ByteArray): Int =
            sampler.sample(
                ExportTraceServiceRequest.newBuilder()
                    .addResourceSpans(resourceSpansOf(serviceName, span(traceId)))
                    .build(),
            ).spanCount()

        When("trace_id 100개를 두 서비스로 나눠 보내면") {
            val results = (1..100).map {
                val traceId = Random.nextBytes(16)
                sampleOne("shop-gateway", traceId) to sampleOne("shop-order", traceId)
            }

            Then("두 서비스의 결정이 언제나 같다 : 같은 선 · 같은 번호라 쪼개질 수 없다") {
                results.count { (gateway, order) -> gateway != order } shouldBe 0
            }

            Then("그래도 일부는 남고 일부는 버려진다 : 선 0.5 가 실제로 걸렸다는 뜻") {
                results.count { it.first == 1 } shouldBeGreaterThan 0
                results.count { it.first == 0 } shouldBeGreaterThan 0
            }
        }
    }

    Given("계량기") {
        val registry = SimpleMeterRegistry()
        val sampler = TraceSampler(SamplingProperties(), fixedRate(0.0), registry)

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
