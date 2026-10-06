package com.monimo.api.query.error

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.PageLimit
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.error.dto.ErrorSearch
import com.monimo.api.query.error.dto.ErrorSpanResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// 에러 분석 (API 명세 #3 · FN-23, VIEWER+). 실패한 스팬을 시간 역순으로
@RestController
@RequestMapping("/api/v1/errors")
class ErrorController(
    private val errorService: ErrorService,
) {

    @GetMapping
    fun list(
        @RequestParam("service_name") serviceName: String,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
        @RequestParam("agent_key", required = false) agentKey: String?,
        @RequestParam("http_status", required = false) httpStatus: Int?,
        @RequestParam("exception_type", required = false) exceptionType: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<List<ErrorSpanResponse>> =
        errorService.list(
            ErrorSearch(serviceName, agentKey, httpStatus, exceptionType),
            TimeRange.of(from, to),
            cursor,
            PageLimit.of(limit),
        )
}
