#!/usr/bin/env bash
# 로컬 연결 약속(개발환경 6단계) 점검: 쇼핑몰 대신 가짜 발신기(telemetrygen)를 공용 네트워크 monimo-dev 에 띄워
# collector:4317 로 트레이스 · 메트릭 · 로그를 보내고, 수집기가 받았는지 /actuator/metrics 로 확인한다.
# 사용: docker compose --profile collector up -d --wait --build && ./scripts/check-wiring.sh
# 적재 처리기까지 한 줄로 확인하려면 scripts/check-pipeline.sh 를 쓴다 (CI 가 도는 것도 그쪽이다).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a

TELEMETRYGEN=ghcr.io/open-telemetry/opentelemetry-collector-contrib/telemetrygen:v0.161.0
NET=monimo-dev
HTTP=localhost:${COLLECTOR_MANAGEMENT_PORT:-18081}   # /actuator/metrics 는 관리 포트(업무 포트 + 10000)에 있다 (#135 · #148)

ok() { echo "✓ $1"; }
fail() { echo "✗ $1"; exit 1; }

# 수집기의 TLS 상태에 맞춰 보낸다 (#159 · ADR #64). 수집기와 같은 .env 를 읽는다 :
#   COLLECTOR_TLS_CLIENT_AUTH 가 none(기본)이면 지금까지처럼 평문(--otlp-insecure),
#   optional · require 면 ./certs 의 점검용 신분증(clients/check)을 내고 기관 인증서로 수집기 신분증을 검사한다(--mtls).
# 수집기만 켜고 .env 를 안 바꾸면 여기서 바로 끊긴다. 그것도 점검이다 : 쇼핑몰 에이전트가 겪을 일을 먼저 겪는다
TLS=${COLLECTOR_TLS_CLIENT_AUTH:-none}
send() {   # $1 = 신호 (traces · metrics · logs). 컨테이너 안에서 collector:4317 로 보낸다 = 쇼핑몰 에이전트가 쓸 주소 그대로
  if [ "$TLS" = "none" ]; then
    docker run --rm --network "$NET" "$TELEMETRYGEN" "$1" --otlp-endpoint collector:4317 --otlp-insecure "--$1" 3
  else
    [ -f certs/clients/check.crt ] || fail "certs/clients/check.crt 가 없다. docker compose up 이 otlp-certs 로 만든다 (COLLECTOR_TLS_CLIENT_AUTH=$TLS)"
    docker run --rm --network "$NET" -v "$PWD/certs:/certs:ro" "$TELEMETRYGEN" "$1" --otlp-endpoint collector:4317 \
      --mtls --ca-cert /certs/ca.crt --client-cert /certs/clients/check.crt --client-key /certs/clients/check.key "--$1" 3
  fi
}

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
  send "$signal" > /dev/null 2>&1 \
    || fail "telemetrygen $signal 전송 실패 (collector:4317, TLS=$TLS). 수집기 로그의 'OTLP gRPC 수신 시작' 줄과 .env 의 COLLECTOR_TLS_CLIENT_AUTH 가 맞는지 보라"
  after=$(received "$signal")
  [ "$after" -gt "$before" ] && ok "collector:4317 로 보낸 $signal 을 수집기가 받음 ($before → $after)" || fail "$signal 을 보냈지만 수집기 카운터가 안 늘었음 ($before → $after)"
done

echo "연결 약속 정상: 쇼핑몰 에이전트는 collector:4317 (컨테이너끼리) · localhost:${COLLECTOR_OTLP_PORT:-4317} (내 컴퓨터) 로 보내면 된다"
