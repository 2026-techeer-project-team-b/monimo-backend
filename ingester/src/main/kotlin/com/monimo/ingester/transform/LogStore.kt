package com.monimo.ingester.transform

// 로그를 저장하는 곳. 무엇으로 저장하는지는 모른다 (SpanStore 와 같은 역할의 포트)
fun interface LogStore {
    fun save(rows: List<LogRow>)
}
