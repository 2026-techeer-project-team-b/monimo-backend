#!/usr/bin/env bash
set -uo pipefail
CH(){ docker exec exp-ch clickhouse-client --user monimo --password monimo --multiquery -q "$1"; }
ins(){ local t=$1; shift; curl -sS -f -u monimo:monimo "http://localhost:28123/?query=INSERT%20INTO%20exp2.$t%20FORMAT%20JSONEachRow$*" --data-binary @- || echo "INSERT 실패 $t"; }
setup(){ CH "CREATE DATABASE IF NOT EXISTS exp2; DROP TABLE IF EXISTS exp2.$1_mv; DROP TABLE IF EXISTS exp2.$1; DROP TABLE IF EXISTS exp2.$1_agg;
CREATE TABLE exp2.$1 (id String, err UInt8) ENGINE=MergeTree ORDER BY id $2;
CREATE TABLE exp2.$1_agg (k UInt8, cnt AggregateFunction(count), e AggregateFunction(sum, UInt8)) ENGINE=AggregatingMergeTree ORDER BY k $3;
CREATE MATERIALIZED VIEW exp2.$1_mv TO exp2.$1_agg AS SELECT 0 AS k, countState() AS cnt, sumState(err) AS e FROM exp2.$1 GROUP BY k;"; }
rec(){ for i in $(seq 1 $2); do echo "{\"id\":\"$1-$i\",\"err\":$3}"; done; }
show(){ printf '%-52s rows=%-3s uniq=%-3s mv_cnt=%-3s mv_err=%s\n' "$1" "$(CH "select count() from exp2.$2")" "$(CH "select uniqExact(id) from exp2.$2")" "$(CH "select countMerge(cnt) from exp2.$2_agg")" "$(CH "select sumMerge(e) from exp2.$2_agg")"; }
W="SETTINGS non_replicated_deduplication_window=1000"
setup g "$W" "$W"; rec e 10 1 | ins g; rec e 10 1 | ins g; show "G 원본·집계 창1000 · 토큰 없음 (async)" g
setup g2 "$W" "$W"; rec e 10 1 | ins g2 "&async_insert=0"; rec e 10 1 | ins g2 "&async_insert=0"; show "G2 원본·집계 창1000 · 토큰 없음 (sync)" g2
setup h "$W" "$W"; rec e 10 1 | ins h "&insert_deduplication_token=raw-0-10"; rec e 10 1 | ins h "&insert_deduplication_token=raw-0-10"; show "H 원본·집계 창1000 · 같은 토큰 (async)" h
setup h2 "$W" "$W"; rec e 10 1 | ins h2 "&insert_deduplication_token=raw-0-10&async_insert=0"; rec e 10 1 | ins h2 "&insert_deduplication_token=raw-0-10&async_insert=0"; show "H2 원본·집계 창1000 · 같은 토큰 (sync)" h2
# 서로 다른 레코드가 같은 집계 결과를 낼 때 (집계 블록 해시가 같아짐) — 잘못 걸러지는지
setup i "$W" "$W"; rec u 10 1 | ins i; rec v 10 1 | ins i; show "I 원본·집계 창1000 · 다른 레코드 · 같은 집계값" i
setup i2 "$W" "$W"; rec u 10 1 | ins i2 "&insert_deduplication_token=raw-0-20"; rec v 10 1 | ins i2 "&insert_deduplication_token=raw-0-21"; show "I2 원본·집계 창1000 · 다른 레코드 · 다른 토큰" i2
