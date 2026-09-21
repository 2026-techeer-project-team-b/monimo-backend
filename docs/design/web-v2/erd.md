### applications (PG, 소유: API 서버)

> **applications** · 감시 대상 서비스 · 소유: API 서버

| 컬럼(영어) | 한국어 | 타입 | 키 | Null | 설명 · 쓰이는 기능 |
|---|---|---|---|---|---|
| id | 내부 번호 | BIGSERIAL | PK | NN | 표끼리 잇는 번호표. 다른 표의 FK가 이 값을 가리킨다 |
| application_uuid | 외부 공개 식별자 | UUID | UK | NN | 화면 주소·API에 나가는 값. 순번을 숨기려고 따로 둔다 |
| name | 서비스 이름 | VARCHAR(100) | UK | NN | CH `service_name`과 같은 글자. 서버맵·히트맵·URL 통계 등 모든 화면이 이 이름으로 CH를 조회한다(핵심기능 1·4) |
| display_name | 화면 표시명 | VARCHAR(200) |  | NULL | 사람이 읽기 좋은 이름. 화면 목록에만 쓴다 |
| description | 설명 | TEXT |  | NULL | 이 서비스가 무슨 일을 하는지 메모. 서비스 상세 화면 |
| created_at | 만든 시각 | TIMESTAMPTZ |  | NN | 등록 시각 |
| updated_at | 고친 시각 | TIMESTAMPTZ |  | NN | 마지막 편집 시각 |

### agents (PG, 소유: 적재 처리기 · 탐지)

> **agents** · 에이전트 인스턴스(파드) · 소유: 적재 처리기 · 탐지

| 컬럼(영어) | 한국어 | 타입 | 키 | Null | 설명 · 쓰이는 기능 |
|---|---|---|---|---|---|
| id | 내부 번호 | BIGSERIAL | PK | NN | 표끼리 잇는 번호표 |
| agent_uuid | 외부 공개 식별자 | UUID | UK | NN | 화면 주소·API에 나가는 값 |
| application_id | 소속 서비스 | BIGINT | FK → `applications.id` | NN | 이 파드가 어느 서비스의 것인지. 서비스별 파드 목록 화면 |
| agent_key | 에이전트 식별 문자열 | VARCHAR(100) | UK | NN | 에이전트가 스스로 붙여 보내는 이름. **CH** `agent_id` **와 같은 값**이다. PG에서는 숫자 FK인 `agent_id`와 헷갈리지 않게 `agent_key`로 부른다 |
| hostname | 호스트 이름 | VARCHAR(255) |  | NULL | 파드 이름. 인스펙터 화면에서 "어느 기계인지" 보여 준다(핵심기능 3) |
| ip | IP 주소 | INET |  | NULL | 파드 IP. 수집기가 연결된 통로에서 알아내 메시지에 붙여 보내고, 적재 처리기가 에이전트를 등록할 때 함께 채운다. 파드 이름이 비슷할 때 어느 파드인지 구분하는 단서(핵심기능 3 화면 표시용). 스레드 덤프 요청은 이 주소로 가지 않고 수집기를 거친다 |
| jvm_version | 자바 버전 | VARCHAR(50) |  | NULL | 예: `17.0.9`. 문제 원인을 볼 때 참고하는 환경 정보 |
| agent_version | 에이전트 버전 | VARCHAR(50) |  | NULL | OTel Java Agent 버전 + 우리 Extension 버전. 파드마다 버전이 섞였는지 확인하는 데 쓴다 |
| status | 생존 상태 | VARCHAR(20) |  | NN | `UP` / `DOWN` / `UNKNOWN`. 탐지가 "CH에 90초 동안 이 파드의 데이터가 없음" 규칙으로 DOWN 처리한다(핵심기능 2의 AGENT_DOWN 규칙) |
| first_seen_at | 처음 본 시각 | TIMESTAMPTZ |  | NULL | 이 파드가 처음 등록된 시각. 배포 시점을 가늠하는 데 쓴다 |
| created_at | 만든 시각 | TIMESTAMPTZ |  | NN | 줄이 생긴 시각 |
| updated_at | 고친 시각 | TIMESTAMPTZ |  | NN | `status`가 바뀔 때 갱신된다 |

### application_configs (PG, 소유: API 서버)

> **application_configs** · 앱 설정 · 소유: API 서버

| 컬럼(영어) | 한국어 | 타입 | 키 | Null | 설명 · 쓰이는 기능 |
|---|---|---|---|---|---|
| id | 내부 번호 | BIGSERIAL | PK | NN | 표끼리 잇는 번호표 |
| application_config_uuid | 외부 공개 식별자 | UUID | UK | NN | 설정 화면 API에 나가는 값(핵심기능 5) |
| application_id | 대상 서비스 | BIGINT | FK → `applications.id`, UK | NN | UK라서 서비스당 딱 한 줄만 생긴다(1:1) |
| sampling_rate | 샘플링 비율 | NUMERIC(5,4) |  | NN | `0.0100` = 100건 중 1건만 추적. 수집기가 이 값으로 스팬을 걸러낸다(핵심기능 1·5) |
| version | 설정 판 번호 | INT |  | NN | 고칠 때마다 +1. 수집기가 "내가 든 설정이 최신인가"를 이 숫자로 판단한다(핵심기능 5) |
| updated_by | 고친 사람 | BIGINT | FK → `users.id` | NULL | 설정 화면에서 누가 마지막으로 바꿨는지. 로그인한 사용자를 가리킨다 |
| created_at | 만든 시각 | TIMESTAMPTZ |  | NN | 설정이 처음 생긴 시각 |
| updated_at | 고친 시각 | TIMESTAMPTZ |  | NN | 마지막으로 값이 바뀐 시각. 수집기가 30초마다 읽어갈 때 "언제 것인지" 확인하는 기준 |

### alert_rules (PG, 소유: API 서버)

> **alert_rules** · 경보 규칙 · 소유: API 서버

| 컬럼(영어) | 한국어 | 타입 | 키 | Null | 설명 · 쓰이는 기능 |
|---|---|---|---|---|---|
| id | 내부 번호 | BIGSERIAL | PK | NN | 표끼리 잇는 번호표 |
| alert_rule_uuid | 외부 공개 식별자 | UUID | UK | NN | 규칙 편집 화면 API에 나가는 값 |
| application_id | 대상 서비스 | BIGINT | FK → `applications.id` | NN | 어느 서비스를 감시하는 규칙인지 |
| name | 규칙 이름 | VARCHAR(200) |  | NN | 사람이 알아볼 이름. 알림 메시지 제목에도 들어간다 |
| metric_kind | 무엇을 볼지 | VARCHAR(30) |  | NN | `5XX_RATE` / `4XX_RATE` / `P95_LATENCY` / `CPU` / `HEAP` / `GC_TIME` / `AGENT_DOWN`. `5XX_RATE`·`4XX_RATE`·`P95_LATENCY`는 CH `service_health_1m`, `CPU`·`HEAP`·`GC_TIME`은 `metrics_1m`, `AGENT_DOWN`은 "CH에 데이터가 90초간 없음"으로 판정한다(ADR `#40`) |
| operator | 비교 방식 | VARCHAR(5) |  | NN | `GT`(초과) / `GTE`(이상) / `LT`(미만) / `LTE`(이하) |
| threshold | 기준값 | NUMERIC(12,4) |  | NN | 넘으면 터지는 선. 설정 화면에서 재배포 없이 고친다(핵심기능 5) |
| window_sec | 평가 구간(초) | INT |  | NN | "몇 초 동안의 값을 모아 볼지". 300이면 5분 평균을 본다 |
| severity | 심각도 | VARCHAR(20) |  | NN | `CRITICAL` / `WARNING` / `INFO`. 알림 문구와 채널 선택에 쓴다 |
| enabled | 켜짐 | BOOLEAN |  | NN, 기본 true | 규칙을 잠시 끌 때 false. **줄을 지우지 않고 끄기만 한다** — 지우면 과거 경보 이력이 부모를 잃는다 |
| created_at | 만든 시각 | TIMESTAMPTZ |  | NN | 규칙을 만든 시각 |
| updated_at | 고친 시각 | TIMESTAMPTZ |  | NN | 기준값·구간을 마지막으로 바꾼 시각 |

