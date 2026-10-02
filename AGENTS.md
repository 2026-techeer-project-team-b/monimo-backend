# AGENTS.md — 작업을 시작하기 전에 읽는 파일

> **사람이든 AI 도구든, 이 레포에서 무언가를 고치기 전에 이 파일을 먼저 읽는다.**
> 여기에는 레포 구조, 깨면 안 되는 규칙, 지금까지 한 일, 지금 막혀 있는 것이 들어 있다.
> 읽지 않고 시작하면 남의 파트를 건드리거나 이미 정해진 결정을 다시 하게 된다.
>
> 작업을 끝내면 **§5(지금까지 한 일)와 §6(막혀 있는 것)을 갱신해서 같은 PR 에 넣는다.** 갱신하지 않으면 다음 사람이 또 헤맨다.

## 0. 정본이 어디인가

| 무엇 | 정본 | 사본 |
|---|---|---|
| 설계 결정(ADR) | `docs/design/01-decisions.md` | 노션 |
| 미해결 질문 | `docs/design/02-open-questions.md` | — |
| API 명세 | 노션 「API 명세」 | `docs/design/web-v2/api-spec.md` |
| ERD | 노션 「ERD」 | `docs/design/web-v2/erd.md` |
| 현재 단계·다음 할 일 | `docs/design/00-index.md` | — |

`docs/design/references/` 아래 두 문서(`erd-clickhouse-guide.md`, `erd-pg-input-sheet.md`)는 **확정 ADR 보다 낡았다.** 머리말에 "정본" 이라 적혀 있어도 믿지 말고 위 표를 따른다.

## 1. 레포 한눈에

Kotlin · Java 17 · Spring Boot 3.5 · Gradle 멀티모듈. 버전은 `gradle/libs.versions.toml` 한 곳에서만 정한다.

| 모듈 | 하는 일 | 포트 |
|---|---|---|
| `common/` | 공유 모델 · Kafka 메시지 형식 · 에러 코드. **Entity · Repository · Service 금지** | — |
| `collector/` | OTLP gRPC 수신 → 샘플링 → Kafka `raw` 발행 | 8081 · gRPC 4317 |
| `ingester/` | Kafka `raw` 소비 → protobuf 풀기 → ClickHouse 적재 (스팬 · 메트릭 · 로그) | 8082 |
| `api-server/` | 화면이 부르는 REST. 인증 · 서비스 등록 · 조회 · 알림 채널 | 8080 |
| `detector/` | 주기 평가 → 경보 상태 전이 → 발송 의도 기록 | 8083 |
| `notifier/` | 발송 대기 큐 소비 → Slack 등 채널 전송 | 8084 |

저장소 셋. **PostgreSQL** = 사람이 편집하는 설정·규칙·사건. **ClickHouse** = 신호 원본과 집계. **Kafka** = 수집기와 적재 처리기 사이 완충.

로컬 실행은 `README.md` 를 본다. 첫 줄의 `docker network create monimo-dev` 를 빠뜨리면 compose 가 실패한다.

## 2. 깨면 안 되는 규칙

- **모듈끼리는 `:common` 만 의존한다.** 다른 모듈을 넣으면 빌드가 바로 실패한다 (`build.gradle.kts`).
- **표마다 주인은 하나다.** 남의 표를 직접 읽지 않고 API 를 거친다 (ADR `#20` `#35` `#36` `#39`). 주인 목록은 `db/postgres/README.md`.
- **마이그레이션은 `db/postgres/` 한 곳.** 파트별 폴더(`config/` · `alert/` · `ingest/`)에 `V{년월일시분}__{동사}_{대상}.sql` 로 추가한다. **이미 main 에 들어간 파일은 고치지 않는다.** 바꿀 게 있으면 `alter` 파일을 새로 만든다 (ADR `#49`).
- **테스트는 Kotest BehaviorSpec.** `Given` · `When` · `Then` 이 주석이 아니라 블록이어야 한다. JUnit `@Test` 는 쓰지 않는다 (ADR `#48`).
- **Entity 는 `data class` 로 만들지 않는다.** `ddl-auto=validate` 라 표와 다르면 기동이 실패한다 (ADR `#42`).
- **비밀값은 레포에 올리지 않는다.** `.env.example` 에 이름만 둔다. 5개 레포 모두 퍼블릭이다.
- **ktlint · detekt 는 쓰지 않는다** (2026-09-23 사용자 확정, 사유는 `02-open-questions.md` Q29 에서 정리 중).

