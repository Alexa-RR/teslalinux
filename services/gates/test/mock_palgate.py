#!/usr/bin/env python3
"""A local stand-in for PalGate's cloud API (the routes services/gates/palgate.py uses), for tests:
nothing here talks to PalGate. Every authenticated route checks X-Bt-Token the way the service
would have to accept it: derived from the linked account's session token for the current second
(within a few seconds), else 401 {"errId": 7101, "status": "401", "msg": "Invalid Token or Key"}.

    m = MockPalGate(); m.start()          # m.base is the URL to give palgate.Client
    m.approve(link_id)                    # the phone app scanned the code
    m.opens                               # [(gate id, output), ...]
"""
import json
import os
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import palgate  # noqa: E402

PHONE = "972500000123"
SESSION = "00112233445566778899aabbccddeeff"
DEVICES = [
    {"id": "4G600001", "name1": "Home gate", "output1": True, "output2": True, "name2": "Pedestrian door",
     "address": "1 Example St", "admin": True},
    {"id": "4G600002", "name1": "Car park barrier", "output1": True, "output2": False},
    {"id": "BT000003", "name1": "Bluetooth door", "output1": True, "localOnly": True},
]


class MockPalGate:
    def __init__(self):
        self.lock = threading.Lock()
        self.approved = set()
        self.token_type = 1
        self.opens = []
        self.requests = []
        self.fail_open = None       # e.g. ("device error", "check device")
        self.revoked = False
        self.unlinked = False
        self.pending = None         # the link id asked for last
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), self._handler())
        self.base = "http://127.0.0.1:%d/v1/bt/" % self.srv.server_address[1]

    def start(self):
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        return self

    def stop(self):
        self.srv.shutdown()

    def approve(self, link_id):
        with self.lock:
            self.approved.add(link_id)

    def _valid(self, token):
        if self.revoked or not token:
            return False
        now = time.time()
        return any(token == palgate.derive_token(SESSION, PHONE, self.token_type, now + d) for d in range(-5, 3))

    def _handler(mock):
        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _send(self, code, obj):
                b = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(b)))
                self.end_headers()
                self.wfile.write(b)

            def do_GET(self):
                u = urllib.parse.urlsplit(self.path)
                q = urllib.parse.parse_qs(u.query)
                path = u.path[len("/v1/bt/"):] if u.path.startswith("/v1/bt/") else None
                with mock.lock:
                    mock.requests.append(path)
                if path is None:
                    return self._send(404, {})
                if path.startswith("un/secondary/init/"):
                    link_id = urllib.parse.unquote(path.rsplit("/", 1)[1])
                    with mock.lock:
                        mock.pending = link_id
                        ok = link_id in mock.approved
                    if not ok:
                        time.sleep(0.2)  # the real server may hold the request; this one answers "not yet"
                        return self._send(200, {"status": "failed", "msg": "not linked yet", "err": None})
                    return self._send(200, {"user": {"id": PHONE, "token": SESSION, "firstname": "Test", "lastname": "Driver"},
                                            "secondary": str(mock.token_type), "status": "ok", "msg": "success", "err": None})
                if not mock._valid(self.headers.get("X-Bt-Token")):
                    return self._send(401, {"errId": 7101, "status": "401", "msg": "Invalid Token or Key"})
                if path == "user/check-token":
                    return self._send(200, {"status": "ok", "msg": "token valid", "err": None})
                if path in ("devices", "devices/"):
                    return self._send(200, {"devices": DEVICES, "status": "ok", "msg": "all my devices", "err": None})
                if path == "secondary/unlink":
                    mock.unlinked = True
                    return self._send(200, {"status": "ok", "msg": "unlinked", "err": None})
                parts = path.split("/")
                if len(parts) == 3 and parts[0] == "device" and parts[2] == "open-gate":
                    dev = next((d for d in DEVICES if d["id"] == parts[1]), None)
                    out = int((q.get("outputNum") or ["1"])[0])
                    if dev is None or not dev.get("output%d" % out) or (q.get("openBy") or [""])[0] != "100":
                        return self._send(200, {"err": "device error", "msg": "check device", "status": "failed"})
                    if mock.fail_open:
                        return self._send(200, {"err": mock.fail_open[0], "msg": mock.fail_open[1], "status": "failed"})
                    with mock.lock:
                        mock.opens.append((parts[1], out))
                    return self._send(200, {"status": "ok", "msg": "Gate opened: true", "err": None})
                return self._send(404, {"status": "failed", "msg": "no such route"})

        return H


if __name__ == "__main__":
    # by hand: python3 mock_palgate.py [PORT]; then run gates.py with TESLALINUX_GATES_API=<the URL>.
    # A link is approved AUTO_APPROVE seconds (default 8) after the service first asks for it.
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    m = MockPalGate()
    if port:
        m.srv.server_close()
        m.srv = ThreadingHTTPServer(("127.0.0.1", port), m._handler())
        m.base = "http://127.0.0.1:%d/v1/bt/" % port
    m.start()
    print("mock PalGate API at", m.base, flush=True)
    delay = float(os.environ.get("AUTO_APPROVE", "8"))
    seen = {}
    while True:
        time.sleep(0.5)
        with m.lock:
            p = m.pending
        if p and p not in m.approved:
            seen.setdefault(p, time.time())
            if time.time() - seen[p] >= delay:
                m.approve(p)
                print("approved a link", flush=True)
        if m.opens:
            print("opens so far:", m.opens, flush=True)
            with m.lock:
                m.opens.clear()
