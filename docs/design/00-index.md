stage: 2 완료 · 3 미착수
next: 수집 파트(승조)는 agents.ip 채우기 → 배포 7~9단계(monimo-deploy, PVC 근거 20-scope §4) → 에이전트 mTLS 순으로 가고, 설계는 3단계 30-architecture.md 를 쓸지 Q28 과 함께 정한다 (T0+1M 점검 2026-10-22 · Phase 진행 기준 = Notion 「API 명세」 · 설계 잔여 Figma v4.3)
open: 7
updated: 2026-10-11
team(2026-09-23 확정): 승조 @SeungJo-02 = 수집 + 쇼핑몰 + 배포 · ukong @ukongee = 알림 · Nova @hyl1115 = 조회 · 재범 @jaebeom79 = 보안(인증 설정) + 파수꾼 · 화면 = 4명 공동. CODEOWNERS 5개 레포 파트별 반영. ⚠️ 세 멤버 레포 권한이 read 라 write 부여 필요
devenv(2026-09-23): 계획 1~6단계 완료(`#13` 연결 약속: monimo-dev 네트워크 · collector:4317 · telemetrygen 점검 · shop otel/agent.properties) · 4단계 springdoc 추가(#12) · 7~9단계 남음. ktlint 미사용(사용자 확정, Q29 — ADR 사유 대기)
notion(개인 스크럼 김승조): CI/CD 흐름 3dcd..8f13 · 깃허브 레포지토리 3dcd..24e6(레포 6개 중간안 → `#46`으로 5개 확정) · 레포별 파일 구성 3e1d..783b(`#46` 채택안) — 2026-09-15 · **사용할 라이브러리 정리 3ded..7453(2026-09-18 작성: 레포 5개별 라이브러리·대안·선택이유, 미확정은 후보+추천안+판단기준. docs 레포 제외한 5개 전제 / 2026-09-19 표 형식 전면 재포맷: 후보 칸에 한 줄 설명, 설명 칸은 장:·단: 각 2줄 이내 — 후보 행 184개 전부. §6은 5열 요약표라 대상 아님)**
notion: 기능명세 3c7d..2d86(핵심기능5축, FN-1~60, #34~#38 반영 2026-09-14) / 설계범위 3d1d..1f90(#34~#38 반영, Q14 종결 표기) / 기술스택 선정서 3c7d..2d9a(**2026-09-15 전면 재작성**: 컴포넌트 21개 토글 — 하는 일·후보 비교표·되돌림, 상태 ✅/🟡/⬜, #40 기준. 옛 선정 DB·A~D 심화절은 사용자가 삭제. 2026-09-15 §6 CNCF 도구 추가 — MVP: OTel✅·Argo CD·Helm·cert-manager🟡, 인그레스⬜(Traefik vs ALB), 고도화 후보 KEDA>Kyverno>Linkerd — ADR 미기록. 로컬 사본 scratchpad/stack-page.md) / API명세 3c7d..5f46(**v3 REST 상세** 2026-09-14: §0 규약(헤더 포함) + 인라인 DB 'REST API' collection 801c..8fe5 **50행**(헬스체크 12행은 사용자가 삭제 → §0-1-1 공통 규약으로) + gRPC 3 절 + 호출 흐름. 최종 검증 APPROVE 2026-09-14. 정본 scratchpad/api-v3/{rows.json,body.md}) / ERD 3c7d..5588(최종본)
web-v2(2026-09-21): Claude Design 아티팩트 https://claude.ai/artifact/AcoxmqwqUETAUytX2FhY2t (15 아트보드, 리뷰 반영 v3) · 사본 design/web-v2/ · API 50행→화면 매핑 api-map.md · 명세 보완 제안 #51~#53(플랫폼 카나리/서비스/이벤트 문) Notion 미반영 · Figma 페이지 「화면 디자인 2」536:30에 15화면 네이티브 재구축(컴포넌트 섹션 538:2, 프레임 540:*) · 「디자인 시스템」 페이지 0:1 완성(변수 79·텍스트 스타일 13·컴포넌트 64·아이콘 30, 원장 web-v2/figma-ds-state.json) · 기존 Figma 1:3 WF는 미수정
figma: 1dH26CE91tZIpMiW1mbC8t · 상세 v4.2 235:2 / EKS v5.1 345:2 / **EKS v5.2 475:2(2026-09-15, #34~#40 반영: API 서버·raw.dlq·S3 cold·PG 발송 대기 큐·규칙 API 화살표 제거·manifest 태그 갱신 화살표·kubelet pull)** · 개략설계안 48:2 / MSA 31:2 / 흐름도 38:2 / 와이어프레임 페이지 1:3(WF-00~07 + WF-03b + 범례)
refs: references/erd-clickhouse-guide.md · references/erd-pg-input-sheet.md (컬럼 정본, ADR #33~#38 이전 초안) → **Notion ERD 페이지 3c7d..5588 = 최종본(2026-09-14)**: PG 9표 + CH 11표, 토글 20개, mermaid 2, TTL 2단, 소유자 표기, 학생 눈높이. 로컬 사본 scratchpad/erd-v2.md
refs: API 명세 v2 초안 scratchpad/api-spec-v2.md (2026-09-14, #39 기준 API 서버 47+·수집기 6·헬스 8) — 명세 내 결정 3건: 덤프 식별 `thread_dumps.dump_uuid` 추가(ERD 반영) · FN-51 대시보드 저장은 MVP localStorage(Phase 3 user_preferences) · 채널 테스트 전송은 알림 내부 API를 API 서버가 대리 호출
refs: ERD 재설계 착수 전 정리(FR 8·ADR 33·Figma v4.2 대조 A~G) 2026-09-13 — 사용자 결정: Notion ERD·API 명세 페이지는 삭제 보류, ERD는 새로 그림. v4.2 즉시 수정 가능 항목 A(TTL 3계층)·B(1분 집계→MV)·F(채널)·G(파수꾼 박스)

figma-todo(v4.3): 화면→탐지 '규칙 관리 API' 화살표 제거·탐지→PG '규칙 읽기+이벤트 쓰기'(#39) · D API 서버→PG 실선(설정·로그인·규칙·채널)·적재 처리기→PG(에이전트 등록)·명령 출발점 화면→API 서버·PG 소유 4분할(#35 #36) · S3 박스 'DLQ'→'cold 계층(CH 계층 저장)'·적재 처리기→Kafka raw.dlq·CH→S3 화살표(#34) · A CH TTL 3계층 표기 · B 적재 처리기 '1분 집계'→MV 자동 · C 아웃박스→PG 발송 대기 큐 · F 채널 4종(SMS 없음) · 질의 파사드→API 서버 개명
glossary: API 서버 = 구 질의 파사드/Query API (2026-09-13 개명, Notion 3페이지 반영 완료, Figma는 v4.3에서)

erd-note(2026-09-16): 경보 규칙 팀 공용 확정 · alert_rules/alert_channels에 updated_by 추가 안 함 · ERDCloud 잔여 = enabled 라벨 "설명"→"켜짐/꺼짐" · UNIQUE/FK 인덱스는 DDL 단계

## recent decisions (max 5)

- `#61` **관리 포트 = 업무 포트 + 10000**(수집기 18081 · 적재 처리기 18082, #148). #58 의 +10 이 쇼핑몰 order · payment 호스트 포트와 겹쳤다. 쇼핑몰 포트 옮기기(비침습 위반) · 안 고치기 · +100 기각. 되돌림 = 팀 전체 포트 규칙이 생기면
- `#60` **2단계 범위 · 규모를 로컬 실측으로 확정, `T0` = 2026-09-22(KST, 조직 첫 머지)**(감시 대상 4 · 시계열 인스턴스당 73.5 · 메트릭 4.9 포인트/초 · 트레이스 1% 3.4 스팬/초 · 유휴 0.6 MB/일). 처리량 NFR 은 부하 시험 상한으로 유지. 첫 커밋 · 설계 착수일 · 팀 배치일 기각. 되돌림 = 운영 실측 10배 어긋남. Q8 종결. 같이 드러난 것 : #135 관리 포트 8091 · 8092 가 쇼핑몰 order · payment 호스트 포트와 충돌
- `#59` **로그 등급은 OTel `severity_number` 가 정본, 글자는 숫자가 없을 때만 별칭 표로, 모르는 글자는 빈 글자**(#144). 글자 정본 · 그대로 넣기 기각. 되돌림 = 원문 글자 요구가 생기면 컬럼 추가
- `#58` **수집기 · 적재 처리기 probe : readiness 에 저장소 안 넣고 업무 포트에도 `/livez` · `/readyz`, 관리 포트 = 업무 포트 + 10**(#135). 규약대로 Kafka · PG · CH 넣기 · PG 만 넣기 기각. 되돌림 = 저장소가 파드마다 다른 구성이 오면
- `#57` **ClickHouse 마이그레이션도 Flyway(커뮤니티 플러그인) + 전용 컨테이너, 장부는 `monimo` 안에**(#119). 쉘 스크립트 · 다른 도구 · `down -v` 유지 기각. 되돌림 = 플러그인이 CH 버전을 못 따라오면
docs: 01-decisions(결정 61) · 02-open-questions · 10-requirements · **20-scope(2단계 범위 · 규모 · T0, 2026-10-11)** · 30-failure-modes(고장 나면 어떻게 되나, 2026-10-04) · ../seungjo(승조: 하네스 · 이슈별 리서치 · 프롬프트 · 결정 · ERD 영향. 2026-10-04 한 폴더로 합침, 첫 이슈 폴더 92-health-check-filter)
