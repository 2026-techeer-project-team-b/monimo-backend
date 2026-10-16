package com.monimo.api.query.stats

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.PageLimit
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.stats.dto.UrlStatResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// URL 통계 탭 · 서버맵 상위 URL (API 명세 #12 · FN-45, VIEWER+)
@RestController
@RequestMapping("/api/v1/stats/urls")
class UrlStatController(
    private val urlStatService: UrlStatService,
) {

    @GetMapping
    fun list(
        @RequestParam("service_name") serviceName: String,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
        @RequestParam("agent_key", required = false) agentKey: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<List<UrlStatResponse>> =
        urlStatService.list(serviceName, agentKey, TimeRange.of(from, to), cursor, PageLimit.of(limit))
}
