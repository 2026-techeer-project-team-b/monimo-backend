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

## 전에는 왜 안 됐나

`db/clickhouse/*.sql` 을 ClickHouse 가 **처음 켤 때만** 읽었다. 데이터가 있으면 안 읽고, 게다가 전부 `CREATE ... IF NOT EXISTS` 라 있으면 그냥 넘어갔다. **파일을 고쳐도 떠 있는 서버는 조용히 건너뛰었다.**

그래서 적용하는 방법이 `docker compose down -v` 하나뿐이었고, 그건 PG 볼륨까지 지워서 seed 를 다시 돌려야 했다. 운영에서는 날릴 수가 없으니 **아예 못 바꿨다.**

Flyway 는 장부(`default.flyway_schema_history`)와 파일을 대조해서 **안 돌린 것만** 돌린다. 그리고 이미 돌린 파일이 바뀌면 **멈추고 알려 준다.** 조용히 건너뛰던 것의 정반대다.

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

## 장부가 `default` 에 있는 이유

빈 서버에는 `monimo` DB 가 아직 없다. **그것을 만드는 것이 첫 마이그레이션**이라 거기에 붙을 수가 없다. 그래서 Flyway 는 `default` 에 붙고 장부도 거기 생긴다.

```sql
SELECT version, description, success FROM default.flyway_schema_history ORDER BY installed_rank;
```

`monimo` 안의 표 개수는 그대로다. 점검 스크립트(`scripts/check-dev-infra.sh`)가 세는 숫자가 안 바뀐 것도 이 때문이다.

## Flyway 버전

| | |
|---|---|
| 본체 | `11.7.2` (PG 쪽 `postgres-migrate` 와 같다) |
| ClickHouse 플러그인 | `10.26.0` |

플러그인은 공식 **커뮤니티 지원** 이라 본체와 번호가 따로 간다. `11.x` 줄이 아예 없는데(2026-10-09 기준) `11.7.2` 이미지에서 붙는 것을 확인했다. PG 와 버전을 맞추는 쪽이 중요해서 본체를 `10.x` 로 내리지 않았다.
