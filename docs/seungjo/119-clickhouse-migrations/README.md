# `#119` ClickHouse 마이그레이션 수단을 만든다 : PG 가 쓰는 Flyway 를 그대로

이슈 `#119` · ADR `#57` · 브랜치 `chore/119-clickhouse-migrations` · `#118` 이 올린 후속

표 정의를 **새로 바꾸지는 않는다.** 바꿀 수단을 만드는 것까지다.
다만 리뷰에서 나온 **호환 마이그레이션 둘**은 넣었다(아래 「리뷰가 잡은 것」). `#118` 이 이미 정한 변경이 옛 로컬에 닿게 하는 것이고, 이 수단의 첫 실사용이기도 하다.
가볍게 간다 : 이 폴더에 `research.md` · `prompts.md` · `tables.md` · `decision.md` 는 없다.
어려운 쪽(어떻게 바꿔야 안전한가)은 `#118` 조사가 이미 풀어 뒀다.

## 문제

ClickHouse 표 정의를 **바꿀 수단이 없었다.**

```
db/clickhouse/*.sql 을 ClickHouse 가 처음 켤 때 한 번 읽는다
데이터가 있으면 안 읽는다
게다가 전부 CREATE ... IF NOT EXISTS 라 있으면 그냥 넘어간다

-> 파일을 고쳐도 떠 있는 서버는 조용히 건너뛴다. 에러도 경고도 없다
```

그래서 적용하는 방법이 **DB 를 통째로 날리는 것** 하나뿐이었다. `down -v` 는 PG 볼륨까지 지워서 seed 도 다시 돌려야 한다.

**실제 비용은 운영이 아니라 지금 나고 있었다.** 최근 2주에 두 번 팀원 전원에게 이걸 시켰다.

| 이슈 | 시킨 것 |
|---|---|
| `#118` | `docker compose down -v` -> 켜기 -> `seed-clickhouse.sh` |
| `#121` | 같은 것 |

운영에서는 날릴 수가 없으니 **아예 못 바꾼다.** 이슈 제목이 "운영에서" 인데, 운영은 아직 없고(배포 7~9단계 미완) 아픈 쪽은 지금이었다.

## 해결

PG 가 쓰는 Flyway 를 ClickHouse 에도 붙였다. 일회성 컨테이너가 떴다가 일하고 꺼진다.

```
PG     postgres-migrate    뜬다 -> 안 돌린 파일만 돌린다 -> 장부에 적는다 -> 꺼진다
CH     clickhouse-migrate  똑같이
```

얻는 것이 셋이다.

| | |
|---|---|
| 떠 있는 서버에도 적용된다 | 날릴 필요가 없다 |
| 어디까지 적용됐는지 장부가 남는다 | `monimo.flyway_schema_history` |
| **파일을 몰래 고치면 멈춘다** | 조용히 건너뛰던 것의 정반대다 |

마지막이 크다. 지금 문제가 **고쳐도 아무 일도 안 일어나는 것**인데, Flyway 는 이미 적용한 파일이 바뀌면 `checksum mismatch` 로 멈춘다.

## 결정

- **고른 것** : Flyway. 본체 버전을 PG 와 같은 `11.7.2` 로 맞추고, 공식 커뮤니티 플러그인(`flyway-database-clickhouse` `10.26.0`)을 얹은 이미지를 `db/clickhouse/Dockerfile` 로 만든다. jar 는 레포에 커밋하지 않고 빌드할 때 받는다
- **버린 것과 이유** : **전용 도구**(golang-migrate · Atlas · dbmate 등)는 도구가 하나 늘고 PG 와 규칙이 갈려 두 벌을 배워야 한다 · **직접 만들기**는 멱등 · 순서 · 실패 처리 · checksum 을 다시 만드는 일이다 · **그대로 두기**는 변경이 생길 때마다 팀원 전원이 로컬을 날린다 · **jar 를 레포에 커밋**하면 12MB 바이너리가 들어간다 · **컨테이너가 뜰 때 받아오기**는 네트워크가 없으면 못 뜬다 · **플러그인에 맞춰 본체를 `10.x` 로 내리기**는 PG 와 버전이 갈린다 · **`CLICKHOUSE_DB` 환경변수로 DB 를 미리 만들기**는 그것도 **첫 기동 때만 도는 entrypoint 기능**이라 우리가 없애려는 "처음 켤 때만" 의존이 그대로 남는다(리뷰가 공식 이미지 entrypoint 로 확인해 줬다)
- **다시 볼 조건 셋** : ① 플러그인이 본체를 따라오지 못해 보안 패치를 못 받으면 전용 도구로 ② ClickHouse 를 복제 구성으로 올리면 `ON CLUSTER` 가 필요해져 절차를 다시 본다 ③ 운영 실행 주체를 K8s Job 으로 바꿀 때 이 컨테이너 정의를 그대로 옮긴다

