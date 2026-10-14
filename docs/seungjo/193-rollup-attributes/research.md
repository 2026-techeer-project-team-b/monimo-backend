# 리서치 : 롤업 표에 속성이 없어 원본 보관 기간이 지나면 갈래를 모른다

- 2026-10-14 / 관련 이슈 `#193` / 발견은 조회 파트(이현영, 노션 「누적 지표 그래프 관련 이슈」 「같이 알아둘 것」)
- 쓴 도구: 레포 코드 · DDL 직접 읽기 (조사 서브에이전트 0개, 아직 재현 없음)

> **읽는 순서가 곧 쓰는 순서다.** 1 → 2 를 쓰고 멈춰서 승조가 3 을 묻는다. 답하고 4 를 쓴다.
> 5 를 쓰고 멈춰서 승조가 6 을 묻는다. 답하고 7 을 쓴다. 8 은 승조가 쓴다.
> **3 · 6 · 8 은 사람 차례다.** 비어 있으면 아직 거기까지 온 것이고, 대신 채우지 않는다.

---

## 1. 이번 작업은 무엇을 하는 일인가

- **지금 무엇이 잘못됐나**
  - 1분 · 1시간으로 접은 지표 표에는 "어느 갈래인가"(G1 Old Gen 인지 Eden 인지)를 적은 꼬리표가 없다
    - 꼬리표를 숫자로 접은 값만 있다
  - 조회는 그 숫자로 원본 표에서 꼬리표를 찾아 붙인다
    - 그런데 원본은 105일이면 지워지고 접은 표는 180일 · 1년 90일을 산다
- **그래서 무엇을 만드나**
  - 접을 때 꼬리표도 같이 접어 둔다
  - 원본이 없어진 뒤에도 접은 표만으로 갈래를 알 수 있게 한다
- **안 하면 어떻게 되나**
  - 2027-01-05 쯤부터(T0 2026-09-22 + 105일) 105일 넘은 구간의 그래프 선에 이름이 없어진다
  - 힙 그래프에 선이 여덟 개 그려지는데 어느 게 Old Gen 인지 모른다

| | |
|---|---|
| 건드리는 모듈 · 파일 | `db/clickhouse/` 마이그레이션 하나(롤업 둘 `ADD COLUMN` · MV 둘 `MODIFY QUERY`) · `ingester` 테스트(`ClickHouseMetricStoreTest`) · `erd.md` 사본 · ADR · `AGENTS.md` §5 §6 |
| 건드리지 않는 것 | 적재 처리기 코드(원본에는 이미 꼬리표가 있다) · 수집기 · Kafka · 롤업 `ORDER BY` · 조회 코드 |
| 이 이슈 범위 밖 (후속) | 조회가 롤업의 꼬리표를 읽도록 바꾸는 것(조회) · 이미 들어온 롤업 줄 채우기 · 원본 · 롤업 TTL 자체를 다시 보는 것 |

**조사 들어가기 전에 내가 알던 것 (세 줄)**

- 대화에서 승조가 말한 것이다

- 롤업 둘에는 `series_hash` 만 있고 `attributes` 가 없다
- 조회가 원본에서 같은 해시로 속성을 찾아 붙이고 있다
- 원본 보관 기간이 지난 줄은 속성을 못 찾는다
  - `#189`(종류 칸) 와 같이 볼지 판단하기로 했고 따로 가져왔다

**알고 싶었던 것**

- 5절이 이 번호에 하나씩 답한다
- ① 해시에서 꼬리표를 되살릴 수는 없나
  - 그러면 표를 안 건드린다
- ② 접은 표에 꼬리표를 넣으면 같은 키 줄이 합쳐질 때 꼬리표가 안전하게 남나
- ③ 꼬리표 칸을 넣는 것 말고 다른 길은 무엇이 있나
  - 별도 사전 표 · 원본을 더 오래 보관 · 안 한다
- ④ 이미 들어온 롤업 줄은 어떻게 되나
- ⑤ 다른 제품은 내려 접은(downsampled) 시계열의 이름표를 어디에 두나

