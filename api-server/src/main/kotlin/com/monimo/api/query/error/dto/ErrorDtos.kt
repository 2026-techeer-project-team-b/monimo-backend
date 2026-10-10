package com.monimo.api.query.error.dto

import java.time.Instant

// 실패한 스팬 한 줄 (API 명세 #3). 스택트레이스는 싣지 않는다 — 트레이스 상세에서 본다
data class ErrorSpanResponse(
    val traceId: String,
    val spanId: String,
    val startTime: String,
    val durationNs: Long,
    val serviceName: String,
    val agentKey: String,
    val spanName: String,
    val spanKind: String,
    val statusCode: String,
    val httpStatus: Int?, // HTTP 가 아닌 스팬이면 null
    val exceptionType: String?, // 예외 이벤트가 없으면 null
    val exceptionMessage: String?,
)

// 목록 조건. agentKey · httpStatus · exceptionType 은 정확히 같은 값만 고른다
data class ErrorSearch(
    val serviceName: String,
    val agentKey: String?,
    val httpStatus: Int?,
    val exceptionType: String?,
)

// 커서에 담는 마지막 줄 위치. 시간 역순이라 이보다 앞선(작은) 줄부터 다음 쪽이다
data class ErrorCursor(
    val ts: Long,
    val id: String,
)

// 시간대별 에러 건수 (API 명세 #36). 건수가 0인 칸은 series 에 없다
data class ErrorTimelineResponse(
    val step: Int,
    val series: List<ErrorTimelinePoint>,
)

data class ErrorTimelinePoint(
    val tsMin: Instant,
    val httpStatusClass: String, // 5xx · 4xx · other(HTTP 가 아니거나 400 미만)
    val exceptionType: String?, // 예외 이벤트가 없으면 null
    val cnt: Long,
)
