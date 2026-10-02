package com.monimo.api.query.trace.dto

// 트레이스 하나 (API 명세 #14). root 아래 children 으로 부모-자식 트리를 이룬다
data class TraceResponse(
    val traceId: String,
    val spanCount: Int,
    val services: List<String>,
    val root: SpanResponse,
)

// 스팬 하나. 시각은 나노초 9자리 고정(화면이 글자로 정렬해서 자릿수가 같아야 한다)
data class SpanResponse(
    val spanId: String,
    val parentSpanId: String?,
    val serviceName: String,
    val agentKey: String,
    val spanName: String,
    val spanKind: String,
    val startTime: String,
    val durationNs: Long,
    val statusCode: String,
    val httpStatus: Int?,
    val attributes: Map<String, String>,
    val events: List<SpanEventResponse>,
    val children: List<SpanResponse>,
)

data class SpanEventResponse(
    val ts: String,
    val name: String,
    val attributes: Map<String, String>,
)
