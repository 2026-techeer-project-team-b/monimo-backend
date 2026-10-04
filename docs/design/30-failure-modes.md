# 고장 나면 어떻게 되나 (수집 경로)

> 파이프라인의 각 칸이 죽었을 때 **데이터가 어떻게 되고, 우리가 그걸 어떻게 알아채는가**.
> 결정 로그(`01-decisions.md`)는 "왜 그렇게 정했나" 를 적고, 이 문서는 "그래서 고장 나면 어떻게 되나" 를 적는다.
>
> 적힌 것은 **코드에서 확인했거나 직접 깨뜨려 본 것**이다. 확인하지 않은 것은 그렇다고 표시했다.
> 수집 경로(에이전트 → 수집기 → Kafka → 적재 처리기 → ClickHouse)만 다룬다. 조회 · 알림은 각 파트 문서에.

## 한눈에

```
쇼핑몰 앱            수집기              Kafka            적재 처리기        ClickHouse
(OTel Agent)  ──▶  :4317 gRPC  ──▶   raw 토픽   ──▶   @KafkaListener ──▶  spans 등
     ①                 ②               ③                  ④                 ⑤
                                                              └──▶ PostgreSQL (agents)
                                                                       ⑥
```

| 무엇이 죽으면 | 어떻게 되나 | 어떻게 알아채나 |
|---|---|---|
| ① 쇼핑몰 앱 · 에이전트 | 그 앱의 신호가 끊긴다. 에이전트 버퍼에 있던 것은 사라진다 | **직접은 못 본다.** 에이전트 내부 지표(버린 건수 · 버퍼 점유율)는 OTel 안쪽이라 우리가 만들 수 없다(ADR `#33`). 파수꾼 카나리가 쇼핑몰을 직접 불러 간접 확인한다(ADR `#41`). 탐지의 `AGENT_DOWN` 규칙(`last_seen_at` 부재)도 걸린다 |
| ② 수집기 | 에이전트가 gRPC 연결에 실패한다. 데이터는 **에이전트 버퍼에 쌓였다가** 수집기가 돌아오면 다시 온다. 버퍼가 넘치면 거기서 사라진다 | 파수꾼 카나리. **수집기 자체 probe 는 아직 없다**(`AGENTS.md` §6) |
| ③ Kafka | 수집기가 `acks=all` 이라 저장이 끝나기 전에는 성공을 답하지 않는다. 실패하면 gRPC **`UNAVAILABLE`** 로 돌려보내 **에이전트가 같은 배치를 다시 보낸다.** 받은 척하고 버리지 않는다 | 에이전트 쪽 재시도 로그. 수집기 수신 카운터는 오르는데 적재 처리기 소비 카운터가 안 오른다 (`check-pipeline.sh`) |
| ④ 적재 처리기 | Kafka 가 메시지를 들고 있다. 돌아오면 오프셋부터 이어서 읽는다. **유실 없음** | `check-pipeline.sh` 의 수신 수 ↔ 소비 수 대조. **probe 는 아직 없다** |
| ⑤ ClickHouse | **메시지가 사라진다.** 10번 재시도(약 1초) 뒤 그 메시지를 건너뛰고 오프셋이 넘어간다. ClickHouse 가 돌아와도 **되돌아오지 않는다** | 적재 처리기 로그의 `Backoff ... exhausted`. 소비 카운터가 안 오른다. **자동 알림은 없다** |
| ⑥ PostgreSQL | ClickHouse 적재는 **이미 끝나 있다.** 파드 등록만 실패하고 메시지 처리는 멈추지 않는다. `peer_service` 는 안 채워져 서버맵에 `EXTERNAL` 로 보인다(틀린 간선보다 낫다) | `monimo.ingester.agents{outcome=...}` 카운터, 경고 로그 |

## ⑤ 가 지금 가장 아픈 곳

**직접 깨뜨려 확인했다** (2026-10-04, 로컬 compose).

1. ClickHouse 컨테이너를 멈추고 telemetrygen 으로 트레이스 2건을 보냈다
2. 적재 처리기 로그:
   ```
   Backoff FixedBackOff{interval=0, currentAttempts=10, maxAttempts=9} exhausted for raw-0@264
   Caused by: java.net.UnknownHostException: clickhouse
   ```
3. ClickHouse 를 되살리고 20초 기다린 뒤 조회 → **0건**
4. 같은 조건에서 새로 보낸 것은 정상 적재(대조군 확인)

즉 **간격 0초로 10번 시도한 뒤 포기하고 그 메시지를 버린다.** 이건 우리가 고른 동작이 아니라 **설정을 안 해서 스프링 기본값(`DefaultErrorHandler`)이 적용된 것**이다. `ingester/src/main/resources/application.yml` 에 에러 핸들러 · 재시도 설정이 없다.

ADR `#34` 가 이미 답을 정해 뒀다: **적재 실패분은 Kafka `raw.dlq`(보관 30일)로 보내고, 재처리는 적재 처리기의 관리 잡이 한다.** 아직 구현되지 않았다(이슈 H).

그때까지의 임시 완화책으로는 재시도 간격 · 횟수를 늘리는 방법이 있지만, **ClickHouse 가 오래 죽어 있으면 결국 같은 일이 벌어진다.** 근본 해결은 DLQ 다.

## 아직 답하지 못하는 것

`02-open-questions.md` 로 올릴 것들이다.

| 질문 | 왜 비어 있나 |
|---|---|
| ClickHouse **디스크가 가득 차면** | 끊긴 경우(⑤)만 확인했다. 디스크 포화는 거절 방식이 달라 재시도 뒤 같은 길로 가는지 확인하지 않았다 |
| Kafka **디스크가 가득 차면** | 수집기가 `UNAVAILABLE` 로 돌려보내는 것까지는 ③ 과 같겠지만 실제로 확인하지 않았다 |
| 에이전트 버퍼 크기 · 재시도 횟수 | OTel 기본값을 우리가 설정하지 않았고 문서에도 없다 |
| 수집기가 **여러 대**일 때 | 지금은 1대 기준이다. 샘플링은 trace ID 해시라 대수와 무관하지만, 명령 채널 팬아웃은 10대 초과 시 재검토 대상(ADR `#31`) |

## 근거

| 주장 | 어디서 확인했나 |
|---|---|
| `acks=all` | `collector/src/main/resources/application.yml` |
| 실패 시 `UNAVAILABLE` 반환 | `collector/.../inbound/otlp/OtlpResponder.kt` |
| 적재 실패 시 10회 재시도 후 건너뜀 | 직접 재현 (위 ⑤). 설정은 `ingester/.../application.yml` 에 **없음** = 스프링 기본값 |
| PG 실패가 적재를 막지 않음 | `ingester/.../inbound/kafka/RawConsumer.kt` 의 `agentRegistry.register` 가 `save` **뒤**, 어댑터가 예외를 삼킨다 |
| 수신 ↔ 소비 대조 | `scripts/check-pipeline.sh` (CI `dev-infra` 가 매 PR 실행) |
| DLQ 설계 | ADR `#34` |
| 에이전트 내부 지표를 못 만듦 | ADR `#33` "빠지는 것" |

## 같이 보는 것

- 결정 기록 : [`01-decisions.md`](01-decisions.md)
- 미해결 질문 : [`02-open-questions.md`](02-open-questions.md)
- 선택지 비교 · 출처 · 프롬프트 : [`../seungjo/`](../seungjo/README.md)
- 지금 막혀 있는 것 : [`AGENTS.md`](../../AGENTS.md) §6
