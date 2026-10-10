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

답한 자리를 5절 소제목 괄호에 달아 뒀다 : 5.3=① · 5.1=②④ · 5.2=③ · 5.5=⑤.

## 2. 작업하기 전에 알아야 하는 것

### 2.1 Kafka · 토픽 · 오프셋

**Kafka** 는 메시지를 줄 세워 보관하는 중간 창고다. 보내는 쪽(수집기)과 받는 쪽(적재 처리기)이 서로를 기다리지 않게 해 준다.

**토픽** 은 그 창고 안의 **칸**이다. 우리는 두 칸을 쓴다.

| 토픽 | 무엇이 들어가나 | 보관 | 어디서 만드나 |
|---|---|---|---|
| `raw` | 수집기가 받은 OTLP 바이트 그대로 | **7일** | `compose.yaml` (파티션 3 · 복제 1) |
| `raw.dlq` | 적재에 실패한 메시지 | **30일** | `compose.yaml` (파티션 1 · 복제 1) |

**파티션** 은 한 토픽을 여러 칸으로 쪼갠 것이다. 쪼개는 이유는 **여러 컨슈머가 나눠 맡을 수 있게** 하는 것이고, Kafka 는 **파티션 안에서만 순서를 지킨다.**

메시지를 보낼 때 **키**를 주면 Kafka 가 `키의 해시 % 파티션 수` 로 칸을 고른다. 우리 `RawProducer` 는 키를 신호 이름(`traces` · `metrics` · `logs`)으로 준다. 그래서 **같은 신호끼리는 순서가 지켜진다.**

실제로 어디에 들어갔는지 세어 봤다(2026-10-06, 로컬):

| 파티션 | 들어 있는 것 |
|---|---|
| 0 | `metrics` 69건 **+** `traces` 131건 (둘이 섞였다) |
| 1 | `logs` 37건 |
| 2 | **비어 있다** |

**파티션 3개는 신호 3개와 무관하다.** 해시가 겹쳐 `traces` 와 `metrics` 가 같은 칸에 들어갔고 한 칸은 영원히 빈다. 그리고 `--partitions 3` 을 고른 근거는 **레포에 적혀 있지 않다**(`compose.yaml:71`, ADR 에도 없음). 흔한 기본값을 쓴 것으로 보인다.

컨슈머 그룹은 **`ingester` 하나**이고 그 안의 **컨슈머도 1개**다(파드 1개). 한 컨슈머가 파티션 3개를 전부 맡고 있다 : `CONSUMER-ID` 가 세 줄 다 같다. 파티션 수가 **동시에 일할 수 있는 컨슈머의 최대 수**라, 지금 구성은 적재 처리기를 **3대까지 늘릴 여유**가 있다는 뜻이다.

> 복제 수가 **1** 이다. 브로커가 죽으면 메시지가 사라진다. 로컬 전용 설정이고 K8s 에서는 올려야 한다. 이번 이슈 범위 밖이다.

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
| 중복 적재는 표 엔진(`ReplacingMergeTree`)으로 막는다 | **못 막는다.** `spans` 중복은 merge 때 합쳐지지만 **집계 MV 는 insert 시점에 이미 세어 버렸고 merge 를 보지 않는다.** 집계에 영향 가는 수정은 **insert 전**이어야 한다(`#83` · `#92` 와 같은 교훈) |
| 멈춘 컨슈머는 하트비트로 잡는다 | **못 잡는다.** 하트비트는 **별도 백그라운드 스레드**가 3초마다 보내므로 우리 코드가 꽉 막혀 있어도 계속 나간다. 그래서 Kafka 가 `max.poll.interval.ms`(5분)를 따로 둔 것이고, 헬스 지표도 **리스너 상태를 직접** 봐야 한다 |
| `pause` 는 연결을 끊는 것 | **아니다.** 전화 보류와 같다. `poll()` 은 계속 돌면서 "살아 있어" 를 알리고 레코드만 안 받는다. 그래서 리밸런스가 안 난다 |

---

## 3. 1차 질문 (사람 차례)

> 2 절을 읽고 승조가 물은 것. **질문은 원문 그대로 두고** 답을 아래에 붙였다.
> 세 바퀴 돌았다. 바로잡힌 이해가 **네 건**이고, 그 네 건이 이 절에서 제일 값나가는 부분이다.

### 첫 바퀴

**Q1.** 우리가 이번에 할 것은 데이터 적재 시 CH에서 문제가 생기면 적재 처리기에서 카프카에서 가져온 메시지를 버리니까 재시도랑 DLQ를 만든다는 거고 **(근데 왜 적재처리기에서 데이터를 버리는 거지? 적재처리기에 버퍼가 없어서 그런건가?)**

> **버퍼 문제가 아니다. Kafka 가 버퍼다.** 데이터는 지금도 Kafka 에 멀쩡히 있다(`raw:0:271`). "버린다" 는 데이터를 지우는 게 아니라 **"다 읽었다" 고 표시하고 지나가는 것**이다.
>
> 재시도를 다 쓰면 스프링이 **복구 담당**(recoverer)을 부른다. 우리가 지정하지 않았으니 기본값이 들어가 있고, 그게 하는 일은 **ERROR 로그 한 줄 + "처리 끝났음" 보고**뿐이다. 보고하면 오프셋이 넘어간다.
>
> 그래서 이번 작업은 버퍼를 만드는 게 아니라 **복구 담당을 바꿔 끼우는 일**이다.

**Q2.** 현재 카프카에서 사용하고 있는 토픽은 raw인데 rawdlq토픽을 만든 다는 거지? 그랬을 때 **각각의 파티션과 브로커들 그리고 소비자 그룹은 어떻게 되는 거지?**

> `raw.dlq` 는 **이미 있다.** `compose.yaml` 이 만든다(파티션 1 · 30일). 넣는 코드만 없다.
>
> | | `raw` | `raw.dlq` |
> |---|---|---|
> | 파티션 | 3 | 1 |
> | 복제 | 1 | 1 |
> | 브로커 | 1대 (로컬) | 같음 |
>
> 컨슈머 그룹은 **`ingester` 하나**뿐이고 그 안의 컨슈머도 **1개**다. `raw.dlq` 에는 그룹이 **안 생긴다** : 적재 처리기가 거기에 **쓰기만** 한다.
>
> 파티션 3 → 1 어긋남은 `verifyPartition` 기본값 `true` 가 막아 준다(목적지 파티션이 모자라면 비워 준다). 바이트코드 `iconst_1` 로 확인했다.

**Q3.** DLQ는 카프카 토픽으로 관리 하는 것으로 보이고 데이터가 깨진 경우엔 DLQ 네트워크 등의 장애로 실패시는 재시도 로직을 추가하는 거 같고

> 맞다. 다만 **"재시도만" 으로는 부족하다.** 재시도를 다 쓴 뒤에 무엇을 할지가 핵심이다. ClickHouse 가 10분 죽어 있으면 횟수를 늘려도 결국 소진된다. 그래서 5.2 의 선택지가 "재시도 몇 번" 이 아니라 **"소진되면 기다리나 · 멈추나 · DLQ 로 보내나"** 로 갈린다.

**Q4.** DLQ에 들어가기 전 재시도를 할 텐데 재시도 할 때 오프셋은 계속 재시도 해야하는 오프셋에 머물러 있는 거고 DLQ에 가거나 다음으로 넘어가게 되면 그 때 커밋을 치고 다음으로 넘어가는 건가?

> **정확히 맞다.** 실패하면 스프링이 컨슈머를 그 오프셋으로 되감아(`seek`) 다음 `poll` 에서 같은 레코드를 다시 받는다. 커밋은 안 한다. **복구가 끝났을 때**(DLQ 로 보냈거나 로그만 찍었거나) 그때 커밋하고 넘어간다.
>
> 그래서 **"멈춘다" 가 안전한 이유**가 여기서 나온다 : 커밋을 안 하면 파드가 재시작돼도 같은 자리에서 다시 읽는다.

**Q5.** 재시도 횟수는 10번이고 **10은 자바 기본 값으로 지정 된거고**

> **10번은 맞지만 자바 기본값이 아니라 Spring Kafka 기본값이다.** `SeekUtils` 의 static 초기화를 바이트코드로 봤다 :
>
> ```
> DEFAULT_BACK_OFF = new FixedBackOff(0L, 9L)
>                                     ↑lconst_0  ↑ldc2_w long 9l
> ```
>
> 그리고 **9 와 10 이 다르다** : `FixedBackOff(0, 9)` = 첫 시도 + 재시도 9번 = **총 10번**. 로그에 `currentAttempts=10, maxAttempts=9` 가 같이 찍힌 이유다.
>
> 자바도 Kafka 도 아니고 **Spring Kafka** 가 정한 값이다. 그리고 중요한 건 횟수가 아니라 **간격이 0초**라는 점이다 : 10번이 **4초 안에** 끝난다.

**Q6.** client-v2를 사용한다고 했는데 이건 ClickHouse 에 데이터를 넣는 자바 라이브러리이고 **JDBC랑은 어떤 관계가 있는 지 설명 해줘**

> **JDBC** = Java Database Connectivity. "자바에서 DB 에 말 거는 방식을 통일한 규격" 이다. 콘센트 규격과 같아서, 모양만 맞으면 MySQL · PostgreSQL · ClickHouse 어느 쪽이든 자바 코드가 거의 안 바뀐다. 그래서 `JdbcTemplate` · 커넥션 풀 · SQL 도구가 그냥 붙는다.
>
> 둘은 **같은 프로젝트(`clickhouse-java`)의 상하 관계**다. 의존성을 뽑아 보니 :
>
> ```
> clickhouse-jdbc
> ├── jdbc-v2
> ├── clickhouse-http-client
> └── client-v2      ← JDBC 가 client-v2 를 품고 있다
> ```
>
> `clickhouse-jdbc` = `client-v2` 에 JDBC 껍데기를 씌운 것이다. 우리는 쓰기 경로에서 **껍데기를 벗고 안쪽을 직접** 쓴다.

