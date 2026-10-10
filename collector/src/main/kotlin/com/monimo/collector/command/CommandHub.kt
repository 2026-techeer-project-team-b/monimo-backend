package com.monimo.collector.command

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.context.request.async.DeferredResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// 에이전트 하나를 가리키는 열쇠. 적재 처리기가 agent_id 로 쓰는 값과 같다 (service.name + service.instance.id)
data class AgentKey(val service: String, val instance: String)

// GET /agent/commands 의 200 본문. Extension 이 이 모양을 읽는다 (monimo-shop agent-extension Command.java)
data class AgentCommand(
    @get:JsonProperty("command_id") val commandId: String,
    @get:JsonProperty("type") val type: String,
    @get:JsonProperty("reply_to") val replyTo: String,
    @get:JsonProperty("timeout_ms") val timeoutMs: Long,
) {
    companion object {
        const val THREAD_DUMP = "THREAD_DUMP"
    }
}

// 스레드 덤프 명령의 보관소. 세 가지를 쥔다.
//   waiting  = 지금 붙잡고 있는 에이전트 폴링 (보유 판정의 근거, 합의안 1 : "최근 N초" 가 아니라 "지금 쥐고 있음")
//   pending  = 폴링 사이 빈틈에 온 명령. 다음 폴링에 바로 준다 (합의안 3)
//   inflight = 결과를 기다리는 명령. command_id → API 서버(또는 curl)가 기다리는 응답
//
// 요청 스레드를 잡지 않으려고 전부 DeferredResult 로 기다린다. 컨테이너 스레드는 풀리고, 다른 스레드가 setResult 하면 응답이 나간다.
// DeferredResult 의 타임아웃 기본 응답은 503 이라, "내 에이전트 아님(503)" 과 겹치지 않게 결과를 늘 명시한다 (폴링 204, 덤프 504)
//
// poll · dispatch · ended 는 synchronized 다. 셋이 맵 넷을 함께 보고 고치는데, 사이에 끼면 명령이 묻히거나(빈틈 보관 직후 새 폴링이 붙잡힘)
// 방금 끝난 폴링을 "없음" 으로 보는 경합이 리뷰에서 나왔다. 사람이 버튼을 누를 때만 오는 일이라 잠금 비용은 문제가 안 된다
@Component
@EnableConfigurationProperties(AgentCommandProperties::class)
class CommandHub(
    private val props: AgentCommandProperties,
) {
    // 테스트가 시간을 돌릴 수 있게 바꿔 끼울 수 있다
    internal var clock: Clock = Clock.systemUTC()

    private data class Pending(val command: AgentCommand, val expiresAt: Instant)

    private val waiting = ConcurrentHashMap<AgentKey, DeferredResult<ResponseEntity<Any>>>()
    private val lastPollEnded = ConcurrentHashMap<AgentKey, Instant>()
    private val pending = ConcurrentHashMap<AgentKey, Pending>()
    private val inflight = ConcurrentHashMap<String, DeferredResult<ResponseEntity<Any>>>()

    // 에이전트가 "명령 있어?" 를 물었다. 빈틈에 온 명령이 있으면 바로 주고, 없으면 poll-timeout 동안 붙잡는다
    @Synchronized
    fun poll(key: AgentKey): DeferredResult<ResponseEntity<Any>> {
        val polling = DeferredResult<ResponseEntity<Any>>(props.pollTimeout.toMillis(), NO_COMMAND)
        pending.remove(key)?.takeIf { it.expiresAt.isAfter(clock.instant()) }?.let {
            polling.setResult(ResponseEntity.ok(it.command))
            lastPollEnded[key] = clock.instant()
            return polling
        }
        // 같은 에이전트가 다시 물었다면 앞의 것은 409 로 끝낸다. 204 로 끝내면 같은 이름표로 뜬 JVM 둘(compose --scale 같은 잘못된 설정)이
        // 백오프 없이 서로를 끊는 고속 루프가 된다 (리뷰). Extension 은 409 를 받으면 백오프한다. 보유는 늘 하나
        waiting.put(key, polling)?.setResult(DISPLACED)
        polling.onCompletion { ended(key, polling) }
        prune()
        return polling
    }

    // 붙잡은 폴링이 응답으로 끝났다 (204 · 명령 전달 · 끊김). 빈틈 판정을 위해 끝난 시각을 남긴다
    @Synchronized
    internal fun ended(key: AgentKey, polling: DeferredResult<ResponseEntity<Any>>) {
        waiting.remove(key, polling)
        lastPollEnded[key] = clock.instant()
    }

    // 빈틈 판정이 끝난 지 오래된 에이전트(파드가 바뀌어 사라진 이름표 등)를 지운다. 폴링이 올 때마다 가볍게
    private fun prune() {
        val cutoff = clock.instant().minus(STALE_AFTER)
        lastPollEnded.entries.removeIf { (key, at) -> at.isBefore(cutoff) && !waiting.containsKey(key) }
        pending.entries.removeIf { (_, p) -> p.expiresAt.isBefore(clock.instant()) }
    }

    // 덤프 명령을 내린다. 이 수집기가 그 에이전트를 쥐고 있지 않으면 null (팬아웃 받은 쪽이 503 을 낸다)
    @Synchronized
    fun dispatch(key: AgentKey, timeout: Duration): DeferredResult<ResponseEntity<Any>>? {
        val command = AgentCommand(UUID.randomUUID().toString(), AgentCommand.THREAD_DUMP, props.advertisedUrl, timeout.toMillis())
        val result = DeferredResult<ResponseEntity<Any>>(timeout.toMillis(), DUMP_TIMEOUT)
        inflight[command.commandId] = result
        result.onCompletion { inflight.remove(command.commandId, result) }

        // 1) 지금 붙잡고 있는 폴링에 명령을 답으로 준다
        val polling = waiting.remove(key)
        if (polling != null && polling.setResult(ResponseEntity.ok(command))) {
            lastPollEnded[key] = clock.instant()
            return result
        }
        // 2) 방금 폴링이 끝나 다음 폴링을 기다리는 빈틈이면 보관한다.
        //    폴링을 꺼냈는데 setResult 가 실패했다면 그 폴링은 막 타임아웃으로 끝난 것이라(onCompletion 이 아직 안 돈 몇 ms) 이것도 빈틈이다.
        //    빈틈에 이미 보관한 명령이 있으면 덮어쓰지 않는다 (앞 명령이 504 로 묻히지 않게). 뒤 명령은 다음 폴링 뒤 다시 시도된다
        val endedAt = if (polling != null) clock.instant() else lastPollEnded[key]
        if (endedAt != null && Duration.between(endedAt, clock.instant()) <= props.gapGrace && !pending.containsKey(key)) {
            pending[key] = Pending(command, clock.instant().plus(timeout))
            return result
        }
        // 3) 이 수집기에는 없다
        inflight.remove(command.commandId)
        return null
    }

    // Extension 이 덤프 결과를 보냈다. 기다리는 명령이면 그 응답으로 넘긴다. 모르는 명령(시간 초과 · 다른 수집기)이면 false
    fun complete(commandId: String, resultJson: String): Boolean {
        val result = inflight.remove(commandId) ?: return false
        return result.setResult(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(resultJson))
    }

    // 지금 붙잡고 있는 폴링 수 (테스트가 "보유는 늘 하나" 를 확인할 때 쓴다)
    internal fun holdingCount(): Int = waiting.size

    companion object {
        val NO_COMMAND: ResponseEntity<Any> = ResponseEntity.noContent().build()
        // 같은 에이전트의 새 폴링에 밀려난 옛 폴링. Extension 은 이걸 받으면 바로 다시 묻지 않고 백오프한다
        val DISPLACED: ResponseEntity<Any> = ResponseEntity.status(HttpStatus.CONFLICT).build()
        private val STALE_AFTER: Duration = Duration.ofMinutes(10)
        val DUMP_TIMEOUT: ResponseEntity<Any> =
            ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(mapOf("code" to "THREAD_DUMP_TIMEOUT", "message" to "에이전트가 시간 안에 덤프를 보내지 않았습니다."))
    }
}
