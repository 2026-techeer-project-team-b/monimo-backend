package com.monimo.api.query.transaction.dto

// 스캐터 차트 (API 명세 #5). raw = 점 하나가 요청 하나, bucketed = 점이 limit 을 넘어 격자로 접은 결과
data class ScatterResponse(
    val mode: String,
    val totalCount: Long,
    val points: List<ScatterPoint>,
)

// 요청 하나. bucketed 에서도 격자 칸을 대표하는 실제 요청이라 trace_id 로 트레이스 상세를 열 수 있다
data class ScatterPoint(
    val traceId: String,
    val startTime: String,
    val durationMs: Long,
    val isError: Boolean,
    val httpStatus: Int?, // HTTP 가 아닌 요청이면 null
    val spanName: String,
    val agentKey: String,
)
