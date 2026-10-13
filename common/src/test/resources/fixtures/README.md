# raw 토픽 실데이터 표본 (개발환경 9단계 · `#167` · ADR `#65`)

쇼핑몰이 실제로 흘린 Kafka `raw` 메시지의 **값 바이트 그대로**다. 세 신호 하나씩.

| 파일 | 신호 | 보낸 서비스 | 크기 | 안에 든 것 |
|---|---|---|---|---|
| `traces.pb` | `traces` | `shop-inventory` | 2,791바이트 | JDBC CLIENT 스팬 3개 (`io.opentelemetry.jdbc` 2.31.1-alpha) |
| `metrics.pb` | `metrics` | `shop-payment` | 7,859바이트 | 지표 19개. `jvm.gc.duration` 히스토그램(버킷 5) · `jvm.cpu.recent_utilization` 게이지 · 나머지 합계 |
| `logs.pb` | `logs` | `shop-payment` | 2,324바이트 | 로그 8줄. `severity_text` INFO · `severity_number` 9 |

## 왜 레포에 두나

가짜 발신기(telemetrygen)가 보내는 모양은 실제 에이전트와 다르다. 적재 처리기의 변환 규칙은 **실데이터 모양**을 전제로 짜여 있다 : 히스토그램 펴기(`#64`) · 로그 등급 정본(`#144`) · 파드 이름표(`#30` · `#58`). 개발환경 계획 9단계가 "telemetrygen 데이터만으로 고정하지 않는다" 고 적은 이유다.

비밀값이 아니다. 우리가 만든 쇼핑몰이 로컬에서 흘린 것이고, 안에 든 것은 JVM 지표 · 스프링 기동 로그 · JDBC 스팬이다.

## 언제 다시 뜨나

- OTel Java Agent 버전을 올렸을 때 (`monimo-shop` `otel/AGENT_VERSION`)
- 쇼핑몰이 꼬리표 · 계측을 바꿨을 때
- 메시지 형식을 바꾸기로 했을 때 (그때는 ADR `#65` 를 먼저 고친다)

바꿀 일이 없으면 그대로 둔다. 표본이 낡아도 깨지지 않는다 : 시험이 보는 것은 "바이트가 왕복하나" 와 "적재가 쓰는 칸이 들어 있나" 이고 특정 값이 아니다.

## 어떻게 뜨나

1. 백엔드와 쇼핑몰을 띄운다

   ```
   docker compose --profile collector --profile ingester up -d --wait --build
   cd ../monimo-shop && docker compose -f docker-compose.dev.yml up -d --wait
   ```

2. 손님을 흘린다 (세 신호가 다 나오게 조회와 주문을 섞는다. 지표는 10초 주기다)

   ```
   curl localhost:8090/api/stock/P-100
   curl -X POST localhost:8090/api/orders -H 'Content-Type: application/json' \
     -d '{"productId":"P-100","quantity":1,"amount":15000}'
   ```

3. `raw` 토픽에서 신호별로 한 건씩 떠서 이 폴더에 `<키>.pb` 로 쓴다. `localhost:19092`(compose 가 호스트에 연 Kafka)에 붙어 키가 `traces` · `metrics` · `logs` 인 메시지를 모으고, **resource 꼬리표의 `service.name` 이 `shop-` 으로 시작하는 것만** 고른다. 그 조건이 가짜 발신기 메시지를 걸러 낸다.

   `#167` 에서는 `ingester` 시험 자리에 소비자를 한 번 띄워 떴고(`CaptureFixtures`, 머지 전에 지웠다) 같은 일을 하는 어떤 도구를 써도 된다. 중요한 것은 **값 바이트를 한 글자도 고치지 말고 파일로 쓰는 것**이다. 글자로 바꿔 저장하면 왕복 시험이 뜻을 잃는다.

4. 다시 떴으면 위 표의 크기 · 내용을 고치고 `./gradlew :common:test` 를 돌린다
