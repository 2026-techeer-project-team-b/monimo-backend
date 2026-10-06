package com.monimo.api.query.agent.dto

import java.time.Instant

// 구간 안에 데이터를 보낸 파드 한 대. agent_key 는 CH agent_id 그대로라 PG agents.agent_key 와 같은 글자다
data class ActiveAgentResponse(
    val serviceName: String,
    val agentKey: String,
    val lastSignalAt: Instant,
    val source: String, // spans · metrics_raw 중 마지막 데이터가 온 표
)
