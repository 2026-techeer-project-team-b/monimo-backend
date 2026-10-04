# 2026-10-04 적재 실패분을 raw.dlq 로 (이슈 #96)

- 관련 PR / 이슈: 이슈 `#96` · 브랜치 `fix/96-raw-dlq`
- 쓴 도구: 웹 리서치 서브에이전트 **2개 동시**(+ 잘린 답 재요청 2회), 로컬 재현(ClickHouse 정지), jar 직접 확인(`javap` · `unzip` · 바이트코드)
- 결과물: (구현 전. 지금은 리서치 카드까지)

## 프롬프트

조사를 두 갈래로 나눴다. 하나로 보내면 답이 60줄 제한에 걸려 어느 한쪽이 얕아진다.

### 조사 A (Spring Kafka 쪽) : 서브에이전트에게

```
리서치 요청. 한국어로 답한다. 링크를 못 찾은 주장은 반드시 "미확인" 이라고 표시한다.
출처 순서: 공식 문서(Spring Kafka reference) → 오픈소스 코드 → 기술 블로그.

## 이미 아는 것
- Spring Boot 3.5 / Kotlin. 적재 처리기가 Kafka `raw` 토픽을 `@KafkaListener` 로 소비해
  OTLP protobuf 를 풀고 ClickHouse 에 넣는다. ByteArrayDeserializer, group-id `ingester`,
  auto-offset-reset `earliest`.
- `application.yml` 에 에러 핸들러 설정이 **없다** → 스프링 기본값 `DefaultErrorHandler`
  = `FixedBackOff(interval=0, maxAttempts=9)`. 10번 시도 후 그 레코드를 **건너뛰고 오프셋을 넘긴다.**
- 직접 재현함: ClickHouse 를 멈추면 `java.net.UnknownHostException: clickhouse` →
  `Backoff FixedBackOff{interval=0, currentAttempts=10, maxAttempts=9} exhausted for raw-0@264`
  → 되살려도 그 메시지는 **영영 안 들어온다**(조회 0건).
- 우리 설계(ADR)는 "CH 에 넣지 못한 메시지(파싱 실패 · CH 거절 · 스키마 위반)를 Kafka
  `raw.dlq` 토픽(보관 30일)으로 보낸다" 로 정해 뒀다. 토픽은 이미 있고 넣는 코드가 없다.
- 핵심 고민: **독성 메시지**(몇 번 넣어도 실패)와 **일시 장애**(CH 가 죽어 전부 실패)는
  다르게 다뤄야 한다. 일시 장애에 DLQ 로 보내면 스트림 전체가 DLQ 로 쏟아진다.

## 알고 싶은 것 (4개)
1. Spring Kafka 에서 소비 실패 레코드를 다른 토픽(DLQ)으로 보내는 표준 방법.
   `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` 의 설정 꼴(Kotlin 이면 더 좋다),
   기본 대상 토픽 이름 규칙(`<topic>.DLT`)을 우리 `raw.dlq` 로 바꾸는 방법,
   DLQ 레코드에 자동으로 붙는 헤더 목록.
2. **일시 장애와 독성 메시지를 어떻게 구분하나.** Spring Kafka 가 제공하는 수단을
   비교해 달라: 예외 분류(`addNotRetryableExceptions` / `setClassifications` /
   `BackOffHandler`), 무한 BackOff, `CommonContainerStoppingErrorHandler`,
   `MessageListenerContainer.pause()`, `RetryableTopic`(non-blocking retry).
   각각 "일시 장애일 때 유실이 없는가 / 컨슈머가 어떻게 되는가 / 되살아나면 자동 복구되나" 로.
3. 일시 장애에 **소비를 멈추는** 쪽을 고를 때의 부작용: `max.poll.interval.ms` 초과로
   리밸런스가 일어나는가, 컨테이너가 멈춘 뒤 자동으로 다시 시작되는가(아니면 사람이?),
   헬스체크(`/actuator/health`)에 멈춘 상태가 드러나는가.
4. DLQ **재처리** 표준 방법과 무한 루프 방지. DLQ 를 원본 토픽으로 되돌리는 방식,
   헤더로 시도 횟수를 세어 N회 넘으면 버리는 패턴, 한 번만 돌고 끝나는 배치(K8s Job)로
   만들 때 주의점.

## 답의 형식
질문 2는 **표 하나**로: | 수단 | 일시 장애 때 유실 | 컨슈머 상태 | 자동 복구 | 쓰는 곳(출처 링크) |
나머지는 질문별 3~6줄 + 코드 조각(있으면). 마지막에 "확인 못 한 것" 목록. 전체 70줄 이내.
```

### 조사 B (ClickHouse 예외 · 업계 비교) : 서브에이전트에게

