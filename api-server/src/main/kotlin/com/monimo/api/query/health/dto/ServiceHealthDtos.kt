package com.monimo.api.query.health.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

// 서비스 하나의 한 버킷(기본 1분) 건강 지표. 지연 백분위는 ms 정수
data class ServiceHealthResponse(
    val tsMin: Instant,
    val serviceName: String,
    val cnt: Long,
    val errCnt: Long,
    // 숫자로 끝나는 이름은 snake_case 변환이 cnt4xx 로 붙여 버려서 명세 이름을 직접 적는다
    @get:JsonProperty("cnt_4xx") val cnt4xx: Long,
    @get:JsonProperty("cnt_5xx") val cnt5xx: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
)
