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
# /actuator/metrics 는 관리 포트(업무 포트 + 10)에 있다 (#135). compose 가 호스트에 열어 준다
COLLECTOR=localhost:${COLLECTOR_MANAGEMENT_PORT:-18081}   # 관리 포트 = 업무 포트 + 10000 (#148)
INGESTER=localhost:${INGESTER_MANAGEMENT_PORT:-18082}
WAIT_SECONDS=20   # 적재 처리기가 소비할 때까지 기다리는 최대 시간

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
# 아무 조회나 한 줄로. $1 = SQL. 중복 대조(아래)가 쓴다
ch_query() {
  docker compose exec -T clickhouse bash -c \
    'clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --query "'"$1"'"'
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

  send "$signal" > /dev/null 2>&1 \
    || fail "telemetrygen $signal 전송 실패 (collector:4317, TLS=$TLS). 수집기 로그의 'OTLP gRPC 수신 시작' 줄과 .env 의 COLLECTOR_TLS_CLIENT_AUTH 가 맞는지 보라"

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

# ── 중복 대조 (#187)
#
# 왜 필요한가 : 적재 처리기가 ClickHouse 에 넣은 뒤 Kafka 오프셋을 커밋하기 전에 죽으면 같은 레코드를
# 다시 넣는다. 그것은 ClickHouse 쪽 중복 제거 기억이 막는데(db/clickhouse 의 창 설정), **놓치는 경우가 있다** :
# 같은 레코드라도 적재 처리기가 내는 바이트가 달라지면 기억이 못 알아본다(research.md 2.2 의 peer_service).
# 그래서 "고쳤는데 가끔 그대로" 인 상태를 알아낼 장치가 따로 필요하다.
#
# 틀어지는 모양이 둘이라 둘 다 본다 :
#   ① 원본과 집계가 같이 중복 (기억이 아예 못 걸렀다)        -> 원본 자체 대조로 보인다
#   ② 원본은 깨끗한데 집계만 중복 (표마다 따로인 기억이 빠졌다) -> **원본만 세면 안 보인다.** 그래서 집계와 대조한다
#
# 최근 10분으로 범위를 좁힌다 : 공유 로컬 DB 에는 MV 정의가 바뀌기 전(#118)에 들어온 옛 줄이 남아 있을 수 있고,
# 그것까지 세면 멀쩡한데도 어긋난 것처럼 보인다. MV 는 insert 와 같은 시점에 채우므로 기다릴 필요는 없다.
echo "중복 대조 (최근 10분)"
RECENT="start_time >= now() - INTERVAL 10 MINUTE"

# ① spans 자체 : 같은 (trace_id, span_id) 가 두 줄이면 중복이다
dup=$(ch_query "SELECT count() - uniqExact(trace_id, span_id) FROM monimo.spans WHERE $RECENT") \
  || fail "spans 중복 대조 조회 실패"
[ "$dup" = "0" ] || fail "spans 에 중복 ${dup}줄 (같은 trace_id·span_id 가 두 번 들어왔다). ClickHouse 중복 제거 기억이 못 걸렀다는 뜻이다 : db/clickhouse 의 non_replicated_deduplication_window 설정과 적재 처리기 재시작 이력을 보라"
ok "spans 중복 0줄"

# ② spans(SERVER) ↔ service_health_1m : 탐지가 읽는 집계다. MV 조건(span_kind = 'SERVER')을 그대로 맞춘다
src=$(ch_query "SELECT count() FROM monimo.spans WHERE span_kind = 'SERVER' AND $RECENT") || fail "spans SERVER 조회 실패"
agg=$(ch_query "SELECT countMerge(cnt) FROM monimo.service_health_1m WHERE ts_min >= toStartOfMinute(now() - INTERVAL 10 MINUTE)") || fail "service_health_1m 조회 실패"
[ "$src" = "$agg" ] || fail "집계가 원본과 다르다: spans(SERVER) ${src}줄인데 service_health_1m 은 ${agg}건으로 센다. 집계만 중복되면 에러율 · P95 가 틀어진다 (탐지가 이 표를 읽는다)"
ok "spans(SERVER) ${src} = service_health_1m ${agg}"

# ③ transactions ↔ heatmap_1m : **연쇄 2단**이다 (spans -> transactions -> heatmap_1m).
# 중복 제거 판정이 다리 하나는 넘는 것이 확인됐고 두 번째 다리가 이 대조로 드러난다
src=$(ch_query "SELECT count() FROM monimo.transactions WHERE $RECENT") || fail "transactions 조회 실패"
agg=$(ch_query "SELECT sum(cnt) FROM monimo.heatmap_1m WHERE ts_min >= toStartOfMinute(now() - INTERVAL 10 MINUTE)") || fail "heatmap_1m 조회 실패"
[ "$src" = "$agg" ] || fail "연쇄 집계가 원본과 다르다: transactions ${src}줄인데 heatmap_1m 은 ${agg}건으로 센다. 다리 둘짜리 MV 사슬에서 중복 제거가 두 번째 다리를 못 넘었다는 뜻이다"
ok "transactions ${src} = heatmap_1m ${agg}"

# 지표(metrics_raw · metrics_1m · metrics_1h)와 로그(logs)는 대조하지 않는다. 이유가 둘 다 있다.
#
# ㄱ 거짓 경보가 난다. 처음에 "한 계열(series_hash)의 한 시각에 점은 하나" 로 보고 지표 중복을 세게 만들었는데
#   바로 걸렸다 : telemetrygen 이 같은 이름 · 같은 꼬리표의 지표 셋을 보내고 metrics_raw.ts 가 **초 단위**라
#   둘이 같은 초에 떨어졌다. 값은 서로 달랐으니(0 과 1) 중복이 아니라 **진짜 점 둘**이다(실측).
#   값까지 키에 넣어도 안 낫다 : 안 변하는 게이지가 같은 초에 두 번 찍히면 진짜와 구분이 안 된다.
#   로그도 같은 이유다 : 같은 글자가 같은 시각에 두 번 찍히는 것이 진짜일 수 있다.
#
# ㄴ 지표는 중복이 집계를 틀어뜨리지 못한다. metrics_1m · metrics_1h 의 롤업이 avg · min · max · argMax 라
#   **같은 값이 두 번 들어와도 결과가 안 바뀐다**(멱등). 즉 중복의 영향이 metrics_raw 줄 수뿐이고
#   그 줄은 창 설정이 막는다. 로그는 MV 가 아예 없다.
#
# 그래서 대조는 스팬 쪽만 본다. 틀어졌을 때 사람이 보는 숫자(에러율 · P95 · 히트맵)가 전부 그쪽이다.

echo "파이프라인 관통 정상: 에이전트 → 수집기 → Kafka raw → 적재 처리기 → ClickHouse"
