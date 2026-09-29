package com.monimo.collector.sampling

import com.google.protobuf.ByteString
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.trace.v1.ResourceSpans
import io.opentelemetry.proto.trace.v1.ScopeSpans
import io.opentelemetry.proto.trace.v1.Span
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import kotlin.math.abs

// 받은 트레이스에서 남길 스팬만 골라 낸다. Kafka 에 넣기 직전에 한 번 거친다.
//
// 고르는 기준 2가지
//  1) 카나리 표시(trace_state 에 monimon=canary)가 있으면 무조건 남긴다 — 파수꾼이 보낸 것이라 버리면 판정이 불가능하다 (ADR #41)
//  2) 아니면 trace ID 해시가 비율 구간에 드는지 본다 — 무작위가 아니라 ID 로 정하므로,
//     같은 trace 의 스팬이 서로 다른 서비스에서 따로 도착해도 결정이 같다. 그래야 콜트리가 끊기지 않는다
@Component
@EnableConfigurationProperties(SamplingProperties::class)
class TraceSampler(
    private val properties: SamplingProperties,
    registry: MeterRegistry,
) {

    // 해시 값이 이 선보다 작으면 남긴다. 비율 0.01 이면 전체 범위의 1% 지점
    private val upperBound: Long = (properties.ratio * Long.MAX_VALUE).toLong()

    private val kept = counter(registry, "kept", "샘플링에서 남긴 스팬 수")
    private val dropped = counter(registry, "dropped", "샘플링에서 버린 스팬 수")

    // 받은 요청에서 남길 스팬만 남긴 새 요청을 만든다. 전부 버려지면 스팬이 하나도 없는 요청이 나온다.
    fun sample(request: ExportTraceServiceRequest): ExportTraceServiceRequest {
        if (properties.ratio >= 1.0) { // 전부 통과 — 다시 만드는 비용도 아낀다 (로컬 · 테스트에서 이 길로 간다)
            kept.increment(request.spanCount().toDouble())
            return request
        }

        val result = ExportTraceServiceRequest.newBuilder()
        var keptCount = 0
        var droppedCount = 0

        // 구조가 resource → scope → span 3겹이라 안쪽부터 걸러 올린다.
        // 스팬이 하나도 안 남은 scope · resource 는 빈 껍데기가 되므로 넣지 않는다
        for (resourceSpans in request.resourceSpansList) {
            val resourceBuilder = ResourceSpans.newBuilder(resourceSpans).clearScopeSpans() // 서비스 이름 등 resource 속성은 그대로 두고
            for (scopeSpans in resourceSpans.scopeSpansList) {
                val (keep, drop) = scopeSpans.spansList.partition { keep(it) } // partition = 조건에 맞는 것과 아닌 것으로 한 번에 나눈다
                keptCount += keep.size
                droppedCount += drop.size
                if (keep.isNotEmpty()) {
                    resourceBuilder.addScopeSpans(ScopeSpans.newBuilder(scopeSpans).clearSpans().addAllSpans(keep))
                }
            }
            if (resourceBuilder.scopeSpansCount > 0) result.addResourceSpans(resourceBuilder)
        }

        kept.increment(keptCount.toDouble())
        dropped.increment(droppedCount.toDouble())
        return result.build()
    }

    // 스팬 하나를 남길지 정한다
    fun keep(span: Span): Boolean {
        if (isCanary(span.traceState)) return true // 카나리는 비율을 건너뛴다
        if (properties.ratio >= 1.0) return true
        if (properties.ratio <= 0.0) return false
        return abs(hashOf(span.traceId)) < upperBound
    }

    // trace_state 는 "키=값,키=값" 형태(W3C). 항목 하나가 우리 표시와 같은지 본다
    private fun isCanary(traceState: String): Boolean =
        traceState.isNotEmpty() && traceState.split(',').any { it.trim() == properties.canaryMarker }

    // trace ID(16바이트)의 뒤 8바이트를 숫자로 읽는다. ID 가 무작위라 이 숫자도 고르게 퍼진다.
    // OTel 의 비율 샘플러와 같은 방식이라, 나중에 에이전트 쪽 샘플링으로 되돌려도 기준이 같다
    private fun hashOf(traceId: ByteString): Long {
        if (traceId.size() < 8) return 0L
        var value = 0L
        for (i in traceId.size() - 8 until traceId.size()) {
            value = (value shl 8) or (traceId.byteAt(i).toLong() and 0xFF) // 한 바이트씩 왼쪽으로 밀어 붙인다
        }
        return if (value == Long.MIN_VALUE) Long.MAX_VALUE else value // MIN_VALUE 는 abs 로 뒤집을 수 없어 따로 처리
    }

    private fun counter(registry: MeterRegistry, outcome: String, description: String): Counter =
        Counter.builder(METRIC).description(description).baseUnit("spans").tag("outcome", outcome).register(registry)

    companion object {
        const val METRIC = "monimo.collector.sampling"
    }
}

// 요청 안의 스팬을 전부 센다. 여러 곳에서 쓰므로 확장 함수로 뽑았다
fun ExportTraceServiceRequest.spanCount(): Int =
    resourceSpansList.sumOf { rs -> rs.scopeSpansList.sumOf { it.spansCount } }
