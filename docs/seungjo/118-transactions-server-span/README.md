# `#118` `transactions` 를 서비스가 받은 요청 기준으로 : 스캐터가 진입 서비스에서만 나온다

> **이 작업의 기술 설계 문서 한 장.** 문제 → 선택지 → 결정 → 장애가 나면 순서로 읽으면 끝난다.
> 결정 정본은 [`../../design/01-decisions.md`](../../design/01-decisions.md) 의 ADR `#52` 이고, 여기는 그 한 건을 한 장으로 펼친 것이다.
> **다른 파트가 발견해서 넘어온 첫 건**이다. 조회 파트(현영)가 스캐터를 만들다 찾았고, `db/clickhouse/` 가 파트를 넘는 공용 영역이라 수집 쪽이 가져갔다.

- 2026-10-07 / 승조(`@SeungJo-02`) / 결정 ADR `#52` / 이슈 [`#118`](https://github.com/2026-techeer-project-team-b/monimo-backend/issues/118) · 후속 [`#119`](https://github.com/2026-techeer-project-team-b/monimo-backend/issues/119)
- 유저 플로우에서 어디: 에이전트 → 수집기 → Kafka → 적재 처리기 → **ClickHouse 안의 MV** → 조회 API → 스캐터 화면. 코드가 아니라 **DB 안에서 표를 채우는 규칙** 한 줄이 걸려 있다. 수집기 · 적재 처리기 · 조회 코드는 0줄 바뀐다

## 문제

스캐터(`GET /traces/scatter`, `#110`)가 **맨 앞 서비스에서만** 점을 보여준다. 주문 · 결제 · 재고를 고르면 비어 있다. `transactions` 를 채우는 MV 조건이 `parent_span_id = ''`(루트만)이라, 요청이 처음 닿는 서비스에만 줄이 생긴다.

| 재 본 것 (2026-10-07, 로컬) | 값 |
|---|---|
| `spans` 의 SERVER 스팬 | gateway 30,478 · order 30,356 · inventory 21,447 · payment 21,396 : **네 서비스 다 있다** |
| 그중 루트 | gateway 30,478 · 나머지 셋 합쳐 **72** |
| 중간 서비스 `transactions` 에 남아 있던 줄의 정체 | **헬스체크 21 · 18 과 앱 시작 `CREATE TABLE` 5.** 전부 `#92` 가 거르기 전에 자식 없는 SERVER 스팬이 루트로 분류돼 들어간 것 |
| 조건을 넓히면 | 30,572 → 103,699줄, **약 3.4배** |

**무엇이 깨지나**: "어느 서비스가 느린가" 를 보려고 만든 화면인데 **느린 서비스를 고를 수가 없다.** 결제가 느려도 결제 화면은 비어 있고, gateway 의 느린 점을 하나씩 눌러 안을 들여다봐야 결제 탓인지 안다. 버그가 아니라 **표의 기준(고객 요청 하나 = 1줄)과 화면의 기준(서비스를 골라 본다)이 어긋난 것**이다. 다른 집계표(`url_stats_1m` · `service_health_1m`)는 이미 SERVER 기준이었고 `transactions` 만 달랐다.

## 선택지

**고르는 것은 조건 하나다.** 조회 코드와 조립은 멀쩡하다.

| 조건 | 얻는 것 | 포기하는 것 | 구현 크기 |
|---|---|---|---|
| 지금 (`parent_span_id = ''`) | 고객 요청 하나 = 1줄 | 중간 서비스 스캐터가 빈다 | 없음 |
| ① `SERVER` | 네 서비스에 점 | 큐(CONSUMER)로 들어온 요청을 빠뜨린다 | 한 줄 |
| **② `SERVER` + `CONSUMER`** | ① + 큐 대비. **지금 비용 0**(CONSUMER 0건) | 스스로 시작한 일(배치 · 앱 시작 DDL)은 안 잡힌다 | **한 줄** |
| ③ ② + `OR parent_span_id = ''` | 업계 표준(Elastic · Datadog · Pinpoint). 배치까지 잡는다 | 앱 시작 `CREATE TABLE` 56줄이 점으로 찍힌다 | 한 줄 |
| B. 조회가 `spans` 직접 읽기 | DB 변경 없음 | 원본 표라 느리고 히트맵이 집계표를 못 쓴다 | 조회 코드 |
| C. 지금대로 + 안내 문구 | 변경 없음 | 기능이 반쪽 | 화면 |

같이 정한 것 : **`is_root` 컬럼**(조건을 넓히면 "루트였다" 가 사라지므로 컬럼으로 옮긴다) · **`span_id` 는 안 넣는다** · **백필 안 한다** · **운영 MV 교체는 `ALTER ... MODIFY QUERY`**.

출처 링크 · 업계 여섯 제품 · ClickHouse 샌드박스 실험 · AI 가 틀렸던 것은 [`research.md`](research.md).

## 결정

- **고른 것**: **② `span_kind IN ('SERVER', 'CONSUMER')` + `is_root UInt8` 컬럼**. 루트는 SERVER 의 부분집합이라 넓혀도 잃는 게 없고, 다른 집계표와 기준이 맞고, 조회 코드는 안 고친다. CONSUMER 는 지금 0건이라 비용 0 인데 남의 앱이 큐를 쓰면 필요하다. `is_root` 는 985바이트(표의 0.04%)이고 `PREWHERE` 가 자동으로 걸려 조회가 오히려 빨라진다. Elastic APM 의 `transaction.root` 와 같은 패턴
- **버린 것과 이유**: ① 은 큐 진입을 빠뜨린다(여섯 제품 중 SERVER 단독은 Jaeger SPM 하나, 그 문서도 경고) · ③ 은 `CREATE TABLE` 점이 찍힌다(운영에서도 파드가 뜰 때마다) · B 는 느리고 히트맵이 깨진다 · C 는 반쪽 · **백필**은 `transactions` 에 직접 INSERT 해도 `mv_heatmap_1m` 이 따라 돌아 `SummingMergeTree` 가 `cnt` 를 **조용히 합산**한다(실험 확인). 복제 표가 아니라 두 번 돌리면 그대로 두 배 · **`DROP VIEW` + `CREATE`** 는 그 사이 INSERT 가 영구 유실된다(실험 : 4줄 → 0줄, 경고 없음) · **`005_` 파일**은 운영에 실행 수단이 없어 "있는데 안 도는" 상태가 된다(`#119`)
- **되돌리는 조건**: 배치 · 스케줄 잡이 생기면 ③ 으로 넓힌다 · 전체 요청 수 화면이 생기고 `is_root` 로 안 풀리면 표를 둘로 나눈다 · 스캐터가 눈에 띄게 느려지면 `ORDER BY` 나 `limit` 5000 을 다시 본다 · 한 서비스에 SERVER 스팬이 여러 개 생기는 구성(사이드카)이 오면 중복 계수를 다시 본다

**뜻이 바뀐다** : 「트랜잭션」이 "고객 요청 하나" 에서 "서비스가 받은 요청 하나" 로. 요청 하나가 서비스 4개를 지나면 1줄 → **4줄**. 이 표의 `count()` 는 고객 요청 수가 아니다. 고객 요청 수는 `is_root = 1`.

4요소 전문은 [`decision.md`](decision.md), 결정을 만든 프롬프트 원문은 [`prompts.md`](prompts.md) 와 ADR 안에 있다.

## 장애가 나면

| 무엇이 죽으면 · 틀리면 | 어떻게 되나 | 어떻게 알아채나 |
|---|---|---|
| 팀원이 `down -v` 없이 파일만 받는다 | **옛 조건 MV 가 그대로 남는다.** DDL 이 빈 DB 에서만 돌고 전부 `IF NOT EXISTS` 다 | 스캐터가 여전히 gateway 만. `SHOW CREATE VIEW mv_transactions` 에 `parent_span_id` 가 보인다 |
| 운영에서 `DROP VIEW` + `CREATE` 로 바꾼다 | 그 사이 INSERT 가 **영구 유실.** 에러도 경고도 없다 | 그 구간 스캐터 · 히트맵이 영원히 0. 사후에만 안다 |
| `MODIFY QUERY` 에서 컬럼을 빠뜨린다 | **조용히 타입 기본값**(빈 문자열 · 0)이 채워진다. 스키마를 검사하지 않는다 | 바꾼 직후 검증 쿼리 : `countIf(service_name = '')` 가 0 이어야 한다 |
| `is_root` 를 MV 수정보다 **나중에** 추가한다 | 타깃에 없는 컬럼이 조용히 무시돼 그 사이 값이 버려진다 | 과거 구간 `is_root` 가 전부 0. 파일 순서 003 → 004 가 막아 둔다 |
| 이 표로 전체 요청 수를 센다 | **4배 부풀려진다** | `is_root = 1` 과 비교하면 드러난다. ERD · ADR 에 적어 뒀다 |
| 쇼핑몰 말고 큐를 쓰는 앱이 붙는다 | CONSUMER 가 이미 조건에 있어 **그 서비스 스캐터가 나온다** | 해당 없음 (② 를 고른 이유) |
| 배치 · 스케줄 잡이 생긴다 | 루트가 INTERNAL 이라 **안 잡힌다** | 그 서비스 스캐터가 빈다. 되돌림 ① |
| 과거 구간을 백필한다 | `heatmap_1m` 의 `cnt` 가 **조용히 두 배.** 줄 수가 안 늘어 눈에 안 보인다 | 백필 전후 `sum(cnt)` 대조. 히트맵 파티션을 먼저 비워야 한다 |

표별 영향은 [`tables.md`](tables.md). 파이프라인 전체 고장 표는 [`../../design/30-failure-modes.md`](../../design/30-failure-modes.md).

## 어떻게 확인했나

| | 고치기 전 | 고친 뒤 |
|---|---|---|
| `transactions` 서비스별 | gateway 30,000 · 나머지 **0** | gateway 30,000 · order 30,000 · payment 21,083 · inventory 21,083 |
| `transactions` 합계 vs `spans` SERVER | 30,000 vs 102,166 | **102,166 = 102,166** (정확히 일치) |
| `sum(is_root)` | 컬럼 없음 | **30,000** = `spans` 의 루트 SERVER 수 30,000 |
| 헬스체크 줄 | (옛 데이터에 섞여 있었다) | **0** |
| 빈 컬럼 (MODIFY QUERY 함정 검사) | : | `service_name` · `trace_id` · `span_name` · `agent_id` 빈 줄 0, `duration_ms` · `http_status` 0 인 줄 0 |
| `heatmap_1m` | gateway 만 | 네 서비스 전부 (1,219칸) |
| `ScatterApiTest` | 6건 기준 | 픽스처를 새 기준으로 고치고 **중간 서비스에 점이 나오는 단언 추가** |
| `check-pipeline.sh` | 통과 | 통과 (traces · metrics · logs) |

- **일부러 깨뜨려 본 것** : 조사 B 가 샌드박스 DB 에서 `DROP VIEW` + `CREATE`(4줄 → 0줄 유실) · MV 둘 같이 돌리기(4줄 → 8줄) · `EXCHANGE TABLES`(둘 다 계속 돈다) · 타깃 직접 INSERT 가 하위 MV 를 돌리는 것(cascade) · `MODIFY QUERY` 가 컬럼 누락을 통과시키는 것을 전부 실제로 돌려 봤다
- **테스트가 옛 기준을 박아 두고 있었다** : `ScatterApiTest` 가 "자식 스팬은 요청이 아니다" 로 같은 서비스의 자식 SERVER 스팬을 빼고 6건을 기대했다. 새 기준에서는 그 스팬이 들어오는 게 맞다. 픽스처를 "gateway 의 CLIENT(보낸 것, 요청 아님) + order 의 SERVER(받은 것, 요청)" 로 바꿔 gateway 는 그대로 6건, **order 를 고르면 1건이 나오는 단언**을 더했다. 이 단언이 `#118` 이 고친 동작 그 자체다

## 결과물

- 수정 : `db/clickhouse/003_create_rollup_tables.sql`(`is_root` 컬럼) · `004_create_materialized_views.sql`(`mv_transactions` 조건 · SELECT · 주석) · `api-server/.../ScatterApiTest.kt`(픽스처 · 단언 1개 추가) · `docs/design/web-v2/erd.md`(4곳) · `docs/design/01-decisions.md`(ADR `#52` 신설, +179/-0) · `AGENTS.md` §5 · §6
- 새 파일 : 이 폴더(`README` · `research` · `prompts` · `decision` · `tables`)
- 후속 이슈 : [`#119`](https://github.com/2026-techeer-project-team-b/monimo-backend/issues/119) 운영 ClickHouse 마이그레이션 수단
- 코드 0줄 : 수집기 · 적재 처리기 · 조회 API

## 읽는 순서

1. [`research.md`](research.md) : 1 · 2절부터. 스캐터 · 히트맵 · MV · 루트와 SERVER 의 차이가 거기 있다
2. [`research.md`](research.md) 5절 : 선택지 셋 · 업계 여섯 제품 · ClickHouse 샌드박스 실험 · 함정 9개
3. [`prompts.md`](prompts.md) : 조사 프롬프트 2건과 결정 프롬프트 원문
4. [`decision.md`](decision.md) : ADR `#52` 4요소
5. [`tables.md`](tables.md) : `transactions` · `heatmap_1m` 에 미친 영향

## 이 이슈에서 배운 것 (세 줄)

- **설계대로 동작하는 것과 쓸모 있는 것은 다르다.** MV 는 ERD 대로 돌았는데 화면이 서비스별 구조라 비어 보였다. 표를 만들 때 "누가 어떤 기준으로 읽나" 를 안 물었다
- **조건 한 줄을 넓히면 그 조건이 들고 있던 정보가 사라진다.** "루트만" 이라는 조건이 곧 "루트였다" 는 정보였다. 넓히면서 그걸 컬럼(`is_root`)으로 옮겨야 했고, Elastic 이 같은 일을 `transaction.root` 로 하고 있었다
- **로컬의 `down -v` 가 운영의 구멍을 가리고 있었다.** ClickHouse 에 마이그레이션 수단이 없다는 것을 이 변경이 처음 드러냈다. 파일을 고쳐도 떠 있는 서버는 조용히 건너뛴다
