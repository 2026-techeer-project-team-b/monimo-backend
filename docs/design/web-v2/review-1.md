# 1차 디자인 리뷰 (2026-09-21)

## 요약 (3줄)
API 50행 중 미매핑(없음)은 0건이고 14건이 부분 충족이다. 화면 자체의 완성도와 필드 충실도는 높고, 특히 인스펙터·파수꾼·트레이스 상세는 그대로 구현 스펙으로 넘길 만하다.
가장 큰 문제는 모달·드로어가 그 화면의 존재 이유인 컬럼을 덮어버리는 것이다. 경보 이벤트의 observed_value·fired_at, 경보 규칙의 metric_kind·조건, 설정의 샘플링률 슬라이더가 세 화면 모두에서 가려져 핵심기능 ②와 ⑤의 증거가 화면에 남지 않는다.
그다음은 데이터 정합성이다. 필터 값과 표 내용이 어긋나는 화면이 둘(에러·스레드 덤프), 같은 경보 3건을 서버맵과 경보 화면이 서로 다르게 적고, 현재 시각이 화면마다 14:05와 15:00으로 갈린다.

---

## A. API 커버리지 — 미흡 항목만

50행 전수 대조 결과: 보임 36 / 부분 14 / 없음 0. 아래는 부분 14건만 적는다.

