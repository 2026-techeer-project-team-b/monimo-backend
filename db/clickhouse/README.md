# ClickHouse 마이그레이션

표 · MV 정의를 바꾸는 자리다. PG 쪽(`db/postgres/`)과 같은 모양으로 돈다 (ADR `#57`).

```
db/clickhouse/V{년월일시분}__{동사}_{대상}.sql   표 · MV 정의
db/clickhouse/Dockerfile                      Flyway 에 ClickHouse 플러그인을 얹은 이미지
scripts/seed/clickhouse-fake-signals.sql      가짜 데이터 (마이그레이션 아니다)
```

가짜 데이터가 여기 없는 이유는 **Flyway 가 하위 폴더까지 긁기** 때문이다. 전에는 `db/clickhouse/seed/` 에 있었는데, 옛 방식(`docker-entrypoint-initdb.d`)은 하위 폴더를 안 읽어서 괜찮았다.

## 돌리는 법

```bash
docker compose run --rm clickhouse-migrate
```

`docker compose up` 을 하면 알아서 돈다. `infra-ready` 가 이 컨테이너가 **정상 종료되기를** 기다리므로, 표가 필요한 서비스는 표가 다 생긴 뒤에 뜬다.

**`Dockerfile` 이 바뀌었으면 한 번 다시 빌드해야 한다.** 이미지 태그가 고정이라 이미 있으면 compose 가 다시 안 만든다. 플러그인이나 드라이버 버전을 올려도 옛 Flyway 를 쓰게 된다.

```bash
docker compose build clickhouse-migrate
```

**첫 빌드에는 네트워크가 필요하다.** jar 를 레포에 두지 않고 Maven Central 에서 받기 때문이다. 한 번 만들고 나면 필요 없다.

## 전에는 왜 안 됐나

`db/clickhouse/*.sql` 을 ClickHouse 가 **처음 켤 때만** 읽었다. 데이터가 있으면 안 읽고, 게다가 전부 `CREATE ... IF NOT EXISTS` 라 있으면 그냥 넘어갔다. **파일을 고쳐도 떠 있는 서버는 조용히 건너뛰었다.**

그래서 적용하는 방법이 `docker compose down -v` 하나뿐이었고, 그건 PG 볼륨까지 지워서 seed 를 다시 돌려야 했다. 운영에서는 날릴 수가 없으니 **아예 못 바꿨다.**

Flyway 는 장부(`monimo.flyway_schema_history`)와 파일을 대조해서 **안 돌린 것만** 돌린다. 그리고 이미 돌린 파일이 바뀌면 **멈추고 알려 준다.** 조용히 건너뛰던 것의 정반대다.

## 파일을 새로 만들 때

```
V{년월일시분}__{동사}_{대상}.sql      예: V202610091730__add_spans_foo_column.sql
```

- **이미 들어간 파일을 고치지 않는다.** 고치면 장부와 어긋나 `checksum mismatch` 로 멈추고, 이미 적용한 사람은 전부 `repair` 를 돌려야 한다. 바꿀 것이 있으면 **새 파일**을 만든다
- 번호는 만든 시각이다. 머지 순서가 번호 순서와 달라도 늦게 들어온 파일이 적용된다(`outOfOrder`). 순서 의존 문제는 CI 가 빈 DB 에 전부 돌려서 잡는다

## 표 정의를 바꿀 때 (중요)

`#118` 조사가 샌드박스에서 여섯 방법을 직접 돌려 본 결과다.

### MV 는 `ALTER TABLE ... MODIFY QUERY` 만 쓴다

```sql
ALTER TABLE monimo.mv_transactions MODIFY QUERY SELECT ... ;
```

| 방법 | 집계가 비나 | 중복이 생기나 | |
|---|---|---|---|
| `ALTER TABLE ... MODIFY QUERY` | 안 빈다 | 안 생긴다 | **이것만 쓴다** |
| `DROP VIEW` + `CREATE` | **빈다** | 안 생긴다 | **금지** |
| 새 이름 MV 를 만들어 둘을 같이 돌리기 | 안 빈다 | **정확히 2배** | **금지** |
| `EXCHANGE TABLES` · `RENAME` | 안 빈다 | **2배** | **금지** |

