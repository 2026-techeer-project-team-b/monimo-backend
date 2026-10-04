# 리서치 : 적재 실패를 어떻게 다루나 (재시도 · 멈춤 · DLQ)

- 날짜 2026-10-04 ~ 10-05 / 걸린 시간 약 1시간 / 관련 이슈 [`#96`](https://github.com/2026-techeer-project-team-b/monimo-backend/issues/96)
- 쓴 도구: 조사 서브에이전트 **2개 동시**(+ 잘린 답 재요청 2회), 로컬 재현(ClickHouse 정지), 라이브러리 jar 직접 확인(`javap` · `unzip` · 바이트코드)

> **읽는 순서가 곧 쓰는 순서다.** 1 → 2 를 쓰고 멈춰서 승조가 3 을 묻는다. 답하고 4 를 쓴다.
> 5 를 쓰고 멈춰서 승조가 6 을 묻는다. 답하고 7 을 쓴다. 8 은 승조가 쓴다.
> **3 · 6 · 8 은 사람 차례다.** 비어 있으면 아직 거기까지 온 것이다.

---

## 1. 이번 작업은 무엇을 하는 일인가

- **지금 무엇이 잘못됐나** : ClickHouse(신호를 모아 두는 저장소)가 잠깐 죽어 있는 동안 들어온 데이터를 **적재 처리기가 조용히 버린다.** 저장소를 되살려도 돌아오지 않는다.
- **그래서 무엇을 만드나** : 실패를 두 종류로 갈라서, **고쳐질 실패는 될 때까지 기다리고**, 고쳐지지 않을 실패만 따로 치워 둔다.
- **안 하면 어떻게 되나** : 저장소가 1분 죽으면 그 1분의 트레이스 · 메트릭 · 로그가 영구 유실된다. 모니터링 도구가 자기 데이터를 잃는 것이라 더 나쁘다.

| | |
|---|---|
| 건드리는 모듈 · 파일 | `ingester/` : `application.yml`(에러 핸들러 설정) · `inbound/kafka/`(에러 핸들러 빈 · DLQ 발행) · 카운터 · **테스트**(독성 메시지는 DLQ 로, 일시 장애는 유실 없이 복구) |
| 같은 PR 에 들어가는 문서 | ADR 신설 · `AGENTS.md` §5 · §6 · [`30-failure-modes.md`](../../design/30-failure-modes.md) ⑤ 갱신 · 이 폴더 4개 파일 |
| 건드리지 않는 것 | `collector/`(수집기) · `db/`(표 구조) · `common/`(메시지 형식) · ClickHouse DDL |
| 이 이슈 범위 밖 (후속) | **DLQ 재처리 잡**(ADR `#34` 가 K8s Job 으로 정함) · ClickHouse 디스크 포화 재현 |

**조사 들어가기 전에 내가 알던 것 (세 줄)**

- `ingester/src/main/resources/application.yml` 에 **에러 핸들러 설정이 없어서** 스프링 기본값 `DefaultErrorHandler`(`FixedBackOff(interval=0, maxAttempts=9)`)가 적용된다. 10번 시도 후 그 레코드를 건너뛰고 오프셋을 넘긴다
- ADR `#34` 가 "CH 에 넣지 못한 메시지는 Kafka `raw.dlq`(30일)로 보내고, 재처리는 적재 처리기의 관리 잡" 으로 정해 뒀다. 토픽은 `compose.yaml` 이 이미 만든다(`#3` `#7`)
- ADR `#34` 의 **기각 사유**가 "DLQ 가 받는 것은 Kafka 장애가 아니라 **독성 메시지**" 라고 적는다. 그런데 내가 재현한 것은 **일시 장애**다

**알고 싶었던 것** (5절이 이 번호에 하나씩 답한다)

① Spring Kafka 에서 DLQ 로 보내는 표준 방법 ② 일시 장애와 독성 메시지를 **무엇으로** 구분하나 ③ 일시 장애에 멈추면 리밸런스가 나나 ④ ClickHouse 드라이버가 둘을 가려 주나 ⑤ 다른 파이프라인은 sink 장애 때 DLQ 로 보내나

## 2. 작업하기 전에 알아야 하는 것

### 2.1 Kafka · 토픽 · 오프셋

**Kafka** 는 메시지를 줄 세워 보관하는 중간 창고다. 보내는 쪽(수집기)과 받는 쪽(적재 처리기)이 서로를 기다리지 않게 해 준다.

**토픽** 은 그 창고 안의 **칸**이다. 우리는 두 칸을 쓴다.

| 토픽 | 무엇이 들어가나 | 보관 | 어디서 만드나 |
|---|---|---|---|
| `raw` | 수집기가 받은 OTLP 바이트 그대로 | **7일** | `compose.yaml` (파티션 3) |
| `raw.dlq` | 적재에 실패한 메시지 | **30일** | `compose.yaml` (파티션 1) |

**오프셋** 은 "내가 여기까지 읽었다" 는 책갈피다. 이게 이번 문제의 핵심이다.

```
raw 토픽 :  [264] [265] [266] [267] [268] ...
                          ↑
                      오프셋 = 266
```

적재 처리기가 266번을 성공적으로 넣으면 책갈피를 267로 옮긴다. **문제는 실패했을 때다.** 10번 시도해 보고 실패하면 **그냥 책갈피를 옮겨 버린다.**

왜 10번인가 : **우리가 정한 게 아니다.** `ingester/src/main/resources/application.yml` 에 에러 핸들러 설정이 **없어서** 스프링 기본값(`DefaultErrorHandler` = `FixedBackOff(간격 0초, 9회 재시도)`)이 그대로 적용된 것이다. 266번은 Kafka 창고에 7일 동안 남아 있지만, 책갈피가 이미 지나갔으니 **아무도 다시 읽지 않는다.** 이것이 "조용히 버린다" 의 정체다 : **Kafka 가 지운 게 아니라 우리가 안 읽는 것**이다.

> 로컬 compose 는 Kafka 데이터를 컨테이너 안에만 두므로 `docker compose down` 하면 메시지와 토픽이 함께 지워진다(`compose.yaml` 주석). 7일은 **설정값**이고, 로컬에서 그만큼 보관되는지는 컨테이너를 안 내린 동안만이다.

### 2.2 DLQ 가 무엇인가

**DLQ** = Dead Letter Queue, 우리말로 **"배달 못 한 편지함"** 이다. 우체국에서 주소가 틀려 배달할 수 없는 편지를 따로 모아 두는 칸과 같다.

핵심은 **무엇을 넣는 칸인가** 다.

| | 넣는다 | 안 넣는다 |
|---|---|---|
| **원래 뜻** | 편지 주소가 틀림 (**이 편지 자체의 문제**) | 집배원이 아픔 (**나중에 하면 되는 문제**) |
| **우리 경우** | 데이터가 깨져서 ClickHouse 가 받아 주지 않음 | ClickHouse 가 잠깐 죽음 |

**이 구분이 이번 작업의 전부다.** 둘을 같이 DLQ 로 보내면 안 되는 이유:

- **데이터가 깨진 경우**(독성 메시지) : 몇 번을 다시 넣어도 똑같이 실패한다. 계속 재시도하면 그 한 건에 막혀 **뒤에 줄 선 것이 전부 멈춘다.** 치워 두고 넘어가는 게 맞다
- **저장소가 잠깐 죽은 경우**(일시 장애) : 조금 뒤에 다시 넣으면 **성공한다.** 그런데 DLQ 로 보내면, 죽어 있는 동안 들어온 **모든 메시지가 DLQ 로 쏟아진다.** DLQ 가 원본 토픽의 복사본이 되고, 나중에 사람이 그 산을 다시 밀어 넣어야 한다

ADR `#34` 가 "파싱 실패 · CH 거절 · 스키마 위반을 `raw.dlq` 로" 라고 적은 것은 **앞쪽만** 말한 것이다. 기각 사유에도 "DLQ 가 받는 것은 Kafka 장애가 아니라 **독성 메시지**" 라고 분명히 써 있다. 그런데 내가 재현한 것은 **뒤쪽**이다. 그래서 ADR 을 글자대로 "실패하면 DLQ" 로 구현하면 설계 의도를 어기게 된다.

### 2.3 S3 로 가나, Kafka 안에서 관리하나

**Kafka 안에서 관리한다. S3 는 DLQ 가 아니다.**

이건 ADR `#34` 가 한 번 바꾼 결정이라 헷갈리기 쉽다. 처음(ADR `#21` ③ · `#30`)에는 실패분을 S3 에 두기로 했는데, `#34` 에서 **Kafka 토픽으로 옮기고 S3 의 역할을 바꿨다.**

| | 지금 결정 (ADR `#34`) | 왜 |
|---|---|---|
| **실패분** | **Kafka `raw.dlq` 토픽, 30일** | 재처리가 싸다. 컨슈머 오프셋만 되감으면 Kafka 클라이언트 하나로 끝난다. S3 는 객체를 읽어 재주입하는 코드 · IAM · 라이프사이클을 따로 짜야 한다 |
| **S3** | **ClickHouse 의 cold 계층** (오래된 데이터) | 실패분 보관이 아니다. **배포 단계에서** CH 가 `TTL ... TO VOLUME 's3'` 로 스스로 옮기게 한다. 우리 코드 0줄 |

**로컬에는 S3 가 아직 없다.** `db/clickhouse/002_create_raw_tables.sql` 은 지금 `TTL ... INTERVAL 93 DAY DELETE` 한 단만 두고, 같은 파일 주석이 "배포 단계에서 `TO VOLUME 's3'` + `DELETE` 2단으로 바꾼다" 고 적어 뒀다. 그래서 **이번 작업에서 S3 는 전혀 건드리지 않는다.**

즉 데이터는 이렇게 흐른다:

```
쇼핑몰 ──OTLP──▶ 수집기 ──▶ Kafka raw (7일) ──▶ 적재 처리기 ──▶ ClickHouse
                                                      │              │
                                            적재 실패 │              │ TTL 지나면
                                                      ▼              ▼
                                            Kafka raw.dlq (30일)   S3 (cold 계층)
                                                      │              = CH 가 스스로 옮김
                                            재처리 잡 │                (배포 단계. 로컬엔 없다)
                                                      ▼
                                                 다시 raw 로
```

**`raw.dlq` 는 이미 있다.** `compose.yaml` 이 만들고 있고(`#3` `#7`), 지금 비어 있다(`raw.dlq:0:0`). **넣는 코드가 없을 뿐이다.**

한 표로 묶으면 이렇다.

| 질문 | 답 |
|---|---|
| 실패한 데이터는 어디에 쌓이나 | Kafka `raw.dlq` 토픽 (**S3 아님**) |
| 얼마나 보관하나 | **30일** (`raw` 는 7일) |
| 누가 꺼내나 | 적재 처리기의 **관리 잡**(K8s Job). ADR `#34` 가 정했고 **아직 없다** : 이 이슈 범위 밖 |

### 2.4 client-v2 가 무엇인가

**ClickHouse 에 데이터를 넣는 자바 라이브러리**다. 정확히는 `com.clickhouse:client-v2` 버전 `0.10.0`.

| | |
|---|---|
| 왜 "v2" 인가 | 같은 회사가 만든 **구 버전(`clickhouse-jdbc`)과 다른 새 API** 다. JDBC(자바 표준 DB 접속 방식)가 아니라 ClickHouse 전용 HTTP 클라이언트다 |
| **적재(쓰기)** 에서 왜 JDBC 를 안 쓰나 | 대량 insert 가 목적이다. JSONEachRow 형식(한 줄에 JSON 하나)으로 수천 줄을 한 번에 밀어 넣는 데 v2 가 맞다 |
| 그럼 레포에 JDBC 는 없나 | **있다.** 조회 경로인 `api-server` 는 `clickhouse-jdbc` 를 쓴다(`api-server/build.gradle.kts` · 설정 키 `monimo.clickhouse.jdbc-url`). **쓰기는 client-v2, 읽기는 JDBC** 로 갈라져 있다. 이번 작업은 쓰기 쪽만 건드린다 |
| 어디에 있나 | `ingester/build.gradle.kts` → `libs.clickhouse.client.v2`, 설정은 `outbound/clickhouse/ClickHouseClientConfig.kt`, 넣는 코드는 `JsonEachRow.kt` |

**이 라이브러리가 이번 작업에 중요한 이유** : 실패를 **예외 타입으로 이미 갈라 준다.** 우리가 "이건 일시 장애, 이건 독성 메시지" 를 직접 판단하지 않아도 된다. 자세한 것은 5절.

### 2.5 자주 헷갈리는 것

| 헷갈리는 것 | 실제 |
|---|---|
| "유실" = Kafka 에서 메시지가 지워졌다 | **아니다.** Kafka 에는 7일간 남아 있다. **오프셋(책갈피)이 지나가서 아무도 안 읽는 것**이다 |
| DLQ 는 실패한 걸 전부 담는 칸 | **아니다.** "이 데이터 자체가 틀렸음" 전용이다. 일시 장애를 담으면 원본 복사본이 된다 |
| S3 가 DLQ | **아니다.** ADR `#34` 가 S3 를 cold 계층으로 바꿨다. DLQ 는 Kafka 토픽이다 |
| 재시도는 많이 할수록 안전 | **아니다.** 재시도 총시간이 `max.poll.interval.ms`(기본 5분)를 넘으면 Kafka 가 "이 컨슈머 죽었나" 보고 **리밸런스**(담당 재배정)를 일으킨다 |
| 적재 처리기가 멈추면 데이터가 샌다 | **반대다.** 멈추면 오프셋이 안 넘어가서 유실이 없다. 지금처럼 넘어가는 게 새는 것이다. 단 **`raw` 보관이 7일**이라 "유실 없음" 은 **7일 안에 되살렸을 때**만 참이다. 7일을 넘기면 Kafka 가 실제로 지운다 |

---

## 3. 1차 질문 (사람 차례)

> 승조가 2 를 읽고 모르는 것을 여기에 적는다. 답은 바로 아래에 붙인다.
> **비어 있으면 아직 안 물은 것이다.**

- **Q.**
  - A.

---

## 4. 1차 정리

> 3 의 질문이 끝난 뒤 채운다. 지금은 2 까지 읽은 상태에서의 요약이다.

**확실한 것**

- 유실은 Kafka 가 지워서가 아니라 **오프셋이 넘어가서** 생긴다. 메시지는 7일간 `raw` 에 남아 있다
- 실패는 두 종류다 : **고쳐질 것**(저장소가 잠깐 죽음)과 **안 고쳐질 것**(데이터가 깨짐). 전자를 DLQ 로 보내면 DLQ 가 원본 복사본이 된다
- `raw.dlq` 토픽(30일)은 이미 있고 넣는 코드만 없다. **S3 는 DLQ 가 아니라 ClickHouse 의 cold 계층**이다(ADR `#34`)
- 고치는 자리는 `ingester` 한 곳이다. 수집기 · 표 구조 · 메시지 형식은 안 건드린다

**아직 모르는 것** (5 에서 다룬다)

- 일시 장애일 때 **정확히 무엇을 할지** : 기다리나 · 멈추나 · 어떻게 멈추나
- 두 종류를 **코드에서 무엇으로 구분**하나
- 재시도를 몇 번 · 얼마 간격으로 하나

---

## 5. 선택지와 설명

### 조사 프롬프트

두 갈래로 나눠 서브에이전트 2개에 동시에 보냈다(A = Spring Kafka 쪽, B = ClickHouse 예외 · 업계 비교). 원문은 같은 폴더의 [`prompts.md`](prompts.md) 에.

### 5.1 무엇으로 구분하나 : client-v2 가 이미 갈라 놓았다

`HttpAPIClientHelper.wrapException()` 이 원인 예외를 타입으로 바꿔 준다. **우리가 판단 규칙을 만들 필요가 없다.**

| 예외 | 언제 나오나 | `isRetryable` | 어느 쪽인가 |
|---|---|---|---|
| `ConnectionInitiationException` | 서버에 닿지 못함 (`UnknownHostException` · `ConnectException` · 연결 타임아웃) | **생성자에서 항상 true** | 일시 장애 |
| `DataTransferException` | 전송 중 끊김 (`SocketTimeoutException` · `IOException`) | false | 일시 장애이지만 **중복 적재 위험** |
| `ServerException` | **서버가 응답했고 거절함** | 코드로 계산 | 코드가 갈라 준다 |
| `TransportException` | SSL 문제 | false | 설정 오류 |
| `ClientException` · `ClientMisconfigurationException` | 클라이언트 코드 · 설정 | false | 재시도 무의미 |

`ServerException.discoverIsRetryable()` 이 재시도 대상 코드를 이미 들고 있다 : `3` · `107` · `164` · `202` · `203` · `209` · `210` · `241` · `242` · `252` · `285` · `319` · `425` · `999` → `true`, **그 외 전부 false.** 권장은 `if (e.isRetryable()) 재시도 else DLQ` 이고 **이 목록을 다시 구현하지 말라**는 것이다.

JSONEachRow insert 에서 실제로 자주 보는 거절 코드 : `117` INCORRECT_DATA · `27` CANNOT_PARSE_INPUT_ASSERTION_FAILED · `53` TYPE_MISMATCH · `41` CANNOT_PARSE_DATETIME · `72` CANNOT_PARSE_NUMBER. 우리 `spans` 의 `events` Nested 배열 세 개(`events.ts` · `events.name` · `events.attributes`) 길이가 어긋나면 `27` 이 날 자리다.

출처 : [HttpAPIClientHelper](https://github.com/ClickHouse/clickhouse-java/blob/main/client-v2/src/main/java/com/clickhouse/client/api/internal/HttpAPIClientHelper.java) · [ServerException](https://github.com/ClickHouse/clickhouse-java/blob/main/client-v2/src/main/java/com/clickhouse/client/api/ServerException.java) · [ClickHouse ErrorCodes.cpp](https://github.com/ClickHouse/ClickHouse/blob/master/src/Common/ErrorCodes.cpp)

### 5.2 일시 장애일 때 무엇을 하나 : 여섯 선택지

| 방법 | 얻는 것 | 포기하는 것 | 구현 크기 |
|---|---|---|---|
| **A. 지금 그대로** `FixedBackOff(0, 9)` | 코드 0줄 | **데이터.** 4초 안에 10번 쓰고 건너뛴다 | 없음 |
| **B. 예외 분류만 + 전부 DLQ** | 소비가 절대 안 멈춘다 | 일시 장애 때 **DLQ 가 원본 복사본**이 된다. 사람이 나중에 밀어 넣어야 한다 | 빈 1개 |
| **C. 무한 BackOff** (`UNLIMITED_ATTEMPTS`) | 유실 없음 · 자동 복구 | **간격이 5분 넘으면 리밸런스.** 긴 간격을 못 준다 | 설정 한 줄 |
| **D. `ContainerPausingBackOffHandler`** | 유실 없음 · 자동 복구 · **리밸런스 없음**(pause 중에도 poll 계속) | 빈 2개(`ListenerContainerPauseService` 포함) | 빈 2개 |
| **E. `CommonContainerStoppingErrorHandler`** | 유실 없음 · 사고가 확실히 드러난다 | **자동 복구 안 됨.** 사람이나 파드 재시작이 필요하다 | 빈 1개 + 헬스체크 |
| **F. `@RetryableTopic`** (non-blocking) | 막히지 않는다 · 자동 복구 | **배치 리스너 미지원 · 파티션 내 순서 깨짐 · 일시 장애 때 트래픽 전량이 재시도 토픽으로 복제** | 애노테이션 + 토픽들 |

**결정을 가르는 축** (7절 「결정을 가르는 기준」 이 이 표에서 나온다)

| 방법 | 일시 장애 때 유실 | 리밸런스 | 자동 복구 |
|---|---|---|---|
| A. 지금 그대로 | **있다** | 없음 | 해당 없음 |
| B. 분류 + 전부 DLQ | 없다(단 DLQ 가 원본 복제) | 없음 | **아니오** (재처리 잡 필요) |
| C. 무한 BackOff | 없다 | **간격이 5분 넘으면 발생** | 예 |
| **D. `ContainerPausingBackOffHandler`** | 없다 | **없다** (pause 중에도 poll) | 예 |
| E. `CommonContainerStoppingErrorHandler` | 없다 | 정지(컨테이너가 멈춘다) | **아니오** |
| F. `@RetryableTopic` | 없다 | 없음 | 예 |

세 "유실 없다"(C · D · E)는 모두 **`raw` 보관 7일 안에 복구했을 때** 참이다. 7일을 넘기면 Kafka 가 지우므로 어느 방법도 못 막는다.

**A** 가 지금 상태다. 간격 0초 · 10회라 **4초면 포기한다.** 재배포 한 번에 ClickHouse 가 10초만 안 떠 있어도 그 사이 데이터가 전부 날아간다.

**B** 는 ADR `#34` 를 글자대로 읽은 구현이다. 소비가 안 멈추니 겉보기엔 건강하다. 그런데 ClickHouse 가 10분 죽으면 10분치가 DLQ 에 쌓이고, **DLQ 는 재처리 잡이 있어야 비는데 그 잡은 이 이슈 범위 밖**이다. 결국 사람이 손으로 되돌려야 한다.

**C** 는 가장 싸다(설정 한 줄). 문제는 **간격을 길게 줄 수 없다**는 것. 기본 `BackOffHandler` 는 리스너 스레드를 재우므로, 백오프 총시간이 `max.poll.interval.ms`(기본 5분)를 넘으면 Kafka 가 컨슈머를 죽은 것으로 보고 리밸런스를 일으킨다. 공식 문서가 "지연이 그 값을 넘을 때는 `ContainerPausingBackOffHandler` 를 쓰라" 고 명시한다.

**D** 가 세 조건(유실 없음 · 자동 복구 · 리밸런스 없음)을 **유일하게 다 만족**한다. pause 는 레코드를 안 받되 **poll 은 계속** 하므로 Kafka 가 컨슈머를 살아 있다고 본다. 대가는 빈 하나 더다.

**E** 는 "조용한 실패보다 시끄러운 정지가 낫다" 는 선택이다. Kafka Connect 가 하는 방식과 같다. 다만 **스스로 다시 시작하지 않는다.** Spring Boot 가 Kafka 컨슈머 헬스 인디케이터를 기본 제공하지 않으므로, 멈춘 상태를 `/actuator/health` 에 드러내려면 `KafkaListenerEndpointRegistry` 를 보는 지표를 직접 만들어야 한다. 안 만들면 **파드가 멈춰 있는데 아무도 모른다.**

**F** 는 이름만 보면 맞아 보이지만 우리에게 안 맞는다. 일시 장애 때 **트래픽 전량이 재시도 토픽으로 복제**되므로 B 와 같은 문제가 생기고, 게다가 파티션 내 순서가 깨진다.

출처 : [Handling Exceptions](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html) · [ContainerPausingBackOffHandler](https://docs.spring.io/spring-kafka/api/org/springframework/kafka/listener/ContainerPausingBackOffHandler.html) · [Pausing and Resuming](https://docs.spring.io/spring-kafka/reference/kafka/pause-resume.html) · [Non-Blocking Retries](https://docs.spring.io/spring-kafka/reference/retrytopic.html) · [CommonContainerStoppingErrorHandler](https://docs.spring.io/spring-kafka/api/org/springframework/kafka/listener/CommonContainerStoppingErrorHandler.html)

### 5.3 DLQ 로 보내는 방법 자체는 정해져 있다

`DefaultErrorHandler` + `DeadLetterPublishingRecoverer` 가 표준이다. DLQ 레코드에 원본 토픽 · 파티션 · 오프셋 · 예외 내용이 **헤더로 자동 추가**된다(`kafka_dlt-original-*` · `kafka_dlt-exception-*`).

출처 : [DeadLetterPublishingRecoverer](https://docs.spring.io/spring-kafka/api/org/springframework/kafka/listener/DeadLetterPublishingRecoverer.html) · [FailedRecordProcessor](https://docs.spring.io/spring-kafka/api/org/springframework/kafka/listener/FailedRecordProcessor.html)

### 5.4 우리 데이터로 확인한 것 (2026-10-04, 로컬 compose)

| 단계 | 결과 |
|---|---|
| 정지 전 `monimo.spans` | **226,417줄** |
| ClickHouse 정지 후 telemetrygen traces 2건(스팬 4개) 전송 → 되살리고 15초 | **226,417줄 (그대로)** |
| 대조군 : 되살린 뒤 같은 명령 | **226,421줄 (+4)** |
| Kafka 에 남아 있나 | **있다.** `raw:0:271` · 건너뛴 오프셋 `raw-0@266` · `@267` |
| `raw.dlq` 에 뭐가 들어갔나 | **`raw.dlq:0:0`** (토픽만 있고 넣는 코드가 없다) |

> 이슈 `#96` 본문의 로그는 `raw-0@264` 로 적혀 있다. **더 앞선 회차**다 : 처음 발견은 `30-failure-modes.md` 를 쓰다가 했고(그때는 20초 기다렸다), 위 표는 숫자를 정확히 재려고 다시 한 회차다(15초). 둘 다 같은 현상이다.

예외 사슬 전문:

```
ListenerExecutionFailedException: Listener method 'RawConsumer.onMessage(...)' threw exception
Caused by: com.clickhouse.client.api.ConnectionInitiationException:
           Insert request failed (attempt: 1, duration: 0ms, queryId: null)
    at Client.insert(Client.java:1488)
    at JsonEachRowKt.insertJsonEachRow(JsonEachRow.kt:18)
    at ClickHouseSpanStore.save(ClickHouseSpanStore.kt:17)
    at RawConsumer.onMessage(RawConsumer.kt:59)
Caused by: java.net.UnknownHostException: clickhouse
```

`ConnectionInitiationException` 이 `CompletableFuture.get()` 의 `ExecutionException` 에 **싸이지 않고 그대로** 올라온다. client-v2 가 `Client.insert` 안에서 풀어 준다. **예외 타입 하나로 분류를 걸 수 있다는 뜻**이라 설계가 쉬워진다.

재시도 10번이 **4초 안에 끝난다**(간격 0초). `Host 'clickhouse:8123' unknown` WARN 10줄 + `Backoff ... exhausted` 1줄. 예외 메시지의 `attempt: 1` 은 **client-v2 자체 재시도가 돌지 않았다**는 뜻이다.

### 5.5 남이 어떻게 하나 : 다섯 중 하나도 sink 장애를 DLQ 로 보내지 않는다

| 도구 | sink 장애 기본 동작 | DLQ 로 보내나 | 멈추나 · 버퍼링하나 | 출처 |
|---|---|---|---|---|
| **Kafka Connect** | `RetriableException` 이면 `errors.retry.timeout` 까지 재시도, 소진 시 task FAILED | **아니다.** DLQ 는 converter · SMT 단계만 받고 sink `put()` 실패는 **구조적으로 제외** | 멈춘다(task 죽음). 오프셋 미커밋이라 재시작 시 재처리 | [KIP-298](https://cwiki.apache.org/confluence/spaces/KAFKA/pages/80453065/KIP-298+Error+Handling+in+Connect) · [Confluent](https://www.confluent.io/blog/kafka-connect-deep-dive-error-handling-dead-letter-queues/) |
| **OTel Collector** | `retry_on_failure` 기본 on, 5s → 30s 지수백오프, `max_elapsed_time=300s` 초과 시 배치 폐기 | DLQ 개념 없음 | 큐 버퍼링(기본 1000). 차면 폐기, `block_on_overflow` 로 블로킹 전환, `storage:` 로 디스크 영속 | [exporterhelper](https://github.com/open-telemetry/opentelemetry-collector/blob/main/exporter/exporterhelper/README.md) · [consumererror](https://pkg.go.dev/go.opentelemetry.io/collector/consumer/consumererror) |
| **SigNoz** (ClickHouse exporter) | OTel 과 동일, 큐 5000 | 없음 | 메모리 큐 초과 시 폐기. 중요 데이터는 `file_storage` 권장 | [clickhouselogsexporter](https://github.com/SigNoz/signoz-otel-collector/blob/main/exporter/clickhouselogsexporter/README.md) |
| **Debezium JDBC sink** | Connect 위. `max.retries` 후 task 종료 | Connect 와 같은 한계. **독성 레코드용으로만** DLQ 권장 | 멈춘다 | [Confluent](https://www.confluent.io/blog/kafka-connect-deep-dive-error-handling-dead-letter-queues/) |
| **Apache Flink** | 예외 → job 실패 → 최신 체크포인트에서 재시작 | 내장 DLQ 없음. side output 으로 직접 | 무한 재시작 + 백프레셔. 독성 메시지면 **재시작 루프** | [Task Failure Recovery](https://nightlies.apache.org/flink/flink-docs-stable/docs/ops/state/task_failure_recovery/) · [AWS](https://aws.amazon.com/blogs/big-data/error-handling-in-apache-flink-applications/) |

**공통 패턴 : 재시도 + 버퍼링 + 멈춤이고, DLQ 는 "이 데이터가 틀렸음" 전용 통로다.**

"일시 장애에 DLQ 를 권하지 않는다" 는 **명시 문구는 못 찾았다(미확인).** 대신 가장 센 1차 근거가 나왔다 : OTel OpenSearch exporter 가 connection refused · timeout · DNS 실패를 `consumererror.NewPermanent` 로 감싸 배치를 통째로 폐기한 것을 **"silent data loss" 로 신고받아 고쳤다** ([contrib #49208](https://github.com/open-telemetry/opentelemetry-collector-contrib/issues/49208) · [PR #49605](https://github.com/open-telemetry/opentelemetry-collector-contrib/pull/49605)). **우리가 지금 하고 있는 것이 정확히 그것이다.**

### 5.6 AI 가 틀렸거나 덜 맞았던 것 (남겨 둔다)

- **나(메인 대화)** 가 기본 DLQ 토픽 접미사를 `.DLT` 로 알고 있었다. 조사 A 가 `-dlt` 라고 했고 **jar 로 확인해 보니 A 가 맞았다**
- 조사 A 가 "기본 리졸버는 원본 파티션을 그대로 쓰므로 DLQ 파티션이 적으면 **실패한다**" 고 경고했다. 반은 맞지만 **`verifyPartition` 기본값이 `true` 라 실제로는 막아 준다.** 바이트코드(`iconst_1`)로 확인
- 조사 A 가 `FailedRecordProcessor.setMaxRecoveryFailures` 함정을 "반드시 의식해야 한다" 고 했는데 **우리 spring-kafka 3.3.16 에 없는 API** 였다. A 도 "미확인" 으로 표시해 둔 항목
- 조사 A 의 `ConsumerHealthIndicator` 코드는 **A 가 조합한 것**이고 출처 원문이 아니다. API 존재만 확인됐다

### 5.7 라이브러리 jar 를 직접 열어 확인한 것

조사 답을 그대로 믿지 않고 `javap` · `unzip` · 바이트코드로 본 것만.

| 확인 | 결과 |
|---|---|
| `ClickHouseException.isRetryable()` | **public.** `ServerException` 이 override |
| client-v2 자체 재시도 | `Client.Builder.retryOnFailures(ClientFaultCause...)` 존재. enum = `None` · `NoHttpResponse` · `ConnectTimeout` · `ConnectionRequestTimeout` · `SocketTimeout` · `ServerRetryable`. **우리 설정은 안 부른다.** DNS 실패는 이 목록에 없어서 `attempt: 1` 이었다 |
| 기본 DLQ 토픽 접미사 | **`-dlt`** (`.DLT` 가 아니다). 우리는 `raw.dlq` 라 리졸버를 직접 줘야 한다 |
| `verifyPartition` 기본값 | 바이트코드 `iconst_1` → **`true`.** 목적지 파티션이 모자라면 비워 준다. 우리 `raw`(3) → `raw.dlq`(1) 조합이 **기본값으로 안전하다** |
| `setMaxRecoveryFailures` | **spring-kafka 3.3.16 에 없다.** 조사 A 가 경고한 함정(상한을 넘기면 ERROR 로그만 남기고 **복구된 것으로 간주해 오프셋을 커밋** = 그 레코드는 버려진다)은 우리 버전에 해당 없음 |
| 헬스체크용 컨테이너 상태 API | `isInExpectedState()` · `isContainerPaused()` · `isPauseRequested()` · `getListenerId()` 전부 있다 |

### 5.8 결정 전에 봐야 하는 함정

1. **`stripPreviousExceptionHeaders` 기본 `true`** : `kafka_dlt-exception-*` 헤더가 매번 덮어써지므로 그걸 카운터로 쓸 수 없다. **`DeadLetterPublishingRecoverer.setHeadersFunction` 으로 자체 헤더**(예: `x-dlq-attempt`)를 1씩 올려 세야 한다. 안 세면 재처리 → 실패 → 다시 DLQ 무한 루프
2. **`setFailIfSendResultIsError`** : 켜지 않으면 DLQ 발행이 실패해도 조용히 넘어가 **DLQ 로 보내려던 것까지 유실**된다
3. **`319 UNKNOWN_STATUS_OF_INSERT` 가 재시도 대상**이다. 이름 그대로 "insert 가 됐는지 모른다" 라서 재시도하면 **중복 적재**다. `spans` 에 멱등 키가 없어 같은 트레이스가 두 번 들어간다
4. **성격상 일시 장애인데 화이트리스트에 없는 코드** : `243` NOT_ENOUGH_SPACE · `439` CANNOT_SCHEDULE_TASK · `565` TOO_MANY_PARTITIONS · `745` SERVER_OVERLOADED. `isRetryable()` 이 `false` 를 주므로 **디스크가 차면 들어오는 전부가 DLQ 로 쏟아진다**
5. **재시도가 두 겹** : `ConnectTimeout` · `SocketTimeout` · `ServerRetryable` 에서는 client-v2 가 자체 재시도하고 그 위에 Spring 이 또 한다
6. **`JsonEachRow.kt:18` 이 `.get()` 을 타임아웃 없이** 부른다. ClickHouse 가 아주 늦게 답하면 재시도 총시간이 `max.poll.interval.ms` 를 넘겨 리밸런스를 일으킬 수 있다

### 확인 못 한 것

- "일시 장애에 DLQ 를 쓰지 말라" 는 **명시 문구** : 공식 문서에서 못 찾음. 근거는 구조(Kafka Connect 가 sink 를 DLQ 범위에서 제외) + 버그 수정 사례
- client-v2 자체 재시도의 **기본 횟수 · 간격**, 기본 `retryOnFailures` 집합에 무엇이 들어 있나
- pause 상태에서 `isRunning()` 이 `true` 로 남는지 : 조사 A 도 "동작상 추정" 이라고 표시
- `CommonContainerStoppingErrorHandler` 가 AckMode 별로 커밋하는 정확한 경계
- ClickHouse **디스크 포화**(`243`) 를 실제로 만들어 확인하지 않았다. [`30-failure-modes.md`](../../design/30-failure-modes.md) 가 이미 「확인 못 한 것」 으로 적어 둔 항목
- `.get()` 타임아웃 없음이 실제로 리밸런스를 일으키는지 : DNS 실패는 즉시 떨어져 재현되지 않았다
- Kafka Connect 공식 문서(kafka.apache.org) 의 `errors.tolerance` 원문 : fetch 실패
- `endOffsets` 스냅샷으로 재처리 Job 을 끝내는 패턴의 공식 레퍼런스 예제

---

## 6. 2차 질문 (사람 차례)

> 승조가 5 를 읽고 모르는 것을 여기에 적는다. 답은 바로 아래에 붙인다.
> **비어 있으면 아직 안 물은 것이다.**

- **Q.**
  - A.

---

## 7. 2차 정리

> 6 의 질문이 끝난 뒤 채운다. **아직 결정이 아니다.** 결정은 8 에서 승조가 내린다.

- 좁혀진 선택지 :
- 결정을 가르는 기준 :
- 무엇을 정해야 하나 (목록) :
  - 일시 장애일 때 : **A~F 중 무엇** (5.2 의 결정 축 표가 근거다. 좁히는 것은 7 · 8 에서 승조가 한다)
  - 독성 메시지일 때 : DLQ 로 보내나 · 재시도 몇 번 뒤에 보내나
  - 재시도 횟수 · 간격
  - `319`(중복 적재 위험)를 재시도 대상에서 뺄지
  - 멈추는 쪽을 고르면 헬스체크 지표를 이 이슈에서 만들지

### 여기서 나온 면접 질문

1. 저장소가 죽었을 때 메시지를 DLQ 로 보내는 것과 소비를 멈추는 것 중 무엇을 골랐나. 왜 그게 더 안전한가
2. "독성 메시지" 와 "일시 장애" 를 코드에서 무엇으로 구분했나. 그 분류를 직접 만들지 않은 이유는
3. Kafka Connect 는 왜 sink 단계 실패를 DLQ 범위에서 아예 빼 놓았다고 생각하나
4. 소비를 멈추면 컨슈머 그룹 리밸런스가 나는데 어떻게 피했나
5. DLQ 를 자동으로 원본 토픽에 되돌리면 무엇이 잘못되나. 시도 횟수를 어디에 적었나
6. 유실이 일어난다는 것을 어떻게 알아냈나 (문서를 쓰다가 직접 깨뜨려 봤다)

---

## 8. 구현 프롬프트 (사람 차례)

> **승조가 직접 쓴다.** 7 을 읽고 결정을 글로 내린다. 그 글이 곧 설계이고,
> 4요소(채택 · 기각 · 사유 · 되돌리는 조건)를 담으면 그대로 ADR 이 된다(`#50` 이 첫 사례).
> 쓰고 나면 같은 원문을 [`prompts.md`](prompts.md) 와 ADR 안에 둔다.

```
(원문 그대로)
```
