# API 명세 v3 (Notion 3c7d..5f46, 수집 2026-09-21)

## §0 공통 규약 요약

**기본 경로**
- `/api/v1` : 화면이 부르는 API 서버의 업무용 경로 (예: `GET /api/v1/applications`)
- `/api/v1/internal/**` : 내부 전용(우리 서비스끼리만, 탐지·파수꾼이 API 서버에 물을 때)
- `/healthz` · `/readyz` : 접두 예외 ① — 쿠버네티스 헬스체크 전용. 헤더·쿼리·본문 없음, 응답은 `{"status":"ok"}` 한 줄. 서비스 6개(API 서버·수집기·탐지·알림·적재 처리기·파수꾼) 모두 동일하게 연다. `readyz` 확인 대상: API 서버=PostgreSQL·ClickHouse 연결 / 수집기=Kafka 연결·PG 설정 캐시 / 탐지=PostgreSQL / 알림=PostgreSQL / 적재 처리기=Kafka·ClickHouse / 파수꾼=외부 웹훅 도달
- `/internal/thread-dump` · `/internal/channels/test` : 접두 예외 ② — 수집기·알림이 여는 서비스 간 문. `/api/v1` 없음
- 형식: JSON(`application/json; charset=utf-8`), 시간대: UTC·ISO 8601 (예: `2026-09-14T10:20:30Z`)

**인증·인가 5종**
| 권한 표기 | 뜻 | 통과 대상 | 개수 |
|---|---|---|---|
| 공개 | 출입증 불필요 | 누구나 (로그인·재발급 2개, 헬스체크는 별도) | 2 |
| VIEWER+ | 로그인만 하면 됨 | VIEWER·ADMIN 둘 다, 읽는 문 | 29 |
| ADMIN | 관리자만 | ADMIN만, 값을 바꾸는 문 전부 | 14 |
| 내부 | 우리 서비스끼리만 | 클러스터 내부, JWT 대신 내부 토큰 | 5 |
| 에이전트 | 감시 대상 앱의 OTel Java Agent | mTLS 클라이언트 인증서(FN-12), gRPC 3개만 | 3 |

**공통 헤더**
- `Authorization: Bearer <JWT>` — VIEWER+·ADMIN 요청
- `Content-Type: application/json` — 본문 있는 POST/PUT/PATCH
- `X-Internal-Token: <내부 토큰>` — 내부 문 5개(`/api/v1/internal/**` 3개, `/internal/thread-dump`, `/internal/channels/test`), Authorization 미사용
- `X-Request-Id` — 모든 응답에 서버가 UUID로 부여 (장애 문의 시 사용)
- `429 TOO_MANY_REQUESTS`에만 `Retry-After` 헤더 동봉

**응답 봉투**
- 성공(단건): `{ "data": {...} }`
- 성공(목록): `{ "data": [...], "page": { "next_cursor": "...", "limit": 50 } }`
- 실패: `{ "error": { "code": "...", "message": "..." } }`
- `data`와 `error`는 절대 함께 나오지 않음

**에러 코드 (일반)**: 400 INVALID_REQUEST · 401 UNAUTHENTICATED · 403 FORBIDDEN · 404 NOT_FOUND · 409 CONFLICT · 422 UNPROCESSABLE · 429 TOO_MANY_REQUESTS · 500 INTERNAL_ERROR · 503 UPSTREAM_UNAVAILABLE

**에러 코드 (도메인)**: 409 CONFIG_VERSION_CONFLICT(설정 동시수정) · 409 APPLICATION_NAME_TAKEN(서비스명 중복) · 409 RULE_CHANNEL_DUPLICATE(규칙-채널 중복) · 404 SIGNAL_EXPIRED(TTL 만료: 트레이스·덤프 93일, 로그 97일) · 422 TIME_RANGE_TOO_WIDE(시간범위 상한 초과) · 503 AGENT_NOT_REACHABLE(파드를 든 수집기 없음) · 503 THREAD_DUMP_TIMEOUT(팬아웃 응답 시간초과)

**페이징**: 쪽 번호 대신 커서. `limit`(기본 50, 최대 500) · `cursor`(직전 응답의 `page.next_cursor`, 마지막 쪽이면 `null`). 예외: 스캐터 차트(`GET /traces/scatter`)의 `limit`은 점 개수 상한이며, 초과 시 서버가 격자로 접어 `mode`가 `raw`→`bucketed`로 바뀜(FN-47, 사용자가 아니라 시스템이 결정).