`DROP VIEW` + `CREATE` 가 가장 위험하다. MV 를 지우고 4줄 넣고 다시 만들었더니 타깃에 **0줄**이 들어갔다. **에러도 경고도 없다.** 로컬은 `down -v` 로 리셋하니 안 보이지만 운영에서는 그 구간 그래프가 영구히 0 이 된다.

### 바꾼 뒤 반드시 값이 들어오는지 확인한다

**`MODIFY QUERY` 는 타깃 표 스키마를 검사하지 않는다.** SELECT 에서 컬럼을 빠뜨려도 에러 없이 통과하고 그 컬럼이 타입 기본값으로 채워진다. `service_name` 을 빠뜨리면 그 뒤 모든 줄의 서비스 이름이 빈 글자가 되고 **아무도 모른다.** 타깃에 없는 컬럼을 넣어도 조용히 무시되고 타입이 달라도 암묵 캐스팅된다.

그래서 바꾼 뒤에 이런 쿼리를 돌린다.

```sql
-- 빈 값으로 채워진 컬럼이 없나
SELECT countIf(service_name = ''), countIf(trace_id = ''), countIf(duration_ms = 0)
FROM monimo.transactions WHERE ts > now() - INTERVAL 5 MINUTE;

-- 서비스가 고르게 나오나 (한 서비스만 나오면 안 바뀐 것이다)
SELECT service_name, count() FROM monimo.transactions
WHERE ts > now() - INTERVAL 5 MINUTE GROUP BY service_name;
```

### 컬럼 추가가 MV 수정보다 먼저다

```
넣을 때    ADD COLUMN  ->  MV 수정
뺄 때      MV 수정     ->  DROP COLUMN
```

거꾸로 하면 **조용히 깨진다.** 타깃에 없는 컬럼을 MV SELECT 에 넣으면 에러 없이 무시되므로, MV 를 먼저 고치면 그 사이 들어온 줄의 그 컬럼이 버려진다. 뺄 때 순서가 반대인 이유는 `DROP COLUMN` 이 MV 가 참조하는 컬럼을 거부하기 때문이다.

### 과거 구간을 채울 때

`ADD COLUMN` 의 기본값이 들어가므로 과거 줄은 그 값이 된다. 채우려면 조심할 것이 셋이다.

- **`transactions` 에 직접 INSERT 해도 하위 MV 가 따라 돈다.** `heatmap_1m` 은 `SummingMergeTree` 라 `cnt` 가 **조용히 합산**되고 줄 수가 안 늘어 눈에 안 보인다. "heatmap 해당 파티션 비우기 -> 백필" 순서여야 한다
- **두 번 돌리면 그대로 두 배다.** 복제 표가 아니라 중복 제거가 안 걸린다. 되돌릴 수단이 `DROP PARTITION` 뿐이라 **날짜 파티션 단위로** 하고 어디까지 했는지 적어 둔다
- **한 번에 많이 못 넣는다.** `max_partitions_per_insert_block` 기본값이 100 이라 하루 파티션이 100개를 넘으면 예외가 난다

## 옛 로컬을 위한 호환 마이그레이션 둘

`V202609221906` 과 `V202609221908` 은 **`#118` 이전에 만든 ClickHouse 를 가진 사람**을 위한 것이다. `#118` 이후에 만든 DB 에서는 둘 다 아무 일도 안 한다.

| 파일 | 하는 일 | 없으면 |
|---|---|---|
| `V202609221906` | `transactions` 에 `is_root` 컬럼을 채운다 | 다음 파일이 **터진다.** `clickhouse-migrate` 가 비정상 종료해 스택 전체가 안 뜬다 |
| `V202609221908` | `mv_transactions` 정의를 `MODIFY QUERY` 로 덮어쓴다 | 터지지는 않지만 **조용히 옛 정의를 그대로 들고 있는다** |

첫째가 필요한 이유는 `CREATE MATERIALIZED VIEW IF NOT EXISTS ... TO ... AS SELECT` 가 **존재 검사보다 SELECT 분석을 먼저** 하기 때문이다. MV 가 이미 있어도 타깃 표에 `is_root` 가 없으면 이렇게 터진다.

```
ERROR: Code: 8. DB::Exception: SELECT query outputs column with name 'is_root',
       which is not found in the target table.
```

