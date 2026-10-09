# `#127` JDBC 응답이 없을 때 주기 작업이 영구히 멈추지 않게 한다

이슈 `#127` · ADR `#54` · 브랜치 `chore/127-jdbc-socket-timeout` · `#121` 리뷰에서 나온 후속

`#121` 이 수집기에 `@Scheduled` 로 PG 를 읽는 주기 작업을 넣으면서 생긴 구멍을 막는다.
가볍게 간다 : 이 폴더에 `research.md` · `prompts.md` · `tables.md` · `decision.md` 는 없다.

## 문제

`#121` 의 약속은 **"PG 가 죽어도 수집은 돌고 비율만 낡는다"** 였다. 그런데 "낡았다" 를 알 방법이 없다.

- **기다림에 한도가 없다.** pgjdbc `socketTimeout` 기본값이 `0`(무제한)이고 레포 전체에 설정이 0건이었다. 커넥션을 이미 쥔 뒤 쿼리 응답이 안 오면 그 자리에서 영원히 멈춘다
- **멈추면 다음 차례가 안 온다.** `fixedDelay` 는 이전 실행이 끝나야 다음을 잡는다. 스케줄러는 Boot 기본값 1스레드다
- **멈춘 것이 조용하다.** 예외가 안 나므로 `monimo.collector.sampling.refresh{outcome=failure}` 도 WARN 로그도 안 난다. "정상이라 조용한 것" 과 구분되지 않는다
- **수집기만의 일이 아니다.** `detector`(15초) · `notifier`(1초)도 `@Scheduled` + 1스레드 + 타임아웃 없음으로 모양이 같다

화면 쪽에서 보면 이렇다. 비율을 1% 에서 10% 로 올리고 저장하면 PG 에는 10% 가 들어가 **화면에는 제대로 보이는데** 수집기는 계속 1% 로 돈다. 화면과 실제가 다른데 화면만 보면 알 수가 없다.

## 알아야 했던 것

- **타임아웃이 세 겹이고 막는 자리가 다르다.** Hikari `connectionTimeout`(기본 30초)은 **풀에서 커넥션을 얻는** 구간, Hikari `validationTimeout`(기본 5초)은 **얻은 커넥션이 살아 있는지 확인하는** 구간, pgjdbc `socketTimeout` 은 **그 뒤 소켓에서 바이트를 기다리는** 구간이다. 한도가 없던 것은 세 번째 하나다
- **`socketTimeout` 단위는 초다.** 공식 문서 : "The timeout is specified in seconds max(2147484) and a value of zero means that it is disabled." Hikari 자신의 값들은 밀리초라 섞어 쓰기 쉽다. `10000` 으로 적으면 **2.8시간**이 되어 조용히 안 듣는다
- **`queryTimeout` 으로는 이 구멍이 안 막힌다.** pgjdbc 는 그 값을 **서버에 취소 요청을 보내** 지키는데, 네트워크가 막혀 있으면 취소 요청도 못 간다. `socketTimeout` 은 클라이언트 쪽 소켓 읽기 기한이라 서버가 닿든 안 닿든 터진다
- **URL 뒤에 `?socketTimeout=` 을 붙이는 흔한 방법은 우리에게 안 통한다.** `spring.datasource.url` 이 `application-local.yml` 에만 있고 운영은 env 로 받는다. 로컬만 바뀌고 운영은 그대로다
- **카운터와 게이지는 성격이 다르다.** 카운터는 코드가 끝까지 돌아야 올라간다. 게이지는 **읽는 순간 계산**되므로 주기 작업 스레드가 막혀 있어도 값이 커진다
- **`ingester` 의 수동 스케줄러가 시한폭탄이었다.** `RawErrorHandlerConfig` 가 Kafka pause 해제용으로 `ThreadPoolTaskScheduler`(1스레드)를 직접 만들어 둔다. 그 모듈에 `@Scheduled` 가 하나라도 들어오면 Boot 의 `taskScheduler` 가 `@ConditionalOnMissingBean` 으로 물러나서 **모든 주기 작업이 그 1스레드 풀에서 돌고 `spring.task.scheduling.*` 이 조용히 무시된다.** 그 파일 주석이 처방까지 적어 둔 상태였다

## 결정

