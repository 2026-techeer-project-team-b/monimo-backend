# 표에 미친 영향 : `#121`

> 컬럼 정본은 `db/postgres/` · `db/clickhouse/` 의 DDL 이다. 여기서는 베끼지 않고 **왜 이 자리에서 건드려야 했나**만 적는다.

## 바꾼 표

**없다. 표 구조를 하나도 바꾸지 않았다.** 마이그레이션 파일도 없다.

그래도 이 문서가 있는 이유는 이 이슈가 **표를 읽는 쪽을 하나 늘렸기** 때문이다. 지금까지 `application_configs` 는 API 서버만 쓰는 표였는데, 이제 수집기도 30초마다 읽는다. 읽기만 하는데도 적어 둘 것이 셋이다 : ① 그 칸의 뜻이 바뀌었다(서비스별로 받지만 서비스별로 적용되지 않는다) ② 읽는 쪽이 늘었으니 표가 고장 날 때 수집 경로가 어떻게 되는지 정해 둬야 한다 ③ 수집기에 Entity 를 안 만든 것이 우연이 아니라 규칙이다.

읽을 칸은 `db/postgres/config/V202609271938__create_application_configs.sql` 의 `sampling_rate NUMERIC(5,4)` 다. 그 DDL 첫 줄에 이미 적혀 있다 : "소유: API 서버 (ADR #36), 수집기가 30초 캐시로 읽는다 (ADR #37)". 이 이슈를 예상해 둔 자리다.

## 건드린(영향받은) 표

| 표 | 주인(쓰는 쪽) | 어떻게 영향받나 | 이번 작업 뒤 |
|---|---|---|---|
| `application_configs` | **API 서버**(ADR `#36`). 수집기는 **읽기 전용** | 구조는 그대로. `sampling_rate` 를 읽는 쪽이 하나 늘었다. 수집기가 30초마다 `SELECT` 한 번. `version` 과 `updated_at` 은 **안 본다** : 바뀐 것을 시각으로 가늠하지 않고 통째로 다시 읽는다 | **칸의 뜻이 바뀌었다.** 서비스별로 네 줄을 받지만 수집기는 그중 최댓값 하나만 쓴다(ADR `#53`). 로컬 seed 값이 `0.0100` 에서 `1.0000` 으로 |
| `applications` | API 서버. 수집기는 읽기 전용 | **JOIN 해서 `name` 과 `deleted_at` 만 본다.** `application_configs` 에는 서비스 이름이 없고 `application_id` 만 있어서, 스팬의 `service.name` 과 맞추려면 이름이 필요하다. `deleted_at` 이 채워진 줄은 감시 대상이 아니므로 비율에 넣지 않는다 | 그대로. 수집기가 읽는 두 번째 표가 됐다 |
| `agents` · `users` · `refresh_tokens` | API 서버 · 적재 처리기 | **안 바뀐다.** 이 이슈가 보지 않는다 | 그대로 |
| ClickHouse 11표 전부 | 적재 처리기 · MV | **구조는 안 바뀐다.** 다만 비율이 바뀌면 들어오는 **양**이 달라진다. 로컬은 seed 가 `1.0000` 이라 전부 들어오고, 운영은 등록 때 API 서버가 `0.0100` 으로 만든다 | 그대로 |

`sampling_rate` 를 쓰는 길은 이렇게 생겼다. 쓰는 쪽과 읽는 쪽이 완전히 갈린다.

```
사람  ──▶ 화면 ──▶ API 서버 PUT /applications/{uuid}/config ──▶ PG application_configs
                     (expected_version 낙관적 락)                        │
                                                       30초마다 읽기 (이 선이 새로 생겼다)
                                                                        ▼
에이전트 100% ──▶ 수집기 ──▶ 헬스체크 거르기 ──▶ 샘플링 ──▶ Kafka raw ──▶ 적재 ──▶ CH
```

## 왜 이 자리에서 고쳤나

**버리는 자리가 수집기여서다.** ADR `#33` 이 샘플링을 수집기에서 하기로 정했고(에이전트는 `always_on` 으로 100% 보낸다), 버려진 스팬은 Kafka 에 들어가기 전에 사라지므로 뒤에서 고칠 수 있는 것이 아무것도 없다. 비율을 바꿀 수 있게 하려면 **비율을 읽는 코드가 수집기 안에 있어야** 한다.

그 비율을 API 서버에 물어보지 않고 PG 를 직접 읽는 이유는 ADR `#37` Q23 이 이미 기각한 것이다 : **API 서버 장애가 수집기 샘플링까지 번진다.** 수집 경로 가용성 목표는 99.9%, 조회는 99.5% 다. 수집이 조회에 매달리면 더 낮은 쪽으로 끌려 내려간다.

### 왜 Entity 를 안 만들었나

두 이유가 겹친다.

