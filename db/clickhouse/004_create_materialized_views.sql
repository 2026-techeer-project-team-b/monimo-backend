-- MV 7개: 원본에 INSERT 가 들어오는 순간 집계 표를 자동으로 채운다. 정본: 노션 ERD「MV 흐름」.
--
--   spans ──mv_transactions──▶ transactions ──mv_heatmap_1m──▶ heatmap_1m
--   spans ──mv_url_stats_1m──▶ url_stats_1m          (SERVER 스팬만)
--   spans ──mv_server_map_1m──▶ server_map_1m        (CLIENT 스팬만)
--   spans ──mv_service_health_1m──▶ service_health_1m (SERVER 스팬만)
--   metrics_raw ──mv_metrics_1m──▶ metrics_1m ──mv_metrics_1h──▶ metrics_1h
--
-- MV는 만든 뒤에 들어온 줄만 계산한다. 과거 데이터는 채워 주지 않는다.

-- 부모가 없는 스팬 = 요청의 첫 구간
CREATE MATERIALIZED VIEW IF NOT EXISTS monimo.mv_transactions TO monimo.transactions AS
SELECT
    trace_id,
    toDateTime64(start_time, 3)            AS start_time,
    toUInt32(intDiv(duration_ns, 1000000)) AS duration_ms,
    service_name,
    agent_id,
    span_name,
    toUInt8(status_code = 'ERROR')         AS is_error,
    http_status
FROM monimo.spans
WHERE parent_span_id = '';

-- 1분 × 50ms 칸으로 세기
CREATE MATERIALIZED VIEW IF NOT EXISTS monimo.mv_heatmap_1m TO monimo.heatmap_1m AS
SELECT
    toStartOfMinute(start_time)                         AS ts_min,
    service_name,
    toUInt16(least(intDiv(duration_ms, 50), 65535))    AS latency_bucket,
    is_error,
    count()                                             AS cnt
FROM monimo.transactions
GROUP BY ts_min, service_name, latency_bucket, is_error;

-- 요청을 받은 쪽(SERVER)만 센다
CREATE MATERIALIZED VIEW IF NOT EXISTS monimo.mv_url_stats_1m TO monimo.url_stats_1m AS
SELECT
    toStartOfMinute(start_time)                                   AS ts_min,
    service_name,
    span_name,
    agent_id,
    countState()                                                  AS cnt,
    sumState(toUInt8(status_code = 'ERROR'))                      AS err_cnt,
    quantilesTDigestState(0.5, 0.95, 0.99)(duration_ns)           AS dur_q
FROM monimo.spans
WHERE span_kind = 'SERVER'
GROUP BY ts_min, service_name, span_name, agent_id;

-- 부른 쪽(CLIENT)만 센다. 상대가 우리 서비스면 peer_service, 아니면 peer_address.
-- DB와 외부 API는 OTel 규칙대로 db.system 꼬리표 유무로 가른다.
CREATE MATERIALIZED VIEW IF NOT EXISTS monimo.mv_server_map_1m TO monimo.server_map_1m AS
SELECT
    toStartOfMinute(start_time)                                        AS ts_min,
    service_name                                                       AS caller_service,
    if(peer_service != '', peer_service, peer_address)                 AS callee_service,
    CAST(multiIf(peer_service != '', 'SERVICE',
                 attributes['db.system'] != '', 'DB',
                 'EXTERNAL'), 'Enum8(\'SERVICE\' = 0, \'DB\' = 1, \'EXTERNAL\' = 2)') AS callee_kind,
    count()                                                            AS cnt,
    countIf(status_code = 'ERROR')                                     AS err_cnt,
    sum(duration_ns)                                                   AS sum_duration_ns
FROM monimo.spans
WHERE span_kind = 'CLIENT'
GROUP BY ts_min, caller_service, callee_service, callee_kind;

-- 서비스 단위 건강 지표. 4xx · 5xx 는 http_status 대역으로 따로 센다 (ADR #40)
CREATE MATERIALIZED VIEW IF NOT EXISTS monimo.mv_service_health_1m TO monimo.service_health_1m AS
SELECT
    toStartOfMinute(start_time)                              AS ts_min,
    service_name,
    countState()                                             AS cnt,
    sumState(toUInt8(status_code = 'ERROR'))                 AS err_cnt,
    sumState(toUInt8(http_status BETWEEN 400 AND 499))       AS cnt_4xx,
    sumState(toUInt8(http_status BETWEEN 500 AND 599))       AS cnt_5xx,
    quantilesTDigestState(0.5, 0.95, 0.99)(duration_ns)      AS dur_q
FROM monimo.spans
WHERE span_kind = 'SERVER'
GROUP BY ts_min, service_name;

-- 15초 원본 → 1분
CREATE MATERIALIZED VIEW IF NOT EXISTS monimo.mv_metrics_1m TO monimo.metrics_1m AS
SELECT
    service_name,
    agent_id,
    metric_name,
    series_hash,
    toStartOfMinute(ts)    AS ts_min,
    avgState(value)        AS avg_v,
    minState(value)        AS min_v,
    maxState(value)        AS max_v,
    argMaxState(value, ts) AS last_v
FROM monimo.metrics_raw
GROUP BY service_name, agent_id, metric_name, series_hash, ts_min;

-- 1분 중간 상태 → 1시간 (이어달리기)
CREATE MATERIALIZED VIEW IF NOT EXISTS monimo.mv_metrics_1h TO monimo.metrics_1h AS
SELECT
    service_name,
    agent_id,
    metric_name,
    series_hash,
    toStartOfHour(ts_min)     AS ts_hour,
    avgMergeState(avg_v)      AS avg_v,
    minMergeState(min_v)      AS min_v,
    maxMergeState(max_v)      AS max_v,
    argMaxMergeState(last_v)  AS last_v
FROM monimo.metrics_1m
GROUP BY service_name, agent_id, metric_name, series_hash, ts_hour;
