package com.monimo.api.threaddump

import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.CursorCodec
import com.monimo.api.common.web.TimeRange
import com.monimo.api.config.agent.AgentRepository
import com.monimo.api.query.support.NanoTime
import com.monimo.api.threaddump.dto.ThreadDumpCursor
import com.monimo.api.threaddump.dto.ThreadDumpResponse
import com.monimo.api.threaddump.dto.ThreadDumpSummary
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// 스레드 덤프 요청 · 목록 · 상세 (API 명세 15 · 16 · 17). 요청은 수집기 팬아웃 → Extension → CH 저장까지 동기로 끝낸다
@Service
class ThreadDumpService(
    private val agents: AgentRepository,
    private val collectors: CollectorClient,
    private val dumps: ThreadDumpRepository,
    private val objectMapper: ObjectMapper,
) {

    // DB 트랜잭션을 잡지 않는다. 팬아웃(최대 30초) 동안 커넥션을 붙잡을 이유가 없다
    fun request(agentUuid: UUID, requestedBy: String, timeoutMs: Long?): ThreadDumpResponse {
        val agent = agents.findOne(agentUuid)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "파드를 찾을 수 없습니다.")
        val timeout = Duration.ofMillis((timeoutMs ?: DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS))

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
        return response(row, agent.agentUuid)
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
    }
}
