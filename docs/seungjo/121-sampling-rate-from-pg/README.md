# `#121` 샘플링 비율의 정본을 PG 로 옮긴다 : 서비스별 네 줄 중 최댓값 하나만 쓴다

> **이 작업의 기술 설계 문서 한 장.** 문제 → 선택지 → 결정 → 장애가 나면 순서로 읽으면 끝난다.
> 결정 정본은 [`../../design/01-decisions.md`](../../design/01-decisions.md) 의 ADR `#53` 이고, 여기는 그 한 건을 한 장으로 펼친 것이다.
> ADR `#33` 이 "비율의 정본은 PG" 로 정하고 ADR `#37`(Q23)이 읽는 방법까지 정했는데 코드가 안 따라간 틈을 메운다.

- 2026-10-08 / 승조(`@SeungJo-02`) / 결정 ADR `#53` / 이슈 [`#121`](https://github.com/2026-techeer-project-team-b/monimo-backend/issues/121)
- 유저 플로우에서 어디: 에이전트 → 수집기 → **샘플링** → Kafka → 적재 처리기 → ClickHouse. 그 샘플링 단계가 쓰는 숫자를 어디서 가져오는지가 바뀐다. PG `application_configs` → 수집기 선 하나가 새로 생기고, 거르는 방식(`trace_id` 해시)과 카나리 예외는 그대로다

## 문제

**문제가 둘이고 성격이 다르다.** 하나는 "숫자를 바꿀 수 없다" 는 운영 문제이고, 하나는 "PG 에서 읽기 시작하면 단위가 어긋난다" 는 설계 문제다. 두 번째는 첫 번째를 고치려다 드러났다.

**문제 1 : 비율이 수집기 이미지 안에 있다.** 100건 중 몇 건을 남길지 정하는 숫자가 `application.yml` 에 있어서, 바꾸려면 파일을 고치고 빌드하고 다시 배포해야 한다. 운영 중에 트래픽이 늘어 비율을 내리고 싶으면 방법이 재배포뿐이다. 핵심기능 5("재배포 없이 샘플링률과 경보 임계값을 바꾼다")의 절반이 안 된 상태로 남는다.

**문제 2 : 거르는 단위와 설정 단위가 다르다.** 거르는 것은 트레이스(요청 하나)인데 `application_configs` 는 `application_id` 에 `UNIQUE` 가 걸려 서비스당 한 줄이다. 수집기는 스팬에 적힌 `service.name` 으로 줄을 찾으므로, 그 줄을 그대로 쓰면 비율이 서비스마다 달라진다. 같은 `trace_id` 의 해시가 한쪽 선 안 · 한쪽 선 밖이 되어 트레이스가 쪼개진다.

| 재 본 것 (2026-10-08, 로컬) | 값 |
|---|---|
| 로컬 PG `application_configs` 네 줄 | `shop-gateway` · `shop-inventory` · `shop-order` · `shop-payment` 전부 `0.0100` |
| 그 네 줄의 `version` | 전부 `1`. seed 가 넣은 뒤 **고친 사람이 없다** |
| 비율이 적힌 자리 | 세 군데 : `application.yml:31` `0.01` · `application-local.yml:19` `1.0` · `compose.yaml:179` `${COLLECTOR_SAMPLING_RATIO:-1.0}` |
| 그 세 자리의 성격 | 전부 이미지 또는 레포 파일. 운영에서 바꾸려면 배포가 필요하다 |
| 수집기가 `application_configs` 를 읽는 코드 | 0줄. PG 연결(`bundles.postgres` · datasource · `POSTGRES_HOST`)은 이미 있다 |
| 수집기의 JPA Entity | 0개 |

**무엇이 깨지나**: 문제 1 은 기능이 반쪽이라는 것이고, 문제 2 는 그대로 구현하면 **지금 멀쩡한 화면을 망가뜨린다**는 것이다. `gateway 1%` 에 `order 10%` 면 전체 요청의 9% 가 부모 없는 고아 스팬이 되는데, 에러가 나는 것이 아니라 경고 없이 틀린 숫자와 틀린 관계가 화면에 뜬다. 깨지는 셋은 전부 두 서비스의 기록을 맞춰 봐야 하는 화면이다 : 트레이스 상세는 `(누락된 구간)` 가짜 루트가 늘고(`#50`), 고객 요청 수는 `count(*)` 와 `count(is_root = 1)`(`#52`)의 비가 어긋나고, 서버맵은 간선이 부른 쪽 CLIENT 스팬 · 노드가 받은 쪽 SERVER 스팬이라 서로 다른 비율로 뽑혀 "들어온 것보다 처리한 것이 많은" 그림이 된다. 한 서비스 안에서 끝나는 화면(스캐터 · 히트맵 · URL 통계 · 서비스 건강)은 표본 밀도만 달라지고 틀리지 않는다.

## 선택지

**고르는 것은 "서비스별 네 줄을 어떻게 쓸까" 하나다.** 읽는 방법은 ADR `#37` Q23 이 이미 정해 뒀다.

| 방법 | 얻는 것 | 포기하는 것 | 왜 떨어졌나 |
|---|---|---|---|
| A. 루트 기준 : 그 서비스가 입구인 트레이스에만 적용 | 조각남이 구조적으로 0. 업계 일곱 중 여섯의 뜻 | 수집기가 스팬만 보고 그 트레이스의 입구를 모른다 | **우리 구조에서 F 가 된다.** 업계는 에이전트가 입구에서 판정해 `traceparent` 로 전파하니 공짜인데, ADR `#33` 이 에이전트를 `always_on` 으로 둬 전파가 없다 |
| B. 서비스별 독립 + 조각남 수용 | 가장 싸다. 서비스 이름만 넘기면 된다 | 고아 스팬이 상시로 생긴다 | **1% 대 10% 면 전체 요청의 9%.** `(누락된 구간)` 이 늘 떠서 유실 경고 신호가 죽는다. 트레이스 추적은 핵심기능 1번이다 |
| C. 단조성 강제 : `상류 >= 하류` 를 검증 | 고아 0 이면서 서비스별의 뜻이 산다. 꼬리만 짧아진다 | 호출 그래프를 코드가 알아야 한다 | **숫자 비 모순이 방향만 뒤집혀 남는다**(고객 요청 1,000 대 order 처리 100). 검증 코드가 API 서버 몫이고 표본 보정이 팀 미결이다. 되돌림 ① 로 미룬다 |
| **D. 최댓값 하나** | 선이 하나라 쪼개질 수 없다. 숫자 비도 맞는다. 표 · API · 화면 안 고친다 | 내릴 때 안 듣는다. 입력칸이 네 개인데 하나만 듣는다 | **채택** |
| E. 전역 설정 표 신설 | 뜻이 가장 정직하다. 샘플링은 애초에 트레이스 단위 = 전역 | 새 표 · 새 API · 화면 수정 | PG 에 전역 설정 표가 없고 주인이 API 서버라 두 파트가 움직인다. `sampling_rate` 를 빼면 `application_configs` 가 거의 빈 표가 된다. 전역 설정 표 자체가 결정거리라 별 이슈로 올린다 |
| F. 트레이스를 모아 루트 기준 판정(tail) | 뜻과 온전성을 둘 다 얻는다 | 수집기가 상태를 갖는다 | ADR `#33` 이 수집기 샘플링을 고른 이유가 "가볍다" 였다. OTel `tail_sampling` 은 같은 트레이스 스팬을 같은 인스턴스가 받으라고 요구해 2대부터 `trace_id` 로드밸런서가 필요하고, 기본값이 30초 대기 · 5만 트레이스 메모리다 |
| G. 샘플링을 에이전트로 되돌리기 | 업계 정석. 전파가 생겨 문제 2 자체가 사라진다 | ADR `#33` 되돌림 (b) 발동 | **조건이 아직 아니다.** (b)가 "수집기 CPU · 네트워크 한계를 넘는 것이 실측되면" 인데 그 실측이 없다. 틀린 방법이 아니라 차례가 아닌 방법이다 |

같이 정한 것 : **읽는 방법**(PG 직접 · 30초 캐시 · 실패 시 마지막 값) · **첫 조회 실패에 yml `0.01`** · **미등록 서비스는 기본값 + 카운터** · **환경변수는 기본값 자리로만** · **로컬 seed 를 `1.0000` 으로**. 각각의 기각 선택지와 사유는 [`decision.md`](decision.md) 의 4요소 표에 있다.

출처 링크 · 업계 일곱 제품 · 우리 데이터 대조 · AI 가 틀렸던 것은 [`research.md`](research.md).

## 결정

- **고른 것**: **D(최댓값 하나).** 수집기가 `application_configs.sampling_rate` 를 서비스별로 다 읽고 그중 가장 큰 값 하나를 모든 트레이스에 쓴다. 선이 하나면 입구를 몰라도 되고 한 트레이스가 전부 남거나 전부 버려진다. 읽는 방법은 ADR `#37` Q23 그대로 PG 직접 조회 · 30초 캐시 · 실패 시 마지막 값 유지이고, Entity 없이 `JdbcTemplate` 으로 SQL 한 줄을 치고 `AtomicReference` 스냅샷을 통째로 갈아끼우며, 갱신에 실패하면 들고 있던 값을 그대로 두고 TTL 은 설정으로 뺀다. 첫 조회부터 실패하면 `application.yml` 의 `0.01` 로 돈다. `applications` 에 없는 서비스도 기본값을 쓰고 그 스팬 수를 `monimo.collector.sampling.unknown_service` 카운터로 센다. `MONIMO_COLLECTOR_SAMPLING_RATIO` 는 기본값 자리로만 남아 PG 를 읽는 데 성공하면 PG 가 이긴다. 로컬 seed 의 비율은 `1.0000` 으로 바꿨다
- **버린 것과 이유**: B 는 고아가 상시로 생긴다(1% 대 10% 면 9%) · C 는 고아만 막고 숫자 비 모순은 남으며 검증이 API 서버 몫이다 · E 는 전역 설정 표가 없어 두 파트가 움직인다 · A 는 뜻은 맞지만 전파가 없어 우리 구조에서 F 가 된다 · F 는 수집기가 상태를 갖고 LB 가 필요하다 · G 는 ADR `#33` 되돌림 (b) 의 실측이 없다 · **첫 조회 실패에 `1.0`** 은 1% 를 전제로 잡은 Kafka 와 ClickHouse 가 100배를 받고, `0.0` 은 PG 가 늦게 뜨는 동안 신호가 조용히 사라지고, **기동 실패**는 수집 경로 가용성 99.9% 를 PG 에 묶는다 · **미등록 서비스를 버리는 것**은 지금 들어오던 것이 안 들어오는 동작 변경이고 막는 문이 수집기뿐이라 4317 평문 구멍과 함께 에이전트 mTLS 에서 한 번에 정한다 · **환경변수를 "PG 무시 스위치" 로 두는 것**은 운영에 켜 둔 것을 잊으면 화면 조작이 조용히 안 듣는다 · **로컬 프로필에서 PG 조회를 끄는 것**은 PG 읽는 코드가 로컬에서 안 돌고 CI 와 운영에서 처음 돈다
- **되돌리는 조건 넷**: ① 서비스별로 다른 비율을 쓸 **실제 요구가 나오면** C(단조성 강제)로 올라간다. D 가 C 의 특수 경우라 `max()` 를 떼고 검증을 붙이면 되고 버리는 코드가 0 이다. 그때 화면의 호출 수 보정을 같이 정해야 한다 ② 수집기 CPU · 네트워크 한계를 넘는 것이 **실측되면** ADR `#33` 되돌림 (b)를 발동해 샘플링을 에이전트로 옮긴다(G). 전파가 생겨 문제 2 자체가 사라진다 ③ **외부에 OTLP 문을 열 때** 미등록 서비스를 버리는 쪽으로 바꾼다. 에이전트 mTLS 와 함께 정한다 ④ **수집기를 2대 이상으로 늘릴 때** Datadog 식 2배 상한(비율 상향을 직전 값의 2배까지로 제한)을 넣는다

**뜻이 바뀐다** : `application_configs.sampling_rate` 는 서비스별로 받지만 서비스별로 적용되지 않는다. 화면 입력칸이 네 개인데 가장 큰 값 하나만 듣는다.

4요소 전문은 [`decision.md`](decision.md), 결정을 만든 프롬프트 원문은 [`research.md`](research.md) 8절 · [`prompts.md`](prompts.md) 와 ADR `#53` 안에 있다.

## 장애가 나면

| 무엇이 죽으면 · 틀리면 | 어떻게 되나 | 어떻게 알아채나 |
|---|---|---|
| PG 가 죽는다 (읽다가 실패) | **마지막으로 읽은 값을 그대로 쓴다.** 수집은 계속 돈다. 스팬 하나하나가 PG 를 치지 않으므로 갱신이 실패해도 들고 있던 값으로 돌다가 다음 주기에 다시 시도한다 | 수집기 로그에 `샘플링 비율 갱신 실패 : 비율 ... 유지`. 수집 자체는 `kept` · `dropped` 카운터가 계속 올라간다 |
| PG 를 한 번도 못 읽었다 (PG 가 수집기보다 늦게 뜬다) | `application.yml` 의 `0.01` 로 돈다. `1.0` 도 `0.0` 도 쓰지 않는다 : 전자는 Kafka · ClickHouse 가 100배를 받고 후자는 신호가 조용히 사라진다 | 갱신 실패 로그가 뜨는데 적용 비율이 `0.01`. `unknown_service` 카운터는 **안 올라간다**(서비스 목록이 비면 전부 미등록으로 세지 않는다) |
| 비율을 바꿨는데 안 듣는 것처럼 보인다 | 원인이 둘이다. ① 캐시 주기가 30초라 **최대 30초** 기다려야 한다 ② 쓰는 값이 네 줄의 최댓값이라 **한 서비스만 내려도 다른 줄이 높으면 그대로 높다** | 로그 `샘플링 비율 갱신 : 적용 {} (서비스별 {})` 가 서비스별 원본과 적용값을 같이 찍는다. 적용값이 네 줄의 `max()` 와 같으면 정상이다 |
| 로컬에 점이 안 보인다 | seed 가 넣는 비율을 읽는다. 머지 뒤 `down -v` 없이 파일만 받으면 **옛 seed 의 `0.0100`** 이 PG 에 남아 손님 100명을 넣어도 점이 한 개다 | `SELECT a.name, c.sampling_rate FROM application_configs c JOIN applications a ON a.id = c.application_id` 가 `1.0000` 이어야 한다. `down -v` → `up -d --wait` → `./scripts/seed-clickhouse.sh` 로 고친다 |
| 등록 안 된 서비스 신호가 들어온다 | **버리지 않는다.** 최댓값 하나를 쓰므로 판정에 영향이 없고 그 스팬 수만 센다. `telemetrygen` 같은 시험 도구가 계속 보인다 | `monimo.collector.sampling.unknown_service` 카운터가 올라간다. 올라가는데 짐작이 안 되면 4317 로 누가 보내고 있는지 봐야 한다 |
| 수집기를 여러 대로 늘렸다 | 사람이 비율을 바꾼 뒤 **최대 30초** 동안 한쪽은 새 비율 · 한쪽은 옛 비율로 돈다. 그 구간에 올리는 방향이면 고아 스팬이 생길 수 있다. 저절로 복구되고 **버그가 아니다** | `(누락된 구간)` 이 비율을 바꾼 직후에만 늘고 30초 뒤 멎는다. 없애려면 수집기끼리 설정을 맞추는 장치가 필요한데 Redis 를 폐기한 이유(ADR `#31`)와 같은 크기의 비용이다. 되돌림 ④ |

표별 영향은 [`tables.md`](tables.md). 파이프라인 전체 고장 표는 [`../../design/30-failure-modes.md`](../../design/30-failure-modes.md).

## 어떻게 확인했나

- **단위 테스트 `:collector:test` 62건 전부 통과** (실패 0 · 건너뜀 0). 새로 쓴 것이 `SamplingRatesTest` 6건 · `PostgresSamplingRateSourceTest` 9건이고 `TraceSamplerTest` 는 15건으로 늘었다

| 테스트 | 건수 | 무엇을 고정하나 |
|---|---|---|
| `SamplingRatesTest` | 6 | 서비스별 값이 다를 때 최댓값이 적용된다 · 원본 `byService` 가 남는다 · 한 줄도 못 읽으면 기본값으로 떨어진다 · 전부 `0.0` 이면 적용값도 `0.0`(기본값으로 떨어지지 않는다) · 넘긴 `Map` 을 나중에 고쳐도 스냅샷 안이 안 바뀐다 |
| `PostgresSamplingRateSourceTest` | 9 | 한 번도 안 읽었으면 yml 기본값으로 돌면서 **PG 를 치지 않는다** · 주기 작업이 한 번 돌면 최댓값이 적용되고 서비스별 원본도 남는다 · 그 뒤 비율을 백 번 물어도 PG 를 다시 치지 않는다 · 읽다가 터지면 마지막 값을 쓰고 PG 가 돌아오면 새 값으로 바뀐다 · 첫 차례부터 터지면 yml 기본값이고 등록된 서비스가 0 이다 |
| `TraceSamplerTest` | 15 (전 10) | 가짜 공급자로 갈아끼운 뒤 기존 단언 전부 유지 · **공급자가 주는 비율이 중간에 바뀌면 판정도 바뀐다** · 미등록 서비스 스팬이 비율대로 남으면서 카운터가 2건 센다 · 등록된 이름이면 카운터가 안 늘어난다 |

- **읽기를 OTLP 요청 경로에 두지 않았다.** 물어볼 때 낡았으면 그 자리에서 읽는 모양(적재 처리기 `PostgresServiceCatalog`)을 그대로 베끼면 PG 가 죽었을 때 그 요청이 커넥션 풀 대기만큼 멈춘다(로컬 실측 : OTLP 요청 하나가 10초). 적재 처리기는 Kafka 컨슈머라 견디지만 수집기는 에이전트가 응답을 기다리고 있고 수집 경로 가용성 목표가 조회보다 높다(99.9% 대 99.5%). 그래서 주기 작업이 스냅샷만 갈아끼우고 `rates()` 는 들고 있는 값을 바로 준다

- **일부러 깨뜨려 본 것** : `upperBound` 를 생성자에서 한 번 계산하면 PG 를 읽어도 판정이 안 바뀌는데 로그에는 새 비율이 찍혀 찾기 어렵다. 읽을 때 계산으로 옮기고 "공급자가 주는 비율이 중간에 바뀌면" 시험으로 고정했다. 그 시험은 생성자 계산으로 되돌리면 깨진다
- **실측** : 로컬 PG 네 줄이 전부 `0.0100` 이고 `version` 이 전부 `1`(seed 이후 변경 0) · 비율이 적힌 자리 셋 · 수집기의 JPA Entity 0개 · 새 의존성 0
- **관통 확인** (로컬 실데이터, 수집기를 `bootRun --args='--spring.profiles.active=local'` 로 띄우고)

| 확인 | 결과 |
|---|---|
| PG 를 읽는다 | 기동 **11ms** 뒤 `scheduling-1` 스레드에서 `적용 1.0 (서비스별 {shop-gateway=1.0, shop-inventory=1.0, shop-order=1.0, shop-payment=1.0})` |
| **최댓값이 쓰인다** | 네 줄을 `{0.01, 0.01, 0.1, 0.01}` 로 바꾸니 **적용 `0.1`** |
| 30초 뒤 반영된다 | `12:41:54` → `12:42:51` 두 번째 갱신 |
| PG 를 멈추면 | `샘플링 비율 갱신 실패 : 비율 1.0 유지 (Failed to obtain JDBC Connection)` 가 `scheduling-1` 에서 뜨고 **수집은 계속 돈다** |
| **PG 장애가 OTLP 요청을 막지 않는다** | PG 정상 `1,462ms` 대 PG 정지 뒤 `1,391ms` · `1,290ms`(`docker run` 포함). **차이 없다.** 요청 경로에서 읽던 때는 **12초**였다 |
| 미등록 서비스를 센다 | `telemetrygen`(등록 안 된 이름) 스팬 6건 → `unknown_service = 6.0`. 수집기는 안 죽고 스팬도 들어온다 |
| seed 가 바뀌었다 | `down -v` → `up -d --wait` → `seed-clickhouse.sh` 뒤 PG 네 줄이 `1.0000` |
- **업계값 대조** : 폴링 주기는 Jaeger · OTel `JaegerRemoteSampler` 60초 / Elastic APM Server 가 내려주는 `max-age` 기본 30초 / SkyWalking 20초 / Datadog Remote Configuration 5초. **우리 30초는 Elastic APM Server 기본값과 같다.** "실패 시 마지막 값 유지" 는 Jaeger · OTel · Elastic · SkyWalking 전원 공통이라 ADR `#37` Q23 은 표준을 따른 것이다. 첫 성공 전 기본값은 갈린다 : Jaeger · OTel 은 `0.001`(보수적), Datadog · SkyWalking 은 전부 통과. 우리는 뒤에 Kafka 와 ClickHouse 가 있어 앞쪽이다

## 결과물

- 새 파일 : `collector/.../sampling/SamplingRateSource.kt`(포트 `SamplingRateSource` 와 불변 스냅샷 `SamplingRates`. `of` 가 최댓값을 계산한다) · `collector/.../outbound/postgres/PostgresSamplingRateSource.kt`(어댑터) · `collector/src/test/.../sampling/SamplingRatesTest.kt` · `collector/src/test/.../outbound/postgres/PostgresSamplingRateSourceTest.kt`
- 수정 : `sampling/TraceSampler.kt`(포트에서 비율을 받고 `upperBound` 를 읽을 때 계산. 미등록 카운터 추가) · `sampling/SamplingProperties.kt`(`ratio` 가 기본값 자리로, `ttl` 추가) · `CollectorApplication.kt`(`@EnableScheduling`) · `collector/src/main/resources/application.yml` · `application-local.yml` · `compose.yaml` · `scripts/seed/postgres-applications.sql`(`0.0100` → `1.0000`) · `collector/src/test/.../sampling/TraceSamplerTest.kt` · `docs/design/01-decisions.md`(ADR `#53` 신설) · `docs/design/web-v2/erd.md`(`application_configs` 설명 · `sampling_rate` · `version` · `updated_at` 네 곳)
- 이 폴더 : `README` · `research` · `prompts` · `decision` · `tables`
- 표 구조 변경 0 : `application_configs` 를 읽기만 한다. 마이그레이션 파일 없음

## 읽는 순서

1. [`research.md`](research.md) 1 · 2절 : 왜 `trace_id` 해시인가 · 그 숫자가 지금 어디 있나 · PG 쪽은 이미 다 준비돼 있다 · 베낄 선례
2. [`research.md`](research.md) 5절 : 선택지 일곱 · 업계 일곱 제품 · 폴링 주기 대조 · AI 가 틀렸던 것
3. [`research.md`](research.md) 7절 : 왜 D 인가. 업계 방식이 우리에게 비싼 이유가 사슬로 적혀 있다
4. [`prompts.md`](prompts.md) : 조사 프롬프트와 결정 프롬프트 원문
5. [`decision.md`](decision.md) : ADR `#53` 4요소
6. [`tables.md`](tables.md) : `application_configs` 를 읽기만 한 이유와 Entity 를 안 만든 이유

## 이 이슈에서 배운 것 (세 줄)

- **ADR 문구를 구현하는 일이 설계를 다시 하는 일이 됐다.** "비율의 정본은 PG" 와 "설정 단위는 앱 단위만" 이 각각 맞는 결정인데, 둘을 겹치면 요청 하나가 쪼개진다. 설정 단위를 정할 때 "이 설정이 요청 경계를 넘나드나" 를 안 물었다. 로그 등급과 경보 임계값은 서비스 안에서 끝나고 샘플링만 넘는다
- **조사가 선택지를 새로 만들었다.** 우리 공식 `abs(hash) < ratio * Long.MAX_VALUE` 가 비율에 대해 단조라서 `상류 >= 하류` 면 고아가 구조적으로 0 이라는 것을 조사하고 나서 봤다. OTel 이 그것을 `consistent probability sampling` 으로 이미 문서화해 뒀다. 그 전까지는 "안전한 길은 최댓값뿐" 이라는 틀린 전제로 결정하려 했다
- **선례를 그대로 베끼면 안 되는 자리가 하나 있었다.** `PostgresServiceCatalog` 는 빈 집합으로 시작해도 "모르는 주소는 EXTERNAL" 이 틀려도 안전한 쪽이었지만, 샘플링은 빈 값이 `0`(전부 버림)이나 `1`(전부 통과)이 되어 **안전한 쪽이 없다.** 그래서 첫 조회 실패만 다르게 다뤘다
