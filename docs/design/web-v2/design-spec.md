# 모니모니터링 v2 디자인 스펙 (아트보드 작성자 공통 규칙)

## 방향
"Ops console · 쿨 뉴트럴 라이트 · 데이터 밀도 우선". Pinpoint형 APM 운영 화면. 장식 없음, 정보 밀도 높고 상태색이 시선을 이끈다. 그라데이션·이모지·좌측 색 테두리 카드 금지.

## 폰트 (helmet의 Google Fonts link — 셸 정본은 서체별 2개, 합친 1개도 동일 렌더)
`<link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+KR:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500;600&display=swap" rel="stylesheet">`
- 본문/UI: `'IBM Plex Sans KR', 'Apple SD Gothic Neo', 'Noto Sans KR', sans-serif`
- ID·경로·수치·코드: `'IBM Plex Mono', 'SF Mono', Menlo, monospace`
- 크기: 페이지 제목 20/600, 절 제목 15/600, 본문 13/400, 표 13, 캡션 12/500, 큰 수치 28/600 mono, 마이크로 라벨 11/600 (ink-3, letter-spacing .02em)

## 색 토큰 (전부 인라인 style로 hex 직접 사용)
| 토큰 | hex | 용도 |
|---|---|---|
| canvas | #F3F5F8 | 본문 배경 |
| surface | #FFFFFF | 카드·표·패널 |
| surface-2 | #EEF1F5 | 표 헤더·입력 배경·비활성 |
| sidebar | #151A22 | 사이드바 배경 (다크) |
| sidebar-text | #C9D1DC / active #FFFFFF | 사이드바 글자 |
| border | #D9DEE6 | 기본 선 |
| border-strong | #B8C0CC | 입력 포커스 전, 구분 강조 |
| ink | #151A22 | 본문 글자 |
| ink-2 | #3E4753 | 보조 글자 |
| ink-3 | #5E6875 | 캡션·라벨 (흰 배경 대비 5.3:1) |
| accent | #3452D6 | 주 액션·활성·링크 |
| accent-ink | #22389A | 링크 hover·강조 텍스트 |
| accent-soft | #E4E9FB | 활성 배경·선택 |
| ok | #1F8A4C / soft #DDF3E6 | 정상·성공·해소 |
| warn | #B2680A / soft #FBEFD9 | 경고·지연·WARNING |
| crit | #C8362F / soft #FADDDB | 위험·실패·CRITICAL·5xx |
| muted-status | #6B7684 / soft #E9ECF0 | INACTIVE·비활성 |
| 서비스 팔레트 6 | #3452D6 #0F8B8D #B25E1F #7A3FBF #C0397A #4E7A1F | 서버맵 노드·트레이스 스팬 바 서비스별 색 (같은 채도·명도) |

## 기하
- 아트보드: 데스크톱 **1440 × 960** (내용이 더 길면 h를 1100까지 늘려도 됨, 잘림 금지). 루트 div는 `width:1440px;height:<h>px;box-sizing:border-box;display:flex;` 고정.
- 사이드바 224px 고정, 상단바 56px 고정, 본문 padding 24px, 카드 gap 16px, 반경: 컨트롤 6px · 카드 10px · 배지 999px
- 그림자: 카드 `0 1px 2px rgba(20,26,34,.06)`, 드로어/모달 `0 12px 32px rgba(20,26,34,.18)`
- 터치 타깃 ≥ 36px 높이(데스크톱), 버튼 높이 36px, 입력 36px, 표 행 40px
- 모든 형제 그룹은 flex/grid + gap. 인라인 마진 배치 금지.

## 컴포넌트 (마크업 관례)
- **버튼**: `<button>` 실제 요소. primary: bg accent, 글자 #fff, 13/600, padding 0 14px, 높이 36, radius 6. secondary: bg #fff, border 1px #B8C0CC, 글자 ink. danger: bg #fff, border crit, 글자 crit. 아이콘 전용은 aria-label.
- **입력/셀렉트**: `<label>` + `<input>`/`<select>`; 높이 36, border 1px #B8C0CC, radius 6, bg #fff, padding 0 10px, 13px.
- **스위치**: `<button role="switch" aria-checked>` 40×22, on=accent, off=#B8C0CC, 손잡이 18px 흰 원.
- **배지**: 999px, 12/600, padding 2px 8px, soft 배경 + 진한 글자 (ok/warn/crit/muted/accent).
- **표**: 헤더 bg surface-2, 12/600 ink-3, 행 높이 40, 행 border-bottom 1px border, 수치·ID 컬럼 mono, 우측 정렬 수치.
- **카드**: bg surface, border 1px border, radius 10, padding 16~20, 제목 15/600 + 우측 액션.
- **탭**: 밑줄형, 활성 accent 2px, 13/600.
- **드로어**: 우측 480px, 전체 높이, bg surface, 좌측 border, 그림자. 모달: 중앙 560~640px, 뒤 오버레이 rgba(21,26,34,.45). 모달/드로어는 본문 위에 `position:absolute`로 그린다(루트 div에 `position:relative`).
- **차트**: 인라인 `<svg>`로 그린다(polyline·rect·circle). 축 글자 11px mono ink-3, 격자선 #E6EAF0. 성공 점 accent 60% 투명, 실패 점 crit. 히트맵 셀은 accent 농도 5단계, 에러 셀은 crit 오버레이.
- **아이콘**: 인라인 stroke SVG 16~18px, stroke 1.75, currentColor. 이모지 금지.
- **상태 점**: 8px 원 ok/warn/crit/muted.