## 3. 작업 흐름

1. GitHub 이슈를 만든다. 제목은 커밋 형식과 같게 (`feat(collector): ...`), 라벨은 `type/*` 과 `area/*`.
2. 브랜치를 판다. `feat/<이슈번호>-<설명>` · `fix/<이슈번호>-<설명>` · `chore/<설명>`. **항상 `origin/develop` 에서 새로 판다.**
3. 커밋 메시지는 `<타입>(<범위>): <요약>`. 타입은 feat · fix · docs · chore · refactor · test.
4. PR 을 올린다. 본문에 무엇을 · 왜 · 어떻게 확인했는지와 `Closes #번호`.
5. PR 의 base 는 **`develop`** 이다 (기본 브랜치라 기본값 그대로 두면 된다). `main` 은 배포 단위로 `develop` 에서 한 번에 올린다.
6. **`main` · `develop` 직접 push 는 막혀 있다.** 리뷰 승인은 필수가 아니지만 CI 통과는 필수다 (ADR `#46`).

CI 는 `build`(테스트 포함) · 이미지 빌드 2개 · `dev-infra`(compose 관통 점검) 를 돈다. `dev-infra` 는 compose · db · scripts · collector · ingester 가 바뀐 PR 에서만 돈다.

`develop` → `main` 올릴 때는 **스쿼시가 아니라 머지 커밋**으로 머지한다. 스쿼시하면 두 브랜치의 역사가 갈라져 다음 배포 PR 에 충돌이 쏟아진다.

## 4. 파트와 담당

2026-09-23 확정. CODEOWNERS 가 폴더별 리뷰어를 자동으로 잡는다.

| 파트 | 담당 | 폴더 |
|---|---|---|
| 수집 | 승조 `@SeungJo-02` | `collector/` · `ingester/` · `db/postgres/ingest/` · 배포(CI · compose · gradle) |
| 알림 | ukong `@ukongee` | `detector/` · `notifier/` · `api-server/.../alert/` · `db/postgres/alert/` |
| 조회 | Nova `@hyl1115` | `api-server/.../query/` |
| 인증 설정 | 재범 `@jaebeom79` | `api-server/.../auth/` · `config/` · `threaddump/` · `db/postgres/config/` · 파수꾼(별도 레포) |
| 화면 | 4명 공동 | `monimo-web` 레포 |

파트별 작업 기록은 각자 `docs/<파트>/` 에 둔다. 지금 있는 것은 `docs/alert/`.

## 5. 지금까지 한 일

### 개발환경 (승조)

- compose 로 Kafka · ClickHouse · PostgreSQL 한 번에 실행, 토픽 `raw` · `raw.dlq` 자동 생성 (`#3` `#7`)
- PG 마이그레이션 틀과 전용 Flyway 컨테이너 (`#5`), 서비스 5개 로컬 프로필과 Testcontainers 테스트 (`#6`)
- 테스트를 Kotest BehaviorSpec 으로 교체 (`#4`), ClickHouse 표 11개 · MV 7개 · 가짜 신호 데이터 (`#10`)
- 공용 네트워크 `monimo-dev`, 수집기 컨테이너 프로필, `check-wiring.sh` (`#13`)
- api-server 에 springdoc — REST 명세와 Swagger UI 를 local 프로필에서만 (`#12`)
- CODEOWNERS 파트별 담당자 (`#15`)

### 수집 파트 (승조)

