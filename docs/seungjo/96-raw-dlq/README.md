# `#96` 적재 실패분이 유실된다 : `raw.dlq` 로 보낸다

> **이 작업의 기술 설계 문서 한 장.** 문제 → 선택지 → 결정 → 장애가 나면 순서로 읽으면 끝난다.
> **구현 완료** (2026-10-06, ADR `#51`). 검증 결과는 아래와 [`decision.md`](decision.md).
> 이 폴더가 **8단계 리서치 꼴을 처음 쓴 이슈**다.

- 날짜 2026-10-04 시작 / 승조(`@SeungJo-02`) / 결정 ADR `#51` / 이슈 [`#96`](https://github.com/2026-techeer-project-team-b/monimo-backend/issues/96) · 브랜치 `fix/96-raw-dlq`
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
| E. `CommonContainerStoppingErrorHandler` | 유실 없음 · 사고가 확실히 드러난다 | **자동 복구 안 됨** |
| F. `@RetryableTopic` | 막히지 않는다 | **순서 깨짐 · 배치 미지원 · 전량 복제** |

C · D · E 의 "유실 없음" 은 **`raw` 보관 7일 안에 복구했을 때**만 참이다. 7일을 넘기면 Kafka 가 지우므로 어느 방법도 못 막는다.

각 선택지의 설명 · 출처 · 우리 데이터 · AI 가 틀린 것 · 함정 6개는 [`research.md`](research.md) 5 절에.

**조사가 뒤집은 것** : 조사한 다섯 파이프라인(Kafka Connect · OTel Collector · SigNoz · Debezium · Flink) 중 **sink 장애를 DLQ 로 보내는 곳이 하나도 없다.** DLQ 는 "이 데이터가 틀렸음" 전용 통로다. ADR `#34` 를 글자대로 "실패하면 DLQ" 로 구현하면 설계 의도(기각 사유에 "DLQ 가 받는 것은 독성 메시지" 라고 적혀 있다)를 어기게 된다.

## 결정

**D + 3단 분류 + `319` 제거 + 분류 뒤집기** (ADR `#51`, 4요소는 [`decision.md`](decision.md))

- **고른 것** : 일시 장애에는 `ContainerPausingBackOffHandler` 로 **기다린다.** pause 중에도 `poll` 이 돌아 **5분 천장이 안 걸린다.** 대기는 세 단(확실한 일시 장애 10분 · 모르는 실패 1분 · 확실한 독성 0). 분류는 **"DLQ 로 보낼 것 목록" 만 들고 나머지는 재시도**
- **버린 것과 이유** : 지금 그대로(4초 만에 버림) · 전부 DLQ(**DLQ 가 원본 복제가 된다.** 다섯 파이프라인 중 sink 장애를 DLQ 로 보내는 곳이 없다) · C(5분 넘으면 **리밸런스**) · E(**스스로 안 살아나고** 파드는 `Running` 이라 K8s 안전망이 안 걸린다) · `@RetryableTopic`(순서 깨짐) · `ReplacingMergeTree`(**MV 는 merge 를 안 본다**) · `insert_deduplication_token`(비복제 `MergeTree` 에서 되는지 **확인 못 함**)
- **되돌리는 조건** : 10분 넘는 장애가 반복돼 DLQ 가 처리 못 할 만큼 쌓이면 · 모르는 실패로 분류된 독성 메시지 때문에 1분 멈춤이 잦으면 · 여러 대로 늘렸을 때 한 파티션 장애가 다른 파티션까지 멈추면

**결정을 만든 프롬프트 원문**은 [`research.md`](research.md) 8절과 ADR `#51` 안에 있다. 질문 31개를 거친 뒤에 쓴 글이다.

## 장애가 나면

| 무엇이 죽으면 · 틀리면 | 어떻게 되나 | 어떻게 알아채나 |
|---|---|---|
| ClickHouse 가 **10분 안에** 되살아난다 | **유실 0.** pause 로 기다렸다가 자동으로 적재한다 | `monimo.ingester.*` 카운터가 멈췄다 다시 오른다. 리밸런스 로그는 **안 난다** |
| ClickHouse 가 **10분 넘게** 죽어 있다 | 오프셋마다 10분씩 기다리다 DLQ 로 간다. **10분에 한 건씩**이라 홍수는 아니다 | `raw.dlq` 오프셋이 오른다 |
| 독성 메시지가 들어온다 | 재시도 없이 바로 `raw.dlq`. **뒤가 막히지 않는다** | `raw.dlq` 오프셋 + `kafka_dlt-exception-*` 헤더 |
| 독성인데 **분류에 안 걸린다** | 1분 막히고 그 뒤 DLQ. **데이터는 안 잃는다** | 1분 멈춤이 로그에 남는다. 잦으면 되돌림 조건 ② |
| 디스크가 꽉 찬다 | `243` 이 긴 쪽에 올라가 있어 10분 기다린다. 그 안에 늘려 주면 유실 0 | `raw.dlq` 가 천천히 오른다 |
| **DLQ 발행 자체가 실패한다** | `setFailIfSendResultIsError` 때문에 예외가 올라와 **오프셋이 안 넘어간다.** 다시 시도한다 | 적재가 멈춘다. Kafka 자체 문제라 파이프라인 전체가 이미 멈춰 있다 |
| 적재 처리기가 **여러 대**가 된다 | pause 가 파티션 단위라 한 파티션 장애가 다른 파티션까지 멈출 수 있다 | **미확인.** 되돌림 조건 ③ |

표별 영향은 [`tables.md`](tables.md)(구현 시 채움). 파이프라인 전체 고장 표는 [`../../design/30-failure-modes.md`](../../design/30-failure-modes.md) ⑤ (이 이슈가 그 칸을 고친다).

## 어떻게 확인했나

| | 고치기 전 | 고친 뒤 |
|---|---|---|
| ClickHouse 정지 중 스팬 4개 → 되살림 | **226,417 그대로** (유실) | **226,421 → 226,425 (+4)** |
| 정지 중 재시도 | 10번을 4초에 다 쓰고 `exhausted` | WARN 3건(0 · 2 · 6초), `exhausted` **0** |
| 리밸런스 | : | **0건** (pause 라 `poll` 이 계속 돈다) |
| 독성 메시지(`0x0F`, 키 `logs` = 파티션 1) | 10번 재시도 뒤 버림 | **재시도 없이** `raw.dlq` 파티션 0 으로. 헤더 `x-dlq-attempt:1` · `x-dlq-reason:poison` · `kafka_dlt-original-topic:raw` |
| 독성 뒤의 정상 메시지 | : | 막히지 않고 `consumed 0 → 1` |
| 테스트 | 87건 | **104건** (분류기 11 · 에러 핸들러 6 포함) |
| `check-pipeline.sh` | 통과 | 통과 (traces 6 · metrics 3 · logs 3) |

- **라이브러리 jar 직접 확인 17건** : `isRetryable()` public · `retryOnFailures` 가 DNS 실패를 대상에 안 넣음 · 기본 접미사 `-dlt` · `verifyPartition` 기본 `true`(바이트코드) · `setMaxRecoveryFailures` 3.3.16 에 없음 · `setBackOffFunction` 존재 · `Consumer.pause/resume` 가 `poll` 과 별개 · pause 가 **컨테이너 단위** · `verifyPartition` 은 **세 인자 `accept` 에서만** 돈다 등 ([`research.md`](research.md) 5.7)
- **구현 중 버그 1건을 수동 검증이 잡았다** : 카운터 래퍼가 `consumer` 를 떨어뜨려 `verifyPartition` 을 꺼 버렸다. 테스트는 토폴로지가 달라(테스트 `raw` 가 파티션 1개) 못 잡았다. 자세한 것은 [`decision.md`](decision.md)

## 읽는 순서

1. [`research.md`](research.md) : **1 · 2 절부터.** 무슨 작업인지와 DLQ · client-v2 · 오프셋 · 데이터 흐름 설명이 거기 있다
2. [`research.md`](research.md) 5 절 : 선택지 6개 · 다섯 파이프라인 비교 · 우리 데이터 · 함정
3. [`prompts.md`](prompts.md) : 조사 프롬프트 원문과 고쳐 물은 말
4. [`decision.md`](decision.md) : ADR `#51` 4요소
5. [`tables.md`](tables.md) : 어느 표가 영향을 받나 (구현 시 채움)

## 이 이슈에서 배운 것 (세 줄)

- 문서(`30-failure-modes.md`)를 쓰다가 **일부러 깨뜨려 보고** 유실을 찾았다. 코드만 읽었으면 "10번 재시도" 를 안전하다고 읽었을 것이다
- ADR 이 정해 둔 것(`#34` DLQ)을 **글자대로 구현하면 더 나빠질 수 있다.** 기각 사유에 적힌 "독성 메시지" 라는 단어가 범위를 정하고 있었다
- **테스트가 통과해도 토폴로지가 실제와 다르면 거짓 안심이다.** 컨테이너 테스트 5건이 통과한 채로 `verifyPartition` 경로가 깨져 있었고, compose(`raw` 파티션 3개)에서 손으로 넣어 보고 잡았다. 그래서 테스트 Kafka 의 토픽을 compose 와 같게 미리 만들도록 바꿨다
