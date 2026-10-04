# 결정 : ADR `#50`

정본은 [`../../design/01-decisions.md`](../../design/01-decisions.md) 의 `#50`. 여기는 요약과 길 안내만.

## 4요소

| | |
|---|---|
| **채택** | 수집기가 Kafka 발행 전에 버린다. `span_kind == SERVER` 이면서 `url.path` 가 목록과 **정확히 일치**. 목록은 수집기 전역 env `MONIMO_COLLECTOR_HEALTH_CHECK_PATHS`(기본 `/actuator/health`), 비우면 꺼진다. 자리는 `TraceSampler` **앞** |
| **기각 ①** 호출자가 `sampled=0` | 찌르는 쪽(도커 · 카나리 · k6)이 여럿이라 하나라도 빠뜨리면 분리가 안 되고 조용히 묻힌다 |
| **기각 ②** 에이전트에서 | 쇼핑몰 4곳마다 설정 · jar. "에이전트만 붙이면 된다"(ADR `#33`) 가 깨진다. OTel 의 그 기능은 실험적 · 3.0 제거 예정 |
| **기각 ③** ClickHouse MV 에서 | 고칠 MV 가 3개라 빠뜨리기 쉽고, MV 는 새 줄만 봐서 **이미 만들어진 집계는 안 고쳐진다** |
| **기각 ④** PG 앱별 목록 | API 문 · 화면 · 캐시 코드가 다 필요한데 쇼핑몰 4개가 전부 Spring Boot 라 채울 값이 같다 |
| **기각 ⑤** `http.route` 로 맞춤 | semconv 에서 `url.path` 는 Required, `http.route` 는 Conditionally Required 라 합법적으로 빌 수 있다 |
| **기각 ⑥** 접두 일치 | `/a` 하나로 `/api/orders` 가 전부 사라진다. 틀려도 안전한 쪽(안 버리는 쪽) |
| **되돌림** | 헬스체크가 실제 쿼리 · HTTP 호출을 하게 되면 자식 스팬이 생겨 **부모만 버리면 고아 스팬**이 된다. 그때 트레이스 단위 제거와 호출자 표시 중에서 다시 고른다 |

## 이 결정은 프롬프트로 내렸다

조사 결과를 읽고 선택지를 다 본 뒤 **사용자가 결정을 글로 쓰고 "그러니 그렇게 구현해줘" 로 끝냈다.** 그 글이 채택 · 기각 셋 · 사유 · 되돌림을 다 담고 있어서 글이 곧 결정이고, 구현은 글을 코드로 옮긴 것이다.

- 원문: [`prompts.md`](prompts.md) 의 「구현 단계」, 그리고 ADR `#50` 안의 「이 결정을 만든 프롬프트」 절
- 그 글을 쓸 수 있게 만든 조사: [`research.md`](research.md)

## 검증 (2026-10-04, 로컬)

- 단위 23건: 목록 일치 · 불일치 · CLIENT 보존 · 속성 없음 · 빈 목록 · **자식 보존(고아 스팬 현재 동작 고정)** · 경로별 카운터 · 다중 resource/scope · 비문자 값
- 관통: telemetrygen 으로 `/actuator/health` 와 `/orders` 를 보내 앞은 `spans` 에 안 들어가고 뒤는 들어감
- env 바인딩: 기본값과 **다른 값**(`/healthz,/custom-probe`)으로 바꿔 `/actuator/health` 가 남는 것 확인 = env 가 yml 을 덮어쓴다
- `check-pipeline.sh` 를 헬스체크가 버려지는 중에 돌려 통과
