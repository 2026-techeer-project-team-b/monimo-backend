package com.monimo.api.query.metric.dto

import java.time.Instant

// 파드 하나의 1분 동안 GC 에 쓴 시간(ms)과 횟수. GC 종류별 줄을 각각 뺀 뒤 더한 값이다 (API 명세 #51)
data class GcTimeResponse(
    val serviceName: String,
    val agentKey: String,
    val tsMin: Instant,
    val gcTimeMs: Double,
    val gcCount: Long,
)
