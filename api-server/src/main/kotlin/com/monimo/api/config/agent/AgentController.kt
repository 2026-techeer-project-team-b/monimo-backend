package com.monimo.api.config.agent

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.PageLimit
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// 에이전트 2개 문 (API 명세 12 · 13번). 전부 VIEWER+
@RestController
@RequestMapping("/api/v1/agents")
class AgentController(
    private val agentService: AgentService,
) {

    @GetMapping
    fun list(
        @RequestParam("service_name", required = false) serviceName: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<List<AgentResponse>> = agentService.list(serviceName, status, cursor, PageLimit.of(limit))

    @GetMapping("/{agentUuid}")
    fun get(@PathVariable agentUuid: UUID): ApiResponse<AgentResponse> =
        ApiResponse.of(agentService.get(agentUuid))
}

// 서비스에 딸린 파드 목록 (API 명세 14번). 경로가 applications 밑이라 컨트롤러를 나눈다
@RestController
@RequestMapping("/api/v1/applications/{applicationUuid}/agents")
class ApplicationAgentController(
    private val agentService: AgentService,
) {

    @GetMapping
    fun list(
        @PathVariable applicationUuid: UUID,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): ApiResponse<List<AgentResponse>> = agentService.listOf(applicationUuid, status, cursor, PageLimit.of(limit))
}
