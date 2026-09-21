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

### 로컬 인프라 (Kafka · ClickHouse · PostgreSQL)

```bash
docker compose -f docker-compose.dev.yml up -d --wait   # 켜기 (토픽까지 준비되면 끝남)
./scripts/check-dev-infra.sh                            # 제대로 떴는지 확인
docker compose -f docker-compose.dev.yml down           # 끄기 (ClickHouse · PostgreSQL 데이터는 남음)
docker compose -f docker-compose.dev.yml down -v        # 데이터까지 전부 지우기
```

- Kafka 토픽 `raw`(7일 보관, 파티션 3) · `raw.dlq`(30일 보관)는 켤 때 자동으로 만든다. 그 외 토픽은 자동으로 생기지 않는다.
- Kafka 메시지는 컨테이너 안에만 있어서 `down` 하면 지워진다.
- ClickHouse 초기 DDL은 `db/clickhouse/*.sql` 에 둔다. **데이터가 비어 있을 때(처음 켤 때)만** 실행되므로, 바꾼 DDL을 다시 적용하려면 `down -v` 후 켠다.
- 포트가 다른 프로젝트와 겹치면 `.env.example` 을 `.env` 로 복사해서 바꾼다.

## 모듈 규칙

- 모듈끼리는 `:common` 만 의존할 수 있다. 다른 모듈을 의존에 넣으면 빌드가 바로 실패한다.
- 라이브러리 버전은 `gradle/libs.versions.toml` 한 곳에서만 정한다.
- `common` 에는 공유 모델만 둔다. Entity · Repository · Service 금지.

## 테스트 규칙

- 테스트는 **Kotest**, 기본 스타일은 **BehaviorSpec** (Given / When / Then). JUnit `@Test` 는 쓰지 않는다. (ADR #48)
- 스프링 컨텍스트가 필요하면 `@SpringBootTest` 를 붙이고 필요한 빈은 테스트 클래스 생성자로 받는다. 연결은 각 모듈의 `src/test/kotlin/io/kotest/provided/ProjectConfig.kt` 가 한다.
- IntelliJ에 Kotest 플러그인을 설치하면 Given · When · Then 옆에 실행 버튼이 생긴다.

```kotlin
@SpringBootTest
class CollectorApplicationTest(environment: Environment) : BehaviorSpec({
    Given("수집기 애플리케이션") {
        When("스프링 컨텍스트를 띄우면") {
            val name = environment.getProperty("spring.application.name")
            Then("애플리케이션 이름이 collector 로 잡힌다") {
                name shouldBe "collector"
            }
        }
    }
})
```

## 환경변수

실제 값은 레포에 올리지 않는다. `.env.example` 에 이름만 적는다.

로컬 인프라용 (`.env.example` 참고, 비워 두면 기본값):

| 이름 | 기본값 | 설명 |
|---|---|---|
| `KAFKA_PORT` | 19092 | Kafka 호스트 포트 |
| `CLICKHOUSE_HTTP_PORT` | 18123 | ClickHouse HTTP 호스트 포트 |
| `CLICKHOUSE_NATIVE_PORT` | 19000 | ClickHouse 네이티브 호스트 포트 |
| `POSTGRES_PORT` | 15432 | PostgreSQL 호스트 포트 |
| `CLICKHOUSE_USER` · `CLICKHOUSE_PASSWORD` | monimo · monimo | 로컬 전용 계정 |
| `POSTGRES_USER` · `POSTGRES_PASSWORD` | monimo · monimo | 로컬 전용 계정 |

## 포트

HTTP 포트(상태 확인 `/actuator/health`). 임시값이며 개발환경 6단계(로컬 연결 약속)에서 확정한다.

| 서비스 | 포트 |
|---|---|
| api-server | 8080 |
| collector | 8081 (OTLP gRPC 4317은 구현 때 추가) |
| ingester | 8082 |
| detector | 8083 |
| notifier | 8084 |

로컬 인프라 (호스트 포트는 다른 프로젝트와 겹치지 않게 1로 시작):

| 인프라 | 내 컴퓨터에서 (bootRun 한 앱) | 컨테이너끼리 |
|---|---|---|
| Kafka | `localhost:19092` | `kafka:29092` |
| ClickHouse HTTP | `localhost:18123` | `clickhouse:8123` |
| ClickHouse 네이티브 | `localhost:19000` | `clickhouse:9000` |
| PostgreSQL | `localhost:15432` | `postgres:5432` |

## 관련 문서

- [설계 문서 (결정 기록 원본)](docs/design/00-index.md): 결정 기록 `docs/design/01-decisions.md`, 미해결 질문 `02-open-questions.md`, 요구사항 `10-requirements.md`
- [레포별 파일 구성](https://app.notion.com/p/3e1d7d6851ff80a8a110e8aea0b5783b)
- [깃허브 레포지토리 규칙](https://app.notion.com/p/3dcd7d6851ff8000b795f1cc609124e6)

## 기여 규칙

- `main` 직접 push 금지, PR로만 머지
- 브랜치: `feat/<이슈번호>-<설명>` · `fix/<이슈번호>-<설명>` · `chore/<설명>`
- 커밋: `<타입>(<범위>): <요약>` (타입: feat · fix · docs · chore · refactor · test)
