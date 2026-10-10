# 실제 프로세스 종료 실험 — 알림 서비스가 보내다 죽으면 (E9 · E5/E6 실측)

> 2026-10-10 로컬 실측. 지금까지 E5 · E6 · E9 는 **같은 JVM 안에서 `claim()` 만 부르고 멈추는 모사**였다(`30-delivery.md`, `GroupingTest`). 이번에는 notifier JVM 을 `kill -9` 로 **실제로 죽였다.**
> 발송 경로는 **DB 폴링**이다: `OutboxClaimer`(선점 · 결과 기록) → `DeliveryWorker`(채널 호출). Kafka · CDC 는 이 경로에 없다.

## 1. 문제와 사용자 영향

알림 서비스는 ① PG 에서 작업을 선점(`IN_FLIGHT` + 90초 임대)하고 커밋 → ② 트랜잭션 밖에서 Slack 호출 → ③ 결과를 PG 에 기록한다. 이 사이에 프로세스가 죽으면:

| 죽는 시점 | 걱정 | 사용자 영향 |
|---|---|---|
| 묶음 대기(10초) 중 | 기다리던 알림이 사라지나 | 알림 유실 |
| ② Slack 이 받은 뒤 ③ 기록 전 | 다시 보내나, 두 번 가나 | 알림 중복 |

## 2. 설계 (기존, 바꾸지 않음)

- 묶음 상태 = outbox 줄 그 자체 (`50-grouping.md` D16). 메모리에 두지 않는다.
- 선점은 임대(`lease_until`)로. 임대가 끝난 `IN_FLIGHT` 줄은 다른 실행이 다시 가져간다 (`Repositories.lockDue` 의 `OR (status = 'IN_FLIGHT' AND lease_until < :now)`).
- 결과 기록은 `claim_token` 이 맞을 때만 (`finishIfOwner`). 늦은 기록은 버린다.
- Slack Incoming Webhook 은 멱등 키가 없다 → **중복을 막을 수단이 없다는 것을 알고 고른 설계** ("안 간 것"이 "두 번 간 것"보다 나쁘다, `30-delivery.md`).

## 3. 재현 방법

도구: `docs/alert/experiments/` (가짜 Slack · 작업 넣기 · notifier 기동). 실패 지점은 **운영 코드에 훅을 넣지 않고** 가짜 Slack 이 "받는 즉시 기록하고 응답을 30초 늦추게" 해서 통제했다 → notifier 는 Slack 호출 중(요청 시간 한도 5초)에 멈춰 있고, 그 사이 `kill -9`.

```bash
./gradlew :notifier:bootJar
python3 docs/alert/experiments/fake_slack_hold.py 18999 /tmp/slack.log /tmp/hold &
OUT=/tmp/monimo-exp docs/alert/experiments/notifier.sh run1
echo 30 > /tmp/hold                                   # E5/E6 만: 응답 지연
docs/alert/experiments/enqueue.sh k9 3                # 같은 그룹 3건
until [ -s /tmp/slack.log ]; do sleep 0.05; done      # 가짜 Slack 이 받는 순간
kill -9 $(cat /tmp/monimo-exp/notifier.pid)
rm -f /tmp/hold; OUT=/tmp/monimo-exp docs/alert/experiments/notifier.sh run2
```

환경: macOS · 로컬 compose PostgreSQL · notifier jar 1개 · group_wait 10초 · lease 90초 · request timeout 5초.

## 4. 결과 (실측 1회씩)

### E9-a. 묶음 대기 중 kill -9

| 시각 (UTC) | 사건 |
|---|---|
| 12:03:15.705 | 같은 그룹 작업 3개 등록 |
| 12:03:18.753 | **kill -9** (등록 3초 뒤, group_wait 10초 전) |
| 15초 뒤 | DB: 3줄 모두 `PENDING` · 시도 0 / 가짜 Slack 수신 0건 |
| 12:03:34.9 | 재시작 직후 **묶음 1건 발송** (`shop-order 경보 3건`) |
| | DB: 3줄 `SENT` · 이력 3줄 |

→ **유실 없음, 묶음도 유지.** 기다리는 상태가 PG 의 PENDING 줄이라 프로세스와 함께 사라지지 않는다.

### E5/E6. Slack 이 받은 직후 · DB 기록 전 kill -9

| 시각 (UTC) | 사건 |
|---|---|
| 12:03:54.976 | 작업 3개 등록 |
| 12:04:05.636 | 가짜 Slack **수신 1** (묶음 3건) |
| 12:04:05.675 | **kill -9** (수신 39ms 뒤) |
| 직후 | DB: 3줄 `IN_FLIGHT` · 시도 0 · 임대 12:05:35 까지 |
| 12:04:17 | 재시작. 5초 뒤에도 `IN_FLIGHT` (임대 안 끝남) |
| 12:05:36.312 | 임대 만료 후 회수 → 가짜 Slack **수신 2** (같은 묶음) |
| 12:05:36.36 | DB: 3줄 `SENT` · 시도 1 · 이력 3줄 `SUCCESS` retry_count 0 |

| 대조 | 값 |
|---|---|
| 외부 수신 횟수 | **2번** (같은 메시지) |
| DB 가 말하는 시도 | **1번**, 이력 SUCCESS · 재시도 0 |
| 유실 | 없음 |
| 다시 보내기까지 | 첫 수신 뒤 **91초** (= 임대 90초) |

→ **중복 1회 발생.** 첫 시도는 기록되기 전에 죽었으므로 DB 에는 흔적이 없다. **DB 이력만 봐서는 중복을 알 수 없다.**

## 5. 모사 실험과의 비교

| | 기존 모사 (같은 JVM) | 이번 (실제 kill -9) |
|---|---|---|
| 방법 | `claim()` 만 부르고 `finish()` 를 안 부름 | JVM 강제 종료, jar 재기동 |
| E9 | 임대 만료 뒤 멈췄던 줄 + 새 형제 1건 (`GroupingTest`) | 묶음 대기 중 종료 → 재시작 후 1건 |
| E6 | 가짜 Slack 2번 · 이력 1줄 (`DeliveryWorkerTest`) | 가짜 Slack 2번 · 이력은 줄마다 1줄 · DB 시도 1 |
| 결론 | 같다 | 같다 — 모사가 실제 동작을 맞게 예측했다 |

## 6. 보장 범위와 남은 위험

- **보장**: 알림 서비스가 어느 시점에 죽어도 **유실은 없다** (최대 지연 = 임대 90초 + 재시작 시간).
- **보장하지 않음**: **정확히 한 번**. Slack 이 받은 뒤 기록 전에 죽으면 같은 알림이 한 번 더 간다. Slack Incoming Webhook 에 멱등 키가 없어 우리 쪽에서 막을 수 없다.
- **줄이는 방법 (미구현, 후보)**: 임대를 짧게(재발송까지 빨라지지만 느린 Slack 호출 중 다른 실행이 가져갈 위험) · 메시지에 사건 UUID 를 넣어 사람이 같은 알림임을 알게 하기 · 멱등 키를 받는 채널(PagerDuty `dedup_key` 등)은 그 키 사용.
- **확인하지 못한 범위**: 실측 1회씩 · notifier 1대 · 로컬. 인스턴스 여러 대에서 임대 만료 직후 두 실행이 경쟁하는 경우, 재시작 없이 다른 인스턴스가 회수하는 경우, Slack 이 실제로 느린 경우는 재지 않았다. 임대 중 원래 실행이 살아 돌아와 늦게 기록하려는 경우는 `claim_token` 테스트(E5 모사)로만 확인했다.
