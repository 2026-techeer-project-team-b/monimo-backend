#!/usr/bin/env bash
# 로컬에 가짜 데이터를 넣는다. ClickHouse 표 11개를 비운 뒤 다시 채우므로 몇 번을 돌려도 같다.
# PostgreSQL 에는 같은 이름의 감시 대상 서비스 4줄을 넣는다 (없을 때만).
# 두 저장소를 잇는 끈이 서비스 이름 하나뿐이라, CH 에만 넣으면 화면의 서비스 목록이 비어 아무것도 안 보인다.
# 사용: docker compose up -d --wait && ./scripts/seed-clickhouse.sh
# 시각은 "지금부터 1시간 전까지"로 들어간다. 오래 지나면 다시 돌리면 된다.
set -euo pipefail
cd "$(dirname "$0")/.."

CH() { docker compose exec -T clickhouse bash -c 'clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --multiquery'; }

tables="spans metrics_raw logs thread_dumps transactions heatmap_1m url_stats_1m server_map_1m service_health_1m metrics_1m metrics_1h"

echo "비우는 중..."
for t in $tables; do echo "TRUNCATE TABLE monimo.$t;"; done | CH

echo "넣는 중..."
CH < db/clickhouse/seed/001_fake_signals.sql

echo "PostgreSQL 감시 대상 서비스 넣는 중..."
docker compose exec -T postgres sh -c 'psql -q -U "$POSTGRES_USER" -d monimo' < scripts/seed/postgres-applications.sql

echo "표별 줄 수"
for t in $tables; do echo "SELECT '$t', count() FROM monimo.$t;"; done | CH | awk '{ printf "  %-18s %s\n", $1, $2 }'
docker compose exec -T postgres sh -c 'psql -tA -U "$POSTGRES_USER" -d monimo -c "SELECT '"'"'applications'"'"', count(*) FROM applications"' | awk -F'|' '{ printf "  %-18s %s\n", $1, $2 }'
