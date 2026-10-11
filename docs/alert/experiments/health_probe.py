#!/usr/bin/env python3
# 집계(service_health_1m) 반영 지연: 요청 1건 → 그 분의 countMerge(cnt) 가 1 늘 때까지. 다른 트래픽이 없을 때만 쓸 수 있다
import base64, datetime as dt, random, sys, time, urllib.request
AUTH = "Basic " + base64.b64encode(b"monimo:monimo").decode()
n, out = int(sys.argv[1]), sys.argv[2]
def ch(q):
    try: return urllib.request.urlopen(urllib.request.Request("http://localhost:18123/", data=q.encode(), headers={"Authorization": AUTH}), timeout=3).read().decode().strip()
    except Exception: return None
def cnt(m):
    v = ch(f"SELECT countMerge(cnt) FROM monimo.service_health_1m WHERE service_name='shop-order' AND ts_min=toDateTime('{m}')")
    return int(v) if v else 0
for i in range(n):
    time.sleep(random.uniform(2, 8))
    # 분 경계 3초 앞뒤는 피한다 (요청이 어느 분에 들어갈지 애매해서)
    while dt.datetime.now(dt.timezone.utc).second < 3 or dt.datetime.now(dt.timezone.utc).second > 52: time.sleep(0.5)
    t0 = time.time(); m = dt.datetime.fromtimestamp(t0, dt.timezone.utc).strftime("%Y-%m-%d %H:%M:00"); before = cnt(m)
    try: urllib.request.urlopen(f"http://localhost:8091/api/orders/{900000000+random.randint(0,99999999)}", timeout=10)
    except Exception: pass
    seen = None
    while time.time() - t0 < 120:
        if cnt(m) >= before + 1: seen = time.time(); break
        time.sleep(0.1)
    line = f"idle-health\t{i+1}\t{dt.datetime.fromtimestamp(t0, dt.timezone.utc).strftime('%H:%M:%S.%f')[:-3]}\t{None if seen is None else round(seen-t0,2)}"
    print(line, flush=True); open(out, "a").write(line + "\n")
