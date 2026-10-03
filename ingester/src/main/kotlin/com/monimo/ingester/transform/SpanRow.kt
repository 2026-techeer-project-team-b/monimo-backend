package com.monimo.ingester.transform

import java.time.Instant

// ClickHouse spans 표 한 줄. 파이프라인 안에서는 OTLP 객체 대신 이 모델만 들고 다닌다.
//
// 왜 따로 만드나: OTLP 는 OTel 이 정한 남의 형식이다. 그걸 그대로 안쪽까지 들고 다니면
// 에이전트 형식이 바뀔 때 적재 · 변환 · 테스트가 한꺼번에 흔들린다. 우리 말로 된 모델을 하나 두고
// 바깥 형식과 닿는 곳(SpanTranslator)만 그 변화를 받아 내게 한다.
//
// 값이 한 번 정해지면 바뀌지 않는다(전부 val). 스팬은 이미 끝난 사건의 기록이라 고칠 일이 없다.
data class SpanRow(
    val traceId: String, // 16바이트를 소문자 16진수 32글자로. CH 는 String 으로 받는다
    val spanId: String, // 8바이트 → 16글자
    val parentSpanId: String, // 루트 스팬이면 빈 글자 (CH 관례: 없음은 NULL 이 아니라 '')
    val startTime: Instant, // 나노초까지 보존한다 (CH DateTime64(9))
    val durationNs: Long, // 끝난 시각 - 시작 시각
    val serviceName: String, // resource 의 service.name. PG applications.name 과 같은 글자여야 화면이 잇는다
    val agentId: String, // 파드 식별자. resource 의 service.instance.id → k8s.pod.name → host.name 순서로 고른다
    val spanName: String, // 예: "POST /orders"
    val spanKind: String, // INTERNAL · SERVER · CLIENT · PRODUCER · CONSUMER (CH Enum8 과 같은 글자)
    val statusCode: String, // UNSET · OK · ERROR
    val httpStatus: Int, // HTTP 응답 코드. HTTP 스팬이 아니면 0
    val peerAddress: String, // 호출 대상 주소 (예: shop-order:8080). 없으면 ''
    val peerService: String, // 호출 대상 서비스 이름. 에이전트가 안 넣어 주면 '' (서버맵 매핑은 이슈 I)
    val attributes: Map<String, String>, // 그 밖의 꼬리표. 카나리 표식도 여기 들어간다
    // 스팬 안에서 벌어진 사건들. OTel 은 예외를 여기에 name="exception" 으로 넣어 보낸다 (스팬 하나에 여러 개 가능).
    // CH 에서는 Nested 컬럼이라 배열 세 개(events.ts · events.name · events.attributes)로 저장된다.
    // 비어 있으면 빈 목록 — #58 에서 이 필드를 빼먹어 예외가 적재되지 않았다 (#79)
    val events: List<SpanEvent> = emptyList(),
) {
    companion object {
        // 카나리 표식을 attributes 에 남길 때 쓰는 키.
        // 수집기는 span 의 trace_state 에서 monimon=canary 를 보고 샘플링을 건너뛰는데(ADR #41),
        // CH spans 에는 trace_state 컬럼이 없다. 그대로 두면 파수꾼이 카나리를 찾을 수단이 사라지므로
        // 적재할 때 이 키로 옮겨 적는다. 조회는 mapContains(attributes, 'monimo.canary') 로 한다
        const val CANARY_KEY = "monimo.canary"
    }
}

// 스팬 안의 사건 하나. 예외면 name = "exception", attributes 에 exception.type · exception.message · exception.stacktrace.
// 예외가 아닌 사건(재시도, 로그)도 올 수 있다. 이름을 가리지 않고 전부 담고, 예외만 고르는 것은 조회가 한다
data class SpanEvent(
    val ts: Instant, // 사건 시각. 나노초까지 (CH DateTime64(9))
    val name: String,
    val attributes: Map<String, String>, // 스택트레이스는 수천 글자가 보통이다. 자르지 않는다 — 자르면 원인을 못 찾는다
)