## 같이 바뀐 것 셋

**① 가짜 데이터가 `scripts/seed/` 로 갔다.** Flyway 는 하위 폴더까지 긁어서 `db/clickhouse/seed/` 에 두면 마이그레이션으로 딸려 들어간다. 옛 방식은 하위 폴더를 안 읽어서 괜찮았다. PG 도 seed 를 `scripts/seed/` 에 둔다.

**② 헬스체크가 바뀌었다.** 전에는 ClickHouse 가 표 만드는 일까지 겸해서 "마지막 MV 가 생겼나" 를 봤다. 이제 표는 migrate 컨테이너가 만드니 ClickHouse 는 "살아 있나" 만 보면 된다.

```
전   clickhouse 헬스체크 : EXISTS TABLE monimo.mv_metrics_1h
     표가 필요한 서비스 : clickhouse 가 healthy 가 될 때까지

후   clickhouse 헬스체크 : SELECT 1
     표가 필요한 서비스 : clickhouse-migrate 가 정상 종료될 때까지
```

**③ 장부를 `monimo` 안에 둔다.** 붙는 곳은 `default` 이지만 일하는 곳과 장부 자리는 `monimo` 다. 그래서 `monimo` 안의 표가 물리적으로는 장부를 포함해 12개가 된다. `check-dev-infra.sh` 는 **장부를 빼고 11 을 유지**하도록 고쳤다. **처음에는 `default` 에 뒀다가 리뷰에서 뒤집혔다**(아래).

## 어떻게 확인했나

### 떠 있는 DB 에 적용해도 데이터가 안 사라진다

로컬에 실데이터가 140만 줄 쌓인 상태에서 `down -v` 없이 적용했다.

```
            적용 전        적용 후
spans       1,405,331  ->  1,405,331
logs           54,564  ->     54,564
metrics_raw   137,855  ->    137,855
표 · MV         11 · 7  ->     11 · 7 (측정 당시. 장부가 monimo 로 들어온 뒤는 물리 12 · 7)
장부           없음      ->  네 줄 전부 success (이 측정 뒤에 호환 파일 둘이 더 붙어 지금은 여섯이다)
한 번 더                   "up to date. No migration necessary"
```

이게 되는 이유는 `db/clickhouse` 의 `CREATE` **19개가 전부 `IF NOT EXISTS`** 이고, `ALTER` 둘은 `ADD COLUMN IF NOT EXISTS` 와 `MODIFY QUERY` 로 **덧붙이기만** 하기 때문이다(`INSERT` · `DROP` 은 0개). 이미 데이터를 가진 DB 에 다시 돌려도 지우는 문장이 없다.

### 마이그레이션으로 MV 정의를 바꿀 수 있다

Flyway 가 처음부터 소유한 DB 에서 `V1`(표 + MV 생성) 다음 `V2`(`MODIFY QUERY`)를 적용했다.

```
적용 전   WHERE v < 10     값 5 와 50 을 넣으면 dst = [5]
적용 후   WHERE v < 100    값 60 을 넣으면    dst = [60, 5]
한 번 더  up to date. No migration necessary
```

`60` 은 옛 조건에서는 안 들어왔을 값이다.

### 빈 DB 에서도 전부 만들어진다

`down -v` 뒤 `up -d --wait`.

```
clickhouse-migrate  Exited      (일하고 정상 종료)
infra-ready         Healthy
monimo 표 11개 · MV 7개
check-dev-infra.sh  모두 정상
```

`check-dev-infra.sh` 의 표 개수 단언은 **11 을 유지**하고 세는 쪽에서 장부를 뺐다. 장부가 `monimo` 안에 있지만 우리 표가 아니다. 12로 올리면 누가 `monimo` 에 임시 표를 하나 만들었을 때 숫자가 맞아 조용히 통과한다.

### 적용한 파일을 고치면 멈춘다

일부러 만든 시험이 아니라 **실제로 한 번 걸렸다.** 마이그레이션이 적용된 뒤에 그 파일 둘의 주석을 고쳤더니 다음 `up` 에서 정확히 그 둘을 짚었다.

```
ERROR: Validate failed: Migrations have failed validation
Migration checksum mismatch for migration version 202609220045
Migration checksum mismatch for migration version 202609221905
```

전에는 **고쳐도 아무 일도 안 일어났다.** 그것이 이 이슈의 문제였다.

## 시험이 설계 결함을 하나 잡았다

MV 시험을 짜다 이 에러를 만났다.

```
ERROR: Found non-empty schema(s) "default" but no schema history table.
       Use baseline() or set baselineOnMigrate to true
```