### alert_channels (PG, 소유: API 서버)

> **alert_channels** · 알림 채널 · 소유: API 서버

| 컬럼(영어) | 한국어 | 타입 | 키 | Null | 설명 · 쓰이는 기능 |
|---|---|---|---|---|---|
| id | 내부 번호 | BIGSERIAL | PK | NN | 표끼리 잇는 번호표 |
| alert_channel_uuid | 외부 공개 식별자 | UUID | UK | NN | 채널 관리 화면 API에 나가는 값 |
| name | 채널 이름 | VARCHAR(100) |  | NN | "백엔드-알람방"처럼 사람이 고를 이름 |
| type | 채널 종류 | VARCHAR(20) |  | NN | `SLACK` / `EMAIL` / `WEBHOOK` / `PAGERDUTY` 4종. 알림이 이 값으로 어떤 방식으로 보낼지 고른다 |
| config | 채널별 설정 | JSONB |  | NN | 훅 주소·수신 메일 주소처럼 종류마다 다른 값을 한 칸에 담는다 |
| enabled | 켜짐 | BOOLEAN |  | NN, 기본 true | 잠시 안 보낼 때 false. 여기도 **줄을 지우지 않는다**(전송 이력이 이 줄을 가리키기 때문) |
| created_at | 만든 시각 | TIMESTAMPTZ |  | NN | 채널을 등록한 시각 |
| updated_at | 고친 시각 | TIMESTAMPTZ |  | NN | 설정을 마지막으로 바꾼 시각 |

### alert_rule_channels (PG, 소유: API 서버)

> **alert_rule_channels** · 규칙-채널 연결 · 소유: API 서버

| 컬럼(영어) | 한국어 | 타입 | 키 | Null | 설명 · 쓰이는 기능 |
|---|---|---|---|---|---|
| id | 내부 번호 | BIGSERIAL | PK | NN | 번호표. UUID는 두지 않는다 |
| alert_rule_id | 규칙 | BIGINT | FK → `alert_rules.id` | NN | 어느 규칙인지 |
| alert_channel_id | 채널 | BIGINT | FK → `alert_channels.id` | NN | 어느 채널인지. `(alert_rule_id, alert_channel_id)`에 복합 UK를 걸어 같은 짝이 두 번 생기지 않게 한다 |
| created_at | 만든 시각 | TIMESTAMPTZ |  | NN | 연결한 시각 |

### alert_events (PG, 소유: 탐지)

> **alert_events** · 경보 이벤트(인시던트) · 소유: 탐지

| 컬럼(영어) | 한국어 | 타입 | 키 | Null | 설명 · 쓰이는 기능 |
|---|---|---|---|---|---|
| id | 내부 번호 | BIGSERIAL | PK | NN | 표끼리 잇는 번호표 |
| alert_event_uuid | 외부 공개 식별자 | UUID | UK | NN | 경보 상세 화면 주소에 들어가는 값 |
| alert_rule_id | 터진 규칙 | BIGINT | FK → `alert_rules.id` | NN | 어떤 규칙 때문에 울렸는지 |
| agent_id | 터진 파드 | BIGINT | FK → `agents.id` | NULL | CPU·힙·GC처럼 파드 단위 규칙이면 그 파드, 5xx 비율·p95처럼 서비스 단위 규칙이면 빈칸 |
| fingerprint | 중복 억제 키 | VARCHAR(64) |  | NN | 같은 규칙 + 같은 대상이면 같은 값이 나오는 지문. 같은 일로 알림이 100번 가는 것을 막는다 |
| state | 상태 | VARCHAR(20) |  | NN | `FIRING`(울리는 중) / `RESOLVED`(가라앉음). 화면의 "진행 중" 목록 기준 |
| observed_value | 당시 실측값 | NUMERIC(12,4) |  | NULL | 터진 순간의 실제 숫자. 알림 문구에 "기준 1%, 실제 4.2%"로 들어간다 |
| fired_at | 울린 시각 | TIMESTAMPTZ |  | NN | 사건 시작 시각. 화면 정렬 기준 |
| resolved_at | 가라앉은 시각 | TIMESTAMPTZ |  | NULL | 아직 진행 중이면 빈칸. 채워지면 "얼마나 오래 아팠나"를 계산할 수 있다 |
| created_at | 만든 시각 | TIMESTAMPTZ |  | NN | 줄이 기록된 시각 |

### notification_history (PG, 소유: 알림)

> **notification_history** · 알림 전송 이력 · 소유: 알림

| 컬럼(영어) | 한국어 | 타입 | 키 | Null | 설명 · 쓰이는 기능 |
|---|---|---|---|---|---|
| id | 내부 번호 | BIGSERIAL | PK | NN | 표끼리 잇는 번호표 |
| notification_uuid | 외부 공개 식별자 | UUID | UK | NN | 전송 이력 조회 API에 나가는 값 |
| alert_event_id | 어떤 사건 | BIGINT | FK → `alert_events.id` | NN | 어떤 경보를 보낸 건지 |
| alert_channel_id | 어떤 채널 | BIGINT | FK → `alert_channels.id` | NN | 어디로 보냈는지. 규칙-채널 연결을 거치지 않고 **채널을 직접** 가리킨다. 나중에 규칙에서 채널을 빼도 과거 영수증이 살아남게 하려는 것 |
| result | 결과 | VARCHAR(20) |  | NN | `SUCCESS` / `FAIL`. 화면의 전송 실패 표시 |
| retry_count | 재시도 횟수 | INT |  | NN, 기본 0 | 몇 번 다시 시도했는지. 채널이 불안정한지 가늠한다 |
| response | 채널 응답 원문 | TEXT |  | NULL | 슬랙·메일 서버가 돌려준 말 그대로. 실패 원인을 찾을 때 본다 |
| sent_at | 보낸 시각 | TIMESTAMPTZ |  | NN | 실제 발송 시각. "터진 뒤 몇 초 만에 알렸나"를 재는 기준 |
| created_at | 만든 시각 | TIMESTAMPTZ |  | NN | 줄이 기록된 시각 |

### users (PG, 소유: API 서버)

> **users** · 사용자 · 소유: API 서버

