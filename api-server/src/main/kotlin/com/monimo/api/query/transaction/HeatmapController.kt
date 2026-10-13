package com.monimo.api.query.transaction

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.support.Rollup
import com.monimo.api.query.transaction.dto.HeatmapResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// 응답시간 히트맵 (API 명세 #9 · FN-44, VIEWER+). 시간 칸 × 50ms 구간의 요청 수
@RestController
@RequestMapping("/api/v1/traces/heatmap")
class HeatmapController(
    private val heatmapService: HeatmapService,
) {

    @GetMapping
    fun get(
        @RequestParam("service_name") serviceName: String,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
        @RequestParam(required = false) step: Int?,
    ): ApiResponse<HeatmapResponse> =
        ApiResponse.of(heatmapService.get(serviceName, TimeRange.of(from, to), step ?: Rollup.DEFAULT_STEP))
}
