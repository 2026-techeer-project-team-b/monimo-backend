# Kafka 재처리 시 집계 정확성 — 1단계: 기준선 재현

> 2026-10-11 · 알림 파트(박유경) · 격리 환경 실측 3회.
> **이번 단계는 문제를 재현하고 근거를 모으는 것까지다. 해결책은 정하지 않았다** (2단계에서 비교).
> 운영 코드 · 스키마는 바꾸지 않았다. 공유 compose 의 `ingester` 그룹 오프셋과 로컬 공유 데이터는 건드리지 않았다.

## 1. 질문

적재 처리기가 **ClickHouse 에 넣은 뒤, Kafka 오프셋을 커밋하기 전**에 죽으면 다시 뜰 때 같은 레코드를 또 처리한다. 그러면
- `spans` 원본에 같은 스팬이 두 줄 생기나?
- 그 위의 `service_health_1m` 집계(탐지가 읽는 에러율)가 틀어지나?

가설: 정상 90 · 오류 10 중 오류 레코드가 한 번 더 처리되면 에러율 10% → 20/110 ≈ 18.18%. **이 숫자는 가설이었고, 아래 실측으로 확인했다.**

## 2. 읽기 분석 (코드 · 설정 · 실제 서버 값)

### 2-1. 소비 → 저장 → 커밋 순서

