import http.server, sys
LOG = sys.argv[1]
class H(http.server.BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Headers', '*')
    def do_OPTIONS(self):
        self.send_response(204); self._cors(); self.end_headers()
    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        line = self.rfile.read(n).decode('utf-8', 'replace')
        with open(LOG, 'a') as f: f.write(line + '\n')
        self.send_response(204); self._cors(); self.end_headers()
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(('0.0.0.0', 8765), H).serve_forever()
