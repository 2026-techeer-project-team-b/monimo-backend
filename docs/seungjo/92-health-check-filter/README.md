# `#92` 헬스체크 스팬을 수집기에서 버린다

> 이 폴더는 **이 방식(조사 → 프롬프트 → 결정 → 표 영향)으로 처음부터 끝까지 한 첫 이슈**다.

## 한 줄

도커 · 쿠버네티스가 5초마다 찌르는 `GET /actuator/health` 를 에이전트가 진짜 요청과 똑같이 보내서 호출 수 · 에러율 · P95 · 히트맵이 틀어졌고, **수집기가 Kafka 에 넣기 전에 그 스팬을 버리게** 했다.

## 결과물

- 이슈 `#92` · PR `#93` · 브랜치 `fix/92-drop-health-spans` · 결정 ADR `#50`
- 새 파일 `collector/.../filter/HealthCheckFilter.kt` · `HealthCheckProperties.kt`, 수정 `OtlpExportServices.kt` · `application.yml` · `compose.yaml` · `.env.example` · `scripts/check-pipeline.sh`
- 단위 테스트 23건(`HealthCheckFilterTest`), 수집기 전체 42건 통과. CI `build` · `dev-infra` · `docker-image` 통과

## 숫자

| 무엇 | 값 |
|---|---|
| 실제 파드 SERVER 스팬 중 헬스체크 비율 | **13.2 ~ 15.4%** (k6 부하 섞인 구간) |
| 가짜 seed 데이터까지 합치면 | 0.1% : 같은 표를 어떻게 자르느냐로 150배 차이 |
| 로컬 헬스체크 스팬 | shop-gateway 18 · shop-order 18 · shop-payment 21, 전부 SERVER |
| 지속시간 | 평균 11ms · p95 121ms |
| 헬스체크 트레이스의 스팬 수 | 57 트레이스 = 57 스팬 (자식 없음) |

## 읽는 순서

1. [`prompts.md`](prompts.md) : 조사 프롬프트 → 되물은 말 2번 → 결정 프롬프트. **결정 프롬프트가 설계 그 자체**다
2. [`research.md`](research.md) : 선택지 7개 비교 · 출처 · 우리 데이터로 확인 · AI 가 틀린 것 · 면접 질문 5개
3. [`decision.md`](decision.md) : ADR `#50` 의 4요소 요약
4. [`erd.md`](erd.md) : 표는 안 바꿨는데 왜 이 작업이 ERD 와 관련 있나 (MV 가 insert 시점에 돈다)

## 이 이슈에서 배운 것 (세 줄)

- 화면에 숫자가 **없는** 게 아니라 **틀린** 거라 늦게 발견했다. `#79` `#83` 과 같은 함정
- 리서치 에이전트가 틀린 답을 줬고, **우리 실데이터와 대조**해서 잡았다. 대조하지 않았으면 `http.route` 를 못 믿는다는 잘못된 결론으로 갔다
- "목록을 비우면 꺼진다" 를 네 군데에 적어 놓고 한 번도 시험하지 않았다가 리뷰에서 틀린 걸 잡았다(`${VAR:-}` 와 `${VAR-}`). **문서에 쓴 기능은 시험한 것만**
