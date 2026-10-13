package com.monimo.api.query.transaction.dto

// 요청 한 건 (API 명세 #26). 값 규칙은 스캐터 점(ScatterPoint)과 같다
data class TransactionResponse(
    val traceId: String,
    val startTime: String,
    val durationMs: Long,
    val serviceName: String,
    val agentKey: String,
    val spanName: String,
    val isError: Boolean,
    val httpStatus: Int?, // HTTP 가 아닌 요청이면 null
)

// 목록 조건. 스캐터에서 드래그한 사각형의 세로 변이 minDurationMs ~ maxDurationMs(둘 다 포함)다
data class TransactionSearch(
    val serviceName: String,
    val agentKey: String?,
    val minDurationMs: Long?,
    val maxDurationMs: Long?,
    val isError: Boolean?,
)

// 커서에 담는 마지막 줄 위치. 느린 순 → 최신 순이라 이보다 뒤(작은) 줄부터 다음 쪽이다
data class TransactionCursor(
    val dur: Long,
    val ts: Long,
    val id: String,
)