| 컬럼(영어) | 한국어 | 타입 | 키 | Null | 설명 · 쓰이는 기능 |
|---|---|---|---|---|---|
| id | 내부 번호 | BIGSERIAL | PK | NN | 표끼리 잇는 번호표 |
| user_uuid | 외부 공개 식별자 | UUID | UK | NN | 사용자 API에 나가는 값 |
| email | 이메일 | VARCHAR(255) | UK | NN | 로그인 아이디. 스레드 덤프를 요청하면 CH `thread_dumps.requested_by`에 이 주소가 남는다(로그인 · 핵심기능 3) |
| password_hash | 비밀번호 해시 | VARCHAR(255) |  | NN | 비밀번호 원문은 저장하지 않는다. 되돌릴 수 없게 섞은 값만 둔다(로그인) |
| name | 이름 | VARCHAR(100) |  | NULL | 화면 오른쪽 위에 보여 줄 표시용 이름 |
| role | 역할 | VARCHAR(20) |  | NN | `ADMIN`(설정·규칙 편집 가능) / `VIEWER`(보기만). 설정 화면 접근 판정에 쓴다(핵심기능 5) |
| created_at | 만든 시각 | TIMESTAMPTZ |  | NN | 가입 시각 |
| updated_at | 고친 시각 | TIMESTAMPTZ |  | NN | 정보를 마지막으로 바꾼 시각 |

## [참고자료, 표 수에 미포함] **CH 데이터 타입 정리** — 이 문서의 CH 표에 나오는 타입 전부

| 타입 | 쉽게 말하면 | 어디에 썼나 |
|---|---|---|
| `DateTime` | 초 단위 날짜+시각. 1분·1시간 단위로 묶은 집계 표에는 초까지면 충분하다 | `ts_min`, `ts_hour`, `metrics_raw.ts` |
| `DateTime64(3)` | 괄호 숫자 = 소수점 아래 자릿수. (3)은 밀리초(1000분의 1초)까지 적는다 | `transactions.start_time`, `logs.ts`, `thread_dumps.requested_at` |
| `DateTime64(9)` | 나노초(10억분의 1초)까지. 0.3ms짜리 스팬도 순서가 뭉개지지 않게 콜트리를 그리려고 | `spans.start_time`, `events.ts` |
| `LowCardinality(String)` | 겉은 글자, 속은 사전+번호. "payment"를 100만 번 쓰는 대신 사전에 한 번 적고 줄엔 번호만 적어 정수만큼 가볍다. **종류가 적은 글자에만** 쓴다 | `service_name`, `agent_id`, `span_name`, `metric_name`, `level` 등 29곳 |
| `String` | 보통 글자. 길이 제한 숫자를 안 적는다. 줄마다 값이 다 다른 것(`trace_id`)에 쓴다. "없음"은 NULL 대신 빈 글자 `''` | `trace_id`, `span_id`, `parent_span_id`, `message`, `dump` |
| `Enum8('A'=0,'B'=1,…)` | 정해진 값 몇 개 중 하나. 저장은 1바이트 숫자, 읽을 땐 이름. 오타 값은 아예 못 들어간다 | `span_kind`, `status_code`, `callee_kind` |
| `UInt8` / `UInt16` / `UInt32` / `UInt64` | 음수 없는 정수(U = Unsigned). 숫자는 비트 크기 — 8은 0\~255, 16은 0\~6만5천, 32는 0\~42억, 64는 사실상 무제한. 컬럼마다 딱 맞는 크기를 고른다 | `is_error`(8), `http_status`(16), `duration_ms`(32), `duration_ns`·`cnt`(64) |
| `Float64` | 소수점 있는 숫자(64비트). CPU 45.3%처럼 정수가 아닌 측정값 | `metrics_raw.value`, 롤업의 avg·min·max |
| `Map(LowCardinality(String), String)` | 키-값 주머니. `{'http.method':'GET', 'db.system':'mysql'}`처럼 줄마다 다른 꼬리표를 컬럼을 미리 안 정하고 담는다. 조회는 `attributes['http.method']` | `spans.attributes`, `metrics_raw.attributes`, `logs.attributes` |
| `Nested(…)` | 줄 안에 들어간 작은 표. 스팬 하나에 딸린 사건(예외·재시도) 여러 개를 별도 표·JOIN 없이 같은 줄에 배열로 붙인다. 실제론 `events.ts[]`·`events.name[]`처럼 배열 여러 개가 나란히 저장 | `spans.events` |
| `AggregateFunction(count)` | "세는 중"인 상태를 저장. 나중에 여러 줄을 합쳐도 정확히 세지도록 결과가 아니라 **계산 중간 상태**를 접어 둔다. 볼 때 `countMerge()`로 펼친다 | `url_stats_1m.cnt`, `service_health_1m.cnt` |
| `AggregateFunction(sum, UInt8)` | "더하는 중" 상태. 에러 건수처럼 0/1을 누적. 볼 때 `sumMerge()` | `err_cnt` |
| `AggregateFunction(avg / min / max, Float64)` | 평균·최소·최대의 중간 상태. 평균은 "합과 개수"를 따로 들고 있어야 나중에 합칠 수 있어서 결과값 대신 상태로 둔다. 볼 때 `avgMerge()` 등 | `metrics_1m`·`metrics_1h`의 `avg_v`·`min_v`·`max_v` |
| `AggregateFunction(argMax, Float64, DateTime)` | "시각이 가장 큰 줄의 값" = 마지막 값. 두 인자는 (가져올 값, 비교할 기준). 볼 때 `argMaxMerge()` | `last_v` |
| `AggregateFunction(quantilesTDigest(0.5,0.95,0.99), UInt64)` | 백분위(p50·p95·p99)의 중간 상태. 백분위는 값을 다 모아야 계산되는데, TDigest는 분포를 작은 요약으로 접어 두고 나중에 합쳐도 거의 정확하다. 볼 때 `quantilesTDigestMerge()` | `dur_q` |

| 표기 | 쉽게 말하면 | 어디에 |
|---|---|---|
| `CODEC(Delta, ZSTD(1))` | 압축 방법 2단. Delta = 앞 줄과의 차이만 저장(시각처럼 조금씩 느는 값에 유리), ZSTD(1) = 지퍼백 압축, 숫자 1은 "빠르게·가볍게"(22가 최대) | `spans.start_time` |
| `CODEC(DoubleDelta, ZSTD(1))` | "차이의 차이"를 저장. 15초 간격처럼 간격이 일정한 시각은 차이가 늘 같아서 거의 0만 남는다 | `metrics_raw.ts` |
| `CODEC(Gorilla, ZSTD(1))` | 소수점 값 전용 압축. CPU 45.3 → 45.4처럼 조금씩 변하는 측정값을 비트 단위로 줄인다(페이스북 시계열 DB에서 온 방식) | `metrics_raw.value` |
| `CODEC(ZSTD(3))` | 긴 글자는 Delta가 의미 없어 ZSTD만, 대신 조금 세게(3). 로그 본문·덤프처럼 크고 반복 많은 텍스트용 | `logs.message`, `thread_dumps.dump` |
| `DEFAULT 0` / `DEFAULT ''` | 값이 없을 때 넣을 기본값. CH는 NULL을 쓰면 별도 파일이 생겨 느려지므로 0이나 빈 글자로 "없음"을 표현한다 | `http_status`, `trace_id`(logs), `peer_address` |
| `[bf]` = `INDEX … TYPE bloom_filter` | 스킵 인덱스. 정렬 키에 없는 컬럼(`trace_id`)으로 찾을 때 덩어리마다 "여기엔 없음"을 빨리 판정하는 쪽지. 없으면 3일치를 다 훑는다 | `spans.trace_id`, `logs.trace_id` |
| `[minmax]` = `INDEX … TYPE minmax` | 덩어리마다 최소·최대만 적어 둔 쪽지. "1초 넘는 스팬"을 찾을 때 최대가 1초 미만인 덩어리는 건너뛴다 | `spans.duration_ns` |

