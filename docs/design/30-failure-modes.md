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
| ⑤ ClickHouse | **10분 안에 되살면 유실 없음**(`#96` · ADR `#51`). 적재 처리기가 pause 로 기다리다(2초 → 4초 → ... → 30초) 되살면 그 자리부터 다시 넣는다. 10분을 넘기면 그 메시지는 `raw.dlq` 로 가고(오프셋마다 10분씩이라 1시간 장애에 약 6건) 나머지는 `raw` 에 남는다(7일). 데이터가 틀린 메시지(파싱 실패 · `117` · `319` 등)는 재시도 없이 바로 `raw.dlq` | 적재 처리기 로그의 `Host 'clickhouse:8123' unknown` 반복(재시도 중) · `raw-N@M → raw.dlq (reason=...)` (DLQ 로 감). 지표 `monimo.ingester.dlq{reason}`. `check-pipeline.sh` 가 숫자가 안 맞으면 DLQ 건수를 읽어 알려 준다. **자동 알림은 아직 없다** |
| ⑥ PostgreSQL | ClickHouse 적재는 **이미 끝나 있다.** 파드 등록만 실패하고 메시지 처리는 멈추지 않는다. `peer_service` 는 안 채워져 서버맵에 `EXTERNAL` 로 보인다(틀린 간선보다 낫다) | `monimo.ingester.agents{outcome=...}` 카운터, 경고 로그 |

## ⑤ 는 고쳤다 (`#96` · ADR `#51`)

**2026-10-04 에 깨뜨려 보고 유실을 찾았고, 2026-10-06 에 고쳐서 다시 깨뜨려 봤다.**

| | 2026-10-04 (고치기 전) | 2026-10-06 (고친 뒤) |
|---|---|---|
| ClickHouse 정지 중 스팬 4개 전송 → 되살림 | `spans` **226,417 그대로** | `spans` **226,421 → 226,425 (+4)** |
| 정지 중 로그 | `Backoff FixedBackOff{interval=0, currentAttempts=10, maxAttempts=9} exhausted` | `exhausted` **0건.** `Host 'clickhouse:8123' unknown` WARN 3건(0 · 2 · 6초 = 2초 → 4초 → 8초) |
| 리밸런스 | : | **0건** |

고치기 전의 원인은 **설정을 안 해서 스프링 기본값(`SeekUtils.DEFAULT_BACK_OFF = FixedBackOff(0, 9)`)이 적용된 것**이었다. 간격 0초로 10번(약 4초) 시도하고 오프셋을 넘겼다.

고친 방식은 ADR `#34` 를 **글자대로 "실패하면 DLQ" 로 구현하지 않았다.** 조사한 다섯 파이프라인(Kafka Connect · OTel Collector · SigNoz · Debezium · Flink) 중 sink 장애를 DLQ 로 보내는 곳이 하나도 없었다. DLQ 는 "데이터가 틀렸음" 전용이고, 저장소가 죽은 것은 **기다린다** :

```
적재 실패
  ├─ 데이터가 틀렸다 (protobuf 못 풂 · 117 · 27 · 53 · 41 · 72 · 319)  → 재시도 0회 → raw.dlq
  ├─ 저장소가 닿지 않는다 · 디스크 꽉 참 등                            → 10분까지 pause 로 기다림 → 그 뒤 raw.dlq
  └─ 모르는 실패                                                      → 1분까지 기다림 → 그 뒤 raw.dlq
```

pause 는 `poll()` 을 계속 돌리므로 `max.poll.interval.ms`(5분) 천장에 걸리지 않는다. 재시도 예산은 오프셋마다 따로라 1시간 장애에도 DLQ 로 가는 것은 약 6건이다. 선택지 비교 · 질문 31개 · 결정 프롬프트 원문은 [`../seungjo/96-raw-dlq/`](../seungjo/96-raw-dlq/README.md).

**아직 남은 것** : `raw.dlq` 에 들어간 것을 다시 `raw` 로 흘려보내는 **재처리 잡**(ADR `#34` : 같은 이미지 · K8s Job · 다른 `group-id`). 그리고 DLQ 에 쌓이는 것을 **알리는 경보**는 없다.

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
| 적재 실패 시 세 단으로 갈라 기다리거나 DLQ | `ingester/.../inbound/kafka/RawErrorHandlerConfig.kt` · `FailureClassifier.kt` · `application.yml` 의 `monimo.ingester.retry.*`. 고치기 전(10회 뒤 건너뜀)과 뒤(+4) 둘 다 직접 재현 (위 ⑤) |
| PG 실패가 적재를 막지 않음 | `ingester/.../inbound/kafka/RawConsumer.kt` 의 `agentRegistry.register` 가 `save` **뒤**, 어댑터가 예외를 삼킨다 |
| 수신 ↔ 소비 대조 | `scripts/check-pipeline.sh` (CI `dev-infra` 가 매 PR 실행) |
| DLQ 설계 | ADR `#34` |
| 에이전트 내부 지표를 못 만듦 | ADR `#33` "빠지는 것" |

## 같이 보는 것

- 결정 기록 : [`01-decisions.md`](01-decisions.md)
- 미해결 질문 : [`02-open-questions.md`](02-open-questions.md)
- 선택지 비교 · 출처 · 프롬프트 : [`../seungjo/`](../seungjo/README.md)
- 지금 막혀 있는 것 : [`AGENTS.md`](../../AGENTS.md) §6