- **`#16`** 수집기가 받은 OTLP 를 풀지 않고 protobuf 바이트 그대로 Kafka `raw` 에 발행. 키는 신호 이름. 약속은 `common/kafka/RawSignal.kt` 한 곳. `acks=all` 로 저장이 끝난 뒤에만 성공 응답, 실패면 `UNAVAILABLE` 로 에이전트가 재시도
- **`#42`** 적재 처리기가 `raw` 를 구독해 키로 protobuf 를 고르고 풀어서 건수를 센다. ClickHouse 적재는 아직 없다
- **`#44`** 적재 처리기 컨테이너 프로필, `check-pipeline.sh`(수집기 수신 수와 적재 처리기 소비 수 대조), dev-infra CI 가 그 검사를 돈다
- **`#46`** 트레이스 샘플링. trace ID 뒤 8바이트 해시로 골라 한 trace 가 통째로 남거나 사라지게 하고, `trace_state` 에 `monimon=canary` 가 있으면 비율을 건너뛴다. 비율은 설정값(운영 1% · 로컬 100%)
- **`#52`** `agents` 표 신설(정본 ERD 12컬럼)과 알림 표 2개의 `agent_id` FK 부착. 가짜 데이터가 PG 명단에도 서비스 4줄을 넣는다(전에는 CH 에만 넣어 화면 목록이 비었다)
- **`#58`** 스팬을 ClickHouse `spans` 에 적재. OTLP → 우리 모델(`SpanRow`) 변환은 프레임워크를 모르는 순수 코드, 저장할 곳은 도메인이 정한 포트(`SpanStore`)이고 ClickHouse 구현은 `outbound/` 에 둔다. 수집기가 읽고 버리던 카나리 표식을 `attributes['monimo.canary']` 로 남긴다. **이로써 에이전트 → 수집기 → Kafka → 적재 처리기 → ClickHouse 가 이어졌다**
- **`#62`** `check-pipeline.sh` 가 ClickHouse `spans` 줄 수가 늘었는지까지 본다. dev-infra CI 가 이 스크립트를 돌리므로 적재(변환 · insert)를 깨뜨리는 PR 은 CI 에서 걸린다. 메트릭 · 로그는 적재가 생기면 `table_of` 에 표 이름만 추가
- **`#64`** 메트릭을 `metrics_raw` 에, 로그를 `logs` 에 적재. `#58` 과 같은 꼴(모델 · 순수 변환 · 포트 · CH 어댑터)이고, 세 변환기가 같이 쓰는 OTLP 값 도구(`transform/OtlpValues.kt`)와 세 저장소가 같이 쓰는 JSONEachRow 도구(`outbound/clickhouse/JsonEachRow.kt`)를 뽑았다. 메트릭은 포인트 1개 = 1줄, 히스토그램은 `.count` · `.sum` · `.min` · `.max` 로 편다. **이로써 세 신호가 전부 ClickHouse 에 쌓이고, `check-pipeline.sh` 와 CI 가 세 표를 다 본다**
- **`#66`** 적재하면서 처음 보는 파드를 PG `agents` 표에 등록. resource 에서 (서비스 이름 · 파드 식별자 · 호스트 · JVM · 에이전트 버전)을 꺼내 `INSERT ... SELECT FROM applications ... ON CONFLICT (agent_key) DO NOTHING` 한 문장으로 넣는다. 이미 등록한 키는 메모리에 들고 있어 PG 왕복이 파드당 한 번이다. 적재(save) **뒤에** 등록하고 실패는 로그 · 카운터만 남겨 적재를 막지 않는다. 적재 처리기가 PG 에 쓰는 첫 코드
- **`#79`** 스팬 `events`(예외 종류 · 메시지 · 스택트레이스)를 `spans` 에 적재. `#58` 에서 이 컬럼만 빼먹었다 — 다른 컬럼은 값 하나인데 `events` 는 스팬 하나에 사건 여러 개(1:다)라 모양이 달라 미뤘다가 잊었고, 가짜 데이터가 채워 넣어 화면이 멀쩡해 보여 늦게 발견했다(ukong 피드백). CH `Nested` 는 배열 세 개(`events.ts` · `events.name` · `events.attributes`)로 넣는다. 수집기 · Kafka 는 손대지 않았다 — 바이트를 풀지 않고 넘기므로 events 는 처음부터 Kafka 에 있었다

### 알림 파트 (ukong)

