package com.monimo.api.query.agent

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.agent.dto.ActiveAgentResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// 탐지가 AGENT_DOWN 을 판정할 재료를 읽는 내부 문 (API 명세 #40 · FN-29). X-Internal-Token 은 SecurityConfig 가 검사한다
// 응답에 없는 파드 = 구간 안에 데이터를 하나도 보내지 않은 파드
@RestController
@RequestMapping("/api/v1/internal/agents/active")
class ActiveAgentController(
    private val activeAgentRepository: ActiveAgentRepository,
) {

    @GetMapping
    fun list(
        @RequestParam("service_name", required = false) serviceName: String?,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
    ): ApiResponse<List<ActiveAgentResponse>> =
        ApiResponse.of(activeAgentRepository.find(serviceName, TimeRange.of(from, to)))
}
