package com.monimo.api.query.metric

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.query.metric.dto.MetricSeriesResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// 인스펙터 지표 그래프 (API 명세 #15 · FN-33 · FN-37, VIEWER+). step 을 안 주면 기간에 맞춰 서버가 고른다
@RestController
@RequestMapping("/api/v1/metrics")
class MetricController(
    private val metricService: MetricService,
) {

    @GetMapping("/series")
    fun series(
        @RequestParam("service_name") serviceName: String,
        @RequestParam("metric_name") metricName: String,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
        @RequestParam("agent_key", required = false) agentKey: String?,
        @RequestParam(required = false) step: Int?,
    ): ApiResponse<MetricSeriesResponse> =
        ApiResponse.of(metricService.series(serviceName, metricName, agentKey, from, to, step))
}
