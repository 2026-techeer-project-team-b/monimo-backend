-- 누적 지표를 읽는 쪽이 알아볼 수 있게 metrics_raw 에 지표 종류 칸 둘을 더한다 (#189, ADR #68).
--
-- 문제 : GC 시간(jvm.gc.duration.sum) 같은 누적 지표가 인스펙터 그래프에서 계속 오르는 선으로 보인다.
-- OTel Java SDK 기본값이 cumulative 라 "켜진 뒤로 쌓인 총합" 이 매 내보내기마다 오고, 적재는 ADR #38 대로
-- 값을 바꾸지 않고 그대로 넣는다. 차이 계산은 조회 몫인데, 이 표에 "이 줄이 총합인가" 를 알려 주는 칸이 없어
-- 조회가 어느 지표에 차이 계산을 걸어야 할지 알 수 없었다. 힙 사용량에 잘못 걸면 값이 틀어진다.
-- OTel 은 종류(Gauge · Sum · Histogram ...)와 누적 여부(aggregation_temporality · is_monotonic)를
-- 보내 주는데 적재 처리기가 버리고 있었다. 조회 파트(이현영) 보고, 노션 「누적 지표 그래프 관련 이슈」.
--
-- 칸 둘 :
--   metric_type  OTel 이 보낸 종류 그대로. gauge · sum · histogram · exponential_histogram · summary.
--                히스토그램을 편 네 줄(.count · .sum · .min · .max)은 전부 histogram 이다
--   is_counter   1 = 켜진 뒤로 줄지 않는 총합이라 **앞 값을 빼야 "이 구간에 얼마" 가 된다.**
--                프로메테우스의 counter 와 같은 뜻이다(횟수 count 와 다르다. GC 시간 합도 counter 다).
--                적재 처리기가 OTel 칸 셋으로 정한다 :
--                  Sum               cumulative 이고 is_monotonic 일 때만 1. 델타 Sum 은 이미 구간 값이고,
--                                    단조 아닌 Sum(UpDownCounter, 예: jvm.memory.used) 은 오르내리는 현재값이라 0
--                  Histogram 계열     cumulative 면 .count · .sum 이 1, .min · .max 는 0
--                  Summary           규약상 항상 누적이라 .count · .sum 이 1
--                  Gauge             0
--                읽는 쪽은 이 한 비트만 본다. 1 이면 같은 파드 · 같은 시계열 안에서 앞 값을 빼고,
--                뺀 값이 음수면 파드 재시작(총합이 0 부터 다시)이라 그 구간은 건너뛴다.
--
-- 왜 OTel 칸을 그대로 안 넣고 한 비트로 접었나 : 읽는 쪽이 필요한 것은 "빼야 하나" 하나뿐이고, 그 답은
-- OTel 칸 셋과 줄의 접미(.count 인가 .min 인가)를 같이 봐야 나온다. 그 규칙을 조회 · 경보 둘이 각자 들고
-- 있으면 어긋난다. 종류(metric_type)는 "왜 1 인가" 를 되짚을 수 있게 같이 둔다.
--
-- 롤업(metrics_1m · metrics_1h)에도 is_counter 를 흘린다. 인스펙터 15일 이상 구간과 탐지가 롤업을 읽는데,
-- 거기서도 총합인지 알아야 뺄 수 있다. 시계열 안에서 값이 늘 같으므로 max 로 접는다.
-- SimpleAggregateFunction 인 이유 : AggregatingMergeTree 는 같은 키의 줄을 합칠 때 집계 상태 컬럼만 보장한다.
-- 순서는 db/clickhouse/README.md 대로 ADD COLUMN 이 먼저, MV MODIFY QUERY 가 나중이다
-- (거꾸로 하면 타깃에 없는 컬럼이 조용히 버려진다). MV SELECT 는 V202609221907 과 is_counter 한 줄만 다르다.
--
-- 이미 들어온 줄은 기본값(metric_type '' · is_counter 0)이 된다 : 지금과 같은 동작(그대로 그린다)이고,
-- 원본 보관 기간(105일)이 지나면 자연히 사라진다. 채우지 않는다.
ALTER TABLE monimo.metrics_raw ADD COLUMN IF NOT EXISTS metric_type LowCardinality(String) DEFAULT '' AFTER metric_name;
ALTER TABLE monimo.metrics_raw ADD COLUMN IF NOT EXISTS is_counter  UInt8 DEFAULT 0 AFTER metric_type;

ALTER TABLE monimo.metrics_1m ADD COLUMN IF NOT EXISTS is_counter SimpleAggregateFunction(max, UInt8) AFTER series_hash;
ALTER TABLE monimo.metrics_1h ADD COLUMN IF NOT EXISTS is_counter SimpleAggregateFunction(max, UInt8) AFTER series_hash;

ALTER TABLE monimo.mv_metrics_1m MODIFY QUERY
SELECT
    service_name,
    agent_id,
    metric_name,
    series_hash,
    toStartOfMinute(ts)    AS ts_min,
    max(is_counter)        AS is_counter,
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
    avgMergeState(avg_v)      AS avg_v,
    minMergeState(min_v)      AS min_v,
    maxMergeState(max_v)      AS max_v,
    argMaxMergeState(last_v)  AS last_v
FROM monimo.metrics_1m
GROUP BY service_name, agent_id, metric_name, series_hash, ts_hour;
