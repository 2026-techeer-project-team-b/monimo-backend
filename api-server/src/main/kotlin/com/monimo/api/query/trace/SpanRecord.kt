package com.monimo.api.query.trace

// spans 표에서 읽은 스팬 한 줄. 트리로 조립하기 전 평면 모양이다
data class SpanRecord(
    val spanId: String,
    val parentSpanId: String?, // 루트 스팬이면 null (CH 는 '')
    val serviceName: String,
    val agentKey: String,
    val spanName: String,
    val spanKind: String,
    val startNs: Long,
    val durationNs: Long,
    val statusCode: String,
    val httpStatus: Int?, // HTTP 가 아닌 스팬이면 null (CH 는 0)
    val attributes: Map<String, String>,
    val events: List<SpanEventRecord>,
)

data class SpanEventRecord(
    val tsNs: Long,
    val name: String,
    val attributes: Map<String, String>,
)
