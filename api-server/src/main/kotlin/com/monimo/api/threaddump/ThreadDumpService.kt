package com.monimo.api.threaddump

import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.CursorCodec
import com.monimo.api.common.web.TimeRange
import com.monimo.api.config.agent.AgentRepository
import com.monimo.api.config.agent.AgentRow
import com.monimo.api.query.support.NanoTime
import com.monimo.api.threaddump.dto.AutoThreadDumpResponse
import com.monimo.api.threaddump.dto.ThreadDumpCursor
import com.monimo.api.threaddump.dto.ThreadDumpResponse
import com.monimo.api.threaddump.dto.ThreadDumpSummary
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// 스레드 덤프 요청 · 목록 · 상세 (API 명세 15 · 16 · 17). 요청은 수집기 팬아웃 → Extension → CH 저장까지 동기로 끝낸다
@Service
class ThreadDumpService(
    private val agents: AgentRepository,
    private val collectors: CollectorClient,
    private val dumps: ThreadDumpRepository,
    private val objectMapper: ObjectMapper,
) {

    // 지금 뜨는 중인 파드. 같은 인스턴스에서 CPU · HEAP · GC 경보가 한꺼번에 들어와도 덤프는 한 번만 뜬다
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // DB 트랜잭션을 잡지 않는다. 팬아웃(최대 30초) 동안 커넥션을 붙잡을 이유가 없다
    fun request(agentUuid: UUID, requestedBy: String, timeoutMs: Long?): ThreadDumpResponse {
        val agent = findAgent(agentUuid)
        val timeout = Duration.ofMillis((timeoutMs ?: DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS))
        val row = dump(agent, requestedBy, timeout)
        return response(row, agent.agentUuid)
    }

    // 탐지가 경보 때 부르는 자동 덤프 (#164). 파드당 쿨다운 안에 한 번만 — 메모리 집합이 같은 인스턴스의 동시 호출을,
    // CH 의 최근 덤프가 인스턴스가 여럿이거나 재시작된 뒤를 막는다. 사람이 방금 뜬 덤프도 센다 (덤프가 쌓이는 것을 막는 게 목적)
    fun autoRequest(agentUuid: UUID, requestedBy: String?): AutoThreadDumpResponse {
        val agent = findAgent(agentUuid)
        if (!inFlight.add(agent.agentKey)) return skipped(agent, lastDumpAtMs = null)
        try {
            val since = Instant.now().minus(AUTO_COOLDOWN).toEpochMilli()
            dumps.findLatestRequestedAtMs(agent.agentKey, since)?.let { return skipped(agent, it) }
            val row = dump(agent, requestedBy?.takeIf { it.isNotBlank() } ?: AUTO_REQUESTED_BY, Duration.ofMillis(DEFAULT_TIMEOUT_MS))
            return AutoThreadDumpResponse(
                skipped = false, agentKey = row.agentKey, serviceName = row.serviceName,
                dumpUuid = row.dumpUuid, threadCount = row.threadCount, lastDumpAt = NanoTime.formatMillis(row.requestedAtMs),
            )
        } finally {
            inFlight.remove(agent.agentKey)
        }
    }

    private fun skipped(agent: AgentRow, lastDumpAtMs: Long?) = AutoThreadDumpResponse(
        skipped = true, agentKey = agent.agentKey, serviceName = agent.serviceName,
        dumpUuid = null, threadCount = null, lastDumpAt = lastDumpAtMs?.let(NanoTime::formatMillis),
    )

    private fun findAgent(agentUuid: UUID): AgentRow =
        agents.findOne(agentUuid) ?: throw ApiException(ErrorCode.NOT_FOUND, "파드를 찾을 수 없습니다.")

    private fun dump(agent: AgentRow, requestedBy: String, timeout: Duration): ThreadDumpRow {
        val body = dumped(agent.serviceName, agent.agentKey, timeout)
        val result = objectMapper.readTree(body)
        val row = ThreadDumpRow(
            dumpUuid = UUID.randomUUID(),
            agentKey = agent.agentKey,
            serviceName = agent.serviceName,
            requestedBy = requestedBy,
            requestedAtMs = Instant.now().truncatedTo(ChronoUnit.MILLIS).toEpochMilli(),
            threadCount = result.path("thread_count").asInt(),
            dump = result.path("dump").asText(),
        )
        dumps.insert(row)
        return row
    }

    // 200 을 준 한 대의 본문. 전부 "내 에이전트 아님" 이면 폴링이 옮겨 가는 빈틈일 수 있어 한 번 더 묻는다 (합의안 1)
    private fun dumped(service: String, instance: String, timeout: Duration): String {
        var outcomes = collectors.threadDump(service, instance, timeout)
        if (outcomes.none { it is DumpOutcome.Dumped || it is DumpOutcome.Timeout }) {
            outcomes = collectors.threadDump(service, instance, timeout)
        }
        outcomes.firstOrNull { it is DumpOutcome.Dumped }?.let { return (it as DumpOutcome.Dumped).body }
        // 504 는 에이전트를 쥔 수집기가 있었다는 뜻이라 "연결 없음" 과 다른 코드로 낸다
        if (outcomes.any { it is DumpOutcome.Timeout }) throw ApiException(ErrorCode.THREAD_DUMP_TIMEOUT)
        throw ApiException(ErrorCode.AGENT_NOT_REACHABLE)
    }

    fun list(serviceName: String?, agentKey: String?, range: TimeRange?, cursor: String?, limit: Int): ApiResponse<List<ThreadDumpSummary>> {
        val after = cursor?.let { CursorCodec.decode<ThreadDumpCursor>(it) }
        val rows = dumps.findPage(serviceName, agentKey, range, after, limit + 1)
        // 커서는 ms 시각이 필요해 줄(row)로 만들고, 화면에는 요약으로 바꿔 준다
        val page = CursorCodec.page(rows, limit) { ThreadDumpCursor(it.requestedAtMs, it.dumpUuid.toString()) }
        return ApiResponse(page.data.map(::summary), page.page)
    }

    // 93일 만료와 처음부터 없음은 uuid 만으로 구분할 수 없어 NOT_FOUND 하나로 낸다 (트레이스 상세 #76 과 같은 결정)
    fun get(dumpUuid: UUID): ThreadDumpResponse {
        val row = dumps.findOne(dumpUuid)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "스레드 덤프를 찾을 수 없습니다.")
        return response(row, agents.findUuidByAgentKey(row.agentKey))
    }

    private fun response(row: ThreadDumpRow, agentUuid: UUID?) = ThreadDumpResponse(
        row.dumpUuid, agentUuid, row.agentKey, row.serviceName, row.requestedBy,
        NanoTime.formatMillis(row.requestedAtMs), row.threadCount, row.dump.orEmpty(),
    )

    private fun summary(row: ThreadDumpRow) = ThreadDumpSummary(
        row.dumpUuid, row.agentKey, row.serviceName, row.requestedBy, NanoTime.formatMillis(row.requestedAtMs), row.threadCount,
    )

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val MIN_TIMEOUT_MS = 1_000L
        // 수집기 max-dump-timeout(30초)과 같다. 더 길게 보내도 수집기가 30초에서 자른다
        const val MAX_TIMEOUT_MS = 30_000L
        const val AUTO_REQUESTED_BY = "detector"
        // 경보는 사건마다 한 번 뜨지만 CPU · HEAP · GC 가 같은 파드에서 같이 뜨면 덤프 셋이 연달아 간다. 그것을 하나로
        val AUTO_COOLDOWN: Duration = Duration.ofMinutes(5)
    }
}
