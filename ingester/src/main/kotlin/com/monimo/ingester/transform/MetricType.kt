package com.monimo.ingester.transform

// OTel 메트릭의 다섯 종류 (protobuf oneof `data` 의 갈래). column 이 CH metrics_raw.metric_type 에 들어가는 글자다.
// 히스토그램을 편 네 줄(.count · .sum · .min · .max)은 전부 HISTOGRAM 이다 : "이 줄이 분포에서 나왔다" 를 남긴다
enum class MetricType(val column: String) {
    GAUGE("gauge"),
    SUM("sum"),
    HISTOGRAM("histogram"),
    EXPONENTIAL_HISTOGRAM("exponential_histogram"),
    SUMMARY("summary"),
}
