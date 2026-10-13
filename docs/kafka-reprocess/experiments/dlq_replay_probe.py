#!/usr/bin/env python3
# raw.dlq 를 꺼내 raw 로 되넣어 보는 **조사용 시제품** (#195). 되넣는 잡(ADR #34)이 아니다 :
# 오프셋 커밋만 하고 포기 규칙 · 분류 걸러내기 · 재집계가 없다. research.md 2.3 ~ 2.5 를 재현하는 도구다.
#
# 가져가는 머리말은 셋뿐이다 (실측 근거는 research.md 2.5) :
#   x-dlq-attempt         버리면 횟수가 매번 1 로 돌아가 영원히 도는 고리가 된다
#   x-dlq-reason          poison 을 걸러낼 단서. 지금은 보여 주기만 한다
#   network.peer.address  버리면 되넣은 메시지만 agents.ip 가 빈칸이 된다 (ADR #63)
# kafka_dlt-* 열 개는 버린다 : 스택 추적 한 장이 약 6,033바이트이고 값은 406바이트다
#
# 사용 (kafka-python 필요) :
#   dlq_replay_probe.py list                       무엇이 들어 있나 (되넣지 않는다)
#   dlq_replay_probe.py replay <그룹 이름>          전부 되넣고 커밋. 그룹은 ingester 가 아니어야 한다
#   dlq_replay_probe.py one <오프셋>               한 건만 되넣는다 (커밋 없음)
#   dlq_replay_probe.py poison <keep|strip>        독성 한 건을 raw 로 보내 머리말이 이어지는지 본다
#   BOOT=localhost:19092 이 기본값. 격리 실험은 BOOT=localhost:39092
import os, sys
from kafka import KafkaConsumer, KafkaProducer, TopicPartition

BOOT = os.environ.get("BOOT", "localhost:19092")
SRC, DST = "raw.dlq", "raw"
KEEP = {"x-dlq-attempt", "x-dlq-reason", "network.peer.address"}


def show(r):
    hdrs = r.headers or []
    size = sum(len(k) + len(v or b"") for k, v in hdrs)
    kept = {k: (v or b"").decode(errors="replace") for k, v in hdrs if k in KEEP}
    print(f"off={r.offset} key={r.key.decode() if r.key else None} value={len(r.value)}B "
          f"headers={size}B({len(hdrs)}개) keep={kept}")
    return [(k, v) for k, v in hdrs if k in KEEP]


def consumer(group=None, offset=None):
    c = KafkaConsumer(bootstrap_servers=BOOT, group_id=group, auto_offset_reset="earliest",
                      enable_auto_commit=False, consumer_timeout_ms=8000)
    if offset is None:
        c.subscribe([SRC])
    else:
        tp = TopicPartition(SRC, 0)
        c.assign([tp])
        c.seek(tp, offset)
    return c


cmd = sys.argv[1] if len(sys.argv) > 1 else "list"

if cmd == "poison":
    keep = sys.argv[2] == "keep"
    p = KafkaProducer(bootstrap_servers=BOOT, acks="all")
    hdrs = [("x-dlq-attempt", b"1"), ("x-dlq-reason", b"poison")] if keep else []
    p.send(DST, key=b"logs", value=bytes([0x0F]), headers=hdrs).get(timeout=10)
    p.flush()
    print(f"독성 1건 보냄 (머리말 {'가져감' if keep else '버림'}). raw.dlq 끝에서 attempt 를 본다")
    sys.exit(0)

group = sys.argv[2] if cmd == "replay" else "dlq-probe-list"
offset = int(sys.argv[2]) if cmd == "one" else None
c = consumer(group if cmd == "replay" else None, offset)
p = KafkaProducer(bootstrap_servers=BOOT, acks="all") if cmd != "list" else None

n = 0
for r in c:
    kept = show(r)
    if cmd != "list":
        p.send(DST, key=r.key, value=r.value, headers=kept).get(timeout=10)
    n += 1
    if cmd == "one":
        break
if p:
    p.flush()
if cmd == "replay":
    c.commit()  # 깊이 = 이 그룹의 lag 이 된다. 토픽은 비워지지 않는다
print(f"=== {cmd} {n}건 (bootstrap={BOOT}) ===")
