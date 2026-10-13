package com.monimo.api.query.agent

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.query.agent.dto.ActiveThreadsResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 파드의 현재 스레드 수 (API 명세 #21 · FN-38, VIEWER+). 파드 목록 · 상세(/api/v1/agents)는 인증 설정 파트 AgentController 가 맡는다
@RestController
class ActiveThreadsController(
    private val activeThreadsService: ActiveThreadsService,
) {

    @GetMapping("/api/v1/agents/{agentUuid}/active-threads")
    fun get(@PathVariable agentUuid: UUID): ApiResponse<ActiveThreadsResponse> =
        ApiResponse.of(activeThreadsService.get(agentUuid))
}