## 2. 작업하기 전에 알아야 하는 것

### 2.1 꼬리표(attributes)와 꾸러미 숫자(series_hash)

- **꼬리표** = 같은 지표를 갈래로 나누는 키-값 주머니
  - `jvm.memory.used` 하나가 `{jvm.memory.type: heap, jvm.memory.pool.name: G1 Old Gen}` 처럼 메모리 풀 8개로 갈라져 8 시계열이 된다(`docs/design/20-scope.md`)
- `series_hash` 는 꼬리표를 숫자로 바꾼 것이다(`ingester/.../transform/MetricRow.kt` `seriesHashOf`)
  - ClickHouse 는 `Map` 을 정렬 키에 못 넣는다
  - 그래서 적재 처리기가 키를 정렬해 `k=v` 줄로 이어 붙인 글자를 SHA-256 한다
  - 그 **앞 8바이트**를 `UInt64` 로 만든다
- 원본 `metrics_raw` 는 둘 다 가진다
  - `attributes Map(LowCardinality(String), String)`
  - `series_hash UInt64`(정렬 키 4번째)
- **해시는 한 방향이다**
  - 숫자에서 글자로 돌아갈 수 없다
  - 꼬리표를 알려면 "그 숫자를 가진 줄" 을 어디선가 읽어야 한다
  - 지금 그 어디가 원본 표 하나뿐이다

### 2.2 롤업 표 둘과 MV 둘

| 표 | 엔진 | 키 | 값 칸 | 꼬리표 | TTL |
|---|---|---|---|---|---|
| `metrics_raw` | MergeTree | (service_name, agent_id, metric_name, series_hash, ts) | `value` | **있다** | **105일** |
| `metrics_1m` | AggregatingMergeTree | (… series_hash, ts_min) | `avg_v` · `min_v` · `max_v` · `last_v` (AggregateFunction) + `is_counter`(`#189`) | 없다 | **180일** |
| `metrics_1h` | AggregatingMergeTree | (… series_hash, ts_hour) | 같음 | 없다 | **1년 90일** |

- MV 는 연쇄 2단이다(`db/clickhouse/V202609221907__create_materialized_views.sql`)
  - `mv_metrics_1m` 이 원본 INSERT 마다 `GROUP BY service_name, agent_id, metric_name, series_hash, ts_min` 으로 접어 `metrics_1m` 에 넣는다
  - `mv_metrics_1h` 가 그 INSERT 를 다시 접어 `metrics_1h` 에 넣는다
- `GROUP BY` 에 `attributes` 가 없으니 접는 순간 꼬리표가 떨어진다
- **떨어뜨린 것이 설계였다**
  - ERD 가 롤업을 "숫자만" 으로 그렸다
  - 조회가 원본에서 찾아 붙이는 것으로 짰다
    - `MetricRepository.findAttributes` 주석 : "원본 보관 기간이 지난 줄은 여기 없어 속성이 비게 된다"
  - 즉 이미 알고 있던 구멍이고, 지금 시점에 메우는 것이다

### 2.3 AggregatingMergeTree 에서 "보통 칸" 은 어떻게 되나

- 이 엔진은 같은 키의 줄 여럿을 합친다(백그라운드 merge)
  - 집계 상태 칸(`AggregateFunction`)은 상태끼리 합친다
  - `SimpleAggregateFunction(f, T)` 칸은 `f` 로 합친다
  - 그 둘이 아닌 **보통 칸은 합칠 때 어느 줄의 값이 남는지 보장이 없다**
- `#189` 가 `is_counter` 를 `SimpleAggregateFunction(max, UInt8)` 로 둔 이유가 이것이다
- 꼬리표도 같은 길이면 `SimpleAggregateFunction(any, Map(...))` 이 된다
  - 같은 `series_hash` 면 꼬리표가 같다(해시가 꼬리표에서 나왔다)
  - 아무거나 하나 남기면 된다