둘째가 필요한 이유는 `CREATE ... IF NOT EXISTS` 가 **이미 있는 MV 를 갱신하지 못하기** 때문이다. 컬럼만 채우면 에러는 없어지지만 옛 DB 는 옛 조건(`WHERE parent_span_id = ''`)을 그대로 쓴다. 에러가 없어서 아무도 모른다.

**새 DDL 을 쓸 때도 같은 함정이 있다.** 표를 고치는 것은 `CREATE ... IF NOT EXISTS` 로 안 되고 `ALTER` 가 필요하다. 기존 파일을 고치지 말고 새 파일에 `ALTER` 를 쓴다.

### 마이그레이션이 실패했을 때

실패 행이 장부에 남아 재실행이 거부된다.

```
ERROR: Validate failed: Migrations have failed validation
Detected failed migration to version ... Please remove any half-completed changes
then run repair to fix the schema history.
```

반쯤 적용된 것을 손으로 치운 뒤 `docker compose run --rm clickhouse-migrate repair` 를 돌리고 다시 `migrate` 한다. 로컬이고 아까운 데이터가 없으면 `docker compose down -v` 가 더 빠르다.

## 장부

`monimo` 안에 있다. 붙는 곳은 `default` 이지만 일하는 곳과 장부 자리는 `monimo` 다(`FLYWAY_SCHEMAS`).

```sql
SELECT version, description, success FROM monimo.flyway_schema_history ORDER BY installed_rank;
```

**장부를 `default` 에 두면 안 된다.** 표와 생명주기가 갈려서 조용히 틀린다. `DROP DATABASE monimo` 를 하면 다음 `migrate` 가 "up to date" 로 종료코드 0 을 내고, 표가 0개인 채로 `infra-ready` 가 켜진다. 장부만 영구히 "다 됐다" 고 말한다.

그래서 `monimo` 안의 표는 물리적으로 장부를 포함해 12개다. `scripts/check-dev-infra.sh` 는 **장부를 빼고 11 을 유지**한다. 12로 올리면 누가 `monimo` 에 임시 표를 하나 만들었을 때 숫자가 맞아 조용히 통과한다.

장부 줄은 7줄인데 **첫 줄이 사람마다 다르다.** 빈 서버는 Flyway 가 `monimo` 를 직접 만들어서 `<< Flyway Schema Creation >>`(종류 `SCHEMA`)이 깔리고, `#119` 전에 만든 로컬은 "표는 있고 장부는 없다" 라서 `<< Flyway Baseline >>`(종류 `BASELINE`, 버전 `1`)이 깔린다. 둘 다 정상이고 그 뒤 여섯 줄은 같다.

`BASELINE` 쪽은 `FLYWAY_BASELINE_ON_MIGRATE` 가 만든다. **이 플래그를 끄면 옛 로컬은 아무도 못 뜬다.** 장부가 `monimo` 안으로 들어온 뒤로는 예외 경로의 보호가 아니라 주 경로를 떠받친다.

장부가 `default` 에 있던 시절에 이 브랜치를 한 번 돌려 본 사람은 `default.flyway_schema_history` 가 고아로 남는다. 해롭지는 않고, `DROP TABLE default.flyway_schema_history` 로 치우면 깔끔하다.

### `success` 가 스키마를 보장하지 않는다

우리 파일이 전부 `CREATE ... IF NOT EXISTS` 라, 장부의 `success` 는 **"그 파일의 문장을 돌렸다" 일 뿐**이다. `monimo` 의 실제 정의가 파일과 같다는 보장이 아니다.

`mv_transactions` 를 손으로 옛 정의로 되돌린 뒤 `migrate` 를 돌려도 장부는 전부 `success` 인데 정의는 안 고쳐진다. **표 정의를 바꿀 때 `CREATE` 를 고치지 말고 새 파일에 `ALTER` 를 쓰라는 것이 이 때문이다.**

## Flyway 버전

| | |
|---|---|
| 본체 | `11.7.2` (PG 쪽 `postgres-migrate` 와 같다) |
| ClickHouse 플러그인 | `10.26.0` |

플러그인은 공식 **커뮤니티 지원** 이라 본체와 번호가 따로 간다. `11.x` 줄이 아예 없는데(2026-10-09 기준) `11.7.2` 이미지에서 붙는 것을 확인했다. PG 와 버전을 맞추는 쪽이 중요해서 본체를 `10.x` 로 내리지 않았다.
