package com.monimo.collector.filter

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import org.slf4j.LoggerFactory
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

    // 버린 스팬 수를 경로별로 센다. 설정 목록에 있는 경로만 미리 등록하므로 가짓수가 목록 크기로 묶인다
    // (지표 가짓수가 들어오는 값에 따라 늘어나면 안 된다).
    //
    // 경로별로 나누는 이유: 목록을 잘못 써서 진짜 트래픽이 사라질 때 그게 드러나야 한다.
    // 예를 들어 목록에 /api 를 잘못 넣으면 path=/api 의 수가 치솟는다. 합계만 세면 "많이 버렸다" 는
    // 보이지만 "무엇을" 버렸는지는 안 보인다.
    // /actuator/metrics/monimo.collector.dropped?tag=reason:health_check 로 물으면 경로를 합쳐
    // 전체 수가 나온다 (연결 점검 스크립트가 그렇게 읽는다)
    private val droppedByPath: Map<String, Counter> = properties.paths.associateWith { path ->
        Counter.builder(METRIC)
            .description("헬스체크로 판정해 버린 스팬 수")
            .baseUnit("spans")
            .tag("reason", REASON_HEALTH_CHECK)
            .tag("path", path)
            .register(registry)
    }

    init {
        // 돌고 있는 수집기의 실제 목록을 확인할 방법이 필요하다. 관리 문은 health · metrics 만 열려 있어
        // configprops 로 못 보고, 목록을 잘못 쓰면 진짜 트래픽이 사라지는 설정이라 기동 때 한 줄 남긴다
        if (properties.paths.isEmpty()) {
            log.info("헬스체크 거르기: 꺼짐 (목록이 비어 있어 스팬을 버리지 않는다)")
        } else {
            log.info("헬스체크 거르기: SERVER 스팬의 url.path 가 {} 중 하나와 정확히 같으면 버린다", properties.paths)
        }
    }

    // 헬스체크 스팬을 뺀 새 요청을 만든다. 전부 헬스체크였으면 스팬이 하나도 없는 요청이 나온다.
    fun drop(request: ExportTraceServiceRequest): ExportTraceServiceRequest {
        // 목록이 비면 필터가 꺼진 것이다. 다시 만드는 비용도 아낀다
        if (properties.paths.isEmpty()) return request

        // 버릴 게 하나도 없으면 받은 객체를 그대로 돌려준다.
        // 성능 때문이 아니라 약속 때문이다: 수집기는 받은 protobuf 바이트를 풀지 않고 그대로
        // Kafka 에 넣는다(#16). 다시 만들면 바이트가 같다는 보장이 "스키마가 이러하므로 같을 것"
        // 이라는 가정으로 내려앉는다. TraceSampler 도 같은 이유로 ratio >= 1.0 지름길을 둔다
        if (request.resourceSpansList.none { rs -> rs.scopeSpansList.any { it.spansList.any(::isHealthCheck) } }) {
            return request
        }

        val result = ExportTraceServiceRequest.newBuilder()

        // 구조가 resource → scope → span 3겹이라 안쪽부터 걸러 올린다.
        // 스팬이 하나도 안 남은 scope · resource 는 빈 껍데기가 되므로 넣지 않는다.
        //
        // 이 3겹 돌기는 TraceSampler.sample 과 모양이 같다. 일부러 합치지 않았다:
        // 거르는 기준(여기는 속성 일치, 저기는 trace ID 해시)과 지름길 조건이 다르고, 공통으로 빼면
        // 판정 함수와 버린 스팬 처리를 인자로 받는 고차 함수가 되어 두 개의 구체적인 반복문보다 읽기 어렵다.
        // 같은 모양이 **셋**이 되면(예: L 의 로그 하한 거르기) 그때 빼는 것이 맞다
        for (resourceSpans in request.resourceSpansList) {
            val resourceBuilder = ResourceSpans.newBuilder(resourceSpans).clearScopeSpans() // 서비스 이름 등 resource 속성은 그대로 두고
            for (scopeSpans in resourceSpans.scopeSpansList) {
                val (drop, keep) = scopeSpans.spansList.partition { isHealthCheck(it) } // partition = 조건에 맞는 것과 아닌 것으로 한 번에 나눈다
                drop.forEach { span -> droppedByPath[pathOf(span)]?.increment() } // 어느 경로를 버렸는지까지 센다
                if (keep.isNotEmpty()) {
                    resourceBuilder.addScopeSpans(ScopeSpans.newBuilder(scopeSpans).clearSpans().addAllSpans(keep))
                }
            }
            if (resourceBuilder.scopeSpansCount > 0) result.addResourceSpans(resourceBuilder)
        }

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

        private val log = LoggerFactory.getLogger(HealthCheckFilter::class.java)
    }
}
