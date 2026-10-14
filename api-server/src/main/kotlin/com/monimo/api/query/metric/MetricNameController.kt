package com.monimo.api.query.metric

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.query.metric.dto.MetricNameResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// 인스펙터 지표 추가 드롭다운 (API 명세 #25 · FN-37, VIEWER+). 페이지 봉투 없는 배열
@RestController
@RequestMapping("/api/v1/metrics")
class MetricNameController(
    private val metricNameService: MetricNameService,
) {

    @GetMapping("/names")
    fun list(
        @RequestParam("service_name") serviceName: String,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
    ): ApiResponse<List<MetricNameResponse>> =
        ApiResponse.of(metricNameService.list(serviceName, from, to))
}
