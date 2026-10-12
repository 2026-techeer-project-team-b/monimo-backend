package com.monimo.api.threaddump

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.threaddump.dto.AutoThreadDumpRequest
import com.monimo.api.threaddump.dto.AutoThreadDumpResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// 탐지가 CPU · HEAP · GC 경보 때 부르는 자동 스레드 덤프 내부 문 (#164). X-Internal-Token 은 SecurityConfig 가 검사한다.
// 쿨다운 안이면 에러가 아니라 200 + skipped — 탐지가 덤프 결과를 경보 실패로 보지 않게
@RestController
@RequestMapping("/api/v1/internal/thread-dumps")
class ThreadDumpInternalController(
    private val threadDumpService: ThreadDumpService,
) {

    @PostMapping
    fun autoRequest(@RequestBody body: AutoThreadDumpRequest): ApiResponse<AutoThreadDumpResponse> =
        ApiResponse.of(threadDumpService.autoRequest(body.agentUuid, body.requestedBy))
}
