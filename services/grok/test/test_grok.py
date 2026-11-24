#!/usr/bin/env python3
"""services/grok against local stand-ins only: a JWKS made here (Cloudflare Access's certificate
endpoint), a fake car app channel with a small Waze behind it (test/fake_carhost.py), a fake ATL
intent channel, a mock of Cloudflare's API (test/mock_cloudflare.py), and host/teslalinux-set-grok
writing into a temporary directory. Nothing here talks to Cloudflare or Grok. Needs
python3-cryptography.

    python3 services/grok/test/test_grok.py
"""
import base64
import json
import os
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
TMP = tempfile.mkdtemp(prefix="tl-grok-")

from cryptography.hazmat.primitives import hashes  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import padding, rsa  # noqa: E402

import fake_carhost  # noqa: E402
import mock_cloudflare  # noqa: E402


# ------------------------------------------------------------------ a team's signing keys

def b64u(b):
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def jwk(key, kid):
    n = key.public_key().public_numbers()
    return {"kid": kid, "kty": "RSA", "alg": "RS256", "use": "sig", "e": b64u(n.e.to_bytes(3, "big")),
            "n": b64u(n.n.to_bytes((n.n.bit_length() + 7) // 8, "big"))}


KEY1 = rsa.generate_private_key(public_exponent=65537, key_size=2048)
KEY2 = rsa.generate_private_key(public_exponent=65537, key_size=2048)
OTHER = rsa.generate_private_key(public_exponent=65537, key_size=2048)
JWKS = {"keys": [jwk(KEY1, "k1")], "public_cert": {}, "public_certs": []}
CERT_HITS = []


class Certs(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        CERT_HITS.append(self.path)
        if self.path != "/cdn-cgi/access/certs":
            self.send_response(404)
            self.end_headers()
            return
        body = json.dumps(JWKS).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


CERTS = ThreadingHTTPServer(("127.0.0.1", 0), Certs)
threading.Thread(target=CERTS.serve_forever, daemon=True).start()
TEAM = "http://127.0.0.1:%d" % CERTS.server_address[1]
AUD = "ab" * 32


def mint(claims=None, key=KEY1, kid="k1", alg="RS256", header=None):
    now = int(time.time())
    c = {"aud": [AUD], "email": "driver@example.com", "exp": now + 300, "iat": now, "nbf": now, "iss": TEAM,
         "type": "app", "sub": "user-1"}
    c.update(claims or {})
    h = {"alg": alg, "kid": kid, "typ": "JWT"}
    h.update(header or {})
    signing = b64u(json.dumps(h).encode()) + "." + b64u(json.dumps(c).encode())
    sig = key.sign(signing.encode(), padding.PKCS1v15(), hashes.SHA256()) if key else b""
    return signing + "." + b64u(sig)


# ------------------------------------------------------------------ the service, configured into TMP

CAR = fake_carhost.FakeCarHost().start()
INTENT = fake_carhost.FakeIntentChannel()
IFACE = os.path.join(TMP, "interface.env")
CONF = os.path.join(TMP, "grok.env")
SECRETS = os.path.join(TMP, "secret")
os.environ.update({
    "TESLALINUX_GROK_CONFIG": CONF, "TESLALINUX_GROK_SECRET_DIR": SECRETS, "TESLALINUX_GROK_DIR": os.path.join(TMP, "state"),
    "TESLALINUX_GROK_HELPER": os.path.join(REPO, "host", "teslalinux-set-grok"), "TESLALINUX_GROK_HELPER_SUDO": "0",
    "TESLALINUX_GROK_NO_SYSTEMCTL": "1", "TESLALINUX_GROK_CARAPP": CAR.addr, "TESLALINUX_GROK_INTENT": INTENT.addr,
    "TESLALINUX_GROK_INTERFACE_ENV": IFACE, "TESLALINUX_GROK_MCP_PORT": "0", "TESLALINUX_GROK_ADMIN_PORT": "0",
    "TESLALINUX_GROK_TUNNEL_METRICS": "http://127.0.0.1:9/ready",
})

import accessjwt  # noqa: E402
import cfsetup  # noqa: E402
import grok  # noqa: E402
import mcp  # noqa: E402
import waze  # noqa: E402

waze.STATE_QUIET_S = 0.15


def set_mode(m):
    with open(IFACE, "w") as f:
        f.write("TESLALINUX_APP_INTERFACE=%s\n" % m)


def configure(enabled=True):
    with open(CONF, "w") as f:
        f.write("TESLALINUX_GROK_ENABLED=%d\nTESLALINUX_GROK_HOSTNAME=mcp.example.com\nTESLALINUX_GROK_TEAM_DOMAIN=%s\n"
                "TESLALINUX_GROK_AUD=%s\n" % (1 if enabled else 0, TEAM, AUD))


MCP_SRV = grok.serve(0, grok.McpHandler)
ADMIN_SRV = grok.serve(0, grok.AdminHandler)
MCP_URL = "http://127.0.0.1:%d/mcp" % MCP_SRV.server_address[1]
ADMIN_URL = "http://127.0.0.1:%d" % ADMIN_SRV.server_address[1]


def post(body, token="default", headers=None, url=None, raw=None):
    h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    if token == "default":
        token = mint()
    if token:
        h["Cf-Access-Jwt-Assertion"] = token
    h.update(headers or {})
    data = raw if raw is not None else json.dumps(body).encode()
    req = urllib.request.Request(url or MCP_URL, data=data, method="POST", headers=h)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def rpc(method, params=None, mid=1, version=None, modern=False, **kw):
    params = dict(params or {})
    headers = dict(kw.pop("headers", {}))
    if modern:
        params["_meta"] = {mcp.META_VERSION: mcp.CURRENT, mcp.META_CAPS: {}, "io.modelcontextprotocol/clientInfo": {"name": "t", "version": "1"}}
        headers.setdefault("MCP-Protocol-Version", mcp.CURRENT)
        headers.setdefault("Mcp-Method", method)
        if method == "tools/call":
            headers.setdefault("Mcp-Name", params.get("name"))
    elif version:
        headers["MCP-Protocol-Version"] = version
    code, h, body = post({"jsonrpc": "2.0", "id": mid, "method": method, "params": params}, headers=headers, **kw)
    return code, (json.loads(body) if body else None)


def call(name, args, modern=False):
    code, r = rpc("tools/call", {"name": name, "arguments": args}, version="2025-06-18", modern=modern)
    assert code == 200, (code, r)
    res = r["result"]
    return res["content"][0]["text"], res.get("structuredContent"), res["isError"]


# ------------------------------------------------------------------ tests

class TestAccessJwt(unittest.TestCase):
    def setUp(self):
        self.keys = accessjwt.KeySet()

    def verify(self, tok, **kw):
        return accessjwt.verify(tok, kw.get("team", TEAM), kw.get("aud", AUD), self.keys)

    def test_valid(self):
        c = self.verify(mint())
        self.assertEqual(c["email"], "driver@example.com")
        self.verify(mint({"aud": AUD}))  # a string aud too

    def refused(self, tok, why, **kw):
        with self.assertRaises(accessjwt.TokenError) as cm:
            self.verify(tok, **kw)
        self.assertIn(why, str(cm.exception))

    def test_refusals(self):
        self.refused(mint(key=OTHER), "bad signature")
        self.refused(mint({"aud": ["cd" * 32]}), "another application")
        self.refused(mint({"iss": "https://evil.cloudflareaccess.com"}), "issuer")
        self.refused(mint({"exp": int(time.time()) - 600}), "expired")
        self.refused(mint({"nbf": int(time.time()) + 600}), "not valid yet")
        self.refused(mint(alg="HS256"), "not RS256")
        self.refused(mint(alg="none", key=None), "not RS256")
        self.refused(mint(kid="nope"), "unknown signing key")
        self.refused(mint({"exp": "soon"}), "no exp")
        self.refused("abc.def", "not a JWS")
        self.refused("", "no Cf-Access-Jwt-Assertion")
        self.refused("e30.e30.e30", "not RS256")
        self.refused(b64u(b'{"alg":"RS256","kid":"k1"}') + ".e30.!!!", "base64url")
        self.refused(mint(header={"crit": ["x"]}), "critical")
        self.refused(mint(), "not configured", aud="")
        # the configured team's keys only: a token naming another issuer is refused even when that
        # issuer would vouch for it (its keys are never fetched)
        self.refused(mint({"iss": "http://127.0.0.1:1"}), "issuer")

    def test_rotation_and_cache(self):
        hits = len(CERT_HITS)
        self.verify(mint())
        self.verify(mint())
        self.assertEqual(len(CERT_HITS), hits + 1, "the key set is cached")
        JWKS["keys"].append(jwk(KEY2, "k2"))
        try:
            # a new kid fetches again (Access rotated its keys)...
            self.keys._tried = 0
            self.verify(mint(key=KEY2, kid="k2"))
            # ... but an unknown kid does not hammer the endpoint
            n = len(CERT_HITS)
            for _ in range(3):
                with self.assertRaises(accessjwt.TokenError):
                    self.verify(mint(kid="k9"))
            self.assertLessEqual(len(CERT_HITS), n + 1)
        finally:
            JWKS["keys"] = [jwk(KEY1, "k1")]


class TestProtocol(unittest.TestCase):
    def setUp(self):
        configure()
        set_mode("car")
        CAR.reset()

    def test_auth_first(self):
        code, _, body = post({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, token=None)
        self.assertEqual(code, 401)
        code, _, _ = post({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, token=mint(key=OTHER))
        self.assertEqual(code, 403)
        code, _, _ = post({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, headers={"Origin": "https://evil.example"})
        self.assertEqual(code, 403)
        code, _, _ = post({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, headers={"Origin": "https://grok.com"})
        self.assertEqual(code, 200)
        configure(enabled=False)
        code, _, body = post({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        self.assertEqual(code, 503)
        self.assertIn("turned off", json.loads(body)["error"]["message"])

    def test_legacy_initialize(self):
        code, r = rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "grok", "version": "1"}})
        self.assertEqual(code, 200)
        self.assertEqual(r["result"]["protocolVersion"], "2025-06-18")
        self.assertIn("tools", r["result"]["capabilities"])
        self.assertEqual(r["result"]["serverInfo"]["name"], "teslalinux-waze")
        code, r = rpc("initialize", {"protocolVersion": "2024-11-05", "capabilities": {}})
        self.assertEqual(r["result"]["protocolVersion"], "2025-11-25")
        code, h, body = post({"jsonrpc": "2.0", "method": "notifications/initialized"}, headers={"MCP-Protocol-Version": "2025-06-18"})
        self.assertEqual((code, body), (202, b""))
        self.assertNotIn("Mcp-Session-Id", h)
        code, r = rpc("ping", version="2025-06-18")
        self.assertEqual(r["result"], {})

    def test_tools_list(self):
        code, r = rpc("tools/list", version="2025-11-25")
        names = [t["name"] for t in r["result"]["tools"]]
        self.assertEqual(names, ["navigate_to", "search_places", "trip_status", "stop_navigation"])
        for t in r["result"]["tools"]:
            self.assertTrue(t["description"] and len(t["description"]) < 320)
            self.assertEqual(t["inputSchema"]["type"], "object")
        code, r = rpc("tools/list", modern=True)
        self.assertEqual(code, 200)
        self.assertEqual(r["result"]["resultType"], "complete")
        self.assertIn(mcp.META_SERVER, r["result"]["_meta"])

    def test_modern_era(self):
        code, r = rpc("server/discover", modern=True)
        self.assertEqual(code, 200)
        self.assertIn(mcp.CURRENT, r["result"]["supportedVersions"])
        self.assertIn("tools", r["result"]["capabilities"])
        # header and body disagree
        code, r = rpc("tools/list", modern=True, headers={"MCP-Protocol-Version": "2025-06-18"})
        self.assertEqual((code, r["error"]["code"]), (400, mcp.HEADER_MISMATCH))
        code, r = rpc("tools/list", modern=True, headers={"Mcp-Method": "tools/call"})
        self.assertEqual((code, r["error"]["code"]), (400, mcp.HEADER_MISMATCH))
        # an unknown version
        code, r = rpc("tools/list", version="1900-01-01")
        self.assertEqual((code, r["error"]["code"]), (400, mcp.UNSUPPORTED_VERSION))
        self.assertIn(mcp.CURRENT, r["error"]["data"]["supported"])
        # the current version without its _meta
        code, r = rpc("tools/list", version=mcp.CURRENT)
        self.assertEqual((code, r["error"]["code"]), (400, mcp.INVALID_PARAMS))
        # unknown methods: 404 in the current era, a JSON-RPC error in the earlier ones
        code, r = rpc("resources/list", modern=True)
        self.assertEqual((code, r["error"]["code"]), (404, mcp.METHOD_NOT_FOUND))
        code, r = rpc("resources/list", version="2025-06-18")
        self.assertEqual((code, r["error"]["code"]), (200, mcp.METHOD_NOT_FOUND))

    def test_http_rules(self):
        req = urllib.request.Request(MCP_URL, method="GET", headers={"Cf-Access-Jwt-Assertion": mint()})
        with self.assertRaises(urllib.error.HTTPError) as cm:
            urllib.request.urlopen(req, timeout=5)
        self.assertEqual(cm.exception.code, 405)
        self.assertEqual(cm.exception.headers["Allow"], "POST")
        code, _, body = post(None, raw=b"{not json")
        self.assertEqual((code, json.loads(body)["error"]["code"]), (400, mcp.PARSE_ERROR))
        code, _, _ = post({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, headers={"Content-Type": "text/plain"})
        self.assertEqual(code, 415)
        code, _, _ = post({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, url=MCP_URL.replace("/mcp", "/other"))
        self.assertEqual(code, 404)
        # SSE for a client that takes only that
        code, h, body = post({"jsonrpc": "2.0", "id": 7, "method": "tools/list"}, headers={"Accept": "text/event-stream", "MCP-Protocol-Version": "2025-06-18"})
        self.assertEqual(code, 200)
        self.assertTrue(h["Content-Type"].startswith("text/event-stream"))
        self.assertTrue(body.startswith(b"event: message\ndata: {"))
        self.assertEqual(json.loads(body.split(b"data: ", 1)[1])["id"], 7)
        # batches: 2025-03-26 only
        code, _, body = post([{"jsonrpc": "2.0", "id": 1, "method": "ping"}, {"jsonrpc": "2.0", "method": "notifications/initialized"},
                              {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}])
        out = json.loads(body)
        self.assertEqual((code, [m["id"] for m in out]), (200, [1, 2]))
        code, _, body = post([{"jsonrpc": "2.0", "id": 1, "method": "ping"}], headers={"MCP-Protocol-Version": "2025-06-18"})
        self.assertEqual(code, 400)
        # a client's response to us (we send no requests): accepted
        code, _, body = post({"jsonrpc": "2.0", "id": 5, "result": {}}, headers={"MCP-Protocol-Version": "2025-06-18"})
        self.assertEqual(code, 202)

    def test_unknown_tool_and_bad_args(self):
        code, r = rpc("tools/call", {"name": "open_gate", "arguments": {}}, version="2025-06-18")
        self.assertEqual(r["error"]["code"], mcp.INVALID_PARAMS)
        text, _, err = call("navigate_to", {})
        self.assertTrue(err)
        self.assertIn("destination", text)
        text, _, err = call("navigate_to", {"latitude": 95, "longitude": 1})
        self.assertTrue(err)
        text, _, err = call("navigate_to", {"latitude": 32.1})
        self.assertTrue(err)


class TestWazeCar(unittest.TestCase):
    def setUp(self):
        configure()
        set_mode("car")
        CAR.reset()
        CAR.received.clear()
        CAR.refuse_navigate = None

    def test_navigate_status_stop(self):
        text, st, err = call("trip_status", {})
        self.assertFalse(err, text)
        self.assertEqual(text, "Waze is not navigating.")
        text, st, err = call("navigate_to", {"destination": "Dizengoff Center, Tel Aviv"}, modern=True)
        self.assertFalse(err, text)
        nav = [m for m in CAR.received if m["type"] == "navigate"][0]
        self.assertEqual(nav["uri"], "geo:0,0?q=Dizengoff%20Center%2C%20Tel%20Aviv")
        self.assertTrue(st["navigating"])
        self.assertIn("Arrival at", text)
        text, st, err = call("trip_status", {})
        self.assertEqual(st["distanceLeft"]["text"], "2.3 km")
        self.assertEqual(st["timeLeftMinutes"], 13)
        self.assertEqual(st["eta"]["local"], "11:36")
        self.assertEqual(st["nextTurn"], {"instruction": "Turn right onto Arlozorov", "maneuver": "turn right", "road": "Arlozorov",
                                          "in": {"value": 150, "unit": "m", "text": "150 m"}})
        text, st, err = call("stop_navigation", {})
        self.assertFalse(err, text)
        self.assertFalse(st["navigating"])
        text, st, err = call("stop_navigation", {})
        self.assertIn("not navigating", text)

    def test_coordinates_and_refusal(self):
        text, st, err = call("navigate_to", {"latitude": 32.0809, "longitude": 34.7806, "name": "Rabin Square"})
        self.assertFalse(err, text)
        self.assertEqual(st["sent"], "geo:32.0809,34.7806?q=32.0809,34.7806")
        call("stop_navigation", {})
        CAR.refuse_navigate = "the app's car session is not running yet"
        text, st, err = call("navigate_to", {"destination": "home"})
        self.assertTrue(err)
        self.assertIn("not running yet", text)

    def test_search_out_of_sight_and_navigate_result(self):
        watcher = waze.WebSocket(CAR.addr)   # the car page
        time.sleep(0.2)
        page = CAR.clients[-1]
        CAR.seen_by[id(page)] = []
        text, st, err = call("search_places", {"query": "gas station", "limit": 2})
        self.assertFalse(err, text)
        self.assertEqual([r["name"] for r in st["results"]], ["Dor Alon", "Paz"])
        self.assertEqual(st["results"][0]["address"], "37 Derech Shlomo, Tel Aviv")
        self.assertEqual(st["results"][1]["distance"]["text"], "1200 m")
        self.assertIn("not given", st["coordinates"])
        # the car page saw none of the search screens: only the map again on the release
        self.assertEqual(CAR.seen_by[id(page)], ["MapWithContentTemplate"])
        self.assertEqual(len(CAR.stack), 1, "the search screens were popped back")
        rid = st["results"][1]["resultId"]
        text, st, err = call("navigate_to", {"resultId": rid})
        self.assertFalse(err, text)
        self.assertEqual(st["destination"]["name"], "Paz")
        self.assertEqual(CAR.stack[-1][1], "NavigationTemplate")
        watcher.close()
        call("stop_navigation", {})
        text, st, err = call("navigate_to", {"resultId": "wz1.nonsense"})
        self.assertTrue(err)
        text, st, err = call("search_places", {"query": "pizza", "latitude": 1, "longitude": 2})
        self.assertTrue(err)
        self.assertIn("car's position", text)

    def test_holder_that_vanishes(self):
        s = waze.CarSession(CAR.addr)
        s.request({"type": "hold", "on": True})
        s.click(24)
        s.wait_template(lambda m: m.get("$") == "SearchTemplate", 3, "search")
        s.ws.sock.close()  # gone without releasing
        deadline = time.time() + 3
        while time.time() < deadline and len(CAR.stack) > 1:
            time.sleep(0.05)
        self.assertEqual(len(CAR.stack), 1)
        self.assertIsNone(CAR.holder)

    def test_action_log(self):
        call("trip_status", {})
        req = urllib.request.Request(ADMIN_URL + "/api/grok")
        with urllib.request.urlopen(req, timeout=5) as r:
            st = json.loads(r.read())
        last = st["actions"][0]
        self.assertEqual(last["tool"], "trip_status")
        self.assertEqual(last["who"], "driver@example.com")
        self.assertTrue(last["ok"])
        self.assertEqual(st["lastRequest"]["who"], "driver@example.com")
        self.assertEqual(st["endpoint"], "https://mcp.example.com/mcp")
        path = os.path.join(TMP, "state", "actions.jsonl")
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)


class TestWazePhone(unittest.TestCase):
    def setUp(self):
        configure()
        set_mode("phone")
        INTENT.requests.clear()

    def test_phone(self):
        text, st, err = call("navigate_to", {"destination": "Haifa port"})
        self.assertFalse(err, text)
        self.assertEqual(INTENT.requests[-1], {"type": "startActivity", "action": "android.intent.action.VIEW",
                                               "data": "waze://?q=Haifa%20port&navigate=yes"})
        call("navigate_to", {"latitude": 32.8, "longitude": 35.0})
        self.assertEqual(INTENT.requests[-1]["data"], "waze://?ll=32.8,35&navigate=yes")
        n = len(INTENT.requests)
        text, st, err = call("stop_navigation", {})
        self.assertTrue(err)
        self.assertIn("Car mode", text)
        self.assertEqual(len(INTENT.requests), n, "nothing sent that would not work")
        text, st, err = call("trip_status", {})
        self.assertTrue(err)
        self.assertIn("Car mode", text)
        text, st, err = call("search_places", {"query": "pizza"})
        self.assertTrue(err)
        INTENT.answer = {"ok": False, "error": "no activity of com.waze takes it"}
        try:
            text, st, err = call("navigate_to", {"destination": "x"})
            self.assertTrue(err)
            self.assertIn("no activity", text)
        finally:
            INTENT.answer = {"ok": True, "activity": "com.waze.MainActivity"}


class TestSetupAndHelper(unittest.TestCase):
    def test_cloudflare_setup_twice(self):
        st = mock_cloudflare.State()
        srv, base = mock_cloudflare.start(st)
        try:
            r = cfsetup.setup(mock_cloudflare.TOKEN, "example.com", "", "driver@example.com", base=base)
            self.assertEqual(r["hostname"], "mcp.example.com")
            self.assertEqual(r["teamDomain"], "https://myteam.cloudflareaccess.com")
            self.assertEqual(len(r["aud"]), 64)
            app = list(st.apps.values())[0]
            self.assertEqual(app["type"], "self_hosted")
            self.assertEqual(app["domain"], "mcp.example.com")
            self.assertTrue(app["oauth_configuration"]["enabled"])
            self.assertTrue(app["oauth_configuration"]["dynamic_client_registration"]["enabled"])
            self.assertEqual(app["oauth_configuration"]["dynamic_client_registration"]["allowed_uris"], ["https://grok.com/*"])
            self.assertEqual(app["policies"][0]["include"], [{"email": {"email": "driver@example.com"}}])
            self.assertEqual(app["policies"][0]["decision"], "allow")
            cfg = st.configs[r["tunnelId"]]["config"]["ingress"]
            self.assertEqual(cfg[0]["service"], "http://127.0.0.1:8083")
            self.assertEqual(cfg[0]["originRequest"]["access"], {"required": True, "teamName": "myteam", "audTag": [r["aud"]]})
            self.assertEqual(cfg[-1], {"service": "http_status:404"})
            rec = list(st.records.values())[0]
            self.assertEqual((rec["type"], rec["content"], rec["proxied"]), ("CNAME", r["tunnelId"] + ".cfargotunnel.com", True))
            # again: everything found and updated, nothing doubled, the AUD kept
            r2 = cfsetup.setup(mock_cloudflare.TOKEN, "example.com", "mcp.example.com", "driver@example.com", base=base)
            self.assertEqual((len(st.tunnels), len(st.apps), len(st.records)), (1, 1, 1))
            self.assertEqual((r2["aud"], r2["tunnelId"]), (r["aud"], r["tunnelId"]))
            # the token never goes anywhere but the Authorization header
            self.assertFalse(any(mock_cloudflare.TOKEN in json.dumps(c[2] or {}) for c in st.calls))
        finally:
            srv.shutdown()

    def test_cloudflare_setup_errors(self):
        st = mock_cloudflare.State(org=None)
        srv, base = mock_cloudflare.start(st)
        try:
            with self.assertRaises(cfsetup.SetupError) as cm:
                cfsetup.setup("wrong-token", "example.com", "", "d@example.com", base=base)
            self.assertIn("Authentication error", str(cm.exception))
            with self.assertRaises(cfsetup.SetupError) as cm:
                cfsetup.setup(mock_cloudflare.TOKEN, "other.org", "", "d@example.com", base=base)
            self.assertIn("not in this token's", str(cm.exception))
            with self.assertRaises(cfsetup.SetupError) as cm:
                cfsetup.setup(mock_cloudflare.TOKEN, "example.com", "", "d@example.com", base=base)
            self.assertIn("Zero Trust", str(cm.exception))
            st.org = "myteam"
            st.records["x"] = {"id": "x", "type": "A", "name": "mcp.example.com", "content": "1.2.3.4", "proxied": True}
            with self.assertRaises(cfsetup.SetupError) as cm:
                cfsetup.setup(mock_cloudflare.TOKEN, "example.com", "", "d@example.com", base=base)
            self.assertIn("not a tunnel's", str(cm.exception))
            for bad in (("example.com", "mcp.other.com", "d@example.com"), ("not a domain", "", "d@example.com"), ("example.com", "", "nobody")):
                with self.assertRaises(cfsetup.SetupError):
                    cfsetup.setup(mock_cloudflare.TOKEN, *bad, base=base)
        finally:
            srv.shutdown()

    def test_setup_route_stores_root_only(self):
        st = mock_cloudflare.State(idps=("cloudflare",))
        srv, base = mock_cloudflare.start(st)
        grok.CF_API = base
        try:
            req = urllib.request.Request(ADMIN_URL + "/api/grokSetup", method="POST", headers={"Content-Type": "application/json"},
                                         data=json.dumps({"domain": "example.com", "email": "driver@example.com", "apiToken": mock_cloudflare.TOKEN}).encode())
            with urllib.request.urlopen(req, timeout=30) as r:
                out = json.loads(r.read())
            self.assertTrue(out["ok"], out)
            self.assertIn("One-time PIN", out["note"])
            s = out["settings"]
            self.assertTrue(s["enabled"] and s["tokenSet"])
            self.assertEqual(s["hostname"], "mcp.example.com")
            secret = os.path.join(SECRETS, "tunnel.env")
            self.assertEqual(stat.S_IMODE(os.stat(secret).st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(os.stat(SECRETS).st_mode), 0o700)
            with open(secret) as f:
                self.assertTrue(f.read().startswith("# teslalinux"))
            with open(CONF) as f:
                conf = f.read()
            self.assertNotIn("TUNNEL_TOKEN", conf)
            self.assertNotIn(mock_cloudflare.TOKEN, conf)
            # the API token is nowhere on disk
            for root, _, files in os.walk(TMP):
                for fn in files:
                    with open(os.path.join(root, fn), "rb") as f:
                        self.assertNotIn(mock_cloudflare.TOKEN.encode(), f.read(), fn)
        finally:
            grok.CF_API = cfsetup.API
            srv.shutdown()
            configure()

    def helper(self, req):
        return subprocess.run([os.path.join(REPO, "host", "teslalinux-set-grok")], input=json.dumps(req), capture_output=True, text=True)

    def test_helper_checks(self):
        p = self.helper({"tunnelToken": "not-a-token-at-all-but-long-enough-xxxxxxxxxxxxxxx"})
        self.assertEqual(p.returncode, 2)
        tok = base64.b64encode(json.dumps({"a": "acc", "t": "c1744f8b-faa1-48a4-9e5c-02ac921467fa", "s": "c2VjcmV0"}).encode()).decode()
        p = self.helper({"tunnelToken": tok, "enabled": True, "hostname": "mcp.example.com", "teamDomain": "myteam", "aud": "AB" * 32})
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("the tunnel runs", p.stdout)
        cfg = grok.settings()
        self.assertEqual((cfg["tunnelId"], cfg["teamDomain"], cfg["aud"]),
                         ("c1744f8b-faa1-48a4-9e5c-02ac921467fa", "https://myteam.cloudflareaccess.com", "ab" * 32))
        for bad in ({"aud": "xyz"}, {"hostname": "not a host"}, {"teamDomain": "http://evil.example"}, {"email": "x"}):
            self.assertEqual(self.helper(bad).returncode, 2, bad)
        p = self.helper({"clearToken": True})
        self.assertIn("no token", p.stdout)
        self.assertFalse(os.path.exists(os.path.join(SECRETS, "tunnel.env")))
        configure()


if __name__ == "__main__":
    unittest.main(verbosity=2)
