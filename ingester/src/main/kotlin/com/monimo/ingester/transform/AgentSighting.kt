package com.monimo.ingester.transform

import java.time.Instant

// "이 서비스의 이 파드를 봤다" 는 한 건의 목격 기록.
//
// 신호(스팬 · 메트릭 · 로그)마다 resource 에 서비스 이름과 파드 정보가 같이 실려 온다.
// 그걸 우리 말로 옮긴 것이 이 모델이고, 아직 PG 에 없는 파드면 agents 표에 등록하는 재료가 된다.
// 적재(SpanRow 등)와 섞지 않고 따로 둔 이유: 적재는 ClickHouse 로, 등록은 PostgreSQL 로 가는 다른 일이다.
data class AgentSighting(
    val serviceName: String, // resource 의 service.name. PG applications.name 과 같은 글자여야 번호를 찾을 수 있다
    val agentKey: String, // 파드 식별자. CH 모든 표의 agent_id 와 같은 글자 (PG 에서는 숫자 FK 와 헷갈리지 않게 key 로 부른다)
    val hostname: String?, // 파드 이름. 없으면 null → 컬럼도 NULL 허용
    val jvmVersion: String?, // 예: 17.0.9
    val agentVersion: String?, // OTel Java Agent 버전
    val seenAt: Instant, // 이 목격의 시각. first_seen_at 에 쓴다
) {
    // resource 에 파드 식별자가 하나도 없으면 등록해도 ClickHouse 와 이을 수 없다.
    // 서비스 이름이 없는 경우(unknown_service)도 PG 에서 번호를 못 찾으므로 같이 걸러 낸다
    val registrable: Boolean get() = agentKey.isNotEmpty() && serviceName.isNotEmpty()
}
