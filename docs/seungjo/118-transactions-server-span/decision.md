# 결정 : ADR `#52`

정본은 [`../../design/01-decisions.md`](../../design/01-decisions.md) 의 `#52`. 여기는 요약과 길 안내만.

## 4요소

| | |
|---|---|
| **채택** | `mv_transactions` 조건을 `span_kind IN ('SERVER', 'CONSUMER')` 로 넓히고 `is_root UInt8` 컬럼(`toUInt8(parent_span_id = '')`)을 추가한다. 운영에서 MV 를 바꿀 때는 `ALTER TABLE ... MODIFY QUERY` 를 쓰고 바꾼 뒤 각 컬럼에 값이 들어오는지 확인한다. `is_root` 는 `ADD COLUMN` 을 MV 수정보다 먼저. 백필은 하지 않는다. 정렬 키 · 스키핑 인덱스 · 조회 코드는 손대지 않는다 |
| **기각 ①** `SERVER` 단독 | 사유: 큐로 들어온 요청(CONSUMER)을 빠뜨려 그 서비스 스캐터가 또 빈다. 여섯 제품 중 SERVER 단독은 Jaeger SPM 하나이고 그 문서가 바로 누락 위험을 경고한다. CONSUMER 는 지금 0건이라 비용 0 |
| **기각 ②** `OR parent_span_id = ''` 까지(업계 표준) | 사유: 스스로 시작한 일(앱 시작 DDL · 배치)이 들어와 `CREATE TABLE` 이 스캐터 점으로 찍힌다. 지금 56줄이 전부 그것이고 운영에서도 파드가 뜰 때마다 생긴다 |
| **기각 ③** 조회가 `spans` 직접 읽기 | 사유: 원본 표라 느리고 히트맵이 `heatmap_1m` 을 못 쓴다 |
| **기각 ④** 지금대로 + 안내 문구 | 사유: 느린 서비스를 못 고르면 스캐터가 반쪽이다 |
| **기각 ⑤** `is_root` 대신 문서로 못 박기 | 사유: 막을 장치가 없다. `count()` 를 쓰면 4배 부풀려진 숫자가 화면에 나간다. 표를 둘로 나눈 사례는 없고 플래그로 거르는 것(Elastic `transaction.root`)이 업계 방식 |
| **기각 ⑥** `DROP VIEW` + `CREATE` | 사유: 그 사이 INSERT 가 영구 유실된다. 샌드박스 실험 4줄 → 0줄, 경고 없음 |
| **기각 ⑦** MV 둘 같이 돌리기 · `EXCHANGE TABLES` | 사유: 겹친 기간만큼 정확히 2배(4줄 → 8줄). `EXCHANGE` 는 교환 뒤에도 둘 다 돈다 |
| **기각 ⑧** 과거 백필 | 사유: `transactions` 직접 INSERT 가 `mv_heatmap_1m` 을 돌리고 `SummingMergeTree` 가 `cnt` 를 조용히 합산한다. 복제 표가 아니라 두 번 돌리면 그대로 두 배. 지금은 과거가 전부 가짜 데이터 |
| **기각 ⑨** `is_root` 를 정렬 키 · 스키핑 인덱스에 | 사유: 첫 칸이면 binary search 가 깨지고 마지막 칸은 효과 없음. 29% 플래그는 스키핑 인덱스가 비용만 든다 |
| **기각 ⑩** `span_id` 컬럼 | 사유: 겹치는 키가 최대 2줄 · 평균 1.00 으로 드물고 조회 파트 화면 계획에 달렸다. `trace_id String` 이 이미 디스크의 78% |
| **기각 ⑪** `005_` 마이그레이션 파일 | 사유: 운영에 실행 수단이 없어 "있는데 안 도는" 상태가 된다. 별 이슈 `#119` |
| **되돌림** | ① 배치 · 스케줄 잡이 생기면 `OR parent_span_id = ''` 로 넓힌다 ② 전체 요청 수 화면이 생기고 `is_root` 로 안 풀리면 표를 둘로 ③ 스캐터가 눈에 띄게 느려지면 `ORDER BY` · `limit` 5000 재검토 ④ 한 서비스에 SERVER 스팬이 여럿 생기는 구성이 오면 중복 계수 재검토 |

