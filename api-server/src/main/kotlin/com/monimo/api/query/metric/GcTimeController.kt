package com.monimo.api.query.metric

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.metric.dto.GcTimeResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// 탐지가 GC_TIME 을 판정할 재료를 읽는 내부 문 (API 명세 #51). X-Internal-Token 은 SecurityConfig 가 검사한다
// 응답에 없는 1분 = 그 파드가 GC 값을 안 보냈거나 재시작으로 뺄 수 없던 1분
@RestController
@RequestMapping("/api/v1/internal/metrics/gc-time")
class GcTimeController(
    private val gcTimeService: GcTimeService,
) {

    @GetMapping
    fun list(
        @RequestParam("service_name", required = false) serviceName: String?,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
    ): ApiResponse<List<GcTimeResponse>> =
        ApiResponse.of(gcTimeService.list(serviceName, TimeRange.of(from, to)))
}