## 앱 셸 (로그인 제외 모든 화면 공통 — `_shell-reference.html` 그대로 복사해 본문만 교체)
- 사이드바(224, #151A22): 상단 워드마크 `MONIMO` (IBM Plex Mono 16/600 #fff) + 아래 11px `Pinpoint형 APM` (#C9D1DC). 메뉴 8개 순서: 서버맵 · 트랜잭션 · 인스펙터 · 에러 · 로그 · 경보 · 설정 · 플랫폼 상태. 활성 항목: bg rgba(255,255,255,.10), 글자 #fff, 좌측 3px accent 바(#7B8FF0). 하단: 파수꾼 상태 미니 카드(카나리 신선도) — "카나리 12초 전 · 정상" 형식, 상태 점.
- 상단바(56, #fff, border-bottom): 좌측 페이지 제목(20/600) + 부제(12 ink-3). 우측 컨트롤 그룹: 서비스 선택 select(값 예: `shop-order`), 시간 범위 세그먼트(5m·15m·**1h**·6h·24h·사용자 지정), 새로고침 버튼(아이콘+"30s"), 사용자 칩(이름 `김승조` + 역할 배지 `ADMIN`).
- 본문: `flex:1; background:#F3F5F8; padding:24px; overflow:hidden; display:flex; flex-direction:column; gap:16px`.

## 데이터 예시 규칙 (사실적, 데이터 슬롭 금지)
- 서비스명: `shop-gateway` `shop-order` `shop-payment` `shop-inventory` `shop-user` (감시 대상 쇼핑몰 MSA) + 우리 스택 6개 `monimo-api` `monimo-collector` `monimo-ingest` `monimo-detector` `monimo-notifier` `monimo-watchdog`(플랫폼 상태 화면에서만)
- agent_key: `shop-order-7d9f4-x2k8q` 형식(파드명). UUID는 `a3f1…9c2e` 처럼 축약 표기.
- 시간: `2026-09-21 14:05:30` KST 표기, 축은 `14:00 14:10 …`
- 지표 이름: `jvm.cpu.recent_utilization` `jvm.memory.used{heap}` `jvm.gc.duration` `jvm.thread.count` `process.cpu.utilization`
- 경보 metric_kind 7종: 5XX_RATE · 4XX_RATE · P95_LATENCY · CPU · HEAP · GC_TIME · AGENT_DOWN. severity: CRITICAL · WARNING · INFO. state: FIRING · RESOLVED. 채널 type: SLACK · EMAIL · WEBHOOK · PAGERDUTY. 파드 status: UP · DOWN · UNKNOWN (agents.status). 알림 result: SUCCESS · FAIL. operator: GT · GTE · LT · LTE. 역할: ADMIN · VIEWER.
- 숫자는 그 화면 의미에 맞게 최소한만. 각 표는 5~8행.

## 파일 규칙 (Design 타입 .dc.html)
- 스켈레톤: format.md 그대로. `<script src="./support.js"></script>` 줄 정확히 유지. `<html lang="ko">`. `<title>`은 화면 이름.
- `<helmet><style>`에는 폰트 link, `body{margin:0}`, `a` 색만. 나머지는 전부 인라인 style.
- `data-props`: `{"accent":{"editor":"color","default":"#3452D6"},"$preview":{"width":1440,"height":<h>}}` 만. renderVals는 `{accent}` 반환. 주 버튼·활성 색에만 `{{accent}}` 사용(나머지 hex 리터럴).
- 정적 목업(is_interactive 없음). 모든 UI는 x-dc 마크업, 스크립트로 DOM 생성 금지. 모든 요소 닫기, 속성 따옴표.
- 다른 화면으로 가는 링크는 `<a href="TraceDetail.dc.html">` 처럼 파일명으로.
