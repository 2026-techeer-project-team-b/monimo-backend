stage: 2
next: Figma v4.3(figma-todo) → 20-scope 규모 숫자·T0(Q8). ERD·API 명세·팀 Notion 3페이지는 #40까지 일치
open: 5
updated: 2026-09-21
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

- `#47` **설계 문서 이관 = `monimo-backend/docs/design/`**(deploy 레포·로컬 유지·Notion 단독 원본 기각). 원본=레포, Notion=사본. `.omc/`·`.bak` 제외, 원 자리 `~/monimonitoring/design/00-index.md` 는 포인터
- `#46` **레포 구성 = 폴리레포 5개**(backend 멀티모듈 6, web·shop·watchdog·deploy) · 퍼블릭+MIT · main 보호는 PR 필수만(리뷰 승인 필수 없음). 모노레포·서비스별 9개·docs 레포 기각. Q9 종결. 첫 커밋 파일 로컬 준비 `~/monimonitoring/repos/`
- `#45` **파수꾼 언어 = Python**(Kotlin 기각 — 본체 30~40줄에 fat jar·콜드스타트 1~3초·Gradle 단계가 과함). Terraform이 zip 배포. 되돌림=담당자가 첫 주 내 관통 확인 실패 시 Kotlin
- - `#44` **IaC = Terraform 단일**(SAM 기각) — VPC·EKS·RDS·ECR·Secrets·S3 + 파수꾼 Lambda까지 한 벌, state는 S3+잠금. GH Actions는 AWS OIDC. 파수꾼 언어는 미정. 되돌림=세팅이 착수 첫 주 초과 시 eksctl+SAM
- `#43` **서버맵 = React Flow**(Cytoscape 기각) + dagre 자동배치. 노드 수십 개 상한이라 Cytoscape 강점 무의미, 노드 내 지표 표시가 실제 요구. 차트는 ECharts 추천안 유지(확정 미표기). 되돌림=노드 100개 초과 시
