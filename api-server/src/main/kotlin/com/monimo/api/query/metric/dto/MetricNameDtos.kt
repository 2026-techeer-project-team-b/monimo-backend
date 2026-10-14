package com.monimo.api.query.metric.dto

// 구간 안에 실제로 들어온 지표 이름과 그 지표에 붙은 속성 키 (API 명세 #25)
data class MetricNameResponse(
    val metricName: String,
    val attributeKeys: List<String>,
)
