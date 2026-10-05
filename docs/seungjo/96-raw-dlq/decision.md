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

## 검증 (2026-10-06, 로컬 compose + 테스트)

- [x] **독성 메시지는 재시도 없이 `raw.dlq` 로 간다** : 키 `logs` 로 바이트 `0x0F`(없는 wire type) 를 넣자 `raw.dlq:0:0 → 0:1`, 카운터 `poison=1`, 로그 `raw-1@39 → raw.dlq (reason=poison, cause=Protocol message tag had invalid wire type.)`. 재시도 로그 없음
- [x] **일시 장애는 유실 없이 복구된다** : ClickHouse 정지 → 스팬 4개 전송 → 되살림 → `spans` **226,421 → 226,425 (+4)**. 같은 조건에서 고치기 전에는 그대로였다. 정지 중 로그에 `exhausted` 0건, 재시도 WARN 3건(0초 · 2초 · 6초 = 지수 백오프 2 → 4 → 8)
- [x] **리밸런스가 나지 않는다** : 정지 · 복구 구간의 새 로그에 `rebalance` · `revoked` · `partitions assigned` **0건.** 컨슈머 그룹 LAG 전부 0. 10분을 넘기는 시험은 하지 않았다(ClickHouse 를 10분 넘게 멈추지 않음 : 「확인 못 한 것」)
- [x] **헤더가 붙는다** : compose 의 DLQ 레코드에서 `x-dlq-attempt:1` · `x-dlq-reason:poison` · `kafka_dlt-original-topic:raw` · `kafka_dlt-exception-fqcn:...ListenerExecutionFailedException` 확인. 테스트(`RawErrorHandlerTest`)도 같은 것을 단언
- [x] **`raw` 파티션 1 에서 실패한 것도 `raw.dlq`(파티션 1개)에 들어간다** : DLQ 레코드 `Partition:0`, 원본 파티션 헤더 `1`. 로그 `Destination resolver returned non-existent partition raw.dlq-1, KafkaProducer will determine partition` = `verifyPartition` 이 번호를 비운 증거. **첫 구현은 이게 깨져 있었다**(아래)
- [x] 뒤가 막히지 않는다 : 독성 다음에 넣은 정상 로그가 `consumed 0 → 1`. `check-pipeline.sh` 통과(traces 6 · metrics 3 · logs 3)
- [x] 테스트 : `FailureClassifierTest` 12건 · `RawErrorHandlerTest` 7건(파티션 1 · 2차 통과 `x-dlq-attempt=2` 시나리오 포함) · `BackOffMappingTest` 7건(세 단 대응을 컨테이너 없이 고정) · 적재 처리기 전체 **113건** 통과

### 코드 리뷰(만든 쪽과 다른 자리)에서 나온 것

리뷰어가 spring-kafka 3.3.16 · client-v2 0.10.0 소스를 직접 열어 봤다. **물어본 설계 판단 다섯 개는 전부 맞다고 확인**(`setCommitRecovered` 안 켠 것 · BackOff 상태가 레코드별인 것 · 헤더가 덧붙여지는 것 · `failIfSendResultIsError` 가 전송 완료를 기다리는 것 · 스케줄러 충돌 없음). 그 위에 고친 것 :

| 지적 | 한 것 |
|---|---|
| 라이브러리 화이트리스트가 14개가 아니라 **15개**(`159 TIMEOUT_EXCEEDED` 누락) | 바이트코드로 재확인. 주석 · ADR · 리서치 숫자 수정. 조사 B 의 오류로 등재 |
| `RawSignal.fromKey` 의 예외가 UNKNOWN(1분, 컨테이너 전체 멈춤)으로 떨어짐 | `common` 에 `UnknownRawKeyException` 을 두고 그 타입만 POISON. `IllegalArgumentException` 전체를 잡으면 변환기 IAE 까지 걸린다는 지적대로 |
| `check-pipeline.sh` 의 `${x:-0}` 가 `set -e` 때문에 **죽은 코드** | 두 함수 끝에 `\|\| true`. 가짜 주소로 돌려 `x=0` 확인. `#92` 의 `metric_value` 도 같은 결함이었다 |
| `x-dlq-attempt` 가 재처리마다 **쌓인다** | `SingleRecordHeader` 로 교체. 테스트가 2차 통과에 값 `2` · 개수 `1` 을 단언 |
| 컨테이너 테스트가 main 의 10분 · 1분을 그대로 써서 실패 하나가 **다음 스펙까지 멈춤** | 테스트 리소스에 2초 · 1초 |
| `RawConsumerTest` 절대값 단정이 **스펙 순서 의존**이 됨 | `before + n` 상대값으로 |
| 세 단 대응을 **아무 테스트도 안 고정** | `BackOffMappingTest` : POISON 은 첫 실패에 STOP · TRANSIENT 는 2 → 4 → 8 → 16 → 30 · UNKNOWN 은 60초 뒤 STOP · 인스턴스 비공유 |
| `rootMessage` 에 깊이 제한 없음(A→B→A 순환이면 무한 루프) | `ROOT_MAX_DEPTH = 10` |
| 틀린 주석 5곳 | "총 maxElapsed 까지" → 간격의 합이고 벽시계는 더 길다 · "가이드의 수동 확인" → ADR 검증 절 · 스케줄러 지뢰 한 줄 등 |

### 구현 중 잡은 버그 하나

`verifyPartition` 이 어긋남을 막아 준다는 것은 맞았지만, **카운터를 세려고 감싼 래퍼가 그 검사를 꺼 버렸다.** `ConsumerRecordRecoverer { record, failure -> dlq.accept(record, failure) }` 두 인자 판은 `consumer = null` 을 넘기고, 파티션 검사는 `consumer` 가 있어야 돈다. 결과 : `raw` 파티션 1 의 독성 메시지가 `raw.dlq` 파티션 1(없음)로 가려다 60초 타임아웃 → `failIfSendResultIsError` 가 세움 → 그 레코드에서 영원히 멈춤(`lag=1`).

**컨테이너 테스트 5건은 통과한 채였다.** 테스트 Kafka 의 `raw` 가 자동 생성되어 파티션 1개라 어긋남이 없었다. compose(`raw` 3개)에서 키 `logs` 로 수동 검증하다 잡았다. 고친 것 : `ConsumerAwareRecordRecoverer`, 그리고 `TestInfraConfig` 가 토픽을 compose 와 같게(`raw` 3 · `raw.dlq` 1) 미리 만들어 테스트가 그 경로를 밟게 했다. **테스트 토폴로지가 실제와 다르면 통과한 테스트가 거짓 안심을 준다.**
