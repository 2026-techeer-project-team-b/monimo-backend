package com.monimo.collector.filter

import com.google.protobuf.ByteString
import com.monimo.collector.sampling.spanCount
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import kotlin.random.Random

// 거르는 규칙만 확인하는 단위 테스트. 스프링도 컨테이너도 띄우지 않아 빠르다.
// SimpleMeterRegistry = 메모리에만 값을 쌓는 가벼운 계량기 창고 (테스트용)
class HealthCheckFilterTest : BehaviorSpec({

    // 목록을 바꿔 가며 필터를 만드는 도우미. registry 도 같이 돌려줘 카운터를 읽을 수 있게 한다
    fun filterOf(vararg paths: String): Pair<HealthCheckFilter, SimpleMeterRegistry> {
        val registry = SimpleMeterRegistry()
        return HealthCheckFilter(HealthCheckProperties(paths.toSet()), registry) to registry
    }

    // 버린 수 읽기. 지표 이름과 reason 태그로 찾는다 (운영에서는 /actuator/metrics 가 같은 값을 보여 준다)
    fun SimpleMeterRegistry.droppedCount(): Double =
        get(HealthCheckFilter.METRIC).tag("reason", HealthCheckFilter.REASON_HEALTH_CHECK).counter().count()

    // 스팬 하나 만들기. urlPath 가 null 이면 url.path 속성을 아예 넣지 않는다
    fun span(
        name: String = "GET /orders",
        kind: Span.SpanKind = Span.SpanKind.SPAN_KIND_SERVER,
        urlPath: String? = null,
        spanId: ByteArray = Random.nextBytes(8),
        parentSpanId: ByteArray? = null,
    ): Span = Span.newBuilder()
        .setTraceId(ByteString.copyFrom(Random.nextBytes(16)))
        .setSpanId(ByteString.copyFrom(spanId))
        .also { builder -> parentSpanId?.let { builder.setParentSpanId(ByteString.copyFrom(it)) } }
        .setName(name)
        .setKind(kind)
        .also { builder ->
            if (urlPath != null) {
                builder.addAttributes(
                    KeyValue.newBuilder().setKey("url.path").setValue(AnyValue.newBuilder().setStringValue(urlPath)),
                )
            }
        }
        .build()

    // 스팬 여러 개가 든 요청 만들기
    fun requestOf(vararg spans: Span): ExportTraceServiceRequest =
        ExportTraceServiceRequest.newBuilder()
            .addResourceSpans(
                ResourceSpans.newBuilder().addScopeSpans(ScopeSpans.newBuilder().addAllSpans(spans.toList())),
            )
            .build()

    // 남은 스팬의 이름 목록. 무엇이 남았는지 확인할 때 쓴다
    fun ExportTraceServiceRequest.spanNames(): List<String> =
        resourceSpansList.flatMap { rs -> rs.scopeSpansList.flatMap { it.spansList } }.map { it.name }

    Given("목록이 /actuator/health 하나인 필터") {
        val (filter, registry) = filterOf("/actuator/health")

        When("url.path 가 목록과 같은 SERVER 스팬을 넣으면") {
            val result = filter.drop(requestOf(span(name = "GET /actuator/health", urlPath = "/actuator/health")))

            Then("버려져서 하나도 안 남는다") {
                result.spanCount() shouldBe 0
            }

            Then("버린 수가 1 로 센다") {
                registry.droppedCount() shouldBe 1.0
            }
        }

        When("url.path 가 목록에 없는 SERVER 스팬을 넣으면") {
            val (freshFilter, freshRegistry) = filterOf("/actuator/health")
            val result = freshFilter.drop(requestOf(span(name = "GET /orders", urlPath = "/orders")))

            Then("그대로 남는다") {
                result.spanCount() shouldBe 1
                result.spanNames() shouldContainExactly listOf("GET /orders")
            }

            Then("버린 수가 0 이다") {
                freshRegistry.droppedCount() shouldBe 0.0
            }
        }

        // 파수꾼이나 게이트웨이가 남의 /actuator/health 를 호출한 기록이다.
        // 진짜 호출이라 서버맵 화살표에 필요하므로 버리면 안 된다
        When("url.path 는 같지만 CLIENT 스팬이면") {
            val (freshFilter, _) = filterOf("/actuator/health")
            val result = freshFilter.drop(
                requestOf(span(name = "GET", kind = Span.SpanKind.SPAN_KIND_CLIENT, urlPath = "/actuator/health")),
            )

            Then("남는다") {
                result.spanCount() shouldBe 1
            }
        }

        When("url.path 속성이 아예 없는 SERVER 스팬이면") {
            val (freshFilter, _) = filterOf("/actuator/health")
            val result = freshFilter.drop(requestOf(span(name = "내부 작업", urlPath = null)))

            Then("남는다") {
                result.spanCount() shouldBe 1
            }
        }

        When("헬스체크와 보통 요청이 섞여 있으면") {
            val (freshFilter, freshRegistry) = filterOf("/actuator/health")
            val result = freshFilter.drop(
                requestOf(
                    span(name = "GET /actuator/health", urlPath = "/actuator/health"),
                    span(name = "POST /orders", urlPath = "/orders"),
                    span(name = "GET /actuator/health", urlPath = "/actuator/health"),
                ),
            )

            Then("헬스체크만 빠지고 보통 요청은 남는다") {
                result.spanNames() shouldContainExactly listOf("POST /orders")
            }

            Then("버린 수가 2 로 센다") {
                freshRegistry.droppedCount() shouldBe 2.0
            }
        }

        // 지금 동작을 못 박는 테스트다. 헬스체크가 DB 쿼리나 HTTP 호출을 하게 되면 자식 스팬이 생기고,
        // 부모(SERVER)만 버리므로 자식은 부모 없이 남는다(고아 스팬). 트레이스 상세는 그걸
        // "(누락된 구간)" 아래에 매단다(#50).
        //
        // 로컬 실데이터에서는 아직 안 생긴다. 쇼핑몰 actuator 의 db 지표가 쿼리를 보내는 대신
        // Connection.isValid() 로 확인해서 OTel 이 스팬을 만들지 않기 때문이다.
        // 그 조건이 바뀌면 이 테스트가 먼저 말해 준다. 그때 트레이스 단위 제거로 넓힌다
        When("헬스체크 SERVER 스팬에 자식 CLIENT 스팬이 붙어 있으면") {
            val (freshFilter, _) = filterOf("/actuator/health")
            val parentId = Random.nextBytes(8)
            val result = freshFilter.drop(
                requestOf(
                    span(name = "GET /actuator/health", urlPath = "/actuator/health", spanId = parentId),
                    span(
                        name = "SELECT shop.orders",
                        kind = Span.SpanKind.SPAN_KIND_CLIENT,
                        urlPath = null,
                        parentSpanId = parentId,
                    ),
                ),
            )

            Then("부모만 버려지고 자식은 남는다 (고아 스팬이 된다)") {
                result.spanNames() shouldContainExactly listOf("SELECT shop.orders")
            }
        }

        When("전부 헬스체크였으면") {
            val (freshFilter, _) = filterOf("/actuator/health")
            val result = freshFilter.drop(
                requestOf(
                    span(urlPath = "/actuator/health"),
                    span(urlPath = "/actuator/health"),
                ),
            )

            Then("스팬이 하나도 없는 요청이 나온다") {
                result.spanCount() shouldBe 0
            }

            Then("빈 scope · resource 껍데기를 넣지 않는다") {
                result.resourceSpansCount shouldBe 0
            }
        }
    }

    Given("목록이 빈 필터 (필터 꺼짐)") {
        val (filter, registry) = filterOf()

        When("헬스체크 스팬을 넣으면") {
            val request = requestOf(span(name = "GET /actuator/health", urlPath = "/actuator/health"))
            val result = filter.drop(request)

            Then("아무것도 안 버린다") {
                result.spanCount() shouldBe 1
            }

            Then("받은 요청을 그대로 돌려준다 (다시 만들지 않는다)") {
                (result === request) shouldBe true
            }

            Then("버린 수가 0 이다") {
                registry.droppedCount() shouldBe 0.0
            }
        }
    }

    Given("목록에 주소가 여럿인 필터") {
        val (filter, _) = filterOf("/actuator/health", "/healthz", "/readyz")

        When("각 주소의 SERVER 스팬을 넣으면") {
            val result = filter.drop(
                requestOf(
                    span(urlPath = "/actuator/health"),
                    span(urlPath = "/healthz"),
                    span(urlPath = "/readyz"),
                    span(name = "POST /orders", urlPath = "/orders"),
                ),
            )

            Then("목록에 있는 셋만 버리고 나머지는 남긴다") {
                result.spanNames() shouldContainExactly listOf("POST /orders")
            }
        }

        // 접두 일치를 쓰지 않는 이유를 못 박는다. /actuator/health 로 시작하는 다른 주소
        // (/actuator/health/liveness 같은 것)는 목록에 없으면 남는다.
        // 틀리게 버리면 진짜 트래픽이 사라지므로 안전한 쪽(안 버리는 쪽)을 고른다
        When("목록 주소로 시작하지만 정확히 같지 않은 주소면") {
            val (freshFilter, _) = filterOf("/actuator/health")
            val result = freshFilter.drop(
                requestOf(span(name = "GET /actuator/health/liveness", urlPath = "/actuator/health/liveness")),
            )

            Then("정확 일치가 아니라 남는다") {
                result.spanCount() shouldBe 1
            }
        }
    }
})
