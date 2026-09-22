#!/usr/bin/env bash
# 로컬 인프라(docker-compose.dev.yml)가 제대로 떴는지 확인한다.
# 사용: ./scripts/check-dev-infra.sh
set -euo pipefail
cd "$(dirname "$0")/.."

C() { docker compose -f docker-compose.dev.yml "$@"; }
ok() { echo "✓ $1"; }
fail() { echo "✗ $1"; exit 1; }

for s in kafka clickhouse postgres; do
  health=$(C ps --format '{{.Service}} {{.Health}}' | awk -v s="$s" '$1 == s { print $2 }')
  [ "$health" = "healthy" ] && ok "$s 정상" || fail "$s 상태: ${health:-꺼져 있음}"
done

topics=$(C exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list)
for t in raw raw.dlq; do
  grep -qx "$t" <<< "$topics" && ok "Kafka 토픽 $t" || fail "Kafka 토픽 $t 없음"
done

db=$(C exec -T clickhouse bash -c 'clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "EXISTS DATABASE monimo"')
[ "$db" = "1" ] && ok "ClickHouse DB monimo" || fail "ClickHouse DB monimo 없음"

C exec -T postgres sh -c 'pg_isready -q -U "$POSTGRES_USER" -d monimo' && ok "PostgreSQL DB monimo" || fail "PostgreSQL DB monimo 접속 실패"

applied=$(C exec -T postgres sh -c 'psql -tA -U "$POSTGRES_USER" -d monimo -c "SELECT count(*) FILTER (WHERE success), count(*) FILTER (WHERE NOT success) FROM flyway_schema_history"' 2>/dev/null || echo "")
ok_cnt=${applied%%|*}; fail_cnt=${applied##*|}
[ -n "$applied" ] && [ "$fail_cnt" = "0" ] && [ "${ok_cnt:-0}" -ge 1 ] \
  && ok "PostgreSQL 마이그레이션 ${ok_cnt}개 적용" || fail "PostgreSQL 마이그레이션 확인 실패 (성공 ${ok_cnt:-?} · 실패 ${fail_cnt:-?})"

echo "모두 정상"
