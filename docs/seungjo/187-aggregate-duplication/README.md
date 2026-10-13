# `#187` 커밋 전에 죽으면 집계가 중복된다 : 설계 한 장

> 이슈 `#187` · 2026-10-14 · 결정은 ADR `#67`
> 조사 · 선택지 · 되물음은 [`research.md`](research.md) · 실측 결과는 [`../../kafka-reprocess/30-stage3-result.md`](../../kafka-reprocess/30-stage3-result.md)
> 재현(1단계)과 비교 계획(2단계)은 알림 파트(유경)가 했다 : [`10-baseline.md`](../../kafka-reprocess/10-baseline.md) · [`20-stage2-plan.md`](../../kafka-reprocess/20-stage2-plan.md)

## 결정

**받는 쪽(ClickHouse)이 중복을 알아보게 한다. 적재 처리기 코드는 0줄이다.**

| 무엇 | 값 |
|---|---|
| 중복 제거 기억 | `non_replicated_deduplication_window = 100`, 표 **열 군데** |
| 커밋 폭 | `ack-mode: RECORD` (사고 때 다시 들어가는 양 500 레코드 → 1 레코드) |
| 알아내는 장치 | 원본 ↔ 집계 대조 (`check-pipeline.sh`, 스팬 쪽 셋) |
| 복구 | **손 절차** (`reaggregate_window.sh`). 재집계 잡은 안 만든다 |
| 안 한 것 | 이름표(`insert_deduplication_token`) · `ReplacingMergeTree` · 표 구조 변경 |

## 흐름을 시점으로 갈라 본다

### [준비할 때] 한 번

```
① 마이그레이션이 표 열 군데에 "최근 묶음 100개의 지문을 기억해라" 를 켠다
   원본 셋   spans · metrics_raw · logs
   집계 일곱 transactions · heatmap_1m · url_stats_1m · server_map_1m ·
            service_health_1m · metrics_1m · metrics_1h
② 적재 처리기가 ack-mode: RECORD 로 뜬다 (레코드마다 커밋)
```

### [쓸 때마다] 레코드 하나가 들어올 때

```
적재 처리기 : 레코드를 풀어 JSON 묶음을 만들고 ClickHouse 에 넣는다
ClickHouse  : 검사 ① 미리 가진 것  = 이 표의 수첩에 적힌 최근 지문 100개
              검사 ② 방금 받은 것  = 지금 들어온 묶음의 지문
              ①에 ②가 있으면 버린다. 없으면 넣고 ②를 수첩에 적는다
              넣으면 MV 가 자기 표에 옮겨 적고, 그 표도 자기 수첩으로 같은 검사를 한다
적재 처리기 : 커밋한다 (이 레코드까지 읽었다)
```

사고가 나는 자리는 **마지막 두 줄 사이**다. 넣었는데 커밋을 못 하고 죽으면, 다시 떠서 같은 레코드를 받는다. 그때 검사 ① 이 그 지문을 들고 있어서 버린다.

## 왜 표 여섯이 아니라 열인가

중복 제거 기억은 **표마다 따로**다. 원본에만 켜면 원본은 걸러지는데 집계는 자기 수첩이 없어 두 번 센다(예비 행렬 B : 원본 10 · 집계 20).

```
spans ──┬─> transactions ─> heatmap_1m      ← 연쇄 2단
        ├─> url_stats_1m
        ├─> server_map_1m
        └─> service_health_1m
metrics_raw ─> metrics_1m ─> metrics_1h     ← 연쇄 2단
logs  (MV 없음. 그래도 원본이라 중복된다)
```

`thread_dumps` 는 API 서버가 넣는 표라 뺐다.

## 어떻게 확인했나

