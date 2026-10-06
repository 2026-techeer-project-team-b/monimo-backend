package com.monimo.api.query.trace

import com.monimo.api.query.trace.dto.SpanEventResponse
import com.monimo.api.query.trace.dto.SpanResponse
import com.monimo.api.query.support.NanoTime
import com.monimo.api.query.trace.dto.TraceResponse

// 평면 스팬 목록을 부모-자식 트리로 조립한다. ClickHouse 에는 트리 조회가 없어 서버 코드에서 쌓는다
object SpanTree {

    // 부모 스팬이 없는 스팬이 2개 이상일 때 루트로 세우는 자리. 실제 스팬이 아니라 span_id 가 비어 있다 (#50 B안)
    const val MISSING_SPAN_NAME = "(누락된 구간)"

    fun assemble(traceId: String, records: List<SpanRecord>): TraceResponse {
        require(records.isNotEmpty()) { "스팬이 없는 트레이스는 조립할 수 없다" }
        val sorted = records.sortedBy { it.startNs }
        val ids = sorted.map { it.spanId }.toSet()
        val childrenOf = sorted.groupBy { it.parentSpanId }

        // 부모가 없거나(루트 스팬) 부모가 아직 도착하지 않은 스팬이 트리의 맨 위가 된다
        val tops = sorted.filter { it.parentSpanId == null || it.parentSpanId !in ids }

        fun build(record: SpanRecord): SpanResponse =
            record.toResponse(childrenOf[record.spanId].orEmpty().map(::build))

        val topNodes = tops.map(::build)
        val root = topNodes.singleOrNull() ?: missingRoot(tops, topNodes)

        return TraceResponse(
            traceId = traceId,
            spanCount = records.size,
            services = sorted.map { it.serviceName }.distinct(),
            root = root,
        )
    }

    // 맨 위가 여럿이면 그중 하나를 부모로 삼지 않고 빈 자리 아래 나란히 둔다. 실제로 없는 호출 관계를 만들지 않으려고
    private fun missingRoot(tops: List<SpanRecord>, topNodes: List<SpanResponse>): SpanResponse {
        val start = tops.minOf { it.startNs }
        val end = tops.maxOf { it.startNs + it.durationNs }
        return SpanResponse(
            spanId = "",
            parentSpanId = null,
            serviceName = "",
            agentKey = "",
            spanName = MISSING_SPAN_NAME,
            spanKind = "INTERNAL",
            startTime = format(start),
            durationNs = end - start,
            statusCode = "UNSET",
            httpStatus = null,
            attributes = emptyMap(),
            events = emptyList(),
            children = topNodes,
        )
    }

    private fun SpanRecord.toResponse(children: List<SpanResponse>) = SpanResponse(
        spanId = spanId,
        parentSpanId = parentSpanId,
        serviceName = serviceName,
        agentKey = agentKey,
        spanName = spanName,
        spanKind = spanKind,
        startTime = format(startNs),
        durationNs = durationNs,
        statusCode = statusCode,
        httpStatus = httpStatus,
        attributes = attributes,
        events = events.map { SpanEventResponse(format(it.tsNs), it.name, it.attributes) },
        children = children,
    )

    fun format(epochNanos: Long): String = NanoTime.format(epochNanos)
}
