#!/usr/bin/env bash
# 느린 채널이 다른 채널 발송을 늦추나. 느린 채널(3, :18997)에 서로 다른 서비스 5건(묶이지 않게) → 5초 뒤 정상 채널(2, :18999)에 1건.
# 시나리오: base(느린 채널도 즉시 응답) / slow(느린 채널이 4초 뒤 응답 — 요청 한도 5초 안이라 실패는 아님)
set -u
OUT=${OUT:-/tmp/monimo-exp}; mkdir -p "$OUT"; S=$OUT; HERE=$(cd "$(dirname "$0")" && pwd)
JAR=notifier/build/libs/notifier-0.0.1-SNAPSHOT.jar
q(){ docker exec monimo-dev-postgres-1 psql -U monimo -d monimo -tAc "$1"; }
ts(){ python3 -c 'import datetime;print(datetime.datetime.now(datetime.timezone.utc).isoformat())'; }
enq(){ # channel service tag
q "WITH e AS (INSERT INTO alert_events (alert_event_uuid, alert_rule_id, fingerprint, state, observed_value, fired_at, resolved_at, rule_version, metric_kind, operator, threshold, window_sec, severity)
 VALUES (gen_random_uuid(), 2, md5(random()::text)||md5(random()::text), 'RESOLVED', 9, date_trunc('minute', now()), now(), 1, '5XX_RATE', 'GT', 5, 60, 'CRITICAL') RETURNING id)
 INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
 SELECT id, 'FIRING', $1, jsonb_build_object('transition','FIRING','rule_name','$3','service_name','$2','severity','CRITICAL','metric_kind','5XX_RATE','observed_value',9,'operator','GT','threshold',5,'fired_at','x'), 'PENDING', now(), now(), now() FROM e RETURNING id" | head -1 ; }

pkill -f fake_slack_hold.py; sleep 1
rm -f $S/hold-slow $S/slow.log $S/normal.log
nohup python3 $HERE/fake_slack_hold.py 18997 $S/slow.log $S/hold-slow >/dev/null 2>&1 &
nohup python3 $HERE/fake_slack_hold.py 18999 $S/normal.log $S/hold-none >/dev/null 2>&1 &
kill -9 $(cat $S/notifier.pid 2>/dev/null) 2>/dev/null; sleep 1
nohup java -jar $JAR --spring.profiles.active=local > $S/notifier-slow.log 2>&1 & echo $! > $S/notifier.pid
until grep -qE "Started .* in|APPLICATION FAILED" $S/notifier-slow.log; do sleep 0.5; done

run(){ MODE=$1; HOLD=$2
  echo $HOLD > $S/hold-slow; : > $S/slow.log; : > $S/normal.log
  for i in 1 2 3 4 5; do enq 3 "svc-$i" "$MODE-slow-$i" >/dev/null; done
  sleep 5
  NID=$(enq 2 normal-svc "$MODE-normal"); TN=$(q "select extract(epoch from created_at) from notification_outbox where id=$NID")
  echo "== $MODE (느린 채널 응답 ${HOLD}초) 정상 작업 id=$NID"
  # 1초마다 대기열 (PENDING · IN_FLIGHT) 과 정상 작업 상태
  for t in $(seq 1 60); do
    st=$(q "select status from notification_outbox where id=$NID")
    qd=$(q "select count(*) filter (where status='PENDING') || '/' || count(*) filter (where status='IN_FLIGHT') from notification_outbox where status in ('PENDING','IN_FLIGHT')")
    echo "t+${t}s 대기열 PENDING/IN_FLIGHT=$qd 정상작업=$st"
    [ "$st" = "SENT" ] && [ "$(q "select count(*) from notification_outbox where status in ('PENDING','IN_FLIGHT')")" = "0" ] && break
    sleep 1
  done
  ARR=$(python3 -c "import datetime,sys;l=open('$S/normal.log').readline().split('\t')[0];print(datetime.datetime.fromisoformat(l).timestamp())")
  python3 -c "print('정상 채널: 생성 → 도착 %.2f초' % ($ARR - $TN))"
  echo "느린 채널 수신: $(wc -l < $S/slow.log)건 · 첫 $(head -1 $S/slow.log | cut -f1) 끝 $(tail -1 $S/slow.log | cut -f1)"
}
run base 0
run slow 4
