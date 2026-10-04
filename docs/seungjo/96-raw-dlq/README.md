# `#96` 적재 실패분이 유실된다 : `raw.dlq` 로 보낸다

> **이 작업의 기술 설계 문서 한 장.** 문제 → 선택지 → 결정 → 장애가 나면 순서로 읽으면 끝난다.
> **진행 중** : 조사까지 끝났고 결정은 아직이다. 결정 · 장애 칸은 [`research.md`](research.md) 의 8 단계를 지난 뒤 채운다.
> 이 폴더가 **8단계 리서치 꼴을 처음 쓴 이슈**다.

- 날짜 2026-10-04 시작 / 승조(`@SeungJo-02`) / 결정 ADR 미정 / 이슈 [`#96`](https://github.com/2026-techeer-project-team-b/monimo-backend/issues/96) · 브랜치 `fix/96-raw-dlq`
- 유저 플로우에서 어디: 에이전트 → 수집기 → Kafka → **적재 처리기** → ClickHouse 중 **적재 처리기**. 신호가 저장소에 들어가는 마지막 관문이라 여기서 버리면 되돌릴 데가 없다

## 문제

ClickHouse 가 잠깐 죽어 있는 동안 들어온 메시지를 **적재 처리기가 조용히 버린다.** `ingester/application.yml` 에 에러 핸들러 설정이 없어 스프링 기본값(`DefaultErrorHandler` = `FixedBackOff(0, 9)`)이 적용되고, **간격 0초로 10번 시도한 뒤 오프셋을 넘긴다.**

| 재 본 것 | 값 |
|---|---|
| 정지 전 `monimo.spans` | 226,417줄 |
| 정지 중 트레이스 2건(스팬 4개) 전송 → 되살린 뒤 | **226,417줄 (그대로)** |
| 대조군 : 되살린 뒤 같은 명령 | 226,421줄 (**+4**) |
| 재시도가 끝나는 데 걸린 시간 | **약 4초** (간격 0초 × 10회) |
| Kafka 에는 남아 있나 | 있다. `raw:0:271`, 건너뛴 오프셋 `raw-0@266` · `@267` |
| `raw.dlq` | `raw.dlq:0:0` : 토픽만 있고 넣는 코드가 없다 |

**무엇이 깨지나** : 재배포 한 번에 ClickHouse 가 10초만 안 떠 있어도 그 사이 신호가 영구 유실된다. [`../../design/30-failure-modes.md`](../../design/30-failure-modes.md) 가 이 칸을 **"지금 가장 아픈 곳"** 으로 적어 뒀다.

## 선택지

| 방법 | 얻는 것 | 포기하는 것 |
|---|---|---|
| A. 지금 그대로 | 코드 0줄 | **데이터** |
| B. 예외 분류 + 전부 DLQ | 소비가 안 멈춘다 | 일시 장애 때 **DLQ 가 원본 복사본**이 된다 |
| C. 무한 BackOff | 유실 없음 · 설정 한 줄 | 간격이 5분 넘으면 **리밸런스** |
| D. `ContainerPausingBackOffHandler` | 유실 없음 · 자동 복구 · **리밸런스 없음** | 빈 2개 |
| E. `CommonContainerStoppingErrorHandler` | 사고가 확실히 드러난다 | **자동 복구 안 됨** |
| F. `@RetryableTopic` | 막히지 않는다 | **순서 깨짐 · 배치 미지원 · 전량 복제** |

각 선택지의 설명 · 출처 · 우리 데이터 · AI 가 틀린 것 · 함정 6개는 [`research.md`](research.md) 5 절에.

**조사가 뒤집은 것** : 조사한 다섯 파이프라인(Kafka Connect · OTel Collector · SigNoz · Debezium · Flink) 중 **sink 장애를 DLQ 로 보내는 곳이 하나도 없다.** DLQ 는 "이 데이터가 틀렸음" 전용 통로다. ADR `#34` 를 글자대로 "실패하면 DLQ" 로 구현하면 설계 의도(기각 사유에 "DLQ 가 받는 것은 독성 메시지" 라고 적혀 있다)를 어기게 된다.

## 결정

**대기 중.** [`research.md`](research.md) 의 8 단계(승조가 쓰는 구현 프롬프트)를 지나면 ADR 로 옮기고 여기에 요약한다.

## 장애가 나면

**대기 중.** 결정이 나면 [`tables.md`](tables.md) 와 함께 채운다.

## 어떻게 확인했나

- **유실 직접 재현** : ClickHouse 정지 → 전송 → 되살림 → 0건. 대조군 +4 로 대조
- **예외 사슬 전문 확보** : `ConnectionInitiationException` → `UnknownHostException`. `ExecutionException` 에 안 싸여 올라오는 것 확인
- **라이브러리 jar 직접 확인 6건** : `isRetryable()` public · `retryOnFailures` 존재 · 기본 접미사 `-dlt` · `verifyPartition` 기본 `true`(바이트코드) · `setMaxRecoveryFailures` 3.3.16 에 없음 · 컨테이너 상태 API 존재

## 읽는 순서

1. [`research.md`](research.md) : **1 · 2 절부터.** 무슨 작업인지와 DLQ · client-v2 · 오프셋 · 데이터 흐름 설명이 거기 있다
2. [`research.md`](research.md) 5 절 : 선택지 6개 · 다섯 파이프라인 비교 · 우리 데이터 · 함정
3. [`prompts.md`](prompts.md) : 조사 프롬프트 원문과 고쳐 물은 말
4. [`tables.md`](tables.md) : 어느 표가 영향을 받나 (결정 후 채움)

## 이 이슈에서 배운 것 (세 줄)

- 문서(`30-failure-modes.md`)를 쓰다가 **일부러 깨뜨려 보고** 유실을 찾았다. 코드만 읽었으면 "10번 재시도" 를 안전하다고 읽었을 것이다
- ADR 이 정해 둔 것(`#34` DLQ)을 **글자대로 구현하면 더 나빠질 수 있다.** 기각 사유에 적힌 "독성 메시지" 라는 단어가 범위를 정하고 있었다
- 조사 결과를 그대로 쓰지 않고 **jar 를 열어 봤더니 3건이 틀렸거나 우리 버전에 없었다**
