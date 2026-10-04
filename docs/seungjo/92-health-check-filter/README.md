# `#92` 헬스체크 스팬을 수집기에서 버린다

> **이 작업의 기술 설계 문서 한 장.** 문제 → 선택지 → 결정 → 장애가 나면 순서로 읽으면 끝난다.
> 결정 정본은 [`../../design/01-decisions.md`](../../design/01-decisions.md) 의 ADR `#50` 이고, 여기는 그 한 건을 한 장으로 펼친 것이다.
> 이 방식(조사 → 프롬프트 → 결정 → 표 영향)으로 처음부터 끝까지 한 **첫 이슈**다.

- 날짜 2026-10-04 / 승조(`@SeungJo-02`) / 결정 ADR `#50` / 이슈 `#92` · PR `#93`
- 유저 플로우에서 어디: 에이전트 → **수집기** → Kafka → 적재 처리기 → ClickHouse 중 **수집기**. 화면의 호출 수 · 에러율 · P95 · 히트맵이 이 지점에서 결정된다

## 문제

도커 · 쿠버네티스가 쇼핑몰의 `GET /actuator/health` 를 몇 초마다 찌르는데, OTel 에이전트가 그걸 **진짜 요청과 똑같은 SERVER 스팬**으로 만들어 보낸다. ClickHouse 집계표에 이름을 거르는 조건이 없어 그대로 섞인다.

| 재 본 것 | 값 |
|---|---|
| 실제 파드 SERVER 스팬 중 헬스체크 비율 | **13.2 ~ 15.4%** (k6 부하가 섞인 구간. 부하가 없으면 거의 전부) |
| 가짜 seed 데이터까지 합쳐 세면 | 0.1% : **같은 표를 어떻게 자르느냐로 150배** 차이 |
| 로컬 헬스체크 스팬 | shop-gateway 18 · shop-order 18 · shop-payment 21, 전부 SERVER |
| 지속시간 | 평균 11ms · p95 121ms ("2ms 짜리" 가 아니다) |
| `service_health_1m` 오염 | shop-\* 253분 |

**무엇이 깨지나**: 호출 수가 부풀고, 헬스체크는 200 응답이라 **에러율 · P95 가 희석**된다 : `5XX_RATE` · `4XX_RATE` · `P95_LATENCY` 경보가 안 터진다. 히트맵 0ms 칸에도 쌓인다. 화면에 숫자가 **없는** 게 아니라 **틀린** 거라 늦게 발견했다(`#79` · `#83` 과 같은 함정).

## 선택지

| 방법 | 얻는 것 | 포기하는 것 | 구현 크기 |
|---|---|---|---|
| **A. 수집기가 Kafka 발행 전에 버린다** | 한 곳만 고치면 MV 3개 + Kafka · CH 저장 비용이 같이 해결. 앱 재배포 없음 | **버린 스팬은 영영 못 본다.** 목록을 잘못 쓰면 진짜 트래픽이 사라진다 | 새 파일 2 · 수정 5 · 테스트 23건 |
| B. ClickHouse MV 에서 거른다 | `spans` 원본은 남는다 | MV 3개 DDL 을 drop · recreate. **이미 만들어진 집계는 안 고쳐진다.** Kafka · CH 비용 그대로 | DDL 3개 + 재생성 절차 |
| C. 에이전트 선언형 설정(YAML) | 네트워크 · 수집기 비용 0 | 쇼핑몰 4곳마다 설정. **실험적이고 3.0 에서 제거 예정.** 설정 파일을 쓰면 env 설정이 전부 무시된다 | 앱 4곳 × 설정 파일 |
| D. 자체 Sampler 확장 jar | 로직 자유 | 직접 빌드 · 배포 · 유지. C 와 같은 "앱마다" 문제 | jar 1개 + 배포 경로 |
| E. 호출자가 `traceparent sampled=0` 을 붙인다 | 앱 · 수집기 손 안 댐 | 찌르는 쪽(도커 · 카나리 · k6)이 여럿이라 **하나라도 빠뜨리면 분리가 안 되고 조용히 묻힌다** | 호출자 3종 각각 |

출처 링크 · 우리 데이터 대조 · **AI 가 틀렸던 것**은 [`research.md`](research.md).

## 결정

