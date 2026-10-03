package com.monimo.api.config.agent

import java.time.Instant
import java.util.UUID

// 파드 한 줄 (API 명세 12 · 13 · 14번). hostname · ip · jvm_version · agent_version 은
// 에이전트가 안 보냈으면 null 로 나간다
data class AgentResponse(
    val agentUuid: UUID,
    val applicationUuid: UUID,
    val serviceName: String,
    val agentKey: String,
    val hostname: String?,
    val ip: String?,
    val jvmVersion: String?,
    val agentVersion: String?,
    val status: String,
    val firstSeenAt: Instant?,
    val updatedAt: Instant,
) {
    companion object {
        fun from(row: AgentRow) = AgentResponse(
            row.agentUuid, row.applicationUuid, row.serviceName, row.agentKey,
            row.hostname, row.ip, row.jvmVersion, row.agentVersion,
            row.status, row.firstSeenAt, row.updatedAt,
        )
    }
}
