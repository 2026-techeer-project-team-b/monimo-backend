#!/usr/bin/env bash
# notifier JVM 을 jar 로 직접 띄운다 (kill -9 이 Gradle 이 아니라 JVM 에 닿게). 기동을 기다리고 pid 를 남긴다
# 사용: ./gradlew :notifier:bootJar 한 뒤 docs/alert/experiments/notifier.sh <로그이름>
set -euo pipefail
OUT=${OUT:-/tmp/monimo-exp}; mkdir -p "$OUT"
LOG=$OUT/notifier-$1.log
nohup java -jar notifier/build/libs/notifier-0.0.1-SNAPSHOT.jar --spring.profiles.active=local > "$LOG" 2>&1 &
echo $! > "$OUT/notifier.pid"
until grep -qE "Started .* in|APPLICATION FAILED" "$LOG"; do sleep 0.5; done
echo "pid=$(cat "$OUT/notifier.pid")"
