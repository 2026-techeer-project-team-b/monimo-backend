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

// 탐지가 경보 때 부르는 자동 덤프 요청 (#164). requestedBy 는 없으면 "detector"
data class AutoThreadDumpRequest(
    val agentUuid: UUID,
    val requestedBy: String? = null,
)

// 자동 덤프 응답. skipped 면 dumpUuid · threadCount 는 null 이고 lastDumpAt 은 쿨다운을 건 직전 덤프 시각(지금 뜨는 중이면 null)
data class AutoThreadDumpResponse(
    val skipped: Boolean,
    val agentKey: String,
    val serviceName: String,
    val dumpUuid: UUID?,
    val threadCount: Int?,
    val lastDumpAt: String?,
)

// 목록 커서. requested_at(ms) 내림차순 · dump_uuid 내림차순 키셋
data class ThreadDumpCursor(
    val ts: Long,
    val id: String,
)
