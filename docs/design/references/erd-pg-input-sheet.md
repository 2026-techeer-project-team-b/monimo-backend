> STATUS: TENTATIVE (blocked by Q14 · Q16) — ERDCloud 입력용 시트. 3단계 `30-architecture.md`의 "소유 데이터"(PG 영역)에 편입된다.
> 근거 ADR: `#20` `#24` `#28` `#29` · CH 영역은 `references/erd-clickhouse-guide.md`

# PostgreSQL 영역 ERD — ERDCloud 입력 시트

## 0. 명명 규칙 (전 테이블 공통)

| 순서 | 컬럼 | 타입 | 제약 | 역할 |
|---|---|---|---|---|
| 1 | `id` | `BIGSERIAL` | **PK** | 내부용. FK가 참조하는 실제 키. JOIN·인덱스 성능 목적 |
| 2 | `<단수형>_uuid` | `UUID` | **UK**, NOT NULL, `DEFAULT gen_random_uuid()` | 외부 노출용. API·URL·프론트에 나가는 식별자 |
| … | 업무 컬럼 | | | |
| 끝-1 | `created_at` | `TIMESTAMPTZ` | NOT NULL `DEFAULT now()` | |
| 끝 | `updated_at` | `TIMESTAMPTZ` | NOT NULL `DEFAULT now()` | |

**왜 두 개인가**
- 숫자 PK: 8바이트 순차값이라 인덱스가 조밀하고 JOIN이 빠르다. UUID를 PK로 쓰면 값이 무작위라 B-tree 페이지가 흩어진다.
- UUID: 순번이 밖으로 새면 "우리 규칙이 몇 개인지"가 추측된다(`/api/rules/1`, `/api/rules/2`…). 외부에는 UUID만 낸다.

**FK 컬럼 이름은 `<참조테이블 단수형>_id` 이고 항상 숫자 `id`를 참조한다.** UUID는 FK로 쓰지 않는다.

**연결 테이블(`alert_rule_channels`)에는 UUID를 두지 않는다.** 외부에 단독으로 노출될 일이 없다.

---

## 1. `applications` — 감시 대상 서비스

| # | 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|---|
| 1 | `id` | BIGSERIAL | PK | |
| 2 | `application_uuid` | UUID | UK, NN | 외부 노출용 |
| 3 | `name` | VARCHAR(100) | UK, NN | **CH `service_name`과 같은 값** (영역 간 논리키) |
| 4 | `display_name` | VARCHAR(200) | | 화면 표시명 |
| 5 | `description` | TEXT | | |
| 6 | `created_at` | TIMESTAMPTZ | NN | |
| 7 | `updated_at` | TIMESTAMPTZ | NN | |

---

## 2. `agents` — 에이전트 인스턴스 등록·생존

| # | 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|---|
| 1 | `id` | BIGSERIAL | PK | |
| 2 | `agent_uuid` | UUID | UK, NN | 외부 노출용 |
| 3 | `application_id` | BIGINT | FK → applications.id, NN | |
| 4 | `agent_key` | VARCHAR(100) | UK, NN | 에이전트가 스스로 보내는 식별자. **CH `agent_id`와 같은 값** (⚠️ 이름 주의, §7) |
| 5 | `hostname` | VARCHAR(255) | | |
| 6 | `ip` | INET | | |
| 7 | `jvm_version` | VARCHAR(50) | | e.g. `17.0.9` |
| 8 | `agent_version` | VARCHAR(50) | | 우리 에이전트 버전 |
| 9 | `status` | VARCHAR(20) | NN | `UP` / `DOWN` / `UNKNOWN` |
| 10 | `first_seen_at` | TIMESTAMPTZ | | 최초 등록 |
| 11 | `last_seen_at` | TIMESTAMPTZ | | **하트비트마다 UPDATE** — CH에 두면 뮤테이션 지옥이라 PG (`#20`) |
| 12 | `created_at` | TIMESTAMPTZ | NN | |
| 13 | `updated_at` | TIMESTAMPTZ | NN | |

---

## 3. `application_configs` — 재배포 없는 설정 (핵심기능 5)

| # | 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|---|
| 1 | `id` | BIGSERIAL | PK | |
| 2 | `application_config_uuid` | UUID | UK, NN | |
| 3 | `application_id` | BIGINT | FK → applications.id, **UK**, NN | 앱당 설정 1행 (1:1) |
| 4 | `sampling_rate` | NUMERIC(5,4) | NN | 0.0100 = 1% (`#25` 해시 기반) |
| 5 | `log_level` | VARCHAR(10) | | `INFO` / `DEBUG` … |
| 6 | `enabled_modules` | JSONB | | 계측 모듈 7종 on/off (`#25`) |
| 7 | `version` | INT | NN | 변경마다 +1. 에이전트가 이 값으로 최신 여부 판단 |
| 8 | `updated_by` | BIGINT | FK → users.id | |
| 9 | `created_at` | TIMESTAMPTZ | NN | |
| 10 | `updated_at` | TIMESTAMPTZ | NN | 변경 시 Redis 채널로 푸시 (`#29`) |

