# `#189` 누적 지표가 계속 오르는 선으로 보인다 : 설계 한 장

> 이슈 `#189` · 2026-10-14 · 결정은 「누적 지표는 종류 · 총합 표시 칸으로」 결정
> 조사 · 선택지는 [`research.md`](research.md) · 결정을 만든 프롬프트 원문은 [`prompts.md`](prompts.md)
> 발견과 선택지 정리는 조회 파트(이현영)가 했다 : 노션 「누적 지표 그래프 관련 이슈」(2026-10-13, 개인 스크럼)
> 팀은 방안 A 로 합의했고 표 주인이라 수집이 받았다

## 결정

- **적재는 값을 바꾸지 않는다**
  - 대신 줄마다 "이 줄이 총합인가" 를 남겨 조회 · 경보가 알아보게 한다
- 빼는 일은 읽는 쪽 몫이다

| 무엇 | 값 |
|---|---|
| `metrics_raw` 새 칸 | `metric_type LowCardinality(String)` (OTel 종류 그대로) · `is_counter UInt8` (1 = 켜진 뒤로 줄지 않는 총합) |
| 롤업 `metrics_1m` · `metrics_1h` | `is_counter SimpleAggregateFunction(max, UInt8)`, MV `MODIFY QUERY` 로 흘린다 |
| 정하는 쪽 | 적재 처리기 `MetricTranslator` (OTel 의 종류 · temporality · is_monotonic + 접미) |
| 빼는 쪽 | 조회 API · 탐지 (같은 파드 · 같은 시계열 안에서 앞 값 빼기, 음수면 재시작이라 건너뛰기) |
| 안 한 것 | 적재에서 차이 계산(C) · 조회가 이름 목록 보유(B) · 옛 줄 채우기 · 가짜 데이터 변경 · 조회 · 탐지 코드 |

## 흐름을 시점으로 갈라 본다

### [준비할 때] 한 번

```
① 마이그레이션이 metrics_raw 에 metric_type · is_counter 를 더하고, 롤업 둘에 is_counter 를 더한다
② MV 둘(mv_metrics_1m · mv_metrics_1h)의 SELECT 에 max(is_counter) 한 줄을 끼운다 (ADD COLUMN 이 먼저)
```

### [쓸 때마다] 메트릭 레코드 하나가 들어올 때

```
에이전트     : 15 ~ 60초마다 "켜진 뒤 총합 1,240ms · 총횟수 37" 을 보낸다 (OTel Java SDK 기본 = cumulative)
적재 처리기  : 검사 ① 미리 가진 것  = 없음 (상태 없음. 직전 값을 기억하지 않는다)
              검사 ② 방금 받은 것  = 이 메트릭의 종류(dataCase) · temporality · is_monotonic · 접미(.count / .min)
              ②만 보고 줄마다 metric_type · is_counter 를 적는다. value 는 받은 총합 그대로
ClickHouse   : metrics_raw 에 넣고, MV 가 is_counter 를 max 로 접어 1분 · 1시간 롤업에 옮겨 적는다
조회 · 탐지  : is_counter = 1 인 시계열만 시간순으로 읽으며 앞 값을 빼서 "이 1분에 40ms" 를 만든다
```

## `is_counter` 를 정하는 규칙

| OTel 이 보낸 것 | 줄 | `is_counter` | 왜 |
|---|---|---|---|
| Gauge | 전부 | 0 | "지금 얼마" 라 총합일 수 없다 (`jvm.memory.used` 의 일부 · `jvm.cpu.recent_utilization`) |
| Sum, cumulative, monotonic | 전부 | **1** | Counter. 켜진 뒤로 쌓인다 (`jvm.cpu.time` · `jvm.class.loaded`) |
| Sum, cumulative, 단조 아님 | 전부 | 0 | UpDownCounter. 오르내리는 현재값 (`jvm.thread.count` · `jvm.memory.used`) |
| Sum, delta | 전부 | 0 | 이미 "이 구간에 얼마". 또 빼면 0 근처가 된다 |
| Sum, temporality 없음 | 전부 | 0 | 모르는데 빼면 값이 사라지는 쪽으로 틀린다 |
| Histogram · ExponentialHistogram, cumulative | `.count` · `.sum` | **1** | GC 횟수 · GC 시간 합은 쌓인다 (`jvm.gc.duration`) |
|  | `.min` · `.max` | 0 | 쌓이는 값이 아니다 |
| Histogram · ExponentialHistogram, delta | 전부 | 0 | 구간마다 0 부터 다시 센 값 |
| Summary | `.count` · `.sum` | **1** | temporality 칸이 없고 규약상 항상 누적 |

