# `#189` 누적 지표가 계속 오르는 선으로 보인다

이슈 `#189` · 2026-10-14 · 승조(`@SeungJo-02`) · 결정은 ADR `#68`

> **발견과 선택지 정리는 조회 파트(이현영)가 했다.** 노션 「누적 지표 그래프 관련 이슈」(2026-10-13). 이 문서는 그것을 베끼지 않는다. 표 주인 쪽에서 더 봐야 했던 것과 고르는 일만 적는다.

---

## 1. 무엇을 하려는 것인가

GC 시간(`jvm.gc.duration.sum`)이 인스펙터에서 계속 오르는 선으로 보인다. 보고 싶은 것은 "이 1분 동안 GC 에 얼마 썼나" 인데, 들어오는 값이 "켜진 뒤로 쌓인 총합" 이고 표에는 그게 총합이라는 표시가 없어서 조회가 빼기를 걸 수 없다.

### 왜 수집 몫인가

| | 주인 |
|---|---|
| `metrics_raw` · 롤업 · ClickHouse 마이그레이션 | 수집 (ADR `#20` 표 주인 · `#57` Flyway CH) |
| 적재 처리기 코드 | 수집 (ADR `#32`) |
| 발견한 쪽 | 조회 (인스펙터 GC 그래프) |
| 빼는 쪽 | 조회 · 알림 (ADR `#38` : 차이 계산은 조회가) |

### 이미 밝혀져 있는 것 (노션, 다시 재지 않는다)

- 적재는 누적 값을 바꾸지 않고 그대로 넣는다 (ADR `#38`)
- `metrics_raw` 에 지표 종류 칸이 없다. 조회는 이름과 값만 본다
- OTel 이 보내는 정보에는 종류(Gauge / Sum / Histogram)와 누적 여부가 들어 있고 적재하면서 버려진다
- 가짜 데이터는 GC 를 한 값으로 넣어 이 문제가 안 보인다. 실데이터를 붙이면 보인다
- GC_TIME 경보는 총합을 그대로 비교하면 언젠가 무조건 울린다. 재시작하면 0 부터라 음수 차이는 건너뛰어야 한다
- 선택지 A(표에 종류 칸) · B(조회가 이름 목록) · C(적재가 차이로 변환). 조회 의견 A, 팀 합의 A

## 2. 꼭 알아야 했던 것 (5개)

### 2.1 왜 GC 만 총합으로 오나

재는 방식이 다르다. 상태를 재는 지표(힙 · 스레드 수)는 에이전트가 주기마다 "지금 얼마" 를 물어 그 순간 값을 보낸다. 사건을 세는 지표(GC · 클래스 로딩 · HTTP 요청)는 사건이 날 때마다 더해 두고, 내보낼 때 OTel Java SDK 기본값(cumulative)대로 **켜진 뒤 총합**을 보낸다. 그래서 "이번 GC 12ms" 는 우리한테 애초에 안 온다. 가장 작은 단위는 "내보내기 구간(1분) 동안 40ms" 이고 그건 총합 둘을 빼서 나온다.

GC 만이 아니다. JVM 런타임 지표 중 counter 는 `jvm.gc.duration`(histogram) · `jvm.cpu.time` · `jvm.class.loaded` · `jvm.class.unloaded`. `jvm.memory.used` · `jvm.thread.count` 는 UpDownCounter(Sum 이지만 단조 아님), `jvm.cpu.recent_utilization` 은 Gauge.

### 2.2 OTel 이 보내 주는 칸이 무엇인가

| 종류 | 칸 | 뜻 |
|---|---|---|
| Sum | `aggregation_temporality` | CUMULATIVE(켜진 뒤 총합) · DELTA(구간 값) · UNSPECIFIED |
| Sum | `is_monotonic` | 줄지 않는가. Counter 참, UpDownCounter 거짓 |
| Histogram · ExponentialHistogram | `aggregation_temporality` | 위와 같음. `count` · `sum` 이 그 temporality 를 따른다 |
| Summary | 없음 | 규약상 항상 누적 |
| Gauge | 없음 | 항상 현재값 |

### 2.3 "빼야 하나" 는 칸 하나로 안 정해진다

빼야 하는 줄 = (Sum 이고 cumulative 이고 monotonic) 또는 (Histogram 계열이고 cumulative 이고 접미가 `.count` · `.sum`) 또는 (Summary 이고 접미가 `.count` · `.sum`). OTel 칸 셋에 줄의 접미까지 봐야 한다. 이 규칙을 조회와 경보가 각자 들면 어긋나므로, 적재가 한 번 정해 비트 하나로 접는 쪽이 맞다.

### 2.4 OTel 용어를 이름으로 쓰면 함정이 된다

