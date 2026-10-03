package com.monimo.api.query.trace

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.query.trace.dto.TraceResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// 트레이스 상세 (API 명세 #14 · FN-19 · FN-20, VIEWER+)
@RestController
@RequestMapping("/api/v1/traces")
class TraceController(
    private val traceService: TraceService,
) {

    @GetMapping("/{traceId}")
    fun get(@PathVariable traceId: String): ApiResponse<TraceResponse> =
        ApiResponse.of(traceService.get(traceId))
}
