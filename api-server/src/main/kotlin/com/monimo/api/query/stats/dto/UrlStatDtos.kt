package com.monimo.api.query.stats.dto

// URL(요청 이름) 하나의 구간 합계 (API 명세 #12). error_rate 는 0 ~ 1
data class UrlStatResponse(
    val spanName: String,
    val cnt: Long,
    val errCnt: Long,
    val errorRate: Double,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
)

// 커서에 담는 마지막 줄 위치. 호출 많은 순 → 이름 순이라 이보다 뒤 줄부터 다음 쪽이다
data class UrlStatCursor(
    val cnt: Long,
    val name: String,
)