### spans (CH, 읽는 화면: 콜트리)

> **spans** · 스팬 원본 · 읽는 화면: 콜트리

**저장 메타데이터**

| 항목 | 값 |
|---|---|
| 엔진 | MergeTree (원본을 그대로 쌓는다) |
| ORDER BY | (service_name, span_name, toDateTime(start_time)) |
| PARTITION BY | toDate(start_time) — 하루 한 상자 |
| TTL (로컬 → S3 → 삭제) | 3일 디스크 → 그 뒤 90일 S3 → 93일에 삭제 |
| MV 출처와 조건 | 원본. 적재 처리기가 배치로 INSERT한다 |
| 읽는 화면 | 콜트리(트레이스 상세). 스캐터·URL 통계·서버맵·경보는 여기서 파생된 표를 읽는다 |

**컬럼 목록**

| 묶음 | 컬럼(영어) | 한국어 | 타입 | 🔑 | 설명 |
|---|---|---|---|---|---|
| 요청 묶기·나무 | trace_id | 요청 한 건의 번호 | String [bf] |  | 요청 하나가 서비스 3곳을 지나면 스팬이 3줄로 흩어진다. 이 번호가 같은 줄을 모아야 '그 요청 하나'가 된다. 없으면 콜트리 자체를 못 그린다 |
|  | span_id | 이 구간의 번호 | String |  | 줄 하나하나에 붙는 이름표. 자식 구간이 이 값을 부모로 적기 때문에, 없으면 콜트리에서 누가 누구를 불렀는지 이어 붙일 수가 없다 |
|  | parent_span_id | 부모 구간 번호 | String |  | '나를 부른 구간이 누구'를 적는 칸. 이걸로 나무 모양(들여쓰기)이 만들어진다. 비어 있으면 맨 처음 구간(루트)이라 `transactions`가 이 조건으로 골라낸다 |
| 언제·얼마나 | start_time | 시작 시각 | DateTime64(9) / (나도초) / CODEC(Delta, ZSTD(1)) / (압축 방법) | 🔑3 | 콜트리에서 막대가 왼쪽 어디에서 시작하는지를 정한다. 나노초까지 적지 않으면 순식간에 끝난 구간들의 앞뒤 순서가 뒤섞여 보인다 |
|  | duration_ns | 걸린 시간(나노초) | UInt64 [minmax] / (음수 없음) |  | 막대의 길이, 곧 '얼마나 오래 걸렸나'다. 이 값이 없으면 느린 구간을 범위로 골라낼 수도, 스캐터 차트의 세로축을 만들 수도 없다 |
| 어디서 | service_name | 서비스 이름 | LowCardinality(String) / (보이는 건 문자열 dic으로 저장) / (같은 데이터를 숫자로 payment=3 같이) | 🔑1 | 이 구간이 어느 서비스에서 났는지. PG `applications.name`과 글자가 똑같아야 화면에서 '주문 서비스'를 고를 때 이 줄들이 딸려 나온다 |
|  | agent_id | 파드 식별자 | LowCardinality(String) |  | 같은 서비스라도 파드는 여러 대다. 이게 없으면 '3번 파드만 느리다'를 못 가려내고 서비스 전체가 느린 것처럼 보인다 |
|  | span_name | 구간 이름 | LowCardinality(String) | 🔑2 | `GET /api/orders/{id}` 같은 URL 틀. 이걸로 묶어야 URL 통계 화면에 줄 제목이 생긴다. 주소를 그대로 두면 주문번호마다 다른 줄이 되어 통계가 안 된다 |
| 종류와 결과 | span_kind | 구간 종류 | Enum8(INTERNAL/SERVER/CLIENT/PRODUCER/CONSUMER) / (저장은 1byte(숫자) 읽은 땐 문자) |  | 요청을 받은 쪽(SERVER)인지 남을 부른 쪽(CLIENT)인지. 이걸로 갈라야 URL 통계는 받은 쪽만, 서버맵은 부른 쪽만 세서 같은 호출을 두 번 세지 않는다 |
|  | status_code | 성공/실패 | Enum8(UNSET/OK/ERROR) |  | 이 구간이 성공했나 실패했나. 히트맵의 빨간 칸과 경보의 에러 비율이 모두 이 값을 센 결과다 |
|  | http_status | HTTP 응답 코드 | UInt16 DEFAULT 0 |  | 500·404 같은 응답 숫자. 에러 중에서도 5xx만 골라 세는 경보 규칙은 이 값이 없으면 만들 수 없다 |
| 서버맵 도착점 | peer_address | 부른 상대 주소 | String DEFAULT '' |  | 부른 상대가 DB나 바깥 API일 때 그 주소. 이게 없으면 서버맵에서 우리 서비스 밖으로 나가는 화살표가 아예 안 그려진다 |
|  | peer_service | 부른 상대가 우리 서비스면 그 이름 | LowCardinality(String) |  | 주소를 그대로 서버맵에 그리면 'payment-svc:8080' 동그라미가 생기지 '결제 서비스' 동그라미로 이어지지 않는다. 그래서 적재 처리기가 주소를 `applications.name`과 맞춰 서비스 이름으로 바꿔 채운다. DB·외부면 빈칸 |
| 주머니 | attributes | 추가 정보 꾸러미 | Map(LowCardinality(String), String) |  | SQL 문장·사용자 ID처럼 스팬마다 달라서 컬럼으로 미리 못 정하는 것들. 상세 패널에서만 보니 주머니에 넣는다 |
|  | events | 구간 안에서 벌어진 일들 | Nested(ts DateTime64(9), name LowCardinality(String), attributes Map(...)) |  | 구간 안에서 터진 예외 스택트레이스 같은 사건. 따로 표를 만들면 콜트리를 펼칠 때마다 두 표를 합쳐야 해서, 같은 줄 안에 접어 담는다 |

### metrics_raw (CH, 읽는 화면: 인스펙터)

> **metrics_raw** · 메트릭 원본(long 형식) · 읽는 화면: 인스펙터

**저장 메타데이터**

| 항목 | 값 |
|---|---|
| 엔진 | MergeTree |
| ORDER BY | (service_name, agent_id, metric_name, series_hash, ts) |
| PARTITION BY | toYYYYMMDD(ts) — 하루 한 상자 |
| TTL (로컬 → S3 → 삭제) | 15일 디스크 → 그 뒤 90일 S3 → 105일에 삭제 |
| MV 출처와 조건 | 원본. 여기서 `metrics_1m`이 파생된다 |
| 읽는 화면 | 인스펙터(최근 15일 구간의 촘촘한 그래프) |

