# syntax=docker/dockerfile:1
# 아래 ADD --chmod 는 BuildKit frontend 기능이라 이 줄이 있어야 어느 도커에서나 같게 빌드된다
# ClickHouse 마이그레이션을 돌리는 일회성 컨테이너 (ADR #57).
# PG 쪽 postgres-migrate 와 같은 일을 하고, 같은 Flyway 버전을 쓴다.
#
# Flyway 본체는 ClickHouse 를 모른다. 공식 "커뮤니티 지원 DB" 라 플러그인을 따로 얹어야 한다.
# 플러그인은 10.26.0 이 최신이고 11.x 줄이 아예 없다 (2026-10-09 Maven Central 확인).
# 그래도 11.7.2 이미지에서 붙는 것을 확인했다 : ClickHouse 26.8 을 알아보고
# ALTER ... MODIFY QUERY 까지 적용된다. PG 와 Flyway 버전을 맞추는 쪽이 중요해서
# 플러그인에 맞춰 본체를 10.x 로 내리지 않았다.
#
# jar 를 레포에 커밋하지 않고 빌드할 때 받는다. 드라이버가 12MB 라 레포에 둘 것이 아니다.
FROM flyway/flyway:11.7.2-alpine

# 드라이버는 애플리케이션이 쓰는 것과 같은 버전으로 맞춘다 (gradle/libs.versions.toml 의 clickhouse-java)
ARG FLYWAY_CLICKHOUSE_VERSION=10.26.0
ARG CLICKHOUSE_JDBC_VERSION=0.10.0

# /flyway/drivers 에 둔다. /flyway/jars 는 Java 마이그레이션을 두는 자리라
# 거기 넣으면 "Storing migrations in 'jars' is not recommended" 경고가 뜬다
ADD --chmod=644 \
    https://repo1.maven.org/maven2/org/flywaydb/flyway-database-clickhouse/${FLYWAY_CLICKHOUSE_VERSION}/flyway-database-clickhouse-${FLYWAY_CLICKHOUSE_VERSION}.jar \
    /flyway/drivers/
ADD --chmod=644 \
    https://repo1.maven.org/maven2/com/clickhouse/clickhouse-jdbc/${CLICKHOUSE_JDBC_VERSION}/clickhouse-jdbc-${CLICKHOUSE_JDBC_VERSION}-all.jar \
    /flyway/drivers/