- **확인해야 하는 것** : ClickHouse 26.8 에서 `SimpleAggregateFunction(any, Map(LowCardinality(String), String))` 이 되는가
  - `Map` 안의 `LowCardinality` 가 걸릴 수 있다
  - 안 되면 `Map(String, String)` 으로 둔다(5절에서 실측)

### 2.4 데이터는 어디로 흐르나

```
에이전트 ─OTLP─▶ 수집기 ─Kafka raw─▶ 적재 처리기 ─INSERT─▶ metrics_raw  (꼬리표 O, 105일)
                                                              │ mv_metrics_1m  GROUP BY … series_hash  ← 여기서 꼬리표가 떨어진다
                                                              ▼
                                                           metrics_1m  (꼬리표 X, 180일)
                                                              │ mv_metrics_1h
                                                              ▼
                                                           metrics_1h  (꼬리표 X, 1년 90일)

조회 GET /metrics/series (step 60 미만 원본 · 60 이상 1분 · 3600 이상 1시간, api-spec 15번)
  ① 고른 표에서 (agent_id, series_hash, bucket) 별 점을 읽는다          MetricRepository.findPoints
  ② 나온 series_hash 들로 metrics_raw 에서 any(attributes) 를 읽는다   MetricRepository.findAttributes
  ③ 둘을 붙여 선마다 꼬리표를 단다                                   MetricService (없으면 orEmpty = 빈 Map)
```

| 질문 | 답 |
|---|---|
| 꼬리표가 비는 줄은 어디서 생기나 | ② 에서 원본에 그 해시 줄이 하나도 없을 때. 즉 요청 구간의 그 시계열이 **105일 전에 끝났거나 전부 105일 전**일 때 |
| 지금 비나 | 아니다. 원본 첫 줄이 2026-09-22 이후라 2027-01-05 까지는 전부 원본에 있다 |
| 누가 아나 | 아무도. 조회는 빈 Map 을 그대로 돌려주고 화면은 이름 없는 선을 그린다. 에러도 로그도 없다 |
| 탐지도 걸리나 | 탐지는 `metrics_1m` 을 `jvm.thread.count` 처럼 꼬리표 없이 합쳐 읽는다(`ActiveThreadsRepository`, api-spec 21번). 갈래가 필요한 규칙(힙 Old Gen 만)은 아직 없다 |

### 2.5 자주 헷갈리는 것

| 헷갈리는 것 | 실제 |
|---|---|
| 해시에서 꼬리표를 되살릴 수 있다 | 없다. SHA-256 앞 8바이트라 한 방향이다 |
| 롤업 TTL 이 원본보다 긴 건 실수다 | 의도다. ERD 가 "15일 촘촘 · 180일 1분 · 1년 추세" 로 그렸고 접은 표가 원본의 1/4 · 1/240 크기라 오래 둔다. 구멍은 TTL 이 아니라 **접을 때 꼬리표를 버린 것** |
| 롤업에 `attributes` 를 그냥 `Map` 칸으로 더하면 된다 | 합칠 때 어느 줄 값이 남는지 보장이 없다(2.3). `SimpleAggregateFunction(any, …)` 이어야 한다 |
| MV 의 `GROUP BY` 에 `attributes` 를 넣으면 된다 | 되긴 하지만 그러면 꼬리표가 **키**가 되어 표의 `ORDER BY` 도 바꿔야 하고, 그건 표를 다시 만드는 일이다(`#118` 조사 : 집계가 비거나 2배). 해시가 이미 키라 꼬리표는 값으로 두면 된다 |
| `#189` 가 `is_counter` 를 롤업에 넣었으니 같은 파일에 넣었으면 됐다 | 맞다, 같은 모양이다. 따로 가져온 이유는 결정이 둘(종류 칸은 팀 합의 A, 꼬리표는 "같이 볼지 판단")이라 ADR 을 섞지 않으려고 |
| 이미 들어온 롤업 줄도 채워진다 | 안 채워진다. `ADD COLUMN` 기본값(빈 Map)이다. 원본이 아직 105일 안이라 조회의 ② 로 전부 찾을 수 있고, 그 사이에 접힌 새 줄부터 꼬리표가 붙는다 |
| 가짜 데이터(seed)도 같다 | 가짜는 `series_hash` 를 `cityHash64(toString(map))` 로 만들어 실데이터 식과 다르지만(`MetricRow.kt` 주석), **꼬리표 → 해시 방향은 둘 다 결정적**이라 "같은 해시 = 같은 꼬리표" 는 가짜에서도 성립한다 |