**시간 범위 공통 파라미터**: `from`(필수, 시작 포함) · `to`(필수, 끝 제외) · `step`(선택, 초 단위, 기본 60 — 60 미만 원본, 60 이상 1분 롤업, 3600 이상 1시간 롤업을 서버가 자동 선택, FN-47). `from`≥`to`(같거나 늦음)면 422 UNPROCESSABLE, 범위 초과 시 422 TIME_RANGE_TOO_WIDE — 상한은 기본 7일(`to`−`from` 길이), 더 긴 범위가 필요한 API는 그 API에서만 상한을 늘린다(예: `metrics/series` 롤업별, 구현 시 확정).

**식별자 규칙**: 숫자 `id`는 절대 외부 노출 안 함. 서비스·파드·규칙·채널·경보·설정·사용자·덤프는 경로/본문에서 **UUID**. 신호 조회 시 서비스는 `service_name`(문자열, `applications.name`과 동일 글자), 파드는 `agent_key`(문자열, CH의 `agent_id`와 동일값, PG 숫자 FK `agent_id`와 혼동 방지). 트레이스는 `trace_id`(외부 발급 문자열), 스레드 덤프는 `dump_uuid`(CH `thread_dumps.dump_uuid`). 공통 쿼리 파라미터명 7개로 통일: `from`·`to`·`step`·`cursor`·`limit`·`service_name`·`agent_key`.

---

## REST API (50행)

