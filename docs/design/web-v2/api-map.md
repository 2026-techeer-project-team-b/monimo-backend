# API 50행 → 화면·UI 요소·트리거 매핑 (2026-09-21)

내부 문(권한 `내부`) 5건은 화면이 직접 호출하지 않는다. 대신 그 결과가 드러나는 화면 요소를 적고 `[간접]`으로 표기한다.

| # | Method Path | 화면 | UI 요소 | 트리거 |
|---|---|---|---|---|
| 1 | POST /agents/{agentUuid}/thread-dumps | S04 인스펙터 / S05 스레드 덤프 | 파드 상세 헤더 "스레드 덤프 요청" 버튼 → 진행 모달(timeout_ms 선택, 503 AGENT_NOT_REACHABLE·THREAD_DUMP_TIMEOUT 안내) | ADMIN 버튼 클릭 |
| 2 | GET /internal/service-health [간접] | S08 경보 이벤트 | 탐지가 이 문으로 판정한 결과 = 이벤트 표 observed_value 컬럼. 화면 내 별도 호출 없음 | 탐지 주기 조회 |
| 3 | GET /errors | S06 에러 분석 | 실패 스팬 표(trace_id·시각·span_name·http_status·exception_type·message), 행 클릭 → S03 | 페이지 진입·필터 변경·커서 페이징 |
| 4 | GET /internal/canary/freshness [간접] | S10 플랫폼 상태 + 전역 상단바 파수꾼 배지 | 카나리 신선도 age_sec/threshold_sec/fresh 표시. ※화면용 VIEWER+ 재노출 필요(리뷰 항목) | 파수꾼 주기 조회 |
| 5 | GET /traces/scatter | S02 트랜잭션 / S01 미니 스캐터 | 스캐터 차트 점(성공/실패 색), mode=bucketed 시 "격자 집계 중" 배지 | 시간범위·서비스·파드 변경 |
| 6 | GET /applications/{uuid} | S09 설정 | 서비스 상세 카드(표시명·설명·agent_count·등록일) | 서비스 목록 행 선택 |
| 7 | PATCH /alert-channels/{uuid}/enabled | S08c 채널 | 채널 카드 켜기/끄기 스위치 | 스위치 토글 |
| 8 | PATCH /applications/{uuid} | S09 설정 | 서비스 수정 모달(표시명·설명, name은 잠금 표시) 저장 | 저장 버튼 |
| 9 | GET /traces/heatmap | S02 트랜잭션 | 히트맵 뷰(1분×지연구간 격자, is_error 오버레이) | 스캐터↔히트맵 토글 |
| 10 | POST /alert-rules | S08b 규칙 | 규칙 생성 모달(서비스·이름·metric_kind 7종·operator·threshold·window_sec·severity·enabled·채널 다중선택) | "규칙 만들기" 저장 |
| 11 | PUT /alert-rules/{uuid}/channels | S08b 규칙 | 규칙 상세 드로어 "채널" 절 다중선택 → 저장(전체 교체) | 채널 절 저장 |
| 12 | GET /stats/urls | S02 트랜잭션 | "URL 통계" 탭 표(span_name·cnt·error_rate·p50/p95/p99) | 탭 진입·페이징 |
| 13 | POST /applications | S09 설정 | 서비스 등록 모달(name·display_name·description), 409 APPLICATION_NAME_TAKEN 인라인 오류 | "서비스 등록" 저장 |
| 14 | GET /traces/{traceId} | S03 트레이스 상세 | 스팬 트리 타임라인 + 서비스 경로 칩 + 스팬 상세 패널(events) | S01/S02/S06/S07에서 trace_id 클릭 |
| 15 | GET /metrics/series | S04 인스펙터 | CPU·힙·GC·스레드 4장 차트 + 사용자 지정 지표 차트(step 자동, source_table 배지) | 파드 선택·시간범위·지표 변경 |
| 16 | GET /alert-events | S08 경보 이벤트 / S01 상단 띠 | 이벤트 표(state·severity·rule_name·service·agent_key·observed_value·fired_at), 상단 "발화 중 N건" 띠 | 페이지 진입·필터·자동 새로고침 |
| 17 | PUT /alert-channels/{uuid} | S08c 채널 | 채널 수정 모달(name·type·config) 저장 | 저장 버튼 |
| 18 | GET /applications | 전역 상단바 / S09 | 서비스 선택 드롭다운, 설정의 서비스 목록 표 | 앱 로드·드롭다운 열기 |
| 19 | POST /alert-channels/{uuid}/test | S08c 채널 | 카드 "테스트 발송" 버튼 → 결과 토스트(SUCCESS/FAILED + response) | 버튼 클릭 |
| 20 | GET /auth/me | 전역 상단바 / S09 계정 | 사용자 메뉴(name·role 배지), 계정 절 | 로그인 직후·앱 로드 |
| 21 | GET /agents/{uuid}/active-threads | S04 인스펙터 | 파드 상세 헤더 "현재 스레드 N" 카운터(ts_min 표기) | 파드 선택·자동 새로고침 |
| 22 | GET /applications/{uuid}/agents | S04 인스펙터 | 좌측 파드 목록(status 필터: UP/DOWN/UNKNOWN) | 서비스 선택 |
| 23 | GET /alert-rules/{uuid} | S08b 규칙 | 규칙 상세 드로어 | 규칙 행 클릭 |
| 24 | GET /alert-events/{uuid}/notifications | S08 경보 이벤트 | 이벤트 상세 드로어 "알림 발송 이력" 표(채널·type·result·retry_count·sent_at) | 드로어 열기 |
| 25 | GET /metrics/names | S04 인스펙터 | "지표 추가" 드롭다운(metric_name + attribute_keys) | 드롭다운 열기 |
| 26 | GET /traces/transactions | S02 트랜잭션 | 드래그 선택 사각형 → 하단 요청 목록 표(min/max_duration·is_error 자동 채움) | 스캐터 드래그 완료 |
| 27 | GET /agents/{uuid} | S04 인스펙터 | 파드 상세 헤더(hostname·ip·jvm_version·agent_version·status·first_seen_at) | 파드 선택 |
| 28 | POST /auth/refresh | 전역(무화면) | 401 시 자동 재발급, 실패하면 S00으로 이동 + "세션 만료" 배너 | 토큰 만료 |
| 29 | GET /applications/{uuid}/config | S09 설정 | "샘플링률" 카드(현재값·version·updated_by·updated_at) | 서비스 선택 |
| 30 | PUT /alert-rules/{uuid} | S08b 규칙 | 규칙 수정 모달(threshold·operator·window_sec·severity) | 저장 버튼 |
| 31 | POST /auth/login | S00 로그인 | 이메일·비밀번호 폼, 401 오류 배너 | 로그인 버튼 |
| 32 | POST /auth/logout | 전역 사용자 메뉴 / S09 계정 | "로그아웃" 항목 | 클릭 |
| 33 | GET /alert-channels/{uuid} | S08c 채널 | 채널 수정 모달 초기값(config 마스킹 표시 `••••`) | 카드 "수정" 클릭 |
| 34 | GET /thread-dumps | S05 스레드 덤프 | 좌측 덤프 목록(agent_key·requested_by·requested_at·thread_count) | 페이지 진입·필터 |
| 35 | GET /logs | S07 로그 검색 / S03 연결 로그 탭 | 필터 바(level·logger·trace_id·q) + 결과 표 | 검색·페이징·S03에서 trace_id 고정 |
| 36 | GET /errors/timeline | S06 에러 분석 | 상단 스택 막대 차트(http_status_class×exception_type) | 페이지 진입·시간범위 |
| 37 | POST /alert-channels | S08c 채널 | 채널 등록 모달(type 선택 → config 필드 전환) | "채널 등록" 저장 |
| 38 | DELETE /applications/{uuid} | S09 설정 | 서비스 상세 "감시 대상에서 제외" 위험 버튼 → 확인 다이얼로그, 409 안내(딸린 규칙/설정) | 확인 클릭 |
| 39 | PUT /applications/{uuid}/config | S09 설정 | 샘플링률 슬라이더+입력 → "적용" 버튼(expected_version 동봉), 409 CONFIG_VERSION_CONFLICT 배너 + 새로고침 | 적용 클릭 |
| 40 | GET /internal/agents/active [간접] | S08 경보 이벤트 / S04 | AGENT_DOWN 경보 행, 파드 목록 DOWN 상태 표시 | 탐지 주기 조회 |
| 41 | GET /server-map | S01 서버맵 | 노드(서비스·cnt·err_cnt)·간선(cnt·err_cnt·avg_duration_ms), callee_kind별 노드 모양 | 페이지 진입·시간범위 |
| 42 | GET /alert-channels | S08c 채널 / S08b 규칙 모달 | 채널 카드 그리드, 규칙 모달 채널 다중선택 목록 | 페이지 진입 |
| 43 | GET /alert-rules/{uuid}/channels | S08b 규칙 | 규칙 상세 드로어 "채널" 절 현재 연결 | 드로어 열기 |
| 44 | PATCH /alert-rules/{uuid}/enabled | S08b 규칙 | 규칙 표 켜기/끄기 스위치 | 스위치 토글 |
| 45 | GET /alert-events/{uuid} | S08 경보 이벤트 | 이벤트 상세 드로어(규칙 조건 threshold/operator/window·observed_value·파드) | 행 클릭 |
| 46 | GET /thread-dumps/{dumpUuid} | S05 스레드 덤프 | 우측 덤프 본문 뷰어(스레드 상태 필터·검색·복사) | 목록 행 클릭 |
| 47 | GET /agents | S04 인스펙터(전체 보기) / S09 | 서비스 미선택 시 전체 파드 표(service_name·status 필터) | 서비스 "전체" 선택 |
| 48 | GET /alert-rules | S08b 규칙 | 규칙 표(service·metric_kind·조건·severity·enabled·채널 수) | 페이지 진입·필터 |
| 49 | POST /internal/thread-dump [간접] | S05/S04 | 덤프 진행 모달 단계 표시 "수집기 팬아웃 중…" | #1 처리 중 |
| 50 | POST /internal/channels/test [간접] | S08c 채널 | 테스트 발송 결과 토스트(result·response) | #19 처리 중 |