**컬럼 목록**

| 묶음 | 컬럼(영어) | 한국어 | 타입 | 🔑 | 설명 |
|---|---|---|---|---|---|
| 어디서 | service_name | 서비스 이름 | LowCardinality(String) | 🔑1 | 어느 서비스의 몸 상태인지 가르는 값. 이게 없으면 인스펙터에서 서비스를 골라도 그래프를 못 찾는다. PG `applications.name`과 글자가 같다 |
|  | agent_id | 파드 식별자 | LowCardinality(String) | 🔑2 | 인스펙터는 파드 한 대를 골라서 본다. 파드마다 힙 사용량이 다른데 이게 없으면 여러 대 값이 한 그래프에 뒤엉킨다 |
| 무엇을 쟀나 | metric_name | 지표 이름 | LowCardinality(String) | 🔑3 | '힙 사용량'인지 'CPU'인지를 적는 칸. 한 줄에 지표 하나만 담는 구조라, 이 이름이 없으면 그 숫자가 무엇을 잰 값인지 알 수 없다 |
|  | series_hash | 꾸러미 요약 숫자 | UInt64 | 🔑4 | `attributes` 주머니를 숫자 하나로 요약한 값. 주머니(Map)는 정렬 키에 못 들어가는데 'G1 Old' GC와 'G1 Young' GC를 다른 줄로 나눠야 해서 이 숫자를 정렬 키에 넣는다 |
|  | attributes | 지표 부가 정보 | Map(LowCardinality(String), String) |  | 같은 지표를 더 잘게 나누는 꼬리표. 'GC 시간'만 봐서는 어느 GC인지 모르는데 이 주머니가 'G1 Old Generation'이라고 알려 준다 |
| 언제 | ts | 측정 시각 | DateTime CODEC(DoubleDelta, ZSTD(1)) | 🔑5 | 15초마다 찍히는 측정 시각. 인스펙터 그래프의 가로축이고, 1분 롤업도 이 시각을 1분 칸으로 접어 만든다 |
| 값 | value | 측정값 | Float64 CODEC(Gorilla, ZSTD(1)) |  | 실제로 잰 숫자. 다른 컬럼이 전부 '누가·무엇을·언제'라면 이 칸만 답이라서, 그래프에 찍히는 점이 바로 이 값이다 |

### logs (CH, 읽는 화면: 로그 검색)

> **logs** · 로그 원본 · 읽는 화면: 로그 검색

**저장 메타데이터**

| 항목 | 값 |
|---|---|
| 엔진 | MergeTree |
| ORDER BY | (service_name, toDateTime(ts)) |
| PARTITION BY | toDate(ts) — 하루 한 상자 |
| TTL (로컬 → S3 → 삭제) | 7일 디스크 → 그 뒤 90일 S3 → 97일에 삭제 |
| MV 출처와 조건 | 원본. 파생 표 없음 |
| 읽는 화면 | 로그 검색 |

**컬럼 목록**

| 묶음 | 컬럼(영어) | 한국어 | 타입 | 🔑 | 설명 |
|---|---|---|---|---|---|
| 요청과 잇기 | trace_id | 요청 한 건의 번호 | String DEFAULT '' [bf] |  | 콜트리에서 느린 구간을 찍고 '이 요청이 남긴 로그'로 바로 넘어갈 수 있는 이유가 이 번호다. 없으면 로그는 시각으로만 더듬어 뒤져야 한다 |
|  | span_id | 구간 번호 | String DEFAULT '' |  | 같은 요청 안에서도 정확히 어느 구간이 남긴 로그인지. 한 요청에 구간이 열 개면 로그도 섞이는데 이걸로 갈라낸다 |
| 언제 | ts | 기록 시각 | DateTime64(3) | 🔑2 | 로그 검색의 시간 필터 기준. 밀리초까지 적어야 같은 초에 쏟아진 로그의 앞뒤 순서가 유지된다 |
| 어디서 | service_name | 서비스 이름 | LowCardinality(String) | 🔑1 | 어느 서비스의 로그인지. 이게 없으면 검색 화면에서 서비스를 고를 수 없어 전체 로그를 통째로 뒤지게 된다. PG `applications.name`과 같은 글자다 |
|  | agent_id | 파드 식별자 | LowCardinality(String) |  | 파드 여러 대 중 어느 대가 남겼는지. 한 대만 이상할 때 그 파드 로그만 뽑아 보려면 있어야 한다 |
|  | logger | 기록한 클래스 | LowCardinality(String) |  | 어느 코드(클래스)가 남긴 로그인지. 검색 화면에서 관심 있는 모듈만 좁혀 보는 필터로 쓴다 |
|  | thread | 스레드 이름 | String |  | 같은 시각에 요청 여러 건이 섞여 찍힐 때 줄을 갈라 보는 단서. `trace_id`가 안 붙은 로그에서는 거의 유일한 실마리다 |
| 내용 | level | 로그 단계 | LowCardinality(String) |  | ERROR만 보고 싶을 때 쓰는 칸. 이게 없으면 INFO 수천 줄 사이에서 에러를 눈으로 찾아야 한다 |
|  | message | 로그 본문 | String CODEC(ZSTD(3)) |  | 사람이 실제로 읽는 본문. 길어서 세게 압축해 두고, 본문 단어 검색이 느려지면 그때 스킵 인덱스를 붙인다 |
| 주머니 | attributes | 추가 정보 꾸러미 | Map(LowCardinality(String), String) |  | 코드가 로그마다 붙인 꼬리표(MDC) 묶음. 주문번호처럼 미리 컬럼으로 정할 수 없는 값이라 주머니에 담는다 |

### thread_dumps (CH, 읽는 화면: 스레드 덤프)

> **thread_dumps** · 스레드 덤프 결과 · 읽는 화면: 스레드 덤프

**저장 메타데이터**

| 항목 | 값 |
|---|---|
| 엔진 | MergeTree |
| ORDER BY | (agent_id, requested_at) |
| PARTITION BY | toDate(requested_at) — 하루 한 상자 |
| TTL (로컬 → S3 → 삭제) | 3일 디스크 → 그 뒤 90일 S3 → 93일에 삭제 |
| MV 출처와 조건 | 원본. API 서버가 수집기 팬아웃으로 받은 결과를 INSERT (적재 처리기가 아님) |
| 읽는 화면 | 스레드 덤프 화면 |

**컬럼 목록**

