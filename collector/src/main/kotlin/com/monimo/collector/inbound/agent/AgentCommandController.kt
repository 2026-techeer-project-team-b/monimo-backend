package com.monimo.collector.inbound.agent

import com.fasterxml.jackson.annotation.JsonProperty
import com.monimo.collector.command.AgentCommandProperties
import com.monimo.collector.command.AgentKey
import com.monimo.collector.command.CommandHub
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.async.DeferredResult
import java.security.MessageDigest
import java.time.Duration

// 스레드 덤프 명령 문 셋 (#122, monimo-shop #34 research ⑧).
//   /agent/**    : 쇼핑몰 Extension 이 부른다. X-Monimo-Agent-Token
//   /internal/** : API 서버 팬아웃이 부른다(ADR #36, 재범). 지금은 curl 로 시험한다. X-Internal-Token
@RestController
class AgentCommandController(
    private val hub: CommandHub,
    private val props: AgentCommandProperties,
) {

    // Extension 의 "명령 있어?". 최대 poll-timeout(25초) 붙잡고 204, 명령이 오면 200 {command_id, type, reply_to, timeout_ms}
    @GetMapping("/agent/commands")
    fun poll(
        @RequestHeader(AGENT_TOKEN_HEADER, required = false) token: String?,
        @RequestParam service: String,
        @RequestParam instance: String,
    ): DeferredResult<ResponseEntity<Any>> {
        if (!matches(props.token, token)) return DeferredResult<ResponseEntity<Any>>().apply { setResult(UNAUTHORIZED) }
        return hub.poll(AgentKey(service, instance))
    }

    // Extension 이 덤프 결과를 보낸다. 본문은 그대로 기다리던 쪽에 넘긴다 (모양은 Extension 이 정한다 : service · instance · taken_at · dump …)
    @PostMapping("/agent/commands/{commandId}/result")
    fun result(
        @RequestHeader(AGENT_TOKEN_HEADER, required = false) token: String?,
        @PathVariable commandId: String,
        @RequestBody body: String,
    ): ResponseEntity<Any> {
        if (!matches(props.token, token)) return UNAUTHORIZED
        return if (hub.complete(commandId, body)) ResponseEntity.ok().build() else ResponseEntity.notFound().build()
    }

    data class ThreadDumpRequest(
        val service: String,
        val instance: String,
        @param:JsonProperty("timeout_ms") val timeoutMs: Long? = null,
    )

    // API 서버 팬아웃이 수집기마다 부른다. 이 수집기가 그 에이전트를 쥐고 있으면 덤프를 받아 동기로 돌려주고(200),
    // 아니면 503 AGENT_NOT_REACHABLE (API 서버는 200 을 준 한 대의 결과를 쓴다, 합의안 1). 에이전트가 늦으면 504
    @PostMapping("/internal/thread-dump")
    fun threadDump(
        @RequestHeader(INTERNAL_TOKEN_HEADER, required = false) token: String?,
        @RequestBody req: ThreadDumpRequest,
    ): DeferredResult<ResponseEntity<Any>> {
        if (!matches(props.internalToken, token)) return DeferredResult<ResponseEntity<Any>>().apply { setResult(UNAUTHORIZED) }
        val timeout = req.timeoutMs?.let { Duration.ofMillis(it) }?.coerceIn(Duration.ofSeconds(1), props.maxDumpTimeout) ?: props.defaultDumpTimeout
        return hub.dispatch(AgentKey(req.service, req.instance), timeout)
            ?: DeferredResult<ResponseEntity<Any>>().apply { setResult(NOT_REACHABLE) }
    }

    companion object {
        const val AGENT_TOKEN_HEADER = "X-Monimo-Agent-Token"
        const val INTERNAL_TOKEN_HEADER = "X-Internal-Token"

        private val UNAUTHORIZED: ResponseEntity<Any> =
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("code" to "UNAUTHORIZED", "message" to "토큰이 없거나 틀렸습니다."))
        private val NOT_REACHABLE: ResponseEntity<Any> =
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("code" to "AGENT_NOT_REACHABLE", "message" to "이 수집기에 연결된 에이전트가 아닙니다."))

        // 설정이 비어 있으면 어떤 값도 통과시키지 않는다 (문이 닫힌다). 길이가 달라도 시간이 같도록 상수 시간 비교 (API 서버 InternalTokenFilter 와 같은 규칙)
        internal fun matches(expected: String, presented: String?): Boolean =
            expected.isNotBlank() && presented != null && MessageDigest.isEqual(expected.toByteArray(), presented.toByteArray())
    }
}
