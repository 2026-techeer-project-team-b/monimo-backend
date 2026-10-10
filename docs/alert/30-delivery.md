# 발송 — outbox · 워커 · 재시도 · 멱등성의 한계

> 개인 설계안 · 팀 미확정. 2026-09-27 결정: **A안(outbox = 채널별 발송 큐)**, N/M 전역 설정.
> 코드: `detector/.../alert/record/` (전이 + outbox 저장) · `notifier/.../delivery/` (워커) · `notifier/.../channel/` (Slack)
> 테스트: `EvaluationRecorderTest` (실제 PG) · `DeliveryWorkerTest` (실제 PG + 가짜 Slack 서버)

## 1. 흐름

```
탐지 TX ─ 평가상태 FOR UPDATE → 상태머신 → alert_events INSERT/UPDATE → notification_outbox INSERT × 켜진 채널 → COMMIT
알림 TX① ─ SELECT … FOR NO KEY UPDATE SKIP LOCKED LIMIT batch → IN_FLIGHT · claim_token · lease_until → COMMIT
(트랜잭션 밖) 채널 켜짐 재확인 → sender.send (timeout < lease)
알림 TX② ─ UPDATE … WHERE id AND claim_token AND IN_FLIGHT → (끝났으면) notification_history INSERT → COMMIT
```

## 2. 결정 기록

### D5. 전이와 발송 의도를 한 트랜잭션에 (transactional outbox)

- **문제**: 사건만 저장되고 죽으면 알림이 영영 안 간다. 먼저 보내고 저장이 실패하면 기록 없는 알림이 간다.
- **선택**: 사건 INSERT 와 outbox INSERT 를 같은 PG 트랜잭션에. 외부 호출은 알림 워커가 나중에.
- **다른 방법**: 탐지→알림 동기 HTTP(내구성 없음) · Kafka relay · CDC (알림 경로에 추가하지 않기로 한 것들).
- **검증 (E3)**: `TransitionHook` 으로 "사건 저장 뒤 · outbox 저장 전" 예외 주입 → 사건 0 · outbox 0 · 평가 상태도 bad=2 그대로. 같은 버킷 재평가 시 정상 발화. ✅
- **남은 위험**: 탐지가 `alert_rule_channels` · `alert_channels`(API 서버 표)를 읽는다. 발화 순간 켜진 채널만 대상.

### D6. 선점은 짧게, 외부 호출은 트랜잭션 밖

- **문제**: 트랜잭션 안에서 Slack 을 부르면 응답을 기다리는 동안 행 잠금 + DB 커넥션을 붙잡는다. Slack 이 5초씩 느려지면 커넥션 풀이 마른다.
- **선택**: TX① 에서 `IN_FLIGHT` + `lease_until` 을 적고 커밋 → 잠금 해제. 다른 워커는 조회 조건(PENDING 이거나 임대 만료)에 걸리지 않아 못 가져간다.
- **장애 순서 E5 (선점 후 처리 중단 모사)** — 실험 방식: 같은 JVM 에서 `claim()` 만 부르고 발송 · `finish()` 를 부르지 않는다. 실제 프로세스 종료 · 재시작은 검증하지 않았다:
  1. A: 선점 커밋 (token=a, lease=+30s) → 처리 멈춤
  2. B: 폴링 → IN_FLIGHT 이고 임대 유효 → 건너뜀 ✅ (호출 0)
  3. 30초 뒤 B: 임대 만료 → 회수(token=b) → 발송 → SENT ✅
  4. A 가 멈췄던 처리를 이어 token=a 로 결과 기록 → `WHERE claim_token = a` 0행 → 거부 ✅ (이력 1줄 유지)
- **기동 검사**: `lease > batch × (connect + request timeout)` 이 아니면 기동 실패. 한 바퀴를 순서대로 보내므로, 이 조건이 깨지면 배치 뒤쪽 작업은 호출하기 전에 임대가 끝나 다른 워커가 또 가져간다.

### D7. 다중 워커 = SKIP LOCKED

