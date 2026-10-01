# monimo-backend

수집기 · 적재 처리기 · API 서버 · 탐지 · 알림 5개 서비스와 공통 모듈을 담은 Kotlin Gradle 멀티모듈 레포

- 기술: Kotlin · Java 17 · Spring Boot 3.x · Gradle
- 상태: 파이프라인 앞 구간(수집기 → Kafka → 적재 처리기)과 인증 · 서비스 등록 · 조회 일부 · 경보 상태머신 · 발송 워커가 들어와 있다. ClickHouse 적재는 아직
- **작업을 시작하기 전에 [`AGENTS.md`](AGENTS.md) 를 먼저 읽는다.** 규칙 · 파트별 담당 · 지금까지 한 일 · 막혀 있는 것이 거기에 있다

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

필요한 것: JDK 17 (없으면 Gradle이 자동으로 내려받는다), **Docker (테스트에도 필요)**

```bash
docker network create monimo-dev     # 처음 한 번만. backend · shop 의 compose 가 같이 쓰는 공용 네트워크 (없으면 compose 가 실패한다)

./gradlew build                      # 전체 빌드 + 테스트 (Docker가 켜져 있어야 한다)
./gradlew :collector:test            # 모듈 하나만 테스트

# 서비스 하나 실행: 로컬 인프라를 먼저 켜고 local 프로필로
docker compose up -d --wait
./gradlew :collector:bootRun --args='--spring.profiles.active=local'
curl localhost:8081/actuator/health  # DB별 연결 상태까지 보인다

# API 서버를 local 프로필로 켜면 REST 명세 · Swagger UI 가 열린다 (다른 프로필에서는 404)
./gradlew :api-server:bootRun --args='--spring.profiles.active=local'
open http://localhost:8080/swagger-ui.html

# Docker 이미지 (레포 루트에서)
docker build -f collector/Dockerfile -t monimo/collector .
```

### 로컬 인프라 (Kafka · ClickHouse · PostgreSQL)

```bash
docker compose up -d           # 켜기 (레포 루트에서)
docker compose up -d --wait    # 켜기 + 토픽 · PostgreSQL 마이그레이션이 끝날 때까지 기다리기
./scripts/check-dev-infra.sh   # 제대로 떴는지 확인
./scripts/seed-clickhouse.sh   # ClickHouse에 가짜 신호 데이터 넣기 (최근 1시간치, 다시 돌리면 비우고 새로 넣음)
docker compose down            # 끄기 (ClickHouse · PostgreSQL 데이터는 남음)
docker compose down -v         # 데이터까지 전부 지우기

# 수집기까지 컨테이너로 (쇼핑몰 에이전트가 collector:4317 로 보내는 것을 시험할 때)
docker compose --profile collector up -d --wait --build
./scripts/check-wiring.sh      # 가짜 발신기(telemetrygen)로 collector:4317 에 보내고 수집기가 받았는지 확인
docker compose --profile collector down

# 수집기 + 적재 처리기까지 컨테이너로 (한 줄이 통째로 이어지는지 시험할 때)
docker compose --profile collector --profile ingester up -d --wait --build
./scripts/check-pipeline.sh    # 수집기가 받은 건수 · 적재 처리기가 푼 건수 · ClickHouse spans 늘어난 줄 수를 대조한다 (CI 도 이걸 돈다)
docker compose --profile collector --profile ingester down
```

