package com.monimo.api.query.canary

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.query.canary.dto.CanaryFreshnessResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// 파수꾼이 파이프라인이 살아 있는지 묻는 내부 문 (API 명세 #4 · FN-55). X-Internal-Token 은 SecurityConfig 가 검사한다
@RestController
@RequestMapping("/api/v1/internal/canary/freshness")
class CanaryController(
    private val canaryService: CanaryService,
) {

    @GetMapping
    fun freshness(
        @RequestParam("service_name", required = false) serviceName: String?,
    ): ApiResponse<CanaryFreshnessResponse> = ApiResponse.of(canaryService.freshness(serviceName))
}