- **검증 (E4)**: 작업 200개 × 워커 스레드 4개 → 가짜 수신 서버가 200개를 각각 정확히 1번 받음, 전부 SENT, 이력 200줄 ✅
- 실제 SQL: ADR #42 가드레일 ①에 따라 **네이티브** `… FOR UPDATE SKIP LOCKED` (처음엔 JPA `@Lock` + lock.timeout 힌트로 만들었다가 가드레일 위반이라 바꿈. 그때 Hibernate 는 더 약한 `FOR NO KEY UPDATE SKIP LOCKED` 를 만들었다 — 자식 행 FK 검사(`KEY SHARE`)를 막지 않는 잠금. outbox 를 FK 로 가리키는 표가 없어 여기서는 차이가 없다)
- **한계**: 같은 JVM 의 스레드 4개다. 프로세스 종료 · 재시작, 인스턴스 4개 · 네트워크 지연 · 커넥션 풀 경쟁은 아직 재지 않았다. 전역 순서(FIFO)는 보장하지 않는다 — 같은 사건의 FIRING 재시도 중 RESOLVED 가 먼저 가는 문제는 그룹핑 선점 조건으로 막았다 (E10, `50-grouping.md` D18).

### D8. 재시도는 워커 한 곳, next_attempt_at 으로

| 결과 | 분류 | 처리 | 검증 |
|---|---|---|---|
| 2xx | Accepted | SENT + 이력 SUCCESS | E4 ✅ |
| 429 | Retryable (+Retry-After) | `max(Retry-After, jitter)` 뒤로 재예약 | E7-a ✅ 120초 뒤, 이력 없음 |
| 5xx · 연결 실패 | Retryable | Full jitter `rand(0, min(cap, base·2^(n-1)))` | E7-b ✅ 3회 호출 후 FAILED, retry_count 2 |
| 400/403/404 | Permanent | 바로 FAILED | E7-c ✅ 호출 1회 |
| 응답 시간 초과 | **Unknown** | 재예약 (중복 가능) | E7-d ✅ 수신 서버는 이미 받았음 |
| 채널 꺼짐 | — | CANCELLED, 호출 0, 시도 수 0 | ✅ |
| 어댑터 없음 (EMAIL 등) | — | FAILED "미구현" (성공으로 치지 않음) | ✅ |

- `attempt_count` 는 **실제 외부 호출 수**. 선점할 때가 아니라 결과를 적을 때 센다 → 이후 서킷 OPEN 재예약이 시도 수를 부풀리지 않는다.
- JDK `HttpClient` 는 스스로 재시도하지 않고 리다이렉트도 따라가지 않게 했다 → 재시도가 겹치지 않는다.
- 최대 나이(`max-age`)를 넘기면 포기 → 채널을 오래 끄거나 공급자가 오래 죽은 뒤 낡은 경보가 몰려 나가지 않는다.

### D9. 채널별 서킷브레이커 — OPEN 은 "시도"가 아니다 (E8, #60)

- **문제**: 채널이 죽어 있어도 작업마다 끝까지 호출하면 `max-attempts` 를 소진해 알림이 FAILED 로 떨어진다. 채널이 살아나도 이미 버린 알림은 다시 가지 않는다.
- **선택**: 채널(`alert_channel_id`)마다 회로. 연속 실패(재시도 가능 · 결과 모름) 5번 → OPEN 30초 → 시험 호출 1번(HALF_OPEN) → 응답하면 CLOSED, 실패면 다시 OPEN. OPEN 동안은 **호출하지 않고 `attempted=false` 로 재예약** → `attempt_count` 가 늘지 않는다.
- 영구 거절(400 · 404)은 채널 설정 문제이지 장애가 아니므로 "응답함"으로 본다 (회로를 닫는 쪽).
- OPEN 중에도 `max-age` 는 지킨다 — 복구 뒤 30분 넘은 낡은 경보가 한꺼번에 가지 않게 호출 없이 FAILED.
- **대안과 비용**: Resilience4j 는 의존성 추가(배포 담당 영역인 `libs.versions.toml`)가 필요하고, 테스트 시계로 OPEN → HALF_OPEN 을 재현하기가 번거롭다. 기능이 늘면(느린 호출 비율 · 슬라이딩 창) 교체를 검토한다.
- **한계**: 회로는 인스턴스 메모리에 있다. 알림 인스턴스가 여러 대면 각자 연속 실패를 센다 (공유하지 않음).
- **검증**: `DeliveryWorkerTest` E8 — 503 이 이어지면 5번 뒤 OPEN, 이후 같은 채널 작업은 가짜 서버 호출 0번 · `attempt_count` 0 으로 30초 뒤 재예약, 다른 채널은 발송됨. 살아나고 30초 뒤 시험 호출 성공 → 쌓인 작업 모두 발송, 보류됐던 작업 이력의 재시도 0. OPEN 중 30분 넘은 작업은 호출 0번으로 FAILED.

