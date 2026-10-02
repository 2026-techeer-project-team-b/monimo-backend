package com.monimo.ingester.transform

// 처음 보는 파드를 명단에 올리는 곳. 무엇으로 올리는지는 모른다 (SpanStore · MetricStore 와 같은 역할의 포트).
//
// agents 표의 주인은 적재 처리기다 (ADR #39: 등록은 적재 처리기, 생존 상태 갱신은 탐지).
// "명단에 올린다" 는 규칙을 도메인이 들고, "PostgreSQL 로 올린다" 는 방법은 outbound/postgres 가 따른다.
fun interface AgentRegistry {

    // 이미 있는 파드는 조용히 넘어간다. 여러 번 불러도 결과가 같다(멱등).
    // 메시지 한 개에서 나온 목격을 한꺼번에 넘긴다
    fun register(sightings: Collection<AgentSighting>)
}
