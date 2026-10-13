package com.monimo.api.query.metric.dto

import java.time.Instant

// 지표 시계열 (API 명세 #15). source_table 은 step 으로 서버가 고른 표다
data class MetricSeriesResponse(
    val metricName: String,
    val sourceTable: String,
    val step: Int,
    val series: List<MetricSeriesLine>,
)

// 같은 지표라도 파드 × 속성 조합마다 줄이 따로다 (예: jvm.memory.used 의 heap · non_heap)
data class MetricSeriesLine(
    val agentKey: String,
    val attributes: Map<String, String>,
    val points: List<MetricPoint>,
)

// step 칸 하나의 값. 원본을 그대로 읽으면(칸에 값이 하나면) 네 값이 같다
data class MetricPoint(
    val tsMin: Instant,
    val avgV: Double,
    val minV: Double,
    val maxV: Double,
    val lastV: Double,
)