`is_cumulative` 는 UpDownCounter 도 참이고(빼면 안 됨), `is_monotonic` 은 delta Sum 도 참이다(빼면 안 됨). 둘 다 "OTel 칸 이름인데 OTel 뜻과 다른 값" 이 된다. 프로메테우스의 counter(줄지 않고 쌓이며 재시작에 0, `rate()` 로 읽는다)가 우리 뜻과 정확히 같아 `is_counter` 로 했다.

### 2.5 롤업에도 흘려야 한다

인스펙터 15일 이상 구간과 탐지(CPU · HEAP · GC_TIME 규칙)가 `metrics_1m` 을 읽는다(`erd.md`). 거기서도 총합인지 알아야 `last_v` 끼리 뺄 수 있다. AggregatingMergeTree 는 같은 키의 줄을 합칠 때 집계 상태 컬럼만 보장하므로 `SimpleAggregateFunction(max, UInt8)` 로 둔다(시계열 안에서 값이 늘 같아 max = any). 순서는 `db/clickhouse/README.md` 대로 ADD COLUMN → MV `MODIFY QUERY`.

## 3. 승조가 정한 것

- 방안 A (팀 합의)
- 칸은 둘 : 종류(`metric_type`) + 총합 표시(`is_counter`). 노션 「정해야 할 것」 2번 = "종류만 / 누적 여부까지" → 둘 다
- 옛 줄은 안 채운다. 가짜 데이터도 안 바꾼다
- 빼는 일은 조회 · 경보가. 「정해야 할 것」 3번(경보가 직접 뺄지 · 조회 API 가 뺀 값을 줄지)은 열어 둔다

## 4. 선택지

| | 방법 | 누가 고치나 | 얻는 것 | 포기하는 것 |
|---|---|---|---|---|
| **A** | `metrics_raw` 에 종류 · 총합 표시 칸 | 수집(표 · 적재), 조회 · 알림(빼기) | 새 지표가 들어와도 자동으로 맞음. 조회 · 경보가 같은 기준 | 표 변경. 옛 줄에 값 없음 |
| B | 조회가 누적 지표 이름 목록 보유 | 조회 | 표 안 건드림, 바로 가능 | 새 누적 지표마다 목록 수정. 경보도 같은 목록 필요 |
| C | 적재가 차이로 바꿔 넣음 | 수집 | 조회 · 경보가 신경 쓸 게 없음 | ADR `#38` 뒤집음. 파드별 직전 값 기억(처리기 최대 3대). 빠진 구간 복원 불가. 재시작 감지 불가 |
| A' | OTel 칸 셋을 그대로 넣음 | 수집, 조회 · 알림 | OTel 모양 그대로 | "빼야 하나" 규칙(2.3)을 읽는 쪽 둘이 각자 듦 |
| D | 에이전트가 delta 로 보내게 설정 | 쇼핑몰 · 사용자 | 코드 0줄 | 사용자 에이전트 설정에 기댐. 빠진 구간 영영 손실 |

## 5. 우리 데이터와 대조

- `MetricTranslator` 는 이미 `dataCase` 로 종류를 가르고 있어 두 칸을 채우는 것은 그 자리에 조건 하나씩이다
- `common/src/test/resources/fixtures/` 의 쇼핑몰 실데이터 표본(`#167`)에 `jvm.gc.duration` 히스토그램이 들어 있다 : 적재가 그 표본으로 왕복 시험을 하므로 cumulative 칸이 실제로 온다는 것은 이미 확인된 상태다
- 가짜 데이터의 `jvm.gc.duration` 은 한 값(0.004 ~ 0.024)이라 `is_counter = 0` 이 뜻에 맞다

## 6. AI 가 틀렸던 것 · 되물은 것

- 처음 설명에서 "누적해서 쌓는 주체" 를 우리 쪽처럼 말했다가 바로잡았다 : 쌓는 것은 에이전트 안의 OTel SDK 이고 우리는 받은 총합을 그대로 넣는다
- 사용자 요약의 `총합(count) + 총횟수(sum)` 가 뒤바뀐 것을 잡았다 : sum 이 시간 합, count 가 횟수
- "현재값과 총합을 나눠서 각각 넣는다" 는 이해를 바로잡았다 : 값 칸은 하나 그대로, 옆에 꼬리표 칸을 붙이는 것

## 7. 절차

1. 마이그레이션 : `ADD COLUMN` 넷 → MV `MODIFY QUERY` 둘
2. 적재 처리기 : `MetricType` · `MetricRow` 칸 둘 · `MetricTranslator` 판정 · `ClickHouseMetricStore` 쓰기
3. 테스트 : 변환 규칙 여덟 모양 · 저장 · 롤업 전파
4. 문서 : ADR `#68` · `erd.md` 사본 · `AGENTS.md` §5 §6

## 8. 구현 프롬프트

[`prompts.md`](prompts.md)

## 9. 결과

[`README.md`](README.md) 「어떻게 확인했나」