```
리서치 요청. 한국어로 답한다. 링크를 못 찾은 주장은 반드시 "미확인" 이라고 표시한다.
출처 순서: 공식 문서 → 오픈소스 코드(GitHub) → 기술 블로그.

## 이미 아는 것
- 적재 처리기(Kotlin · Spring Boot 3.5)가 Kafka `raw` 를 소비해 ClickHouse 에 넣는다.
  ClickHouse 클라이언트는 **`com.clickhouse:client-v2` 0.10.0** (JDBC 아님, HTTP 기반 v2 클라이언트).
- 넣는 방식은 JSONEachRow 형식으로 insert.
- ClickHouse 가 죽어 있을 때 `java.net.UnknownHostException: clickhouse` 가 올라왔다.
- 하려는 것: 실패를 **"일시 장애(저장소가 죽음 · 네트워크)"** 와 **"독성 메시지(이 데이터가
  틀렸음)"** 로 **예외 타입/에러코드로 자동 분류**해서 전자는 재시도 · 후자는 DLQ 로 보내고 싶다.

## 알고 싶은 것 (3개)
1. **`client-v2` 가 던지는 예외 종류.** `ClickHouseException` 의 구조(에러 코드를 들고 있나),
   "서버에 닿지 못함"(연결 거부 · DNS 실패 · 타임아웃)과 "서버가 데이터를 거절함"(타입 불일치 ·
   컬럼 없음 · CHECK 위반 · 파싱 실패)이 각각 어떤 예외 · 어떤 서버 에러코드로 오나.
   예외만 보고 둘을 가를 수 있는지, 가를 수 없으면 무엇을 봐야 하는지.
2. ClickHouse 서버 에러코드 중 **"데이터가 틀렸다"에 해당하는 번호**(예: `TYPE_MISMATCH` ·
   `CANNOT_PARSE_*` · `UNKNOWN_IDENTIFIER` · `TOO_LARGE_STRING_SIZE`)와
   **"지금은 못 받는다"에 해당하는 번호**(예: `TOO_MANY_PARTS` · `MEMORY_LIMIT_EXCEEDED` ·
   `NOT_ENOUGH_SPACE` · 읽기 전용)를 구분해 달라. 공식 에러코드 목록 링크 포함.
3. **다른 파이프라인은 sink(저장소) 장애 때 DLQ 로 보내나 멈추나.** 각각의 기본 동작과
   권장 설정을 비교: Kafka Connect(`errors.tolerance` · `errors.deadletterqueue.*`),
   OpenTelemetry Collector(`sending_queue` · `retry_on_failure` · persistent queue),
   Debezium, Apache Flink, SigNoz 또는 유사 관측 파이프라인.
   특히 "일시 장애에 DLQ 로 보내는 것을 권하지 않는다" 는 문구가 공식 문서에 있는지 찾아 달라.

## 답의 형식
질문 3은 **표 하나**로: | 도구 | sink 장애 기본 동작 | DLQ 로 보내나 | 멈추나 · 버퍼링하나 | 출처 링크 |
질문 1~2 는 표 또는 목록 + 에러코드 번호. 마지막에 "확인 못 한 것". 전체 60줄 이내.
```

### 되물은 말 (둘 다 답이 잘려서)

```
Q3 표가 전송 중 잘렸습니다. Q3 만 다시 보내 주세요 (Q1 · Q2 는 잘 받았습니다).
...
2. "일시 장애에 DLQ 로 보내는 것을 권하지 않는다" 에 해당하는 공식 문서 문구를 찾았는지.
   찾았으면 그 문장과 링크를, 못 찾았으면 "미확인" 이라고 명시해 주세요.
```

## 다섯 칸은 어디에 있었나

| 칸 | 프롬프트에 | 다른 곳에 |
|---|---|---|
| 목표 | 있다 (알고 싶은 것 4~3개로 번호를 붙여 적었다) | : |
| 배경 | 있다 (「이미 아는 것」 : 버전 · 설정 · 재현 로그 · ADR 결정) | 이슈 `#96` 본문 |
| 범위 | 있다 (질문 번호 밖은 묻지 않았다) | : |
| 제약 | 있다 (**"링크를 못 찾은 주장은 미확인이라고 표시"** · 출처 순서 · 줄 수 상한) | `docs/seungjo/README.md` 규칙 |
| 완료 기준 | 있다 (「답의 형식」 : 어느 질문을 표로 받을지까지 지정) | : |

## 결과

- 한 번에 됐나: **조사 내용은 예, 전송은 아니오.** 두 답 모두 마지막 질문 표에서 잘려 다시 요청했다
- 고쳐 물은 횟수: 2 (각 조사 1회, 잘린 부분만 범위를 좁혀 재요청)
- 무엇이 잘못 나왔나:
  - 조사 A 가 "DLQ 파티션이 원본보다 적으면 **실패한다**" 고 경고 → `verifyPartition` 기본값이 `true` 라 실제로는 막아 준다 (바이트코드 `iconst_1` 로 확인)
  - 조사 A 가 `setMaxRecoveryFailures` 함정을 "반드시 의식해야 한다" 고 했으나 **spring-kafka 3.3.16 에 없는 API** 였다 (A 도 "미확인" 표시는 해 뒀다)
  - 내가(메인 대화) 기본 접미사를 `.DLT` 로 알고 있었는데 A 의 `-dlt` 가 맞았다
- 고쳐 물은 말: 위 「되물은 말」. **범위를 좁혀 다시 묻는 것**이 전체를 다시 받는 것보다 싸다

## 다음에 바꿀 점

「답의 형식」 칸에 줄 수 상한만 주고 **전송 단위**를 안 줬다. 표가 큰 질문이 마지막에 오면 그 표에서 잘린다.
다음부터는 "표가 길면 질문별로 나눠 보내 달라" 를 형식 칸에 넣는다. 또는 **표를 앞 질문에 배치**한다.

그리고 이번에 효과가 컸던 것은 **"링크를 못 찾은 주장은 미확인이라고 표시하라"** 를 제약 칸에 둔 것이다.
두 조사 모두 「확인 못 한 것」 을 5~8건 솔직히 적어 왔고, 그중 **3건을 내가 jar 로 직접 확인해 결론을 바꿨다.**

## 숫자 (잴 수 있었으면)

총 토큰 : 못 쟀다 (서브에이전트별 사용량을 따로 안 봤다)
왕복 횟수 : 조사 A 2회 · 조사 B 2회 · 로컬 재현 1회 · jar 확인 4회
걸린 시간 : 약 1시간
