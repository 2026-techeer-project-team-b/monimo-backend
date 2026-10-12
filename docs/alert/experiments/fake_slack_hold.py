#!/usr/bin/env python3
# 실험용 가짜 Slack. 요청을 받는 즉시 수신 시각 · 본문을 파일에 한 줄 적고(flush),
# HOLD 파일이 있으면 그 안의 초만큼 응답을 늦춘다. "외부는 받았는데 발신자는 아직 결과를 모르는" 구간을 만든다.
import http.server, sys, time, datetime, os, threading

PORT = int(sys.argv[1]); LOG = sys.argv[2]; HOLD = sys.argv[3]
lock = threading.Lock()

class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0))).decode()
        now = datetime.datetime.now(datetime.timezone.utc).isoformat()
        with lock, open(LOG, 'a') as f:
            f.write(f"{now}\t{body}\n"); f.flush(); os.fsync(f.fileno())
        if os.path.exists(HOLD):
            time.sleep(float(open(HOLD).read().strip() or 0))
        self.send_response(200); self.send_header('Content-Length', '2'); self.end_headers(); self.wfile.write(b'ok')
    def log_message(self, *a): pass

http.server.ThreadingHTTPServer(('127.0.0.1', PORT), H).serve_forever()
