#!/usr/bin/env bash
# 격리 환경: 실험 전용 Kafka(39092, 파티션 1) · ClickHouse(28123, 공유 compose 와 같은 이미지 = 같은 기본 설정).
# 공유 compose 의 kafka · clickhouse · ingester 그룹은 건드리지 않는다. 지울 때: docker rm -f exp-kafka exp-ch
# 준비물: ./gradlew :ingester:bootJar · pip install kafka-python opentelemetry-proto (PY 환경변수로 그 python 지정)
set -eu
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
docker rm -f exp-kafka exp-ch >/dev/null 2>&1 || true
docker run -d --name exp-kafka -p 39092:39092 -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:39092,CONTROLLER://:39093 -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:39092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:39093 -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=false apache/kafka:3.9.2 >/dev/null
docker run -d --name exp-ch -p 28123:8123 -e CLICKHOUSE_USER=monimo -e CLICKHOUSE_PASSWORD=monimo \
  -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 --ulimit nofile=262144:262144 clickhouse/clickhouse-server:26.8.10.6 >/dev/null
sleep 15
for t in raw raw.dlq; do docker exec exp-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:39092 --create --topic $t --partitions 1 --replication-factor 1; done
for f in $(ls "$ROOT"/db/clickhouse/V*.sql | sort); do docker exec -i exp-ch clickhouse-client --user monimo --password monimo --multiquery < "$f"; done
