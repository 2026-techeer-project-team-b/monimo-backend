# `#83` CLIENT 스팬의 `peer_service` 를 호출 대상 주소에서 채운다

> **소급 폴더.** 이슈 폴더 방식(조사 → 프롬프트 → 결정 → 표 영향)은 `#92` 부터 썼다. `#83` 은 그 전이라 **프롬프트 로그만** 있다. 리서치 카드 · `decision.md` · `tables.md` 는 그때 쓰지 않았으므로 지금 지어 넣지 않는다.

## 한 줄

OTel 에이전트 2.x 가 CLIENT 스팬에 `peer.service` 를 안 넣고 `server.address` 만 넣어서, 서버맵이 우리 서비스를 `EXTERNAL` 로 그렸다. 적재 처리기가 **`spans` 에 넣기 전에** 주소의 첫 DNS 라벨을 `applications.name` 과 맞춰 `peer_service` 를 채운다.

## 결과물

- 이슈 `#83` · PR `#84`
- `ingester/.../transform/ServiceCatalog.kt` · `PeerServiceResolver.kt` · `outbound/postgres/PostgresServiceCatalog.kt`, `RawConsumer.kt` 수정. 테스트 15건 추가(전체 87건)

## 있는 것

- [`prompts.md`](prompts.md) : 프롬프트 원문 · 한 번에 안 됐던 것(SERVER 스팬에도 채워지던 문제)과 고쳐 물은 말

## 그때 적어 둔 다른 곳

- 한 일 요약: [`AGENTS.md`](../../../AGENTS.md) §5 수집 파트 `#83`
- 왜 적재 **전**인가: `#92` 의 [`tables.md`](../92-health-check-filter/tables.md) 「왜 표 주인이 아닌 수집기에서 고쳤나」 가 같은 이유를 설명한다
- AI 가 틀린 것: [`harness.md`](../harness.md) 「안 된 것」 표의 `#83` 두 줄(1970년 TTL · telemetrygen 이 SERVER 에도 속성을 붙임)
