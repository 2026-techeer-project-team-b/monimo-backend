-- MV 7개: 원본에 INSERT 가 들어오는 순간 집계 표를 자동으로 채운다. 정본: 노션 ERD「MV 흐름」.
--
--   spans ──mv_transactions──▶ transactions ──mv_heatmap_1m──▶ heatmap_1m  (SERVER · CONSUMER 스팬)
--   spans ──mv_url_stats_1m──▶ url_stats_1m          (SERVER 스팬만)
--   spans ──mv_server_map_1m──▶ server_map_1m        (CLIENT 스팬만)
--   spans ──mv_service_health_1m──▶ service_health_1m (SERVER 스팬만)
--   metrics_raw ──mv_metrics_1m──▶ metrics_1m ──mv_metrics_1h──▶ metrics_1h
--
-- MV는 만든 뒤에 들어온 줄만 계산한다. 과거 데이터는 채워 주지 않는다.

-- 서비스가 받은 요청 = 일이 그 서비스로 들어온 지점 (ADR #52)
--
-- SERVER 는 HTTP 로 받은 것, CONSUMER 는 큐에서 받은 것이다. 둘 다 "일이 들어온 지점" 이다.
-- 전에는 parent_span_id = '' (루트만)이었는데, 그러면 요청이 처음 닿는 서비스에만 줄이 생겨
-- 중간 서비스(order · payment · inventory) 스캐터가 비었다. 화면은 서비스를 골라 보는 구조다.
-- 루트는 SERVER 의 부분집합이라 넓혀도 전에 들어오던 줄은 그대로 들어온다.
--
-- CONSUMER 는 지금 0건이다(쇼핑몰이 동기 MVC 단일 조합, ADR #25). 미리 넣어 두는 이유는
-- 남의 앱에 에이전트를 붙였을 때 그 앱이 큐를 쓰면 같은 문제가 또 생기기 때문이다.
--
-- OR parent_span_id = '' 는 일부러 안 넣었다. 그러면 아무도 안 부른 일(앱 시작 DDL · 배치)까지
-- 들어와 CREATE TABLE 이 스캐터 점으로 찍힌다. 진짜 배치를 만들 때 넓힌다(ADR #52 되돌림 ①).
--
-- SELECT 의 컬럼 수와 순서는 transactions 표와 정확히 맞아야 한다. ALTER ... MODIFY QUERY 로
-- 이 정의를 바꿀 때 ClickHouse 가 타깃 스키마를 검사하지 않아서, 빠뜨리면 그 컬럼이 조용히
-- 타입 기본값으로 채워진다(빈 문자열 · 0). 고친 뒤에는 각 컬럼에 값이 들어오는지 확인한다.
CREATE MATERIALIZED VIEW IF NOT EXISTS monimo.mv_transactions TO monimo.transactions AS
SELECT
    trace_id,
    toDateTime64(start_time, 3)            AS start_time,
    toUInt32(intDiv(duration_ns, 1000000)) AS duration_ms,
    service_name,
    agent_id,
    span_name,
    toUInt8(status_code = 'ERROR')         AS is_error,
    http_status,
    toUInt8(parent_span_id = '')           AS is_root
FROM monimo.spans
WHERE span_kind IN ('SERVER', 'CONSUMER');

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