- **고른 것** : `socketTimeout` **10초**를 PG 를 치는 **다섯 모듈 전부**(`collector` · `ingester` · `detector` · `notifier` · `api-server`)의 `application.yml` `spring.datasource.hikari.data-source-properties` 에 넣는다. 수집기에 **"마지막 성공 이후 몇 초"** 게이지 `monimo.collector.sampling.refresh.age` 를 더한다. 주기 작업이 있는 세 모듈에 `spring.task.scheduling.pool.size: 2` 를 둔다. `ingester` 의 수동 스케줄러 빈에 `@Bean(defaultCandidate = false)` 를 붙이고 쓰는 쪽에 `@Qualifier` 를 붙인다
- **버린 것과 이유** : **URL 파라미터**는 운영에서 안 걸린다(url 이 env) · **서버 쪽 `statement_timeout`** 은 서버가 끊어도 그 통보가 못 오므로 막히는 경로 자체를 안 막는다 · **주기 작업 전용 DataSource 를 따로** 두는 것은 수집기가 PG 를 치는 곳이 여기뿐이라 얻는 것이 없는데 풀이 둘이 된다 · **`queryTimeout`** 은 취소 요청이 왕복을 필요로 해서 블랙홀을 못 막는다 · **범위를 내 모듈 둘로** 줄이는 것은 `detector` · `notifier` 에 같은 구멍을 남기는데 모듈당 한 줄이라 줄여서 아끼는 것이 없다 · **값 30초**는 수집기 주기(30초) 한 바퀴를 통째로 먹고 **5초**는 로컬에서 PG 가 느릴 때 오탐이 난다
- **임계값은 정하지 않았다.** 게이지만 내놓는다. "몇 초를 넘으면 경보" 는 경보 규칙이라 알림 파트 몫이다
- **되돌리는 조건** : ① 10초 안에 안 끝나는 정상 쿼리가 생기면 그 모듈만 값을 올리거나 그 쿼리 전용 DataSource 로 뺀다 ② 한 모듈에 주기 작업이 셋 이상이 되면 풀 크기를 다시 본다 ③ `api-server` 의 ClickHouse DataSource 에도 같은 한도가 필요해지면 그때 드라이버별 이름으로 넣는다(아래 「안 한 것」) ④ 게이지를 보는 사람이 없으면(알림 파트가 규칙을 안 걸면) 지표를 늘린 값어치가 없으므로 경보 규칙을 이 파트가 가져온다

## 어떻게 확인했나

### `socketTimeout` 이 실제로 막는 것

커넥션은 멀쩡한데 쿼리 응답이 안 오는 구간을 `pg_sleep` 으로 흉내 내고 같은 드라이버(42.7.11)로 두 번 쟀다.

```
socketTimeout 없음(기본 0 = 무제한)   pg_sleep(15) 가 15.1초에 정상 반환
socketTimeout 10                    pg_sleep(30) 가 10.0초에 끊김
                                    An I/O error occurred while sending to the backend.
```

없을 때는 끝까지 기다리고, 있을 때는 한도에서 끊는다.

### 적용값이 실제로 드라이버까지 가나

수집기를 로컬로 띄워 Hikari 설정 로그를 봤다. `/actuator/configprops` 는 키만 보여 주고 값은 가린다(`socketTimeout: ******`).

```
① yml 값만                          dataSourceProperties...{password=<masked>, socketTimeout=10}
② 환경변수 ..._SOCKETTIMEOUT=7        dataSourceProperties...{password=<masked>, socketTimeout=7}
③ 커맨드라인 ...socketTimeout=7       dataSourceProperties...{password=<masked>, socketTimeout=7}
```

환경변수 이름은 `SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_SOCKETTIMEOUT` 이다. **Map 키가 camelCase 인데 환경변수는 대문자라 깨질까 걱정했는데 걱정이 틀렸다.** 다만 이것은 `yml` 이 그 키를 먼저 선언해 둔 상태에서 값만 덮어쓴 경우다. yml 줄을 지우고 환경변수만으로 넣는 경우는 재지 않았다.

`api-server` 는 `api-server/.../common/config/DataSourceConfig.kt` 가 `@ConfigurationProperties("spring.datasource.hikari")` 로 `HikariDataSource` 에 직접 바인딩하는데, 같은 접두 아래라 `data-source-properties` 가 그대로 먹는다.

### 게이지가 "조용한 멈춤" 을 보이게 하나

수집기를 `ttl=10s` 로 띄우고 `docker pause` 로 PG 를 멈춘 뒤 5초마다 쟀다.

```
정상       age=0.63   success=1.0  failure=0.0
  +5s     age=5.82   success=1.0  failure=0.0
  +15s    age=15.91  success=1.0  failure=0.0
  +25s    age=26.03  success=1.0  failure=0.0
  +35s    age=36.15  success=1.0  failure=0.0
  +40s    age=41.20  success=1.0  failure=1.0
unpause   age=6.17   success=2.0  failure=1.0
```

