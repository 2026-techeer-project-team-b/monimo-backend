#!/usr/bin/env bash
# 격리 ingester: 격리 Kafka(39092) · 격리 CH(28123) · 로컬 PG(읽기 — 등록 안 된 서비스라 agents 에 쓰지 않음)
# 사용: ingester_exp.sh <로그파일> [debug]
# 포트는 환경변수로 바꾼다. 기본값은 공유 compose 의 업무 · 관리 포트(8082 · 18082 등, #150)와 겹치지 않게 골랐다
#   EXP_INGESTER_PORT (기본 28082) · EXP_INGESTER_MGMT_PORT (기본 38082) · EXP_JDWP_PORT (기본 5005) · STARTUP_TIMEOUT 초 (기본 90)
set -euo pipefail
S=${OUT:-/tmp/monimo-kafka-exp}; mkdir -p "$S"; HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
JAR=${JAR:-$ROOT/ingester/build/libs/ingester-0.0.1-SNAPSHOT.jar}
LOG=$1
[ -f "$JAR" ] || { echo "실패: jar 없음 $JAR (./gradlew :ingester:bootJar)" >&2; exit 1; }
DBG=""; [ "${2:-}" = debug ] && DBG="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:${EXP_JDWP_PORT:-5005}"
KAFKA_PORT=39092 CLICKHOUSE_HTTP_PORT=28123 nohup java $DBG -jar "$JAR" \
  --spring.profiles.active=local --server.port="${EXP_INGESTER_PORT:-28082}" --management.server.port="${EXP_INGESTER_MGMT_PORT:-38082}" \
  --logging.level.org.springframework.kafka.listener=debug > "$LOG" 2>&1 &
PID=$!; echo $PID > "$S/ingester-exp.pid"
deadline=$(( $(date +%s) + ${STARTUP_TIMEOUT:-90} ))
until grep -q "partitions assigned" "$LOG"; do
  if grep -q "APPLICATION FAILED" "$LOG" || ! kill -0 $PID 2>/dev/null; then
    echo "실패: ingester 기동 실패 (로그 $LOG)" >&2; tail -20 "$LOG" >&2; kill -9 $PID 2>/dev/null || true; exit 1; fi
  if [ "$(date +%s)" -ge $deadline ]; then
    echo "실패: ${STARTUP_TIMEOUT:-90}초 안에 파티션을 배정받지 못함 (로그 $LOG)" >&2; kill -9 $PID 2>/dev/null || true; exit 1; fi
  sleep 0.5
done
grep -m1 "partitions assigned" "$LOG"