- **고른 것**: **A.** `span_kind == SERVER` **이면서** `url.path` 가 목록과 **정확히 일치**할 때만 버린다. 목록은 수집기 전역 env `MONIMO_COLLECTOR_HEALTH_CHECK_PATHS`(기본 `/actuator/health`), **비우면 꺼진다**. 자리는 `TraceSampler` **앞**
- **버린 것과 이유**: B(MV 3개를 다 고쳐야 하고 지난 집계는 안 고쳐짐) · C · D(쇼핑몰 4곳마다 작업 = **"에이전트만 붙이면 된다"는 제품 전제 ADR `#33` 이 깨진다**) · E(하나라도 빠뜨리면 분리 실패). 매칭 키는 `http.route` 기각(semconv 에서 `url.path` 는 **Required**, `http.route` 는 **Conditionally Required** 라 합법적으로 빈다) · 접두 일치 기각(`/a` 하나로 `/api/orders` 가 전부 사라진다)
- **되돌리는 조건**: **헬스체크가 실제 쿼리나 HTTP 호출을 하게 되면**(`validation-query` 지정 · Redis 확인 추가 · gateway 의 `/health` 가 order 를 확인) 자식 CLIENT 스팬이 생겨 **부모만 버리면 고아 스팬**이 된다. 그때 트레이스 단위 제거(버퍼 비용)와 호출자 표시 중에서 다시 고른다

4요소 전문은 [`decision.md`](decision.md), **이 결정을 만든 프롬프트 원문**은 [`prompts.md`](prompts.md) 와 ADR `#50` 안에 있다.

## 장애가 나면

| 무엇이 죽으면 · 틀리면 | 어떻게 되나 | 어떻게 알아채나 |
|---|---|---|
| 목록에 짧은 값(`/a`)이 들어간다 | 접두 일치였다면 `/api/orders` 가 통째로 사라진다. **빠진 줄은 못 되살린다** | 정확 일치만 쓰므로 안 생긴다. 틀려도 안 버리는 쪽으로 설계 |
| 필터를 끈다(목록을 비운다) | 다시 섞인다. 끈 구간의 `service_health_1m` 만 틀어진다 | 의도한 동작(머지 전후 비교용). `compose.yaml` 이 레포 파일이라 끈 기록이 git 에 남는다 |
| 헬스체크에 자식 스팬이 생긴다 | 부모만 버려져 자식이 **고아**가 된다. `server_map_1m` 에 간선으로 남고 트레이스 상세가 뿌리 없는 트리가 된다 | 지금은 안 생긴다(57 트레이스 = 57 스팬). `HealthCheckFilterTest` 가 현재 동작을 고정 |
| 수집기가 여러 대인데 한 대만 env 가 다르다 | 그 대를 거친 헬스체크만 들어온다 | 아직 1대. 여러 대가 되면 env 를 deploy 레포 values 한 곳에서 뿌린다 |

표별 영향(어느 MV 가 무엇을 잘못 세었나)은 [`tables.md`](tables.md). 파이프라인 전체의 고장 표는 [`../../design/30-failure-modes.md`](../../design/30-failure-modes.md).

## 어떻게 확인했나

- 단위 테스트 **23건** : 목록 일치 · 불일치 · CLIENT 보존 · 속성 없음 · 빈 목록 · **자식 보존(고아 스팬 현재 동작 고정)** · 경로별 카운터 · 다중 resource/scope · 비문자 값. 수집기 전체 42건 통과
- 관통 : telemetrygen 으로 `/actuator/health` 와 `/orders` 를 보내 앞은 `spans` 에 안 들어가고 뒤는 들어감
- **env 바인딩을 기본값과 다른 값으로** 재확인 : `/healthz,/custom-probe` 로 바꾸니 `/actuator/health` 가 남았다 = env 가 yml 을 덮어쓴다
- `check-pipeline.sh` 를 헬스체크가 버려지는 중에 돌려 통과. CI `build` · `dev-infra` · `docker-image` 통과

## 결과물

- 새 파일 `collector/.../filter/HealthCheckFilter.kt` · `HealthCheckProperties.kt`
- 수정 `OtlpExportServices.kt` · `application.yml` · `compose.yaml` · `.env.example` · `scripts/check-pipeline.sh`

## 읽는 순서

1. [`prompts.md`](prompts.md) : 조사 프롬프트 → 되물은 말 2번 → **결정 프롬프트**. 그 글이 설계 그 자체다
2. [`research.md`](research.md) : 선택지 7개 전문 · 출처 · AI 가 틀린 것 · 면접 질문 5개
3. [`decision.md`](decision.md) : ADR `#50` 4요소
4. [`tables.md`](tables.md) : 표를 안 바꿨는데 왜 표와 관련 있나 (MV 가 insert 시점에 돈다)

## 이 이슈에서 배운 것 (세 줄)

- 화면에 숫자가 **없는** 게 아니라 **틀린** 거라 늦게 발견했다. `#79` `#83` 과 같은 함정
- 리서치 에이전트가 틀린 답을 줬고 **우리 실데이터와 대조해서** 잡았다. 안 했으면 "`http.route` 는 못 믿는다" 로 잘못 결론 났다
- "목록을 비우면 꺼진다" 를 네 군데에 적어 놓고 한 번도 시험하지 않았다가 리뷰에서 거짓임을 잡았다(`${VAR:-}` 가 빈 값에도 기본값을 되살린다). **문서에 쓴 기능은 시험한 것만**
