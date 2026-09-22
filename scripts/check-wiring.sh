#!/usr/bin/env bash
# 로컬 연결 약속(개발환경 6단계) 점검: 쇼핑몰 대신 가짜 발신기(telemetrygen)를 공용 네트워크 monimo-dev 에 띄워
# collector:4317 로 트레이스 · 메트릭 · 로그를 보내고, 수집기가 받았는지 /actuator/metrics 로 확인한다.
# 사용: docker compose --profile collector up -d --wait --build && ./scripts/check-wiring.sh
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a

TELEMETRYGEN=ghcr.io/open-telemetry/opentelemetry-collector-contrib/telemetrygen:v0.161.0
NET=monimo-dev
HTTP=localhost:${COLLECTOR_HTTP_PORT:-8081}

ok() { echo "✓ $1"; }
fail() { echo "✗ $1"; exit 1; }

docker network inspect "$NET" > /dev/null 2>&1 && ok "공용 네트워크 $NET" || fail "공용 네트워크 $NET 없음 (docker network create $NET)"

health=$(docker compose --profile collector ps --format '{{.Service}} {{.Health}}' | awk '$1 == "collector" { print $2 }')
[ "$health" = "healthy" ] && ok "collector 컨테이너 정상" || fail "collector 상태: ${health:-꺼져 있음} (docker compose --profile collector up -d --wait --build)"

# 신호별 받은 건수 (수집기 카운터 monimo.collector.otlp.received)
received() {
  curl -fs "http://$HTTP/actuator/metrics/monimo.collector.otlp.received?tag=signal:$1" \
    | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2 | cut -d. -f1
}

for signal in traces metrics logs; do
  before=$(received "$signal") || fail "collector 카운터($signal) 조회 실패: http://$HTTP/actuator/metrics"
  # 컨테이너 안에서 collector:4317 로 보낸다 = 쇼핑몰 에이전트가 쓸 주소 그대로
  docker run --rm --network "$NET" "$TELEMETRYGEN" "$signal" --otlp-endpoint collector:4317 --otlp-insecure "--$signal" 3 > /dev/null 2>&1 \
    || fail "telemetrygen $signal 전송 실패 (collector:4317)"
  after=$(received "$signal")
  [ "$after" -gt "$before" ] && ok "collector:4317 로 보낸 $signal 을 수집기가 받음 ($before → $after)" || fail "$signal 을 보냈지만 수집기 카운터가 안 늘었음 ($before → $after)"
done

echo "연결 약속 정상: 쇼핑몰 에이전트는 collector:4317 (컨테이너끼리) · localhost:${COLLECTOR_OTLP_PORT:-4317} (내 컴퓨터) 로 보내면 된다"
