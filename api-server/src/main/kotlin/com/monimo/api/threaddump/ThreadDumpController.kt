package com.monimo.api.threaddump

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.PageLimit
import com.monimo.api.common.web.TimeRange
import com.monimo.api.threaddump.dto.ThreadDumpRequest
import com.monimo.api.threaddump.dto.ThreadDumpResponse
import com.monimo.api.threaddump.dto.ThreadDumpSummary
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

// 스레드 덤프 3개 문 (API 명세 15 · 16 · 17 : 요청 ADMIN, 목록 · 상세 VIEWER+)
@RestController
@RequestMapping("/api/v1")
class ThreadDumpController(
    private val threadDumpService: ThreadDumpService,
) {

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/agents/{agentUuid}/thread-dumps")
    fun request(
        @PathVariable agentUuid: UUID,
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody(required = false) body: ThreadDumpRequest?,
    ): ApiResponse<ThreadDumpResponse> =
        ApiResponse.of(threadDumpService.request(agentUuid, jwt.getClaimAsString("email"), body?.timeoutMs))

    @GetMapping("/thread-dumps")
    fun list(
        @RequestParam("service_name", required = false) serviceName: String?,
        @RequestParam("agent_key", required = false) agentKey: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<List<ThreadDumpSummary>> {
        // 시간 범위는 선택이지만 둘 중 하나만 오면 뜻이 없다
        val range = when {
            from == null && to == null -> null
            from != null && to != null -> TimeRange.of(from, to)
            else -> throw ApiException(ErrorCode.INVALID_REQUEST, "from 과 to 는 같이 보내야 합니다.")
        }
        return threadDumpService.list(serviceName, agentKey, range, cursor, PageLimit.of(limit))
    }

    @GetMapping("/thread-dumps/{dumpUuid}")
    fun get(@PathVariable dumpUuid: UUID): ApiResponse<ThreadDumpResponse> =
        ApiResponse.of(threadDumpService.get(dumpUuid))
}
