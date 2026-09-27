# 알림 PG 스키마 제안 (초안 · 미합의)

> `db/postgres/alert/` 에는 아직 넣지 않았다. main 에 들어간 마이그레이션은 고칠 수 없으므로(ADR #49) 합의 뒤 한 번에 넣는다.
> 기존 ERD 9표 중 알림 5표(`alert_rules` · `alert_channels` · `alert_rule_channels` · `alert_events` · `notification_history`)는 ERD 그대로 만들고, 아래는 **추가 · 보완분**이다.

## 1. 기존 표 보완

| 표 | 추가 | 이유 |
|---|---|---|
| `alert_rules` | `version INT NOT NULL DEFAULT 1` | 규칙 판 번호. 늦은 평가 · 수정 전 평가 차단 (상태머신 D3). API 서버가 PUT 때 +1 |
| `alert_events` | `rule_version` · `metric_kind` · `operator` · `threshold` · `window_sec` · `severity` (발화 당시 스냅샷) | 규칙을 고쳐도 과거 사건 상세(API #15)가 바뀌지 않게 (C7) |
| `alert_events` | `CREATE UNIQUE INDEX … (fingerprint) WHERE state = 'FIRING'` | 탐지 경쟁의 최종 방어선. 부분 인덱스라서 RESOLVED 뒤 재발은 막지 않는다 |

## 2. 새 표

### 2-1. `alert_evaluation_states` (주인: 탐지)

```sql
CREATE TABLE alert_evaluation_states (
    id                   BIGSERIAL PRIMARY KEY,
    fingerprint          VARCHAR(64)  NOT NULL UNIQUE,
    alert_rule_id        BIGINT       NOT NULL REFERENCES alert_rules (id),
    agent_id             BIGINT       REFERENCES agents (id),        -- 서비스 단위 규칙이면 NULL
    phase                VARCHAR(10)  NOT NULL CHECK (phase IN ('NORMAL', 'PENDING', 'FIRING')),
    consecutive_bad      INT          NOT NULL DEFAULT 0,
    consecutive_good     INT          NOT NULL DEFAULT 0,
    consecutive_unknown  INT          NOT NULL DEFAULT 0,
    active_alert_event_id BIGINT      REFERENCES alert_events (id),
    last_bucket_end      TIMESTAMPTZ,
    rule_version         INT          NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CHECK ((phase = 'FIRING') = (active_alert_event_id IS NOT NULL))
);
```

`EvaluationState` 값 객체와 1:1. 평가 트랜잭션은 이 행을 `FOR UPDATE` 로 잡는다.

### 2-2. 발송 작업 — 두 안

**A안 (최소안, 추천): outbox 한 표가 채널별 발송 큐를 겸한다**

탐지가 전이를 저장하는 같은 트랜잭션에서 `alert_rule_channels` 를 읽어 **채널 수만큼** 작업 행을 넣는다.

```sql
CREATE TABLE notification_outbox (
    id               BIGSERIAL PRIMARY KEY,
    alert_event_id   BIGINT       NOT NULL REFERENCES alert_events (id),
    transition       VARCHAR(10)  NOT NULL CHECK (transition IN ('FIRING', 'RESOLVED')),
    alert_channel_id BIGINT       NOT NULL REFERENCES alert_channels (id),
    payload          JSONB        NOT NULL,     -- 발화 당시 메시지 재료 스냅샷. 재시도 중 바꾸지 않는다
    status           VARCHAR(12)  NOT NULL CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SENT', 'FAILED', 'CANCELLED')),
    attempt_count    INT          NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL,
    lease_until      TIMESTAMPTZ,
    claim_token      UUID,
    last_error       TEXT,                      -- 정제된 오류. 웹훅 주소 · 토큰 금지
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    UNIQUE (alert_event_id, transition, alert_channel_id)   -- 논리 발송 1건 = 사건 × 전이 × 채널
);
CREATE INDEX notification_outbox_due ON notification_outbox (next_attempt_at)
    WHERE status IN ('PENDING', 'IN_FLIGHT');
```

- 장점: 표 1개, 전이와 발송 의도가 확실히 한 트랜잭션, 채널 한 곳 실패가 다른 채널을 다시 보내지 않는다.
- 비용: **쓰는 서비스가 둘**이라 "표 하나에 주인은 하나"(README · ADR #20 #36 #39)와 부딪힌다 → 아래 §2-5 처럼 **서비스별 허용 동작**을 ADR 로 정한다. 탐지가 `alert_rule_channels` 도 읽게 된다.

(2026-09-27 사용자 결정: A안으로 제안한다)

**B안: 사건 전이 outbox(탐지) + 발송 작업(알림) 분리**

- 탐지: `alert_event_transitions` 에 append-only INSERT.
- 알림: 한 트랜잭션에서 아직 작업이 없는 전이를 골라(`NOT EXISTS`) 채널별 `notification_deliveries` 를 만든다. 전이 표는 고치지 않으므로 주인이 하나씩.
- 비용: 표 2개, 팬아웃 단계 하나 더(지연 · 코드). "마지막 처리 id" 오프셋 방식은 BIGSERIAL 이 커밋 순서와 달라 **작은 id 가 늦게 커밋되면 영영 건너뛴다** — 그래서 오프셋 대신 NOT EXISTS 로 찾아야 한다.

### 2-3. `notification_history` 의 의미

API #16 이 행마다 `retry_count` 를 주므로 **행 1개 = 논리 발송 1건의 최종 결과**로 본다. 작업이 SENT / FAILED 로 끝날 때 같은 트랜잭션에서 1행 INSERT, `retry_count = attempt_count - 1`. 시도별 원본 로그가 필요하면 별도 표(Phase 2).

### 2-4. 시험 발송 (API #13 · #17)

사건이 없으므로 `notification_history` 에 넣지 않는다. 1a 는 구조화 로그(`X-Request-Id`, 채널 UUID, 결과, 지연)만. 화면에 이력이 필요해지면 `channel_test_logs` 를 제안.

### 2-5. 서비스별 허용 동작 (쓰기 소유권 ADR 초안)

"누가 어느 컬럼을 쓰나"가 아니라 **"누가 어떤 동작을 해도 되나"**로 정한다. 표에 없는 동작은 금지.

| 표 | 탐지 | 알림 | API 서버 | 삭제 · 보관 (담당 · 정책) |
|---|---|---|---|---|
| `alert_rules` · `alert_channels` · `alert_rule_channels` | 읽기 | 읽기 (채널 켜짐 · 설정) | 생성 · 수정 · 켜기/끄기, 연결 전체 교체 | **삭제 API 없음** (enabled 로 끈다, ERD). 연결 줄은 교체 때 API 서버가 지움 |
| `alert_evaluation_states` | 줄 생성 · 카운터 갱신 | — | 읽기 (진단 화면 필요 시) | 담당 탐지. 끈 규칙의 NORMAL 줄 정리 시점 **미정** |
| `alert_events` | **사건 생성 (FIRING)**, **복구 전이 (FIRING → RESOLVED)** | 읽기 | 읽기 (API #14 · #15 · #16) | **삭제 없음** (이력 · FK 부모). 보관 기간 **미정** |
| `notification_outbox` | **발송 작업 생성** (PENDING 으로 INSERT 만) | **선점** (IN_FLIGHT · claim_token · lease_until), **재시도 예약** (PENDING · next_attempt_at), **완료** (SENT · FAILED · CANCELLED). INSERT · 내용(payload · 대상) 수정 금지 | — | 담당 알림. 끝난 작업을 N일 뒤 삭제 제안 (결과는 `notification_history` 에 남으므로). 기간 **미정** |
| `notification_history` | — | **생성만** (작업이 끝날 때 1줄). 수정 금지 | 읽기 (API #16) | 삭제 없음, 보관 기간 **미정** |
| `agents.status` | FN-29 에 따르면 DOWN 변경 (**C1 충돌, 미정**) | — | 읽기 | 담당 적재 처리기 (명단) |

## 3. 발송 워커 흐름 (A안 기준 · 미구현)

```
[선점 트랜잭션, 짧게]
  SELECT id FROM notification_outbox
   WHERE (status = 'PENDING' AND next_attempt_at <= now())
      OR (status = 'IN_FLIGHT' AND lease_until < now())      -- 임대 만료 회수
   ORDER BY next_attempt_at LIMIT :batch FOR UPDATE SKIP LOCKED;
  UPDATE … SET status='IN_FLIGHT', claim_token=:new, lease_until=now()+:lease, attempt_count=attempt_count+1;
COMMIT
[트랜잭션 밖] 채널 enabled 재확인 → 서킷 확인 → sender.send(timeout < lease)
[결과 트랜잭션]
  UPDATE … SET status=… WHERE id=:id AND claim_token=:mine   -- 0행이면 늦은 완료. 결과를 버리고 로그만
```

남는 한계: 외부가 접수한 뒤 결과 UPDATE 전에 워커가 죽으면 임대 만료 뒤 **다시 보낸다**. 로컬 UNIQUE 는 이것을 막지 못한다. 공급자 멱등 키 지원 여부는 채널별로 공식 문서 확인 후 `30-delivery.md` 에 정리 예정.
