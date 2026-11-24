"""A local mock of the parts of Cloudflare's API (https://api.cloudflare.com/client/v4) that
services/grok/cfsetup.py calls, with Cloudflare's envelope ({success, errors, messages, result})
and an in-memory account: one zone, a Zero Trust organization, tunnels, Access apps, DNS records.
It checks the bearer token and records every call."""
import json
import re
import threading
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = "cf-test-token-0123456789"
ACCOUNT = "a" * 32
ZONE = "z" * 32


class State:
    def __init__(self, zone="example.com", org="myteam", idps=("onetimepin",)):
        self.zone = zone
        self.org = org
        self.idps = list(idps)
        self.tunnels = {}
        self.apps = {}
        self.records = {}
        self.configs = {}
        self.calls = []


def make_handler(st):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _out(self, code, result=None, errors=None):
            body = json.dumps({"success": not errors and code < 400, "errors": errors or [], "messages": [], "result": result}).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _body(self):
            n = int(self.headers.get("Content-Length") or 0)
            return json.loads(self.rfile.read(n).decode()) if n else None

        def _go(self, method):
            u = urllib.parse.urlsplit(self.path)
            path, q = u.path.replace("/client/v4", ""), urllib.parse.parse_qs(u.query)
            body = self._body() if method in ("POST", "PUT", "PATCH") else None
            st.calls.append((method, path, body))
            if self.headers.get("Authorization") != "Bearer " + TOKEN:
                return self._out(403, None, [{"code": 10000, "message": "Authentication error"}])
            acc = "/accounts/%s" % ACCOUNT
            if path == "/zones" and method == "GET":
                name = q.get("name", [""])[0]
                return self._out(200, [{"id": ZONE, "name": st.zone, "account": {"id": ACCOUNT}}] if name == st.zone else [])
            if path == acc + "/access/organizations" and method == "GET":
                if not st.org:
                    return self._out(404, None, [{"code": 12130, "message": "access.api.error.not_found"}])
                return self._out(200, {"name": st.org, "auth_domain": "%s.cloudflareaccess.com" % st.org})
            if path == acc + "/access/identity_providers" and method == "GET":
                return self._out(200, [{"id": str(uuid.uuid4()), "type": t, "name": t} for t in st.idps])
            if path == acc + "/cfd_tunnel":
                if method == "GET":
                    name = q.get("name", [""])[0]
                    return self._out(200, [t for t in st.tunnels.values() if t["name"] == name])
                if method == "POST":
                    assert body["config_src"] == "cloudflare"
                    t = {"id": str(uuid.uuid4()), "name": body["name"], "deleted_at": None}
                    st.tunnels[t["id"]] = t
                    return self._out(200, t)
            m = re.match(r"^%s/cfd_tunnel/([^/]+)/(token|configurations)$" % acc, path)
            if m and m.group(1) in st.tunnels:
                if m.group(2) == "token" and method == "GET":
                    import base64
                    tok = base64.b64encode(json.dumps({"a": ACCOUNT, "t": m.group(1), "s": "c2VjcmV0c2VjcmV0c2VjcmV0"}).encode()).decode()
                    return self._out(200, tok)
                if m.group(2) == "configurations" and method == "PUT":
                    st.configs[m.group(1)] = body
                    return self._out(200, {"tunnel_id": m.group(1), "config": body["config"]})
            if path == acc + "/access/apps":
                if method == "GET":
                    return self._out(200, list(st.apps.values()))
                if method == "POST":
                    a = dict(body, id=str(uuid.uuid4()), aud=uuid.uuid4().hex + uuid.uuid4().hex)
                    st.apps[a["id"]] = a
                    return self._out(201, a)
            m = re.match(r"^%s/access/apps/([^/]+)$" % acc, path)
            if m and method == "PUT" and m.group(1) in st.apps:
                a = dict(body, id=m.group(1), aud=st.apps[m.group(1)]["aud"])
                st.apps[m.group(1)] = a
                return self._out(200, a)
            if path == "/zones/%s/dns_records" % ZONE:
                if method == "GET":
                    name = q.get("name", [""])[0]
                    return self._out(200, [r for r in st.records.values() if r["name"] == name])
                if method == "POST":
                    r = dict(body, id=uuid.uuid4().hex)
                    st.records[r["id"]] = r
                    return self._out(200, r)
            m = re.match(r"^/zones/%s/dns_records/([^/]+)$" % ZONE, path)
            if m and method == "PATCH" and m.group(1) in st.records:
                st.records[m.group(1)].update(body)
                return self._out(200, st.records[m.group(1)])
            return self._out(404, None, [{"code": 7003, "message": "No route for that URI"}])

        def do_GET(self):
            self._go("GET")

        def do_POST(self):
            self._go("POST")

        def do_PUT(self):
            self._go("PUT")

        def do_PATCH(self):
            self._go("PATCH")
    return H


def start(st):
    srv = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(st))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, "http://127.0.0.1:%d/client/v4" % srv.server_address[1]
