# `#193` 롤업 표에 꼬리표가 없어 원본이 지워지면 갈래를 모른다 : 설계 한 장

> 이슈 `#193` · 2026-10-14 · 결정은 ADR `#70`
> 조사 1 · 2절은 [`research.md`](research.md) · 프롬프트 원문은 [`prompts.md`](prompts.md)
> 발견은 조회 파트(이현영) : 노션 「누적 지표 그래프 관련 이슈」 「같이 알아둘 것」. `#189` 와 같이 볼지 판단하기로 했던 것을 따로 가져왔다

## 결정

**접을 때 꼬리표를 `any` 로 하나 남긴다. 마이그레이션 파일 하나, 코드 0줄.**

| 무엇 | 값 |
|---|---|
| 롤업 둘 새 칸 | `attributes SimpleAggregateFunction(any, Map(String, String))` (원본과 달리 `LowCardinality` 없음, 아래 「틀렸던 것」) |
| MV 둘 | `MODIFY QUERY` 로 `any(attributes)` 한 줄 추가 (`ADD COLUMN` 이 먼저) |
| 이미 접힌 줄 | 빈 Map 으로 둔다. 원본이 105일 안이라 조회가 지금 방식으로 찾는다 |
| 안 한 것 | 사전 표 · 원본 TTL 연장 · `GROUP BY` 에 꼬리표 · 조회 코드 · 옛 줄 채우기 |

## 세 줄 요약

- 원본 표에만 꼬리표가 있고 1분 · 1시간 롤업에는 꼬리표를 숫자로 접은 `series_hash` 만 있다
- MV 가 접는 기준에 꼬리표가 없어 접는 순간 떨어지고, 조회는 원본에서 찾아 붙이는데 원본은 105일에 지워진다
- 그래서 접을 때 꼬리표도 한 칸 남겨, 원본이 없어진 뒤에도 롤업만으로 갈래(G1 Old Gen · Eden)를 알게 한다

## 흐름을 시점으로 갈라 본다

### [준비할 때] 한 번

```
① 마이그레이션이 metrics_1m · metrics_1h 에 attributes 칸을 더한다
② MV 둘의 SELECT 에 any(attributes) 한 줄을 끼운다 (칸이 먼저, 거꾸로면 조용히 버려진다)
```

### [쓸 때마다] 원본에 줄이 들어올 때

```
적재 처리기  : metrics_raw 에 (꼬리표 + series_hash + 값) 을 넣는다. 전과 같다
ClickHouse   : mv_metrics_1m 이 같은 (서비스 · 파드 · 이름 · series_hash · 분) 끼리 접으면서
              검사 ① 미리 가진 것  = 없음
              검사 ② 방금 받은 것  = 이 묶음의 꼬리표 → any 로 하나 남긴다
              mv_metrics_1h 가 1분 줄을 다시 접으며 같은 일을 한다
조회         : (지금) 롤업 숫자로 원본에서 꼬리표를 찾는다 → (나중) 롤업의 꼬리표를 쓰고 비어 있을 때만 원본
```

## 왜 `any` 로 충분한가

`series_hash` 는 꼬리표를 키 정렬 · `k=v` 이어 붙이기 · SHA-256 · 앞 8바이트로 만든 숫자다(`MetricRow.seriesHashOf`). 같은 숫자 = 같은 꼬리표이므로 한 묶음 안의 꼬리표는 전부 같고 아무거나 하나 남기면 된다. 가짜 데이터는 `cityHash64` 로 만들어 식이 다르지만 "꼬리표 → 숫자" 가 결정적인 것은 같다.

## 장애가 나면

| 무엇이 틀리면 | 어떻게 되나 | 어떻게 알아채나 |
|---|---|---|
| MV `MODIFY QUERY` 가 안 먹었다 | 새 줄도 빈 Map. 2027-01-05 부터 이름 없는 선 | `SELECT countIf(empty(attributes)), count() FROM monimo.metrics_1m WHERE ts_min > now() - INTERVAL 1 HOUR` 에서 첫 값이 0 이 아니면 안 먹은 것 (원본 꼬리표가 빈 지표 `jvm.cpu.recent_utilization` 는 원래 빈 Map 이라 그 지표는 뺀다) |
| 옛 롤업 줄 | 빈 Map. 조회가 원본에서 찾는다 | 마이그레이션 시각 전 줄. 105일 뒤엔 전부 TTL 로 사라진다 |
| 같은 해시에 다른 꼬리표 (충돌) | 둘 중 하나의 이름이 붙는다 | 8바이트 해시라 사실상 없다. 가짜 · 실데이터를 한 표에 섞지 않는 전제는 `#64` 와 같다 |

## 어떻게 확인했나

| 무엇 | 결과 |
|---|---|
| `ClickHouseMetricStoreTest` (진짜 ClickHouse 26.8.10.6, `db/clickhouse` 전부 적용) | **7건** 통과. 원본에 넣은 `{jvm.gc.name: G1 Young Generation}` 이 `metrics_1m` · `metrics_1h` 에서 `attributes['jvm.gc.name']` 으로 그대로 나온다. 처음 `Map(LowCardinality(String), String)` 으로 썼다가 여기서 터졌다(아래) |
| 전체 테스트 (새 DDL 로 두 모듈) | `ingester:test` **162건** · `api-server:test` **322건** 전부 통과. api-server 도 `db/clickhouse` 를 그대로 올려 쓰므로 조회 쪽 테스트가 새 MV 정의와 같이 도는 것을 봤다 |
| 로컬 compose | **안 돌렸다**. `#189` 와 같은 모양(`ADD COLUMN IF NOT EXISTS` + `MODIFY QUERY`)이라 `docker compose run --rm clickhouse-migrate` 로 `down -v` 없이 먹는다 |

## 틀렸던 것

처음에 원본과 같은 `Map(LowCardinality(String), String)` 으로 썼다. 테스트 컨테이너가 `Code: 36. DB::Exception: Incompatible data types between aggregate function 'any' which returns Map(String, String) and column storage type Map(LowCardinality(String), String). (BAD_ARGUMENTS)` 로 표를 못 만들고 죽었다. `any` 가 `LowCardinality` 를 벗긴 타입을 돌려주기 때문에 `SimpleAggregateFunction` 칸은 그 타입과 같아야 한다. 조사 2.3 이 "걸릴 수 있다, 안 되면 `Map(String, String)`" 으로 적어 둔 그대로였다. 롤업은 원본의 1/4 · 1/240 줄이라 사전 압축을 포기해도 된다.

## 결과물

- 새 파일 : `db/clickhouse/V202610140634__add_rollup_attributes.sql` · 이 폴더
- 수정 : `ClickHouseMetricStoreTest.kt`(Then 하나) · `docs/design/01-decisions.md`(ADR `#70`) · `docs/design/web-v2/erd.md`(롤업 두 절) · `AGENTS.md` §5 §6
- 안 바꾼 것 : 적재 처리기 · 수집기 · 조회 · 탐지 · 화면 · TTL · 가짜 데이터

## 조회가 나중에 할 것 (급하지 않음)

- `MetricRepository.findPoints` 가 롤업을 읽을 때 `attributes` 도 같이 읽고, 빈 Map 인 줄만 `findAttributes` 로 원본을 찾는다
- 105일 뒤(2027-01-05)에는 옛 줄이 다 사라져 원본 조인을 없앨 수 있다 (ADR `#70` 되돌림 ②)