| 묶음 | 컬럼(영어) | 한국어 | 타입 | 🔑 | 설명 |
|---|---|---|---|---|---|
| 어디서 | agent_id | 파드 식별자 | LowCardinality(String) | 🔑1 | 어느 파드를 찍은 사진인지. 스레드 덤프는 파드 한 대를 콕 집어 요청하는 기능이라, 이 값이 없으면 결과를 어느 파드 것으로 붙일지 모른다 |
|  | service_name | 서비스 이름 | LowCardinality(String) |  | 그 파드가 속한 서비스. 화면에서 서비스별로 덤프 이력을 모아 볼 때 쓴다 |
| 식별 | dump_uuid | 덤프 번호 | String |  | 덤프 한 건을 화면·API에서 가리키는 이름표. API 서버가 팬아웃 결과를 넣을 때 UUID를 만들어 채운다. 이게 없으면 파드+요청 시각 두 칸으로만 덤프를 지목해야 해서 주소가 지저분해진다(API 명세 `GET /thread-dumps/{dumpUuid}`) |
| 누가·언제 | requested_by | 요청한 사람 | String |  | 누가 눌렀나. PG `users.email` 글자를 그대로 적는다 — CH엔 FK가 없어서 이메일 문자열로 남긴다 |
|  | requested_at | 요청 시각 | DateTime64(3) | 🔑2 | 언제 찍은 사진인지. 요청 상태를 적는 별도 표가 없어서, 목록을 최신순으로 세우는 기준이 이 시각뿐이다 |
| 내용 | thread_count | 스레드 개수 | UInt16 |  | 찍은 순간의 스레드 수. 긴 본문을 펼치기 전에 목록에서 '평소보다 많다'를 먼저 알아채라고 따로 빼 둔 값이다 |
|  | dump | 덤프 본문 | String CODEC(ZSTD(3)) |  | 스레드별 스택트레이스 전체 글. 이게 이 기능의 결과물 자체라, 없으면 요청 기록만 남고 정작 볼 것이 없다 |

### transactions (CH, 읽는 화면: 스캐터 차트)

> **transactions** · 트랜잭션(루트 스팬) · 읽는 화면: 스캐터 차트

**저장 메타데이터**

| 항목 | 값 |
|---|---|
| 엔진 | MergeTree |
| ORDER BY | (service_name, start_time) |
| PARTITION BY | toDate(start_time) |
| TTL (로컬 → S3 → 삭제) | 3일 디스크 → 그 뒤 90일 S3 → 93일에 삭제 |
| MV 출처와 조건 | `spans` WHERE parent_span_id = '' (mv_transactions) — 부모가 없는 줄 = 요청의 첫 구간 |
| 읽는 화면 | 스캐터 차트. 여기서 다시 `heatmap_1m`이 파생된다 |

**컬럼 목록**

| 묶음 | 컬럼(영어) | 한국어 | 타입 | 🔑 | 설명 |
|---|---|---|---|---|---|
| 요청 잇기 | trace_id | 요청 한 건의 번호 | String |  | 스캐터 차트에서 점을 클릭하면 이 번호로 `spans`에 가서 콜트리를 펼친다. 없으면 점은 보이는데 눌러도 안이 안 열린다 |
| 언제·얼마나 | start_time | 시작 시각 | DateTime64(3) | 🔑2 | 스캐터 차트의 가로축. 점이 시간축 어디에 찍히는지를 정한다 |
|  | duration_ms | 걸린 시간(밀리초) | UInt32 |  | 스캐터 차트의 세로축. 위로 튄 점이 느린 요청이고, 그 점들을 드래그해 골라내는 것이 이 화면의 핵심이다 |
| 어디서 | service_name | 서비스 이름 | LowCardinality(String) | 🔑1 | 어느 서비스의 요청인지. 스캐터는 서비스 하나를 골라 보는 화면이라 이 값으로 걸러낸다 |
|  | agent_id | 파드 식별자 | LowCardinality(String) |  | 그 요청을 처리한 파드. 느린 점이 한 파드에만 몰려 있는지 확인할 때 쓴다 |
|  | span_name | 요청 이름 | LowCardinality(String) |  | `GET /api/orders/{id}` 같은 요청 이름. 점을 드래그해 나온 목록에서 '무슨 API였나'를 보여 준다 |
| 결과 | is_error | 실패 여부 | UInt8 |  | 1이면 실패. 스캐터에서 빨간 점으로 칠하는 기준이라, 없으면 실패한 요청이 성공한 점들 사이에 숨는다 |
|  | http_status | HTTP 응답 코드 | UInt16 |  | 500·404 같은 응답 숫자. 목록에서 실패 이유를 한눈에 가늠하게 해 준다 |

### heatmap_1m (CH, 읽는 화면: 히트맵)

> **heatmap_1m** · 히트맵 1분 버킷 · 읽는 화면: 히트맵

**저장 메타데이터**

| 항목 | 값 |
|---|---|
| 엔진 | SummingMergeTree (같은 칸의 건수를 더해서 한 줄로 합친다) |
| ORDER BY | (service_name, ts_min, latency_bucket, is_error) |
| PARTITION BY | toYYYYMM(ts_min) — 한 달 한 상자 |
| TTL (로컬 → S3 → 삭제) | 30일 디스크 → 그 뒤 90일 S3 → 120일에 삭제 |
| MV 출처와 조건 | `transactions` (mv_heatmap_1m, 이어달리기) — 1분 단위로 묶고 지연 구간별로 센다 |
| 읽는 화면 | 히트맵 |

**컬럼 목록**

| 묶음 | 컬럼(영어) | 한국어 | 타입 | 🔑 | 설명 |
|---|---|---|---|---|---|
| 칸의 좌표 | ts_min | 1분 단위 시각 | DateTime | 🔑2 | 히트맵의 가로축. 1분을 한 칸으로 접어야 하루치를 봐도 줄 수가 1,440개에서 멈춘다 |
|  | service_name | 서비스 이름 | LowCardinality(String) | 🔑1 | 어느 서비스의 히트맵인지 가르는 값. PG `applications.name`과 글자가 같아야 화면에서 고른 서비스와 이어진다 |
|  | latency_bucket | 지연 구간 번호 | UInt16 | 🔑3 | 50ms 폭으로 자른 칸 번호. 응답 시간을 그대로 두면 칸이 무한히 생겨서 세로축을 만들 수 없다 |
|  | is_error | 실패 여부 | UInt8 | 🔑4 | 성공한 요청과 실패한 요청을 다른 줄로 세는 칸. 같이 세면 히트맵에서 빨간 칸을 따로 칠할 수 없다 |
| 칸의 값 | cnt | 건수 | UInt64 |  | 그 칸에 들어온 요청 수이고 색의 진하기가 된다. 좌표 네 개가 같은 줄은 엔진이 알아서 이 숫자를 더해 한 줄로 합친다 |

### url_stats_1m (CH, 읽는 화면: URL 통계)

> **url_stats_1m** · URL 통계 1분 · 읽는 화면: URL 통계

**저장 메타데이터**

| 항목 | 값 |
|---|---|
| 엔진 | AggregatingMergeTree (계산 중간 상태를 접어 두고 나중에 합친다) |
| ORDER BY | (service_name, ts_min, span_name, agent_id) |
| PARTITION BY | toYYYYMM(ts_min) |
| TTL (로컬 → S3 → 삭제) | 30일 디스크 → 그 뒤 90일 S3 → 120일에 삭제 |
| MV 출처와 조건 | `spans` WHERE span_kind = 'SERVER' (mv_url_stats_1m) — 요청을 받은 쪽 구간만 센다 |
| 읽는 화면 | URL 통계 |

**컬럼 목록**