## 3. 가장 중요한 한계 — 외부 exactly-once 는 없다

**E6 재현 (발송 후 완료 기록 누락)**: A 가 가짜 Slack 에 보냄(접수됨) → `finish()` 를 부르지 않음 → 30초 뒤(시계 이동) B 가 임대 만료 작업을 회수해 다시 보냄 → **가짜 서버가 같은 알림을 2번 받음.** DB 이력은 1줄이라 이력만 봐서는 중복을 알 수 없다. 응답 시간 초과(E7-d)도 같은 구조다.

`UNIQUE (alert_event_id, transition, alert_channel_id)` 는 "보낼 의도"를 한 번만 만든다는 보장이지, 외부에 한 번만 도착한다는 보장이 아니다.

| 채널 | 공급자 멱등 지원 | 근거 | 결론 |
|---|---|---|---|
| Slack Incoming Webhook | **문서에 없음** | [Sending messages using incoming webhooks](https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks) (오류 코드 목록만 있음, 멱등 · 중복 제거 언급 없음) · [Rate limits](https://docs.slack.dev/apis/web-api/rate-limits) ("Incoming webhooks: 1 per second. Short bursts >1 allowed", 429 + Retry-After 초) | at-least-once. 중복 창을 줄이는 것만 가능 (짧은 timeout, lease 여유) |
| PagerDuty Events v2 | `dedup_key` 로 같은 사건 묶음 (확인 필요) | 미확인 — 구현 전에 공식 문서로 확인 | 지원되면 `alert_event_uuid` 를 키로 |
| Webhook | 받는 쪽 구현에 달림 | — | `Idempotency-Key` 헤더로 사건 UUID+전이 전달 제안 |
| Email | 없음 | — | at-least-once |

## 4. 아직 안 한 것 (미실행 · 미구현)

- 그룹핑 · 발화/복구 순서 (E9 · E10): **구현** — `50-grouping.md`. 같은 채널 · 서비스를 group_wait 10초 기다려 한 메시지로, RESOLVED 는 FIRING 이 끝난 뒤에만. E9 는 같은 JVM 모사(선점 후 멈춤 → 임대 만료 후 새 형제와 함께 발송)까지. 반복 알림 간격(리마인더)은 하지 않는다
- **실제 프로세스 종료 (2026-10-10)**: `60-process-kill.md`. notifier JVM `kill -9` — 묶음 대기 중 종료는 유실 없이 재시작 후 1건, Slack 수신 직후 · 기록 전 종료는 유실 없이 **91초 뒤 같은 메시지 1번 더**(DB 이력만으로는 중복이 안 보임). 같은 JVM 모사와 결과가 같다
- 최종 실패 자체 알림 (FN-32): 별도 경로 + 재귀 억제. **미구현**
- 관측: backlog 깊이 · oldest age 쿼리(`countByStatusIn` · `oldestCreatedAt`)만 있음, 지표 노출 없음
- 성능 실험 (폴링 0.5/1/5초 · 워커 1/2/4 · 배치 10/50): **미실행**. 위 E4 는 기능 검증이지 성능 측정이 아니다
