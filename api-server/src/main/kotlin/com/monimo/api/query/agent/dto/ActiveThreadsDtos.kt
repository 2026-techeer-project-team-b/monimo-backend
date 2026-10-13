package com.monimo.api.query.agent.dto

import java.time.Instant
import java.util.UUID

// 파드의 가장 최근 스레드 수 (API 명세 #21). ts_min 은 그 값이 찍힌 1분
data class ActiveThreadsResponse(
    val agentUuid: UUID,
    val agentKey: String,
    val serviceName: String,
    val metricName: String,
    val tsMin: Instant,
    val lastV: Long,
)
