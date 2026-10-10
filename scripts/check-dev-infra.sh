#!/usr/bin/env bash
# 로컬 인프라(compose.yaml)가 제대로 떴는지 확인한다.
# 사용: ./scripts/check-dev-infra.sh
set -euo pipefail
cd "$(dirname "$0")/.."

C() { docker compose "$@"; }
ok() { echo "✓ $1"; }
fail() { echo "✗ $1"; exit 1; }

docker network inspect monimo-dev > /dev/null 2>&1 && ok "공용 네트워크 monimo-dev" || fail "공용 네트워크 monimo-dev 없음 (docker network create monimo-dev)"

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

# 장부(flyway_schema_history)는 빼고 센다. 장부도 monimo 안에 있지만(ADR #57) 우리 표가 아니다.
# 12로 세면 "우리 표 11개" 라는 뜻이 흐려지고, 누가 monimo 에 임시 표를 하나 만들면 숫자가 맞아 조용히 통과한다
ch_objects=$(C exec -T clickhouse bash -c 'clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "SELECT countIf(engine != '"'MaterializedView'"'), countIf(engine = '"'MaterializedView'"') FROM system.tables WHERE database = '"'monimo'"' AND name != '"'flyway_schema_history'"'"')
[ "$ch_objects" = "$(printf '11\t7')" ] && ok "ClickHouse 표 11개 · MV 7개" || fail "ClickHouse 표 · MV 개수가 다름 (표 MV: ${ch_objects})"

# PG 와 같은 기준으로 ClickHouse 장부도 본다. 실패 행이 하나라도 있으면 그 뒤가 조용히 안 돈다.
# 세는 것은 SQL 행만이다. 첫 줄은 Flyway 가 깐 SCHEMA(빈 서버) 또는 BASELINE(옛 로컬)이라 사람마다 다르고,
# 그것까지 세면 migrate 로그의 "applied 6 migrations" 와 숫자가 어긋나 보는 사람이 점검을 의심한다
ch_mig=$(C exec -T clickhouse bash -c 'clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "SELECT countIf(success AND type = '"'SQL'"'), countIf(NOT success) FROM monimo.flyway_schema_history"' 2>/dev/null || echo "")
ch_ok=${ch_mig%%$'\t'*}; ch_fail=${ch_mig##*$'\t'}
[ -n "$ch_mig" ] && [ "$ch_fail" = "0" ] && [ "${ch_ok:-0}" -ge 1 ] \
  && ok "ClickHouse 마이그레이션 ${ch_ok}개 적용" || fail "ClickHouse 마이그레이션 확인 실패 (성공 ${ch_ok:-?} · 실패 ${ch_fail:-?})"

C exec -T postgres sh -c 'pg_isready -q -U "$POSTGRES_USER" -d monimo' && ok "PostgreSQL DB monimo" || fail "PostgreSQL DB monimo 접속 실패"

applied=$(C exec -T postgres sh -c 'psql -tA -U "$POSTGRES_USER" -d monimo -c "SELECT count(*) FILTER (WHERE success), count(*) FILTER (WHERE NOT success) FROM flyway_schema_history"' 2>/dev/null || echo "")
ok_cnt=${applied%%|*}; fail_cnt=${applied##*|}
[ -n "$applied" ] && [ "$fail_cnt" = "0" ] && [ "${ok_cnt:-0}" -ge 1 ] \
  && ok "PostgreSQL 마이그레이션 ${ok_cnt}개 적용" || fail "PostgreSQL 마이그레이션 확인 실패 (성공 ${ok_cnt:-?} · 실패 ${fail_cnt:-?})"

echo "모두 정상"
