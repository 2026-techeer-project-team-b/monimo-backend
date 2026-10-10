package com.monimo.api.query.transaction

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.PageLimit
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.transaction.dto.ScatterResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// 응답시간 스캐터 (API 명세 #5 · FN-41 · FN-42, VIEWER+). limit 은 쪽 크기가 아니라 점 개수 상한이다
@RestController
@RequestMapping("/api/v1/traces/scatter")
class ScatterController(
    private val scatterService: ScatterService,
) {

    @GetMapping
    fun get(
        @RequestParam("service_name") serviceName: String,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
        @RequestParam("agent_key", required = false) agentKey: String?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<ScatterResponse> =
        ApiResponse.of(
            scatterService.get(
                serviceName,
                agentKey,
                TimeRange.of(from, to),
                PageLimit.of(limit, default = ScatterService.DEFAULT_LIMIT, max = ScatterService.MAX_LIMIT),
            ),
        )
}