- **`#22`** 경보 상태머신 순수 로직. N회 연속 위반이면 발화, M회 연속 정상이면 해제, 같은 버킷 재처리 차단
- **`#23`** 알림 스키마 제안 문서와 SQL (평가 상태 · outbox · 사건 스냅샷)
- **`#24`** 경보 전이와 발송 의도를 한 트랜잭션에 저장. 실제 PG 로 원자적 롤백 · 동시 평가 검증
- **`#25`** 채널 전략과 Slack 어댑터. 응답을 성공 · 재시도 · 영구 실패 · 결과 모름으로 분류
- **`#26`** 발송 워커. `FOR UPDATE SKIP LOCKED` 선점, 외부 호출 중에는 트랜잭션을 잡지 않음, 재시도 정책
- **`#38`** 알림 표 7개를 `db/postgres/alert/` 로 이관. `agents` 표가 없어 `agent_id` FK 는 보류
- **`#40`** 알림 채널 5개 문 (api-server)
- **`#54`** 경보 규칙 7개 문 (api-server). 생성 · 연결 교체는 전부 아니면 전무, 제외된 서비스 규칙 불가, 조건 수정 시 `version` +1
- **`#56`** 탐지 스케줄 평가. 15초마다 켜진 서비스 단위 규칙(5XX · 4XX · P95)을 `service-health`(`#48`) 1분 버킷으로 판정해 상태머신에 넣는다. 비율은 sum/sum, p95 는 버킷별, 줄이 없거나 조회 실패면 판정 불가. 로컬 compose 에서 ClickHouse → API 서버 → 탐지 → 알림 → Slack(가짜) 관통 확인
- **`#60`** 발송 채널별 서킷브레이커(E8). 연속 실패 5번이면 30초 동안 호출을 멈추고 시도 횟수를 늘리지 않은 채 재예약, 시험 호출 1번으로 회복 확인
- **`#70`** 경보 사건 목록 · 상세 · 전송 이력 3개 문 (api-server, VIEWER+). 사건은 탐지가, 이력은 알림이 쓰는 표라 읽기 전용 Entity(`@Immutable`). 상세의 조건 · 심각도는 지금 규칙이 아니라 **발화 당시 스냅샷**. 목록은 기본 FIRING, `from` · `to` 는 함께 보내야 하고 최대 90일, 제외된 서비스의 사건은 안 보인다. 파드 단위 사건은 `agents` 를 읽어 `agent_uuid` · `agent_key` 를 채운다
- **`#67`** `#52` 가 주석을 고친 알림 마이그레이션 2개(`V202609281821` · `1822`)를 처음 들어갔을 때 내용으로 되돌림. Flyway 는 주석까지 체크섬에 넣어, 고친 채로 두면 `#52` 이전에 만든 DB 가 `checksum mismatch` 로 멈춘다 (ADR `#49`). SQL 변화 없음
- **`#72`** 채널 시험 발송 (api-server `POST /alert-channels/{uuid}/test` → 알림 서비스 내부 문 `POST /internal/channels/test`). 저장된 실제 config 로 한 번 보내고 결과만 돌려준다(비밀값 미노출). outbox · 재시도 · 서킷을 거치지 않고 `notification_history` 에도 남기지 않는다. 공급자가 응답했으면(2xx SUCCESS, 4xx · 429 · 5xx FAILED) 200, 닿지 않으면 FAILED("채널 서버가 응답하지 않습니다"), 알림 서비스가 죽었으면 503. 같은 채널은 10초에 한 번(인스턴스 메모리 기준). 꺼진 채널도 시험 가능. **알림 서비스도 이제 `MONIMO_INTERNAL_TOKEN` 이 필요하다**

### 인증 설정 파트 (재범)

- **`#32`** PG 표 3개 — `users` · `applications` · `application_configs`
- **`#34`** 로그인 · JWT · 역할 검사. refresh 는 httpOnly 쿠키 회전, 내부 문은 `X-Internal-Token` 필터
- **`#36`** 서비스 등록 · 목록 · 상세 · 수정 · 제외 5개 문. `deleted_at` 논리 삭제, 설정 줄 자동 생성

### 조회 파트 (Nova)

- **`#17`** 응답 봉투 · 에러 코드 · 전역 예외 처리 · `X-Request-Id`
- **`#19`** 시간 범위 검사 · step 별 읽을 표 단위 · limit 검사 · 커서 페이징
- **`#48`** 내부 문 `GET /internal/service-health`. 실제 ClickHouse 에 스팬을 넣어 검증
- **`#50`** 트레이스 상세 `GET /traces/{traceId}`. `trace_id` 로 `spans` 를 평면 조회해 서버 코드(`SpanTree`)에서 부모-자식 트리로 조립한다. 루트 `parent_span_id` · HTTP 아닌 스팬 `http_status` 는 `null`, 부모 스팬 없는 스팬이 2개 이상이면 "(누락된 구간)" 자리 아래 나란히 둔다. 같은 스팬이 두 번 적재돼도 한 번만 나온다

## 6. 지금 막혀 있는 것

2026-09-29 레포 전체 점검에서 나온 것들이다. 파트를 넘나드는 것만 적는다.

