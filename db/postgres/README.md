# PostgreSQL 마이그레이션

PostgreSQL 표 정의는 **전부 이 폴더 한 곳**에 두고, 전용 Flyway 컨테이너가 **한 번만** 실행한다. 서비스(Spring Boot)는 마이그레이션을 돌리지 않는다. (ADR #49)

## 왜 한 곳인가

표 9개가 서로 다른 파트의 표를 FK로 참조한다. 예: `alert_events` → `agents` → `applications`.
서비스마다 따로 돌리면 실행 순서가 꼬이고, 같은 DB에 이력 표(`flyway_schema_history`)가 여러 개 생겨 충돌한다.

## 폴더 = 표의 주인 파트

| 폴더 | 파트 | 표 |
|---|---|---|
| `config/` | 인증 설정 | `users` · `applications` · `application_configs` |
| `alert/` | 알림 | `alert_rules` · `alert_channels` · `alert_rule_channels` · `alert_events` · `notification_history` (+ 발송 대기 큐) |
| `ingest/` | 수집 | `agents` |

조회 파트는 ClickHouse만 읽으므로 PostgreSQL 표가 없다. ClickHouse 표는 `db/clickhouse/`.

## 파일 이름

```
V{년월일시분}__{동사}_{대상}.sql
예) config/V202609231030__create_applications.sql
    alert/V202609231415__create_alert_rules.sql
```

- 번호는 **파일을 만드는 시각**으로 적는다. 여러 명이 동시에 추가해도 겹치지 않는다.
- 다른 파트 표를 FK로 참조하면, 그 표를 만드는 파일보다 **번호가 커야** 한다. (예: `alert_rules` 는 `applications` 뒤)
- 이름 규칙에 안 맞는 파일은 조용히 무시되지 않고 **실행이 실패**하도록 설정해 두었다.

## 지켜야 할 것

- **이미 main에 들어간 파일은 고치지 않는다.** 바꿀 게 있으면 새 파일(`V…__alter_…`)을 추가한다. 고치면 Flyway가 체크섬 불일치로 멈춘다.
- 표 하나에 주인은 하나. 다른 파트 폴더의 파일은 그 파트 리뷰를 받는다.

## 로컬에서

```bash
docker compose -f docker-compose.dev.yml up -d --wait   # 켜면서 마이그레이션까지 끝낸다
./scripts/check-dev-infra.sh                            # 적용 결과 확인
docker compose -f docker-compose.dev.yml run --rm postgres-migrate info   # 적용 이력 보기
```

## 번호와 머지 순서가 다를 때

번호는 파일을 만든 시각인데 머지 순서는 다를 수 있다. (월요일에 만든 A가 화요일에 만든 B보다 늦게 머지)
그래서 **늦게 들어온 작은 번호도 적용**하도록 켜 두었다(`outOfOrder`).

대신 순서에 기대는 실수는 **CI가 잡는다.** CI(`dev-infra`)는 항상 빈 DB에 번호 순으로 전부 실행하므로,
작은 번호 파일이 아직 없는 표를 참조하면 PR 단계에서 실패한다. 그러면 파일 이름의 시각을 지금으로 바꿔 다시 올린다.

## 1b 이후

EKS로 가면 이 폴더를 `monimo-deploy/schema/postgres` 로 옮기고, 같은 Flyway 이미지를 쿠버네티스 Job으로 실행한다.
