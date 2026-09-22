#!/usr/bin/env bash
# 로컬 ClickHouse에 가짜 신호 데이터를 넣는다. 표 11개를 비운 뒤 다시 채우므로 몇 번을 돌려도 같다.
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

echo "표별 줄 수"
for t in $tables; do echo "SELECT '$t', count() FROM monimo.$t;"; done | CH | awk '{ printf "  %-18s %s\n", $1, $2 }'