| 무엇 | 누가 풀어야 하나 | 안 풀면 |
|---|---|---|
| `agents.ip` 가 비어 있다 | 수집 | 등록은 시작됐지만(`#66`) `ip` 는 못 채운다. ERD 는 수집기가 gRPC 연결 통로에서 알아내 메시지에 붙이고 적재 처리기가 적는 것으로 정했는데, 수집기 쪽 코드가 없다. 파드 이름이 비슷할 때 구분하는 단서라 화면에만 영향 |
| 카나리 조회 문이 없다 | 조회 · 파수꾼 | 적재가 표식을 `attributes['monimo.canary']` 로 남기기 시작했다(`#58`). `GET /internal/canary/freshness` 를 `mapContains(attributes, 'monimo.canary')` 기준으로 만들면 된다. 명세의 `service_name=canary-probe` 기준은 0건이 나오므로 고쳐야 한다 |
| `peer_service` 를 채우는 코드가 없다 | 수집(적재) | 적재는 OTel 이 준 값을 그대로 넣는데(`#58`) 에이전트 2.x 는 `peer.service` 를 기본으로 안 넣는다. 그래서 서버맵 노드에 서비스 이름 대신 `shop-order:8080` 같은 주소가 뜬다. `applications.name` 매핑이 필요하다. 가짜 데이터는 이 값을 손으로 박아 둬서 지금은 안 보인다 |
| 가짜 데이터와 실데이터의 메트릭 모양이 다르다 | 조회 · 알림 | 가짜 데이터(`db/clickhouse/seed`)는 `jvm.gc.duration` 을 한 값으로 넣지만, 실데이터는 OTel 히스토그램이라 `jvm.gc.duration.count` · `.sum` · `.min` · `.max` 네 이름으로 들어온다(`#64`). GC_TIME 경보와 인스펙터 GC 그래프는 `.sum` 기준으로 짜야 한다. `series_hash` 도 가짜(`cityHash64`)와 실데이터(SHA-256 앞 8바이트)가 다른 식이지만 같은 지표 안에서 갈래를 나누는 용도라 섞이지 않으면 문제 없다 |
| `service-health` 의 `step` 규칙이 노션 명세와 다르다 | 조회 | 레포 사본(`api-spec.md` 2번)은 `step≥60 · 60의 배수` 로 고쳤다(`#50`). 노션 「API 명세」 반영만 남았다 |
| 적재가 `spans.events` 를 채우지 않는다 | 수집(적재) | `SpanRow` 에 events 가 없어(`#58`) 실데이터에서 예외 type · message · stacktrace 가 빈다. 트레이스 상세의 예외 표시와 에러 목록 · 타임라인(`exception_type`)이 비어 나온다. 가짜 데이터는 손으로 넣어 지금은 안 보인다 |
| 재발급 토큰이 명세는 본문, 코드는 쿠키 | 인증 설정 | 명세대로 만든 화면이 재발급에서 401 을 받는다 |
| 에이전트 mTLS 인증이 없다 | 수집 | OTLP 문이 평문이라 4317 에 닿는 누구나 가짜 스팬을 넣을 수 있고, 카나리 표식을 붙이면 샘플링까지 우회한다 (ADR `#21` ④ 가 기각 사유로 적은 상태) |
| `#52` 머지 후 ~ `#67` 머지 전에 만든 로컬 DB 는 `postgres-migrate` 가 `checksum mismatch` 로 멈춘다 | 해당하는 사람 각자 | `docker compose run --rm postgres-migrate repair` 를 한 번 돌리면 풀린다. 주석만 바뀐 것이라 표 구조는 같다. 그 전이나 그 후에 만든 DB 는 해당 없음 |
| `/healthz` · `/readyz` 가 어느 모듈에도 없다 | 전원 (규격은 배포) | 쿠버네티스 프로브와 화면 S10 의 "우리 서비스 6개" 카드가 읽을 대상이 없다 |

**이 과정에서 정한 것**

