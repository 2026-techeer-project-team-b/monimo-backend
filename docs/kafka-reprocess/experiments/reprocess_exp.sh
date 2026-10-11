#!/usr/bin/env bash
# Kafka 재처리 기준선: 정상 90 커밋 → 오류 10 이 CH 에 들어간 뒤 · 커밋 전에 kill -9 → 같은 그룹으로 재시작
set -u
S=${OUT:-/tmp/monimo-kafka-exp}; mkdir -p "$S"; HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
RUN=$(date +%H%M%S); SVC=exp-reprocess-$RUN
CH(){ docker exec exp-ch clickhouse-client --user monimo --password monimo -q "$1"; }
GRP(){ docker exec exp-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:39092 --describe --group ingester 2>/dev/null | awk '$2=="raw"{print "committed="$4" end="$5" lag="$6}'; }
snap(){ echo "-- [$1] $(python3 -c "import datetime;print(datetime.datetime.now(datetime.UTC).strftime(\"%H:%M:%S.%f\")[:-3])")"; echo "kafka: $(GRP)"
  CH "SELECT count() AS raw_rows, uniqExact(trace_id, span_id) AS unique_spans, countIf(status_code='ERROR') AS err_rows FROM monimo.spans WHERE service_name='$SVC' FORMAT PrettyCompactMonoBlock"
  # ServiceHealthRepository 와 같은 식 (step 60)
  CH "SELECT service_name, countMerge(cnt) AS cnt, sumMerge(err_cnt) AS err_cnt, round(100*err_cnt/cnt,2) AS err_pct FROM monimo.service_health_1m WHERE service_name='$SVC' GROUP BY service_name FORMAT PrettyCompactMonoBlock"; }

kill -9 $(cat $S/ingester-exp.pid) 2>/dev/null; sleep 1
M=$(( $(date +%s)/60*60 - 600 )); echo "run=$RUN service=$SVC bucket=$(date -u -r $M +%H:%M)"
$HERE/ingester_exp.sh $S/ing-$RUN-1.log debug
${PY:-python3} $HERE/produce_spans.py ok 90 $M $RUN
until [ "$(CH "select count() from monimo.spans where service_name='$SVC'")" = 90 ]; do sleep 0.3; done; sleep 2
snap "정상 90 커밋 뒤"

# 커밋 직전 중단점
rm -f $S/jdb.in; mkfifo $S/jdb.in
( jdb -attach 127.0.0.1:5005 < $S/jdb.in > $S/jdb-$RUN.log 2>&1 ) & exec 3>$S/jdb.in
echo "stop in org.apache.kafka.clients.consumer.KafkaConsumer.commitSync(java.util.Map, java.time.Duration)" >&3; echo "stop in org.apache.kafka.clients.consumer.KafkaConsumer.commitSync(java.util.Map)" >&3; sleep 2
${PY:-python3} $HERE/produce_spans.py error 10 $M $RUN
for i in $(seq 1 100); do grep -q "Breakpoint hit" $S/jdb-$RUN.log && break; sleep 0.2; done; grep -q "Breakpoint hit" $S/jdb-$RUN.log || { echo "중단점 안 걸림 — 중단"; cat $S/jdb-$RUN.log; kill -9 $(cat $S/ingester-exp.pid); exit 1; }
echo "where" >&3; sleep 1
echo "== 중단점: $(grep -m1 'Breakpoint hit' $S/jdb-$RUN.log | cut -c1-160)"
snap "오류 10 CH 저장 · 커밋 전 (멈춘 상태)"
kill -9 $(cat $S/ingester-exp.pid); echo "== kill -9 $(python3 -c "import datetime;print(datetime.datetime.now(datetime.UTC).strftime(\"%H:%M:%S.%f\")[:-3])")"; exec 3>&-; sleep 1
snap "kill 직후"

$HERE/ingester_exp.sh $S/ing-$RUN-2.log
for i in $(seq 1 60); do [ "$(GRP | grep -o 'lag=[0-9]*')" = "lag=0" ] && break; sleep 0.5; done; sleep 3
snap "같은 그룹으로 재시작 뒤"
grep -E "raw 소비|Committing" $S/ing-$RUN-2.log | cut -c1-200 | head -5
echo "-- 중복 스팬 (같은 trace_id·span_id 가 2줄 이상)"
CH "SELECT status_code, count() AS spans, sum(c) AS rows FROM (SELECT trace_id, span_id, any(status_code) status_code, count() c FROM monimo.spans WHERE service_name='$SVC' GROUP BY trace_id, span_id HAVING c > 1) GROUP BY status_code FORMAT PrettyCompactMonoBlock"
echo "-- 스택 (중단점에서)"; sed -n '/\[1\] org.apache.kafka/,/\[20\]/p' $S/jdb-$RUN.log | cut -c1-170