시험 자체는 내 실수였지만(표를 Flyway 밖에서 만들어 두고 migrate 를 불렀다), **같은 일이 팀원에게 날 수 있다.** 누가 ClickHouse `default` 에 임시 표를 하나만 만들어 두면, 그 사람은 `clickhouse-migrate` 가 멈춰서 스택이 아예 안 뜬다.

`FLYWAY_BASELINE_ON_MIGRATE` 로 막았다. 장부를 `v1` 로 깔고 시작하는데 우리 파일 번호가 전부 `2026...` 이라 `v1` 보다 커서 **하나도 안 건너뛰고 실행되고**, 전부 `IF NOT EXISTS` 라 이미 있으면 아무 일도 안 한다.

## 안 잰 것

**"적용 중에 집계가 비지 않는다" 를 이번에 다시 재지 않았다.** 동시 쓰기를 세 번 시도해 세 번 다 못 만들었다. ClickHouse 의 `INSERT SELECT` 는 블록 단위로 커밋해서 느리게 돌려도 중간 상태가 안 보이고, `docker compose exec` 를 반복하는 방식은 불안정했다.

그 성질은 `#118` 조사가 샌드박스에서 `DROP VIEW` + `CREATE` 와 `MODIFY QUERY` 를 **직접 비교해 이미 확인**한 것이다(앞쪽은 4줄 넣고 0줄, 뒤쪽은 손실 없음). 여기서는 다시 재지 않고 인용한다.

## 리뷰가 잡은 것 셋 (전부 High)

**① `#118` 이전 로컬은 스택이 아예 안 떴다.** 처음에 PR 에 "팀원이 해야 하는 것 : 없다" 로 적었는데 **틀렸다.** 위의 live-apply 측정을 **이미 `#118` 이후 상태인 DB 에서만** 하고 일반화했다.

`CREATE MATERIALIZED VIEW IF NOT EXISTS ... TO ... AS SELECT` 는 **존재 검사보다 SELECT 분석을 먼저** 한다. MV 가 이미 있어도 타깃 표에 `is_root` 가 없으면 터지고, `clickhouse-migrate` 가 비정상 종료해 `infra-ready` 가 안 켜진다.

문서로 덮지 않고 **호환 마이그레이션 둘**로 고쳤다.

| 파일 | 하는 일 | 없으면 |
|---|---|---|
| `V202609221906` | `transactions` 에 `is_root` 를 채운다 | 다음 파일이 **터진다** |
| `V202609221908` | `mv_transactions` 를 `MODIFY QUERY` 로 덮어쓴다 | 터지진 않지만 **조용히 옛 정의를 그대로 쓴다** |

둘째가 핵심이다. `CREATE ... IF NOT EXISTS` 는 **이미 있는 MV 를 갱신하지 못한다.** 컬럼만 채우면 에러는 사라지지만 옛 DB 가 옛 조건을 그대로 쓴다. 에러가 없어 아무도 모른다. **이 이슈가 없애려는 바로 그 실패 모양이다.**

양쪽으로 쟀다.

```
호환 파일 없이  ERROR: Script V202609221907__create_materialized_views.sql failed
호환 파일 넣고  6개 전부 적용. is_root 가 생기고 조건이 span_kind IN ('SERVER','CONSUMER') 로 바뀌고
               SERVER 스팬 2개 -> transactions 2줄 (옛 조건이면 1줄), sum(is_root) = 1
```

### `repair` 가 절반만 된다

README 에 실패 복구 절차로 "`repair` 를 돌린다" 고 적어 뒀는데, 관통 시험에서 그게 **절반만 사실**인 것이 드러났다.

```
실패 행 지우기      된다   (일부러 터뜨린 V202609300000 행이 사라지고 다음 migrate 정상)
체크섬 어긋남 고치기  안 된다  Code: 48 Lightweight updates are not supported
```

체크섬을 맞추려면 장부를 `UPDATE` 해야 하는데 ClickHouse `26.8` 의 가벼운 UPDATE 는 표에 설정 둘(`enable_block_number_column` · `enable_block_offset_column`)이 켜져 있어야 한다. 둘을 켜면 `repair` 가 정상 동작한다. 무거운 mutation(`ALTER TABLE ... UPDATE ... SETTINGS mutations_sync = 2`)은 설정 없이 돌아간다.

**도구가 못 하는 것을 내 문서가 약속하고 있었다.** 이 이슈가 없애려는 바로 그 모양이라, `db/clickhouse/README.md` 의 복구 절을 두 경로로 나눠 고쳤다. 내가 직접 체크섬을 어긋나게 만들었을 때(주석 한 줄 수정) 처음 걸렸다.

