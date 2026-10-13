#!/usr/bin/env bash
# 집계가 틀어진 1분 구간을 **원본에서 다시 만든다** (#187 ⑤ 복구 절차).
#
# 언제 쓰나 : 중복 제거 기억이 못 걸러서(research.md 2.2) 또는 새 오프셋 재발행 · 기억 밖 재처리로
# (research.md 2.5 ②③) 집계 숫자가 원본과 어긋났을 때. 어긋남은 check-pipeline.sh 의 중복 대조가 알려 준다.
#
# 사용 : reaggregate_window.sh <서비스 이름> '<UTC 분 시작, 예 2026-10-13 19:04:00>'
#   CH=exp-ch       격리 실험용 (기본값)
#   CH=clickhouse   compose 로컬. docker exec 로 바로 치므로 컨테이너 이름만 바꾸면 된다
#
# **자동화하지 않은 이유** : 이 일이 몇 번 필요한지 아직 세어 본 적이 없다. 되넣는 잡(ADR #34)을 만들 때
# 그때까지 손으로 돌린 횟수를 보고 자동화할지 정한다. 팀이 ADR #55 · #66 ① 에서 쓴 기준과 같다.
set -euo pipefail
CH_CONTAINER=${CH:-exp-ch}
SVC=${1:?서비스 이름을 주세요}
TS=${2:?UTC 분 시작을 주세요 (예 '2026-10-13 19:04:00')}

Q() { docker exec -i "$CH_CONTAINER" clickhouse-client --user monimo --password monimo -q "$1"; }

# 중복 제거 기억에 걸리지 않게 명시한다.
#
# 복구 INSERT 는 INSERT SELECT 이고 ClickHouse 는 그쪽에 deduplicate_insert_select 를 먼저 본다.
# 기본값 enable_when_possible 은 "SELECT 가 안정적일 때만"(ORDER BY ALL + 단일 스트림) 중복 제거를 하므로
# 아래 질의들은 기본값에서도 안 걸린다. 그래도 **끄는 것을 글자로 적어 둔다** : 안 적어 두면 나중에
# 질의 모양이 바뀌었을 때(ORDER BY 가 붙는 등) 복구 INSERT 가 조용히 버려지고 그 사실이 안 보인다.
NODEDUP="SETTINGS deduplicate_insert_select = 'disable', deduplicate_insert = 'disable'"
# 삭제가 끝날 때까지 기다린다. 기본은 비동기라 안 기다리면 다음 INSERT 가 지워지기 전 상태에 얹힌다
SYNC="SETTINGS mutations_sync = 2"

# 원본에서 중복을 뺀 스팬. 같은 (trace_id, span_id) 가 여러 줄이면 하나만 남긴다.
# 어느 줄을 남겨도 같다 : 같은 레코드를 다시 처리해 생긴 줄이라 내용이 같다. 다르면 peer_service 뿐이고
# 그 칸은 집계에 안 쓰인다(서버맵 MV 는 CLIENT 전용이라 이 복구 범위 밖이다).
#
# **중복 제거를 LIMIT 1 BY 로 한다.** 처음에 GROUP BY + any() 로 썼다가 두 번 틀렸다(실측) :
#   ① 종류 걸러내기를 바깥 WHERE 에 뒀더니 ClickHouse 가 그 조건을 안쪽으로 밀어 넣으려다
#      Code: 184 Aggregate function any(service_name) AS service_name is found in WHERE in query
#   ② 안쪽으로 옮겨도 같은 오류가 났다. 원인이 달랐다 : 안쪽 WHERE 의 service_name 이
#      컬럼이 아니라 **같은 이름의 집계 별칭**(any(service_name) AS service_name)으로 풀린다.
# LIMIT 1 BY 는 집계가 아니라 "키마다 한 줄만" 이라 별칭도 안 만들고 컬럼 이름이 그대로 살아 있다.
# 쓰는 것과 돌려 보는 것은 다른 일이다(#119 에서 같은 교훈).
deduped() {
  echo "
  SELECT *
  FROM monimo.spans
  WHERE service_name = '$SVC' AND toStartOfMinute(start_time) = toDateTime('$TS')
    AND span_kind IN ($1)
  LIMIT 1 BY trace_id, span_id"
}

echo "== 복구 대상 : $SVC / $TS (컨테이너 $CH_CONTAINER)"
echo "-- 복구 전"
Q "SELECT
     (SELECT count() FROM monimo.spans WHERE service_name='$SVC' AND toStartOfMinute(start_time)=toDateTime('$TS')) AS spans_rows,
     (SELECT uniqExact(trace_id, span_id) FROM monimo.spans WHERE service_name='$SVC' AND toStartOfMinute(start_time)=toDateTime('$TS')) AS spans_uniq,
     (SELECT countMerge(cnt) FROM monimo.service_health_1m WHERE service_name='$SVC' AND ts_min=toDateTime('$TS')) AS health_cnt,
     (SELECT sumMerge(err_cnt) FROM monimo.service_health_1m WHERE service_name='$SVC' AND ts_min=toDateTime('$TS')) AS health_err,
     (SELECT count() FROM monimo.transactions WHERE service_name='$SVC' AND toStartOfMinute(start_time)=toDateTime('$TS')) AS tx_rows,
     (SELECT sum(cnt) FROM monimo.heatmap_1m WHERE service_name='$SVC' AND ts_min=toDateTime('$TS')) AS heatmap_cnt,
     (SELECT countMerge(cnt) FROM monimo.url_stats_1m WHERE service_name='$SVC' AND ts_min=toDateTime('$TS')) AS url_cnt
   FORMAT Vertical"