미매핑: 0건 (50/50)

## 리뷰(2026-09-21)에서 드러난 명세 보완 제안 — 화면이 필요로 하는데 API 명세에 없는 문
| 제안# | Method Path (권한) | 화면 | 이유 |
|---|---|---|---|
| 51 | GET /api/v1/platform/canary (VIEWER+) — 응답 service_name, last_signal_at, age_sec, threshold_sec, fresh (+ dms_last_ping_at) | 전 화면 사이드바 미니 카드 · S10 상단 배너 | 내부 문 #4는 X-Internal-Token 전용이라 화면이 못 부름. "화면은 API 서버 하나만 호출" 규칙 준수 |
| 52 | GET /api/v1/platform/services (VIEWER+) — 응답 service_name, ready, deps[], pod_count, checked_at | S10 우리 서비스 6개 카드 | 화면이 6개 서비스 /readyz에 직접 붙으면 규칙 위반. API 서버가 대신 훑어 한 번에 반환 |
| 53 | GET /api/v1/platform/canary/events (VIEWER+) — kind(FRESH/STALE/DMS_MISSED), ts, age_sec, threshold_sec, action | S10 최근 카나리 이벤트 표 | 근거 API·ERD 표(canary_events) 없음. 파수꾼 이력을 어디에 적을지 결정 필요 |

기타 명세 피드백: 스레드 덤프 hot/cold 보존 기간(3일+90일=93일)을 §0에 표로 명시 · 에이전트 JVM 옵션 전체(mTLS client key/cert)를 FN-12에 기재 · step→source_table 대응표(<60 raw / ≥60 1m / ≥3600 1h)를 §0에 표로 추가.
