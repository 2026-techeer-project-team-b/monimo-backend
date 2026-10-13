package com.monimo.api.query.transaction.dto

import java.time.Instant

// 응답시간 히트맵 (API 명세 #9). 요청이 0건인 칸은 cells 에 없다
data class HeatmapResponse(
    val bucketWidthMs: Int,
    val step: Int,
    val cells: List<HeatmapCell>,
)

// 시간 칸 × 응답시간 구간 × 성공/실패 의 요청 수. 응답시간 구간 = latency_bucket × bucket_width_ms 부터 한 칸
data class HeatmapCell(
    val tsMin: Instant,
    val latencyBucket: Int,
    val isError: Boolean,
    val cnt: Long,
)