| 무엇 | 결과 |
|---|---|
| 유경 도구 v3 로 3회 (커밋 전 `kill -9` → 같은 그룹 재시작) | 전부 재시작 뒤 `spans` **100** · 고유 100 · 오류 10 · 에러율 **10%** · `transactions` 100 · `heatmap_1m` 100 · `url_stats_1m` 100. 중복 스팬 목록 빈 목록 |
| **음성 대조** (기억만 0 으로 되돌림) | `spans` **110** · 에러율 **18.18%** · `heatmap_1m` **110**. 1단계 숫자가 정확히 재현 |
| **연쇄 2단** (`transactions` → `heatmap_1m`) | 이어진다. 2단계 계획의 미확인 항목이었다 |
| 지표 연쇄 | **잴 필요가 없었다** : 롤업이 `avgState` · `minState` · `maxState` · `argMaxState` 라 같은 값이 두 번 와도 결과가 안 바뀐다(멱등) |
| `ack-mode: RECORD` 가 먹었나 | 중단점 스택으로 확인 : `invokeOnMessage → ackCurrent → commitOffsets → commitSync` (v2 때는 `pollAndInvoke → doProcessCommits → commitIfNecessary`) |
| **`ack-mode` 처리량 영향** | 같은 격리 환경에서 레코드 108개(스팬 9,720개)를 소비하는 시간 : **RECORD 8.45초 · BATCH 8.43초.** 차이 0.2% 로 잡음 수준이다 |
| **`peer_service` 구멍** | 재현했다. 레코드 하나(SERVER + CLIENT 스팬)를 창이 켜진 `spans` 에 직접 넣어 같은 바이트는 걸러지고(2줄 · 집계 1) `peer_service` 만 바꾸면 안 걸러진다(4줄 · 집계 **2**) |
| 복구 절차 | 음성 대조로 망가뜨린 구간에 돌려 `service_health_1m` 110/20 → **100/10** · `heatmap_1m` 110 → **100** |
| 시험 | `./gradlew test` **756건 · 실패 0** (3분 5초) |

## 내가 틀렸던 것

**ㄱ. 창 크기 근거를 기억으로 적었다.** 「복제 표 기본값이 100」 이라고 썼는데 실측은 **10000** 이다. 원자료가 바로 옆에 있었는데 안 봤다. 비복제 표만 0 이라는 것이 이 건의 핵심인데 그 숫자를 틀리게 적었다.

**ㄴ. 알림 장치의 선택지와 추천이 서로 어긋났다.** 표에는 "원본만 본다" 라고 적고 추천에는 "유일한 장치" 라고 적었다. 승조가 두 문단을 나란히 놓고 잡았다. 원인은 **틀어지는 모양이 둘인데 하나로 보고 있었던 것**이다 : 원본과 집계가 같이 중복되는 경우와, 원본은 깨끗한데 집계만 중복되는 경우. 둘째가 연쇄 2단 미확인과 정확히 겹치는 가장 걱정되는 경우였는데 그걸 못 보는 장치를 유일한 장치라고 불렀다. 원본 ↔ 집계 대조로 고쳤다.

**ㄷ. 구멍이 둘인데 안 갈랐다.** "비동기 쪽은 쟀으니 안전해 보인다" 고 적었는데, 승조가 "측정해 보니 없었다는 건가" 로 되물어 다시 보니 비동기 구멍은 쟀고 **`peer_service` 구멍은 실험이 건드릴 수 없는 구조**였다 : 1단계 실험 서비스 이름이 PG 에 등록되지 않아 `peer_service` 가 두 번 다 빈 글자였다. "안 생겼다" 가 아니라 **"생길 조건을 만들지 않았다"** 였다. 이번에 그 조건을 만들어 재현했다.

