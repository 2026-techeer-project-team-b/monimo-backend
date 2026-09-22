-- 집계 7표. 정본: 노션 ERD「CH 영역 · 집계 7표」.
-- 사람이 INSERT 하지 않는다. 004 의 MV가 원본에 줄이 들어오는 순간 자동으로 채운다.
-- AggregateFunction 컬럼은 결과가 아니라 "계산 중간 상태"다. 읽을 때 countMerge · sumMerge · quantilesTDigestMerge 등으로 펼친다.
-- TTL: 002 머리말과 같은 이유로 최종 삭제 시점만 둔다.

-- 트랜잭션(루트 스팬): 스캐터 차트의 점 하나 = 한 줄.
CREATE TABLE IF NOT EXISTS monimo.transactions
(
    trace_id      String,
    start_time    DateTime64(3),
    duration_ms   UInt32,
    service_name  LowCardinality(String),
    agent_id      LowCardinality(String),
    span_name     LowCardinality(String),
    is_error      UInt8,
    http_status   UInt16
)
ENGINE = MergeTree
PARTITION BY toDate(start_time)
ORDER BY (service_name, start_time)
TTL toDateTime(start_time) + INTERVAL 93 DAY DELETE;

-- 히트맵 1분: 1분 × 50ms 칸마다 요청 수. 좌표 4개가 같은 줄은 엔진이 cnt 를 더해 합친다.
CREATE TABLE IF NOT EXISTS monimo.heatmap_1m
(
    ts_min          DateTime,
    service_name    LowCardinality(String),
    latency_bucket  UInt16,
    is_error        UInt8,
    cnt             UInt64
)
ENGINE = SummingMergeTree
PARTITION BY toYYYYMM(ts_min)
ORDER BY (service_name, ts_min, latency_bucket, is_error)
TTL ts_min + INTERVAL 120 DAY DELETE;

-- URL 통계 1분: URL(span_name) · 파드별 호출 수 · 에러 수 · 지연 백분위.
CREATE TABLE IF NOT EXISTS monimo.url_stats_1m
(
    ts_min        DateTime,
    service_name  LowCardinality(String),
    span_name     LowCardinality(String),
    agent_id      LowCardinality(String),
    cnt           AggregateFunction(count),
    err_cnt       AggregateFunction(sum, UInt8),
    dur_q         AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), UInt64)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(ts_min)
ORDER BY (service_name, ts_min, span_name, agent_id)
TTL ts_min + INTERVAL 120 DAY DELETE;

-- 서버맵 간선 1분: 화살표 하나 = 한 줄. 평균 대신 합(sum_duration_ns)을 두고 볼 때 cnt 로 나눈다.
CREATE TABLE IF NOT EXISTS monimo.server_map_1m
(
    ts_min           DateTime,
    caller_service   LowCardinality(String),
    callee_service   LowCardinality(String),
    callee_kind      Enum8('SERVICE' = 0, 'DB' = 1, 'EXTERNAL' = 2),
    cnt              UInt64,
    err_cnt          UInt64,
    sum_duration_ns  UInt64
)
ENGINE = SummingMergeTree
PARTITION BY toYYYYMM(ts_min)
ORDER BY (ts_min, caller_service, callee_service, callee_kind)
TTL ts_min + INTERVAL 120 DAY DELETE;

-- 서비스 건강 1분: 탐지가 API 서버를 통해 읽는 계기판. 5XX_RATE · 4XX_RATE · P95_LATENCY 규칙의 재료 (ADR #40).
CREATE TABLE IF NOT EXISTS monimo.service_health_1m
(
    ts_min        DateTime,
    service_name  LowCardinality(String),
    cnt           AggregateFunction(count),
    err_cnt       AggregateFunction(sum, UInt8),
    cnt_4xx       AggregateFunction(sum, UInt8),
    cnt_5xx       AggregateFunction(sum, UInt8),
    dur_q         AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), UInt64)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(ts_min)
ORDER BY (service_name, ts_min)
TTL ts_min + INTERVAL 120 DAY DELETE;

-- 메트릭 1분 롤업: 15초 원본 4줄을 1분 1줄로. 인스펙터(15일 이상)와 CPU · HEAP · GC_TIME 규칙이 읽는다.
CREATE TABLE IF NOT EXISTS monimo.metrics_1m
(
    service_name  LowCardinality(String),
    agent_id      LowCardinality(String),
    metric_name   LowCardinality(String),
    series_hash   UInt64,
    ts_min        DateTime,
    avg_v         AggregateFunction(avg, Float64),
    min_v         AggregateFunction(min, Float64),
    max_v         AggregateFunction(max, Float64),
    last_v        AggregateFunction(argMax, Float64, DateTime)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(ts_min)
ORDER BY (service_name, agent_id, metric_name, series_hash, ts_min)
TTL ts_min + INTERVAL 180 DAY DELETE;

-- 메트릭 1시간 롤업: 1분 롤업을 다시 1시간으로 (이어달리기). 1년 추세 그래프가 읽는다.
CREATE TABLE IF NOT EXISTS monimo.metrics_1h
(
    service_name  LowCardinality(String),
    agent_id      LowCardinality(String),
    metric_name   LowCardinality(String),
    series_hash   UInt64,
    ts_hour       DateTime,
    avg_v         AggregateFunction(avg, Float64),
    min_v         AggregateFunction(min, Float64),
    max_v         AggregateFunction(max, Float64),
    last_v        AggregateFunction(argMax, Float64, DateTime)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(ts_hour)
ORDER BY (service_name, agent_id, metric_name, series_hash, ts_hour)
TTL ts_hour + INTERVAL 1 YEAR + INTERVAL 90 DAY DELETE;