### 출처

- `db/clickhouse/V202609221904__create_raw_tables.sql` (`metrics_raw`, TTL 105일) · `V202609221905__create_rollup_tables.sql` (`metrics_1m` 180일 · `metrics_1h` 1년 90일) · `V202609221907__create_materialized_views.sql` (MV 둘) · `V202610140450__add_metrics_metric_type.sql` (`#189`, 같은 모양의 선례)
- `ingester/src/main/kotlin/com/monimo/ingester/transform/MetricRow.kt` (`seriesHashOf`)
- `api-server/src/main/kotlin/com/monimo/api/query/metric/MetricRepository.kt` (`findPoints` · `findAttributes`) · `MetricService.kt`
- `docs/design/web-v2/erd.md` `metrics_raw` · `metrics_1m` · `metrics_1h` 절, `api-spec.md` 15 · 21번
- `db/clickhouse/README.md` 「표 정의를 바꿀 때」(ADD COLUMN → MODIFY QUERY 순서, DROP VIEW 금지)

---

## 3. 1차 질문 (사람 차례)

- 2절을 쉬운 말로 되묻는 자리였다
- 물은 것
  - "왜 하는 거지" · "왜 105일이지" · "꼬리표 · 꾸러미 숫자가 뭐지"
  - "Old Gen · Eden 이 뭐지" · "Map 이 정렬 키에 왜 못 들어가지"
  - "접은 표가 뭐지" · "105일 뒤 삭제돼도 되는 거 아닌가"
- 답은 2절 2.1 ~ 2.5 에 녹였다
  - 용어 풀이
  - 105 = 15 디스크 + 90 S3
  - 구멍은 TTL 이 아니라 접을 때 꼬리표를 버린 것
- **결정 : 이 이슈는 4 ~ 8절을 건너뛰고 바로 구현한다** (승조, 2026-10-14)
  - `#189` 와 같은 모양이라 선택지가 사실상 하나다
  - 2절만으로 "원본엔 있고 롤업엔 없으니 롤업에 넣자" 가 설명됐다
  - 선택지 넷(롤업 칸 · 사전 표 · 원본 TTL 연장 · 안 한다)과 기각 사유는 「롤업에 꼬리표를 any 로 남기기」 결정 에 적었다

---

## 4. 1차 정리

> 3 의 질문이 끝난 뒤 채운다.

---

## 5. 선택지와 설명

> 4 뒤에 쓴다. 실측(ClickHouse 26.8 에서 `SimpleAggregateFunction(any, Map(LowCardinality(String), String))` 이 되는가 · MV 연쇄 2단으로 꼬리표가 1시간 표까지 가는가)을 여기에 적는다.

---

## 6. 2차 질문 (사람 차례)

- **Q.**
  - A.

---

## 7. 2차 정리

---

## 8. 구현 프롬프트 (사람 차례)

```
```

---

## 9. 프롬프트를 가지고 실제 구현

- 8절 없이 갔다
- 결과는 [`README.md`](README.md) 「어떻게 확인했나」
- 구현 중 달라진 것 하나 : 2.3 의 걱정이 그대로 맞았다
  - `SimpleAggregateFunction(any, Map(LowCardinality(String), String))` 은 `Code: 36` 으로 표가 안 만들어진다
    - `any` 가 `LowCardinality` 를 벗긴 타입을 돌려준다
  - `Map(String, String)` 으로 바꿨다