### 둘째 바퀴 (되물음)

**Q7.** 1번에서 말하는 복구 담당이라는 것이 **없어서** 기존에는 무조건 재시도든 DLQ든 오프셋이 넘어갔는데 이제 복구 담당을 **만들어서** 이제 실패해서 성공 혹은 DLQ로 넘어가야 오프셋이 변경 되도록 하는 건가?

> 목표는 그대로 맞다. 다만 **복구 담당은 "없다" 가 아니라 "기본값이 들어 있다"** 다. 지금도 "복구가 끝나면 오프셋이 넘어간다" 는 규칙대로 돈다. 문제는 **기본 복구 담당이 "로그 찍기" 를 복구로 치는 것**이다.
>
> 그리고 복구 담당 하나만 바꿔서는 안 되고 **셋을 같이** 해야 한다 :
>
> ```
> ① 재시도를 쓸 만하게 바꾼다            (4초 → 더 오래)
> ② 복구 담당을 DLQ 로 바꾼다            (로그 찍기 → raw.dlq 에 옮겨 담기)
> ③ 저장소 장애면 복구 담당을 아예 안 부른다  ← 이게 핵심
> ```
>
> ③ 이 빠지면 **ClickHouse 가 오래 죽어 있을 때 결국 전부 DLQ 로 쏟아진다.**

**Q8.** 현재 raw는 파티션을 3개로 했고 **이유가 로그, 트레이스, 메트릭으로 분리하고 순서 보장하려고 한거고** 컨슈머 그룹은 하나고 **그룹안에 컨슈머는 3개인거고 각각 전부 raw의 파티션 1개씩 잡고 있는 거고**

> **두 군데가 다르다.**
>
> **① 파티션 3개는 신호 3개와 무관하다.** 실제로 세어 보니 파티션 0 에 `metrics` 69건 + `traces` 131건이 섞여 있고, 파티션 2 는 **비어 있다**. 키의 해시가 3으로 나눴을 때 겹쳐서 그렇다. `--partitions 3` 을 고른 근거는 레포에 적혀 있지 않다.
>
> 순서 보장은 **키 덕**이다. 같은 키는 같은 칸에 가므로 같은 신호끼리 순서가 지켜진다. 파티션이 1개여도 같은 결과다.
>
> **② 컨슈머는 3개가 아니라 1개다.** 조회 결과의 `CONSUMER-ID` 가 세 줄 다 같다. 파드 1개가 파티션 3개를 전부 혼자 맡고 있다. 파티션 3개는 **3대까지 늘릴 여유**라는 뜻이다.

**Q9.** DLQ에서 컨슈머 그룹이 안 생기는 이유를 조금만 더 쉽게 설명해줘

> **컨슈머 그룹은 "읽는 사람들의 모임" 이다. 읽는 사람이 없으면 모임도 없다.**
>
> ```
> raw 토픽      ──읽는다──▶  적재 처리기   →  "ingester" 그룹 생김
> raw.dlq 토픽  ◀──쓴다───   적재 처리기   →  아무것도 안 생김
> ```
>
> 적재 처리기는 `raw.dlq` 에 **넣기만** 한다. 우체통에 편지를 넣는 사람이지 꺼내는 사람이 아니다. 꺼내는 사람(재처리 잡)은 **아직 안 만들었다.**

**Q10.** 재시도 간격이 스프링 카프카의 기본값이라 0s라고 하는데 이건 **지수적 그거(용어가 생각안남)랑 지터** 이용해서 늘리면 되는 거지 이번 업무를 통해서?

> 용어는 **지수 백오프(exponential backoff)** 다. 그리고 Spring 에 이미 있다. `spring-core 6.2.19` 소스를 열어 봤다 :
>
> ```java
> ExponentialBackOff
>   DEFAULT_INITIAL_INTERVAL = 2000L       // 2초로 시작
>   DEFAULT_MULTIPLIER       = 1.5         // 1.5배씩
>   DEFAULT_MAX_INTERVAL     = 30000L      // 최대 30초
>   DEFAULT_MAX_ELAPSED_TIME = Long.MAX    // 총 시간 제한 없음
>   DEFAULT_MAX_ATTEMPTS     = Integer.MAX // 횟수 제한 없음
> ```
>
> **지터는 우리 버전에 없다.** 설정 항목은 위 다섯 개뿐이고 `jitter` 가 없다. 그리고 **지금 필요하지도 않다** : 지터는 여러 클라이언트가 동시에 재시도해 서버를 다시 때려눕히는 것을 막는 장치인데, 우리는 **컨슈머가 1개**라 흩뿌릴 대상이 없다.
>
> **그런데 지수 백오프에 벽이 있다. 이게 이번 결정의 핵심이다.** 기본 `BackOffHandler` 는 리스너 스레드를 **재운다.** 재우는 동안 `poll` 을 못 하므로 백오프 총시간이 `max.poll.interval.ms`(기본 5분)를 넘으면 **리밸런스**가 터진다. 즉 **"간격을 넉넉히 늘린다" 와 "리밸런스를 안 낸다" 가 부딪친다.** 그 벽을 넘는 방법이 선택지 D(`ContainerPausingBackOffHandler`)다.

### 셋째 바퀴 (되물음)

**Q11.** DLQ에 컨슈머 그룹이 안생기는 이유는 DLQ 토픽 속 메시지는 적재처리기가 가져가지 않기 때문인건가? 그저 적재 처리기가 raw.dlq 토픽을 **사용하기** 때문에 그런건가? 그래서 나중에 **ingester파일에 한 번에 넣으면 안되고 분리해서 넣어야** 한다는 거고

> 앞쪽은 맞다 : **가져가지 않기 때문**이다. 그룹은 **읽을 때** 생긴다. 뒤쪽("토픽을 사용하기 때문")은 반대로 읽은 것이다 : **쓰기는 그룹을 만들지 않는다.**
>
> 마지막 줄은 다르다. **파일을 나누는 게 아니라 `group-id` 를 나눈다.** ADR `#34` 가 재처리를 **"같은 이미지, K8s Job"** 으로 정해 뒀다. 코드를 따로 만들지 않는다.
>
> ```
> 같은 이미지를 두 방식으로 띄운다
>   평소 (Deployment)        group-id = ingester       → raw 를 읽는다
>   재처리 (K8s Job, 한 번)  group-id = dlq-reprocess  → raw.dlq 를 읽는다
> ```
>
> 같은 이름을 쓰면 **책갈피를 공유해** 서로 남의 자리를 밀어 버린다.

**Q12.** 현재 재시도 간격이 0초라는 점이 어떤 말인지 설명해줘

> 재시도 사이에 **쉬는 시간이 0초**라는 뜻이다.
>
> ```
> 간격 0초 (지금)          간격 5초 (예시)
>   0.0초 시도1 실패         0초  시도1 실패
>   0.0초 시도2 실패          5초  시도2 실패
>   ...                      ...
>   3.9초 시도10 → 포기      45초 시도10 → 포기
>   총 4초                   총 45초
> ```
>
> 저장소가 살아나는 데는 보통 몇 초에서 몇 분이 걸린다. 컨테이너 재시작만 10~30초다. 그런데 우리는 **4초만 기다리고 포기한다.** 횟수를 100번으로 늘려도 간격이 0이면 40초다. **횟수보다 간격이 문제다.**

**Q13.** api-server 가 JDBC 를 쓰는 이유는 실패 종류를 알아야할 필요가 없고 쿼리를 많이 쓰기에 사용하는 거다?

> 맞다. 조회는 쿼리가 실패하면 화면에 "조회 실패" 를 돌려주면 끝이라 **종류를 가릴 이유가 없다.** 대신 쿼리를 많이 쓰므로(API 50행 · 화면 15개) `JdbcTemplate` 의 편의가 훨씬 크다.
>
> | | 적재 `ingester` | 조회 `api-server` |
> |---|---|---|
> | 하는 일 | 수천 줄을 **넣는다** | SQL 로 **읽는다** |
> | 필요한 것 | 대량 쓰기 · **실패 분류** | `JdbcTemplate` · 커넥션 풀 |
> | 실패하면 | 재시도냐 DLQ 냐를 **갈라야 한다** | 화면에 에러를 돌려주면 끝 |
> | 그래서 | `client-v2` (껍데기를 벗는다) | `clickhouse-jdbc` (껍데기를 쓴다) |

### 이 절에서 바로잡힌 것 (네 건)

| 내가 틀리게 알고 있던 것 | 실제 |
|---|---|
| 적재 처리기에 **버퍼가 없어서** 버린다 | 버퍼 문제가 아니다. **Kafka 가 버퍼**고, 책갈피를 옮겨 버리는 것이다 |
| 재시도 10번은 **자바** 기본값 | **Spring Kafka** 기본값(`SeekUtils.DEFAULT_BACK_OFF = FixedBackOff(0, 9)`) |
| 파티션 3개는 **로그 · 트레이스 · 메트릭을 분리**하려고 | 무관하다. 해시가 겹쳐 `metrics`+`traces` 가 한 칸, **한 칸은 빈다.** 근거도 레포에 없다 |
| 컨슈머가 **3개**, 각각 파티션 1개씩 | **1개**가 3개를 전부 맡는다. 파티션 3개는 "3대까지 늘릴 여유" |

그리고 **지수 백오프 + 지터로 간격을 늘리면 된다** 는 생각은 절반만 맞았다 : 지수 백오프는 있고 지터는 우리 버전에 없으며 필요도 없다. 무엇보다 **`max.poll.interval.ms` 5분 천장**에 걸려서 그것만으로는 안 된다.

---

## 4. 1차 정리

> 3 의 질문 세 바퀴가 끝난 뒤의 상태다.

**이번 작업은 결국 세 가지다**