| # | 서비스 | Method | Path | 설명 | 요청 주요 필드 | 응답 주요 필드 | 관련 기능 |
|---|---|---|---|---|---|---|---|
| 1 | API 서버 | POST | /api/v1/agents/{agentUuid}/thread-dumps | 지금 이 파드의 스레드 덤프를 동기로 요청 (권한 ADMIN) | body: timeout_ms | dump_uuid, agent_uuid, agent_key, service_name, requested_by, requested_at, thread_count, dump | FN-39, thread_dumps |
| 2 | API 서버 | GET | /api/v1/internal/service-health | 서비스별 1분 호출수·에러수·지연 백분위 (권한 내부) | service_name?, from, to, step? | ts_min, service_name, cnt, err_cnt, cnt_4xx, cnt_5xx, p50/p95/p99_ms | FN-27, FN-29, service_health_1m |
| 3 | API 서버 | GET | /api/v1/errors | 실패한 스팬 목록(예외 타입·메시지·상태코드) (권한 VIEWER+) | service_name, from, to, agent_key?, http_status?, exception_type?, cursor?, limit? | trace_id, span_id, start_time, duration_ns, service_name, agent_key, span_name, span_kind, status_code, http_status, exception_type, exception_message | FN-23, spans |
| 4 | API 서버 | GET | /api/v1/internal/canary/freshness | 가장 최근 카나리 신호가 몇 초 전인지 (권한 내부) | service_name? | service_name, last_signal_at, age_sec, threshold_sec, fresh | FN-55, #01 #18 |
| 5 | API 서버 | GET | /api/v1/traces/scatter | 스캐터 차트 점 데이터, 과다시 버킷 집계 (권한 VIEWER+) | service_name, from, to, agent_key?, limit?(점 상한) | mode(raw/bucketed), total_count, points[trace_id, start_time, duration_ms, is_error, http_status, span_name, agent_key] | FN-41, FN-42, transactions/heatmap_1m |
| 6 | API 서버 | GET | /api/v1/applications/{applicationUuid} | 서비스 하나의 상세 (권한 VIEWER+) | (경로만) | application_uuid, name, display_name, description, created_at, updated_at, agent_count | FN-17, applications |
| 7 | API 서버 | PATCH | /api/v1/alert-channels/{alertChannelUuid}/enabled | 채널을 잠시 끄거나 켠다 (권한 ADMIN) | body: enabled | alert_channel_uuid, enabled, updated_at | FN-30, alert_channels.enabled |
| 8 | API 서버 | PATCH | /api/v1/applications/{applicationUuid} | 표시명·설명 수정(name은 불변) (권한 ADMIN) | body: display_name, description | application 상세 | FN-17, applications |
| 9 | API 서버 | GET | /api/v1/traces/heatmap | 1분×지연구간 격자의 건수 (권한 VIEWER+) | service_name, from, to, step? | bucket_width_ms, step, cells[ts_min, latency_bucket, is_error, cnt] | FN-44, heatmap_1m |
| 10 | API 서버 | POST | /api/v1/alert-rules | 경보 규칙 생성 (권한 ADMIN) | body: application_uuid, name, metric_kind, operator, threshold, window_sec, severity, enabled, channel_uuids | alert_rule_uuid + 규칙 상세 + channels[] | FN-24, FN-25, alert_rules |
| 11 | API 서버 | PUT | /api/v1/alert-rules/{alertRuleUuid}/channels | 규칙-채널 연결 목록 전체 교체 (권한 ADMIN) | body: channel_uuids[] | alert_rule_uuid, channels[] | FN-24, alert_rule_channels |
| 12 | API 서버 | GET | /api/v1/stats/urls | URL별 호출수·에러율·p50/p95/p99 (권한 VIEWER+) | service_name, from, to, agent_key?, cursor?, limit? | span_name, cnt, err_cnt, error_rate, p50/p95/p99_ms | FN-45, url_stats_1m |
| 13 | API 서버 | POST | /api/v1/applications | 서비스 신규 등록 (권한 ADMIN) | body: name, display_name, description | application 상세 | FN-17, applications |
| 14 | API 서버 | GET | /api/v1/traces/{traceId} | 트레이스 전체 스팬을 부모-자식 트리로 조립 (권한 VIEWER+) | path: traceId | trace_id, span_count, services, root(트리: span_id, service_name, agent_key, duration_ns, status_code, events, children) | FN-19, FN-20, spans |
| 15 | API 서버 | GET | /api/v1/metrics/series | 인스펙터 시계열(원본/1분/1시간 자동 선택) (권한 VIEWER+) | service_name, metric_name, from, to, agent_key?, step? | metric_name, source_table, step, series[agent_key, attributes, points(ts_min, avg/min/max/last_v)] | FN-33, FN-37, metrics_raw/1m/1h |
| 16 | API 서버 | GET | /api/v1/alert-events | 터진 경보 목록(기본 FIRING) (권한 VIEWER+) | service_name?, state?, severity?, from?, to?, cursor?, limit? | alert_event_uuid, alert_rule_uuid, rule_name, service_name, agent_key, fingerprint, state, severity, observed_value, fired_at, resolved_at | FN-28, FN-50, alert_events |
| 17 | API 서버 | PUT | /api/v1/alert-channels/{alertChannelUuid} | 채널 설정 수정 (권한 ADMIN) | body: name, type, config(webhook_url, channel 등) | 채널 상세 | FN-30, alert_channels |
| 18 | API 서버 | GET | /api/v1/applications | 감시 대상 서비스 목록(드롭다운) (권한 VIEWER+) | cursor?, limit? | application 목록 + page | FN-17, FN-52, applications |
| 19 | API 서버 | POST | /api/v1/alert-channels/{alertChannelUuid}/test | 채널로 시험 메시지 한 번 발송 (권한 ADMIN) | (본문 없음) | alert_channel_uuid, type, result(SUCCESS/FAILED), response, tested_at | FN-30, alert_channels |
| 20 | API 서버 | GET | /api/v1/auth/me | 로그인한 사용자 이름·역할 조회 (권한 VIEWER+) | 없음 | user_uuid, email, name, role, created_at | FN-17, users |
| 21 | API 서버 | GET | /api/v1/agents/{agentUuid}/active-threads | 현재 스레드 수(jvm.thread.count 최신값) (권한 VIEWER+) | (경로만) | agent_uuid, agent_key, service_name, metric_name, ts_min, last_v | FN-38, metrics_1m |
| 22 | API 서버 | GET | /api/v1/applications/{applicationUuid}/agents | 그 서비스에 속한 파드 목록 (권한 VIEWER+) | status?, cursor?, limit? | agent 목록(agent_uuid, service_name, agent_key, hostname, ip, jvm_version, agent_version, status) | FN-37, agents |
| 23 | API 서버 | GET | /api/v1/alert-rules/{alertRuleUuid} | 경보 규칙 하나의 상세 (권한 VIEWER+) | (경로만) | 규칙 상세 + channels[] | FN-24, alert_rules |
| 24 | API 서버 | GET | /api/v1/alert-events/{alertEventUuid}/notifications | 이 경보를 어디로 몇 번 보냈는지 (권한 VIEWER+) | cursor?, limit? | notification_uuid, alert_channel_uuid, channel_name, type, result, retry_count, response, sent_at | FN-31, FN-32, notification_history |
| 25 | API 서버 | GET | /api/v1/metrics/names | 실제 들어온 지표 이름 목록(드롭다운) (권한 VIEWER+) | service_name, from?, to? | metric_name, attribute_keys[] | FN-37, metrics_raw |
| 26 | API 서버 | GET | /api/v1/traces/transactions | 드래그한 사각영역(시각×응답시간) 내 요청 목록 (권한 VIEWER+) | service_name, from, to, agent_key?, min/max_duration_ms?, is_error?, cursor?, limit? | trace_id, start_time, duration_ms, service_name, agent_key, span_name, is_error, http_status | FN-43, transactions |
| 27 | API 서버 | GET | /api/v1/agents/{agentUuid} | 파드 하나의 상세 (권한 VIEWER+) | (경로만) | agent_uuid, application_uuid, service_name, agent_key, hostname, ip, jvm_version, agent_version, status, first_seen_at, updated_at | FN-48, agents |
| 28 | API 서버 | POST | /api/v1/auth/refresh | 만료된 출입증(JWT) 재발급 (권한 공개, refresh_token 필요) | body: refresh_token | access_token, expires_in | FN-17, users |
| 29 | API 서버 | GET | /api/v1/applications/{applicationUuid}/config | 현재 적용 중인 설정(샘플링률·판번호) 조회 (권한 VIEWER+) | (경로만) | application_config_uuid, sampling_rate, version, updated_by, updated_at | FN-48, application_configs |
| 30 | API 서버 | PUT | /api/v1/alert-rules/{alertRuleUuid} | 기준값·구간·심각도 수정 (권한 ADMIN) | body: name, metric_kind, operator, threshold, window_sec, severity | 규칙 상세 | FN-24, alert_rules |
| 31 | API 서버 | POST | /api/v1/auth/login | 이메일·비밀번호로 로그인 (권한 공개) | body: email, password | access_token, refresh_token, expires_in, user(user_uuid, email, name, role) | FN-17, users |
| 32 | API 서버 | POST | /api/v1/auth/logout | 재발급 토큰 무효화 (권한 VIEWER+) | body: refresh_token | result: LOGGED_OUT | FN-17, users |
| 33 | API 서버 | GET | /api/v1/alert-channels/{alertChannelUuid} | 채널 하나의 상세(비밀값은 가림) (권한 ADMIN) | (경로만) | 채널 상세(config 마스킹) | FN-30, alert_channels |
| 34 | API 서버 | GET | /api/v1/thread-dumps | 찍어둔 스레드 덤프 목록(본문 제외) (권한 VIEWER+) | service_name?, agent_key?, from?, to?, cursor?, limit? | dump_uuid, agent_key, service_name, requested_by, requested_at, thread_count | FN-39, thread_dumps |
| 35 | API 서버 | GET | /api/v1/logs | 로그 검색 (권한 VIEWER+) | service_name?, from, to, agent_key?, level?, logger?, trace_id?, q?, cursor?, limit? | ts, service_name, agent_key, level, logger, thread, message, trace_id, span_id, attributes | FN-61, logs |
| 36 | API 서버 | GET | /api/v1/errors/timeline | 시간대별 에러 건수(상태코드 대역·예외타입별) (권한 VIEWER+) | service_name, from, to, step? | step, series[ts_min, http_status_class, exception_type, cnt] | FN-46, spans |
| 37 | API 서버 | POST | /api/v1/alert-channels | 채널 등록 (권한 ADMIN) | body: name, type, config(webhook_url, channel), enabled | 채널 상세 | FN-30, alert_channels |
| 38 | API 서버 | DELETE | /api/v1/applications/{applicationUuid} | 감시 대상에서 제외(딸린 규칙/설정 있으면 409) (권한 ADMIN) | (경로만) | application_uuid, result: DELETED | FN-17, applications |
| 39 | API 서버 | PUT | /api/v1/applications/{applicationUuid}/config | 샘플링률 변경(version+1, 낙관적 잠금) (권한 ADMIN) | body: sampling_rate, expected_version | application_config_uuid, sampling_rate, version, updated_by, updated_at | FN-49, application_configs |
| 40 | API 서버 | GET | /api/v1/internal/agents/active | 최근 구간 데이터를 보낸 파드 목록(AGENT_DOWN 판정용) (권한 내부) | service_name?, from, to | service_name, agent_key, last_signal_at, source | FN-29, #35, spans/metrics_raw |
| 41 | API 서버 | GET | /api/v1/server-map | 서비스 간 호출량·에러수·평균소요시간 (권한 VIEWER+) | service_name?, from, to | nodes[service_name, cnt, err_cnt], edges[caller_service, callee_service, callee_kind, cnt, err_cnt, avg_duration_ms] | FN-21, FN-22, server_map_1m |
| 42 | API 서버 | GET | /api/v1/alert-channels | 채널 목록(SLACK·EMAIL·WEBHOOK·PAGERDUTY) (권한 VIEWER+) | type?, enabled?, cursor?, limit? | 채널 목록 + page | FN-30, alert_channels |
| 43 | API 서버 | GET | /api/v1/alert-rules/{alertRuleUuid}/channels | 이 규칙이 터지면 어디로 가는지 (권한 VIEWER+) | (경로만) | alert_rule_uuid, channels[] | FN-24, alert_rule_channels |
| 44 | API 서버 | PATCH | /api/v1/alert-rules/{alertRuleUuid}/enabled | 규칙 켜기/끄기 (권한 ADMIN) | body: enabled | alert_rule_uuid, enabled, updated_at | FN-50, alert_rules.enabled |
| 45 | API 서버 | GET | /api/v1/alert-events/{alertEventUuid} | 경보 하나의 상세(규칙·파드·실측값) (권한 VIEWER+) | (경로만) | alert_event 상세 + threshold, operator, window_sec, metric_kind | FN-28, alert_events |
| 46 | API 서버 | GET | /api/v1/thread-dumps/{dumpUuid} | 덤프 본문 전체 조회 (권한 VIEWER+) | path: dumpUuid | dump_uuid, agent_key, service_name, requested_by, requested_at, thread_count, dump(본문) | FN-39, thread_dumps |
| 47 | API 서버 | GET | /api/v1/agents | 파드 전체 목록 (권한 VIEWER+) | service_name?, status?, cursor?, limit? | agent 목록 + page | FN-48, agents |
| 48 | API 서버 | GET | /api/v1/alert-rules | 경보 규칙 목록 (권한 VIEWER+) | service_name?, enabled?, severity?, cursor?, limit? | 규칙 목록 + page | FN-24, alert_rules |
| 49 | 수집기 | POST | /internal/thread-dump | API 서버의 팬아웃 수신용. 해당 agent_key를 들고 있으면 덤프를 떠서 반환, 없으면 204 (권한 내부) | body: agent_key | thread_count, dump (204면 본문 없음) | FN-39, #31 #36 |
| 50 | 알림 | POST | /internal/channels/test | API 서버가 대행 요청 시 채널로 시험 메시지 실발송 후 성공/실패 반환 (권한 내부) | body: type, config(webhook_url, channel 등) | result(SUCCESS/FAILED), response | FN-30, alert_channels |