| 묶음 | 컬럼(영어) | 한국어 | 타입 | 🔑 | 설명 |
|---|---|---|---|---|---|
| 묶는 기준 | ts_min | 1분 단위 시각 | DateTime | 🔑2 | 이 값이 같은 줄끼리 한 줄로 합쳐진다. 1분 칸이 있어야 '방금 1분 동안'을 잘라 볼 수 있다 |
|  | service_name | 서비스 이름 | LowCardinality(String) | 🔑1 | 서비스별로 나눠 세는 기준. 없으면 모든 서비스의 호출이 한 줄로 합쳐져 어느 서비스 통계인지 알 수 없다 |
|  | span_name | URL 틀 | LowCardinality(String) | 🔑3 | 같은 URL끼리 묶어 세려면 있어야 한다. 없으면 서비스 전체가 한 줄로 뭉개져 어느 API가 느린지 못 본다 |
|  | agent_id | 파드 식별자 | LowCardinality(String) | 🔑4 | 파드별로도 나눠 세어 둔다. '특정 파드만 느린가'를 보려면 묶는 기준에 파드가 들어 있어야 한다 |
| 접어 둔 계산값 | cnt | 호출 수(중간 상태) | AggregateFunction(count) |  | 호출 수를 결과가 아니라 계산 중간 상태로 접어 둔다. 볼 때 `countMerge`로 펼치며, 1분 줄 열 개를 10분치로 합쳐도 값이 맞다 |
|  | err_cnt | 에러 수(중간 상태) | AggregateFunction(sum, UInt8) |  | 에러 수의 중간 상태. 볼 때 `sumMerge`로 펼치고 호출 수로 나누면 에러율이 나온다 |
|  | dur_q | 지연 백분위(중간 상태) | AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), UInt64) |  | p50·p95·p99를 결과로 굳히지 않고 계산 중간 상태로 접어 둔다. 백분위끼리는 더할 수 없어서, 볼 때 `quantilesTDigestMerge`로 펼쳐야 여러 분을 합쳐도 맞다 |

### server_map_1m (CH, 읽는 화면: 서버맵)

> **server_map_1m** · 서버맵 간선 1분 · 읽는 화면: 서버맵

**저장 메타데이터**

| 항목 | 값 |
|---|---|
| 엔진 | SummingMergeTree |
| ORDER BY | (ts_min, caller_service, callee_service, callee_kind) |
| PARTITION BY | toYYYYMM(ts_min) |
| TTL (로컬 → S3 → 삭제) | 30일 디스크 → 그 뒤 90일 S3 → 120일에 삭제 |
| MV 출처와 조건 | `spans` WHERE span_kind = 'CLIENT' (mv_server_map_1m) — 부른 쪽 = `service_name`, 불린 쪽 = `peer_service`(우리 서비스일 때) 또는 `peer_address`(DB·외부) |
| 읽는 화면 | 서버맵 |

**컬럼 목록**

| 묶음 | 컬럼(영어) | 한국어 | 타입 | 🔑 | 설명 |
|---|---|---|---|---|---|
| 언제 | ts_min | 1분 단위 시각 | DateTime | 🔑1 | 서버맵은 '지금 이 시간대'를 통째로 보는 화면이라 1분 칸이 맨 앞 기준이다. 없으면 어제 호출과 방금 호출이 한 그림에 섞인다 |
| 화살표 양 끝 | caller_service | 부른 쪽 | LowCardinality(String) | 🔑2 | 화살표가 나가는 동그라미. 부른 쪽이 없으면 선을 어디에서 시작할지 정할 수 없다 |
|  | callee_service | 불린 쪽 | LowCardinality(String) | 🔑3 | 화살표가 닿는 동그라미. 우리 서비스 이름이거나 DB·외부 주소가 들어간다 |
|  | callee_kind | 불린 쪽 종류 | Enum8(SERVICE/DB/EXTERNAL) | 🔑4 | 닿는 쪽이 우리 서비스인지 DB인지 바깥 API인지. 동그라미 모양을 다르게 그리는 기준이라, 없으면 DB도 서비스처럼 보인다 |
| 화살표에 적는 값 | cnt | 호출 수 | UInt64 |  | 1분 동안 몇 번 불렀나. 화살표 굵기가 된다. 양 끝이 같은 줄은 엔진이 더해 한 줄로 만든다 |
|  | err_cnt | 에러 수 | UInt64 |  | 그중 몇 번 실패했나. 화살표를 빨갛게 물들이는 기준이다 |
|  | sum_duration_ns | 걸린 시간 총합 | UInt64 |  | 평균을 저장하지 않고 합을 저장하는 이유 — 합끼리는 더할 수 있지만 평균끼리는 더할 수 없다. 볼 때 `cnt`로 나눈다 |

### service_health_1m (CH, 읽는 화면: 없음(탐지가 읽는다))

> **service_health_1m** · 서비스 건강 1분 · 읽는 화면: 없음(탐지가 읽는다)

**저장 메타데이터**

| 항목 | 값 |
|---|---|
| 엔진 | AggregatingMergeTree |
| ORDER BY | (service_name, ts_min) |
| PARTITION BY | toYYYYMM(ts_min) |
| TTL (로컬 → S3 → 삭제) | 30일 디스크 → 그 뒤 90일 S3 → 120일에 삭제 |
| MV 출처와 조건 | `spans` WHERE span_kind = 'SERVER' (mv_service_health_1m) — URL 구분 없이 서비스 단위로만 합친다 · http_status 대역별로 cnt_4xx·cnt_5xx도 함께 센다(`#40`) |
| 읽는 화면 | 없음. 탐지가 API 서버를 통해 주기 조회한다 |

**컬럼 목록**

| 묶음 | 컬럼(영어) | 한국어 | 타입 | 🔑 | 설명 |
|---|---|---|---|---|---|
| 묶는 기준 | ts_min | 1분 단위 시각 | DateTime | 🔑2 | 탐지가 '최근 N분'을 잘라 읽는 기준. 규칙의 `window_sec`이 이 1분 칸 수에 맞춰 계산된다 |
|  | service_name | 서비스 이름 | LowCardinality(String) | 🔑1 | 이 값이 같은 줄끼리 한 줄로 합쳐진다. 규칙에 적힌 대상 서비스와 이 이름으로 맞춰 본다 |
| 접어 둔 계산값 | cnt | 호출 수(중간 상태) | AggregateFunction(count) |  | 1분 동안의 호출 수를 중간 상태로 접어 둔 값. 볼 때 `countMerge`로 펼치며 5xx 비율의 분모가 된다 |
|  | err_cnt | 에러 수(중간 상태) | AggregateFunction(sum, UInt8) |  | 에러 수의 중간 상태. 볼 때 `sumMerge`로 펼친다. `status_code`가 ERROR인 스팬 수라 5xx와 세는 기준이 다르고, `5XX_RATE` 규칙의 분자는 `cnt_5xx`가 맡는다(ADR `#40`) |
|  | cnt_4xx | 4xx 수(중간 상태) | AggregateFunction(sum, UInt8) |  | HTTP 상태가 400\~499인 요청 수. 볼 때 `sumMerge(cnt_4xx)`. 호출 수와 나누면 4xx 비율 — `4XX_RATE` 규칙이 쓴다(ADR `#40`). 4xx는 부른 쪽 잘못, 5xx는 우리 잘못이라 따로 센다 |
|  | cnt_5xx | 5xx 수(중간 상태) | AggregateFunction(sum, UInt8) |  | HTTP 상태가 500\~599인 요청 수. 볼 때 `sumMerge(cnt_5xx)`. `5XX_RATE` 규칙의 분자(ADR `#40`). `err_cnt`(status_code=ERROR)와는 세는 기준이 다르다 |
|  | dur_q | 지연 백분위(중간 상태) | AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), UInt64) |  | 지연 백분위의 중간 상태. 백분위를 미리 숫자로 굳히면 여러 분을 합칠 때 틀리므로 접어 두고, `P95_LATENCY` 규칙이 펼쳐 읽는다 |