```
① 재시도를 쓸 만하게 바꾼다             지금 4초 → 더 오래 (지수 백오프)
② 복구 담당을 바꾼다                    로그 찍기 → raw.dlq 에 옮겨 담기
③ 저장소 장애면 복구 담당을 안 부른다     ← 핵심. 부르면 오프셋이 넘어간다
```

③ 이 빠지면 ① ② 만으로는 **ClickHouse 가 오래 죽어 있을 때 전부 DLQ 로 쏟아진다.** 그게 ADR `#34` 의 기각 사유("DLQ 가 받는 것은 독성 메시지")를 어기는 결과다.

**확실한 것**

- 유실은 Kafka 가 지워서가 아니라 **오프셋(책갈피)이 넘어가서** 생긴다. 메시지는 `raw` 에 7일간 남아 있다
- **버퍼 문제가 아니다.** Kafka 가 버퍼다. 고칠 것은 "꺼낸 뒤 읽었다고 표시하는 규칙" 이다
- 재시도 중에는 오프셋이 **머물러 있고**, 복구가 끝나면 커밋하고 넘어간다. 그래서 **멈추는 쪽이 유실이 없다**(단 `raw` 보관 7일 안에 복구했을 때)
- 실패는 두 종류다 : **고쳐질 것**(저장소가 잠깐 죽음)과 **안 고쳐질 것**(데이터가 깨짐). 전자를 DLQ 로 보내면 DLQ 가 원본 복사본이 된다
- `raw.dlq` 토픽(30일)은 **이미 있고 넣는 코드만 없다.** S3 는 DLQ 가 아니라 ClickHouse 의 cold 계층이다(ADR `#34`)
- 고치는 자리는 `ingester` 한 곳이다. 수집기 · 표 구조 · 메시지 형식은 안 건드린다
- 재처리 잡은 **같은 이미지**로 돌리고 `group-id` 만 다르게 한다(ADR `#34` : K8s Job). 이 이슈 범위 밖이다
- 재시도 10번 · 간격 0초는 **Spring Kafka 기본값**이고, **횟수보다 간격이 문제**다(10번이 4초)
- `ExponentialBackOff` 로 간격을 늘릴 수 있지만 **`max.poll.interval.ms` 5분 천장**에 걸린다. 지터는 우리 Spring 버전에 없고 컨슈머가 1개라 필요도 없다
- 실패 종류를 가릴 수 있는 것은 **`client-v2` 를 직접 쓰기 때문**이다. JDBC 를 거치면 `SQLException` 하나로 뭉개져 이번 작업이 불가능하다

**아직 모르는 것** (5 에서 다룬다)

- 일시 장애일 때 **정확히 무엇을 할지** : 기다리나 · 멈추나 · 어떻게 멈추나. 5분 천장을 어떻게 넘나
- 두 종류를 **코드에서 무엇으로 구분**하나
- 재시도를 몇 번 · 얼마 간격으로 하나
- 멈추는 쪽을 고르면 **멈춘 것을 어떻게 알아채나**

---

## 5. 선택지와 설명

### 조사 프롬프트

두 갈래로 나눠 서브에이전트 2개에 동시에 보냈다(A = Spring Kafka 쪽, B = ClickHouse 예외 · 업계 비교). 원문은 같은 폴더의 [`prompts.md`](prompts.md) 에.

### 5.1 무엇으로 구분하나 : client-v2 가 이미 갈라 놓았다 (② ④)

`HttpAPIClientHelper.wrapException()` 이 원인 예외를 타입으로 바꿔 준다. **우리가 판단 규칙을 만들 필요가 없다.**

| 예외 | 언제 나오나 | `isRetryable` | 어느 쪽인가 |
|---|---|---|---|
| `ConnectionInitiationException` | 서버에 닿지 못함 (`UnknownHostException` · `ConnectException` · 연결 타임아웃) | **생성자에서 항상 true** | 일시 장애 |
| `DataTransferException` | 전송 중 끊김 (`SocketTimeoutException` · `IOException`) | false | 일시 장애이지만 **중복 적재 위험** |
| `ServerException` | **서버가 응답했고 거절함** | 코드로 계산 | 코드가 갈라 준다 |
| `TransportException` | SSL 문제 | false | 설정 오류 |
| `ClientException` · `ClientMisconfigurationException` | 클라이언트 코드 · 설정 | false | 재시도 무의미 |

`ServerException.discoverIsRetryable()` 이 재시도 대상 코드를 이미 들고 있다 : `3` · `107` · `159` · `164` · `202` · `203` · `209` · `210` · `241` · `242` · `252` · `285` · `319` · `425` · `999` **(15개, 바이트코드 확인)** → `true`, **그 외 전부 false.** 권장은 `if (e.isRetryable()) 재시도 else DLQ` 이고 **이 목록을 다시 구현하지 말라**는 것이다.

JSONEachRow insert 에서 실제로 자주 보는 거절 코드 : `117` INCORRECT_DATA · `27` CANNOT_PARSE_INPUT_ASSERTION_FAILED · `53` TYPE_MISMATCH · `41` CANNOT_PARSE_DATETIME · `72` CANNOT_PARSE_NUMBER. 우리 `spans` 의 `events` Nested 배열 세 개(`events.ts` · `events.name` · `events.attributes`) 길이가 어긋나면 `27` 이 날 자리다.