비고: 위 표의 "요청 주요 필드"에서 `?`는 선택 파라미터, `cursor`·`limit`은 §0-6 공통 페이징 규칙을 따름. 25번(GET /internal/service-health), 4번(canary/freshness), 40번(internal/agents/active)은 `X-Internal-Token` 헤더로 인증하는 `내부` 전용 문.

---

## gRPC 절 요약 (수집기 OTLP 수신 3건)

3개 문 모두 포트 **4317**, **mTLS**(에이전트 클라이언트 인증서), 메서드 이름은 모두 `Export`로 동일하고 싣는 내용만 다르다. HTTP처럼 경로·쿼리 파라미터가 없고 필요한 값은 전부 메시지 안에 들어간다.

| 서비스/메서드 | 실어 나르는 것 | 요청 메시지 | 응답 메시지 | 관련 기능 |
|---|---|---|---|---|
| `TraceService/Export` (opentelemetry.proto.collector.trace.v1) | 스팬(요청이 거친 단계·시간). 콜스택/스캐터/서버맵 재료 | `ExportTraceServiceRequest`: resource_spans 배열 | `ExportTraceServiceResponse` — partial_success(rejected_spans, error_message) | FN-7, FN-11, #33 |
| `MetricsService/Export` (opentelemetry.proto.collector.metrics.v1) | 지표(JVM·호스트: CPU·힙·GC). 시스템메트릭·경보 재료 | `ExportMetricsServiceRequest`: resource_metrics 배열 | `ExportMetricsServiceResponse` — partial_success(rejected_data_points) | FN-11, FN-33, FN-34 |
| `LogsService/Export` (opentelemetry.proto.collector.logs.v1) | 로그 줄. 로그 검색 재료 | `ExportLogsServiceRequest`: resource_logs 배열 | `ExportLogsServiceResponse` — partial_success(rejected_log_records). 전역 하한(MIN_LOG_LEVEL) 미만 등급은 여기서 버려짐 | FN-11, #38, Q22 |