- **표 주인 규칙**(ADR `#36`) : `application_configs` 의 주인은 API 서버다. 수집기에 Entity 를 두면 "이 표를 누가 바꾸나" 가 흐려지고, 스키마가 두 모듈에 중복 선언된다. 적재 처리기의 `PostgresServiceCatalog` 가 같은 이유로 `applications` 를 `JdbcTemplate` 으로 읽는다. 그 파일 짝인 `ServiceCatalog.kt` 주석에 미리 적혀 있다 : "표 주인은 API 서버지만 읽기만 한다 : 수집기가 샘플링 비율을 읽는 것과 같은 성격 (ADR #20)"
- **`ddl-auto: validate`** : 수집기 `application.yml:7` 이 `validate` 다. 표는 `db/postgres/` 마이그레이션이 만들고 코드는 맞는지만 확인한다(ADR `#49`). Entity 를 만들면 그 검증 대상이 늘어난다 : 컬럼 이름 · 타입 · nullable 이 DDL 과 한 칸이라도 어긋나면 **수집기가 기동하지 않는다.** 읽기만 하는 표 때문에 수집 경로 가용성을 거는 일이 된다

그래서 어댑터는 `JdbcTemplate.queryForList` 로 SQL 한 줄을 친다. RowMapper 도 두지 않는다 : 줄이 몇 개 안 되고 칸이 둘뿐이다.

### 왜 OTLP 요청 경로에서 읽지 않나

적재 처리기 `PostgresServiceCatalog` 는 **물어볼 때** 낡았으면 그 자리에서 읽는다. 그 모양을 그대로 베끼면 PG 가 죽었을 때 그 요청이 커넥션 풀 대기만큼 멈춘다. 로컬 실측으로 **OTLP 요청 하나가 10초** 걸렸다.

적재 처리기는 Kafka 컨슈머라 그 대기를 견디지만 수집기는 **에이전트가 응답을 기다리고 있다.** 수집 경로 가용성 목표도 조회보다 높다(99.9% 대 99.5%). 그래서 읽기를 주기 작업으로 빼고 스냅샷만 갈아끼우게 했다. `rates()` 는 들고 있는 값을 바로 준다. PG 가 죽으면 비율이 낡기만 하고 수집은 한 번도 멈추지 않는다. `PostgresSamplingRateSourceTest` 의 "PG 를 치지 않는다 : 읽기는 주기 작업이 하고 요청 경로는 들고 있는 값만 준다" 가 이것을 고정한다.

### 왜 스냅샷이 불변인가

읽기와 쓰기 횟수가 극단적으로 다르다. 읽기는 스팬마다(초당 수천 번), 쓰기는 30초에 한 번이다. 잠금을 걸면 그 수천 번마다 비용을 내고 갱신하는 동안 읽기가 멈춘다. `AtomicReference` 로 참조 하나를 통째로 갈아끼우면 읽는 쪽은 잠금 없이 "옛 벌" 또는 "새 벌" 중 하나를 보는데 둘 다 완성된 값이다.

**조건이 하나 붙는다 : 스냅샷 안이 불변이어야 한다.** 비율은 서비스별 `Map` 이 들어가므로, 그 `Map` 을 나중에 꺼내 고치면 반쯤 바뀐 중간 상태가 보인다. 그래서 `SamplingRates.of` 가 넘겨받은 `Map` 을 복사해 둔다. `SamplingRatesTest` 의 "넘긴 Map 을 나중에 고치면" 이 그것을 고정한다.

## 이 표들이 고장 나면