출처 : [HttpAPIClientHelper](https://github.com/ClickHouse/clickhouse-java/blob/main/client-v2/src/main/java/com/clickhouse/client/api/internal/HttpAPIClientHelper.java) · [ServerException](https://github.com/ClickHouse/clickhouse-java/blob/main/client-v2/src/main/java/com/clickhouse/client/api/ServerException.java) · [ClickHouse ErrorCodes.cpp](https://github.com/ClickHouse/ClickHouse/blob/master/src/Common/ErrorCodes.cpp)

### 5.2 일시 장애일 때 무엇을 하나 : 여섯 선택지 (③)

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

**D 는 "무한 대기" 가 아니다** (6절 Q25 에서 바로잡은 것). D 는 **"어떻게 기다리나"** 만 정하고 **"몇 번"** 은 BackOff 가 정한다. 둘은 따로다 :

```
D + ExponentialBackOff(2초 시작, 1.5배, 최대 30초, 상한 60회)
  → 총 대기 약 30분 · 리밸런스 없음
  → 독성 메시지가 잘못 분류돼도 30분 뒤 DLQ 로 빠진다 (영원히 안 막힌다)
  → ClickHouse 가 10분 죽어도 유실 0
```

그래서 **C 와 D 의 차이는 "간격을 얼마나 주나" 가 아니라 "5분 천장을 넘을 수 있나" 다.**

**E** 는 "조용한 실패보다 시끄러운 정지가 낫다" 는 선택이다. Kafka Connect 가 하는 방식과 같다. 다만 **스스로 다시 시작하지 않는다.** Spring Boot 가 Kafka 컨슈머 헬스 인디케이터를 기본 제공하지 않으므로, 멈춘 상태를 `/actuator/health` 에 드러내려면 `KafkaListenerEndpointRegistry` 를 보는 지표를 직접 만들어야 한다. 안 만들면 **파드가 멈춰 있는데 아무도 모른다.**

**F** 는 이름만 보면 맞아 보이지만 우리에게 안 맞는다. 일시 장애 때 **트래픽 전량이 재시도 토픽으로 복제**되므로 B 와 같은 문제가 생기고, 게다가 파티션 내 순서가 깨진다.

**간격을 늘리는 수단** (3절 Q10 에서 추가 조사한 것) : `spring-core 6.2.19` 의 `ExponentialBackOff` 를 `FixedBackOff` 자리에 꽂으면 된다. 기본값은 `initialInterval=2000` · `multiplier=1.5` · `maxInterval=30000` · `maxElapsedTime=Long.MAX` · `maxAttempts=Integer.MAX` 라 **2초 → 3초 → 4.5초 ... → 30초** 로 늘어난다. 설정 한 줄이다.

**지터(jitter)는 우리 버전에 없다.** 소스를 다 훑어도 설정 항목이 위 다섯 개뿐이다. 그리고 지터는 **여러 클라이언트가 동시에 재시도해 서버를 다시 때려눕히는 것**을 막는 장치인데 우리는 컨슈머가 **1개**라 흩뿌릴 대상이 없다. 적재 처리기를 3대로 늘려도 ClickHouse 를 때려눕힐 규모가 아니다.

그래서 **C 와 D 의 차이는 "간격을 얼마나 주나" 가 아니라 "5분 천장을 넘을 수 있나" 다.** 기본 `BackOffHandler` 는 리스너 스레드를 재우므로 재우는 동안 `poll` 을 못 한다. D 는 pause 를 쓰므로 `poll` 을 계속한다.

출처 : [Handling Exceptions](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html) · [ContainerPausingBackOffHandler](https://docs.spring.io/spring-kafka/api/org/springframework/kafka/listener/ContainerPausingBackOffHandler.html) · [Pausing and Resuming](https://docs.spring.io/spring-kafka/reference/kafka/pause-resume.html) · [Non-Blocking Retries](https://docs.spring.io/spring-kafka/reference/retrytopic.html) · [CommonContainerStoppingErrorHandler](https://docs.spring.io/spring-kafka/api/org/springframework/kafka/listener/CommonContainerStoppingErrorHandler.html)

### 5.3 DLQ 로 보내는 방법 자체는 정해져 있다 (①)

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

### 5.5 남이 어떻게 하나 : 다섯 중 하나도 sink 장애를 DLQ 로 보내지 않는다 (⑤)

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
- **조사 B** 가 라이브러리 화이트리스트를 **14개**라 했고 `159 TIMEOUT_EXCEEDED` 를 "재시도도 DLQ 도 아닌 설정 · 쿼리 문제" 로 분류했다. **코드 리뷰어가 바이트코드로 15개임을 짚었고 내가 다시 확인했다** : `discoverIsRetryable` 의 switch 케이스가 `3 107 159 164 202 203 209 210 241 242 252 285 319 425 999`. 동작에는 영향 없었다(`isRetryable()` 을 그대로 쓰므로 `159` 도 재시도 쪽으로 간다). 주석 · 문서의 숫자만 틀려 있었다. **같은 목록을 세 사람(조사 · 나 · 리뷰어)이 봤는데 세 번째에야 맞았다**
- **나(메인 대화)** 가 쓴 코드 주석 5곳이 코드와 다른 말을 했다(리뷰가 잡음) : "총 maxElapsed 까지"(간격의 합이고 벽시계는 더 길다) · "조회 실패를 0 으로 본다"(`set -e` 로 종료였다) · "가이드의 수동 확인 2번"(레포 밖 파일을 가리켰다) 등. 그리고 `IllegalArgumentException` 을 통째로 POISON 으로 잡으려 했는데 변환기 IAE 까지 걸린다는 지적을 받아 `common` 에 전용 예외를 뒀다
- **나(메인 대화)** 가 결정 프롬프트 초안을 다듬으며 되돌림 ③ 에 "pause 가 **파티션 단위로** 동작해서" 라고 썼다. **거꾸로다.** `FailedRecordTracker` 는 컨테이너 변형 `onNextBackOff(container, Exception, long)` 을 부르고, 그게 `pause(컨테이너, Duration)` 으로 이어져 **컨테이너 전체가 멈춘다**(바이트코드 확인, 구현 직전). 결론(`pausePartition` 으로 좁힌다)은 맞아서 ADR `#51` 에 정정 줄을 달았다
- **나(메인 대화)** 가 "`verifyPartition` 기본값 `true` 가 `raw`(3) → `raw.dlq`(1) 어긋남을 막아 준다" 고 했고 그건 맞았지만, **내가 쓴 래퍼가 그 검사를 꺼 버렸다.** 카운터를 세려고 `ConsumerRecordRecoverer { record, failure -> dlq.accept(record, failure) }` 로 감쌌는데, 파티션 검사는 `consumer` 를 받는 **세 인자 판**에서만 돈다. 두 인자 판은 `consumer = null` 을 넘겨 검사를 건너뛴다. 결과 : `raw` 파티션 1 의 독성 메시지가 `raw.dlq` 파티션 1(없음)로 가려다 `TimeoutException: Partition 1 of topic raw.dlq with partition count 1 is not present` → `failIfSendResultIsError` 가 세움 → **그 레코드에서 영원히 멈춤.** 컨테이너 테스트(5건 통과)는 **못 잡았다** : 테스트 Kafka 의 `raw` 가 자동 생성되어 파티션 1개라 어긋남이 없었다. compose(`raw` 3개)에서 키 `logs` 로 수동 검증하다 `lag=1` 로 잡았다. 고친 것 : `ConsumerAwareRecordRecoverer` 로 바꿔 `consumer` 를 넘기고, `TestInfraConfig` 가 토픽을 compose 와 같게(`raw` 3 · `raw.dlq` 1) 미리 만들고, 테스트가 파티션 1 로 보내 원본 파티션 헤더 `1` · 목적지 파티션 `0` 을 확인한다. **테스트 토폴로지가 실제와 다르면 통과한 테스트가 거짓 안심을 준다**

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
| 재시도 10번이 **어디서 온 값인가** | `SeekUtils.DEFAULT_BACK_OFF = new FixedBackOff(0L, 9L)`. 바이트코드 `lconst_0` · `ldc2_w long 9l`. **자바도 Kafka 도 아니라 Spring Kafka 값**이고, 9는 재시도 수라 총 시도는 10이다 |
| `ExponentialBackOff` 의 지터 | **없다.** `spring-core 6.2.19` 소스에 `initialInterval`(2000) · `multiplier`(1.5) · `maxInterval`(30000) · `maxElapsedTime` · `maxAttempts` 다섯 개뿐 |
| `clickhouse-jdbc` 가 `client-v2` 를 품나 | **그렇다.** `:api-server:dependencies` 에 `clickhouse-jdbc → client-v2` 가 보인다. 둘은 경쟁이 아니라 상하 관계 |
| `raw` 의 키별 파티션 분포 | 파티션 0 = `metrics` 69 + `traces` 131 · 파티션 1 = `logs` 37 · **파티션 2 = 비어 있음.** 신호 3개와 파티션 3개는 무관하다 |
| 컨슈머 수 | **1개.** `kafka-consumer-groups --describe` 의 `CONSUMER-ID` 가 파티션 세 줄 다 같다 |
| 예외마다 다른 BackOff 를 줄 수 있나 | **된다.** `FailedRecordProcessor.setBackOffFunction(BiFunction<ConsumerRecord, Exception, BackOff>)` 가 있다. 분류별로 대기 규칙을 나누는 근거 |
| 분류를 손으로 덧씌울 수 있나 | **된다.** `ExceptionClassifier` 에 `addNotRetryableExceptions` · `addRetryableExceptions` · `setClassifications` · `defaultFalse()` 가 있다. `defaultFalse()` 가 "모르는 것은 재시도 안 함" 을 뒤집는 열쇠 |
| `pause` 중에 왜 poll 이 되나 | Kafka `Consumer` 에 `pause(파티션들)` · `resume(파티션들)` · `paused()` 가 **`poll()` 과 별개로** 있다. `pause` 는 연결을 끊지 않고 "이 파티션은 데이터를 주지 마" 라고 표시만 한다 |
| pause 가 스스로 깨나 | **깬다.** `ListenerContainerPauseService.pause(컨테이너, Duration)` 가 `Duration` 을 받고 그 시간 뒤 자동 `resume` 한다 |
| 컨슈머 타임아웃 실제 값 | 적재 처리기 로그에서 : `heartbeat.interval.ms=3000` · `session.timeout.ms=45000` · `max.poll.interval.ms=300000` · `max.poll.records=500`. 우리가 설정한 것은 **하나도 없다**(전부 기본값) |
| pause 가 파티션 단위인가 컨테이너 단위인가 | **컨테이너 단위.** `FailedRecordTracker` → `BackOffHandler.onNextBackOff(container, Exception, long)` → `ContainerPausingBackOffHandler` → `ListenerContainerPauseService.pause(container, Duration)`. 파티션 변형(`pausePartition`)도 있지만 트래커가 안 부른다 |
| `verifyPartition` 이 실제로 도는 조건 | `DeadLetterPublishingRecoverer.accept(record, **consumer**, failure)` 세 인자 판에서만. 두 인자 판은 `consumer=null` → 검사 건너뜀. 래퍼를 쓸 때 **`ConsumerAwareRecordRecoverer`** 여야 한다 (실제로 걸려 넘어진 지점) |
| Boot 가 `CommonErrorHandler` 빈을 자동으로 꽂나 | **꽂는다.** `ConcurrentKafkaListenerContainerFactoryConfigurer.setCommonErrorHandler` 가 있다. 리스너 팩토리를 직접 만들 필요 없음 |
| `TaskScheduler` 가 자동으로 있나 | **없다.** `TaskSchedulingAutoConfiguration` 이 `@EnableScheduling`(`internalScheduledAnnotationProcessor` 빈)을 조건으로 건다. `ListenerContainerPauseService` 에 넘길 스케줄러를 직접 만들었다 |
| `ServerException` 생성자 순서 | `(code, message, transportProtocolCode, queryId)`. 바이트코드 `iload_1 → putfield code` · `iload_3 → putfield transportProtocolCode`. `isRetryable` 은 생성자에서 코드로 계산된다 |

### 5.8 결정 전에 봐야 하는 함정

1. **`stripPreviousExceptionHeaders` 기본 `true`** : `kafka_dlt-exception-*` 헤더가 매번 덮어써지므로 그걸 카운터로 쓸 수 없다. **`DeadLetterPublishingRecoverer.setHeadersFunction` 으로 자체 헤더**(예: `x-dlq-attempt`)를 1씩 올려 세야 한다. 안 세면 재처리 → 실패 → 다시 DLQ 무한 루프
2. **`setFailIfSendResultIsError`** : 켜지 않으면 DLQ 발행이 실패해도 조용히 넘어가 **DLQ 로 보내려던 것까지 유실**된다. 켜면 예외가 올라와 오프셋이 안 넘어가고 다시 시도한다. **정할 것이 없다. 그냥 켠다** : 스프링의 상한 설정 `setMaxRecoveryFailures`(넘기면 버린 걸로 치고 커밋)가 우리 3.3.16 에 **없어서** 몰래 버려질 길도 없다
3. **`319 UNKNOWN_STATUS_OF_INSERT` 가 재시도 대상**이다. 이름 그대로 "insert 가 됐는지 모른다" 라서 재시도하면 **중복 적재**다. `spans` 에 멱등 키가 없어 같은 트레이스가 두 번 들어간다
   - **해결법 ①** 재시도 대상에서 **뺀다**. 한 줄이고 중복이 안 생긴다. 다만 실제로는 들어갔을 수도 있는 것이 DLQ 로 간다(사람이 보고 판단할 수 있으니 조용한 중복보다 낫다)
   - **해결법 ②** `insert_deduplication_token` 으로 넣기 자체를 멱등으로 만든다. 서버가 insert 를 거부하므로 MV 가 애초에 안 돈다. 비복제 `MergeTree` 에서 되는지는 **미확인**
   - **해결법 ③ `ReplacingMergeTree` 는 안 된다.** `spans` 중복은 merge 때 합쳐지지만 **집계 MV 는 insert 시점에 이미 세어 버렸고 merge 를 보지 않는다.** `#83` · `#92` 와 같은 교훈이다 : 집계에 영향 가는 수정은 **insert 전**이어야 한다
4. **성격상 일시 장애인데 화이트리스트에 없는 코드** : `243` NOT_ENOUGH_SPACE · `439` CANNOT_SCHEDULE_TASK · `565` TOO_MANY_PARTITIONS · `745` SERVER_OVERLOADED. `isRetryable()` 이 `false` 를 주므로 **디스크가 차면 들어오는 전부가 DLQ 로 쏟아진다**
   - **해결법 ①** 빠진 코드를 **우리가 덧붙인다.** 명시적이지만 **우리가 목록을 들게 되고** ClickHouse 버전이 올라가면 또 빠뜨린다
   - **해결법 ② 판단을 뒤집는다.** 지금은 "재시도할 것 목록" 을 쓰고 나머지를 DLQ 로 보낸다. 거꾸로 **"DLQ 로 보낼 것 목록"(파싱 · 타입 오류 계열)만 DLQ, 나머지 전부 재시도**로 바꾼다
     ```
     지금   : 모르는 실패 → DLQ   → 유실 위험 쪽으로 기운다
     뒤집기 : 모르는 실패 → 재시도 → 멈추는 쪽으로 기운다
     ```
     **틀려도 안전한 쪽으로 기울이는 것**이고, ADR `#50` 이 "접두 일치를 안 쓰고 정확 일치만 쓴다. 틀려도 안 버리는 쪽을 고른다" 고 정한 것과 **같은 원칙**이다. 단점은 진짜 독성 메시지가 재시도 목록에 걸리면 막히는 것인데, **재시도에 상한이 있으면 결국 DLQ 로 빠져 영원히는 안 막힌다**(6절 Q25)
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
- **`insert_deduplication_token` 이 비복제 `MergeTree` 에서 동작하는지** : `319` 해결법 ②의 전제인데 확인하지 않았다. `spans` 는 `ENGINE = MergeTree`(복제 아님)다
- **분류를 뒤집었을 때 "DLQ 로 보낼 코드 목록" 이 실제로 짧고 안정적인지** : 파싱 · 타입 오류 계열이 ClickHouse 버전에 따라 늘어나는지 보지 않았다

---

## 6. 2차 질문 (사람 차례)

> 5 절을 읽고 승조가 물은 것. **질문은 원문 그대로** 두고 답을 붙였다. 다섯 바퀴 돌았다.
> 이 절에서 **내 이해 6건**이 바로잡혔고, **내(AI) 설명의 허점 2건**도 드러났다.

### 첫 바퀴 : 용어

**Q14.** poll 이 뭔지, sink 장애가 뭔지 리밸런스가 뭔지

> **poll** : Kafka 는 밀어 주지 않고 **받는 쪽이 가져간다.** 적재 처리기가 "새 메시지 있어?" 라고 묻는 행위가 `poll()` 이다. **두 가지를 동시에 한다** : ① 메시지를 받아 온다 ② **"나 살아 있어" 를 알린다.** ②가 이번 결정의 분기점이다.
>
> **sink** : 파이프라인에서 들어오는 쪽이 source, **나가는 쪽이 sink** 다. 우리 sink 는 ClickHouse 다. **sink 장애 = 내보낼 목적지가 고장난 것.**
>
> **리밸런스** : 컨슈머 그룹 안에서 **파티션 담당을 다시 나누는 일.** 멤버가 들어오거나 나가거나 **죽은 것처럼 보이면** 일어난다. 그 사이 일이 멈추고, 커밋 안 된 작업은 마지막 커밋 지점부터 다시 한다.

**Q15.** BackOff가 뭔지

> **"재시도 전에 얼마나 쉴지" 정하는 규칙.** 이름대로 "한 발 물러나기" 다.
>
> ```
> FixedBackOff(0, 9)    ← 지금. 0초씩 9번 → 총 4초
> FixedBackOff(5000, 9)        5초씩 9번 → 총 45초
> ExponentialBackOff           2초 → 3초 → 4.5초 → ... → 30초(상한)
> ```
>
> 늘리는 이유 : 고장은 금방 낫거나 한참 걸린다. 처음엔 짧게 자주 보다가 안 나으면 간격을 늘려 **헛되게 두드리는 횟수를 줄인다.**

### 둘째 바퀴 : 리밸런스와 함정

**Q16.** 리벨런스가 나는 이유가 poll을 해야하는데 재시도 때문에 poll을 못하면 카프카가 해당 컨슈머를 빼고 다시 파티션을 나눠주고 다시 헬스체크가 되면 다시 컨슈머랑 파티션이랑 연결해야하는데 **지금은 컨슈머가 1개라 문제가 없지만** 적재처리기가 많아지면 문제가 되니까 (리벨런싱 동안 컨슈머로 가는 일이 멈추니까 컨슈머 랙이 발생 수도 있는 거고)

> 흐름은 맞다. 다만 **1개여도 문제가 없는 건 아니다.** 남에게 넘어갈 일이 없을 뿐, **빠졌다 들어오는 왕복**은 그대로 생기고 그동안 적재가 멈추고 마지막 커밋 지점부터 다시 한다. 여러 대가 되면 **남의 파티션까지 흔들려** 더 커진다.

**Q17.** 또한 **적재처리기 쪽 문제를 DLQ로 보낼 곳이 없다**는 거네

> **거꾸로 읽은 것이다.** 보낼 곳은 **있다**(`raw.dlq` 가 이미 만들어져 있다). 조사 결과는 **"남들은 sink 장애를 DLQ 로 보내지 않는다"** 였다. 없어서가 아니라 **그게 틀린 방법이라고 판단해서** 그렇게 만들어 놨다. Kafka Connect 는 아예 sink 실패가 DLQ 로 갈 수 없게 구조를 짜 뒀다.

**Q18.** 여기서 말하는 **라이브러리가 뭐지?** · **라이브러리 화이트리스트는 뭐지?**

> 라이브러리 = **`com.clickhouse:client-v2`.** ClickHouse 를 만든 회사가 직접 만든 자바 라이브러리고, 우리가 적재에 쓴다.
>
> 화이트리스트 = 그 안에 하드코딩된 **"이 에러코드들은 재시도해도 된다" 는 목록**(`ServerException.discoverIsRetryable()`, 15개). ClickHouse 에러코드가 1000개가 넘어 우리가 다 분류할 수 없으니 라이브러리가 골라 준 것이고, `e.isRetryable()` 만 부르면 된다.
>
> **문제는 그 목록이 우리 상황을 다 반영하지 못한다는 것이다** : `319` 는 들어 있는데 들어가면 안 되고, `243` 은 빠져 있는데 들어가야 한다.

**Q19.** 319 문제 해결법이 있나? · 디스크가 꽉차는 경우 해결책이 있나? · setFailIfSendResultIsError 키면 문제가 해결되는 건가?

> `319` : ① **재시도 대상에서 뺀다**(한 줄, 중복이 안 생긴다. 다만 실제로 들어갔을 수도 있는 것이 DLQ 로 간다) ② **멱등성을 만든다**(`insert_deduplication_token`. 우리 비복제 `MergeTree` 에서 되는지 **미확인**) ③ `ReplacingMergeTree` **는 안 된다**(아래 Q21)
>
> 디스크 : ① 빠진 코드(`243` · `745` · `439` · `565`)를 **우리가 덧붙인다**(명시적이지만 우리가 목록을 들게 된다) ② **판단을 뒤집는다**(아래 Q22)
>
> `setFailIfSendResultIsError` : **맞다.** 켜면 DLQ 발행 실패가 예외로 올라와 오프셋이 안 넘어가고 다시 시도한다. 그리고 **스프링의 `setMaxRecoveryFailures`**(상한 넘으면 버린 걸로 치고 커밋)**가 우리 3.3.16 에 없어서** 몰래 버려질 길이 애초에 없다. **정할 것이 없는 유일한 항목이다. 그냥 켠다.**

**Q20.** 방법 E에서 **스스로 안 살아난다**가 무슨 말이지?

> 리스너를 **정지**시키고 **아무도 다시 켜 주지 않는다.** 파드는 살아 있고 HTTP 도 응답하는데 **Kafka 만 더 이상 안 읽는다.** 겉으로는 멀쩡해 보인다.
>
> 다시 켜려면 ① 사람이 재시작 ② `ContainerStoppedEvent` 를 듣는 코드 ③ **헬스 지표를 만들고** liveness probe 가 파드를 죽이게 하기, 셋 중 하나가 필요하다. 그런데 스프링 부트가 **Kafka 컨슈머 헬스 지표를 기본 제공하지 않는다.** 그게 **E 의 숨은 비용**이다.

### 셋째 바퀴 : sink 와 멱등성

**Q21.** **우리도 sink 장애는 고려 안해도 되나?**

> **반대다. sink 장애가 이번 작업의 전부다.** ClickHouse 가 죽는 것이 곧 sink 장애이고 그게 `#96` 의 발단이다. 조사 결과는 **"방법을 DLQ 에서 찾지 말라"** 는 뜻이었다. 남들은 전부 고려하되 **재시도 · 버퍼링 · 멈춤**으로 한다.

**Q22.** 화이트 리스트가 있지만 **319랑 디스크 차는 경우도 추가를 하면 된다?**

> **두 가지가 반대 방향이다.** `319` 는 목록에 **있으니 빼야** 하고, `243` 은 목록에 **없으니 넣어야** 한다. "추가" 로 묶으면 안 된다.

**Q23.** ReplacingMergeTree를 사용 하면 중복된 스팬에서 발생한 집계 결과를 고칠 수가 없기에 즉 ReplacingMergeTree가 나중에 중복 문제를 해치웠다고 해도 MV는 insert 시점에서 이미 집계가 되니까 insert 전에 막아야 하니까 의미가 없는 방법이구만

> **정확하다.** 더 보탤 것이 없다.
>
> ```
> insert1  insert2            (merge 가 돈다)
>    │        │                     │
>    ├─ MV +1 ├─ MV +1              └─ spans 만 1줄로 합쳐짐
>    집계는 2 로 굳었다                 MV 는 merge 를 안 본다
> ```
>
> `#83`(`peer_service` 를 적재 **전**에 채움) · `#92`(헬스체크를 발행 **전**에 버림)와 **같은 교훈의 세 번째 사례**다.

**Q24.** 디스크의 경우는 먼저 재시도하고 10번 재시도 했는데 안되면 그 때 DLQ로 넘어간다는 거지?

> **고른 방식에 따라 다르다.** 이게 분류(5.1)와 소진 후 행동(5.2)이 **맞물려 있다**는 증거다.
>
> ```
> B 를 고르면 : 재시도 → 소진 → 복구 담당 호출 → DLQ        (결국 DLQ 로 간다)
> D 를 고르면 : 재시도 → 소진 안 함 → 계속 기다림 → DLQ 안 감 (디스크 늘리면 자동 복구)
> E 를 고르면 : 재시도 → 소진 → 컨테이너 정지 → DLQ 안 감
> ```
>
> **복구 담당을 부르는 순간 DLQ 로 가거나 버려진다.** 안 부르는 것이 안 넘어가게 하는 유일한 방법이다.

**Q25.** 단점은 진짜 독성 메시지인데 재시도 목록에 걸리면 그 한 건에 막혀 뒤가 멈춘다고 하는데 **10번으로 재시도 하면 괜찮지 않을까?**

> **맞는 생각이고, 그게 선택을 제약한다.** 상한이 있으면 잘못 분류된 독성 메시지도 결국 DLQ 로 빠져 안 막힌다. 그런데 **상한과 대기 시간이 서로 맞물린다** :
>
> | 조합 | 독성 메시지가 잘못 분류되면 | ClickHouse 가 오래 죽으면 |
> |---|---|---|
> | 뒤집기 + **상한 있음** | 상한 뒤 DLQ. **안 막힌다** | 상한 넘기면 **DLQ 로 쏟아진다** |
> | 뒤집기 + **무한** | **영원히 막힌다** | 유실 없다 |
>
> **그런데 중간이 있다.** D 는 "어떻게 기다리나" 만 정하고 "몇 번" 은 BackOff 가 정한다. 둘은 따로다 :
>
> ```
> D + ExponentialBackOff(2초 시작, 1.5배, 최대 30초, 상한 60회)
>   → 총 대기 약 30분, 리밸런스 없음
>   → 독성 메시지면 30분 뒤 DLQ (영원히는 아니다)
>   → ClickHouse 10분 장애면 유실 0
> ```
>
> **내가 5절에 D 를 "무한" 으로만 써 놓은 것이 설명을 좁혔다.** D 의 진짜 가치는 "무한" 이 아니라 **"5분 천장 없이 오래 기다릴 수 있다"** 다.

**Q26.** Kafka 리스너가 뭐지? CH가 끊기면 영원히 끊긴다는 건가? / 헬스 지표를 만들어야 한다는 거구만 **하트비트로 확인해야 하니까**

> **리스너** = Kafka 에서 메시지를 꺼내 우리 함수에 넣어 주는 스프링 부품. `@KafkaListener` 를 보고 스프링이 **리스너 컨테이너**를 만들고, 그게 `poll → onMessage → 커밋` 을 쉬지 않고 반복한다. **"리스너 정지" = 이 반복을 멈추는 것.**
>
> "영원히 끊긴다" 는 **E 를 골랐을 때만** 그렇다. B · C · D 는 ClickHouse 가 되살면 **자동으로 다시 적재한다.**
>
> **하트비트 쪽은 한 군데 다듬어야 한다.** 우리 컨슈머 실제 설정값(로그에서 뽑음) :
>
> ```
> heartbeat.interval.ms = 3000      session.timeout.ms   = 45000
> max.poll.interval.ms  = 300000    max.poll.records     = 500
> ```
>
> 하트비트는 **별도 백그라운드 스레드**가 보낸다. 그래서 우리 코드가 재시도로 꽉 막혀 있어도 **하트비트는 꼬박꼬박 나간다.** 즉 **하트비트로는 "일을 못 하고 있다" 를 못 잡는다.** 그걸 잡으려고 Kafka 가 `max.poll.interval.ms` 를 따로 둔 것이다. 같은 이유로 헬스 지표도 하트비트가 아니라 **리스너 상태를 직접** 봐야 한다(`isInExpectedState()` · `isContainerPaused()`).

### 넷째 바퀴 : 소진 · 버퍼링 · 5분 천장

**Q27.** 소진 된다는 말이 뭐지? · 재시도 → 소진 안 함 여기서 소진이 뭐지?

> **재시도 예산을 다 썼다**는 뜻이다. 영어 `exhausted` 를 옮긴 말이고 **우리 로그에 그 단어가 그대로 찍혀 있다** :
>
> ```
> Backoff FixedBackOff{..., currentAttempts=10, maxAttempts=9} exhausted for raw-0@266
>                                                              ↑ 소진
> ```
>
> **소진되는 순간 복구 담당이 불려 오고, 그때 DLQ 로 가거나 버려진다.** "소진 안 함" 은 예산을 무한으로 두거나 아주 크게 둬서(60회 × 최대 30초 = 30분) ClickHouse 재시작 정도는 예산 안에서 끝나게 하는 것이다.

**Q28.** 큐에 버퍼링 · 디스크 영속 큐 이거도 잘 모르겠고

> OTel Collector 방식이다. **보낼 데이터를 손에 들고 있다가 다시 보내는 것.** 메모리 큐는 프로세스가 재시작되면 날아가므로 `storage:` 로 디스크에 쓰는 것이 영속 큐다.
>
> **우리는 만들 필요가 없다. Kafka 가 이미 디스크 영속 큐다.**
>
> ```
> OTel Collector : 꺼낸 걸 들고 있는다 (자기 큐를 만들어야 한다)
> 우리           : 아예 안 꺼낸다 (Kafka 가 들고 있다)  ← 더 쉽고 더 안전하다
> ```
>
> 이게 **멈추는 쪽(C · D · E)이 안전한 또 다른 이유**다.

**Q29.** 5분 천장 없이 오래 기다릴 수 있다는 뭔말?

> 5분 천장 = `max.poll.interval.ms = 300000`. **기다리는 방식에 따라 poll 을 하느냐가 갈린다.**
>
> ```
> C : 리스너 스레드를 재운다 → 자는 동안 poll 을 못 한다 → 5분 넘으면 쫓겨난다
> D : pause 표시만 하고 루프는 돈다 → poll 은 계속 → 쫓아내지 않는다 → 30분도 가능
> ```

**Q30.** D방식에서 CH가 죽었을 때랑 독성 메시지를 받았을 때 어떻게 분리되서 작동하는 거지?

> **예외 타입을 보고 갈라서 각각 다른 대기 규칙을 준다.** `setBackOffFunction` 이 그걸 가능하게 한다(jar 로 확인).
>
> ```
>                       실패가 올라온다
>                  ┌──────────┴──────────┐
>     ConnectionInitiation...      ServerException(117 등)
>     ServerException(재시도 O)     protobuf 파싱 실패
>        = 저장소가 죽었다              = 데이터가 깨졌다
>                  ▼                     ▼
>     긴 BackOff + pause           재시도 0회 = 바로 소진
>     오프셋 그대로                  → 복구 담당 → raw.dlq → 커밋
>                  ▼
>     CH 되살아남 → 적재 성공 → 커밋 (DLQ 로 안 간다)
> ```
>
> **D 는 "기다리는 방법" 만 정한다.** 무엇을 기다리고 무엇을 바로 보낼지는 **분류(5.1)가 정한다.** 그래서 **D 와 DLQ 는 같이 쓰는 것**이고 둘 중 하나를 고르는 게 아니다.
>
> **내가 A~F 를 한 줄에 늘어놓아 배타적 선택처럼 보이게 쓴 것이 오해를 만들었다.** 7절을 그 구조로 다시 쓴다.

### 다섯째 바퀴 : pause 의 원리

**Q31.** D에서 pause 상태일때 어떻게 poll을 계속할 수 있는 거지?

> **`poll()` 이 하는 일이 둘이고 `pause` 는 그중 하나만 끈다.**
>
> ```
> 평소      poll() → ① 메시지 500건 받음 + ② "일하고 있어"
> pause 중  poll() → ① 0건(빈 손)      + ② "일하고 있어"   ← 이게 계속된다
> ```
>
> Kafka `Consumer` 인터페이스가 그렇게 생겼다(jar 확인) : `pause(파티션들)` · `resume(파티션들)` · `paused()` 가 `poll()` 과 **별개로** 있다. `pause` 는 연결을 끊는 게 아니라 **"이 파티션은 당분간 데이터를 주지 마" 라고 표시**하는 것이고, 컨슈머는 그룹 멤버로 그대로 남는다.
>
> 전화로 비유하면 **C 는 전화를 끊고 다시 거는 것**(상대가 끊긴 줄 알고 넘긴다)이고 **D 는 보류 버튼**(선은 연결돼 있다)이다.
>
> 스프링에서는 `ContainerPausingBackOffHandler` 가 `ListenerContainerPauseService.pause(컨테이너, Duration)` 를 부른다. **`Duration` 을 받는 게 핵심** : 그 시간 뒤에 **스스로 `resume`** 한다. 그래서 D 가 자동 복구된다.

### 이 절에서 바로잡힌 것

| 내가 틀리게 알고 있던 것 | 실제 |
|---|---|
| sink 장애는 **고려 안 해도 되나** | **반대다.** 그게 이번 작업의 전부다. DLQ 가 아닌 방법으로 다룬다 |
| `319` 과 `243` 을 **둘 다 추가**하면 된다 | **반대 방향이다.** `319` 는 제거, `243` 은 추가 |
| 디스크가 차면 **재시도 후 DLQ 로 간다** | **고른 방식에 따라 다르다.** D · E 면 DLQ 로 안 간다 |
| 컨슈머가 1개라 **리밸런스는 문제가 없다** | 작지만 있다. 빠졌다 들어오는 왕복 동안 적재가 멈춘다 |
| 리스너 정지는 **CH 가 끊기면 늘** 일어난다 | **E 를 골랐을 때만.** B · C · D 는 자동 복구된다 |
| 멈춘 것을 **하트비트로** 확인한다 | 하트비트는 별도 스레드가 계속 보낸다. **"일을 못 하고 있다" 를 못 잡는다** |

**내(AI) 설명의 허점도 둘 드러났다.** 남겨 둔다.

| 무엇이 좁았나 | 어떻게 고쳤나 |
|---|---|
| D 를 **"무한 대기"** 로만 설명했다 | D 는 "어떻게 기다리나" 만 정한다. **상한 있는 지수 백오프와 같이 쓸 수 있다**(30분 대기 + 리밸런스 없음) |
| A~F 를 **한 줄에 늘어놓아 배타적 선택처럼** 보이게 썼다 | 분류(5.1)와 DLQ(5.3)는 **어느 경우에도 필요하다.** 고르는 것은 "일시 장애일 때 얼마나 · 어떻게 기다리나" 하나다 |

---

## 7. 2차 정리

> 6 의 질문 다섯 바퀴가 끝난 뒤의 상태다. **아직 결정이 아니다.** 결정은 8 에서 승조가 내린다.

### 5절을 다시 읽으면 이 구조다

질문을 받으면서 **A~F 가 배타적 선택지가 아니라는 것**이 드러났다. 실제 모양은 이렇다.

```
                      적재 실패
                          │
            ① 분류 (5.1)  │  client-v2 가 예외 타입으로 갈라 준다. 우리가 만들 게 없다
            ┌─────────────┴─────────────┐
     저장소가 죽었다              데이터가 깨졌다
            │                           │
  ② 얼마나 · 어떻게 기다리나      ③ DLQ 로 보낸다 (5.3)
     ← 고르는 것은 이것 하나다        표준 방법이 정해져 있다
            │                           │
     되살면 적재 성공              오프셋 커밋하고 다음으로
```

**① 과 ③ 은 어느 경우에도 한다.** 분류는 라이브러리가 공짜로 해 주고, DLQ 발행은 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` 로 정해져 있다. **고를 것이 없다.**

**고르는 것은 ② 하나다.**

### 좁혀진 선택지 : ② 를 어떻게 하나

| | 어떻게 | 대기 한계 | 소진되면 | 되살면 |
|---|---|---|---|---|
| ~~A~~ | 지금 상태 | 4초 | **버린다** | : |
| ~~B~~ | 기다리지 않는다 | 없음 | DLQ 로 (원본 복제) | : |
| **C** | 스레드를 재운다 | **5분**(`max.poll.interval.ms`) | DLQ 또는 무한 | 자동 |
| **D** | pause (poll 은 계속) | **없음** | 상한을 우리가 정한다 | 자동 |
| **E** | 컨테이너를 정지 | 없음 | 정지 상태로 남는다 | **사람이** |
| ~~F~~ | 재시도 토픽으로 | 없음 | 순서 깨짐 · 전량 복제 | 자동 |

**A · B · F 는 떨어졌다.** A 는 지금 상태(유실), B 는 업계가 안 하는 방식(DLQ 가 원본 복제), F 는 파티션 내 순서가 깨지고 배치 리스너를 못 쓴다.

**C · D · E 셋이 남았고, 가르는 기준은 셋이다.**

### 결정을 가르는 기준

| 기준 | C | D | E |
|---|---|---|---|
| **얼마나 기다릴 수 있나** | 5분 안 | **제한 없음** | 제한 없음 |
| **되살면 자동으로 돌아오나** | 예 | 예 | **아니오 (사람)** |
| **이 이슈에 추가로 만들 것** | 없음 (설정 한 줄) | 빈 2개 | 빈 1개 **+ 헬스 지표** |

세 기준이 서로 맞물린다 :

- **C 를 고르면** 설정 한 줄로 끝나지만 **5분 안에 안 살아나는 장애는 못 견딘다.** 5분을 넘기면 리밸런스가 나거나 DLQ 로 가야 한다
- **D 를 고르면** 대기 시간을 우리가 정한다. 상한을 30분으로 두면 **독성 메시지가 잘못 분류돼도 30분 뒤엔 DLQ 로 빠지고**(영원히 안 막힌다) **ClickHouse 가 10분 죽어도 유실이 없다.** 대가는 빈 하나 더
- **E 를 고르면** 사고가 확실히 드러나지만 **헬스 지표를 같이 만들어야 한다.** 스프링이 Kafka 컨슈머 헬스를 기본 제공하지 않아서, 안 만들면 **멈춘 걸 아무도 모른다.** 그러면 이 이슈가 커진다

### 무엇을 정해야 하나

| | 정할 것 | 안 정하면 |
|---|---|---|
| 1 | **일시 장애일 때 C · D · E 중 무엇** | 이 이슈의 본체다 |
| 2 | **재시도 상한과 간격** (몇 분까지 기다리나) | ClickHouse 가 얼마나 죽어 있어도 견디게 할지가 안 정해진다 |
| 3 | **`319` 을 재시도에서 뺄지** | 중복 적재가 생길 수 있다. 집계가 틀어진다 |
| 4 | **분류를 뒤집을지** (모르는 실패를 재시도 쪽으로) | 디스크가 차면 **전부 DLQ 로 쏟아진다** |
| 5 | **E 를 고르면** 헬스 지표를 이 이슈에서 만들지 | 멈춘 것을 아무도 모른다 |

4 번은 ADR `#50` 이 쓴 원칙(**"틀려도 안전한 쪽을 고른다"**)과 같은 꼴이다. 거기서는 접두 일치를 버리고 정확 일치만 썼다.

### 정할 것이 없는 것 (그냥 한다)

- `setFailIfSendResultIsError` **켜기** : 안 켜면 DLQ 발행 실패가 조용히 유실된다. 안 켤 이유가 없다
- DLQ 재처리 시도 횟수를 **자체 헤더로 세기** : `stripPreviousExceptionHeaders` 기본 `true` 라 `kafka_dlt-exception-*` 를 카운터로 못 쓴다
- **`verifyPartition` 은 건드리지 않는다** : 기본 `true` 가 `raw`(3) → `raw.dlq`(1) 어긋남을 막아 준다

### 범위 밖인 것

- **DLQ 재처리 잡** : ADR `#34` 가 "같은 이미지, K8s Job, 다른 `group-id`" 로 정했다. 이 이슈는 **넣는 것까지**다
- **ClickHouse 디스크 포화 재현** : 4번 결정의 근거가 되지만 만들어 보지 않았다
- **복제 수 1 → 3** : 브로커가 죽으면 메시지가 사라지는 문제. 배포 단계

### 여기서 나온 면접 질문

1. 저장소가 죽었을 때 메시지를 DLQ 로 보내는 것과 소비를 멈추는 것 중 무엇을 골랐나. 왜 그게 더 안전한가
2. "독성 메시지" 와 "일시 장애" 를 코드에서 무엇으로 구분했나. 그 분류를 직접 만들지 않은 이유는
3. Kafka Connect 는 왜 sink 단계 실패를 DLQ 범위에서 아예 빼 놓았다고 생각하나
4. 소비를 멈추면 컨슈머 그룹 리밸런스가 나는데 어떻게 피했나. `pause` 가 `sleep` 과 무엇이 다른가
5. DLQ 를 자동으로 원본 토픽에 되돌리면 무엇이 잘못되나. 시도 횟수를 어디에 적었나
6. 유실이 일어난다는 것을 어떻게 알아냈나 (문서를 쓰다가 직접 깨뜨려 봤다)
7. 중복 적재를 `ReplacingMergeTree` 로 막을 수 없는 이유는 무엇인가 (MV 는 insert 만 보고 merge 를 안 본다)
8. 하트비트가 정상인데도 컨슈머가 그룹에서 빠질 수 있다. 왜인가

---

## 8. 구현 프롬프트 (사람 차례)

> 7 을 읽고 승조가 결정을 글로 내린 것. **이 글이 곧 설계다.**
> 같은 원문이 [`prompts.md`](prompts.md) 와 ADR `#51` 안에도 있다.
> 2026-10-06 작성. 질문 31개(3절 · 6절)를 거친 뒤에 쓴 글이다.

```
선택은 D로 가자.

재시도가 발생했을 때 5분 천장 없이 우리가 정한 만큼 기다릴 수 있고 방법 E와 다르게 자동
복구도 되며 따로 헬스 지표를 만들 필요 없이 DLQ 문제를 해결할 수 있기 때문이다.

방법 C 같은 경우 방법 D 대비 추가로 만들 것이 없지만 재시도가 발생하면 그동안 poll 호출
자체가 안되어서(적재 처리기에 있는 리스너 스레드가 잠들어있어서) 5분 동안
(max.poll.interval.ms가 300000인데 우리가 설정한 게 아니라 기본값이다) 응답이 없으면
카프카가 해당 컨슈머를 그룹에서 빼고 리밸런싱하고 시간이 지나 다시 돌아오면 또
리밸런싱하게 되니까 리밸런싱하는 동안 적재 처리기로 가는 업무가 멈추고 마지막 커밋
지점부터 다시 처리하게 되니까 컨슈머 랙도 발생하게 되는 거고 나중에 적재 처리기가 여러
대로 늘어나면 남의 파티션까지 흔들려서 그만큼 더 커지니까 C는 안 하는 방향이 맞는 거
같다.

방법 E는 헬스 지표를 만드는 것이 빈을 하나 추가하는 것보다 시간적으로 문제가 있고 헬스
지표가 없으면 파드는 Running이고 HTTP도 200을 돌려주는데 리스너만 멈춘 상태가 되니까
아무도 모르는 일이 발생할 수 있다. 파드가 아예 죽으면 쿠버네티스가 새로 띄워주는데
리스너만 멈추면 그 안전망이 작동을 안 하는 거라서 더 위험하다. 그러니 리스크가 조금 더
적은 D로 하는 것으로 정하자.

재시도 상한과 간격은 실패 종류마다 버티는 시간을 다르게 줘서 세 단으로 나누자.
setBackOffFunction으로 예외마다 다른 BackOff를 줄 수 있으니까 가능하다.

확실한 일시 장애는 2초로 시작해서 2배씩 늘리고 한 번 대기는 최대 30초, 전체 10분까지
기다린다. ConnectionInitiationException이랑 ServerException 중 isRetryable()이 true인
것들이 여기 들어가고, 거기에 243 NOT_ENOUGH_SPACE랑 745 SERVER_OVERLOADED,
439 CANNOT_SCHEDULE_TASK, 565 TOO_MANY_PARTITIONS를 우리가 올려서 넣는다. 이 네 개는
라이브러리 목록에 없는데 성격상 일시 장애라서 그렇다.

모르는 실패는 같은 모양으로 전체 1분까지만 기다린다.

확실한 독성은 FixedBackOff(0, 0)으로 재시도 없이 바로 DLQ로 보낸다.

10분으로 잡은 이유는 컨테이너 재시작이 10초에서 30초고 파드 재배치가 1분에서 2분,
노드 장애로 재스케줄되는 게 3분에서 5분이라 그걸 다 견디는 선이기 때문이다. 그보다 긴
장애는 대개 사람이 손을 써야 하는 일이라서 그 선에서 DLQ로 넘기고 쌓이게 해서 신호로
쓰는 게 낫다. OTel Collector도 같은 모양으로 5초 시작에 30초 상한, 전체 5분을 쓰는데
우리는 Kafka가 디스크에 들고 있으니까 더 길게 가도 된다.

그리고 FailedRecordTracker가 레코드마다, 정확히는 오프셋마다 예산을 따로 세기 때문에
ClickHouse가 1시간 죽어 있어도 DLQ로 가는 건 10분에 한 건씩이고 나머지는 Kafka에 그냥
남아 있다. DLQ가 원본 복제가 되는 일은 바로 DLQ로 보내는 B 방식에서만 생기는 거라서
이것도 D를 고른 이유다.

319 UNKNOWN_STATUS_OF_INSERT는 화이트리스트에서 제거하는 방향으로 가자. 재시도하면
중복 문제가 발생하고 MV 입장에서도 중복이 insert되는 순간 이미 집계에 더해지니까 나중에
집계가 이상해진다. ReplacingMergeTree로 spans 중복을 합쳐도 MV는 merge를 안 보니까
집계는 안 고쳐진다. 결국 #83이랑 #92에서 배운 거랑 같은 얘기인데 집계에 영향 가는 수정은
insert 전에 해야 한다는 거다. 멱등하게 만드는 insert_deduplication_token도 있지만 우리
spans는 ENGINE = MergeTree라서 복제가 아니고, 그러면 그 기능이 이 엔진에서 어느 버전부터
어떤 설정과 같이 동작하는지 확인이 안 되기 때문에 되는지 모르는 기능에 기대지 않고
간단하면서 확실한 방법으로 하는 게 좋을 거 같다. 확인 안 한 건 「확인 못 한 것」에 남긴다.

디스크가 꽉 차게 되었을 때는 그 때 나오는 243을 화이트리스트에 추가하는 것보다 분류
자체를 뒤집는 방법으로 가보자. 지금은 재시도할 것 목록을 들고 목록에 없는 걸 DLQ로
보내는데 이걸 DLQ로 보낼 것 목록만 들고 목록에 없으면 재시도하는 쪽으로 바꾸는 거다.

이유가 두 가지인데 하나는 들어야 하는 목록이 짧고 안정적이라서다. 우리가 하는 일이
JSON 한 줄을 받아서 표에 꽂는 거니까 데이터가 틀릴 수 있는 방식이 정해져 있는데
117 INCORRECT_DATA, 27 CANNOT_PARSE_INPUT_ASSERTION_FAILED, 53 TYPE_MISMATCH,
41 CANNOT_PARSE_DATETIME, 72 CANNOT_PARSE_NUMBER 정도고 ClickHouse가 새 기능을 추가해도
이 목록은 거의 안 늘어난다. 반대로 서버가 지금 못 받는 이유는 서버가 복잡해질수록
늘어나니까 지금 방식대로 가면 다른 이유로 생기는 오류마다 우리가 알아채서 화이트리스트를
건드려야 하는 일이 발생하고 빠뜨리면 그 코드가 바로 DLQ로 가서 조용히 쌓인다.

다른 하나는 모를 때 틀리는 방향이 안전하다는 거다. 독성인데 일시 장애로 보면 1분 막히고
그 뒤에 DLQ로 가니까 데이터는 하나도 안 잃는데, 반대로 일시 장애인데 독성으로 보면
DLQ로 쌓이고 재처리 잡이 아직 없으니까 사람이 손으로 되돌려야 한다. 한쪽 실수는 시간만
잃고 다른 쪽은 데이터를 잃으니까 모를 때는 시간을 잃는 쪽으로 틀리는 게 맞다.
ADR #50에서 접두 일치 버리고 정확 일치만 쓴 것도 같은 이유였다.

그리고 뒤집기를 바닥에 깔면 위에서 243 같은 네 개 코드를 긴 쪽으로 올리는 일의 성격도
바뀐다. 목록을 빠뜨려도 바로 DLQ로 가는 게 아니라 모르는 실패 쪽으로 떨어져서 1분은
재시도하니까 목록이 없으면 유실되는 게 아니라 있으면 더 좋은 정도가 된다.

setFailIfSendResultIsError는 켠다. 안 켜면 DLQ 발행 자체가 실패했을 때 스프링이 보낸
것으로 치고 넘어가서 조용히 유실되는데 켜면 예외가 올라와서 오프셋이 안 넘어가고 다시
시도한다. 스프링에 setMaxRecoveryFailures라고 상한 넘기면 버린 걸로 치고 커밋하는 설정이
있는데 우리 spring-kafka 3.3.16에는 없으니까 몰래 버려질 길도 없다.

DLQ 레코드에는 x-dlq-attempt 같은 자체 헤더를 심어서 이 레코드가 DLQ를 몇 번 거쳤는지
센다. DeadLetterPublishingRecoverer의 setHeadersFunction으로 하면 되는데 자체 헤더를
써야 하는 이유는 stripPreviousExceptionHeaders 기본값이 true라서 kafka_dlt-exception-*
헤더는 매번 덮어써져서 카운터로 쓸 수 없기 때문이다. 이번 이슈에서는 헤더를 심어두는
것까지만 하고 몇 번이면 포기할지는 재처리 잡 만들 때 정하자. 보통 3번에서 5번 정도
쓴다고 한다. 적재 재시도 횟수는 메모리에서 BackOff가 세는 거고 DLQ를 거친 횟수는 헤더에
남는 거라서 둘은 다른 카운터다.

verifyPartition은 건드리지 않는다. 기본값이 true라서 보내기 전에 목적지 토픽의 파티션
수를 확인하고 그 번호가 없으면 파티션 번호를 비워서 Kafka가 알아서 고르게 한다.
raw가 3개고 raw.dlq가 1개라 파티션 1이랑 2에서 실패한 것도 문제없이 들어간다.
바이트코드에서 iconst_1로 초기화되는 걸 확인했다.

DLQ 재처리 잡은 이번 이슈 범위 밖이다. ADR #34가 같은 이미지에 K8s Job으로 돌리기로
정했고 group-id만 ingester가 아닌 다른 이름을 써야 하는데 같은 이름을 쓰면 책갈피를
공유해서 서로 남의 자리를 밀어버린다. 이번 이슈는 raw.dlq에 넣는 것까지만 한다.

되돌리는 조건은 세 가지다. 10분 넘는 ClickHouse 장애가 반복돼서 DLQ에 쌓이는 양이 사람이
처리할 수 없게 되면 대기 시간을 늘리거나 재처리 잡을 앞당긴다. 모르는 실패로 분류된 독성
메시지 때문에 1분 멈추는 일이 자주 보이면 그 코드를 DLQ 목록에 추가한다. 적재 처리기를
여러 대로 늘렸을 때 pause가 파티션 단위로 동작해서 한 파티션 장애가 다른 파티션까지
멈추는 게 확인되면 pausePartition으로 좁힌다.

그러니 그렇게 구현해줘.
```

**이 프롬프트가 결정의 4요소를 그대로 담고 있다.** 채택(D + 3단 분류 + `319` 제거 + 뒤집기)과 기각 둘(C 는 리밸런스, E 는 리스너만 멈춰 안전망이 안 걸림)이 각각 사유와 함께 있고, 마지막 문단이 되돌리는 조건 셋이다. 그래서 이 글이 곧 ADR `#51` 이고, 구현은 이 글을 코드로 옮기는 것이다.

### 결정이 선택지 어디에 닿았나

| 7절에서 정할 것 | 결정 |
|---|---|
| 1. 일시 장애일 때 C · D · E 중 무엇 | **D** (`ContainerPausingBackOffHandler`) |
| 2. 재시도 상한과 간격 | **3단** : 확실한 일시 장애 10분 · 모르는 실패 1분 · 확실한 독성 0. `ExponentialBackOff(2초, 2.0배, 최대 30초)` |
| 3. `319` 을 재시도에서 뺄지 | **뺀다.** 멱등성은 `MergeTree` 에서 되는지 몰라 안 쓴다 |
| 4. 분류를 뒤집을지 | **뒤집는다.** DLQ 목록만 들고 나머지는 재시도. `243` · `745` · `439` · `565` 는 긴 쪽으로 올린다 |
| 5. E 를 고르면 헬스 지표를 만들지 | **해당 없음** (E 를 안 골랐다) |