요청 메시지는 셋 다 3층 구조: ① `resource`(서비스명·파드) → ② `scope`(계측 라이브러리) → ③ 실제 기록(spans/metrics/log_records). 수집기는 ①만 보고도 어느 서비스·파드인지 식별.

**실패 코드**
- `UNAVAILABLE` — 수집기 백프레셔/큐 참, `RetryInfo`(재시도 대기시간) 동봉 → 에이전트 재시도
- `UNAUTHENTICATED` — 인증서 없음/만료/불일치 → 재시도 안 함
- `RESOURCE_EXHAUSTED`는 사용하지 않음(OTLP 스펙상 RetryInfo 없이는 에이전트가 재시도 없이 묶음을 버리기 때문). 밀릴 때는 `UNAVAILABLE`로 통일.

**수신 후 처리**: 검증 통과분은 Kafka `raw` 토픽으로 전송. 스팬은 트레이스ID 해시로 1%만 샘플링하되, `trace_state`에 카나리 표시(`monimon=canary`)가 있으면 예외적으로 전량 통과. 파수꾼의 카나리 요청은 gRPC 문에 직접 들어오지 않고, 쇼핑몰 API 호출 시 쇼핑몰에 붙은 에이전트가 실제 요청과 같은 경로로 기록해 들어옴(에이전트 구간까지 검증하기 위함).