| 항목 | 값 | 근거 |
|---|---|---|
| 리스너 | 레코드 하나씩 (`onMessage(record)`) | `RawConsumer.kt` |
| 순서 | protobuf 풀기 → `spanStore.save` (CH insert) → `agentRegistry.register` (PG) → 카운터 | `RawConsumer.onMessage` |
| 자동 커밋 | `enable.auto.commit=false` (Spring Kafka 가 커밋) | 기동 로그 ConsumerConfig |
| 커밋 방식 (ack-mode) | 설정 없음 → Spring Kafka 기본 `BATCH`: **poll 한 묶음을 다 처리한 뒤** 한 번에 `commitSync` | `application.yml` 에 `ack-mode` 없음. 실측: 중단점 스택 `pollAndInvoke → doProcessCommits → commitIfNecessary → commitSync` (§3-3) |
| poll 묶음 | `max.poll.records=500` | 기동 로그 |
| 실패 처리 | `DefaultErrorHandler` + pause 백오프, 종류별 10분/1분/0 뒤 `raw.dlq` | `RawErrorHandlerConfig.kt` (ADR #51) |
| 그룹 · 시작점 | `ingester`, `auto.offset.reset=earliest` | `application.yml` |
| 죽은 소비자 감지 | `session.timeout.ms=45000` (기본) | 기동 로그 |

→ **저장과 커밋은 원자적이지 않다.** CH insert 는 끝났는데 커밋 전에 죽으면, 커밋된 위치부터 다시 읽어 같은 레코드를 또 넣는다. 이건 Kafka 소비자의 일반적인 "최소 한 번(at-least-once)" 성질이고, 문제는 **받는 쪽(CH)이 중복을 거르느냐**다.

### 2-2. ClickHouse 쪽에 중복을 거르는 장치가 있나

| 장치 | 지금 값 | 이 경우에 걸러 주나 |
|---|---|---|
| `spans` 엔진 | `MergeTree` (ORDER BY service_name, span_name, toDateTime(start_time)) | ✗ — `ReplacingMergeTree` 가 아니라 병합 때도 같은 행을 합치지 않는다 |
| insert 블록 중복 제거 | `insert_deduplicate=1` 이지만 **비복제 표는 `non_replicated_deduplication_window=0`** | ✗ — 창이 0 이라 꺼진 것과 같다 |
| 비동기 insert 중복 제거 | `async_insert=1` · `async_insert_deduplicate=0` | ✗ |
| insert 토큰 | 적재 처리기가 `InsertSettings()` 기본값, `insert_deduplication_token` 없음 | ✗ |
| MV 쪽 | `deduplicate_blocks_in_dependent_materialized_views=1` | ✗ — 원본 insert 가 중복 제거되지 않으니 MV 도 그대로 한 번 더 받는다 |
| `service_health_1m` | `AggregatingMergeTree` ← `mv_service_health_1m` (SERVER 스팬, `countState` · `sumState(status_code='ERROR')` 등) | ✗ — 집계 상태는 "더하기"라 같은 스팬이 두 번 오면 두 번 센다 |
| 조회 | `ServiceHealthRepository`: `countMerge(cnt)` · `sumMerge(err_cnt)` | 중복을 구분할 정보(trace_id · span_id)가 집계 표에 없다 |

→ **읽기만으로 예상: 원본도 집계도 중복된다.** 다만 원본은 `(trace_id, span_id)` 로 나중에 가려낼 수 있지만, 집계는 합쳐진 숫자라 가려낼 수 없다.

## 3. 실험

### 3-1. 격리 환경

- 실험 전용 Kafka(`exp-kafka`, 39092, `raw` 파티션 1개) · ClickHouse(`exp-ch`, 28123, **공유 compose 와 같은 이미지** → 같은 기본 설정, `db/clickhouse/V*.sql` 그대로 적용, 표 18개)
- 적재 처리기는 **develop 의 jar 그대로**, 접속 주소만 바꿔 실행 (PG 는 로컬 것을 읽기만 — 실험 서비스는 등록되지 않아 `agents` 에 쓰지 않는다)
- 그룹 이름은 운영과 같은 `ingester` 지만 **격리 Kafka 안의 그룹**이라 공유 그룹과 무관하다
- 실행마다 새 서비스 이름(`exp-reprocess-<시각>`)과 10분 전 1분 버킷을 써서 회차끼리 섞이지 않는다

### 3-2. 입력

수집기와 같은 형식(키 `traces`, 값 = `ExportTraceServiceRequest` 바이트)으로 레코드 2개:
1. 정상 SERVER 스팬 90개 (OK, HTTP 200) → 처리 · **커밋 확인**
2. 오류 SERVER 스팬 10개 (ERROR, HTTP 500) — 같은 서비스 · 같은 1분

### 3-3. "저장 후 · 커밋 전"을 정확히 맞추는 방법

운영 코드에 실험용 훅을 넣지 않았다. 적재 처리기를 JDWP 로 띄우고 **`jdb` 로 `KafkaConsumer.commitSync(Map, Duration)` 에 중단점**을 건 뒤 오류 레코드를 넣는다. 처리가 끝나고 커밋하려는 순간 JVM 이 멈춘다 → CH 에 들어간 것을 확인 → `kill -9`. 중단점에서 찍힌 스택:

```
KafkaConsumer.commitSync
KafkaMessageListenerContainer$ListenerConsumer.doCommitSync
  .commitSync → .commitOffsets → .commitIfNecessary → .processCommits
  .doProcessCommits → .pollAndInvoke → .run
```

리스너 호출이 끝난 뒤 `pollAndInvoke` 안에서 커밋한다 = `BATCH` 동작과 일치.

재현: `docs/kafka-reprocess/experiments/` (`setup_isolated.sh` → `reprocess_exp.sh`). 원자료 `result-run1~3.txt`.

## 4. 결과 (3회 모두 같음)

| 시점 | 커밋 오프셋 / 끝 | `spans` 줄 | 고유 (trace_id, span_id) | 오류 줄 | 집계 cnt | 집계 err_cnt | **에러율** |
|---|---|---|---|---|---|---|---|
| 정상 90 커밋 뒤 | n / n (lag 0) | 90 | 90 | 0 | 90 | 0 | 0% |
| 오류 10 저장 · 커밋 전 (멈춤) | n / n+1 (lag 1) | 100 | 100 | 10 | 100 | 10 | **10%** |
| `kill -9` 직후 | n / n+1 (lag 1) | 100 | 100 | 10 | 100 | 10 | 10% |
| 같은 그룹으로 재시작 뒤 | n+1 / n+1 (lag 0) | **110** | **100** | **20** | **110** | **20** | **18.18%** |

- 중복 스팬: **오류 스팬 10개가 각 2줄** (정상 스팬은 1줄씩 — 이미 커밋돼 다시 읽히지 않음).
- 재시작한 적재 처리기 로그: `raw 소비: traces 10건 (오프셋 n)` → `Committing offset=n+1`. 같은 레코드를 정상 처리로 한 번 더 넣었다. 오류 · 경고 로그 없음 → **조용히 틀린다.**
- 재시작 → 다시 처리까지 약 41초 (kill 06:31:41.1Z → 재합류 06:32:22.1Z, 3회 모두 비슷). 기동은 3초였고 나머지는 **그룹이 죽은 소비자를 `session.timeout.ms` 45초가 지나야 빼기 때문**이다 (중단점에서 하트비트도 멈춰 kill 몇 초 전부터 셌다).

## 5. 영향 (사실과 추론을 나눔)

**측정한 것**
- 같은 1분의 에러율이 실제 10% 인데 집계 · 조회는 18.18% 로 답한다. 중복은 집계 표에 합쳐져 사후에 가려낼 수 없다.
- 원본 `spans` 는 `uniqExact(trace_id, span_id)` 로 가려낼 수 있다(110 → 100).

**추론 (측정하지 않음)**
- 탐지 규칙 "5XX_RATE > 15%" 같은 기준이면 실제 10% 인데 발화한다 — 오발화. 반대로 정상 스팬이 중복되면 에러율이 내려가 놓칠 수도 있다. 어느 쪽인지는 커밋 전에 죽은 묶음에 뭐가 들었는지에 달렸다.
- `max.poll.records=500` 이라 한 번 죽으면 최대 500 레코드(레코드 하나에 스팬 수백 개)가 다시 들어갈 수 있다. 이번 실험은 레코드 1개만 걸리게 만들었다.
- 같은 MV 구조인 `transactions` · `url_stats_1m` · `heatmap_1m` · `server_map_1m` 도 같은 방식으로 중복될 것이다 (이번에 확인한 것은 `spans` · `service_health_1m` 뿐).
- 언제 생기나: `kill -9` · OOM · 노드 장애 · 커밋 직전의 리밸런싱. 정상 종료(SIGTERM)는 Spring 이 커밋하고 끝내므로 덜하다 — **확인 안 함**.

## 6. 팀 문서와의 관계

`docs/design/30-failure-modes.md` ④ "적재 처리기가 죽으면 오프셋부터 이어서 읽는다. **유실 없음**" — 맞다. 이번 실험은 그 **반대 면(중복)** 이 집계를 틀리게 만든다는 근거다. 그 문서(수집 파트 소관)는 고치지 않았고, 이 결과를 공유해 반영 여부를 묻는다.

## 7. 다음 단계 (2단계에서 비교할 후보 — 아직 고르지 않음)

후보만 적는다. 각각 이번 실험 도구로 같은 입력을 넣어 비교한다.
- CH 쪽에서 거르기: insert 중복 제거 창 켜기 + 레코드별 `insert_deduplication_token`(예: 토픽-파티션-오프셋), 또는 `ReplacingMergeTree` / 조회 때 거르기
- 커밋 쪽: 저장과 커밋을 더 가깝게 (레코드 단위 커밋 등) — 창을 줄일 뿐 없애지는 못함
- 집계 쪽: 집계를 원본에서 다시 계산하는 경로

비교 기준(제안): 재처리 뒤 집계 정확성 · 처리량 · CH 부하 · 정상 경로 지연 · 구현 범위(적재 처리기 · 스키마 → 수집 파트와 합의 필요).
