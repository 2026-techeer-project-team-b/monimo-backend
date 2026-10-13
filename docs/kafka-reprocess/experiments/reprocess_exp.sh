#!/usr/bin/env bash
# Kafka 재처리 기준선: 정상 90 커밋 → 오류 10 이 CH 에 들어간 뒤 · 커밋 전에 kill -9 → 같은 그룹으로 재시작.
# 준비: setup_isolated.sh. 결과는 $OUT/result-<RUN>.txt · 실행 환경은 $OUT/env-<RUN>.txt
# 기대값과 다르면 실패(종료 코드 1)한다. 재시작 뒤 기대값은 환경변수로 바꿀 수 있다 (2단계에서 중복 제거를 켠 비교용):
#   EXPECT_AFTER_ROWS (기본 110) · EXPECT_AFTER_ERR (기본 20)  — 고유 스팬은 언제나 100 이어야 한다
#
# 도구 v3 (#187, 수집 파트): 스팬에서 갈라져 나오는 집계 표를 전부 본다.
#   v2 는 service_health_1m 하나만 봤다. 중복 제거 기억은 표마다 따로 있어야 하고(2단계 계획 §2 의 B vs G),
#   transactions -> heatmap_1m 은 **다리가 둘**이라 하나만 보면 연쇄 2단을 못 본다.
#   더 본 것: transactions · heatmap_1m · url_stats_1m · server_map_1m.
#   기대값은 새 환경변수 없이 원본 줄 수(er)와 오류 줄 수(ee)에서 바로 나온다 (스팬이 전부 SERVER 라서다):
#     transactions count = er · heatmap_1m sum(cnt) = er · url_stats_1m countMerge = er · sumMerge(err) = ee ·
#     server_map_1m count = 0 (CLIENT 전용 MV 라 SERVER 스팬은 안 들어온다)
#   v1 · v2 원자료는 그대로 보존한다. 이 판으로 돈 결과는 result-*-tool-v3.txt 로 남긴다.
set -euo pipefail
S=${OUT:-/tmp/monimo-kafka-exp}; mkdir -p "$S"; HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
PY=${PY:-python3}; WAIT=${WAIT_TIMEOUT:-90}
EXPECT_AFTER_ROWS=${EXPECT_AFTER_ROWS:-110}; EXPECT_AFTER_ERR=${EXPECT_AFTER_ERR:-20}
RUN=$(date +%H%M%S); SVC=exp-reprocess-$RUN; FAILS=0
ts(){ "$PY" -c 'import datetime;print(datetime.datetime.now(datetime.UTC).strftime("%H:%M:%S.%f")[:-3])'; }
die(){ echo "실패: $*" >&2; exit 1; }

JDB_PID=""
cleanup(){ [ -n "$JDB_PID" ] && kill "$JDB_PID" 2>/dev/null; kill -9 "$(cat "$S/ingester-exp.pid" 2>/dev/null)" 2>/dev/null; exec 3>&- 2>/dev/null; true; }
trap cleanup EXIT

CH(){ docker exec exp-ch clickhouse-client --user monimo --password monimo -q "$1" || die "ClickHouse 조회 실패: $1"; }
# 오프셋 조회. 실패를 숨기지 않는다: 명령이 실패하거나 raw-0 줄이 없으면 멈춘다
committed(){ local out; out=$(docker exec exp-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:39092 --describe --group ingester 2>&1) \
    || die "kafka-consumer-groups 실패: $out"
  local v; v=$(echo "$out" | awk '$2=="raw" && $3=="0"{print $4}')
  [ -n "$v" ] || die "그룹 ingester 의 raw-0 줄이 없음: $out"
  [ "$v" = "-" ] && echo 0 || echo "$v"; }
end_offset(){ local out; out=$(docker exec exp-kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:39092 --topic raw --partitions 0 --time -1 2>&1) \
    || die "kafka-get-offsets 실패: $out"
  local v; v=$(echo "$out" | awk -F: '$1=="raw" && $2=="0"{print $3}'); [ -n "$v" ] || die "raw:0 끝 오프셋 없음: $out"; echo "$v"; }
wait_for(){ local desc=$1; shift; local deadline=$(( $(date +%s) + WAIT ))
  until "$@"; do [ "$(date +%s)" -ge $deadline ] && die "${WAIT}초 안에 '$desc' 안 됨"; sleep 0.3; done; }