### metrics_1m (CH, 읽는 화면: 인스펙터(15일 이상) · 탐지)

> **metrics_1m** · 메트릭 1분 롤업 · 읽는 화면: 인스펙터(15일 이상) · 탐지

**저장 메타데이터**

| 항목 | 값 |
|---|---|
| 엔진 | AggregatingMergeTree |
| ORDER BY | (service_name, agent_id, metric_name, series_hash, ts_min) |
| PARTITION BY | toYYYYMM(ts_min) |
| TTL (로컬 → S3 → 삭제) | 90일 디스크 → 그 뒤 90일 S3 → 180일에 삭제 |
| MV 출처와 조건 | `metrics_raw` (mv_metrics_1m) — 1분 단위로 묶어 평균·최소·최대·마지막값을 만든다 |
| 읽는 화면 | 인스펙터(15일 이상 구간), 탐지(CPU·힙 규칙). 여기서 다시 `metrics_1h`가 파생된다 |

**컬럼 목록**

| 묶음 | 컬럼(영어) | 한국어 | 타입 | 🔑 | 설명 |
|---|---|---|---|---|---|
| 묶는 기준 | service_name | 서비스 이름 | LowCardinality(String) | 🔑1 | 이 값이 같은 줄끼리 한 줄로 접힌다. 어느 서비스의 지표인지 가르는 첫 기준이다 |
|  | agent_id | 파드 식별자 | LowCardinality(String) | 🔑2 | 파드 한 대씩 따로 접어 둔다. 여러 대를 섞어 평균 내면 한 대만 힙이 꽉 찬 상황이 묻혀 버린다 |
|  | metric_name | 지표 이름 | LowCardinality(String) | 🔑3 | 무슨 지표를 접은 줄인지. CPU와 힙 사용량이 한 줄로 합쳐지면 값이 뒤섞여 아무 의미가 없다 |
|  | series_hash | 꾸러미 요약 숫자 | UInt64 | 🔑4 | 같은 지표라도 'G1 Old'와 'G1 Young'처럼 갈래가 다르면 다른 줄이어야 한다. 원본의 주머니를 숫자로 접어 그 갈래를 구분한다 |
|  | ts_min | 1분 단위 시각 | DateTime | 🔑5 | 1분에 한 줄로 접는 시각 기준. 15초짜리 원본 네 줄이 여기서 한 줄이 되고, 인스펙터 그래프의 가로축이 된다 |
| 접어 둔 값 | avg_v | 평균(중간 상태) | AggregateFunction(avg, Float64) |  | 평균을 숫자로 굳히지 않고 계산 중간 상태로 접어 둔다. 볼 때 `avgMerge`로 펼친다. 평균끼리는 더할 수 없어서, 결과를 바로 저장하면 1시간 롤업에서 값이 틀어진다 |
|  | min_v | 최소(중간 상태) | AggregateFunction(min, Float64) |  | 1분 안의 최솟값 중간 상태. 볼 때 `minMerge`로 펼쳐 그래프의 아래 띠를 그린다 |
|  | max_v | 최대(중간 상태) | AggregateFunction(max, Float64) |  | 1분 안의 최댓값 중간 상태. 볼 때 `maxMerge`로 펼친다. 평균만 두면 순간 최고점이 깎여 사라진다 |
|  | last_v | 마지막값(중간 상태) | AggregateFunction(argMax, Float64, DateTime) |  | 그 1분의 마지막 관측값 중간 상태. 볼 때 `argMaxMerge`로 펼친다. 힙 사용량처럼 '지금 얼마인가'가 중요한 지표에 쓴다 |

### metrics_1h (CH, 읽는 화면: 1년 추세)

> **metrics_1h** · 메트릭 1시간 롤업 · 읽는 화면: 1년 추세

**저장 메타데이터**

| 항목 | 값 |
|---|---|
| 엔진 | AggregatingMergeTree |
| ORDER BY | (service_name, agent_id, metric_name, series_hash, ts_hour) |
| PARTITION BY | toYYYYMM(ts_hour) |
| TTL (로컬 → S3 → 삭제) | 1년 디스크 → 그 뒤 90일 S3 → 1년 3개월째에 삭제 |
| MV 출처와 조건 | `metrics_1m` (mv_metrics_1h, 이어달리기) — 1분 중간 상태를 그대로 한 시간 단위로 다시 합친다 |
| 읽는 화면 | 1년 추세 그래프 |

**컬럼 목록**

| 묶음 | 컬럼(영어) | 한국어 | 타입 | 🔑 | 설명 |
|---|---|---|---|---|---|
| 묶는 기준 | service_name | 서비스 이름 | LowCardinality(String) | 🔑1 | 1분 표와 같은 기준으로 한 번 더 접는다. 어느 서비스의 1년 추세인지 가르는 값이다 |
|  | agent_id | 파드 식별자 | LowCardinality(String) | 🔑2 | 파드별로는 그대로 두고 시간만 접는다. 파드를 섞어 버리면 '어느 파드가 조금씩 늘고 있나'를 볼 수 없다 |
|  | metric_name | 지표 이름 | LowCardinality(String) | 🔑3 | 1분 표와 같은 이름을 그대로 쓴다. 이름이 달라지면 1분 그래프와 1년 그래프가 서로 다른 지표가 된다 |
|  | series_hash | 꾸러미 요약 숫자 | UInt64 | 🔑4 | 같은 지표의 갈래 구분. 1분 표에서 쓰던 숫자를 그대로 이어받아 접는다 |
|  | ts_hour | 1시간 단위 시각 | DateTime | 🔑5 | 한 시간을 한 줄로 접는 시각 기준. 1년을 담아도 줄 수가 1분 표의 60분의 1이라 추세 그래프가 가볍다 |
| 접어 둔 값 | avg_v | 평균(중간 상태) | AggregateFunction(avg, Float64) |  | 1분 표의 중간 상태를 다시 합친 평균 중간 상태. 결과 숫자가 아니라 중간 상태라서 60개를 합쳐도 값이 정확하다. 볼 때 `avgMerge`로 펼친다 |
|  | min_v | 최소(중간 상태) | AggregateFunction(min, Float64) |  | 한 시간 안의 최솟값 중간 상태. 볼 때 `minMerge`로 펼친다 |
|  | max_v | 최대(중간 상태) | AggregateFunction(max, Float64) |  | 한 시간 안의 최댓값 중간 상태. 볼 때 `maxMerge`로 펼쳐 1년 중 최고점을 찾는다 |
|  | last_v | 마지막값(중간 상태) | AggregateFunction(argMax, Float64, DateTime) |  | 그 시간의 마지막 관측값 중간 상태. 볼 때 `argMaxMerge`로 펼친다 |

TOTAL_TABLES=20