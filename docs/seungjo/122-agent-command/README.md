# `#122` 수집기 스레드 덤프 명령 문

> 짝 이슈 monimo-shop `#34`(Extension)와 함께 만들었다. 조사 · 질문 · 선택지 · 결정 프롬프트 원문 · 확인한 수치는 한 곳에 둔다 :
> **monimo-shop [`docs/seungjo/34-thread-dump-extension/`](https://github.com/2026-techeer-project-team-b/monimo-shop/tree/develop/docs/seungjo/34-thread-dump-extension)** (README = 설계 한 장, research = 8단계, prompts = 원문)

- 2026-10-08 / 승조(`@SeungJo-02`) / ADR `#31` · `#33` 구체화 (롱폴링 합의안 6가지)

## 이 레포에서 바뀐 것

| 파일 | 하는 일 |
|---|---|
| `collector/.../command/CommandHub.kt` | 보유 맵(지금 붙잡은 폴링) · 빈틈 보관(2초) · 결과 기다리기. 전부 `DeferredResult` 라 요청 스레드를 잡지 않는다. 같은 에이전트의 새 폴링이 오면 옛 폴링은 409(Extension 백오프). 경합을 막으려고 synchronized |
| `collector/.../command/AgentCommandProperties.kt` | 토큰 둘 · `advertised-url` · 대기 시간 |
| `collector/.../inbound/agent/AgentCommandController.kt` | `GET /agent/commands` (25초 뒤 204) · `POST /agent/commands/{id}/result` · `POST /internal/thread-dump` (200 · 503 · 504 · 401) |
| `collector/src/main/resources/application.yml` | `monimo.collector.agent.*` |
| `compose.yaml` · `.env.example` | 로컬 토큰 기본값, `MONIMO_COLLECTOR_ADVERTISED_URL=http://collector:8081` |
| 테스트 2개 (16건) | 보관소 판단 10 · 문 셋의 토큰 · 응답 코드 6 |

## 범위 밖

API 서버 팬아웃(`agentUuid` → service · instance, 수집기 전부에 보내고 503 이면 1회 재시도) · 화면 · CH `thread_dumps` 저장(Q16) · mTLS(FN-12)