> 설정은 **앱 단위**다. 같은 앱의 에이전트 전부가 같은 샘플링률을 쓴다. 에이전트 개별 오버라이드는 **Q18** — 필요해지면 `agent_config_overrides(agent_id, …)` 를 별도 테이블로 추가한다(§13 ①).

---

## 4. `alert_rules` — 경보 규칙 (핵심기능 2)

| # | 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|---|
| 1 | `id` | BIGSERIAL | PK | |
| 2 | `alert_rule_uuid` | UUID | UK, NN | |
| 3 | `application_id` | BIGINT | FK → applications.id, NN | |
| 4 | `name` | VARCHAR(200) | NN | 규칙 이름 |
| 5 | `metric_kind` | VARCHAR(30) | NN | `5XX_RATE` / `P95_LATENCY` / `CPU` / `HEAP` / `GC_TIME` |
| 6 | `operator` | VARCHAR(5) | NN | `GT` / `GTE` / `LT` / `LTE` |
| 7 | `threshold` | NUMERIC(12,4) | NN | |
| 8 | `window_sec` | INT | NN | 평가 구간(초) |
| 9 | `severity` | VARCHAR(20) | NN | `CRITICAL` / `WARNING` / `INFO` |
| 10 | `enabled` | BOOLEAN | NN, DEFAULT true | |
| 11 | `created_at` | TIMESTAMPTZ | NN | |
| 12 | `updated_at` | TIMESTAMPTZ | NN | |

> 탐지기는 이 규칙을 읽고 CH `service_health_1m` / `metrics_1m` 을 주기 조회한다 (`#30` — Kafka 컨슈머 아님).

---

## 5. `alert_channels` — 알림 채널

| # | 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|---|
| 1 | `id` | BIGSERIAL | PK | |
| 2 | `alert_channel_uuid` | UUID | UK, NN | |
| 3 | `name` | VARCHAR(100) | NN | |
| 4 | `type` | VARCHAR(20) | NN | `EMAIL` / `SMS` / `WEBHOOK` / `SLACK` |
| 5 | `config` | JSONB | NN | 채널별 설정(수신 주소·훅 URL 등) |
| 6 | `enabled` | BOOLEAN | NN, DEFAULT true | |
| 7 | `created_at` | TIMESTAMPTZ | NN | |
| 8 | `updated_at` | TIMESTAMPTZ | NN | |

---

## 6. `alert_rule_channels` — 규칙 ↔ 채널 (N:M 연결)

| # | 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|---|
| 1 | `id` | BIGSERIAL | PK | **UUID 없음** (외부 노출 안 함) |
| 2 | `alert_rule_id` | BIGINT | FK → alert_rules.id, NN | |
| 3 | `alert_channel_id` | BIGINT | FK → alert_channels.id, NN | |
| 4 | `created_at` | TIMESTAMPTZ | NN | |

> `UNIQUE (alert_rule_id, alert_channel_id)` 복합 유니크를 건다.

---

## 7. `alert_events` — 발화/해소 인시던트

| # | 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|---|
| 1 | `id` | BIGSERIAL | PK | |
| 2 | `alert_event_uuid` | UUID | UK, NN | |
| 3 | `alert_rule_id` | BIGINT | FK → alert_rules.id, NN | |
| 4 | `agent_id` | BIGINT | FK → agents.id, **NULL 허용** | 앱 단위 규칙(5xx 비율·p95) = NULL / 에이전트 단위 규칙(CPU·힙·GC) = 발생 에이전트 |
| 5 | `fingerprint` | VARCHAR(64) | NN | 중복 억제 키. 같은 규칙+대상이면 같은 값 |
| 6 | `state` | VARCHAR(20) | NN | `FIRING` / `RESOLVED` |
| 7 | `observed_value` | NUMERIC(12,4) | | 발화 당시 실측값 |
| 8 | `fired_at` | TIMESTAMPTZ | NN | |
| 9 | `resolved_at` | TIMESTAMPTZ | NULL | 해소 전에는 NULL |
| 10 | `created_at` | TIMESTAMPTZ | NN | |

---

## 8. `notification_history` — 채널 전송 이력