- `spans.agent_id`(파드 식별자)는 resource 속성에서 `service.instance.id` → `k8s.pod.name` → `host.name` 순서로 고른다. 셋 다 없으면 빈 글자(`#58`). 쇼핑몰 에이전트가 첫 번째를 채워 주는 것이 맞다 — `monimo-shop` 후속 작업
- 카나리 표식은 CH `spans.attributes` 의 `monimo.canary` 키로 남긴다. `trace_state` 컬럼을 새로 만들지 않았다(`#58`)
- 메트릭 변환 규칙(`#64`): Gauge · Sum 은 포인트 1개 = 1줄, 값은 Double. Histogram · ExponentialHistogram · Summary 는 `<이름>.count` · `.sum`(+ 있으면 `.min` · `.max`) 로 펴고 버킷은 버린다. Sum 의 누적/델타는 바꾸지 않고 그대로 넣는다(ADR `#38`) — 누적 → 델타는 조회가 `runningDifference` 로. `series_hash` = attributes 를 키 정렬해 `k=v` 줄로 이은 글자의 SHA-256 앞 8바이트
- 로그 변환 규칙(`#64`): `logger` = scope 이름(OTel Java 로그 appender 가 로거 이름을 넣는 자리), `thread` = 꼬리표 `thread.name`, `level` = `severity_text` 대문자, 없으면 `severity_number` 구간(1~4 TRACE … 21~24 FATAL), `ts` = `time_unix_nano`, 0 이면 `observed_time_unix_nano`
- 적재 처리기 카운터 `monimo.ingester.raw.consumed` 는 수집기와 같은 단위(스팬 · 메트릭 · 레코드 **개수**)를 센다. 줄 수가 아니다 — `check-pipeline.sh` 가 둘을 대조하기 때문
- 화면에 **등록되지 않았거나 제외된(`deleted_at`) 서비스**의 파드는 `agents` 에 넣지 않는다(`#66`). `application_id` 가 NOT NULL FK 이고, 감시 대상 등록은 사람이 화면에서 하는 것이 설계다(ADR `#36`). 건너뛴 수는 `monimo.ingester.agents{outcome=unknown_service}` 로 센다 — telemetrygen 기본 서비스 이름이 여기 걸린다
- `agents.first_seen_at` 은 적재 처리기가 **본 시각**이다(`#66`). 신호 안의 시각을 쓰지 않는 이유: 에이전트 시계가 틀릴 수 있고 배포 시점을 가늠하는 칸이라 초 단위 정확성이 필요 없다. `status` 는 기본값 `UNKNOWN` 으로 두고 탐지가 바꾼다(ADR `#39`)

- 트레이스 상세 응답(`#50`, 2026-09-29 회의): 루트 `parent_span_id` = `null`, HTTP 아닌 스팬 `http_status` = `null`(CH 는 0), 시각은 나노초 9자리 고정. 부모 스팬 없는 스팬이 1개면 그대로 루트, 2개 이상이면 `span_id` 가 빈 "(누락된 구간)" 자리를 루트로 두고 그 아래에 나란히 둔다(실제로 없는 호출 관계를 만들지 않기 위해). 샘플링으로는 트리가 끊기지 않는다(`#46`) — 남는 원인은 요청 직후 조회(루트 스팬이 가장 늦게 도착) · 적재 실패 · 에이전트 버퍼 초과

**팀이 결정해야 하는 것** (`02-open-questions.md` 로 옮길 것)

- 1% 샘플링한 표본이 그대로 화면의 "호출 수" 가 된다. 보정할지, 표본이라고 표시할지 정해야 한다
- 같은 이유로 **예외도 1% 만 남는다**(`#79`). 에러 화면(`/errors` · `/errors/timeline`)이 표본으로도 쓸 만한지, 아니면 `status_code = ERROR` 인 트레이스는 샘플링을 건너뛸지(tail 샘플링) 정해야 한다. 후자는 수집기 `TraceSampler` 에 조건 하나 추가로 가능하지만 "에러만 100%" 라 비율이 왜곡된다
- 파수꾼이 Gradle 모듈에 없다(별도 레포 · Python). 그런데 명세는 서비스 6개가 `/readyz` 를 연다고 적는다
- `application_configs.log_level` 이 폐기 기록 없이 사라졌다. 핵심기능 5에 로그 등급 변경이 포함되는지
- 트레이스 404 에서 `SIGNAL_EXPIRED`(93일 지나 지워짐)와 `NOT_FOUND`(처음부터 없음)를 나눌지. trace ID 에 시각이 없어 서버가 구분할 수 없다. 지금은 `NOT_FOUND` 하나로 응답한다(`#50`)

## 7. 참고

- 각 파트의 자세한 기록: `docs/alert/00-status.md` (알림)
- 마이그레이션 규칙: `db/postgres/README.md`
- 로컬 실행 · 포트 · 환경변수: `README.md`
