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
| `ingester/` | Kafka `raw` 소비 → protobuf 풀기 → (예정) ClickHouse 적재 | 8082 |
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
2. 브랜치를 판다. `feat/<이슈번호>-<설명>` · `fix/<이슈번호>-<설명>` · `chore/<설명>`.
3. 커밋 메시지는 `<타입>(<범위>): <요약>`. 타입은 feat · fix · docs · chore · refactor · test.
4. PR 을 올린다. 본문에 무엇을 · 왜 · 어떻게 확인했는지와 `Closes #번호`.
5. **main 직접 push 는 막혀 있다.** 리뷰 승인은 필수가 아니지만 CI 통과는 필수다 (ADR `#46`).

CI 는 `build`(테스트 포함) · 이미지 빌드 2개 · `dev-infra`(compose 관통 점검) 를 돈다. `dev-infra` 는 compose · db · scripts · collector · ingester 가 바뀐 PR 에서만 돈다.

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

### 알림 파트 (ukong)

- **`#22`** 경보 상태머신 순수 로직. N회 연속 위반이면 발화, M회 연속 정상이면 해제, 같은 버킷 재처리 차단
- **`#23`** 알림 스키마 제안 문서와 SQL (평가 상태 · outbox · 사건 스냅샷)
- **`#24`** 경보 전이와 발송 의도를 한 트랜잭션에 저장. 실제 PG 로 원자적 롤백 · 동시 평가 검증
- **`#25`** 채널 전략과 Slack 어댑터. 응답을 성공 · 재시도 · 영구 실패 · 결과 모름으로 분류
- **`#26`** 발송 워커. `FOR UPDATE SKIP LOCKED` 선점, 외부 호출 중에는 트랜잭션을 잡지 않음, 재시도 정책
- **`#38`** 알림 표 7개를 `db/postgres/alert/` 로 이관. `agents` 표가 없어 `agent_id` FK 는 보류
- **`#40`** 알림 채널 5개 문 (api-server)

### 인증 설정 파트 (재범)

- **`#32`** PG 표 3개 — `users` · `applications` · `application_configs`
- **`#34`** 로그인 · JWT · 역할 검사. refresh 는 httpOnly 쿠키 회전, 내부 문은 `X-Internal-Token` 필터
- **`#36`** 서비스 등록 · 목록 · 상세 · 수정 · 제외 5개 문. `deleted_at` 논리 삭제, 설정 줄 자동 생성

### 조회 파트 (Nova)

- **`#17`** 응답 봉투 · 에러 코드 · 전역 예외 처리 · `X-Request-Id`
- **`#19`** 시간 범위 검사 · step 별 읽을 표 단위 · limit 검사 · 커서 페이징
- **`#48`** 내부 문 `GET /internal/service-health`. 실제 ClickHouse 에 스팬을 넣어 검증

## 6. 지금 막혀 있는 것

2026-09-29 레포 전체 점검에서 나온 것들이다. 파트를 넘나드는 것만 적는다.

| 무엇 | 누가 풀어야 하나 | 안 풀면 |
|---|---|---|
| `agents` 표가 없다 | 수집 | 알림 표 2개가 FK 를 못 붙이고, CPU · 힙 · GC · 에이전트다운 경보 4종이 데이터 원천 없이 남는다 |
| 카나리 표식이 ClickHouse 에 안 남는다 | 수집(적재) → 조회 · 파수꾼 | 수집기가 `trace_state` 를 읽고 버려서 `canary/freshness` 를 구현할 수 없다. 파수꾼 판정 전체가 여기 걸린다 |
| `peer_service` 를 채우는 코드가 없다 | 수집(적재) | 서버맵 노드에 서비스 이름 대신 `shop-order:8080` 같은 주소가 뜬다. 가짜 데이터는 이 값을 손으로 박아 둬서 지금은 안 보인다 |
| 경보 규칙을 만들 문도 읽을 코드도 없다 | 인증 설정(CRUD) + 알림(폴링) | 탐지가 평가할 규칙이 0건이다. 지금 `alert_rules` 에 줄을 넣는 곳은 테스트뿐 |
| `service-health` 의 `step` 규칙이 명세와 다르다 | 조회 | 명세대로 `step=30` 을 보내는 탐지가 매 주기 400 을 받고 판정 불가로 떨어진다. 코드 제약이 타당하니 명세를 고치는 쪽 |
| 재발급 토큰이 명세는 본문, 코드는 쿠키 | 인증 설정 | 명세대로 만든 화면이 재발급에서 401 을 받는다 |
| 에이전트 mTLS 인증이 없다 | 수집 | OTLP 문이 평문이라 4317 에 닿는 누구나 가짜 스팬을 넣을 수 있고, 카나리 표식을 붙이면 샘플링까지 우회한다 (ADR `#21` ④ 가 기각 사유로 적은 상태) |
| `/healthz` · `/readyz` 가 어느 모듈에도 없다 | 전원 (규격은 배포) | 쿠버네티스 프로브와 화면 S10 의 "우리 서비스 6개" 카드가 읽을 대상이 없다 |

**팀이 결정해야 하는 것** (`02-open-questions.md` 로 옮길 것)

- 1% 샘플링한 표본이 그대로 화면의 "호출 수" 가 된다. 보정할지, 표본이라고 표시할지 정해야 한다
- 파수꾼이 Gradle 모듈에 없다(별도 레포 · Python). 그런데 명세는 서비스 6개가 `/readyz` 를 연다고 적는다
- `application_configs.log_level` 이 폐기 기록 없이 사라졌다. 핵심기능 5에 로그 등급 변경이 포함되는지

## 7. 참고

- 각 파트의 자세한 기록: `docs/alert/00-status.md` (알림)
- 마이그레이션 규칙: `db/postgres/README.md`
- 로컬 실행 · 포트 · 환경변수: `README.md`
