# monimo-backend

수집기 · 적재 처리기 · API 서버 · 탐지 · 알림 5개 서비스와 공통 모듈을 담은 Kotlin Gradle 멀티모듈 레포

- 기술: Kotlin · Java 17 · Spring Boot 3.x · Gradle
- 상태: Gradle 뼈대 완료, 기능 구현 전 (개발환경 세팅 중)

## 폴더 구성

| 폴더 | 하는 일 |
|---|---|
| `common/` | 공유 모델 (Kafka 메시지 형식 · 에러 코드). Entity · Service 금지 |
| `collector/` | 수집기: OTLP 수신 → 샘플링 → Kafka 발행 |
| `ingester/` | 적재 처리기: Kafka 소비 → ClickHouse 적재 |
| `api-server/` | API 서버: 화면용 조회 · 설정 · 규칙 · 채널 |
| `detector/` | 탐지: 주기 조회 후 경보 규칙 평가 |
| `notifier/` | 알림: Slack · 이메일 · 웹훅 · PagerDuty 전송 |

## 로컬 실행

필요한 것: JDK 17 (없으면 Gradle이 자동으로 내려받는다), Docker

```bash
./gradlew build                      # 전체 빌드 + 테스트
./gradlew :collector:bootRun         # 서비스 하나만 실행 (collector 자리에 모듈 이름)
./gradlew :collector:test            # 모듈 하나만 테스트

# Docker 이미지 (레포 루트에서)
docker build -f collector/Dockerfile -t monimo/collector .
```

Kafka · ClickHouse · PostgreSQL 로컬 실행(`docker-compose.dev.yml`)은 개발환경 5단계에서 추가한다.

## 모듈 규칙

- 모듈끼리는 `:common` 만 의존할 수 있다. 다른 모듈을 의존에 넣으면 빌드가 바로 실패한다.
- 라이브러리 버전은 `gradle/libs.versions.toml` 한 곳에서만 정한다.
- `common` 에는 공유 모델만 둔다. Entity · Repository · Service 금지.

## 환경변수

실제 값은 레포에 올리지 않는다. `.env.example` 에 이름만 적는다.

| 이름 | 설명 |
|---|---|
| (준비 중) | |

## 포트

HTTP 포트(상태 확인 `/actuator/health`). 임시값이며 개발환경 6단계(로컬 연결 약속)에서 확정한다.

| 서비스 | 포트 |
|---|---|
| api-server | 8080 |
| collector | 8081 (OTLP gRPC 4317은 구현 때 추가) |
| ingester | 8082 |
| detector | 8083 |
| notifier | 8084 |

## 관련 문서

- [설계 문서 (결정 기록 원본)](docs/design/00-index.md): 결정 기록 `docs/design/01-decisions.md`, 미해결 질문 `02-open-questions.md`, 요구사항 `10-requirements.md`
- [레포별 파일 구성](https://app.notion.com/p/3e1d7d6851ff80a8a110e8aea0b5783b)
- [깃허브 레포지토리 규칙](https://app.notion.com/p/3dcd7d6851ff8000b795f1cc609124e6)

## 기여 규칙

- `main` 직접 push 금지, PR로만 머지
- 브랜치: `feat/<이슈번호>-<설명>` · `fix/<이슈번호>-<설명>` · `chore/<설명>`
- 커밋: `<타입>(<범위>): <요약>` (타입: feat · fix · docs · chore · refactor · test)
