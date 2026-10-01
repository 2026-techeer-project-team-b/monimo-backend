package com.monimo.ingester.transform

// 메트릭을 저장하는 곳. 무엇으로 저장하는지는 모른다 (SpanStore 와 같은 역할의 포트).
// 규칙("저장한다")은 도메인이 들고, 방법("ClickHouse 로")은 outbound/clickhouse 가 따른다
fun interface MetricStore {
    fun save(rows: List<MetricRow>)
}
