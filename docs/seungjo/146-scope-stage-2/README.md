# `#146` 설계 2단계 범위 · 규모 문서와 `T0` : 가정 대신 실측으로 채운다

> **이 작업의 기술 설계 문서 한 장.** 코드가 아니라 설계 문서를 쓴 건이다. 문제 → 선택지 → 결정 → 장애가 나면 순서로 읽으면 끝난다.
> 결정 정본은 [`../../design/01-decisions.md`](../../design/01-decisions.md) 의 ADR `#60` 이고, 산출물은 [`../../design/20-scope.md`](../../design/20-scope.md) 다.
> 라이트 모드 : `research.md` 를 안 돌렸다. 조사할 것이 "무엇을 재나" 하나였고 선택지도 `T0` 후보 셋이 전부였다.

- 2026-10-11 / 승조(`@SeungJo-02`) / 결정 ADR `#60` / 이슈 [`#146`](https://github.com/2026-techeer-project-team-b/monimo-backend/issues/146)
- 유저 플로우에서 어디: 어디에도 없다. 사람이 읽는 문서다. 다만 배포 7 ~ 9단계(monimo-deploy)의 PVC · 자원 값이 여기 숫자에서 나온다

## 문제

설계를 5단계로 나눠 단계마다 문서 한 장을 쓰기로 했는데(스킬 `모니모니터링` §4), **2단계 문서 `20-scope.md` 가 없었다.** 1단계가 2026-09-02 에 끝나고 팀이 2026-09-23 에 "노션 API 명세 기준으로 바로 구현" 을 택해 설계 문서가 멈췄다. 코드는 다섯 레포에서 돌고 있는데 "얼마나 큰 것을 만드나" 가 숫자로 적힌 곳이 없었다.

| 재 본 것 (2026-10-11) | 값 |
|---|---|
| `docs/design/20-scope.md` | **없다.** `10-requirements.md` 끝줄과 `00-index.md` `next:` 가 "다음은 20-scope" 라고만 적고 있다 |
| 1단계 처리량 NFR 의 상태 | "2단계에서 역산해 확정한다" 가 39일째 그대로 |
| `T0` (Q8) | 2026-09-04 부터 OPEN. `T0+1M` · `T0+2M` 을 쓰는 ADR 이 셋(`#03` `#06` `#09`)인데 날짜가 없어 전부 공중에 떠 있었다 |
| 설계 헌법 스킬의 포인터 | `~/monimonitoring/design/00-index.md` 가 **2026-09-22 에 멈춘 사본**(`~/monimonitoring/repos/…`)을 가리켰다. 스킬 §0 2번이 읽으라는 `02-open-questions.md` 는 그 폴더에 없다 |
| 노션 설계범위 · 기술스택 페이지 | 2026-09-14 · 09-15 에 멈춤. 오버헤드 기준 · 1a 범위 · 파수꾼 상태 · `T0` 미정 표기가 낡았다 |

**무엇이 깨지나**: 배포 매니페스트에 CPU · 메모리 · PVC 를 감으로 적게 된다. `T0+1M` 중간 점검(ADR `#06` `#09` 되돌림 조건)이 언제인지 아무도 모른다. 면접에서 "설계 5단계를 했다" 고 말하면 2단계가 비어 있다.

## 선택지

| 묶음 | 고른 것 | 버린 것과 이유 |
|---|---|---|
| `T0` 를 어느 날로 | **조직 첫 머지**(PR `#1`, KST 2026-09-22 00:09) | 첫 커밋(09-21 23:46) = push 라 CI 전이고 다섯 레포에 같은 분에 찍힌 "폴더 만든 순간" · 설계 착수일(09-01) = 코드가 없던 때라 두 달에 설계 3주가 들어가 MVP 기한이 이미 지남 · 팀 배치일(09-23) = 머지 기록처럼 바깥에서 확인할 산출물이 없음 |
| 숫자를 어떻게 채우나 | **로컬 실측 + 역산** (k6 초당 주문 20건 3분) | 1단계 가정("서버 1,500대 × 지표 100종")으로 채우기 = 우리 규모가 아니다(인스턴스 2,040대 분량) · 운영 실측 = 운영이 없다 |
| 처리량 NFR 을 어떻게 하나 | **숫자 유지, 지위를 "부하 시험 상한" 으로** | 실측치로 내림 = 몰릴 때 거동을 영원히 못 잼 · 올림 = 근거 없음 |
| 가짜 데이터를 어떻게 가르나 | **`agent_id LIKE '%-local-1'`** (compose 이름표) | 서비스 이름 = 가짜도 `shop-*` 라 못 가름 · 시각 = 가짜가 2026-10-09 한 시간이라 되긴 하지만 다음 seed 때 깨짐 · `#144` 가 쓴 로그 attributes 모양 = 로그에만 통함 |
| 카디널리티를 무엇으로 세나 | **`(agent_id, metric_name, series_hash)`** | `series_hash` 만 = 히스토그램 네 이름이 해시를 공유해 에이전트별로 더해도 **실제의 43%**(125 대 294), 전체로 세면 14%(40) |

## 결정

- **고른 것**: `T0` = 2026-09-22(KST). 숫자 7항은 전부 실측 또는 역산(동시 사용자만 가정 10). NFR 은 상한으로 유지. 경계표는 "지금 코드가 어디까지 있나" 16행. ADR `#60` 에 4요소
- **버린 것과 이유**: 위 표
- **되돌리는 조건**: 운영 실측이 로컬 역산과 10배 넘게 어긋나면 다시 잰다 · 자기 감시를 붙여 9 인스턴스가 되면 §2 · §4 갱신 · `T0` 는 기록 사실이라 안 되돌린다

## 어떻게 확인했나

측정 2026-10-11 06:00 ~ 06:03 KST(= 2026-10-10 21:00:16Z ~ 21:03:16Z), 로컬 compose. 쇼핑몰은 `PAYMENT_PORT=18092 ORDER_PORT=18091` 로 띄웠다(아래 「같이 드러난 것」).

```
docker run --rm --network monimo-dev -v "$PWD/k6:/scripts" -e BASE_URL=http://gateway:8090 \
  -e RATE=20 -e DURATION=3m grafana/k6:2.3.0 run /scripts/order.js
# iterations 3,601 (20.0/s) · http_reqs 5,402 (30.0/s) · checks_failed 0 · p95 19.25ms
```

| 무엇 | SQL (ClickHouse, `W` = 위 창) | 값 |
|---|---|---|
| 스팬 · 트레이스 | `SELECT count(), uniqExact(trace_id) FROM monimo.spans WHERE agent_id LIKE '%-local-1' AND start_time BETWEEN W` | **61,027 · 5,388** → 339 스팬/초 |
| trace 당 스팬 (transactions 는 창 안 17,950행 = 스팬 1개당 0.294행) | `SELECT n, count() FROM (SELECT trace_id, count() n FROM monimo.spans WHERE agent_id LIKE '%-local-1' AND trace_id IN (루트가 W 안인 trace) GROUP BY trace_id) GROUP BY n` | 14 × 3,590(주문) · 6 × 1,792(재고 조회) · 1 × 3 · 4 × 3 |
| 서비스별 스팬 | 같은 조건 `GROUP BY service_name` | gateway 10,770 · inventory 25,127 · order 17,950 · payment 7,180 |
| 시계열 수 | `SELECT agent_id, uniqExact((metric_name, series_hash)), count(), count()/uniqExact(ts) FROM monimo.metrics_raw WHERE agent_id LIKE '%-local-1' AND ts BETWEEN W GROUP BY 1` | 70 · 79 · 83 · 62 (합 294) · 틱당 포인트 = 시계열 수 · 18틱 |
| 틀린 셈법 | `SELECT sum(h) FROM (SELECT agent_id, uniqExact(series_hash) h FROM monimo.metrics_raw WHERE … GROUP BY 1)` · 전체로는 `uniqExact(series_hash)` | 에이전트별 합 125 (43%) · 전체 40 (14%) |
| 메트릭 포인트 | `SELECT count() FROM monimo.metrics_raw WHERE … ts BETWEEN W` | 5,292 → 29.4/초(10초 전송) → 운영 60초면 4.9/초 |
| 로그 | `SELECT count() FROM monimo.logs WHERE agent_id LIKE '%-local-1' AND ts BETWEEN W` | **0** · 기동 때만 13 · 16 · 13 · 40 |
| 행당 바이트 | `OPTIMIZE TABLE monimo.<표> FINAL` 뒤 `SELECT table, partition, count(), sum(rows), sum(bytes_on_disk)/sum(rows) FROM system.parts WHERE database='monimo' AND active GROUP BY 1,2` | 2026-10-10 파티션(part 1개) : spans 73.7 · transactions 39.0 · metrics_raw 1.4 · logs 76.6. **합치기 전(part 여럿)에는 metrics_raw 가 5.4 였다**(1.4 는 54,484행 시점. 행이 늘수록 내려간다). 그 파티션에 telemetrygen 행이 spans 12 · metrics_raw 6 · logs 6 섞여 있다 |
| PG 등록 | `SELECT a.name, g.agent_key FROM agents g JOIN applications a ON a.id=g.application_id` | 4 · 4 |
| `T0` | `gh pr list -R 2026-techeer-project-team-b/<레포> --state merged --json number,mergedAt` 다섯 레포 | backend `#1` `2026-09-21T15:09:13Z` 가 가장 이르다. 나머지 넷은 15:22Z |

역산 : 일일 = 초당 × 86,400 × 행당 바이트. 1% 상시 = spans 21.6 MB + transactions 3.4 MB + metrics_raw 0.6 MB = 26 MB/일. 100% 를 24시간 걸면 spans 2,159 MB/일. 3분 데모 한 번은 약 5 MB. 상주량은 보관 기간(ADR `#10`)을 곱했고, 로컬 TTL(93 ~ 105일)은 S3 2단을 합친 것이라 따로 적었다. 전부 `20-scope.md` §4 표.

## 같이 드러난 것

- **`#135` 의 관리 포트 8091 · 8092 가 쇼핑몰 order · payment 호스트 포트와 겹친다.** 쇼핑몰 compose 가 `Bind for 0.0.0.0:8092 failed: port is already allocated` 로 payment 를 못 띄우고 그 뒤 order · gateway 가 연쇄로 안 뜬다. **`#144` 때 "order 와 gateway 가 안 떴다" 를 의존 사슬 탓으로 적었는데 원인은 이것이었다.** `#135` 조사가 "+10 이면 다섯 모듈을 한 컴퓨터에 다 띄워도 안 겹친다" 고 적은 것은 우리 모듈끼리만 본 것이다. `AGENTS.md` §6 에 올렸다. 쿠버네티스에서는 파드마다 네트워크 이름공간이 달라 안 겹친다. 예외는 같은 파드 안(사이드카)이나 `hostNetwork` 를 쓸 때다
- **`series_hash` 는 카디널리티 셈법으로 못 쓴다.** 꼬리표만 해시하므로(`#64`, 메트릭 · 로그 적재를 넣은 이슈) 히스토그램이 `.count` · `.sum` · `.min` · `.max` 로 펴지면 넷이 같은 해시다. 조회 · 집계 쪽이 "시계열 수" 를 셀 때 `metric_name` 을 같이 묶어야 한다
- **노션 두 페이지가 낡았다.** 목록은 `20-scope.md` §7. 팀 공유 페이지라 손으로 고친다(승조)
- **설계 헌법 스킬이 2026-09-22 사본을 읽고 있었다.** 포인터를 실제 레포로 고치고, 스킬 §0 의 "`02-open-questions.md` 를 읽는다" 를 "포인터가 가리킨 폴더에서" 로 고쳤다. 샘플링 행(`#53` 최댓값 하나)과 설정 단위 행(`#55` 구현 보류)도 레포 사실에 맞췄다

## 장애가 나면

| 보이는 것 | 원인 | 보는 곳 |
|---|---|---|
| 운영 디스크가 예상(하루 수십 MB)보다 훨씬 빨리 찬다 | 샘플링 비율이 1% 가 아니다(100% 를 24시간 받으면 하루 2.5 GB). 또는 로컬 TTL(93일)이 운영에 그대로 갔다 | PG `application_configs.sampling_rate` · `db/clickhouse` TTL · `system.parts` |
| 로컬 디스크가 찬다 | 데모 탓이 아니다(3분 100% 데모 한 번 약 5 MB, 93일 매일 0.5 GB). 가짜 seed 를 반복 넣었거나(한 시간치 23 MB) TTL 93 ~ 105일 | `SELECT table, sum(bytes_on_disk) FROM system.parts WHERE database='monimo' GROUP BY 1` |
| 시계열 수가 화면과 다르다 | `series_hash` 만 셌다 | `metric_name` 을 함께 묶는다 |
| 쇼핑몰 compose 가 안 뜬다 | 포트 8091 · 8092 충돌 | `PAYMENT_PORT=18092 ORDER_PORT=18091` 로 띄운다. 영구 수정은 `AGENTS.md` §6 |
| `T0+1M` 점검(2026-10-22)에 되돌림 조건이 걸린다 | ADR `#06`(메트릭 규칙 평가 미완) · `#09`(대시보드 1종 미완). 코드는 둘 다 있다. 끝났는지는 그날 판정한다 | `detector/` · monimo-web `features/` |