**번호를 한 번 틀렸다.** `1905900` 으로 끼우려 했는데 Flyway 는 버전을 숫자로 비교해서 `202609221905900`(15자리)이 `202609221906`(12자리)보다 크다. 분 단위로 다시 매겼다.

**② 장부 자리를 처음에 틀렸다.** `default` 에 붙이고 장부도 거기 뒀는데, 사유로 적은 "빈 서버에는 `monimo` 가 없어 거기 붙을 수가 없다" 가 **제약이 아니었다.** Flyway 의 `createSchemas`(기본 참)가 없으면 만들어 준다.

그리고 `default` 에 두면 **장부와 표의 생명주기가 갈려 조용히 틀린다.**

```
DROP DATABASE monimo
-> migrate: "up to date. No migration necessary."  (종료코드 0)
-> 표 0개인 채로 infra-ready 가 켜진다. 장부만 영구히 "다 됐다" 고 말한다
```

**이 이슈가 없애려는 바로 그 실패 모양이다.** `FLYWAY_SCHEMAS=monimo` 로 바꾸니 장부가 표와 같이 사라지고 다음 `migrate` 가 전부 다시 만든다. 대가는 장부가 `monimo` 안으로 들어와 점검 스크립트가 **세는 쪽**을 고친 것뿐이다(단언은 11 그대로).

**처음에는 그 단언이 안 깨지는 것을 "부수 효과" 로 적었는데, 사실은 틀린 선택을 유지하는 쪽의 변명이었다.**

세 경로를 다시 쟀다.

```
빈 서버          장부가 monimo 에, 6개 적용, 표 11(장부 빼고) · MV 7
                 장부 첫 줄은 << Flyway Schema Creation >> (Flyway 가 monimo 를 직접 만들어서)
#118 이후 DB     장부 첫 줄은 << Flyway Baseline >> v1 -> 6개 전부 실행(전부 무해) -> 표 11 · MV 7
#118 이전 DB     is_root 0 -> 1, 조건 parent_span_id='' -> span_kind IN ('SERVER','CONSUMER')
clean            cleanDisabled 로 막힌다 (Flyway 가 monimo 를 소유하므로 한 번 돌면 신호가 다 날아간다)
```

**③ 루트 `README.md` 가 이 변경이 없앤 동작을 아직 정본처럼 적고 있었다.** `AGENTS.md` 가 "로컬 실행은 `README.md` 를 본다" 로 가리키는 파일인데, 존재하지 않는 파일명 셋 · 옛 seed 경로 · "데이터가 비어 있을 때만 실행되므로 `down -v` 후 켠다" 가 남아 있었다. `#126` 이 `api-spec.md` 의 거짓 기술을 고친 것과 같은 종류다.

## 운영 절차

표 정의를 바꿀 때 지켜야 하는 것(`MODIFY QUERY` 만 쓸 것 · 바꾼 뒤 값이 들어오는지 확인할 것 · 컬럼 추가가 먼저일 것 · 과거 구간을 채울 때 조심할 것)은 **`db/clickhouse/README.md`** 에 적었다. 파일 옆에 둬야 바꾸려는 사람이 본다.

## 바꾼 파일

- `db/clickhouse/*.sql` : 기존 네 개의 이름을 `V{년월일시분}__{동사}_{대상}.sql` 로(내용은 그대로, `git` 이 전부 rename 으로 인식). 호환 마이그레이션 둘(`V...1906` · `V...1908`) 신설
- `db/clickhouse/Dockerfile` : 신설. Flyway 에 플러그인과 드라이버를 얹는다
- `db/clickhouse/README.md` : 신설. 운영 절차
- `compose.yaml` : `initdb` 마운트 제거 · `clickhouse-migrate` 추가 · 헬스체크 교체 · `infra-ready` 의존 교체
- `scripts/seed/clickhouse-fake-signals.sql` : `db/clickhouse/seed/` 에서 옮김
- `scripts/seed-clickhouse.sh` : 경로 한 줄
- `docs/design/01-decisions.md` : ADR `#57`
- `scripts/check-dev-infra.sh` : 표를 셀 때 장부를 빼고(11개 유지), PG 와 같은 기준으로 CH 장부의 성공 · 실패도 본다
- `AGENTS.md` : §2 마이그레이션 규칙(PG 전용으로 적혀 있었다) · §5 `#119` · §6 해당 행 해소
- `README.md`(루트) : 이 변경이 없앤 동작을 정본처럼 적고 있던 네 곳
- 낡은 경로를 가리키던 넷 : `ingester/.../MetricRow.kt` · `docs/alert/40-agent-down.md` · `scripts/seed/postgres-applications.sql` · `AGENTS.md` §6
