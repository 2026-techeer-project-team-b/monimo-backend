# 결정 : ADR `#51`

정본은 [`../../design/01-decisions.md`](../../design/01-decisions.md) 의 `#51`. 여기는 요약과 길 안내만.

## 4요소

| | |
|---|---|
| **채택** | ① 일시 장애에는 **`ContainerPausingBackOffHandler`(pause)로 기다린다**(pause 중에도 `poll` 이 돌아 5분 천장이 안 걸린다) ② 대기를 **세 단**으로 : 확실한 일시 장애 10분 · 모르는 실패 1분 · 확실한 독성 0(`ExponentialBackOff(2초, 2.0배, 최대 30초)`, 갈라 주는 수단은 `setBackOffFunction`) ③ **`319` 를 재시도에서 뺀다** ④ **분류를 뒤집는다**(DLQ 목록만 들고 나머지는 재시도. `243` · `745` · `439` · `565` 는 긴 쪽으로 올림) ⑤ `setFailIfSendResultIsError` 켠다 ⑥ `x-dlq-attempt` 헤더를 심는다 ⑦ `verifyPartition` 은 건드리지 않는다 |
| **기각 ①** 지금 그대로 | 4초 만에 포기하고 버린다. 재배포 중 10초만 죽어도 유실 |
| **기각 ②** 실패하면 전부 DLQ | **DLQ 가 원본 토픽의 복사본이 된다.** 조사한 다섯 파이프라인 중 sink 장애를 DLQ 로 보내는 곳이 없다. OTel OpenSearch exporter 가 같은 실수를 "silent data loss" 버그로 고친 사례가 있다 |
| **기각 ③** 무한 · 긴 `FixedBackOff` (C) | 기본 `BackOffHandler` 가 스레드를 재워서 **5분 넘으면 리밸런스.** 적재가 멈추고 재처리가 생기며 여러 대면 남의 파티션까지 흔들린다 |
| **기각 ④** `CommonContainerStoppingErrorHandler` (E) | **스스로 안 살아난다.** 파드는 `Running` · HTTP 200 인데 리스너만 멈춰 **K8s 안전망이 안 걸린다.** 드러내려면 헬스 지표를 직접 만들어야 해 이슈가 커진다 |
| **기각 ⑤** `@RetryableTopic` | 배치 리스너 미지원 · 파티션 내 순서 깨짐 · 일시 장애 때 전량 복제 |
| **기각 ⑥** `ReplacingMergeTree` | `spans` 중복은 합쳐지지만 **집계 MV 는 insert 시점에 이미 세었고 merge 를 안 본다** |
| **기각 ⑦** `insert_deduplication_token` | `spans` 가 `ENGINE = MergeTree`(비복제)라 **되는지 확인 못 했다.** 되는지 모르는 기능에 기대지 않는다 |
| **기각 ⑧** 화이트리스트 보강만 | 들어야 하는 목록이 **서버가 복잡해질수록 늘어나고, 빠뜨리면 조용히 DLQ 로 쌓인다** |
| **되돌림** | ① 10분 넘는 장애가 반복돼 DLQ 가 처리 못 할 만큼 쌓이면 대기를 늘리거나 재처리 잡을 앞당긴다 ② 모르는 실패로 분류된 독성 메시지 때문에 1분 멈춤이 잦으면 그 코드를 DLQ 목록에 추가한다 ③ 여러 대로 늘렸을 때 한 파티션 장애가 다른 파티션까지 멈추면 `pausePartition` 으로 좁힌다 |

## 이 결정은 프롬프트로 내렸다

조사와 선택지를 다 읽고 **질문 31개를 거친 뒤** 사용자가 결정을 글로 쓰고 "그러니 그렇게 구현해줘" 로 끝냈다. 그 글이 채택 · 기각 · 사유 · 되돌림을 다 담고 있어서 글이 곧 결정이다.

- 원문 : [`research.md`](research.md) 8절, [`prompts.md`](prompts.md) 「구현 단계」, ADR `#51` 안의 「이 결정을 만든 프롬프트」
- 그 글을 쓸 수 있게 만든 질문 31개 : [`research.md`](research.md) 3절 · 6절. **그 과정에서 사용자 이해 10건과 AI 설명의 허점 6건이 바로잡혔다**

## 중요한 성질

`FailedRecordTracker` 가 **오프셋마다** 재시도 예산을 센다(jar 확인). 그래서 ClickHouse 가 1시간 죽어 있어도 **DLQ 로 가는 것은 10분에 한 건씩**이고 나머지는 Kafka 에 남는다. "DLQ 가 원본 복제가 된다" 는 위험은 기각안 ② 에만 해당한다.

## 검증 (구현 시 채울 것)

- [ ] 독성 메시지는 재시도 없이 `raw.dlq` 로 간다
- [ ] 일시 장애는 유실 없이 복구된다 (ClickHouse 를 멈췄다 되살려 `spans` 줄 수로 확인)
- [ ] 10분을 넘기면 DLQ 로 가고, 그 사이 리밸런스가 **나지 않는다**
- [ ] DLQ 레코드에 `x-dlq-attempt` 와 `kafka_dlt-original-*` 헤더가 붙는다
- [ ] `raw` 파티션 1 · 2 에서 실패한 것도 `raw.dlq`(파티션 1개)에 들어간다