- `metric_type` 은 OTel 종류 그대로다
  - `gauge` · `sum` · `histogram` · `exponential_histogram` · `summary`
  - 히스토그램을 편 네 줄은 전부 `histogram` 이다
- 읽는 쪽이 판단에 쓰는 칸은 `is_counter` 하나다
  - `metric_type` 은 "왜 1 인가" 를 되짚는 칸이다

## 왜 이름이 `is_counter` 인가

- OTel 용어를 그대로 쓰면 뜻이 어긋난다

| 후보 | 어긋나는 곳 |
|---|---|
| `is_cumulative` | OTel 의 cumulative 는 UpDownCounter(`jvm.memory.used`)도 포함한다. 그건 빼면 안 된다 |
| `is_monotonic` | OTel 의 monotonic 은 delta Sum 에도 참이다. 그것도 빼면 안 된다 |
| `is_counter` | 프로메테우스의 counter = "줄지 않고 쌓이며 재시작에 0 으로 돌아가는 값, `rate()` 로 읽는다". 우리 뜻과 같다. 횟수(count)와 헷갈리지 않게 문서마다 적는다 |

## 왜 GC 만 총합으로 오나 (다른 지표는 멀쩡한데)

- 재는 방식이 다르다
  - 힙 사용량 · 스레드 수는 에이전트가 주기마다 JVM 에 "지금 얼마" 를 물어 그 순간 값을 보낸다
  - GC 는 순식간에 끝나는 **사건**이다
    - 물어보는 순간엔 보통 안 하고 있다
    - 그래서 에이전트가 GC 끝날 때마다 알림을 받아 "이번 12ms" 를 히스토그램에 더해 둔다
- 내보낼 때는 SDK 기본값이 cumulative 라 켜진 뒤 총합이 나간다
  - 그래서 "이번 12ms" 는 우리한테 애초에 안 온다
  - 뽑을 수 있는 가장 작은 단위는 "이번 내보내기 구간(1분) 동안 40ms" 다
  - 그게 그래프에 딱 필요한 값이다
- GC 만이 아니다
  - `jvm.cpu.time` · `jvm.class.loaded` · HTTP 요청 수 · 응답 시간 히스토그램도 같은 성격이다
  - 지금은 GC 그래프만 화면에 올려서 GC 에서 먼저 보였을 뿐이다

## 왜 총합을 그대로 두고 읽을 때 빼나 (C 안을 버린 이유)

- 총합이 있으면 차이는 언제든 만들 수 있다
  - 반대는 안 된다
- 중간에 한 구간이 빠져도 다음 총합에서 빼면 빠진 만큼이 합쳐져 나온다
  - 차이만 저장했으면 그 구간은 영영 사라진다
- 재시작하면 총합이 0 으로 떨어져서 티가 난다
  - 차이로만 저장하면 알 방법이 없다
- 적재 처리기가 파드별 직전 값을 기억해야 한다
  - 그런데 `raw` 가 파티션 3개라 최대 3대까지 뜨고 그 기억이 갈라진다
  - 「metrics_raw 는 long 1지표 1행 · 설정은 앱 단위」 결정 이 조회 몫으로 둔 이유 그대로다

## 장애가 나면