| 가정 | 무슨 일이 생기나 | 막아 둔 것 |
|---|---|---|
| 표가 아예 없다 (마이그레이션 전에 수집기가 뜬다) | `SELECT` 가 터지고 **첫 조회 실패**가 된다. 들고 있는 값이 없으므로 yml 기본값 `0.01` 로 돈다. 수집기는 뜨고 수집은 돈다 | 기동 실패로 만들지 않았다(ADR `#53` 기각 ⑨) : 수집 경로 가용성 99.9% 를 PG 에 묶지 않는다. 갱신 실패 로그가 TTL 마다 뜬다 |
| 줄이 없다 (서비스가 `applications` 에만 있고 설정 줄이 없다) | 그 서비스만 `byService` 에서 빠진다. 최댓값 계산에 안 들어가고, 그 서비스 스팬은 **미등록으로 세어진다**(`unknown_service` 카운터). 버리지는 않는다 | 평소에는 API 서버가 서비스 등록 때 설정 줄을 같이 만들고(`ApplicationConfig.DEFAULT_SAMPLING_RATE = 0.0100`) seed 도 같이 넣는다. 한 줄도 없으면 기본값으로 떨어진다 |
| 줄이 **한 줄도** 없다 (표는 있는데 빈 표다) | `byService` 가 비어 적용값이 yml 기본값이 된다. `0`(전부 버림)도 `1`(전부 통과)도 되지 않는다 | `SamplingRates.of` 가 `maxOrNull() ?: fallback` 으로 떨어진다. `SamplingRatesTest` 의 "한 줄도 못 읽었으면" 이 고정한다. 이때 미등록 카운터는 **안 올라간다** : 목록이 비면 전부 미등록으로 세는 것이 뜻이 없다 |
| 비율이 범위를 벗어난다 (`1.5` · `-0.1`) | **들어가지 않는다.** DDL 의 `CHECK (sampling_rate >= 0 AND sampling_rate <= 1)` 이 막고, `NUMERIC(5,4)` 라 소수 넷째 자리까지다. API 서버도 `setScale(4, HALF_UP)` 으로 맞춰 둔다 | DB 가 막으므로 수집기에 방어 코드를 두지 않았다. 혹시 `1.0` 이 들어오면 전부 통과인데 그것은 로컬 seed 가 쓰는 정상 값이다 |
| `deleted_at` 이 채워진다 (서비스를 지운다) | **비율에서 빠진다.** `WHERE a.deleted_at IS NULL` 이 걸러낸다. 그 서비스가 최댓값을 들고 있었으면 적용 비율이 내려가고, 그 서비스가 아직 스팬을 보내면 미등록으로 세어진다 | `application_configs` 줄은 FK 때문에 남아 있어도 JOIN 조건이 거른다. 적재 처리기 `PostgresServiceCatalog` 와 같은 조건이다 |
| 한 사람이 바꾼 값이 다른 사람 값에 묻힌다 | **API 서버가 막는다.** `PUT /applications/{uuid}/config` 가 `expected_version` 을 받아 다르면 `409 CONFIG_VERSION_CONFLICT` 로 거절한다 | 수집기는 이 길에 끼지 않는다. `version` 을 안 보고 통째로 다시 읽는다 |
| PG 가 죽는다 (읽다가 실패) | **마지막으로 읽은 값을 그대로 쓴다.** 스팬 하나하나가 PG 를 치지 않으므로 갱신이 실패해도 들고 있던 값으로 돌다가 다음 주기에 다시 시도한다 | `PostgresSamplingRateSourceTest` 의 "읽다가 PG 가 죽으면" 이 고정한다. "실패 시 마지막 값 유지" 는 Jaeger · OTel · Elastic · SkyWalking 전원 공통 동작이다 |
| 로컬에서 `down -v` 없이 파일만 받는다 | **옛 seed 의 `0.0100`** 이 PG 에 남는다. `ON CONFLICT DO NOTHING` 이라 새 seed 가 덮어쓰지 않는다. 손님 100명을 넣어도 점이 한 개다 | PR 본문 · `AGENTS.md` 에 안내. `SELECT a.name, c.sampling_rate FROM application_configs c JOIN applications a ON a.id = c.application_id` 로 확인하고 `down -v` → `up -d --wait` → `./scripts/seed-clickhouse.sh` 로 고친다 |

## 다른 파트에 알릴 것

- **조회(화면)** : `application_configs.sampling_rate` 의 뜻이 바뀌었다. 입력칸이 서비스별로 네 개인데 **실제로 듣는 값은 가장 큰 하나**다. 코드는 안 고쳐도 되지만 **화면에 한 줄 설명이 필요하다.** 없으면 "order 만 내렸는데 안 듣는다" 는 문의가 온다. 그리고 바꾼 값은 최대 30초 뒤에 반영된다
- **API 서버(인증 · 설정)** : 표도 API 도 그대로다. 수집기가 읽기만 하므로 `PUT /applications/{uuid}/config` 와 `expected_version` 낙관적 락은 손댈 것이 없다. 서비스 등록 때 설정 줄을 같이 만드는 동작이 **수집기의 전제**가 됐다 : 줄이 없으면 그 서비스가 미등록으로 세어진다
- **전원** : 파일을 받으면 `docker compose down -v` → `docker compose up -d --wait` → `./scripts/seed-clickhouse.sh`. **seed 의 비율이 `0.0100` 에서 `1.0000` 으로 바뀌었고** `ON CONFLICT DO NOTHING` 이라 볼륨을 지우지 않으면 옛 값이 그대로 남는다
- **노션 「ERD」 정본** : `application_configs` 네 곳을 레포 사본([`../../design/web-v2/erd.md`](../../design/web-v2/erd.md))과 같게 고친다. 표 설명(수집기가 읽는 방법) · `sampling_rate`(최댓값 하나) · `version`(수집기는 안 본다) · `updated_at`(수집기는 안 본다)
- **배포** : 수집기에 새 의존성이 0 이고 PG 연결도 이미 있다(`bundles.postgres` · datasource · `POSTGRES_HOST`). 다만 **수집기가 PG 에 닿아야 한다**는 전제가 새로 생겼다. 닿지 않으면 죽지는 않고 yml `0.01` 로 돈다