## 이 결정을 어떻게 내렸나

- 조회 파트 글 → 질문 열일곱 번(2026-10-06, 노션에 먼저 기록) → 레포 리서치 1 · 2절 → 질문 세 번 → 조사 서브에이전트 2개(업계 여섯 제품 · ClickHouse 샌드박스 실험) → 5 · 7절 → **승조가 8절에 결정 프롬프트를 썼다**
- 프롬프트 원문은 [`research.md`](research.md) 8절 · [`prompts.md`](prompts.md) · ADR `#52` 안. 세 곳이 글자 단위로 같다
- **조사가 초안을 바꿨다** : 조건이 한 줄(`SERVER`)에서 선택지 셋으로, 교체 방법이 "파일 고치고 `down -v`" 에서 "`MODIFY QUERY` + 검증 쿼리" 로, `is_root` 가 "넣으면 좋다" 에서 "업계 선례가 있고 조회가 빨라진다" 로

## 검증

- [x] `down -v` → 켜기 → seed 뒤 `transactions` 네 서비스 전부 0 아님 (gateway 30,000 · order 30,000 · payment 21,083 · inventory 21,083)
- [x] `transactions` 합계 102,166 = `spans` 의 SERVER 스팬 102,166
- [x] `sum(is_root)` 30,000 = `spans` 의 루트 SERVER 수 30,000
- [x] 헬스체크 줄 0
- [x] 빈 컬럼 0 (`MODIFY QUERY` 함정 검사 : `service_name` · `trace_id` · `span_name` · `agent_id` 빈 줄 0, `duration_ms` · `http_status` 0 인 줄 0, `is_root` 값 종류 2)
- [x] `heatmap_1m` 네 서비스 전부 (1,219칸)
- [x] `check-pipeline.sh` 통과
- [x] `ScatterApiTest` 픽스처를 새 기준으로 고치고 중간 서비스 단언 추가 (아래 「테스트가 잡은 것」)
- [x] ADR `#52` 가 `+179 / -0` (append-only)
- [ ] PR 머지 (사용자)
- [ ] 노션 「ERD」 정본 갱신 (사용자)
- [ ] 팀원 각자 `docker compose down -v` (PR 본문 · `AGENTS.md` 에 안내)

### 테스트가 잡은 것

`ScatterApiTest` 2건이 "6을 기대했는데 7" 로 깨졌다. 픽스처의 7번째 스팬이 **같은 서비스(`sc-gateway`)의 자식 SERVER 스팬**이었고 테스트가 "자식 스팬은 요청이 아니다" 로 빼고 있었다. 옛 기준(루트만)의 전제가 테스트에 박혀 있던 것이다.

새 기준에서는 SERVER 스팬이면 자식이어도 "받은 요청" 이라 들어오는 게 맞다. 그래서 픽스처를 실제 모양으로 바꿨다 : gateway 가 order 를 부르는 **CLIENT 스팬**(보낸 기록, 요청 아님)과 order 가 받는 **SERVER 스팬**(받은 기록, 요청). gateway 를 고르면 그대로 6건이라 기존 단언이 다 살고, **order 를 고르면 1건이 나오는 단언**을 더했다. 그 한 줄이 `#118` 이 고친 동작이다.

### 범위 밖으로 남긴 것

- 운영에서 ClickHouse DDL 을 실행하는 수단 : [`#119`](https://github.com/2026-techeer-project-team-b/monimo-backend/issues/119)
- `GET /traces/transactions` · `GET /traces/heatmap` 구현 : 조회 파트
- `ttl_only_drop_parts = 1` · `trace_id` 가 저장의 78% : 독립적 개선
