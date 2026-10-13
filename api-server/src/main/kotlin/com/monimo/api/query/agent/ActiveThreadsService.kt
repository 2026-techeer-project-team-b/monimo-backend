package com.monimo.api.query.agent

import com.monimo.api.common.error.ApiException
import com.monimo.api.common.error.ErrorCode
import com.monimo.api.config.agent.AgentRepository
import com.monimo.api.query.agent.dto.ActiveThreadsResponse
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

// 주소의 agent_uuid 를 PG agents 에서 agent_key 로 바꾼 뒤(ClickHouse 에는 agent_key 만 있다) 스레드 수를 읽는다
@Service
class ActiveThreadsService(
    private val agents: AgentRepository,
    private val activeThreadsRepository: ActiveThreadsRepository,
) {

    fun get(agentUuid: UUID): ActiveThreadsResponse {
        val agent = agents.findOne(agentUuid)
            ?: throw ApiException(ErrorCode.NOT_FOUND, "파드를 찾을 수 없습니다.")
        val latest = activeThreadsRepository.findLatest(agent.serviceName, agent.agentKey, Instant.now().minus(LOOKBACK))
            ?: throw ApiException(ErrorCode.NOT_FOUND, "최근 ${LOOKBACK.toMinutes()}분 안에 들어온 스레드 수가 없습니다.")
        return ActiveThreadsResponse(
            agentUuid = agent.agentUuid,
            agentKey = agent.agentKey,
            serviceName = agent.serviceName,
            metricName = ActiveThreadsRepository.METRIC_NAME,
            tsMin = latest.tsMin,
            lastV = latest.count,
        )
    }

    private companion object {
        // 끊긴 파드도 끊기기 전 마지막 값을 이 시간까지는 보여 준다. 응답의 ts_min 으로 언제 값인지 알 수 있다
        val LOOKBACK: Duration = Duration.ofHours(1)
    }
}