| # | 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|---|
| 1 | `id` | BIGSERIAL | PK | |
| 2 | `notification_uuid` | UUID | UK, NN | |
| 3 | `alert_event_id` | BIGINT | FK → alert_events.id, NN | |
| 4 | `alert_channel_id` | BIGINT | FK → alert_channels.id, NN | |
| 5 | `result` | VARCHAR(20) | NN | `SUCCESS` / `FAIL` |
| 6 | `retry_count` | INT | NN, DEFAULT 0 | |
| 7 | `response` | TEXT | | 채널 응답 원문(실패 원인 추적) |
| 8 | `sent_at` | TIMESTAMPTZ | NN | |
| 9 | `created_at` | TIMESTAMPTZ | NN | |

---

## 9. `thread_dump_requests` — 스레드 덤프 요청 상태 (핵심기능 3)

| # | 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|---|
| 1 | `id` | BIGSERIAL | PK | |
| 2 | `thread_dump_request_uuid` | UUID | UK, NN | |
| 3 | `agent_id` | BIGINT | FK → agents.id, NN | |
| 4 | `requested_by` | BIGINT | FK → users.id | |
| 5 | `status` | VARCHAR(20) | NN | `REQUESTED` / `COMPLETED` / `TIMEOUT` / `FAILED` |
| 6 | `dump_ref` | VARCHAR(200) | | **Q16 미결** — CH `thread_dumps` 참조키 / 본문 직접 저장 / 저장 안 함 |
| 7 | `requested_at` | TIMESTAMPTZ | NN | Redis 채널로 명령 발행 (`#24`) |
| 8 | `completed_at` | TIMESTAMPTZ | NULL | |
| 9 | `created_at` | TIMESTAMPTZ | NN | |

---

## 10. `users` — 로그인 최소치

| # | 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|---|
| 1 | `id` | BIGSERIAL | PK | |
| 2 | `user_uuid` | UUID | UK, NN | |
| 3 | `email` | VARCHAR(255) | UK, NN | |
| 4 | `password_hash` | VARCHAR(255) | NN | |
| 5 | `name` | VARCHAR(100) | | |
| 6 | `role` | VARCHAR(20) | NN | `ADMIN` / `VIEWER`. RBAC 세분화는 비목표 |
| 7 | `created_at` | TIMESTAMPTZ | NN | |
| 8 | `updated_at` | TIMESTAMPTZ | NN | |

---

## 11. 관계 목록 (ERDCloud에서 선 그을 때)

| 부모 | 자식 | 카디널리티 | 자식 FK 컬럼 |
|---|---|---|---|
| applications | agents | 1 : N | `application_id` |
| applications | application_configs | **1 : 1** | `application_id` (UK) |
| applications | alert_rules | 1 : N | `application_id` |
| agents | thread_dump_requests | 1 : N | `agent_id` |
| agents | alert_events | 1 : N (옵션) | `agent_id` (NULL 허용) |
| alert_rules | alert_rule_channels | 1 : N | `alert_rule_id` |
| alert_channels | alert_rule_channels | 1 : N | `alert_channel_id` |
| alert_rules | alert_events | 1 : N | `alert_rule_id` |
| alert_events | notification_history | 1 : N | `alert_event_id` |
| alert_channels | notification_history | 1 : N | `alert_channel_id` |
| users | application_configs | 1 : N (옵션) | `updated_by` |
| users | thread_dump_requests | 1 : N (옵션) | `requested_by` |

**ClickHouse 영역과의 연결 (ERD에 점선으로만, FK 아님)**
- `applications.name` ⇢ CH `service_name`
- `agents.agent_key` ⇢ CH `agent_id`

---

## 12. 결정·주의사항

**① `agent_key` 이름** — CH는 에이전트 식별자를 `agent_id`(문자열)로 쓴다. PG에서도 같은 이름을 쓰면 `alert_events.agent_id`·`thread_dump_requests.agent_id`(둘 다 `agents.id`를 가리키는 숫자 FK)와 충돌한다. 그래서 **PG에서만 `agent_key`로 부른다. 값은 CH `agent_id`와 동일하다.**
반대안: 비즈니스 키를 `agent_id`로 두고 FK를 `agent_no` 등으로 부르기 — FK 명명 일관성이 10개 테이블에 걸쳐 깨져서 기각.

**② 연결 테이블 UUID 생략** — `alert_rule_channels`는 API로 단독 노출되지 않는다(항상 규칙의 하위로 다뤄짐). 필요해지면 그때 추가.

**③ `dump_ref` 는 Q16 종속** — 스레드 덤프 본문을 CH에 둘지 PG에 둘지 안 둘지가 미정이라 컬럼 의미가 확정 안 됐다. Q16이 닫히면 타입까지 확정한다.

**④ 인덱스** — ERD 단계에서는 안 그린다. 실제 DDL 작성 시 아래를 추가한다.
- `agents(application_id, status)` — 앱별 살아있는 에이전트 목록
- `alert_events(alert_rule_id, state, fired_at DESC)` — 진행 중 인시던트 조회
- `alert_events(fingerprint) WHERE state = 'FIRING'` — 중복 억제 조회
- `alert_events(agent_id, fired_at DESC) WHERE agent_id IS NOT NULL` — 에이전트 상세 화면의 경보 이력
- `notification_history(alert_event_id, sent_at DESC)`

