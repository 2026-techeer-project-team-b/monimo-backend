# 미해결 질문

`Q | 질문 | 막는 것 | 잠정안` · 근거는 ADR · 닫힘 `Q1~Q5 Q10 Q11 Q13~Q26` (Q6 Q7 Q8 Q12 Q28 Q29만 OPEN) → 최근 종결 ADR `#34`~`#38`

## 최우선

- **Q14 [CLOSED 2026-09-14 → `#38`]** | 잠정 ADR `#13` `#16`~`#22` 를 팀이 확정하는가 | 3단계 착수 | 종결: 전부 확정(`#18` 세부는 구현 시 재검토). TENTATIVE 표기 해제

## 그 외

- **Q6 [OPEN]** | AWS 월 예산 상한 | MSK vs Strimzi, EKS vs 경량 K8s, 관리형 저장소 | 4단계 독립 판단 축인데 값 없음
- **Q7 [OPEN]** | 이력서 타깃 직무(백엔드/SRE/데이터) | 고도화 기술 3개 선정 기준 | T0+2M 전까지
- **Q8 [OPEN]** | `T0` 확정 | 페이즈 경계 전체 | 사용자 보류. 2단계 완료조건이라 지금 필요
- **Q9 [CLOSED 2026-09-21 → `#46`]** | GitHub 레포 생성 시점 | `design/` 이관 | 종결: 레포 5개 생성·구성 확정. 이관은 개발환경 3단계
- **Q12 [OPEN]** | 팀 노션 Phase 1a~4 정의 | FN 60건 배정 정확도 | 가정으로 채움, 노션 기능명세 콜아웃 참조
- **Q16 [CLOSED 2026-09-14 → `#37`]** | 스레드 덤프 본문 저장 위치 (CH `thread_dumps` / 저장 안 함 — PG text는 `#36`으로 제외, 요청 이력 표는 삭제됨) | ERD의 `thread_dumps` 존재 여부 | 잠정: CH, TTL 3일. 근거 `references/erd-clickhouse-guide.md` §3-1
- **Q17 [CLOSED 2026-09-14 → `#38`]** | `metrics_raw` long(1지표 1행) vs wide(에이전트 1행 컬럼 N개) | 메트릭 ORDER BY·롤업 MV 설계 | 잠정: long(OTLP 모델 일치·ALTER 불필요). 2단계 카디널리티로 검증
- **Q18 [CLOSED 2026-09-14 → `#38`]** | 에이전트 개별 설정 오버라이드(샘플링률 등)가 MVP 범위인가 | `application_configs`(앱 단위) vs 개별 `agent_config_overrides` 추가 여부 | 잠정: 앱 단위만. Pinpoint도 앱 단위. 근거 `references/erd-pg-input-sheet.md` §13 ①
- **Q19 [CLOSED 2026-09-14 → `#37`]** | 트레이스 파생 집계(heatmap_1m·url_stats_1m·server_map_1m)의 보관기간 | CH 집계 테이블 TTL·파티션 확정 | 잠정 90일. ADR `#10`은 메트릭 계층(15일→90일→1년)만 정했고 트레이스 파생 집계는 미정. 근거 Notion ERD 페이지·`references/erd-clickhouse-guide.md`
- **Q20 [CLOSED 2026-09-14 → `#37`]** | 서버맵 간선(호출 주체→대상) 계산 방식 — OTel W3C 전파에는 부모 서비스명이 없어 `spans.parent_service_name` 전제가 깨짐 | CH `server_map_1m` 파생·ERD `spans` 컬럼 확정 | 잠정: 적재 처리기가 CLIENT span의 `server.address`/`peer.service`를 `applications`에 매핑해 간선 생성. 근거 ADR `#33`
- **Q21 [CLOSED 2026-09-14 → `#37`]** | `application_configs.enabled_modules` 의 의미 — OTel 계측 on/off는 기동 옵션이라 런타임 변경 불가 | ERD PG `application_configs` 컬럼 유지/폐기 | 잠정: 수집기 span 필터(계측 종류별 드롭)로 재해석, 아니면 폐기. 근거 ADR `#33`
- **Q22 [CLOSED 2026-09-14 → `#38`]** | `application_configs.log_level` 의 의미 — OTel Agent는 앱 로그 레벨을 바꾸지 못함 | ERD PG `application_configs` 컬럼 | 잠정: 수집기 수신 필터(레벨 미만 드롭)로 재해석. 근거 ADR `#33`
- **Q23 [CLOSED 2026-09-14 → `#37`]** | 수집기의 샘플링 비율 조회 경로 — PG 직접 조회 vs 탐지 설정 API 경유 | v4.2 보조 흐름 확정·수집기↔PG 결합 여부 | 잠정: 직접 조회 + 30초 캐시 + PG 장애 시 마지막 값 유지. 근거 ADR `#33` `#31`
- **Q24 [CLOSED 2026-09-14 → `#36`]** | 명령 API(스레드 덤프 요청 접수)의 소유 서비스와 `thread_dump_requests`·`users` 쓰기 주체 — 화면이 PG를 직접 쓰는가, 탐지/별도 API를 거치는가 | ERD PG 소유자 확정·v4.3 화살표 | 종결: API 서버가 명령 팬아웃·설정·로그인 담당, `thread_dump_requests` 삭제
- **Q25 [CLOSED 2026-09-13]** | 알림 채널 목록 — FR-6(이메일·단문메시지·PagerDuty·웹훅) vs Figma v4.2(Slack·이메일·웹훅·PagerDuty) 불일치 | `alert_channels.type` enum·FR-6 문구 | 종결: 사용자 확정 = Slack · 이메일 · HTTPS 웹훅 · PagerDuty (SMS 없음). FR-6 갱신 완료
- **Q26 [CLOSED 2026-09-14 → `#37`]** | S3 cold 계층의 신호별 보관 기간과 CH storage_policy 세부(볼륨 이동 기준·cold 조회 허용 범위) | ERD CH 테이블 TTL 절 · `#10` 보관 수치 개정 여부 | 잠정: 로컬은 `#10` 그대로(트레이스 3일·로그 7일·메트릭 15일), S3는 그 뒤 90일 후 삭제. 근거 ADR `#34`
- **Q27 [CLOSED 2026-09-14 → `#40`]** | FN-25(4xx 비율·특정 상태코드 횟수 규칙 유형, Phase 1a)를 `alert_rules.metric_kind` 6종과 CH `service_health_1m`(cnt·err_cnt·dur_q)으로는 표현 못 함 | 경보 규칙 POST 명세·ERD service_health_1m 컬럼 | 잠정: `metric_kind`에 `4XX_RATE` 추가 + `service_health_1m`에 `cnt_4xx`·`cnt_5xx` 컬럼 추가(MV에서 http_status 대역으로 sumState). "특정 코드 횟수"는 Phase 2로 미룸. 근거 API 명세 검증 2026-09-14
- **Q28 [OPEN]** | 팀 투입 비율 6:2:1:1(2026-09-17 사용자 전달, 프론트는 4명 공동)에 맞춘 MVP 범위 재조정 — 기존 ADR 다수가 '4명·2개월 풀타임' 전제(`#05` `#33` 기각 사유) | 스레드 덤프 Extension 존치(`#33` 되돌림 a 조기 적용 여부) · 알림 채널 4종 → Slack 우선 · 파수꾼 Lambda 조합 단순화 · 서버맵·로그 검색 우선순위 | 잠정 분담: 6=관통 경로(수집기·적재·CH 스키마·API 서버 조회·공통 틀·클러스터 기반)+트레이스/스캐터 화면 / 2=탐지·알림·규칙/채널/경보 API+화면 / 1①=쇼핑몰 골격·에이전트 설정·k6+로그 화면 / 1②=서비스·설정 API+화면·파수꾼. 원칙: 크리티컬 패스는 6, 10% 담당은 비차단 과제만
  - **2026-09-23 배치 확정(사용자)**: 승조 = 수집(수집기·적재 처리기) + 쇼핑몰 + 배포 / ukong = 알림(탐지·알림·경보 API) / Nova = 조회(API 서버 조회) / 재범 = 보안(인증 설정) + 파수꾼 / 화면 = 4명 공동. 잠정 분담의 "6·2·1①·1②" 는 이 이름으로 읽는다. CODEOWNERS 반영. **범위 재조정(스레드 덤프 Extension 존치 · 채널 4종 → Slack 우선 · 파수꾼 조합 단순화)은 여전히 OPEN**
- **Q29 [OPEN]** | 정적 분석 도구 — 사용자가 **ktlint 는 쓰지 않는다** 고 확정(2026-09-23). detekt 여부와 기각 사유가 없어 ADR 4요소를 못 채움 | 노션 「사용할 라이브러리 정리」 정적 분석 행(ktlint + detekt 🟡 추천) 갱신 · CI 린트 단계 | 잠정: 정적 분석 없이 시작. 사유 한 줄(예: 포맷 논쟁 방지 vs 학습 부담)과 detekt 결정이 오면 ADR 로 종결
