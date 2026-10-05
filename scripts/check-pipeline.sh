#!/usr/bin/env bash
# 파이프라인 관통 점검: 가짜 발신기(telemetrygen) → 수집기(OTLP gRPC) → Kafka raw → 적재 처리기 → ClickHouse.
# 수집기가 "받은 건수" 와 적재 처리기가 "푼 건수" 가 같은 만큼 늘었는지 대조하고,
# 신호별 ClickHouse 표(spans · metrics_raw · logs)의 줄 수까지 늘었는지 본다.
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

# 수집기가 중간에서 버린 스팬 수. 수신(버리기 전)과 소비(버린 뒤)를 대조할 때 이만큼 빼야 숫자가 맞는다.
# 버리는 자리가 둘이다: 헬스체크 거르기(#92)와 트레이스 샘플링(#46).
# 두 지표에는 signal 태그가 없고 트레이스에만 해당하므로, traces 검사에서만 쓴다.
# 거르기가 꺼져 있거나(목록이 빔) 샘플링이 1.0 이면 카운터가 없거나 0 이라 조회 실패를 0 으로 본다
# 끝의 || true 가 없으면 set -e · pipefail 때문에 조회 실패가 스크립트 전체를 멈춰서, 부르는 쪽의 ${x:-0} 에 닿지 못한다
metric_value() {
  curl -fs "http://$COLLECTOR/actuator/metrics/$1?tag=$2" 2>/dev/null \
    | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2 | cut -d. -f1 || true
}

# 적재 처리기가 raw.dlq 로 보낸 메시지 수 (ADR #51). reason 태그(poison · transient · unknown)를 합친 총합.
# DLQ 로 간 것은 소비 카운터에 안 오르므로 수신 ≠ 소비 가 된다. #92 의 "버린 수" 와 달리 여기서 빼서 맞추지 않는다 :
# 헬스체크는 의도해서 버린 것이지만 DLQ 는 의도하지 않은 실패라, 숫자를 맞추면 실패를 숨기는 꼴이 된다.
# 대신 숫자가 안 맞을 때 "DLQ 에 N건 들어갔다" 고 원인을 말해 준다
dlq_total() {
  curl -fs "http://$INGESTER/actuator/metrics/monimo.ingester.dlq" 2>/dev/null \
    | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2 | cut -d. -f1 || true
}
dropped_total() {
  case "$1" in
    traces)
      h=$(metric_value monimo.collector.dropped reason:health_check); h=${h:-0}
      r=$(metric_value monimo.collector.sampling outcome:dropped);    r=${r:-0}
      echo $((h + r))
      ;;
    *) echo 0 ;;   # 메트릭 · 로그는 버리는 자리가 없다
  esac
}

# ClickHouse 표 줄 수. $1 = 표 이름. 컨테이너 안에서 clickhouse-client 를 치므로 계정은 컨테이너 환경변수를 쓴다
rows() {
  docker compose exec -T clickhouse bash -c \
    'clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "SELECT count() FROM monimo.'"$1"'"'
}
# 신호별로 적재되는 표. 비어 있으면 적재가 없는 신호라 ClickHouse 확인을 건너뛴다 (지금은 셋 다 있다)
table_of() { case "$1" in traces) echo spans ;; metrics) echo metrics_raw ;; logs) echo logs ;; esac; }

for signal in traces metrics logs; do
  before_in=$(received "$signal")  || fail "수집기 카운터($signal) 조회 실패: http://$COLLECTOR/actuator/metrics"
  before_out=$(consumed "$signal") || fail "적재 처리기 카운터($signal) 조회 실패: http://$INGESTER/actuator/metrics"
  before_dropped=$(dropped_total "$signal")
  before_dlq=$(dlq_total); before_dlq=${before_dlq:-0}
  table=$(table_of "$signal")
  if [ -n "$table" ]; then
    before_rows=$(rows "$table") || fail "ClickHouse $table 조회 실패 (docker compose ps clickhouse)"
  fi

  # 컨테이너 안에서 collector:4317 로 보낸다 = 쇼핑몰 에이전트가 쓸 주소 그대로
  docker run --rm --network "$NET" "$TELEMETRYGEN" "$signal" --otlp-endpoint collector:4317 --otlp-insecure "--$signal" 3 > /dev/null 2>&1 \
    || fail "telemetrygen $signal 전송 실패 (collector:4317)"

  # 소비는 비동기라 바로 안 보인다. 기댓값(받은 수 - 버린 수)에 도달할 때까지 기다린다.
  #
  # 세 카운터를 매번 다시 읽는 이유: 쇼핑몰을 같이 띄워 두면 헬스체크가 몇 초마다 들어와 수신 ·
  # 버린 수가 계속 움직인다. 한 번 읽어 고정해 두면 "수신은 올랐는데 버린 수는 아직" 인 찰나에
  # 기댓값이 틀어진다. 소비를 먼저 읽어(가장 오래된 값) 기댓값이 모자라는 쪽으로만 기울게 한다
  for _ in $(seq 1 $((WAIT_SECONDS * 2))); do
    cur_out=$(consumed "$signal")
    cur_in=$(received "$signal")
    cur_dropped=$(dropped_total "$signal")
    sent=$(( (cur_in - before_in) - (cur_dropped - before_dropped) ))
    got=$((cur_out - before_out))
    [ "$sent" -gt 0 ] && [ "$got" -eq "$sent" ] && break
    sleep 0.5
  done

  received_delta=$((cur_in - before_in))
  dropped_delta=$((cur_dropped - before_dropped))
  [ "$received_delta" -gt 0 ] || fail "$signal 을 보냈지만 수집기가 못 받았다 ($before_in -> $cur_in)"
  [ "$sent" -gt 0 ] \
    || fail "$signal 을 보냈지만 수집기가 전부 버렸다 (받은 수 $received_delta · 버린 수 $dropped_delta). MONIMO_COLLECTOR_HEALTH_CHECK_PATHS 와 MONIMO_COLLECTOR_SAMPLING_RATIO 를 확인하라"
  if [ "$got" -ne "$sent" ]; then
    cur_dlq=$(dlq_total); cur_dlq=${cur_dlq:-0}
    dlq_delta=$((cur_dlq - before_dlq))
    [ "$dlq_delta" -gt 0 ] \
      && fail "$signal 중 ${dlq_delta}건이 적재에 실패해 raw.dlq 로 갔다 (수집기가 ${sent}건 넘김 · 적재 처리기는 ${got}건 풂). 숫자를 맞추지 않는다 : DLQ 는 실패다. 적재 처리기 로그의 'raw-N@M → raw.dlq (reason=...)' 줄과 FailureClassifier 의 분류를 확인하라"
    fail "$signal 이 중간에서 끊겼다: 수집기가 ${sent}건 넘겼는데(받은 수 $received_delta · 버린 수 $dropped_delta) 적재 처리기는 ${got}건만 풀었다 (${WAIT_SECONDS}초 대기). raw.dlq 에도 안 갔다. Kafka 토픽 raw 와 적재 처리기 로그를 확인하라"
  fi

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
