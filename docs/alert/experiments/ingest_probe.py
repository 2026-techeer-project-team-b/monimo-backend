#!/usr/bin/env python3
# 수집 지연 프로브: 고유 주소 요청 1건 → 그 SERVER 스팬이 ClickHouse 에 보일 때까지.
# 사용: ingest_probe.py <조건이름> <횟수> <최소간격초> <최대간격초> <출력tsv> [--check-health]
import base64, datetime as dt, random, sys, time, urllib.request

CH = "http://localhost:18123/"
AUTH = "Basic " + base64.b64encode(b"monimo:monimo").decode()
name, n, lo, hi, out = sys.argv[1], int(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
check_health = "--check-health" in sys.argv


def ch(q):
    req = urllib.request.Request(CH, data=q.encode(), headers={"Authorization": AUTH})
    try:
        return urllib.request.urlopen(req, timeout=3).read().decode().strip()
    except Exception as e:
        return None  # ClickHouse 가 멈춘 동안


def health_cnt(minute):
    v = ch(f"SELECT countMerge(cnt) FROM monimo.service_health_1m WHERE service_name='shop-order' AND ts_min = toDateTime('{minute}')")
    return int(v) if v not in (None, "") else None


for i in range(n):
    time.sleep(random.uniform(lo, hi))
    probe = 900000000 + random.randint(0, 99999999)
    path = f"/api/orders/{probe}"
    t0 = time.time()
    minute = dt.datetime.fromtimestamp(t0, dt.timezone.utc).strftime("%Y-%m-%d %H:%M:00")
    before = health_cnt(minute) if check_health else None
    try:
        urllib.request.urlopen(f"http://localhost:8091{path}", timeout=10)
    except Exception:
        pass  # 404 가 정상 (없는 주문)
    since = dt.datetime.fromtimestamp(t0 - 5, dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
    q = (f"SELECT count() FROM monimo.spans WHERE service_name='shop-order' AND span_kind='SERVER' "
         f"AND attributes['url.path']='{path}' AND start_time >= toDateTime64('{since}', 3, 'UTC')")
    seen = None
    while time.time() - t0 < 180:
        v = ch(q)
        if v and int(v) >= 1:
            seen = time.time(); break
        time.sleep(0.1)
    lat = None if seen is None else round(seen - t0, 2)
    extra = ""
    if check_health and seen is not None:
        after = health_cnt(minute)
        extra = f"\thealth {before}->{after}"
    sent = dt.datetime.fromtimestamp(t0, dt.timezone.utc).strftime("%H:%M:%S.%f")[:-3]
    line = f"{name}\t{i+1}\t{sent}\t{lat}{extra}"
    print(line, flush=True)
    open(out, "a").write(line + "\n")