**35초 동안 카운터 둘은 꼼짝도 안 했고 게이지만 올라갔다.** 이것이 `#121` 의 카운터로는 안 되는 이유를 그대로 보여 주는 숫자다. PG 가 돌아오자 게이지가 0 쪽으로 떨어지고 성공이 2 가 됐다.

### 바로잡힌 것 : `docker pause` 는 이 구멍의 재현 수단이 아니었다

이슈 본문에 재현 조건으로 `docker pause` 를 적었는데 **틀렸다.** 위 측정에서 실패가 40초 동안 **1건**이고 메시지가 `Failed to obtain JDBC Connection` 이다. 그것은 Hikari 가 커넥션을 얻지 못한 것이고 실측 34초는 `connectionTimeout` 기본값 30초다. 즉 `docker pause` 는 이 변경이 없어도 영구히 멈추지 않는다.

정확히 말하면 이렇다.

| 경로 | 전부터 한도가 있었나 |
|---|---|
| 커넥션을 못 얻는다 (PG 정지 · `docker pause`) | 있다. `connectionTimeout` 30초 |
| 얻은 커넥션이 죽어 있다 | 있다. `validationTimeout` 5초 |
| **확인을 통과한 커넥션으로 보낸 쿼리의 응답이 없다** | **없었다.** 이 변경이 막는 자리 |

`socketTimeout` 의 값어치는 줄지 않는다. 세 번째 경로는 잠금 대기 · 커넥션 체크아웃 이후의 네트워크 단절 · PG 가 쿼리 중 멈춤에서 실제로 생기고, 거기서는 스레드가 영구히 블록된다. 다만 **"`docker pause` 하면 영구히 멈춘다" 는 이슈의 문장은 사실이 아니었다.**

### 회귀

- `:collector:test` **92건** 통과(실패 0 · 건너뜀 0). 전 85건에서 **+7** 이고 전부 `PostgresSamplingRateSourceTest` 의 게이지 시험이다(13 → 20)
- `:ingester:test` **113건** 통과. `@Bean(defaultCandidate = false)` 와 `@Qualifier` 가 pause 동작을 깨지 않았다
- 다섯 모듈 전부 기동 정상. 타임아웃 값이 기동을 깨지 않는다

## 안 한 것

- **`api-server` 의 ClickHouse DataSource** 는 그대로 뒀다. 드라이버가 달라 속성 이름도 다르고(`socket_timeout`, 단위도 다르다) 그쪽은 주기 작업이 아니라 요청 경로다. 쿼리가 멈추면 요청 스레드 하나가 묶이고 나머지는 계속 돈다. 조회 파트에 알린다
- **`queryTimeout`** 과 **서버 쪽 `statement_timeout`** 은 넣지 않았다(사유는 「결정」)
- **경보 규칙** 은 넣지 않았다. 게이지만 내놓는다
- **`detector` · `notifier` 에 게이지**를 넣지 않았다. 그 모듈의 주기 작업은 각 파트 코드라 우리가 손대면 동작 변경이 된다. 타임아웃 한 줄만 넣고 같은 게이지가 필요하다는 사실을 PR 에 적는다
- **`@Bean(defaultCandidate = false)` 전용 시험**을 쓰지 않았다. 그것이 막는 상황은 "`ingester` 에 `@Scheduled` 가 들어왔을 때" 인데 지금 그 조건이 없어서, 시험을 쓰려면 시험용 `@Scheduled` 를 모듈에 넣어야 한다. 지금 보장되는 것은 `IngesterApplicationTest`(`@SpringBootTest`)가 컨텍스트를 통째로 띄워 `@Qualifier` 주입이 깨지지 않는 것까지다

## 바꾼 파일

- `collector` · `ingester` · `detector` · `notifier` · `api-server` 의 `application.yml` : `socketTimeout: 10`
- `collector` · `detector` · `notifier` 의 `application.yml` : `spring.task.scheduling.pool.size: 2`
- `collector/.../outbound/postgres/PostgresSamplingRateSource.kt` : `monimo.collector.sampling.refresh.age` 게이지. 기준 시각은 마지막 성공, 한 번도 성공하지 못했으면 기동 시각
- `ingester/.../inbound/kafka/RawErrorHandlerConfig.kt` : `@Bean(defaultCandidate = false)` + 쓰는 쪽 `@Qualifier`
- `collector/src/test/.../PostgresSamplingRateSourceTest.kt` : 게이지 시험 7건(`MockClock` 으로 시계를 돌린다)
- `docs/design/01-decisions.md` : ADR `#54`
- `AGENTS.md` : §5 `#127`, §6 ClickHouse DataSource 행
