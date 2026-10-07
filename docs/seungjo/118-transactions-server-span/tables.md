# 표에 미친 영향 : `#118`

> 컬럼 정본은 `db/postgres/` · `db/clickhouse/` 의 DDL 이다. 여기서는 베끼지 않고 **왜 이 자리에서 건드려야 했나**만 적는다.

## 바꾼 표

`db/clickhouse/003_create_rollup_tables.sql` 의 `transactions` 에 `is_root UInt8` 컬럼 하나, `004_create_materialized_views.sql` 의 `mv_transactions` 조건과 SELECT.

**마이그레이션 파일은 없다.** 그리고 그것이 이 이슈가 드러낸 문제다 : `db/clickhouse/*.sql` 은 빈 DB 에서만 돌고 전부 `IF NOT EXISTS` 라, 파일을 고쳐도 떠 있는 ClickHouse 는 조용히 건너뛴다. 로컬은 `docker compose down -v` 로 적용하고, 운영 수단은 [`#119`](https://github.com/2026-techeer-project-team-b/monimo-backend/issues/119) 로 넘겼다.

## 건드린(영향받은) 표

| 표 | 주인(쓰는 쪽) | 어떻게 영향받나 | 이번 작업 뒤 |
|---|---|---|---|
| `transactions` | MV 가 채운다. 읽는 쪽은 조회 API(스캐터 · 요청 목록) | 채우는 규칙이 "루트만" 에서 "SERVER · CONSUMER" 로. **줄 수 약 3.4배**(30,572 → 103,699). `is_root` 컬럼 추가 | 네 서비스 전부 줄이 있다. 줄 하나의 뜻이 "고객 요청" 에서 "서비스가 받은 요청" 으로 바뀌었다 |
| `heatmap_1m` | `mv_heatmap_1m` 이 `transactions` 에서 파생. 읽는 쪽은 조회 API(히트맵) | **손대지 않았는데 같이 바뀐다.** 원본이 바뀌니 격자가 491 → 1,343(2.74배. 집계가 흡수해 3.4배보다 작다) | 네 서비스 전부 격자가 있다 |
| `spans` | 적재 처리기 | **안 바뀐다.** MV 가 읽기만 한다 | 그대로 |
| `url_stats_1m` · `service_health_1m` · `server_map_1m` | 각자 MV | **안 바뀐다.** 이미 `span_kind` 기준이었다. 이번 변경으로 `transactions` 가 이들과 **같은 기준**이 됐다 | 그대로 |

## 왜 이 자리에서 고쳤나

**MV 는 insert 시점에 한 번만 돈다.** `spans` 에 줄이 들어오는 그 순간 조건을 통과한 줄만 `transactions` 로 간다. 그래서 조회 쪽에서 고칠 수 없다 : 읽을 줄이 아예 없는데 조회가 무엇을 하겠나. `#83`(`peer_service`) · `#92`(헬스체크) 와 같은 이유로 **적재 쪽(여기서는 MV 조건)** 을 고쳐야 했다.

조회가 `spans` 를 직접 읽는 길(B)도 있었지만, 그러면 `heatmap_1m` 이 집계표를 못 쓰고 원본 표를 매번 훑게 된다. 집계표를 만든 이유가 없어진다.

**`is_root` 를 조건이 아니라 컬럼에 둔 이유** : 조건 `parent_span_id = ''` 는 "루트만 통과" 이면서 동시에 "들어온 줄은 전부 루트" 라는 정보였다. 조건을 넓히면 그 정보가 사라진다. 컬럼으로 옮기면 한 표에서 서비스별(`service_name`)과 고객 요청 수(`is_root = 1`)를 다 본다. `UInt8` 하나라 985바이트(표의 0.04%)이고 ClickHouse 가 `PREWHERE` 로 자동으로 옮겨 조회가 오히려 빨라진다(읽는 바이트 430 → 147 KiB).

## 이 표들이 고장 나면

| 가정 | 무슨 일이 생기나 | 막아 둔 것 |
|---|---|---|
| 팀원이 파일만 받고 `down -v` 를 안 한다 | 옛 조건 MV 가 남아 **스캐터가 여전히 gateway 만** | PR 본문 · `AGENTS.md` 에 안내. `SHOW CREATE VIEW monimo.mv_transactions` 로 확인 가능 |
| 운영에서 `MODIFY QUERY` 로 바꾸다 컬럼을 빠뜨린다 | 그 컬럼이 **조용히 타입 기본값**(빈 문자열 · 0). 스키마를 검사하지 않는다 | 004 주석에 적어 뒀다. 바꾼 뒤 `countIf(service_name = '')` 등 검증 쿼리를 돌린다(`#119` 절차에 넣는다) |
| `is_root` 컬럼 없이 MV 만 먼저 바꾼다 | 타깃에 없는 컬럼이 **조용히 무시**돼 그 사이 `is_root` 가 버려진다 | 파일 순서가 003 → 004. 운영 절차에서도 `ADD COLUMN` 먼저(`#119`) |
| 이 표로 "전체 요청 수" 를 센다 | **4배 부풀려진다** (요청 하나가 서비스 4개를 지나면 4줄) | `is_root = 1` 로 세도록 ERD · ADR · 003 주석에 적었다 |
| 과거 구간을 백필한다 | `transactions` 직접 INSERT → `mv_heatmap_1m` 이 돈다 → `SummingMergeTree` 가 `cnt` 를 **조용히 두 배**. 줄 수가 안 늘어 눈에 안 보인다 | 백필을 안 하기로 했다. 하게 되면 히트맵 파티션 먼저 비우기 → 날짜 파티션 단위 → 별 표 + `MOVE PARTITION`(`research.md` 5.8) |
| 큐를 쓰는 앱이 붙는다 | CONSUMER 가 조건에 있어 **정상** | 미리 넣어 둔 이유 |
| 배치 · 스케줄 잡이 생긴다 | 루트가 INTERNAL 이라 **그 서비스 스캐터가 빈다** | ADR `#52` 되돌림 ① : `OR parent_span_id = ''` 로 넓힌다 |

## 다른 파트에 알릴 것

- **조회(현영)** : `transactions` 줄 하나의 뜻이 바뀌었다. 서비스별 화면은 그대로 쓰면 되고, **고객 요청 수를 세는 화면이 생기면 `is_root = 1`** 로. `span_id` 는 요청 목록(`GET /traces/transactions`)에서 줄을 눌러 그 구간으로 바로 가는 화면을 만들 거면 그때 넣는다
- **전원** : 파일을 받으면 `docker compose down -v` → 켜기 → `./scripts/seed-clickhouse.sh`. 안 하면 옛 MV 가 남는다
- **배포** : 운영에 ClickHouse 마이그레이션 수단이 없다(`#119`). 배포 전에 정해야 한다
- **노션 「ERD」 정본** : `transactions` 설명 · MV 조건 · `is_root` 컬럼 · `spans.parent_span_id` 설명 네 곳을 레포 사본과 같게 고친다
