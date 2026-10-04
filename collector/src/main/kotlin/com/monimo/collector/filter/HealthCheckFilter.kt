package com.monimo.collector.filter

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component

// 받은 트레이스에서 헬스체크 스팬을 빼낸다. Kafka 에 넣기 전, 샘플링보다 먼저 한 번 거친다.
//
// 왜 버리나: 도커 · 쿠버네티스가 쇼핑몰의 /actuator/health 를 몇 초마다 찌르는데, 에이전트가 그걸
//   진짜 요청과 똑같이 기록해 보낸다. ClickHouse 집계표(service_health_1m · transactions → 히트맵 ·
//   url_stats_1m)에 이름을 거르는 조건이 없어 호출 수가 부풀고 에러율 · P95 가 희석된다.
//   그러면 5XX_RATE · P95_LATENCY 경보가 안 터지고 히트맵에도 쌓인다.
//
// 왜 여기서 버리나: 집계표(MV)는 insert 시점에 계산된다. spans 에 들어간 뒤에는 조회가 못 고친다
//   (#83 에서 겪은 것과 같은 이유). 그리고 수집기에서 버리면 Kafka · ClickHouse 저장도 안 하고,
//   히트맵이 거쳐 가는 transactions 까지 한 번에 깨끗해진다.
//   에이전트에서 거르는 방법도 있지만 쇼핑몰 4곳에 설정 파일이나 jar 를 얹어야 해서
//   "에이전트만 붙이면 된다"는 전제(ADR #33)가 깨진다.
//
// 거르는 기준 2가지를 모두 만족해야 버린다
//  1) SERVER 스팬이다 - 헬스체크를 "받은" 기록. CLIENT 는 남긴다. 파수꾼이나 게이트웨이가 남의
//     /health 를 "호출한" 기록은 진짜 호출이라 서버맵 화살표에 필요하다
//  2) url.path 가 목록과 정확히 같다 - http.route 가 아니라 url.path 를 보는 이유는 OTel 규약에서
//     url.path 는 Required(항상 있다)이고 http.route 는 Conditionally Required(프레임워크가
//     알려 줄 때만)라서다. 지금은 둘 다 채워지지만 WebFlux · 게이트웨이로 바꾸면 route 가 빌 수 있다
@Component
@EnableConfigurationProperties(HealthCheckProperties::class)
class HealthCheckFilter(
    private val properties: HealthCheckProperties,
    registry: MeterRegistry,
) {

    // 버린 스팬 수. 미리 등록해 한 건도 안 버렸을 때도 0 으로 보이게 한다.
    // 샘플링이 버린 수(monimo.collector.sampling{outcome=dropped})와 지표 이름을 달리해서,
    // "샘플링에서 버린 수" 를 물을 때 헬스체크가 섞여 나오지 않게 한다
    private val dropped: Counter = Counter.builder(METRIC)
        .description("헬스체크로 판정해 버린 스팬 수")
        .baseUnit("spans")
        .tag("reason", REASON_HEALTH_CHECK)
        .register(registry)

    // 헬스체크 스팬을 뺀 새 요청을 만든다. 전부 헬스체크였으면 스팬이 하나도 없는 요청이 나온다.
    fun drop(request: ExportTraceServiceRequest): ExportTraceServiceRequest {
        // 목록이 비면 필터가 꺼진 것이다. 다시 만드는 비용도 아낀다
        if (properties.paths.isEmpty()) return request

        val result = ExportTraceServiceRequest.newBuilder()
        var droppedCount = 0

        // 구조가 resource → scope → span 3겹이라 안쪽부터 걸러 올린다.
        // 스팬이 하나도 안 남은 scope · resource 는 빈 껍데기가 되므로 넣지 않는다
        for (resourceSpans in request.resourceSpansList) {
            val resourceBuilder = ResourceSpans.newBuilder(resourceSpans).clearScopeSpans() // 서비스 이름 등 resource 속성은 그대로 두고
            for (scopeSpans in resourceSpans.scopeSpansList) {
                val (drop, keep) = scopeSpans.spansList.partition { isHealthCheck(it) } // partition = 조건에 맞는 것과 아닌 것으로 한 번에 나눈다
                droppedCount += drop.size
                if (keep.isNotEmpty()) {
                    resourceBuilder.addScopeSpans(ScopeSpans.newBuilder(scopeSpans).clearSpans().addAllSpans(keep))
                }
            }
            if (resourceBuilder.scopeSpansCount > 0) result.addResourceSpans(resourceBuilder)
        }

        dropped.increment(droppedCount.toDouble())
        return result.build()
    }

    // 스팬 하나가 헬스체크인지 본다. 받은 기록(SERVER)이면서 주소가 목록에 있을 때만 참이다
    fun isHealthCheck(span: Span): Boolean =
        span.kind == Span.SpanKind.SPAN_KIND_SERVER && pathOf(span) in properties.paths

    // 스팬 속성은 KeyValue 목록이다. url.path 를 찾아 글자 값을 꺼낸다.
    // 속성이 없으면 null 이고, null 은 목록에 없으니 그 스팬은 남는다
    private fun pathOf(span: Span): String? =
        span.attributesList.firstOrNull { it.key == URL_PATH }?.value?.stringValue

    companion object {
        const val METRIC = "monimo.collector.dropped"
        const val REASON_HEALTH_CHECK = "health_check"

        // OTel 의미 규약(semantic conventions)의 이름. 요청의 실제 경로이고 HTTP 서버 스팬에 항상 있다
        const val URL_PATH = "url.path"
    }
}