rows(){ CH "SELECT count() FROM monimo.spans WHERE service_name='$SVC'"; }
# 스팬에서 갈라져 나오는 집계 표 전부 (#187 v3). heatmap_1m 은 transactions 를 거친 **연쇄 2단**이다
aggs(){ CH "SELECT
    (SELECT count() FROM monimo.transactions WHERE service_name='$SVC'),
    (SELECT sum(cnt) FROM monimo.heatmap_1m WHERE service_name='$SVC'),
    (SELECT countMerge(cnt) FROM monimo.url_stats_1m WHERE service_name='$SVC'),
    (SELECT sumMerge(err_cnt) FROM monimo.url_stats_1m WHERE service_name='$SVC'),
    (SELECT count() FROM monimo.server_map_1m WHERE caller_service='$SVC')
    FORMAT TSV"; }
committed_is(){ [ "$(committed)" = "$1" ]; }
rows_is(){ [ "$(rows)" = "$1" ]; }

# 기대값 검사: 원본 줄 · 고유 스팬 · 오류 줄 · MV 집계(cnt · err_cnt) · 커밋 · 끝 오프셋
check(){ local label=$1 er=$2 eu=$3 ee=$4 ec=$5 eerr=$6 ecommit=$7 eend=$8
  local got; got=$(CH "SELECT count(), uniqExact(trace_id, span_id), countIf(status_code='ERROR') FROM monimo.spans WHERE service_name='$SVC' FORMAT TSV")
  local agg; agg=$(CH "SELECT countMerge(cnt), sumMerge(err_cnt) FROM monimo.service_health_1m WHERE service_name='$SVC' FORMAT TSV")
  local c e; c=$(committed); e=$(end_offset)
  local pct; pct=$(CH "SELECT round(100*sumMerge(err_cnt)/countMerge(cnt),2) FROM monimo.service_health_1m WHERE service_name='$SVC'")
  echo "-- [$label] $(ts)"
  echo "kafka: committed=$c end=$e lag=$((e-c))"
  printf 'spans: rows=%s unique=%s err_rows=%s\n' $got
  printf 'service_health_1m: cnt=%s err_cnt=%s' $agg; echo " err_pct=$pct"
  local five; five=$(aggs)
  printf 'transactions=%s heatmap_1m=%s url_stats_1m=%s url_err=%s server_map_1m=%s\n' $five
  local want="$er	$eu	$ee"; local wantagg="$ec	$eerr"
  # 스팬이 전부 SERVER 라 네 표는 원본 줄 수와 같고, server_map_1m 은 CLIENT 전용이라 0 이다
  local wantfive="$er	$er	$er	$ee	0"
  if [ "$got" != "$want" ] || [ "$agg" != "$wantagg" ] || [ "$five" != "$wantfive" ] || [ "$c" != "$ecommit" ] || [ "$e" != "$eend" ]; then
    echo "!! 기대와 다름: spans=($want) agg=($wantagg) five=($wantfive) committed=$ecommit end=$eend"; FAILS=$((FAILS+1)); fi; }

# ---- 실행 환경 기록 (이 실행에서 실제로 쓴 값) ----
JAR=${JAR:-$ROOT/ingester/build/libs/ingester-0.0.1-SNAPSHOT.jar}
{ echo "run=$RUN service=$SVC"
  echo "code: $(git -C "$ROOT" rev-parse HEAD) $( [ -z "$(git -C "$ROOT" status --porcelain -- ingester common)" ] && echo '(ingester · common 변경 없음)' || echo '(ingester · common 에 커밋 안 된 변경 있음!)')"
  echo "jar: $JAR sha256=$(shasum -a 256 "$JAR" | cut -d' ' -f1) mtime=$(stat -f '%Sm' -t '%Y-%m-%dT%H:%M:%S%z' "$JAR")"
  echo "jdk: $(java -version 2>&1 | head -1)"
  echo "jar libs: $(unzip -l "$JAR" | grep -oE '(spring-kafka|kafka-clients|client-v2|spring-boot)-[0-9][^/]*\.jar' | sort -u | tr '\n' ' ')"
  echo "kafka image: $(docker inspect -f '{{.Config.Image}} started={{.State.StartedAt}}' exp-kafka)"
  echo "ch image: $(docker inspect -f '{{.Config.Image}} started={{.State.StartedAt}}' exp-ch) version=$(CH 'SELECT version()')"
  echo "ch settings (system.settings, 기본 사용자):"
  CH "SELECT name, value, changed FROM system.settings WHERE name IN ('deduplicate_insert','insert_deduplicate','async_insert','async_insert_deduplicate','wait_for_async_insert','insert_deduplication_token','deduplicate_blocks_in_dependent_materialized_views','update_insert_deduplication_token_in_dependent_materialized_views','compatibility') ORDER BY name FORMAT TSV" | sed 's/^/  /'
  echo "ch merge_tree_settings:"; CH "SELECT name, value, changed FROM system.merge_tree_settings WHERE name IN ('non_replicated_deduplication_window','replicated_deduplication_window') FORMAT TSV" | sed 's/^/  /'
  echo "tables:"; CH "SELECT name, engine_full FROM system.tables WHERE database='monimo' AND name IN ('spans','service_health_1m') FORMAT TSV" | sed 's/^/  /'
} > "$S/env-$RUN.txt"
cat "$S/env-$RUN.txt"

pkill -9 -f "ingester-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --server.port=${EXP_INGESTER_PORT:-28082}" 2>/dev/null || true
M=$(( $(date +%s)/60*60 - 600 )); echo "bucket=$(date -u -r $M +%H:%M)"
"$HERE/ingester_exp.sh" "$S/ing-$RUN-1.log" debug
E0=$(end_offset); C0=$(committed); [ "$E0" = "$C0" ] || die "시작 전 lag 가 0 이 아님 (committed=$C0 end=$E0)"

"$PY" "$HERE/produce_spans.py" ok 90 $M $RUN
wait_for "정상 레코드 커밋 (committed=$((E0+1)))" committed_is $((E0+1))
check "정상 90 커밋 뒤" 90 90 0 90 0 $((E0+1)) $((E0+1))

# 커밋 직전 중단점
rm -f "$S/jdb.in"; mkfifo "$S/jdb.in"
jdb -attach "127.0.0.1:${EXP_JDWP_PORT:-5005}" < "$S/jdb.in" > "$S/jdb-$RUN.log" 2>&1 & JDB_PID=$!
exec 3>"$S/jdb.in"
echo "stop in org.apache.kafka.clients.consumer.KafkaConsumer.commitSync(java.util.Map, java.time.Duration)" >&3
echo "stop in org.apache.kafka.clients.consumer.KafkaConsumer.commitSync(java.util.Map)" >&3
bp_set(){ [ "$(grep -c 'Set breakpoint' "$S/jdb-$RUN.log" 2>/dev/null)" -ge 2 ]; }
wait_for "jdb 중단점 2개 설정" bp_set
"$PY" "$HERE/produce_spans.py" error 10 $M $RUN
bp_hit(){ grep -q "Breakpoint hit" "$S/jdb-$RUN.log"; }
wait_for "커밋 중단점 도달" bp_hit
echo "where" >&3; sleep 1
echo "== 중단점: $(grep -m1 'Breakpoint hit' "$S/jdb-$RUN.log" | cut -c1-160)"
check "오류 10 CH 저장 · 커밋 전 (멈춘 상태)" 100 100 10 100 10 $((E0+1)) $((E0+2))
kill -9 "$(cat "$S/ingester-exp.pid")"; echo "== kill -9 $(ts)"; exec 3>&-; kill "$JDB_PID" 2>/dev/null || true; JDB_PID=""; sleep 1
check "kill 직후" 100 100 10 100 10 $((E0+1)) $((E0+2))

"$HERE/ingester_exp.sh" "$S/ing-$RUN-2.log"
wait_for "재시작 뒤 커밋 (committed=$((E0+2)))" committed_is $((E0+2))
wait_for "재시작 뒤 원본 줄 수 $EXPECT_AFTER_ROWS" rows_is "$EXPECT_AFTER_ROWS"
check "같은 그룹으로 재시작 뒤" "$EXPECT_AFTER_ROWS" 100 "$EXPECT_AFTER_ERR" "$EXPECT_AFTER_ROWS" "$EXPECT_AFTER_ERR" $((E0+2)) $((E0+2))
grep -E "raw 소비|Committing" "$S/ing-$RUN-2.log" | cut -c1-200 | head -5
echo "-- 중복 스팬 (같은 trace_id·span_id 가 2줄 이상)"
CH "SELECT status_code, count() AS spans, sum(c) AS rows FROM (SELECT trace_id, span_id, any(status_code) status_code, count() c FROM monimo.spans WHERE service_name='$SVC' GROUP BY trace_id, span_id HAVING c > 1) GROUP BY status_code FORMAT PrettyCompactMonoBlock"
echo "-- 스택 (중단점에서)"; sed -n '/\[1\] org.apache.kafka/,/\[12\]/p' "$S/jdb-$RUN.log" | cut -c1-170
echo "-- 브로커: 옛 소비자 제거 · 그룹 안정화"
docker logs --since 5m exp-kafka 2>&1 | grep -E "group ingester" | grep -E "has failed|Stabilized|Preparing to rebalance" | tail -3 | cut -c1-170
[ $FAILS -eq 0 ] && echo "== 결과: 기대와 같음" || die "기대와 다른 단계 $FAILS 개"