| # | 화면 | 기대 UI | 실제 | 조치 |
|---|---|---|---|---|
| 5 | Transactions | mode=bucketed 시 "격자 집계 중" 배지 | `mode: raw · 1,284점` 배지와 설명문만 있고 bucketed 상태 표현이 없음 | 배지 옆에 bucketed 예시 배지를 회색 보조 상태로 하나 더 붙이거나, 설명문을 "1,284점 · 상한 넘으면 bucketed로 전환"으로 고쳐 두 상태를 모두 보이게 |
| 8 | Settings | 서비스 수정 모달(display_name·description, name 잠금) | "수정" 버튼만 있고 수정 모달은 안 그림. 그려진 모달은 등록(#13) | 등록 모달을 수정 모달로 바꾸거나(409는 아래 지시 참조), 상세 카드 안에서 display_name·description을 인라인 편집 상태로 그려 PATCH 대상 필드를 드러내기 |
| 9 | Transactions | 스캐터↔히트맵 토글의 히트맵 뷰 | 히트맵은 하단 120px "미리보기" 띠로만 존재. 토글의 히트맵 쪽이 활성인 상태가 없음 | 미리보기 띠의 세로 높이를 키워 `bucket_width_ms`·`ts_min` 축 눈금과 is_error 오버레이 범례를 붙이면 별도 아트보드 없이 #9 충족 |
| 10 | AlertRules | 규칙 생성 모달 | "규칙 만들기" 버튼만 있고 생성 모달 없음. 그려진 건 수정 모달(#30) | 수정 모달 제목 아래에 "생성 시 application 선택 가능 · 수정 시 잠금" 캡션을 달아 두 상태를 한 모달로 겸하게 표기 |
| 15 | Inspector | 사용자 지정 지표 차트 | 표준 4장만. "지표 추가"로 고른 지표의 차트 자리가 없음 | 4장 아래에 점선 테두리 플레이스홀더 1장("여기에 추가된 지표 차트가 붙는다")을 넣어 확장 자리를 명시 |
| 16 | Alerts | 이벤트 표의 observed_value·fired_at | 드로어에 가려 두 컬럼이 화면에 전혀 안 보임 | 아래 H-1 참조 |
| 23 | AlertRules | 규칙 상세 드로어 | 상세 드로어 없이 수정 모달이 겸함 | 모달 좌측 상단에 읽기 전용 요약(생성일·최근 발화 건수)을 한 줄 추가하면 #23의 "상세" 성격을 흡수 |
| 29 | Settings | 샘플링률 카드의 현재값·version·updated_by·updated_at | version·updated_at 일부만 보이고 현재값과 updated_by가 모달 뒤 | 아래 H-3 참조 |
| 37 | AlertChannels | 채널 등록 모달(type 선택 → config 필드 전환) | "채널 등록" 버튼만. 그려진 건 수정 모달(#17) | 수정 모달의 type 세그먼트 아래에 "type을 바꾸면 config 필드가 통째로 바뀐다" 캡션을 달아 전환 규칙을 명시 |
| 38 | Settings | 삭제 확인 다이얼로그 | "감시 대상에서 제외" 버튼 + 409 캡션만 있고 확인 다이얼로그 없음 | 버튼 아래 캡션을 "확인 다이얼로그에서 name을 직접 입력해야 진행"으로 구체화 |
| 39 | Settings | 슬라이더+입력 → 적용(expected_version 동봉) | 409 배너·expected_version 캡션·적용 버튼은 보이나 슬라이더 핸들(5%)과 직접 입력이 모달 뒤 | 아래 H-3 참조 |
| 41 | ServerMap | 간선의 cnt·err_cnt·avg_duration_ms | 간선 라벨이 `12.4k · 214ms`로 cnt·avg만. err_cnt 없음 | 에러 있는 간선만 라벨을 `9.6k · err 402 · 386ms`로 3값 표기(정상 간선은 2값 유지) |
| 47 | Inspector | 서비스 미선택 시 전체 파드 표(service_name·status) | "전체 파드 목록" 링크만 있고 표는 없음 | 링크 문구를 "전체 파드 24개 · UP 19 / DOWN 3 / UNKNOWN 2"로 바꿔 GET /agents 응답 형태를 드러내기 |
| 48 | AlertRules | 규칙 표의 service·metric_kind·조건 | 모달에 가려 세 컬럼이 안 보임 | 아래 H-2 참조 |

---

## B. 핵심기능 충족

| 기능 | 화면·요소 | 판정 | 보완 |
|---|---|---|---|
| ① 여러 서비스를 거친 콜스택이 화면에 뜬다 | TraceDetail 스팬 트리 11행 — shop-gateway → shop-order → shop-inventory → shop-payment 4서비스, 서비스별 색 바, 서비스 경로 칩, 스팬 상세 events | 충족 | 트레이스 진입 경로가 끊겨 있다. Transactions·Errors 표의 trace_id 8개 중 TraceDetail의 `4b1f9a2c…e3a15`와 일치하는 값이 하나도 없다. TraceDetail을 Transactions 1행(`a3f1…9c2e`, 14:38:12.481, 2,584ms, 500)과 같은 트레이스로 맞추면 목록→상세 왕복이 실제로 증명된다 |
| ② 5xx 비율 초과가 사람에게 닿는다 | AlertRules 규칙(5XX_RATE·GT·1.0%·300초·CRITICAL) → Alerts 이벤트(FIRING·observed_value 4.2%) → 드로어 알림 발송 이력(SLACK/EMAIL/WEBHOOK SUCCESS, PAGERDUTY FAIL 재시도 2) | 충족 | 사슬 자체는 완비다. 다만 사슬의 가운데 고리인 observed_value 4.2%가 드로어 안에만 있고 이벤트 표 컬럼은 드로어에 가려 안 보인다. 표에서도 4.2%가 읽혀야 "규칙→이벤트" 연결이 한눈에 선다 |
| ③ 에이전트별 CPU·힙·GC + 스레드 덤프 | Inspector 4차트(CPU 37.2% / 힙 1,312MB / GC 48ms·분 / 스레드 142) + InspectorDumpModal 3단계 진행 + ThreadDump 상태별 집계·스택 트레이스 | 충족 | ThreadDump 상단 칩 RUNNABLE 58 + WAITING 61 + TIMED_WAITING 19 + BLOCKED 4 = 142가 헤더 thread_count와 정확히 맞아떨어진다. 잘 만들었다. 보완은 시각 결함(스택 트레이스 우측 잘림)뿐 |
| ④ 느린 구간 드래그 → API 목록 | Transactions 스캐터 드래그 사각형 + "선택 47건 · 실패 9" 말풍선 + 조건 칩 5개 + 하단 요청 목록 8행 | 충족 | 드래그 사각형의 y 하단이 1000ms 눈금인데 조건 칩은 min_duration_ms 900이다. 칩을 1000으로 맞추거나 사각형을 900 위치로 내려 좌표와 조건을 일치시켜야 "긁은 그대로가 조건이 된다"는 설득이 산다 |
| ⑤ 재배포 없이 샘플링률·임계값 변경 | Settings 샘플링 카드(슬라이더·expected_version=7·409 배너) + AlertRules 모달(threshold 1.0·window_sec 300 + "재배포 없이 즉시 적용 · 탐지가 10~30초 주기로 다시 읽음") | **부족** | 임계값 쪽(AlertRules)은 문구까지 완벽하다. 샘플링률 쪽은 슬라이더 핸들이 value=5로 트랙 맨 왼쪽에 있는데 모달이 그 영역을 정확히 덮어, 화면에 남는 건 "75% 100% 5.0%"라는 읽을 수 없는 조각뿐이다. ⑤의 절반이 시각적으로 증명되지 않는다 |
| + 파수꾼: 스택 다운 시 1분 내 알림 | Watchdog 관통 경로 8단계(+0.00s→age_sec 12) + 카나리 이벤트 표(STALE age_sec 74 > threshold 60 → "Slack 직접 발송, 알림 서비스 미경유") + 주기 30초 + Dead Man's Switch | 충족 | 판정 근거는 완비인데 "1분 내"라는 수치가 화면에 없다. 이벤트 표 조치 컬럼에 발송 시각(13:59:47 등)이나 "탐지 후 17초"를 넣으면 완료조건이 표에서 직접 읽힌다 |

---

## C. 시각 결함

| 화면 | 위치 | 문제 | 심각도 | 조치 |
|---|---|---|---|---|
| Alerts | 이벤트 표 우측, x≈960 | 드로어 480px가 observed_value·fired_at 두 컬럼과 세 번째 요약 카드, from/to 필터를 통째로 덮음. 표 헤더가 agent_key 중간에서 잘림 | H | 표 컬럼 순서를 state·severity·service_name·observed_value·fired_at·rule_name으로 바꿔 왼쪽 940px 안에 핵심 4개가 들어오게 하고, rule_name·agent_key를 오른쪽으로 밀기 |
| AlertRules | 규칙 표 중앙, x≈400~1040 | 모달 640px가 service_name·metric_kind·조건(operator/threshold/window_sec) 컬럼을 전부 덮음. 남은 건 스위치·name·severity·채널수·updated_at | H | 모달을 우측으로 옮겨(좌측 여백 280px → 660px) 표 왼쪽 절반이 드러나게 하거나, 모달을 드로어(우측 480)로 바꿔 표 전체를 살리기 |
| Settings | 샘플링 카드 좌측, x≈440~1000 | 모달이 슬라이더 핸들(5%)·0/25/50 눈금·현재 sampling_rate 값을 덮어, 화면에는 "75% 100% 5.0%"만 남음 | H | 모달을 상단으로 올리고(y 100~560) 샘플링 카드를 그 아래로 내리거나, 모달을 좌측 서비스 목록 위에만 겹치게 배치 |
| TraceDetail | 타임라인 축 우측 상단, x≈1355 | 눈금 "800"과 총 길이 라벨 "842 ms"가 겹쳐 "8842 ms"로 읽힘 | H | 마지막 눈금을 800에서 지우고 축 끝을 842 ms 라벨 하나로 처리 |
| TraceDetail | 스팬 상세 events 표 마지막 행, y≈1070 | 아트보드 높이 1100 끝에서 exception 행이 하단 패딩 없이 잘림 | H | 높이를 1160으로 늘리거나 events 표를 2행으로 줄이고 카드 하단 패딩 16px 확보 |
| ThreadDump | 스택 트레이스 코드 블록 우측, x≈1400 | 긴 줄이 카드 경계에서 잘림. 줄임표도 가로 스크롤 표시도 없어 `InvocableHandlerMethod.jav`에서 끊김 | H | 긴 프레임 줄을 패키지 축약(`o.s.w.m.s.InvocableHandlerMethod.doInvoke(...:895)`)으로 바꾸고 하단에 "가로 스크롤" 힌트 바 추가 |
| ServerMap | shop-gateway → shop-order 간선 라벨, x≈450 | 소스의 `12.4k · 214ms`가 노드 박스에 가려 `2.4k · 214ms`로 보임. 노드 cnt 12,540과 어긋나 보이는 착시 | H | 라벨을 간선 위쪽으로 12px 띄우고 흰 배경 패딩 2px 주기. 오른쪽 `9.2k · 63ms`도 같은 문제 |
| Settings | 감시 대상 서비스 표 name 컬럼 | 컬럼 폭이 좁아 `shop-order`가 `shop-` / `order` 두 줄로 쪼개짐. 6행 중 5행이 같은 증상 | H | name 컬럼 min-width 120px, `white-space:nowrap` 지정. 필요하면 표시명 컬럼을 줄이기 |
| Errors | 실패 스팬 표 span_name·exception_message | `POST /api/v1/ord…` `GET /internal/st…` `카드사 응답 한도 …`처럼 거의 모든 값이 잘림. agent_key도 `…w9j5`에서 끊김 | M | span_name 컬럼 폭을 220px로 넓히고 exception_message는 2행 줄바꿈 허용. duration_ns→ms 컬럼은 80px로 축소 가능 |
| Alerts | 드로어 알림 발송 이력 sent_at 컬럼, x≈1420 | 값이 아트보드 우측 경계에 붙어 잘릴 위험. 여백 0 | M | 드로어 내부 우측 패딩 20px 확보, sent_at을 `14:05:31` 대신 `+1s` 상대 표기로 축약 |
| Logs | thread 컬럼 | 10행 중 9행이 동일한 `http-nio-808…`이고 글자색이 다른 컬럼보다 옅어 대비가 낮음 | M | 색을 ink-3(#5E6875)로 통일하고, 포트 자리까지 보이도록 폭을 90px로 |
| Logs | level 칩 그룹 | WARN은 채움(amber), ERROR는 테두리형으로 선택 상태 표현이 서로 다름 | M | 선택된 칩은 soft 배경 + 진한 글자로 통일, 비선택은 surface-2 배경 |
| AlertChannels | 토스트 좌측 경계, x≈1020 | 토스트가 모달 우측 모서리와 맞닿아 두 면이 겹쳐 보임 | M | 토스트를 y 상단 24px, 우측 24px로 올려 모달과 40px 이상 띄우기 |
| InspectorDumpModal | "요청 중…" 버튼 | 연한 파랑 배경에 흰 글자로 대비 약 2.6:1 | M | 비활성 버튼은 surface-2 배경 + ink-3 글자로 바꿔 대비 확보 |
| Login | 좌측 패널 y 330~860 | 파이프라인 도식 아래 530px가 비어 균형이 깨짐 | M | 도식 아래에 감시 대상 6서비스 칩 줄 또는 "우리 서비스 6개 · 파드 14" 요약을 넣어 여백 흡수 |
| Login | 배너 2개 | 세션 만료(amber)와 401(red)이 동시에 떠 서로 모순되는 상태 | M | 401만 남기고 세션 만료는 하단 설명문으로 강등 |
| Logs | 검색 결과 카드 하단 y 800~880 | 표 아래 빈 공간 80px | L | 행을 2개 더 넣거나 카드 높이 축소 |
| ThreadDump | 덤프 목록 헤더 `7 / 24` | 무슨 뜻인지 읽히지 않음 | L | `24건 중 7건`으로 표기 |
| 전 화면 | 사이드바 워드마크 하단 | `Pinpoint형 APM · 자기 감시`로, design-spec의 `Pinpoint형 APM`과 다름 | L | 스펙을 현재 문구로 갱신하거나 문구를 되돌리기(15개 파일 모두 동일하므로 어느 쪽이든 일관) |

---

## D. 일관성

**용어·배지** — 대체로 잘 통일됐다. FIRING/RESOLVED, UP/DOWN/UNKNOWN, SUCCESS/FAIL, SLACK/EMAIL/WEBHOOK/PAGERDUTY, CRITICAL/WARNING/INFO가 색·모양까지 일치한다. 예외 둘:

1. Watchdog의 서비스 상태 배지만 `READY`(영문 enum)와 `재시도 중`(한국어 서술)을 섞어 쓴다. 다른 화면이 전부 영문 enum이므로 `READY` / `NOT_READY`로 맞추고, "알림 실패 2건" 칩이 사유를 설명하게 하면 된다. (M)
2. AlertChannels의 테스트 결과는 `SUCCESS/FAILED`, Alerts의 발송 이력은 `SUCCESS/FAIL`이다. 이건 api-spec #19와 #24가 실제로 다른 enum이라 **디자인이 맞다**. 다만 사용자에게는 오타로 보이므로 채널 카드 캡션에 "채널 테스트 결과" / 드로어에 "발송 이력 결과"라고 문맥을 붙여두면 오해가 없다. (L)

**현재 시각** — 화면마다 다르다. (M)

| 화면 | 그 화면이 암시하는 "지금" |
|---|---|
| ServerMap 부제 | 14:05:30 |
| ServerMap 미니 스캐터 / Transactions / UrlStats / Errors 축 | 15:00 |
| Inspector 차트 축 | 14:05 |
| Alerts 드로어 "12분째 진행 중" (fired_at 14:05:30) | 14:17 |
| ThreadDump requested_at / InspectorDumpModal | 14:05:41 / 14:06:03 |

15:00을 "지금"으로 정하고 1h 창을 14:00~15:00으로 통일해야 한다. Inspector 축은 14:00~15:00으로, ServerMap 부제는 15:00:00으로, 드로어 fired_at은 14:48:30(12분 전)으로 고치면 전 화면이 맞는다.

**agent_key 형식** — `<service>-<5자>-<5자>` 규칙이 전 화면에서 지켜진다. 문제없음.

**서비스명 집합** — 상단바 드롭다운은 5개(gateway/order/payment/inventory/user), Settings 표는 6개(+shop-coupon)다. Logs의 logger에 `com.shop.order.coupon.Coup…`이 있으니 shop-coupon은 order 안의 패키지이지 별도 서비스로 볼 근거가 약하다. Settings 표에서 shop-coupon을 빼고 5개로 통일하거나, 드롭다운에 shop-coupon을 추가하고 ServerMap 노드에도 넣어야 한다. (M)

**URL 경로 접두사** — 세 갈래다. (M)

| 화면 | 형태 |
|---|---|
| Transactions / UrlStats / Errors | `/api/v1/orders/{orderId}` |
| ServerMap 상위 URL | `/api/orders/{orderId}` |
| TraceDetail 스팬 이름 | `/api/orders`, `/api/stock`, `/payments` |

감시 대상은 쇼핑몰이고 `/api/v1`은 모니모 API 서버의 경로이므로, 쇼핑몰 스팬 이름에 `/api/v1`을 쓰는 게 오히려 혼동을 준다. 쇼핑몰 쪽은 `/api/orders` 계열로 통일하고 모니모 API 경로 표기(`GET /traces/scatter` 등)와 시각적으로 구분하는 쪽을 권한다.

**이메일 도메인** — 같은 사람 김승조가 Login·Settings에서는 `seungjo@monimo.dev`, ThreadDump·InspectorDumpModal에서는 `seungjo@monimo.io`다. 채널·수집기 엔드포인트는 `.io`와 `.dev`가 섞여 있다. `monimo.io`로 통일 권장. (M)

**축약 UUID 충돌** — `a3f1…9c2e`가 Transactions·Errors에서는 trace_id, Alerts 드로어에서는 alert_rule_uuid, Settings에서는 application_uuid, ThreadDump에서는 dump_uuid 꼬리로 쓰인다. 서로 다른 엔티티가 같은 축약값을 쓰면 데모 중 "같은 건가요?" 질문이 나온다. 엔티티마다 다른 8자리를 배정할 것. (M)

**교차 화면 데이터 충돌** — 같은 행이 화면마다 다르다. (M)

| 값 | Transactions | Errors |
|---|---|---|
| 14:38:12.481 / 2,584ms | GET /api/v1/orders/{orderId}, trace a3f1…9c2e | POST /api/v1/ord…, trace a3f1…9c2e |
| 14:36:41.207 / 2,402ms | http_status 500, trace 7b24…4a10 | http_status 504, trace c98d…21f7 |

두 표의 공통 8행을 한 벌로 만들어 양쪽이 같은 값을 쓰게 해야 한다.

**경보 3건 충돌** — ServerMap 상단 띠와 Alerts 표의 FIRING 3건이 서로 다르다. (H)

| | ServerMap 띠 | Alerts 표 |
|---|---|---|
| 1 | CRITICAL shop-payment 5XX_RATE 4.2% | FIRING CRITICAL shop-payment 5xx 비율 초과 |
| 2 | WARNING shop-order P95_LATENCY 1,240ms | FIRING **CRITICAL** shop-order p95 지연 초과 |
| 3 | CRITICAL shop-inventory **AGENT_DOWN** | FIRING **WARNING** shop-inventory **힙 사용률 경고** |

Alerts 쪽을 정본으로 삼고 ServerMap 띠를 맞추면 된다(shop-inventory 힙 사용률 WARNING). AGENT_DOWN은 Alerts에서 shop-gateway RESOLVED로 이미 존재한다.

---

## E. UX 논리

**왕복 링크** — 서버맵→트랜잭션→트레이스→인스펙터 축은 잘 이어진다. TraceDetail이 "이 파드 인스펙터 보기"와 "트랜잭션 목록" 버튼을 양쪽에 달았고, Errors·Logs 표의 trace_id 8~10개가 모두 TraceDetail로 간다. Inspector는 "덤프 이력"으로 ThreadDump에 닿는다. 끊긴 곳은 넷이다.

1. **Login의 "로그인" 버튼에 링크가 없다.** Login에서 나가는 유일한 링크가 "관리자에게 문의" → Settings.dc.html인데, 이건 의미도 어긋난다(비밀번호를 잊은 VIEWER가 관리자용 설정 화면에 떨어진다). 클릭 데모의 출발점이 막혀 있으므로 로그인 버튼을 ServerMap.dc.html로 잇고, 문의 링크는 `mailto:` 또는 비링크 텍스트로 바꿀 것. (H)
2. **ThreadDump의 "다시 요청" 버튼이 Inspector.dc.html로 간다.** 주 버튼 자리에 있는 재요청 동작이 상위 화면 이동으로 연결돼 있다. 재요청은 InspectorDumpModal.dc.html로 보내고, 대신 상단에 "← 인스펙터" 되돌아가기 링크를 따로 두는 게 맞다. (H)
3. **Alerts의 이벤트 표 8행이 모두 AlertRules.dc.html로 간다.** api-map #45는 행 클릭 시 이벤트 상세 드로어를 열라고 한다. 드로어는 같은 아트보드에 이미 열려 있으므로 행 링크는 Alerts.dc.html(자기 자신)로 두고, 규칙으로 가는 건 드로어 안의 "규칙 보기" 하나면 충분하다. (M)
4. **파수꾼 배지가 어디서도 플랫폼 상태로 이어지지 않는다.** 사이드바 하단 카나리 미니 카드가 `<a>`가 아니어서, 15개 파일 전부 Watchdog로 가는 링크가 사이드바 메뉴 하나뿐이다. ia.md는 "배지 클릭 → 플랫폼 상태"를 명시한다. 미니 카드를 Watchdog.dc.html 링크로 감쌀 것. 덧붙여 Watchdog의 마지막 카나리 `trace_id 8f2c…41ab`도 TraceDetail로, STALE 이벤트도 Alerts로 이어야 파수꾼이 고립되지 않는다. (M)

**권한 노출** — `VIEWER`라는 단어가 Login 한 곳에만 나온다. ADMIN 전용 문이 14개인데, 화면에서 권한을 암시하는 건 InspectorDumpModal의 "권한 ADMIN" 캡션과 Settings 두 곳뿐이다. VIEWER로 로그인했을 때 "스레드 덤프 요청", "규칙 만들기", "채널 등록", "적용", "감시 대상에서 제외", enabled 스위치가 어떻게 보이는지 정보가 하나도 없다. 한 화면(AlertRules 권장)에 ADMIN 버튼을 비활성 + "ADMIN만 바꿀 수 있다" 툴팁으로 그린 VIEWER 상태 변형을 하나 추가하면 14개 문의 처리 방식이 한 번에 정해진다. (H)

**에러 상태** — 노출이 충실하다. 409 APPLICATION_NAME_TAKEN(Settings 모달 인라인), 409 CONFIG_VERSION_CONFLICT(샘플링 배너), 409 CONFLICT 예고(삭제 버튼 캡션), 503 AGENT_NOT_REACHABLE·THREAD_DUMP_TIMEOUT(덤프 모달 + 직전 실패 배너), 401 UNAUTHENTICATED(Login), 404 SIGNAL_EXPIRED(Logs 상단 TTL 안내), 429 rate_limited(발송 이력 PAGERDUTY FAIL)까지 7종이 실제 위치에 있다. 빠진 건 422 TIME_RANGE_TOO_WIDE뿐이다. 시간 범위 세그먼트의 "사용자 지정" 옆에 "최대 7일" 캡션을 달면 채워진다. (L)

**데이터 슬롭** — 전반적으로 억제가 잘 됐다. 검산되는 숫자가 많다(Errors 91+221=312, 예외 상위4 289+23=312, ThreadDump 58+61+19+4=142, Inspector UP4+DOWN1+UNKNOWN1=6, Watchdog 관통 경로 누적 초). 남은 슬롭 두 가지:

- 표 하단 문구가 `312건 중 8건 표시 · limit 50`, `47건 중 8건 표시 · limit 50`, `총 42개 URL 중 8개 표시 · limit 50`으로 되어 있어 limit 50인데 8건만 보인다는 모순이 세 화면에 반복된다. "8건 표시"를 빼고 `312건 · limit 50 · 커서 페이징`으로 고칠 것. (M)
- UrlStats는 총 42개 URL인데 "다음 50건" 버튼이 있다. 42 < 50이므로 다음 쪽이 없다. 버튼을 비활성 상태로 그리거나 총계를 128개로 올릴 것. (L)

**필터와 결과의 불일치** — 두 화면에서 필터 값이 표 내용과 정면으로 어긋난다. 심사 자리에서 가장 먼저 지적당할 대목이다. (H)

- **Errors**: service_name=shop-order, exception_type=PaymentDeclinedException으로 검색했는데 결과 8행에 shop-payment·shop-inventory·shop-gateway·shop-user가 섞여 있고 SQLTimeoutException·HttpClientErrorException·NullPointerException이 나온다.
- **ThreadDump**: service_name=shop-order 필터인데 목록에 shop-payment-5a3c8-b7n4k, shop-gateway-2f61d-r8m5v, shop-inventory-9b47e-k3t2p가 들어 있다.

두 화면 모두 필터를 "전체"로 바꾸는 쪽이 손이 덜 간다. Errors는 exception_type 입력칸을 비우고 service_name을 "전체"로, ThreadDump는 service_name select를 "전체"로 두면 표를 건드릴 필요가 없다.

**서비스 등록 모달의 논리** — 신규 등록 모달인데 display_name "주문", description "주문 접수·결제 요청·재고 차감을 묶어 처리하는 핵심 서비스"가 미리 채워져 있다. 이건 이미 존재하는 shop-order의 값이다. 409를 보여주려는 의도는 알겠으나, 등록 폼에 기존 레코드 값이 채워져 있으면 "수정 화면인가"로 읽힌다. name만 `shop-order`로 두고 나머지 두 칸은 비우면 409 시연은 그대로 살고 논리는 맞아진다. (M)

---

## F. 설계 문서 피드백 (API/ERD/기능명세로 되돌릴 것)

1. **내부 문 #4를 VIEWER+로 재노출해야 한다.** api-map에 이미 적힌 항목의 확정이다. Watchdog 상단 배너와 15개 화면 전부의 사이드바 미니 카드가 `GET /internal/canary/freshness`의 `age_sec`·`threshold_sec`·`fresh`를 그대로 쓴다. 화면이 내부 토큰을 들 수 없으므로 `GET /api/v1/platform/canary`(VIEWER+, 응답은 #4와 동일 3필드) 1행을 51번으로 추가할 것.

2. **우리 서비스 6개의 readyz 상태를 읽을 문이 없다.** Watchdog "우리 서비스 6개" 카드는 서비스명·READY 여부·의존 저장소 목록·파드 수·확인 시각 5개 값을 쓰는데, 지금 명세에는 각 서비스가 `/readyz`를 연다는 기술만 있고 **화면이 6개를 한 번에 조회할 문이 없다**. 화면이 6개 서비스에 직접 붙는 건 "화면은 API 서버 하나만 호출한다"는 핵심 규칙 위반이다. `GET /api/v1/platform/services`(VIEWER+, 응답 `service_name, ready, deps[], pod_count, checked_at`)를 52번으로 추가하고, API 서버가 6개 readyz를 대신 훑도록 할 것.

3. **Dead Man's Switch 상태를 읽을 문이 없다.** Watchdog가 "마지막 핑 38초 전"과 카나리 이벤트의 `DMS_MISSED` 종류를 표시하는데, 이건 Healthchecks.io 쪽 상태다. 51번 응답에 `dms_last_ping_at`을 얹든지, 카나리 이벤트 이력 자체를 문으로 여는(`GET /api/v1/platform/canary/events`) 결정이 필요하다. 지금 화면의 "최근 카나리 이벤트" 표 6행은 근거 API가 없는 상태다.

4. **카나리 이벤트의 `종류` enum이 ERD에 없다.** 화면은 `FRESH`·`STALE`·`DMS_MISSED` 3값을 쓴다. 파수꾼이 이 이력을 어디에 적는지(PostgreSQL 새 테이블인지, 적지 않고 매번 계산하는지)가 정해져 있지 않다. 적는다면 `canary_events(ts, kind, age_sec, threshold_sec, action)` 테이블이 ERD에 들어가야 한다.

5. **스레드 덤프 TTL이 명세와 화면에서 다르다.** api-spec은 404 SIGNAL_EXPIRED 항목에서 "트레이스·덤프 93일"이라고 하는데, ThreadDump 화면 부제는 "TTL 3일"이고 목록 하단은 "3일 지난 덤프는 S3로 내려가 목록에서 빠진다"고 쓴다. Logs 화면의 "TTL 7일 · 그 뒤 S3 cold 90일 = 97일" 표기와 형식도 다르다. 덤프도 "TTL 3일 · 그 뒤 S3 cold 90일 = 93일"이 의도였던 것으로 보이므로, **api-spec에 신호별 hot/cold 보존 기간을 표로 명시**하고 화면 문구를 그 표에 맞출 것. 지금 상태로는 화면이 명세를 어긴 것으로 읽힌다.

6. **에이전트 설치 명령의 endpoint 포트가 스펙과 어긋난다.** Settings의 설치 명령은 `-Dotel.exporter.otlp.endpoint=https://collector.monimo.dev:4317`인데, api-spec의 gRPC 절은 포트 4317에 **mTLS 클라이언트 인증서**를 요구한다. `https://`만으로는 클라이언트 인증서 지정이 빠져 있다. 화면 문구에 `-Dotel.exporter.otlp.client.key`·`client.certificate` 두 줄을 더하거나, 명세에 "에이전트 배포 시 필요한 JVM 옵션 전체"를 FN-12 아래에 한 번 적어둘 것.

7. **`GET /metrics/series`의 source_table 선택 규칙을 화면이 어기고 있다.** Inspector의 "스레드 수" 차트는 `metrics_raw` 배지를 달았는데, 시간 범위가 1시간(step 60)이므로 §0 규칙상 `metrics_1m`이 나와야 한다. 같은 화면의 "현재 스레드 142"도 api-spec #21이 `metrics_1m` 기반이라고 적는다. 화면 배지를 `metrics_1m`으로 고치는 게 맞지만, 이 혼동이 생긴 이유는 **step→source_table 대응표가 명세에 글로만 있고 표가 없기 때문**이다. api-spec §0 시간 범위 절에 3행짜리 대응표를 넣을 것.

8. **화면 목록에 아트보드 2개가 빠져 있다.** ia.md는 13화면인데 실제 작성물은 15개다. `UrlStats.dc.html`(S02b, URL 통계 탭 활성)과 `InspectorDumpModal.dc.html`(S04b, 덤프 요청 모달)이 표에 없다. ia.md 표에 두 행을 추가할 것.

9. **ia.md의 파수꾼 배지 위치와 링크 대상이 design-spec·구현과 다르다.** ia.md는 "상단바 우측 파수꾼 배지 … 클릭 → S09"라고 하는데, design-spec은 "사이드바 하단 미니 카드"로 적었고 15개 화면 모두 사이드바 하단에 그렸다. 또 파수꾼 화면은 S09(설정)가 아니라 S10(플랫폼 상태)이다. ia.md를 사이드바 하단 · S10으로 고칠 것.

---

## 수정 지시 목록

### 필수 (H) — 15건

**Alerts**
1. 이벤트 표 컬럼 순서를 `state · severity · service_name · observed_value · fired_at · rule_name · agent_key`로 바꿔, 드로어에 가리지 않는 왼쪽 940px 안에 observed_value와 fired_at이 들어오게 한다.
2. ServerMap 상단 띠의 경보 3건을 Alerts 표의 FIRING 3건과 일치시킨다(3번째를 `WARNING shop-inventory 힙 사용률 경고`로, AGENT_DOWN은 shop-gateway RESOLVED이므로 띠에서 뺀다).

**AlertRules**
3. 모달의 좌측 시작 위치를 x=280에서 x=660으로 옮겨(또는 우측 480px 드로어로 바꿔) 규칙 표의 `service_name · metric_kind · 조건` 세 컬럼이 최소 한 행은 읽히게 한다.
4. ADMIN 전용 동작의 VIEWER 상태를 이 화면에 한 번 그린다: "규칙 만들기" 버튼과 enabled 스위치를 비활성(surface-2 배경 · ink-3 글자)으로, 표 위에 "VIEWER는 읽기만 · 변경은 ADMIN" 캡션 한 줄.

**Settings**
5. 서비스 등록 모달을 y 100~560 위치로 올려 샘플링률 카드의 슬라이더 핸들(value=5)·0/25/50 눈금·현재값이 모두 드러나게 한다.
6. 감시 대상 서비스 표의 name 컬럼에 `min-width:120px; white-space:nowrap`을 주어 `shop-order`가 두 줄로 쪼개지지 않게 한다.

**TraceDetail**
7. 타임라인 축의 마지막 눈금 "800"을 지워 총 길이 라벨 "842 ms"와의 겹침을 없앤다.
8. 아트보드 높이를 1100에서 1160으로 늘려(`$preview.height`와 루트 div 동시 수정) events 표 마지막 행 아래에 16px 패딩이 남게 한다.

**ThreadDump**
9. 스택 트레이스의 긴 프레임 줄을 패키지 축약형(`o.s.w.m.s.InvocableHandlerMethod.doInvoke(…:895)`)으로 바꿔 카드 우측 경계에서 잘리지 않게 한다.
10. service_name 필터를 "전체"로 바꿔, 목록에 섞인 shop-payment·shop-gateway·shop-inventory 덤프와의 모순을 없앤다.
11. 부제 "TTL 3일 · CH thread_dumps"를 "TTL 3일 · 그 뒤 S3 cold 90일 (합계 93일)"로 고쳐 api-spec의 93일과 맞춘다.

**Errors**
12. service_name을 "전체"로, exception_type 입력칸을 비워 필터와 결과 8행의 모순을 없앤다.

**ServerMap**
13. `12.4k · 214ms`와 `9.2k · 63ms` 간선 라벨을 간선 위 12px로 띄우고 흰 배경 패딩 2px을 줘 노드 박스에 가려 앞자리가 잘리는 현상을 없앤다.

**Login**
14. "로그인" 버튼을 `<a href="ServerMap.dc.html">`으로 감싸 클릭 데모의 출발점을 잇고, "관리자에게 문의"의 Settings 링크는 뗀다.

**ThreadDump (링크)**
15. 주 버튼 "다시 요청"의 링크를 Inspector.dc.html에서 InspectorDumpModal.dc.html로 바꾸고, 페이지 제목 왼쪽에 "← 인스펙터" 되돌아가기 링크를 따로 단다.

### 가능하면 (M) — 20건

**전 화면**
16. 현재 시각을 15:00으로 통일한다: Inspector 4차트 축을 14:00~15:00으로, ServerMap 부제를 `2026-09-21 15:00:00 KST`로, Alerts 드로어의 fired_at을 14:48:30으로(그래야 "12분째 진행 중"이 맞는다).
17. 사이드바 하단 카나리 미니 카드를 `<a href="Watchdog.dc.html">`으로 감싼다(15개 파일 공통).
18. 축약 UUID를 엔티티별로 분리한다: trace_id는 현재대로 두고, alert_rule_uuid를 `c71d…8b04`, application_uuid를 `e2a9…36f1`로 바꿔 `a3f1…9c2e` 중복을 없앤다.
19. 이메일 도메인을 `monimo.io`로 통일한다(Login·Settings의 `seungjo@monimo.dev` 2곳).
20. 표 하단 문구에서 "8건 표시"를 빼고 `312건 · limit 50 · 커서 페이징` 형태로 통일한다(Errors·Transactions·UrlStats).

**Transactions / Errors**
21. 두 표의 공통 8행을 한 벌로 맞춘다. 특히 `14:38:12.481`의 메서드(GET/POST)와 `14:36:41.207`의 http_status(500/504)·trace_id가 지금 서로 다르다.
22. Transactions의 드래그 조건 칩 `min_duration_ms 900`을 1000으로 고쳐 사각형 하단 눈금과 일치시킨다.

**TraceDetail**
23. trace_id를 Transactions 1행의 `a3f1…9c2e`로 바꾸고 시작 시각을 14:38:12.481, 총 소요를 2,584ms로 맞춰 목록→상세 왕복이 같은 트레이스로 이어지게 한다.

**Errors**
24. span_name 컬럼 폭을 220px로 넓히고 exception_message는 두 줄 줄바꿈을 허용해 잘림을 줄인다.

**Alerts**
25. 이벤트 표 8행의 링크를 AlertRules.dc.html에서 Alerts.dc.html로 바꾼다(규칙으로 가는 길은 드로어의 "규칙 보기" 하나면 충분).

**Settings**
26. 서비스 등록 모달의 display_name·description 칸을 비운다(name만 `shop-order`로 남겨 409를 시연).
27. 서비스 목록에서 shop-coupon을 빼 상단바 드롭다운 5개와 집합을 맞춘다.

**Watchdog**
28. 서비스 상태 배지 `재시도 중`을 `NOT_READY`로 바꾸고, 사유는 옆의 "알림 실패 2건" 칩이 설명하게 둔다.
29. 카나리 이벤트 표의 조치 컬럼에 발송 시각을 넣는다(`Slack 직접 발송 13:59:47 · 탐지 후 17초`). 이 한 줄이 "스택 다운 시 1분 내 알림" 완료조건의 증거가 된다.

**ServerMap**
30. 에러가 있는 간선의 라벨을 `9.6k · err 402 · 386ms` 3값으로 바꿔 api-map #41의 err_cnt를 충족한다.

**Inspector**
31. "스레드 수" 차트의 source_table 배지를 `metrics_raw`에서 `metrics_1m`으로 고친다(1h 범위는 step 60 → 1분 롤업).

**AlertChannels / InspectorDumpModal / Logs / Login**
32. AlertChannels 토스트를 우측 상단 24px로 올려 모달과 40px 이상 띄운다.
33. InspectorDumpModal의 "요청 중…" 버튼을 surface-2 배경 + ink-3 글자로 바꿔 대비를 확보한다.
34. Logs의 level 칩 선택 상태를 통일한다(WARN 채움 / ERROR 테두리 → 둘 다 soft 배경 + 진한 글자).
35. Login의 amber "세션 만료" 배너를 빼고 401 배너 하나만 남긴다. 좌측 패널 파이프라인 도식 아래 빈 530px에는 감시 대상 서비스 칩 줄을 넣고, 도식에 빠진 "적재 처리기"를 Kafka와 ClickHouse 사이에 추가한다.

### 선택 (L) — 6건

36. Transactions의 히트맵 미리보기 띠 높이를 키우고 축 눈금·is_error 범례를 붙여 #9를 부분에서 보임으로 올린다.
37. Inspector 4차트 아래에 점선 플레이스홀더 1장을 넣어 "지표 추가"로 붙는 사용자 지정 차트 자리를 표시한다(#15).
38. 시간 범위 세그먼트의 "사용자 지정" 옆에 "최대 7일" 캡션을 달아 422 TIME_RANGE_TOO_WIDE를 노출한다.
39. ThreadDump 덤프 목록 헤더 `7 / 24`를 `24건 중 7건`으로 고친다.
40. UrlStats의 "다음 50건" 버튼을 비활성으로 그린다(총 42개 < limit 50).
41. Logs의 thread 컬럼 글자색을 ink-3로 올리고 폭을 90px로 넓혀 포트 자리까지 읽히게 한다.

---

## 반영 목록 (수정 1회, 2026-09-21, 아티팩트 v3)

### H 15건 — 전부 반영
| # | 반영 |
|---|---|
| 1 | Alerts 컬럼 순서 state·severity·service_name·observed_value·fired_at·rule_name·agent_key(+resolved_at 맨 끝) |
| 2 | ServerMap 띠 = Alerts FIRING 3건(5XX_RATE 4.2% / P95 1,240ms / HEAP 91.3%), AGENT_DOWN 제거 |
| 3 | AlertRules 모달 x=660 + 표 컬럼 enabled·service_name·metric_kind·조건 우선 → 8행 전부 조건까지 읽힘 |
| 4 | AlertRules 표 헤더에 "VIEWER는 읽기만 · 변경은 ADMIN" 칩 |
| 5 | Settings 모달 상단 이동 → 슬라이더·눈금·현재값 노출 (현재값·v7 배지는 카드 우측으로) — 추가로 모달을 좌측(서비스 목록 위)으로 옮겨 409 배너까지 노출 |
| 6 | Settings name 컬럼 nowrap(124px), 좌측 열 360px |
| 7 | TraceDetail 축 마지막 눈금 제거, 축 끝 `2,584 ms` 라벨 |
| 8 | TraceDetail 높이 1160 |
| 9 | ThreadDump 프레임 패키지 축약 |
| 10 | ThreadDump service_name 필터 "전체"(상단바도) |
| 11 | ThreadDump TTL 문구 "3일 · 그 뒤 S3 cold 90일 (합계 93일)" |
| 12 | Errors 필터 service_name "전체"·exception_type 비움 |
| 13 | ServerMap 간선 라벨 선 위 + 흰 배경, gateway 노드 폭 조정 |
| 14 | Login 버튼 → ServerMap 링크, 관리자 문의 링크 제거 |
| 15 | ThreadDump "다시 요청" → InspectorDumpModal, "← 인스펙터" 링크 |

### M 20건 — 전부 반영 (판단 차이 포함)
16 현재 15:00 통일(축·부제·fired_at 14:48:30/14:44:10/14:41:44·모달 접수 15:00:19) · 17 파수꾼 미니 카드 `<a href=Watchdog>` 14개 셸 파일 전부 · 18 UUID 분리(alert_rule c71d…8b04, application e2a9…36f1, Errors·Logs 나머지 trace_id 새 값) · 19 monimo.io · 20 표 하단 `N건 · limit 50 · 커서 페이징` · 21 Transactions/Errors 공통 행 통일 + **trace a3f1…9c2e = `POST /api/v1/orders/{orderId}/pay` 2,584ms 500**(TraceDetail 루트와 동일) · 22 min_duration_ms 1000 · 23 TraceDetail = Transactions 1행 · 24 Errors span_name 200px(220 대신, 9컬럼 수용)·message 2줄·높이 1100 · 25 이벤트 행 링크 Alerts · 26 모달 display_name·description 비움 · 27 shop-coupon 제거 · 28 NOT_READY · 29 카나리 STALE 행 "Slack 직접 발송 13:59:47 · 탐지 후 17초" · 30 에러 간선 3값 · 31 metrics_1m · 32 토스트 우상단 · 33 요청 중 버튼 대비 · 34 이미 동일 구조(토큰 유지) · 35 Login 배너 제거·적재 처리기·서비스 칩

### L 6건 — 전부 반영
36 히트맵 띠 확대 + 눈금·범례 · 37 사용자 지정 차트 플레이스홀더(Inspector·DumpModal 높이 1184) · 38 "최대 7일" 캡션(ServerMap·Transactions·Watchdog) · 39 "24건 중 7건" · 40 UrlStats 다음 버튼 비활성 · 41 thread 164px

### F 9건 — 문서 처리
F1~F3 → api-map.md 하단 제안 #51~#53 · F4 canary_events ERD 후보 · F5~F7 명세 보완 메모(api-map.md) · F8·F9 ia.md 수정 완료. Notion 명세는 미수정(사용자 판단 후 반영).

### 미반영·잔여
- Inspector 파드 목록 상태 칩 4개가 260px 폭에서 2줄로 접힘(작성자 판단, 기능 영향 없음)
- Logs trace_id 12행을 Transactions 8건의 실행 구간·파드에 맞춰 재배정 완료(구간 검증 12/12) · Watchdog 시각도 15:00 기준으로 통일
