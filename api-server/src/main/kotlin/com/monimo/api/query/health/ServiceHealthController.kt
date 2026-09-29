package com.monimo.api.query.health

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.health.dto.ServiceHealthResponse
import com.monimo.api.query.support.Rollup
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// 탐지가 경보 규칙 재료를 읽는 내부 문 (API 명세 #2 · FN-27 · FN-29). X-Internal-Token 은 SecurityConfig 가 검사한다
@RestController
@RequestMapping("/api/v1/internal/service-health")
class ServiceHealthController(
    private val serviceHealthService: ServiceHealthService,
) {

    @GetMapping
    fun list(
        @RequestParam("service_name", required = false) serviceName: String?,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
        @RequestParam(required = false) step: Int?,
    ): ApiResponse<List<ServiceHealthResponse>> =
        ApiResponse.of(serviceHealthService.list(serviceName, TimeRange.of(from, to), step ?: Rollup.DEFAULT_STEP))
}