- Kafka 토픽 `raw`(7일 보관, 파티션 3) · `raw.dlq`(30일 보관)는 켤 때 자동으로 만든다. 그 외 토픽은 자동으로 생기지 않는다.
- Kafka 메시지는 컨테이너 안에만 있어서 `down` 하면 지워진다.
- **PostgreSQL 표는 `db/postgres/` 한 곳**에 파트별 폴더(`config/` · `alert/` · `ingest/`)로 추가하고, 켤 때 Flyway가 자동 적용한다. 서비스는 마이그레이션을 돌리지 않는다. 규칙은 [`db/postgres/README.md`](db/postgres/README.md) (ADR #49)
- **ClickHouse 표는 `db/clickhouse/`** 에 있다. 원본 4표(`002`) → 집계 7표(`003`) → MV 7개(`004`) 순서이고, 정본은 노션 ERD「CH 영역」이다. **데이터가 비어 있을 때(처음 켤 때)만** 실행되므로, 바꾼 DDL을 다시 적용하려면 `down -v` 후 켠다.
- 집계 7표는 사람이 넣지 않는다. 원본(`spans` · `metrics_raw`)에 줄이 들어오면 MV가 자동으로 채운다.
- 가짜 데이터(`db/clickhouse/seed/`)는 쇼핑몰 서비스 4개(`shop-gateway` · `shop-order` · `shop-inventory` · `shop-payment`)의 최근 1시간이다. 결제 5xx 급증(5~15분 전) · 느린 결제 · 404 · 힙이 새는 파드 1대가 들어 있어 화면 · 경보를 바로 시험할 수 있다. 모양은 OTel Java Agent 2.x 형식에 맞췄고, 쇼핑몰이 붙으면 진짜 데이터와 비교해 고친다.
- 포트가 다른 프로젝트와 겹치면 `.env.example` 을 `.env` 로 복사해서 바꾼다.
- **연결 약속 (개발환경 6단계)**: 모든 compose 는 공용 네트워크 `monimo-dev` 를 쓴다. 쇼핑몰 에이전트는 컨테이너끼리 `collector:4317`, 내 컴퓨터에서 실행한 앱은 `localhost:4317` 로 보낸다. 수집기를 IDE 로 직접 실행할 때는 `--profile collector` 를 켜지 않는다 (포트가 겹친다).

## 모듈 규칙

- 모듈끼리는 `:common` 만 의존할 수 있다. 다른 모듈을 의존에 넣으면 빌드가 바로 실패한다.
- 라이브러리 버전은 `gradle/libs.versions.toml` 한 곳에서만 정한다.
- `common` 에는 공유 모델만 둔다. Entity · Repository · Service 금지.

## 저장소 연결

| 모듈 | PostgreSQL | Kafka | ClickHouse |
|---|---|---|---|
| collector | JPA (샘플링 설정 읽기) | 보내기 | |
| ingester | JPA (에이전트 명단) | 받기 | client-v2 (적재) |
| api-server | JPA | | JDBC (조회, `clickHouseJdbcTemplate`) |
| detector | JPA | | |
| notifier | JPA | | |

- **REST 명세는 api-server 코드에서 자동 생성**(springdoc). 노션 「API 명세」가 설계 정본이고 Swagger 는 구현이 명세와 맞는지 대조하는 용도다. `local` 프로필에서만 켜지고 운영에는 노출하지 않는다 (`/v3/api-docs` · `/swagger-ui` 접두는 명세 §0-1 주소 규칙 밖).
- 접속 주소 · 계정은 `local` 프로필(`src/main/resources/application-local.yml`)에만 있다. 기본 `application.yml` 에는 환경과 무관한 설정만 둔다.
- PostgreSQL: `open-in-view=false` · `ddl-auto=validate` · 실행 SQL 로깅 (ADR #42 가드레일). 표는 `db/postgres` 가 만들고 코드는 맞는지만 확인한다 (ADR #49).
- Entity는 `data class` 로 만들지 않는다. JPA용 allOpen · noArg는 빌드에 이미 걸려 있다.

## 로그인 · 권한 (api-server)

API 명세 §0-2 의 권한 4종을 `common/security/SecurityConfig` 가 경로로 가른다. 에이전트(mTLS)는 수집기 몫이라 여기 없다.

| 권한 | 경로 | 어떻게 |
|---|---|---|
| 공개 | `/api/v1/auth/login` · `/auth/refresh` · 헬스체크 · actuator · springdoc(local) | 아무것도 안 봄 |
| VIEWER+ | 그 밖의 `/api/v1/**` | `Authorization: Bearer <access JWT>` |
| ADMIN | 값을 바꾸는 문 | 컨트롤러 메서드에 `@PreAuthorize("hasRole('ADMIN')")` |
| 내부 | `/api/v1/internal/**` | `X-Internal-Token` 이 `MONIMO_INTERNAL_TOKEN` 과 같아야 한다. 사람 JWT 는 403 |

- access 토큰은 HS256 JWT, 1시간. `sub` = user_uuid, `role` = ADMIN · VIEWER.
- refresh 토큰은 응답 본문이 아니라 **httpOnly 쿠키**(`monimo_refresh`, `Path=/api/v1/auth`, `SameSite=Strict`, 14일)로 오간다. PG `refresh_tokens` 에 해시만 두고 재발급마다 회전한다. 로그아웃은 그 줄을 지운다.
- 시큐리티 필터에서 막힌 요청도 명세 에러 봉투(`401 UNAUTHENTICATED` · `403 FORBIDDEN`)로 응답한다.
- 첫 계정: `local` 프로필은 users 표가 비어 있으면 `admin@monimo.io` / `monimo2026` 을 ADMIN 으로 만든다 (화면 가짜 응답의 데모 계정과 같다). 운영은 `monimo.auth.bootstrap-admin` 을 환경변수로 넣어 한 번만 만든다.
- `@WebMvcTest` 로 컨트롤러를 시험할 때는 `@Import(SecurityConfig::class)` 를 붙인다. 없으면 스프링 기본 설정이 모든 요청을 401 로 막는다.

## 테스트 규칙

- 테스트는 **Kotest**, 기본 스타일은 **BehaviorSpec** (Given / When / Then). JUnit `@Test` 는 쓰지 않는다. (ADR #48)
- 스프링 컨텍스트가 필요하면 `@SpringBootTest` 를 붙이고 필요한 빈은 테스트 클래스 생성자로 받는다. 연결은 각 모듈의 `src/test/kotlin/io/kotest/provided/ProjectConfig.kt` 가 한다.
- 스프링 테스트는 `@Import(TestInfraConfig::class)` 를 붙이면 **진짜 PostgreSQL · Kafka · ClickHouse 컨테이너**(Testcontainers)에 붙는다. 테스트용 PostgreSQL에는 `db/postgres` 마이그레이션이 그대로 적용되므로, Entity가 표와 다르면 테스트가 실패한다.
- IntelliJ에 Kotest 플러그인을 설치하면 Given · When · Then 옆에 실행 버튼이 생긴다.

```kotlin
@SpringBootTest
@Import(TestInfraConfig::class)
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
| `COLLECTOR_OTLP_PORT` · `COLLECTOR_HTTP_PORT` | 4317 · 8081 | `--profile collector` 로 수집기를 컨테이너로 띄울 때 호스트 포트 |
| `INGESTER_HTTP_PORT` | 8082 | `--profile ingester` 로 적재 처리기를 컨테이너로 띄울 때 호스트 포트 |
| `COLLECTOR_SAMPLING_RATIO` | 1.0 | 수집기 트레이스 샘플링 비율 (로컬은 전부 통과. 운영 정본은 1%, ADR #33). 카나리 표시는 비율과 무관하게 통과 |
| `CLICKHOUSE_USER` · `CLICKHOUSE_PASSWORD` | monimo · monimo | 로컬 전용 계정 |
| `POSTGRES_USER` · `POSTGRES_PASSWORD` | monimo · monimo | 로컬 전용 계정 |

api-server 인증용 (bootRun 환경변수):

| 이름 | 기본값 | 설명 |
|---|---|---|
| `MONIMO_JWT_SECRET` | (없음) | access JWT 서명 키, 32바이트 이상. 비우면 기동 때 임시 키를 만들어 재시작마다 토큰이 전부 무효가 된다 (로컬 · 테스트만) |
| `MONIMO_INTERNAL_TOKEN` | (없음, local 프로필은 `local-internal-token`) | 내부 문 공유 비밀값. 탐지 · 파수꾼이 같은 값을 보낸다. 비우면 내부 문이 전부 닫힌다 |

detector 스케줄 평가용 (bootRun 환경변수):

| 이름 | 기본값 | 설명 |
|---|---|---|
| `MONIMO_INTERNAL_TOKEN` | (없음, local 프로필은 `local-internal-token`) | API 서버와 같은 값. `service-health` 를 부를 때 `X-Internal-Token` 으로 보낸다 |
| `MONIMO_API_SERVER_URL` | `http://localhost:8080` | `service-health` 를 여는 API 서버 주소 |

## 포트

HTTP 포트(상태 확인 `/actuator/health`). 개발환경 6단계(로컬 연결 약속)에서 확정.

| 서비스 | 포트 |
|---|---|
| api-server | 8080 |
| collector | 8081 · **OTLP gRPC 4317** (에이전트 수신) |
| ingester | 8082 |
| detector | 8083 |
| notifier | 8084 |

어느 compose 가 무엇을 켜는지:

| compose | 켜는 것 | 네트워크 |
|---|---|---|
| `monimo-backend/compose.yaml` | Kafka(토픽 2개) · ClickHouse(표 · MV) · PostgreSQL(마이그레이션) | `monimo-dev` |
| `monimo-backend/compose.yaml --profile collector` | 위 + 수집기 컨테이너 (`collector:4317` · `:8081`) | `monimo-dev` |
| `monimo-backend/compose.yaml --profile ingester` | 위 + 적재 처리기 컨테이너 (`:8082`) | `monimo-dev` |
| `monimo-shop/docker-compose.dev.yml` (예정) | 쇼핑몰 4개 + MySQL. OTel 에이전트는 `collector:4317` 로 보낸다 | `monimo-dev` (external) |
| (없음) | api-server · detector · notifier 는 `bootRun` 또는 IDE 로 실행. 컨테이너 프로필은 구현 때 추가 | |

프로필은 겹쳐 쓸 수 있다: `--profile collector --profile ingester`. 같은 서비스를 IDE 로도 띄우면 호스트 포트가 겹치므로 둘 중 하나만 켠다.

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

- 브랜치 전략: 기능 브랜치 → `develop`(기본 브랜치, 작업을 모으는 곳) → 배포 단위로 `develop` → `main`
- `main` · `develop` 직접 push 금지, PR로만 머지. PR 의 base 는 기본값(`develop`) 그대로 두면 된다
- 브랜치: `feat/<이슈번호>-<설명>` · `fix/<이슈번호>-<설명>` · `chore/<설명>`
- 커밋: `<타입>(<범위>): <요약>` (타입: feat · fix · docs · chore · refactor · test)
