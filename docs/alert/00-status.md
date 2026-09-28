# 알림 파트 구현 현황 (박유경)

> **개인 작업 기록 · 팀 미확정.** 팀 정본은 노션 「API 명세」「ERD」「기능 명세」와 `docs/design/` 이다.
> 이 폴더의 새 표 · 컬럼 · 정책은 **제안**이다. 알림 표는 서비스 기동을 위해 합의 전에 `db/postgres/alert/` 로 옮겼다(#38). 바꿀 게 생기면 `alter` 파일로 고친다.
>
> 기준: 2026-09-27, 브랜치 `feat/alert-state-machine`, 분기 원점 `6c83ce1` (main)

## 1. 저장소 사실 (확인한 파일)

| 항목 | 값 | 근거 |
|---|---|---|
| Kotlin | 2.2.21 | `gradle/libs.versions.toml` |
| Spring Boot | 3.5.16 | 같은 파일 |
| Java toolchain | 17 (foojay 자동 설치) | `libs.versions.toml` · `settings.gradle.kts` |
| Kotest | 6.2.5, 기본 BehaviorSpec (ADR #48) | `libs.versions.toml` · `README.md` 테스트 규칙 |
| PostgreSQL (테스트) | `postgres:17.11-alpine` Testcontainers | `notifier/src/test/.../TestInfraConfig.kt` |
| PG 접근 | JPA (ADR #42), `ddl-auto=validate`, `open-in-view=false` | `notifier/src/main/resources/application.yml` |
| 마이그레이션 | `db/postgres/` 한 곳, 전용 Flyway 컨테이너 (ADR #49). 서비스는 `flyway.enabled=false`, 테스트만 적용 | `db/postgres/README.md` · `build.gradle.kts` |
| 모듈 경계 | 모듈끼리는 `:common` 만 의존. 어기면 빌드 실패 | `build.gradle.kts` `allowedProjectDependencies` |
| Entity | `data class` 금지, allOpen · noArg 적용 | `README.md` · `build.gradle.kts` |
| HTTP client · Resilience4j | **없음** (의존성 목록에 없다) | `libs.versions.toml` |
| 담당 경로 | `/detector/` · `/notifier/` · `api-server/.../alert/` · `db/postgres/alert/` | `.github/CODEOWNERS` |

현영 개인스크럼에 적힌 규칙 4가지(Kotest BehaviorSpec · Entity data class 금지 · common 만 의존 · db/postgres 공용 Flyway)는 위 파일에서 **모두 사실로 확인**했다.

## 2. 구현 현황

| 영역 | 상태 | 근거 |
|---|---|---|
| PG 알림 표 (`alert_rules` 등 7개) | **`db/postgres/alert/` 이관 (#38)**. `agent_id` 두 컬럼은 `agents` 표가 없어 FK 보류 | `db/postgres/alert/V202609281820~1824` |
| 아웃박스 | **없음 (저장소에서 찾지 못함)** | `*.kt` · `*.sql` 에 outbox 흔적 0건, 원격 브랜치 7개 커밋 메시지에도 없음. 노션 학습 노트에도 "사용자 설명만 확인"으로 적혀 있음 |
| 탐지 스케줄러 | 없음 | `detector/` 는 `DetectorApplication.kt` 뿐 |
| 발송 워커 · 채널 어댑터 | 없음 | `notifier/` 는 `NotifierApplication.kt` 뿐 |
| API 서버 공통 틀 (봉투 · 에러 · 커서 · limit · X-Request-Id) | 구현됨 (다른 담당) | `api-server/.../common/web/*` · `common/error/*` |
| `RULE_CHANNEL_DUPLICATE` 에러 코드 | 구현됨 | `api-server/.../common/error/ErrorCode.kt` |
| 인증 (JWT · 내부 토큰 · 역할) | 없음 (다른 담당) | `api-server/.../auth/.gitkeep` |
| API 서버 내부 조회 `service-health` · `agents/active` | 없음 (조회 담당) | `api-server/.../query/` 는 `Rollup.kt` 뿐 |
| **경보 상태머신 순수 로직** | **구현됨 · 테스트 25건 통과** | `detector/.../alert/state/` |
| 전이 + outbox 원자 저장 (탐지) | **구현됨 (제안 스키마 위) · 실제 PG 테스트 9건 통과** | `detector/.../alert/record/` · `EvaluationRecorderTest` |
| 발송 워커 · 재시도 · Slack 어댑터 (알림) | **구현됨 (제안 스키마 위) · 실제 PG + 가짜 Slack 11건 통과** | `notifier/.../delivery/` · `notifier/.../channel/` · `DeliveryWorkerTest` |
| 서킷브레이커 · 그룹핑 · 실패 자체 알림 · 스케줄 평가 · API 17개 | 없음 | — |

→ 기존 아웃박스 코드는 없었다 (2026-09-27 사용자 확인: 새로 만든다). 새 구현 기준 답:
> - 아웃박스 = **채널별 발송 작업** (사건 × 전이 × 채널 한 줄, A안)
> - 상태 전이와 outbox INSERT = **같은 PG 트랜잭션** (`EvaluationRecorder.record`, E3 로 검증)
> - 외부 호출 중 DB 트랜잭션 = **유지하지 않음** (선점 TX 커밋 → 호출 → 결과 TX, `DeliveryWorker`)

**남은 것**: `agents`(수집 파트) 표가 들어오면 `alert_events.agent_id` · `alert_evaluation_states.agent_id` 에 FK 를 붙이는 `alter` 파일을 추가한다. 스키마 제안 자체(평가 상태 · outbox · 스냅샷)는 여전히 팀 합의 대상이다.

## 3. API 17개 ↔ 코드

원문 계약: 노션 「알림파트 API 17개 상세」(요청 · 응답 예시 원문 포함). 전부 **미구현**.

| # | 엔드포인트 | 서비스 / 권한 | 코드 | 막는 것 |
|---|---|---|---|---|
| 1 | `GET /api/v1/alert-rules` | API / VIEWER+ | 없음 | PG 표, 인증 |
| 2 | `POST /api/v1/alert-rules` | API / ADMIN | 없음 | PG 표, 인증 |
| 3 | `GET /api/v1/alert-rules/{alertRuleUuid}` | API / VIEWER+ | 없음 | PG 표 |
| 4 | `PUT /api/v1/alert-rules/{alertRuleUuid}` | API / ADMIN | 없음 | PG 표, 규칙 판 번호 정책 |
| 5 | `PATCH /api/v1/alert-rules/{alertRuleUuid}/enabled` | API / ADMIN | 없음 | PG 표, 규칙 off 정책 |
| 6 | `GET /api/v1/alert-rules/{alertRuleUuid}/channels` | API / VIEWER+ | 없음 | PG 표 |
| 7 | `PUT /api/v1/alert-rules/{alertRuleUuid}/channels` | API / ADMIN | 없음 | PG 표 |
| 8 | `GET /api/v1/alert-channels` | API / VIEWER+ | 없음 | PG 표 |
| 9 | `POST /api/v1/alert-channels` | API / ADMIN | 없음 | PG 표, 비밀값 저장 방식 |
| 10 | `GET /api/v1/alert-channels/{alertChannelUuid}` | API / ADMIN | 없음 | PG 표, 마스킹 규칙 |
| 11 | `PUT /api/v1/alert-channels/{alertChannelUuid}` | API / ADMIN | 없음 | 마스킹 값 재전송 처리 계약 |
| 12 | `PATCH /api/v1/alert-channels/{alertChannelUuid}/enabled` | API / ADMIN | 없음 | PG 표 |
| 13 | `POST /api/v1/alert-channels/{alertChannelUuid}/test` | API / ADMIN | 없음 | #17 |
| 14 | `GET /api/v1/alert-events` | API / VIEWER+ | 없음 | PG 표 |
| 15 | `GET /api/v1/alert-events/{alertEventUuid}` | API / VIEWER+ | 없음 | 스냅샷 컬럼 |
| 16 | `GET /api/v1/alert-events/{alertEventUuid}/notifications` | API / VIEWER+ | 없음 | 이력 행 의미 |
| 17 | `POST /internal/channels/test` | 알림 / 내부 | 없음 | 내부 토큰, Slack 어댑터 |

## 4. 문서 사이 충돌 (공용 명세는 고치지 않았다)

| # | 충돌 | 위치 | 제안 |
|---|---|---|---|
| C1 | FN-27 "탐지가 쓰는 표는 alert_events 뿐" ↔ FN-29 "탐지가 agents.status 를 DOWN 으로 바꾼다" ↔ ERD agents 주인 "적재 처리기 · 탐지" | 기능 명세 · `docs/design/web-v2/erd.md:15` | 평가 상태 · outbox 를 추가하면 탐지 쓰기 표가 더 늘어난다. 한 번에 소유권 ADR 로 정리 (질문 Q-A1) |
| C2 | FN-28 연속 N · M ↔ ERD 에 N · M 컬럼 · 평가 카운터 없음 | `erd.md:49-66` | `docs/alert/10-state-machine.md` §4 |
| C3 | ERD `alert_rules.metric_kind` 설명 "CH `service_health_1m` 을 본다" ↔ API 명세 "탐지는 API 서버 내부 문으로만 읽는다" | `erd.md:59` · `api-spec.md:131` | API 경유를 따른다. ERD 문구만 어긋남 |
| C4 | 시험 발송 결과 `SUCCESS/FAILED` ↔ 전송 이력 `SUCCESS/FAIL` | API #13 · #17 ↔ #16 · ERD | 외부 계약은 그대로 두고 표에 따로 기록. 통일 여부는 팀 결정 |
| C5 | 사건 상세 예시가 서비스 단위 `5XX_RATE` 인데 `agent_uuid` · `agent_key` 가 채워져 있음 | API #14 · #15 예시 | 서비스 단위 규칙이면 null 이 맞다고 보고 확인 요청 |
| C6 | `notification_history.alert_event_id` NN FK ↔ 시험 발송에는 사건이 없음 | ERD · API #17 | 시험 발송은 notification_history 에 넣지 않는다 |
| C7 | 사건 상세 `threshold · operator · window_sec` 를 현재 규칙 JOIN 으로 만들면 규칙 수정 뒤 과거 사건이 바뀜 | API #15 ↔ ERD alert_events | 발화 당시 스냅샷 컬럼 (`20-schema-proposal.md`) |
| C8 | `window_sec` 설명 "300이면 5분 **평균**" ↔ 5분 오류율은 `sum(cnt_5xx)/sum(cnt)` 이어야 함 | `erd.md:62` | 비율은 가중 합으로. p95 는 1분 p95 평균 불가 → 조회 담당과 계약 |