# ── 순서가 중요하다 ──────────────────────────────────────────────
# heatmap_1m 은 transactions 를 읽는 MV 의 대상이다(연쇄 2단). transactions 에 다시 넣으면
# mv_heatmap_1m 이 그 자리에서 heatmap_1m 을 **자동으로 다시 채운다.**
# 그래서 heatmap_1m 을 먼저 지우고, transactions 를 지우고, transactions 에 넣는다.
# 순서를 바꿔 heatmap_1m 을 나중에 지우면 방금 자동으로 채운 것을 지워 버린다.
echo "-- ① 집계 구간 삭제 (heatmap_1m -> transactions 순서)"
Q "ALTER TABLE monimo.heatmap_1m        DELETE WHERE service_name = '$SVC' AND ts_min = toDateTime('$TS') $SYNC"
Q "ALTER TABLE monimo.transactions      DELETE WHERE service_name = '$SVC' AND toStartOfMinute(start_time) = toDateTime('$TS') $SYNC"
Q "ALTER TABLE monimo.service_health_1m DELETE WHERE service_name = '$SVC' AND ts_min = toDateTime('$TS') $SYNC"
Q "ALTER TABLE monimo.url_stats_1m      DELETE WHERE service_name = '$SVC' AND ts_min = toDateTime('$TS') $SYNC"

echo "-- ② transactions 다시 넣기 (heatmap_1m 은 MV 가 따라 채운다)"
Q "INSERT INTO monimo.transactions
   SELECT
     trace_id,
     toDateTime64(start_time, 3)            AS start_time,
     toUInt32(intDiv(duration_ns, 1000000)) AS duration_ms,
     service_name, agent_id, span_name,
     toUInt8(status_code = 'ERROR')         AS is_error,
     http_status,
     toUInt8(parent_span_id = '')           AS is_root
   FROM ($(deduped "'SERVER', 'CONSUMER'"))
   $NODEDUP"

echo "-- ③ service_health_1m 다시 넣기"
Q "INSERT INTO monimo.service_health_1m
   SELECT
     toStartOfMinute(start_time)                        AS ts_min,
     service_name,
     countState()                                       AS cnt,
     sumState(toUInt8(status_code = 'ERROR'))           AS err_cnt,
     sumState(toUInt8(http_status BETWEEN 400 AND 499)) AS cnt_4xx,
     sumState(toUInt8(http_status BETWEEN 500 AND 599)) AS cnt_5xx,
     quantilesTDigestState(0.5, 0.95, 0.99)(duration_ns) AS dur_q
   FROM ($(deduped "'SERVER'"))
   GROUP BY ts_min, service_name
   $NODEDUP"

echo "-- ④ url_stats_1m 다시 넣기"
Q "INSERT INTO monimo.url_stats_1m
   SELECT
     toStartOfMinute(start_time)                        AS ts_min,
     service_name, span_name, agent_id,
     countState()                                       AS cnt,
     sumState(toUInt8(status_code = 'ERROR'))           AS err_cnt,
     quantilesTDigestState(0.5, 0.95, 0.99)(duration_ns) AS dur_q
   FROM ($(deduped "'SERVER'"))
   GROUP BY ts_min, service_name, span_name, agent_id
   $NODEDUP"

echo "-- 복구 후"
Q "SELECT
     (SELECT count() FROM monimo.spans WHERE service_name='$SVC' AND toStartOfMinute(start_time)=toDateTime('$TS')) AS spans_rows,
     (SELECT uniqExact(trace_id, span_id) FROM monimo.spans WHERE service_name='$SVC' AND toStartOfMinute(start_time)=toDateTime('$TS')) AS spans_uniq,
     (SELECT countMerge(cnt) FROM monimo.service_health_1m WHERE service_name='$SVC' AND ts_min=toDateTime('$TS')) AS health_cnt,
     (SELECT sumMerge(err_cnt) FROM monimo.service_health_1m WHERE service_name='$SVC' AND ts_min=toDateTime('$TS')) AS health_err,
     (SELECT count() FROM monimo.transactions WHERE service_name='$SVC' AND toStartOfMinute(start_time)=toDateTime('$TS')) AS tx_rows,
     (SELECT sum(cnt) FROM monimo.heatmap_1m WHERE service_name='$SVC' AND ts_min=toDateTime('$TS')) AS heatmap_cnt,
     (SELECT countMerge(cnt) FROM monimo.url_stats_1m WHERE service_name='$SVC' AND ts_min=toDateTime('$TS')) AS url_cnt
   FORMAT Vertical"

echo "== 끝. 집계는 고유 스팬 수와 같아져야 한다. **원본 spans 의 중복 줄은 그대로 남는다** :"
echo "   원본을 지우는 것은 범위 밖이다(조회 · 콜트리는 (trace_id, span_id) 로 가려낼 수 있다)."
echo "   원본까지 치우려면 ALTER TABLE monimo.spans DELETE 로 지우고 다시 넣어야 하는데, 그 사이"
echo "   콜트리 조회가 빈 구간을 보게 되어 비용이 더 크다. 필요해지면 따로 정한다."
