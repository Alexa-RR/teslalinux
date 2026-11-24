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
        # more zones the account has (an OAuth grant sees them all; the setup uses st.zone's id for each)
        self.more_zones = []
        # OAuth (dash.cloudflare.com/oauth2): codes issued by the consent page, live tokens, revoked ones
        self.client_id = "test-client-id"
        self.codes = {}          # code -> {challenge, redirect_uri, client_id, email}
        self.oauth_tokens = {}   # token -> email
        self.revoked = []
        self.user_email = "driver@example.com"
        self.account_name = "Driver's Account"

    def consent(self, authorize_url, email=None):
        """The browser's trip through Cloudflare's consent page: checks the request as Cloudflare would
        and returns the callback parameters (a code for the state)."""
        q = {k: v[0] for k, v in urllib.parse.parse_qs(urllib.parse.urlsplit(authorize_url).query).items()}
        assert q["response_type"] == "code" and q["client_id"] == self.client_id, q
        assert q["code_challenge_method"] == "S256" and q["code_challenge"][0].isalnum(), q
        # exactly the project client's scopes (all required; Cloudflare refuses others)
        assert q["scope"].split() == ["dns.write", "zone.read", "access.write", "access-acct.read", "teams-connector-cloudflared.write",
                                      "account-settings.read", "user-details.read"], q
        code = uuid.uuid4().hex
        self.codes[code] = {"challenge": q["code_challenge"], "redirect_uri": q["redirect_uri"], "client_id": q["client_id"],
                            "email": self.user_email if email is None else email}
        return {"code": code, "state": q["state"]}


def make_oauth_handler(st):
    import base64
    import hashlib

    class O(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _out(self, code, obj):
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            n = int(self.headers.get("Content-Length") or 0)
            assert self.headers.get("Content-Type") == "application/x-www-form-urlencoded"
            f = {k: v[0] for k, v in urllib.parse.parse_qs(self.rfile.read(n).decode()).items()}
            st.calls.append(("POST", "oauth2" + self.path, f))
            if self.path == "/oauth2/token":
                c = st.codes.pop(f.get("code"), None)
                if not c or f.get("grant_type") != "authorization_code" or f.get("client_id") != c["client_id"] \
                        or f.get("redirect_uri") != c["redirect_uri"]:
                    return self._out(400, {"error": "invalid_grant"})
                v = f.get("code_verifier") or ""
                if base64.urlsafe_b64encode(hashlib.sha256(v.encode()).digest()).decode().rstrip("=") != c["challenge"]:
                    return self._out(400, {"error": "invalid_grant", "error_description": "PKCE verification failed"})
                tok = "oauth-" + uuid.uuid4().hex
                st.oauth_tokens[tok] = c["email"]
                return self._out(200, {"access_token": tok, "token_type": "bearer", "expires_in": 3600, "scope": "argotunnel.write"})
            if self.path == "/oauth2/revoke":
                st.revoked.append(f.get("token"))
                st.oauth_tokens.pop(f.get("token"), None)
                return self._out(200, {})
            self._out(404, {"error": "not_found"})
    return O


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
            auth = self.headers.get("Authorization") or ""
            if auth != "Bearer " + TOKEN and auth[7:] not in st.oauth_tokens:
                return self._out(403, None, [{"code": 10000, "message": "Authentication error"}])
            acc = "/accounts/%s" % ACCOUNT
            if path == "/user" and method == "GET":
                # User Details Read: the login's email (an API token stands for the account owner)
                return self._out(200, {"id": "u1", "email": st.oauth_tokens.get(auth[7:], st.user_email)})
            if path == "/accounts" and method == "GET":
                return self._out(200, [{"id": ACCOUNT, "name": st.account_name}])
            if path == "/zones" and method == "GET":
                names = [n for n in [st.zone] + st.more_zones if n]
                name = q.get("name", [None])[0]
                return self._out(200, [{"id": ZONE, "name": n, "account": {"id": ACCOUNT}} for n in names if name is None or n == name])
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


def start_oauth(st):
    """Cloudflare's OAuth endpoints (token, revoke; the consent page is State.consent)."""
    srv = ThreadingHTTPServer(("127.0.0.1", 0), make_oauth_handler(st))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, "http://127.0.0.1:%d/oauth2" % srv.server_address[1]
