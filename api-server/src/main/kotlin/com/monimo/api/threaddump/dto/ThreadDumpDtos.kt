package com.monimo.api.threaddump.dto

import java.util.UUID

// 선택. 없으면 5000. 수집기 상한(30초)에 맞춰 1000~30000 으로 자른다
data class ThreadDumpRequest(
    val timeoutMs: Long? = null,
)

// 15 · 17번 응답. agentUuid 는 파드가 agents 표에서 사라졌으면 null
data class ThreadDumpResponse(
    val dumpUuid: UUID,
    val agentUuid: UUID?,
    val agentKey: String,
    val serviceName: String,
    val requestedBy: String,
    val requestedAt: String,
    val threadCount: Int,
    val dump: String,
)

// 16번 목록 한 줄. 본문(dump)은 뺀다 — 한 건이 수십 KB 다
data class ThreadDumpSummary(
    val dumpUuid: UUID,
    val agentKey: String,
    val serviceName: String,
    val requestedBy: String,
    val requestedAt: String,
    val threadCount: Int,
)

// 목록 커서. requested_at(ms) 내림차순 · dump_uuid 내림차순 키셋
data class ThreadDumpCursor(
    val ts: Long,
    val id: String,
)
