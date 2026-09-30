package com.monimo.detector.alert.evaluate

import java.time.Instant

// API 서버 내부 문 GET /api/v1/internal/service-health 의 1분 버킷 한 줄 (조회 파트 #48 구현 그대로)
// 요청이 0건인 분은 줄 자체가 없다 — 적재가 안 끝난 1분과 구분하려고 0 으로 채우지 않는다
data class HealthBucket(
    val start: Instant,
    val cnt: Long,
    val cnt4xx: Long,
    val cnt5xx: Long,
    val p95Ms: Long,
)

// 조회 계약이 바뀌면 이 인터페이스 구현만 고친다. 판정 · 기록 쪽은 그대로
fun interface ServiceHealthClient {
    // [from, to) 의 1분 버킷을 오래된 순으로. 조회에 실패하면 예외를 던진다 (빈 목록으로 삼키지 않는다)
    fun fetch(serviceName: String, from: Instant, to: Instant): List<HealthBucket>
}

class ServiceHealthQueryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
