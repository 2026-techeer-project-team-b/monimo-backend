# 헬스체크 스팬을 어디서, 무엇으로 거르나

- 날짜 2026-10-04 / 걸린 시간 약 1시간 (웹 리서치 에이전트 1개 + 로컬 ClickHouse · PG 조회) / 이슈 `#92`
- 알고 있던 것 (세 줄):
  - 쇼핑몰이 5초마다 받는 `GET /actuator/health` 를 OTel Java 에이전트가 SERVER 스팬으로 만들어 보낸다. `always_on` 이라 전부 온다
  - ClickHouse MV(`mv_service_health_1m` · `mv_transactions` → 히트맵 · `mv_url_stats_1m`)에 이름을 거르는 조건이 없어 호출 수 · 에러율 · P95 · 히트맵에 섞인다
  - MV 는 insert 시점에 돌아 조회가 나중에 못 고친다 (`#83` 과 같은 이유). `spans` 에 들어가기 전에 빼야 한다
- 알고 싶었던 것: ① 에이전트 자체에 경로 제외 설정이 있나 ② 다른 APM(Pinpoint · SigNoz · OTel Collector)은 어디서 무엇으로 거르나 ③ `span_name` · `http.route` · `url.path` 중 무엇으로 맞춰야 안전한가 ④ 거를 목록을 어디 두나

## 물은 것

웹 리서치 에이전트에게 "이미 아는 것 / 알고 싶은 것 5개 / 답의 형식(표 + 질문별 요약 + 미확인 목록)" 을 주고 보냈다. 프롬프트 원문은 [`docs/prompts/`](../prompts/README.md) 의 `#92` 로그에.

## 나온 선택지