---

## 호출 흐름 요약

핵심 규칙: **화면은 API 서버 하나만 호출**하고, 신호는 항상 수집기로 들어와 Kafka를 거쳐 ClickHouse에 쌓이며, 유일하게 거꾸로 흐르는 것은 스레드 덤프뿐이다.

1. **평소 흐름**: 에이전트(쇼핑몰에 부착된 OTel Java Agent) → gRPC 3개(4317, mTLS) → 수집기(1% 샘플링, 카나리 표시는 예외) → Kafka → 적재 처리기 → ClickHouse. 화면은 API 서버에게만 물어 이 데이터를 조회.
2. **탐지·파수꾼**: ClickHouse에 직접 붙지 않고, API 서버의 `내부` 문 3개(`GET /api/v1/internal/service-health`, `GET /api/v1/internal/agents/active`, `GET /api/v1/internal/canary/freshness`)로만 읽음. 화면이 보는 그래프와 탐지의 판정이 같은 계산에서 나오도록 하기 위함.
3. **알림**: 경보 발송은 PostgreSQL을 큐처럼 스스로 폴링해 처리. 화면에서 "이 슬랙 주소가 맞나" 테스트 버튼을 누르면 그때만 API 서버가 내부 문(`POST /internal/channels/test`)으로 알림 서비스에 대행 요청.
4. **역방향(유일)**: 스레드 덤프. 화면 → API 서버(`POST /api/v1/agents/{agentUuid}/thread-dumps`) → 수집기 전체 팬아웃(`POST /internal/thread-dump`) → 그 파드를 실제로 들고 있는 수집기 한 대 → Extension → 같은 경로로 응답 역류. 아무도 답하지 않으면 `503 AGENT_NOT_REACHABLE`.
5. 기타 경로: API 서버 ↔ PostgreSQL(설정·규칙·채널·사용자 쓰기, 경보 이력 읽기) / API 서버 ↔ ClickHouse(신호 조회: 스팬·메트릭·로그·덤프) / 탐지 ↔ PostgreSQL(규칙 읽기·경보 이벤트 쓰기) / 알림 ↔ PostgreSQL(발송 대기 읽기·전송 이력 쓰기) / 파수꾼 → 쇼핑몰(카나리 가짜 주문 요청, traceparent+tracestate 카나리 표시 포함).

TOTAL_ROWS=50
