-- 롤업 표 둘(metrics_1m · metrics_1h)에 꼬리표(attributes)를 남긴다 (#193, ADR #70).
--
-- 문제 : 꼬리표는 원본 metrics_raw 에만 있고 롤업에는 꼬리표를 숫자로 접은 series_hash 만 있다.
-- MV 가 GROUP BY ... series_hash 로 접는 순간 꼬리표가 떨어지고, 조회는 그 숫자로 원본에서 꼬리표를
-- 찾아 붙인다(MetricRepository.findAttributes). 원본은 105일에 지워지고 롤업은 180일 · 1년 90일을 살아서,
-- 105일 지난 구간은 'G1 Old Gen' 인지 'Eden' 인지 모르는 이름 없는 선이 된다(원본 첫 줄 2026-09-22 기준
-- 2027-01-05 부터). 해시는 SHA-256 앞 8바이트라 되살릴 수 없다. 조회 파트(이현영) 보고,
-- 노션 「누적 지표 그래프 관련 이슈」 「같이 알아둘 것」.
--
-- 장치 : 접을 때 꼬리표를 any 로 하나 남긴다. 같은 series_hash 면 꼬리표가 늘 같으므로(숫자가 꼬리표에서
-- 나왔다) 아무거나 하나로 충분하다. SimpleAggregateFunction 인 이유는 #189 의 is_counter 와 같다 :
-- AggregatingMergeTree 는 같은 키의 줄을 합칠 때 보통 칸은 어느 값이 남는지 보장하지 않는다.
--
-- 타입이 원본(Map(LowCardinality(String), String))과 다른 이유 : any 가 LowCardinality 를 벗긴 Map(String, String) 을
-- 돌려줘서 SimpleAggregateFunction(any, Map(LowCardinality(String), String)) 은 "Incompatible data types between
-- aggregate function 'any' which returns Map(String, String) and column storage type ..." (Code 36) 로 표가 안 만들어진다
-- (실측, 테스트 컨테이너). 롤업은 줄 수가 원본의 1/4 · 1/240 이라 사전 압축을 포기해도 된다.
--
-- 순서는 db/clickhouse/README.md 대로 ADD COLUMN 이 먼저, MV MODIFY QUERY 가 나중이다
-- (거꾸로 하면 타깃에 없는 컬럼이 조용히 버려진다). MV SELECT 는 V202610140450 과 attributes 한 줄만 다르다.
--
-- 이미 접혀 있는 줄은 빈 Map 으로 남는다. 채우지 않는다 : 원본이 아직 105일 안이라 조회가 지금 방식(원본에서
-- 해시로 찾기)으로 전부 찾을 수 있고, 새로 접히는 줄부터 꼬리표가 붙어 105일 뒤에는 롤업 전체가 꼬리표를 가진다.
-- 조회는 나중에 "롤업의 attributes 가 빈 Map 일 때만 원본을 찾는다" 로 바꾸면 되고 지금은 안 바꿔도 같다.
ALTER TABLE monimo.metrics_1m ADD COLUMN IF NOT EXISTS attributes SimpleAggregateFunction(any, Map(String, String)) AFTER is_counter;
ALTER TABLE monimo.metrics_1h ADD COLUMN IF NOT EXISTS attributes SimpleAggregateFunction(any, Map(String, String)) AFTER is_counter;

ALTER TABLE monimo.mv_metrics_1m MODIFY QUERY
SELECT
    service_name,
    agent_id,
    metric_name,
    series_hash,
    toStartOfMinute(ts)    AS ts_min,
    max(is_counter)        AS is_counter,
    any(attributes)        AS attributes,
    avgState(value)        AS avg_v,
    minState(value)        AS min_v,
    maxState(value)        AS max_v,
    argMaxState(value, ts) AS last_v
FROM monimo.metrics_raw
GROUP BY service_name, agent_id, metric_name, series_hash, ts_min;

ALTER TABLE monimo.mv_metrics_1h MODIFY QUERY
SELECT
    service_name,
    agent_id,
    metric_name,
    series_hash,
    toStartOfHour(ts_min)     AS ts_hour,
    max(is_counter)           AS is_counter,
    any(attributes)           AS attributes,
    avgMergeState(avg_v)      AS avg_v,
    minMergeState(min_v)      AS min_v,
    maxMergeState(max_v)      AS max_v,
    argMaxMergeState(last_v)  AS last_v
FROM monimo.metrics_1m
GROUP BY service_name, agent_id, metric_name, series_hash, ts_hour;
