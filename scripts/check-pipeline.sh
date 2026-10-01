#!/usr/bin/env bash
# 파이프라인 관통 점검: 가짜 발신기(telemetrygen) → 수집기(OTLP gRPC) → Kafka raw → 적재 처리기 → ClickHouse.
# 수집기가 "받은 건수" 와 적재 처리기가 "푼 건수" 가 같은 만큼 늘었는지 대조하고,
# 트레이스는 ClickHouse spans 줄 수까지 늘었는지 본다 (#58 부터 적재가 있다. 메트릭 · 로그는 적재가 생기면 같은 자리에 붙인다).
# 중간에 하나라도 끊기면 숫자가 어긋나므로, 어디서 막혔는지도 메시지로 드러난다.
#
# 사용:
#   docker network create monimo-dev          # 처음 한 번
#   docker compose --profile collector --profile ingester up -d --wait --build
#   ./scripts/check-pipeline.sh
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a

TELEMETRYGEN=ghcr.io/open-telemetry/opentelemetry-collector-contrib/telemetrygen:v0.161.0
NET=monimo-dev
COLLECTOR=localhost:${COLLECTOR_HTTP_PORT:-8081}
INGESTER=localhost:${INGESTER_HTTP_PORT:-8082}
WAIT_SECONDS=20   # 적재 처리기가 소비할 때까지 기다리는 최대 시간

ok() { echo "✓ $1"; }
fail() { echo "✗ $1"; exit 1; }

docker network inspect "$NET" > /dev/null 2>&1 && ok "공용 네트워크 $NET" || fail "공용 네트워크 $NET 없음 (docker network create $NET)"

# 두 컨테이너가 다 떠 있어야 한다
for service in collector ingester; do
  health=$(docker compose --profile collector --profile ingester ps --format '{{.Service}} {{.Health}}' | awk -v s="$service" '$1 == s { print $2 }')
  [ "$health" = "healthy" ] && ok "$service 컨테이너 정상" \
    || fail "$service 상태: ${health:-꺼져 있음} (docker compose --profile collector --profile ingester up -d --wait --build)"
done

# 카운터 읽기. $1 = 주소, $2 = 지표 이름, $3 = 신호
counter() {
  curl -fs "http://$1/actuator/metrics/$2?tag=signal:$3" \
    | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2 | cut -d. -f1
}
received() { counter "$COLLECTOR" monimo.collector.otlp.received "$1"; }
consumed() { counter "$INGESTER" monimo.ingester.raw.consumed "$1"; }

# ClickHouse 표 줄 수. $1 = 표 이름. 컨테이너 안에서 clickhouse-client 를 치므로 계정은 컨테이너 환경변수를 쓴다
rows() {
  docker compose exec -T clickhouse bash -c \
    'clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "SELECT count() FROM monimo.'"$1"'"'
}
# 신호별로 적재되는 표. 비어 있으면 아직 적재가 없는 신호라 ClickHouse 확인을 건너뛴다
table_of() { case "$1" in traces) echo spans ;; esac; }

for signal in traces metrics logs; do
  before_in=$(received "$signal")  || fail "수집기 카운터($signal) 조회 실패: http://$COLLECTOR/actuator/metrics"
  before_out=$(consumed "$signal") || fail "적재 처리기 카운터($signal) 조회 실패: http://$INGESTER/actuator/metrics"
  table=$(table_of "$signal")
  if [ -n "$table" ]; then
    before_rows=$(rows "$table") || fail "ClickHouse $table 조회 실패 (docker compose ps clickhouse)"
  fi

  # 컨테이너 안에서 collector:4317 로 보낸다 = 쇼핑몰 에이전트가 쓸 주소 그대로
  docker run --rm --network "$NET" "$TELEMETRYGEN" "$signal" --otlp-endpoint collector:4317 --otlp-insecure "--$signal" 3 > /dev/null 2>&1 \
    || fail "telemetrygen $signal 전송 실패 (collector:4317)"

  after_in=$(received "$signal")
  [ "$after_in" -gt "$before_in" ] || fail "$signal 을 보냈지만 수집기가 못 받았다 ($before_in → $after_in)"
  sent=$((after_in - before_in))

  # 소비는 비동기라 바로 안 보인다. 같은 만큼 늘 때까지 기다린다
  for _ in $(seq 1 $((WAIT_SECONDS * 2))); do
    after_out=$(consumed "$signal")
    [ $((after_out - before_out)) -ge "$sent" ] && break
    sleep 0.5
  done

  got=$((after_out - before_out))
  [ "$got" -eq "$sent" ] \
    || fail "$signal 이 중간에서 끊겼다 — 수집기는 ${sent}건 받았는데 적재 처리기는 ${got}건만 풀었다 (${WAIT_SECONDS}초 대기). Kafka 토픽 raw 와 적재 처리기 로그를 확인하라"

  # 적재할 표가 없는 신호는 여기서 끝
  if [ -z "$table" ]; then
    ok "$signal ${sent}건: 수집기 수신 → Kafka raw → 적재 처리기 소비까지 도착 (ClickHouse 적재는 아직 없음)"
    continue
  fi

  # 소비 카운터가 오른 시점은 "풀었다" 이고 insert 는 그 뒤다. 줄 수가 늘 때까지 다시 기다린다.
  # 요청 1건에 스팬이 여러 개(telemetrygen 은 trace 당 부모 + 자식)라 정확한 수가 아니라 늘었는지만 본다
  for _ in $(seq 1 $((WAIT_SECONDS * 2))); do
    after_rows=$(rows "$table")
    [ "$after_rows" -gt "$before_rows" ] && break
    sleep 0.5
  done
  added=$((after_rows - before_rows))
  [ "$added" -gt 0 ] \
    && ok "$signal ${sent}건: 수집기 수신 → Kafka raw → 적재 처리기 소비 → ClickHouse $table (+${added}줄)" \
    || fail "$signal 을 적재 처리기가 풀었지만 ClickHouse $table 에 안 들어갔다 (${before_rows}줄 그대로, ${WAIT_SECONDS}초 대기). 적재 처리기 로그의 insert 오류를 확인하라"
done

echo "파이프라인 관통 정상: 에이전트 → 수집기 → Kafka raw → 적재 처리기 → ClickHouse"
