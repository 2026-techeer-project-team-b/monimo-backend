package com.monimo.api.query.canary.dto

import java.time.Instant

// 카나리 신선도 (API 명세 #4). 판정(fresh)은 파수꾼이 아니라 여기서 낸다
// 구간 안에 카나리가 한 건도 없으면 last_signal_at · age_sec 은 null 이고 fresh 는 false 다
data class CanaryFreshnessResponse(
    val serviceName: String?,
    val lastSignalAt: Instant?,
    val ageSec: Long?,
    val thresholdSec: Long,
    val fresh: Boolean,
)
