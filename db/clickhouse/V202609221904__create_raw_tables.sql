-- 원본 4표. 정본: 노션 ERD「CH 영역 · 원본 4표」.
-- spans · metrics_raw · logs 는 적재 처리기가, thread_dumps 는 API 서버가 넣는다.
--
-- TTL: ERD는 "로컬 디스크 N일 → S3 90일 → 삭제" 2단이다. 로컬에는 S3가 없어서 최종 삭제 시점만 둔다.
--      배포 단계에서 `TTL <시각> + INTERVAL N DAY TO VOLUME 's3', <시각> + INTERVAL N+90 DAY DELETE` 로 바꾼다.

-- 스팬 원본: 요청이 지나간 구간 하나가 한 줄. 콜트리가 읽고, 집계 4표가 여기서 나온다.
CREATE TABLE IF NOT EXISTS monimo.spans
(
    trace_id        String,
    span_id         String,
    parent_span_id  String,
    start_time      DateTime64(9) CODEC(Delta, ZSTD(1)),
    duration_ns     UInt64,
    service_name    LowCardinality(String),
    agent_id        LowCardinality(String),
    span_name       LowCardinality(String),
    span_kind       Enum8('INTERNAL' = 0, 'SERVER' = 1, 'CLIENT' = 2, 'PRODUCER' = 3, 'CONSUMER' = 4),
    status_code     Enum8('UNSET' = 0, 'OK' = 1, 'ERROR' = 2),
    http_status     UInt16 DEFAULT 0,
    peer_address    String DEFAULT '',
    peer_service    LowCardinality(String) DEFAULT '',
    attributes      Map(LowCardinality(String), String),
    events          Nested
    (
        ts          DateTime64(9),
        name        LowCardinality(String),
        attributes  Map(LowCardinality(String), String)
    ),
    INDEX idx_trace_id trace_id TYPE bloom_filter GRANULARITY 1,
    INDEX idx_duration duration_ns TYPE minmax GRANULARITY 1
)
ENGINE = MergeTree
PARTITION BY toDate(start_time)
ORDER BY (service_name, span_name, toDateTime(start_time))
TTL toDateTime(start_time) + INTERVAL 93 DAY DELETE;

-- 메트릭 원본(long 형식): 지표 하나가 한 줄. 15초마다 들어온다.
CREATE TABLE IF NOT EXISTS monimo.metrics_raw
(
    service_name  LowCardinality(String),
    agent_id      LowCardinality(String),
    metric_name   LowCardinality(String),
    series_hash   UInt64,
    attributes    Map(LowCardinality(String), String),
    ts            DateTime CODEC(DoubleDelta, ZSTD(1)),
    value         Float64 CODEC(Gorilla, ZSTD(1))
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(ts)
ORDER BY (service_name, agent_id, metric_name, series_hash, ts)
TTL ts + INTERVAL 105 DAY DELETE;

-- 로그 원본: 로그 한 줄이 한 줄. trace_id 로 콜트리와 이어진다.
CREATE TABLE IF NOT EXISTS monimo.logs
(
    trace_id      String DEFAULT '',
    span_id       String DEFAULT '',
    ts            DateTime64(3),
    service_name  LowCardinality(String),
    agent_id      LowCardinality(String),
    logger        LowCardinality(String),
    thread        String,
    level         LowCardinality(String),
    message       String CODEC(ZSTD(3)),
    attributes    Map(LowCardinality(String), String),
    INDEX idx_trace_id trace_id TYPE bloom_filter GRANULARITY 1
)
ENGINE = MergeTree
PARTITION BY toDate(ts)
ORDER BY (service_name, toDateTime(ts))
TTL toDateTime(ts) + INTERVAL 97 DAY DELETE;

-- 스레드 덤프 결과: 덤프 한 번이 한 줄. 요청 기록도 이 표가 유일하다.
CREATE TABLE IF NOT EXISTS monimo.thread_dumps
(
    agent_id      LowCardinality(String),
    service_name  LowCardinality(String),
    dump_uuid     String,
    requested_by  String,
    requested_at  DateTime64(3),
    thread_count  UInt16,
    dump          String CODEC(ZSTD(3))
)
ENGINE = MergeTree
PARTITION BY toDate(requested_at)
ORDER BY (agent_id, requested_at)
TTL toDateTime(requested_at) + INTERVAL 93 DAY DELETE;
