#!/usr/bin/env bash
# 격리 CH(exp-ch)의 exp2 DB 에서만. 적재 처리기와 같은 HTTP + JSONEachRow, 서버 기본 설정(async_insert=1 · wait=1)
set -uo pipefail
CH(){ docker exec exp-ch clickhouse-client --user monimo --password monimo --multiquery -q "$1"; }
ins(){ local t=$1; shift; curl -sS -f -u monimo:monimo "http://localhost:28123/?query=INSERT%20INTO%20exp2.$t%20FORMAT%20JSONEachRow$*" --data-binary @- || echo "INSERT 실패 $t"; }
setup(){ CH "CREATE DATABASE IF NOT EXISTS exp2; DROP TABLE IF EXISTS exp2.$1_mv; DROP TABLE IF EXISTS exp2.$1; DROP TABLE IF EXISTS exp2.$1_agg;
CREATE TABLE exp2.$1 (id String, err UInt8) ENGINE=MergeTree ORDER BY id $2;
CREATE TABLE exp2.$1_agg (k UInt8, cnt AggregateFunction(count), e AggregateFunction(sum, UInt8)) ENGINE=AggregatingMergeTree ORDER BY k;
CREATE MATERIALIZED VIEW exp2.$1_mv TO exp2.$1_agg AS SELECT 0 AS k, countState() AS cnt, sumState(err) AS e FROM exp2.$1 GROUP BY k;"; }
rec(){ for i in $(seq 1 $2); do echo "{\"id\":\"$1-$i\",\"err\":$3}"; done; }
show(){ printf '%-48s rows=%-3s uniq=%-3s mv_cnt=%-3s mv_err=%s\n' "$1" "$(CH "select count() from exp2.$2")" "$(CH "select uniqExact(id) from exp2.$2")" "$(CH "select countMerge(cnt) from exp2.$2_agg")" "$(CH "select sumMerge(e) from exp2.$2_agg")"; }
W="SETTINGS non_replicated_deduplication_window=1000"
setup a "";  rec e 10 1 | ins a; rec e 10 1 | ins a; show "A 창0 · 같은 블록 2번 (async)" a
setup b "$W"; rec e 10 1 | ins b; rec e 10 1 | ins b; show "B 창1000 · 같은 블록 2번 (async)" b
setup b2 "$W"; rec e 10 1 | ins b2 "&async_insert=0"; rec e 10 1 | ins b2 "&async_insert=0"; show "B2 창1000 · 같은 블록 2번 (sync)" b2
setup c "$W"; rec e 10 1 | ins c "&insert_deduplication_token=raw-0-10"; rec e 10 1 | ins c "&insert_deduplication_token=raw-0-10"; show "C 창1000 · 같은 토큰 2번 (async)" c
setup c2 "$W"; rec x 10 0 | ins c2 "&insert_deduplication_token=raw-0-10"; rec y 10 1 | ins c2 "&insert_deduplication_token=raw-0-10"; show "C2 창1000 · 내용 다름 · 같은 토큰 (async)" c2
setup d "$W"; rec e 10 1 | ins d; rec e 10 1 | tail -r | ins d; show "D 창1000 · 같은 줄 · 순서만 다름 (async)" d
setup d2 "$W"; rec e 10 1 | ins d2 "&insert_deduplication_token=raw-0-10"; rec e 10 1 | tail -r | ins d2 "&insert_deduplication_token=raw-0-11"; show "D2 창1000 · 같은 줄 · 다른 토큰(새 오프셋)" d2
setup e "SETTINGS non_replicated_deduplication_window=2"; for x in p q r p; do rec $x 5 0 | ins e; done; show "E 창2 · p q r 뒤 p 재삽입 (창 밖)" e
setup f "SETTINGS non_replicated_deduplication_window=2"; for x in p q p; do rec $x 5 0 | ins f; done; show "F 창2 · p q 뒤 p 재삽입 (창 안)" f
