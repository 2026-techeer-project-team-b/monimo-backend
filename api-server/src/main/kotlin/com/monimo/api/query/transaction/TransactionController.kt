package com.monimo.api.query.transaction

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.PageLimit
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.transaction.dto.TransactionResponse
import com.monimo.api.query.transaction.dto.TransactionSearch
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// 스캐터에서 드래그한 영역의 요청 목록 (API 명세 #26 · FN-43, VIEWER+). 느린 요청부터
@RestController
@RequestMapping("/api/v1/traces/transactions")
class TransactionController(
    private val transactionService: TransactionService,
) {

    @GetMapping
    fun list(
        @RequestParam("service_name") serviceName: String,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
        @RequestParam("agent_key", required = false) agentKey: String?,
        @RequestParam("min_duration_ms", required = false) minDurationMs: Long?,
        @RequestParam("max_duration_ms", required = false) maxDurationMs: Long?,
        @RequestParam("is_error", required = false) isError: Boolean?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<List<TransactionResponse>> =
        transactionService.list(
            TransactionSearch(serviceName, agentKey, minDurationMs, maxDurationMs, isError),
            TimeRange.of(from, to),
            cursor,
            PageLimit.of(limit),
        )
}