| 무엇이 틀리면 | 어떻게 되나 | 어떻게 알아채나 |
|---|---|---|
| 적재가 `is_counter` 를 잘못 1 로 적는다 (예 : UpDownCounter) | 조회가 현재값을 빼서 그래프가 0 근처 · 음수에서 흔들린다 | `MetricTranslatorTest` 가 여덟 모양을 고정. 화면에서는 힙 그래프가 평평해지면 의심 |
| 잘못 0 으로 적는다 | 지금과 같은 증상(계속 오르는 선) | 인스펙터에서 우상향 직선 |
| MV `MODIFY QUERY` 가 안 먹었다 (옛 정의 유지) | 롤업의 `is_counter` 가 전부 0. 15일 이상 구간만 틀어진다 | `SELECT max(is_counter) FROM monimo.metrics_1m WHERE metric_name LIKE '%.sum'` 이 0 이면 안 먹은 것 |
| 옛 줄(이전에 들어온 것) | `is_counter = 0` 이라 그대로 그려진다. 새 줄과 경계에서 한 번 튄다 | 마이그레이션 시각 전후. 보관 105일이 지나면 사라진다 |
| 파드 재시작 | 총합이 0 부터 다시 시작. 빼면 음수 | 읽는 쪽이 음수를 건너뛴다 (조회 · 탐지 몫) |

## 어떻게 확인했나

| 무엇 | 결과 |
|---|---|
| `MetricTranslatorTest` | **21건** 통과 (이 이슈가 더한 것 : Gauge 종류 · UpDownCounter 0 · Counter 1 · delta Sum 0 · UNSPECIFIED 0 · 누적 히스토그램 count/sum 1 · min/max 0 · delta 히스토그램 0 · 지수 히스토그램 · Summary) |
| `ClickHouseMetricStoreTest` (진짜 ClickHouse 26.8.10.6, `db/clickhouse` 전부 적용) | **6건** 통과. `metrics_raw` 에 `histogram` · 1 이 들어가고 **`metrics_1m` · `metrics_1h` 까지 `is_counter = 1` 이 따라간다** : MV `MODIFY QUERY` 가 먹었고 연쇄 2단도 이어진다 |
| 마이그레이션이 떠 있는 서버에 먹나 | 테스트 컨테이너는 빈 서버라 "`CREATE` 뒤 `ALTER`" 경로만 봤다. `ADD COLUMN IF NOT EXISTS` + `MODIFY QUERY` 는 `#119` 가 실측한 것과 같은 모양이라 떠 있는 로컬에 `down -v` 없이 먹는다. **로컬 compose 에는 아직 안 돌렸다**(`#187` 실험이 같은 ClickHouse 를 쓰고 있어서) |
| 전체 테스트 (새 DDL 로 두 모듈) | `ingester:test` **161건** · `api-server:test` **322건** 전부 통과. api-server 도 `db/clickhouse` 를 그대로 올려 쓰므로 조회 쪽 테스트가 새 컬럼 · MV 정의와 같이 도는 것을 봤다 |

## 결과물

- 새 파일 : `db/clickhouse/V202610140450__add_metrics_metric_type.sql` · `ingester/.../transform/MetricType.kt` · 이 폴더
- 수정 : `MetricRow.kt`(칸 둘) · `MetricTranslator.kt`(종류 · 총합 판정) · `ClickHouseMetricStore.kt`(칸 둘 쓰기) · 테스트 둘 · `docs/design/01-decisions.md`(「누적 지표는 종류 · 총합 표시 칸으로」 결정) · `docs/design/web-v2/erd.md`(사본) · `AGENTS.md` §5 §6
- 안 바꾼 것 : 수집기 · Kafka(바이트를 풀지 않고 넘기므로 칸은 처음부터 있었다) · 가짜 데이터(기본값 0 = 한 값으로 넣는 지금 뜻과 같다) · 조회 API · 탐지 · 화면

## 조회 · 경보가 이어서 할 것

- 조회 : `GET /metrics/series` 가 `is_counter = 1` 인 시계열은 같은 `agent_id` · `series_hash` 안에서 시간순으로 앞 값을 빼서 준다
  - 음수면 그 점은 건너뛴다
  - 롤업 구간은 `last_v` 끼리 뺀다
- 탐지 : GC_TIME 규칙이 `jvm.gc.duration.sum` 을 뺀 값에 건다
  - 음수면 재시작이라 그 주기는 평가하지 않는다
- 노션 「정해야 할 것」 3번(경보가 직접 뺄지 · 조회 API 가 뺀 값을 줄지)은 아직 열려 있다
  - 「누적 지표는 종류 · 총합 표시 칸으로」 결정 되돌림 ③
