-- #118 이전에 만든 ClickHouse 를 가진 사람을 위한 호환 마이그레이션 (#119, ADR #57).
--
-- 앞 파일(V202609221906)이 컬럼을 채워 주면 V202609221907 은 더 이상 터지지 않는다.
-- 그런데 CREATE MATERIALIZED VIEW IF NOT EXISTS 는 **이미 있는 MV 를 갱신하지 못한다.**
-- 그래서 옛 DB 는 조용히 옛 정의(WHERE parent_span_id = '')를 그대로 들고 있게 되고,
-- 스캐터가 여전히 진입 서비스에서만 점을 보여 준다. 에러가 없어서 아무도 모른다.
--
-- MODIFY QUERY 로 정의를 덮어쓴다. V202609221907 과 글자 단위로 같은 SELECT 라
-- #118 이후에 만든 DB 에서는 같은 값을 다시 넣는 것이라 아무것도 안 바뀐다.
--
-- 두 파일이 같은 것은 지금 상태일 뿐이고 지켜야 하는 불변식이 아니다. 둘 다 이미 적용된 파일이라
-- 고치면 체크섬이 어긋나 멈춘다. 앞으로 MV 정의를 바꿀 때는 이 둘을 고치지 말고 새 V 파일에
-- MODIFY QUERY 를 쓴다 (db/clickhouse/README.md).
--
-- MODIFY QUERY 를 쓰는 이유(DROP VIEW + CREATE 가 아니라)는 db/clickhouse/README.md 에 있다 :
-- 지우고 다시 만들면 그 사이 INSERT 가 영구 유실된다 (#118 조사 실험에서 4줄 넣고 0줄).
ALTER TABLE monimo.mv_transactions MODIFY QUERY
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