**ㄹ. 복구 절차가 두 번 안 됐다.** 적기 전에 돌려 봐서 드러났다.
1. 중복을 `GROUP BY` + `any()` 로 빼고 종류 조건을 **바깥 `WHERE`** 에 뒀다 → ClickHouse 가 그 조건을 안쪽으로 밀어 넣으려다 `Code: 184 Aggregate function any(service_name) AS service_name is found in WHERE in query`
2. 조건을 안쪽으로 옮겼는데 **같은 오류가 났다.** 원인이 달랐다 : 안쪽 `WHERE` 의 `service_name` 이 컬럼이 아니라 **같은 이름의 집계 별칭**으로 풀린다
3. `LIMIT 1 BY trace_id, span_id` 로 바꿔 통했다

`#119` 가 남긴 교훈("쓰는 것과 돌려 보는 것은 다른 일이다")이 또 맞았다.

**ㅁ. 지표 중복 점검이 거짓 경보를 냈다.** "한 계열(`series_hash`)의 한 시각에 점은 하나" 로 보고 대조에 넣었는데 `check-pipeline.sh` 를 돌리자마자 걸렸다. 보니 `telemetrygen` 이 같은 이름 · 같은 꼬리표의 지표 셋을 보내고 `metrics_raw.ts` 가 **초 단위**라 둘이 같은 초에 떨어진 것이었다. **값이 서로 달랐으니(0 과 1) 중복이 아니라 진짜 점 둘이다.** 값까지 키에 넣어도 안 낫다 : 안 변하는 게이지가 같은 초에 두 번 찍히면 진짜와 구분이 안 된다. 지표 쪽은 롤업이 멱등이라 중복이 집계를 틀어뜨리지도 못하므로 **그 점검을 뺐다.** 로그도 같은 이유로 안 본다. 울지 않아야 할 때 우는 점검은 없는 것보다 나쁘다.

## 아직 못 막는 것

| 무엇 | |
|---|---|
| 같은 스팬이 **새 오프셋**으로 다시 온 경우 | 에이전트 재전송 · 수집기 재시도. 바이트가 같을 때만 운 좋게 막힌다 |
| 기억에서 **밀려 나간 뒤**의 재처리 | `raw.dlq` 되넣기 · 오프셋 되감기 · 보관 기간 재생 |
| `peer_service` 가 재처리 사이에 바뀌는 경우 | 재현했다. 고치는 길은 이름표(안 2)이고 안 갔다 |
| ClickHouse 가 적재 처리기와 **같이 죽는** 경우 기억이 사는지 | 안 쟀다 |
| 창 100 이 **적재 처리기 2대 이상**에서 충분한지 | 안 쟀다. 1대일 때는 죽어 있는 동안 넣는 쪽이 없어 거의 안 밀린다 |

앞 셋의 해법은 **원본에서 다시 집계하는 것** 하나뿐이고, 되넣는 잡(ADR `#34`)을 만들 때 자동화를 같이 정한다.

## 바꾼 파일

| 파일 | 한 줄 |
|---|---|
| `db/clickhouse/V202610140410__enable_insert_dedup_window.sql` | 표 열 군데에 기억 100. 머리말에 왜 열인지 · 왜 100 인지 · 단위가 개수라는 것 |
| `ingester/src/main/resources/application.yml` | `ack-mode: RECORD` + 정확성을 고치는 것이 아니라는 주석 |
| `scripts/check-pipeline.sh` | 중복 대조 셋 (`spans` 자체 · `spans`↔`service_health_1m` · `transactions`↔`heatmap_1m`). 지표 · 로그는 거짓 경보 때문에 뺐다 |
| `docs/kafka-reprocess/experiments/reprocess_exp.sh` | 도구 v3 : 집계 표를 전부 본다 |
| `docs/kafka-reprocess/experiments/reaggregate_window.sh` | 복구 절차 (신규) |
| `docs/kafka-reprocess/30-stage3-result.md` | 3단계 결과 (신규). 유경 문서는 안 고쳤다 |
| `docs/design/01-decisions.md` | ADR `#67` (append) |
| `docs/design/00-index.md` · `30-failure-modes.md` · `AGENTS.md` | 갱신 |