| 방법 | 어디서 | 무엇으로 매칭 | 장점 | 단점 | 출처 |
|---|---|---|---|---|---|
| **A. 우리 수집기에서 버림** (Kafka 발행 전) | 수집기 | `url.path` (+ 보조로 `user_agent.original`) | 앱 재배포 없이 한 곳에서. MV 세 개 + 저장 비용 한 번에 해결. OTel Collector 의 filter processor 자리를 우리 수집기가 가져가는 것 : ADR `#14` `#33` 과 맞음 | 버린 스팬은 영영 못 본다. 목록을 잘못 쓰면 진짜 트래픽이 사라진다 | [filterprocessor README](https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/processor/filterprocessor/README.md) · [SigNoz drop-spans](https://signoz.io/docs/traces-management/guides/drop-spans/) |
| B. ClickHouse MV 3개에서 거름 | 저장 | `span_name` 또는 `attributes['url.path']` | `spans` 원본은 남는다 | MV 3개 drop · recreate(DDL 변경), 지난 줄은 어차피 안 고쳐짐, Kafka · CH 저장 비용 그대로 | : (우리 DDL `db/clickhouse/004_*.sql`) |
| C. 에이전트 선언형 설정 `rule_based_routing` 샘플러 (`action: DROP`) | 에이전트 | `url.path` 정규식 + `span_kind: SERVER` | 네트워크 · 수집기 비용 0. OTel 메인테이너가 권하는 방식 | **실험적** · 3.0 에서 제거 예정(PR #20249 로 deprecated). 설정 파일을 쓰면 **env · 시스템 속성 설정이 전부 무시**돼 OTLP 엔드포인트까지 YAML 로 옮겨야 함. 쇼핑몰 4개마다 적용 → "에이전트만 붙이면 된다"(ADR `#33`) 와 어긋남 | [OTel 선언형 설정](https://opentelemetry.io/blog/2025/declarative-config/) · [Java 설정](https://opentelemetry.io/docs/languages/java/configuration/) · [PR #20249](https://github.com/open-telemetry/opentelemetry-java-instrumentation/pull/20249) |
| D. 커스텀 `Sampler` 확장 jar | 에이전트 | 자유 | 로직 자유 | 직접 빌드 · 배포 · 유지. C 와 같은 "에이전트마다" 문제 | [discussion #6605](https://github.com/open-telemetry/opentelemetry-java-instrumentation/discussions/6605) |
| E. 헬스체커가 `traceparent` 에 `sampled=0` 을 붙여 보냄 | 호출자 | : | 앱 · 수집기 손 안 댐 | 도커 · kubelet 프로브에 헤더를 못 넣는다 | [discussion #6605](https://github.com/open-telemetry/opentelemetry-java-instrumentation/discussions/6605) |
| (참고) Pinpoint `profiler.tomcat.excludeurl` | 에이전트 | Ant 스타일 URL 패턴 (`/aa/*.html`, `**`) | 설정 한 줄, 검증된 방식 | WAS 플러그인별 속성이 따로(`profiler.jetty.*` …). 내장 톰캣에서 안 먹는 사례(issue #5942) | [tomcat 플러그인 README](https://github.com/pinpoint-apm/pinpoint/tree/master/agent-module/plugins/tomcat) |
| (참고) OTel Collector `tail_sampling` `type: drop` | 수집기 | `string_attribute key: url.path` | 트레이스 단위라 안 깨짐 | 버퍼링(메모리 · 지연) | [SigNoz tail sampling](https://signoz.io/docs/traces-management/guides/tail-sampling/) |

**질문 ① 답**: OTel Java 에이전트에 "이 경로는 스팬 만들지 마라" 는 **안정된 전용 속성이 없다.** `otel.instrumentation.http.*` 계열에 경로 제외는 없고, 메인테이너도 discussion #6605 에서 선언형 설정 샘플러(C)를 권한다. Pinpoint 는 에이전트에서 빼는 쪽(`excludeurl`)을 골랐지만 우리는 기성 에이전트를 쓰므로 그 자리에 손을 못 댄다.

**질문 ③ 답 : 매칭 키**: semconv HTTP 는 v1.23.0(2023-11)에 stable. `url.path` 는 **Required** 라 항상 있고, `http.route` 는 **Conditionally Required**("가능한 경우만") 라 프레임워크가 못 주면 **합법적으로 비어 있을 수 있다**(그 경우 URI path 로 대체하지 말라고 규약이 명시). 스팬 이름은 `{method} {http.route}` 라 route 가 없으면 `GET` 만 남는다. 그러므로 `span_name` 은 쓰지 않고 `url.path` 를 1순위로. SigNoz 예시도 `url.path` 를 쓴다. `http.target` 은 구 semconv 이름이라 후보에서 뺀다.

## 우리 데이터로 확인한 것 (로컬 ClickHouse · PG, 2026-10-04)

| 확인 | 값 |
|---|---|
| 헬스체크 스팬이 `spans` 에 있나 | **있다.** shop-gateway 18 · shop-order 18 · shop-payment 21건, 전부 `span_kind = SERVER` |
| 보낸 에이전트 | PG `agents` 표로 확인 : **2.31.1** (16:18~16:20 등록된 실제 파드) |
| 어떤 속성이 있나 | `url.path = /actuator/health` · `http.route = /actuator/health` · `span_name = GET /actuator/health` · `http.request.method = GET` · `http.response.status_code = 200` · `user_agent.original`(curl) · `client.address` 등 14개 |
| 지속시간 | 평균 **11ms** · p95 **121ms** : "2ms 짜리" 가 아니다. actuator 가 DB 핑을 하므로 느릴 수 있고, 첫 호출(워밍업)이 섞여 있다 |
| `service_health_1m` 에 섞였나 | **섞였다.** shop-* 253분 |
| 로컬 비율 | SERVER 스팬의 **0.1%** : 가짜 데이터(서비스당 21,083건, 전부 14:07:33 에 seed) + k6 부하가 압도. "평소엔 거의 전부 헬스체크" 는 **쇼핑몰만 켜고 부하가 없을 때** 얘기다. PR 에 전후 수치를 적으려면 그 조건으로 재야 한다 |

**리서치 에이전트가 틀렸던 것 (남겨 둔다)**: 처음 답은 "`http.route` 는 Spring Boot actuator 에서 비어 있었고 2.32.0(issue #20215 · PR #20216)에서 채워졌다" 였다. 우리 2.31.1 데이터와 맞지 않아 되물었더니 정정 : 그 이슈는 **starter(라이브러리) 방식**의 라우트 탐지 경로(`OpenTelemetryHandlerMappingFilter` 의 `RequestMappingHandlerMapping` 허용목록) 문제이고, `-javaagent` 는 `HandlerAdapterInstrumentation` 이 Spring 의 `BEST_MATCHING_PATTERN_ATTRIBUTE` 를 읽는 **둘째 경로**가 있어 actuator 도 채운다. 재현 환경도 Boot 4.0.5 + starter 2.31.1 이었다. 실데이터와 대조하지 않았으면 "`http.route` 는 못 믿는다" 로 잘못 결론 났을 것이다. ("둘째 경로가 보조" 라는 해석은 코드 구조상 추론 : 부분 미확인)

## 고른 것과 이유 (확정, 2026-10-04)

**A. 수집기에서 `url.path` 로 버린다. 목록은 수집기 전역 env.**

버린 자리와 사유:
- **B(MV 에서 거름)**: MV 3개 DDL 을 바꿔도 지난 줄은 안 고쳐지고(MV 는 새 줄만 본다), Kafka · ClickHouse 비용은 그대로. 고칠 곳이 셋이라 하나 빠뜨리기 쉽다 (이번에 히트맵이 `transactions` 를 거친다는 걸 처음엔 못 봤다)
- **C · D(에이전트)**: 쇼핑몰 4곳에 설정 파일이나 jar 를 얹어야 하고 서비스가 늘면 또 늘어난다. "에이전트만 붙이면 된다"(ADR `#33`)가 깨진다. C 는 실험적이고 3.0 에서 제거 예정이며, 설정 파일을 쓰면 env 설정이 전부 무시된다
- **E(호출자가 `sampled=0`)**: 찌르는 쪽(도커 · 카나리 · k6) 중 하나라도 빠뜨리면 분리가 안 된다. 헤더를 넣는 것 자체는 가능하지만 "모두가 매번" 이 어렵다

**매칭 키 `url.path`**: 규약상 Required 라 항상 있다. `http.route` 는 우리 셋업(javaagent + Spring MVC)에서는 채워지지만 Conditionally Required 라 WebFlux · 게이트웨이 · starter 로 바꾸면 비어도 규약 위반이 아니다. 스팬 이름은 `{메서드} {http.route}` 조합이라 route 가 없으면 `GET` 만 남아 더 약하다.

**정확 일치만**. 접두 일치는 쓰지 않는다. `/a` 같은 값이 들어가면 `/api/orders` 가 전부 사라진다. 틀려도 안전한 쪽(안 버리는 쪽)을 고른다.

**SERVER 만**. CLIENT 는 파수꾼 · 게이트웨이가 남의 `/health` 를 호출한 진짜 기록이고 서버맵 화살표에 필요하다.

**목록은 수집기 전역 env** `MONIMO_COLLECTOR_HEALTH_CHECK_PATHS`(기본 `/actuator/health`). 이유 셋:
1. 수집기에 이미 env 로 설정을 받는 길이 있다(`MONIMO_COLLECTOR_SAMPLING_RATIO`). ADR `#38` 이 로그 하한도 전역 env 로 정해 뒀으니 설정 셋이 한 자리에 모인다
2. 목록을 비우면 필터가 꺼진다. 머지 전후 숫자를 비교할 수 있고, 나중에 헬스체크를 조사할 일이 생기면 잠깐 끌 수 있다 (버린 스팬은 영영 못 보는 A 방식의 단점을 일시적으로 되돌리는 수단)
3. `compose.yaml` 이 레포 파일이라 바꾼 기록이 git 에 남고 PR 리뷰를 받는다. 잘못 쓰면 진짜 트래픽을 지우는 목록이라 이게 안전장치다

PG 앱별은 API 문 · 설정 화면 · 30초 캐시 코드가 다 필요한데 쇼핑몰 4개가 전부 Spring Boot 라 채울 값이 똑같다. K(샘플링 비율을 PG 로)가 그 길을 뚫으면 그때 옮길 수 있고, env 로 지금 만드는 것이 이사를 막지 않는다 (ADR `#33` 이 `SamplingProperties` 를 PG 값 없을 때의 기본값으로 쓰기로 적어 뒀다).

**고아 스팬은 1차에서 다루지 않는다.** 로컬 실데이터에 자식 스팬이 0개라(헬스체크 트레이스 57개 = 스팬 57개) 생기지 않는다. 대신 "부모만 버려지고 자식은 남는다" 를 테스트로 못 박고, 뒤집히는 조건을 `AGENTS.md` §6 에 적었다. 그 조건이 오면 트레이스 단위 제거(버퍼 비용)와 호출자 표시 중에서 다시 고른다.

## 확인 못 한 것

- `otel.instrumentation.http.exclude-paths` 같은 에이전트 경로 제외 속성 : 공식 문서에 없음. 존재하지 않는 것으로 본다
- "`OpenTelemetryHandlerMappingFilter` 경로(PR #20216)는 예외 · 404 때만 의미 있는 보조 경로" 라는 해석 : 코드 구조상 추론, PR 설명에 명시 없음
- WebFlux · Spring Cloud Gateway 에서 actuator `http.route` 가 채워지는지 : 조사 범위 밖. 우리 gateway 가 WebFlux 로 바뀌면 다시 본다
- Jaeger · Datadog · New Relic 의 헬스체크 제외 권장 방식 : SigNoz 만 확인
- Pinpoint `profiler.tomcat.excludeurl` : 플러그인 README 주석으로만 확인, 공식 문서 페이지는 못 찾음
- OTel 3.0 에서 `rule_based_routing` 을 대체할 SDK incubator `rule_based` 샘플러의 YAML 스키마
- starter 쪽 actuator `http.route` 누락이 정확히 어느 버전부터인지 : 이슈는 영향 모듈만 나열
- kubelet 의 `user_agent.original` 값이 정확히 `kube-probe/<버전>` 인지 : 우리 K8s 에 올려 보기 전까지

## 여기서 나온 면접 질문

1. 모니터링 시스템이 자기 헬스체크를 손님으로 세던 걸 어떻게 발견했나. 왜 화면에서는 안 보였나
2. 에이전트 · 수집기 · 저장소 세 자리 중 어디서 걸렀고, 다른 두 자리는 왜 버렸나
3. `url.path` 와 `http.route` 는 뭐가 다르고 왜 `url.path` 를 골랐나. semconv 의 Required 와 Conditionally Required 가 뭔가
4. 부모 스팬만 버리면 자식 스팬은 어떻게 되나. 그래서 뭘 조심했나
5. AI 리서치가 틀린 걸 어떻게 잡았나 (실데이터 대조)
