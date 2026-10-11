#!/usr/bin/env bash
# 격리 ingester: 격리 Kafka(39092) · 격리 CH(28123) · 로컬 PG(읽기 — 등록 안 된 서비스라 agents 에 쓰지 않음)
# 사용: ingester_exp.sh <로그파일> [debug]
S=${OUT:-/tmp/monimo-kafka-exp}; mkdir -p "$S"; HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
DBG=""; [ "${2:-}" = debug ] && DBG="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"
KAFKA_PORT=39092 CLICKHOUSE_HTTP_PORT=28123 nohup java $DBG -jar $ROOT/ingester/build/libs/ingester-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local --server.port=18082 --management.server.port=18092 \
  --logging.level.org.springframework.kafka.listener=debug > $1 2>&1 &
echo $! > $S/ingester-exp.pid
until grep -qE "partitions assigned|APPLICATION FAILED" $1; do sleep 0.5; done; grep -E "partitions assigned|APPLICATION FAILED" $1 | head -2
