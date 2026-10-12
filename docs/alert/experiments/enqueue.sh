#!/usr/bin/env bash
# 탐지가 넣었을 사건 + 발송 작업(FIRING)을 n 개 넣는다. 같은 채널(2) · 같은 서비스(shop-order) → 한 그룹
# 사용: enqueue.sh <tag> <n>   출력: 넣은 outbox id 들
set -euo pipefail
TAG=$1; N=$2
for i in $(seq 1 "$N"); do
docker exec -i monimo-dev-postgres-1 psql -U monimo -d monimo -tAq <<SQL
WITH e AS (
  INSERT INTO alert_events (alert_event_uuid, alert_rule_id, fingerprint, state, observed_value, fired_at,
      rule_version, metric_kind, operator, threshold, window_sec, severity)
  VALUES (gen_random_uuid(), 2, md5(random()::text) || md5(random()::text), 'FIRING', 9, date_trunc('minute', now()),
      1, '5XX_RATE', 'GT', 5, 60, 'CRITICAL')
  RETURNING id
)
INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
SELECT id, 'FIRING', 2,
  jsonb_build_object('transition','FIRING','rule_name','$TAG-$i','service_name','shop-order','severity','CRITICAL',
    'metric_kind','5XX_RATE','observed_value',9,'operator','GT','threshold',5,'fired_at', to_char(date_trunc('minute', now()) at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"')),
  'PENDING', now(), now(), now()
FROM e RETURNING id;
SQL
done