---

## 13. 검증 — 삼각형(중복 경로) 점검과 기능 대조

### 삼각형 두 종류

**나쁜 삼각형** = 같은 사실을 두 경로로 저장해서 **어긋날 수 있는** 것. 제거 대상.
**괜찮은 삼각형** = 이력(사실) 테이블이 **독립된 사실 둘**을 가리켜서 생기는 것. 두 경로가 만나는 지점은 도메인 불변식이지 중복 저장이 아니다.

### ① `applications → agents → agent_configs` + `applications → agent_configs` — **나쁜 삼각형, 제거함**

초안의 `agent_configs`는 `application_id`(NN)와 `agent_id`(NULL)를 둘 다 가졌다. `agent_id`가 채워지면 `application_id`는 `agents.application_id`에서 유도되므로 **같은 정보를 두 번 저장**하는 꼴이고, "설정은 앱 A인데 에이전트는 앱 B 소속"인 행이 생길 수 있다.

**조치**: 핵심기능 5("재배포 없이 샘플링률과 경보 임계값을 바꾼다")는 앱 단위로 충분하다. Pinpoint도 샘플링은 앱 단위다. → `application_configs`로 바꾸고 `agent_id`를 뺐다. 앱당 1행이라 1:1. 개별 오버라이드가 정말 필요하면 `agent_config_overrides(agent_id UK, …)` 를 **따로** 만든다(Q18). 그러면 삼각형 없이 `agents ─ agent_config_overrides` 한 줄만 는다.

### ② `alert_rules ─< alert_rule_channels >─ alert_channels` + `alert_events ─< notification_history >─ alert_channels` — **괜찮은 삼각형, 유지**

`notification_history`가 `alert_event_id`와 `alert_channel_id`를 둘 다 가지니, `alert_rules`까지 두 경로가 생긴다.

이건 **"어떤 이벤트를 어떤 채널로 보냈다"는 사실 두 개**를 기록하는 것이다. `alert_rule_channels`를 거쳐 가리키면(`notification_history → alert_rule_channels`) 삼각형은 사라지지만, 나중에 규칙에서 채널을 빼는 순간 **과거 전송 이력이 고아가 된다**. 이력은 설정 변경과 무관하게 살아남아야 하므로 채널을 직접 가리키는 지금 구조가 맞다.

**대신 규칙 하나를 건다**: `alert_rules`·`alert_channels`·`agents`는 **하드 DELETE 금지**. `enabled = false`(채널·규칙) / `status = 'DOWN'`(에이전트)로만 끈다. 그래야 이력 FK가 안 깨진다.

### ③ `applications → agents → alert_events` + `applications → alert_rules → alert_events` — **괜찮은 삼각형, 새로 생김**

기능 대조에서 **빠진 컬럼**이 나왔다. `alert_rules.metric_kind`에 `CPU`·`HEAP`·`GC_TIME`이 있는데 이건 **에이전트 단위** 지표다. 인시던트가 "어느 에이전트에서" 났는지 없으면 화면에서 보여줄 수가 없다. → `alert_events.agent_id`(NULL 허용) 추가.

②와 같은 논리로 괜찮다: 이벤트는 "이 규칙이 / 이 에이전트에서" 났다는 사실 둘을 가리킨다. `event.agent.application == event.rule.application`은 탐지기가 보장할 불변식이다.

### 기능 대조표

| 핵심기능 | 필요한 PG 데이터 | 있는가 |
|---|---|---|
| 1 · 트레이스 추적 | 서비스 목록, 에이전트 등록 | ✅ `applications` `agents` (신호 자체는 CH) |
| 2 · 알람 | 규칙, 채널, 규칙↔채널, 인시던트, 전송 이력 | ✅ 5개 테이블. 에이전트 단위 인시던트는 ③에서 보강 |
| 3 · 시스템 메트릭 | 스레드 덤프 요청 상태 | ✅ `thread_dump_requests` (본문 위치는 Q16) |
| 4 · 히트맵 | 없음 — CH 집계만 | ✅ (PG 불필요) |
| 5 · config UI | 샘플링률·모듈·로그레벨 / 경보 임계값 | ✅ `application_configs` / `alert_rules.threshold` |
| + 파수꾼 | **없음 — 의도적** | ✅ 파수꾼은 우리 PG가 죽어도 알려야 한다. 설정은 파일/환경변수, 상태는 자기 메모리. 이 ERD에 넣으면 목적이 무너진다 (ADR `#01` 3평면) |
| 로그인 | 사용자·역할 | ✅ `users` |
