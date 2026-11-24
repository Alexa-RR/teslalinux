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
sys.path.insert(0, os.path.join(REPO, "services", "gates"))
sys.path.insert(0, os.path.join(REPO, "services", "gates", "test"))
import mock_palgate  # noqa: E402
PALGATE = mock_palgate.MockPalGate().start()
GPS = fake_carhost.FakeGpsFeed()
STATUS = {"uplink": {"interface": "wwan0", "kind": "lte"}, "internet": {"reachable": True, "latencyMs": 48},
          "modem": {"operator": "Partner", "technology": "LTE", "signalPercent": 80, "state": "connected"},
          "hotspot": {"running": True, "clients": [{"mac": "aa"}]}, "failover": {"wifi": {"ssid": None}},
          "system": {"cpuTemperature": 51.2}}


class StatusApi(BaseHTTPRequestHandler):
    def do_GET(self):
        body = json.dumps(STATUS).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


STATUS_SRV = ThreadingHTTPServer(("127.0.0.1", 0), StatusApi)
threading.Thread(target=STATUS_SRV.serve_forever, daemon=True).start()
IFACE = os.path.join(TMP, "interface.env")
CONF = os.path.join(TMP, "grok.env")
SECRETS = os.path.join(TMP, "secret")
os.environ.update({
    "TESLALINUX_GROK_CONFIG": CONF, "TESLALINUX_GROK_SECRET_DIR": SECRETS, "TESLALINUX_GROK_DIR": os.path.join(TMP, "state"),
    "TESLALINUX_GROK_HELPER": os.path.join(REPO, "host", "teslalinux-set-grok"), "TESLALINUX_GROK_HELPER_SUDO": "0",
    "TESLALINUX_GROK_NO_SYSTEMCTL": "1", "TESLALINUX_GROK_CARAPP": CAR.addr, "TESLALINUX_GROK_INTENT": INTENT.addr,
    "TESLALINUX_GROK_INTERFACE_ENV": IFACE, "TESLALINUX_GROK_MCP_PORT": "0", "TESLALINUX_GROK_ADMIN_PORT": "0",
    "TESLALINUX_GROK_TUNNEL_METRICS": "http://127.0.0.1:9/ready", "TESLALINUX_GROK_APP_DIR": os.path.join(TMP, "app"),
    "TESLALINUX_GROK_GPS_FEED": GPS.addr, "TESLALINUX_GROK_API_STATUS": "http://127.0.0.1:%d/api/status" % STATUS_SRV.server_address[1],
    # the gates service (services/gates) in this process, against its own PalGate mock
    "TESLALINUX_GATES_DIR": os.path.join(TMP, "gates"), "TESLALINUX_GATES_API": PALGATE.base, "TESLALINUX_GATES_GPS_FEED": "",
})
os.makedirs(os.path.join(TMP, "app"))
with open(os.path.join(TMP, "app", "installed.json"), "w") as f:
    f.write('{"package":"com.waze","versionCode":1030750,"versionName":"5.27.5.0"}\n')

import accessjwt  # noqa: E402
import cfoauth  # noqa: E402
import cfsetup  # noqa: E402
import urllib.parse  # noqa: E402
import grok  # noqa: E402
import mcp  # noqa: E402
import waze  # noqa: E402



def set_mode(m):
    with open(IFACE, "w") as f:
        f.write("TESLALINUX_APP_INTERFACE=%s\n" % m)


def configure(enabled=True, gates=False):
    with open(CONF, "w") as f:
        f.write("TESLALINUX_GROK_ENABLED=%d\nTESLALINUX_GROK_HOSTNAME=mcp.example.com\nTESLALINUX_GROK_TEAM_DOMAIN=%s\n"
                "TESLALINUX_GROK_AUD=%s\n%s" % (1 if enabled else 0, TEAM, AUD, "TESLALINUX_GROK_GATES=1\n" if gates else ""))


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
        self.assertEqual(names, ["navigate_to", "search_places", "place_details", "add_stop", "remove_stop", "trip_status",
                                 "stop_navigation", "report", "report_types", "route_options", "choose_route", "start_drive",
                                 "set_avoid_tolls", "set_avoid_ferries", "set_avoid_freeways", "set_sound", "mute_voice",
                                 "unmute_voice", "zoom_in", "zoom_out", "recenter", "answer_alert", "recent_places",
                                 "saved_places", "next_turn", "current_speed", "waze_alerts", "current_screen",
                                 "car_position", "device_status", "list_gates", "open_gate"])
        self.assertEqual(len(set(names)), len(names))
        for t in r["result"]["tools"]:
            self.assertTrue(t["description"] and len(t["description"]) < 320, t["name"])
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
        code, r = rpc("tools/call", {"name": "unlock_doors", "arguments": {}}, version="2025-06-18")
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
        text, st, err = call("navigate_to", {"destination": "Haifa"})
        self.assertTrue(err)
        self.assertIn("not running yet", text)

    def test_search_out_of_sight_and_navigate_result(self):
        watcher = waze.WebSocket(CAR.addr)   # the car page
        time.sleep(0.2)
        page = CAR.clients[-1]
        CAR.seen_by[id(page)] = []
        text, st, err = call("search_places", {"query": "gas station", "limit": 2})
        self.assertFalse(err, text)
        self.assertNotIn("address", st["results"][0], "the list alone, no cards")
        text, st, err = call("search_places", {"query": "gas station", "limit": 2, "addresses": True})
        self.assertFalse(err, text)
        self.assertEqual([r["name"] for r in st["results"]], ["Dor Alon", "Paz"])
        self.assertEqual(st["results"][0]["address"], "37 Derech Shlomo, Tel Aviv")
        text, det, err = call("place_details", {"resultId": st["results"][0]["resultId"]})
        self.assertFalse(err, text)
        self.assertEqual((det["name"], det["address"], det["actions"]), ("Dor Alon", "37 Derech Shlomo, Tel Aviv", ["Go now"]))
        self.assertIn("Location: 37 Derech Shlomo", text)
        self.assertEqual(st["results"][1]["distance"]["text"], "1200 m")
        self.assertIn("not given", st["coordinates"])
        # the car page saw none of the search screens: only the map again on the release
        self.assertTrue(CAR.seen_by[id(page)] and set(CAR.seen_by[id(page)]) == {"MapWithContentTemplate"}, CAR.seen_by[id(page)])
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

    def test_stops(self):
        text, st, err = call("add_stop", {"place": "gas station"})
        self.assertTrue(err)
        self.assertIn("only during a drive", text)
        text, st, err = call("navigate_to", {"destination": "Eilat"})
        self.assertFalse(err, text)
        text, st, err = call("add_stop", {"place": "gas station"})
        self.assertFalse(err, text)
        self.assertEqual(st["stop"]["name"], "Dor Alon")
        self.assertEqual((st["added"], st["stops"], st["tripText"]), (True, 1, "1 stop"))
        self.assertEqual(CAR.stops, ["Dor Alon"])
        self.assertEqual(len(CAR.stack), 1, "Waze is back on its navigation screen")
        text, st, err = call("trip_status", {})
        self.assertEqual((st["stops"], st["nextStop"], st["estimatesTo"]), (1, {"name": "Dor Alon"}, "the next stop"))
        self.assertEqual(st["destination"]["name"], "Eilat")
        self.assertIn("Next stop: Dor Alon", text)
        text, st, err = call("add_stop", {"place": "pizza"})
        self.assertTrue(err)
        self.assertIn("one stop per drive", text)
        text, st, err = call("remove_stop", {})
        self.assertTrue(err)
        self.assertIn("no way to remove", text)
        call("stop_navigation", {})
        # several places: the last is the destination, the first goes in as the stop, the rest is refused
        text, st, err = call("navigate_to", {"destinations": [{"destination": "gas station"}, {"destination": "pizza"},
                                                              {"latitude": 29.55, "longitude": 34.95, "name": "Eilat port"}]})
        self.assertFalse(err, text)
        self.assertEqual(st["stopsAdded"], ["Dor Alon"])
        self.assertEqual([k["place"] for k in st["notAdded"]], ["pizza"])
        self.assertIn("one stop per drive", st["notAdded"][0]["why"])
        self.assertIn("Not added: pizza", text)
        call("stop_navigation", {})
        set_mode("phone")
        text, st, err = call("add_stop", {"place": "gas station"})
        self.assertIn("needs Car mode", text)
        text, st, err = call("navigate_to", {"destinations": [{"destination": "a"}, {"destination": "b"}]})
        self.assertIn("need Car mode", text)
        set_mode("car")

    def test_places_through_the_tools(self):
        # nothing is fetched for a link with coordinates: the geo: URI carries them; the result says how
        text, st, err = call("navigate_to", {"link": "https://waze.com/ul/hsv2g9qef9"})
        self.assertFalse(err, text)
        self.assertEqual(st["sent"], "geo:30.182641,34.864833?q=30.182641,34.864833")
        self.assertEqual(st["understood"]["source"], "Waze link")
        self.assertIn("Read as Waze link (the location code after /ul/h): 30.182641, 34.864833.", text)
        call("stop_navigation", {})
        text, st, err = call("navigate_to", {"destination": "https://www.google.com/maps/search/?api=1&query=Eilat"})
        self.assertEqual(st["sent"], "geo:0,0?q=Eilat")
        self.assertEqual(st["understood"], {"source": "Google Maps link", "how": "its query= text", "query": "Eilat"})
        text, st, err = call("add_stop", {"place": "https://waze.com/ul?q=gas%20station"})
        self.assertFalse(err, text)
        self.assertEqual(st["stop"]["name"], "Dor Alon")
        call("stop_navigation", {})
        text, st, err = call("navigate_to", {"destination": "32°04'51.2\"N 34°46'50.2\"E"})
        self.assertEqual(st["sent"], "geo:32.080889,34.780611?q=32.080889,34.780611")
        call("stop_navigation", {})
        text, st, err = call("navigate_to", {"destination": "home"})
        self.assertTrue(err)
        self.assertIn("no Home place set", text)
        text, st, err = call("navigate_to", {"destination": "my last destination"})
        self.assertFalse(err, text)
        self.assertEqual(st["understood"]["source"], "Waze's recent places")
        self.assertEqual(st["place"], "Dor Alon")
        call("stop_navigation", {})
        text, st, err = call("navigate_to", {"destination": "///filled.count.soap"})
        self.assertIn("not supported", text)
        text, st, err = call("navigate_to", {"link": "https://waze.com/ul?ll=1,2", "destination": "x"})
        self.assertIn("not both", text)
        set_mode("phone")
        text, st, err = call("navigate_to", {"link": "https://waze.com/ul/hsv2g9qef9"})
        self.assertEqual(st["sent"], "waze://?ll=30.182641,34.864833&navigate=yes")
        text, st, err = call("navigate_to", {"destination": "home"})
        self.assertIn("Car mode", text)
        set_mode("car")

    def test_holder_that_vanishes(self):
        ws = waze.WebSocket(CAR.addr)   # a holder of its own, apart from the service's link
        ws.send_text(json.dumps({"type": "hold", "on": True, "id": 1}))
        ws.send_text(json.dumps({"type": "click", "cb": 24}))
        deadline = time.time() + 3
        while time.time() < deadline and CAR.stack[-1][1] != "SearchTemplate":
            time.sleep(0.05)
        self.assertEqual(CAR.stack[-1][1], "SearchTemplate")
        ws.sock.close()  # gone without releasing
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


class FakeRedirects:
    """An opener for maplinks.follow: short link -> its target, as the map sites answer a HEAD."""

    def __init__(self, hops):
        self.hops, self.asked = hops, []

    def open(self, req, timeout=None):
        self.asked.append((req.get_method(), req.full_url, timeout, req.get_header("Cookie")))
        if req.full_url in self.hops:
            raise urllib.error.HTTPError(req.full_url, 302, "Found", {"Location": self.hops[req.full_url]}, None)
        raise AssertionError("fetched a URL that is not a short link: " + req.full_url)


class TestPlaces(unittest.TestCase):
    def where(self, text, near=None):
        import locate
        r = locate.resolve(text, near)
        return {k: r[k] for k in ("latitude", "longitude", "query", "list", "source") if k in r}

    def test_coordinates(self):
        ll = {"latitude": 32.0809, "longitude": 34.7806, "source": "decimal degrees"}
        for t in ("32.0809, 34.7806", "32.0809 34.7806", "32.0809N 34.7806E", "N32.0809 E34.7806", "32.0809° N, 34.7806° E",
                  "34.7806E, 32.0809N"):
            self.assertEqual(self.where(t), ll, t)
        self.assertEqual(self.where("32°04.854'N, 34°46.836'E"), dict(ll, source="degrees and minutes"))
        dms = {"latitude": 32.0808889, "longitude": 34.7806111, "source": "degrees, minutes and seconds"}
        self.assertEqual(self.where("32°04'51.2\"N 34°46'50.2\"E"), dms)
        self.assertEqual(self.where("32 4 51.2 N 34 46 50.2 E"), dms)
        self.assertEqual(self.where("33° 52' S, 151° 12' E"), {"latitude": -33.8666667, "longitude": 151.2, "source": "degrees and minutes"})
        # longitude first only when the numbers leave no doubt
        self.assertEqual(self.where("151.2093, -33.8688"), {"latitude": -33.8688, "longitude": 151.2093, "source": "decimal degrees"})
        self.assertEqual(self.where("34.78, 32.08"), {"latitude": 34.78, "longitude": 32.08, "source": "decimal degrees"})
        self.assertEqual(self.where("32 61 0 N 34 0 0 E"), {"query": "32 61 0 N 34 0 0 E", "source": "an address or place name"})

    def test_codes_and_grids(self):
        self.assertEqual(self.where("8G4P9VJ2+QR"), {"latitude": 32.3819375, "longitude": 34.8520625, "source": "Plus Code"})
        self.assertEqual(self.where("9VJ2+QR", near=(32.08, 34.78)), {"latitude": 32.3819375, "longitude": 34.8520625, "source": "Plus Code (short)"})
        with self.assertRaises(waze.WazeError):
            self.where("9VJ2+QR")
        self.assertEqual(self.where("sv2g9qef9"), {"latitude": 30.1826406, "longitude": 34.8648334, "source": "geohash"})
        # ITM, checked against PROJ's EPSG:2039 (within 2 m)
        for t in ("180000 665000", "665000, 180000", "ITM 180000/665000"):
            r = self.where(t)
            self.assertEqual(r["source"], "Israeli grid (ITM)")
            self.assertAlmostEqual(r["latitude"], 32.077602, delta=0.00002)
            self.assertAlmostEqual(r["longitude"], 34.786538, delta=0.00002)
        with self.assertRaises(waze.WazeError) as e:
            self.where("///filled.count.soap")
        self.assertIn("what3words", str(e.exception))

    def test_links(self):
        cases = {
            "https://waze.com/ul/hsv2g9qef9": (30.1826406, 34.8648334, "Waze link"),
            "waze://?ll=29.55,34.95&navigate=yes": (29.55, 34.95, "Waze link"),
            "https://www.waze.com/live-map/directions?to=ll.30.18264%2C34.86483": (30.18264, 34.86483, "Waze link"),
            "https://www.google.com/maps/place/Azrieli/@32.0742,34.7922,17z/data=!3m1!4b1!8m2!3d32.0745!4d34.7915": (32.0745, 34.7915, "Google Maps link"),
            "https://www.google.com/maps/@32.0742,34.7922,15z": (32.0742, 34.7922, "Google Maps link"),
            "https://maps.google.com/?q=32.0742,34.7922": (32.0742, 34.7922, "Google Maps link"),
            "https://www.google.com/maps/search/?api=1&query=32.07,34.79": (32.07, 34.79, "Google Maps link"),
            "https://www.google.com/maps/dir/?api=1&destination=32.07,34.79": (32.07, 34.79, "Google Maps link"),
            "https://www.google.com/maps/dir/32.1,34.8/32.07,34.79/@32.4,34.9,9z": (32.07, 34.79, "Google Maps link"),
            "https://maps.apple.com/?ll=32.08,34.78&q=Rabin%20Square": (32.08, 34.78, "Apple Maps link"),
            "https://maps.apple.com/?coordinate=32.08,34.78": (32.08, 34.78, "Apple Maps link"),
            "https://maps.apple.com/?daddr=32.08,34.78": (32.08, 34.78, "Apple Maps link"),
            "https://www.openstreetmap.org/?mlat=32.08&mlon=34.78#map=17/32.1/34.7": (32.08, 34.78, "OpenStreetMap link"),
            "https://www.openstreetmap.org/#map=16/32.0809/34.7806": (32.0809, 34.7806, "OpenStreetMap link"),
            "https://www.bing.com/maps?cp=32.08~34.78&lvl=15": (32.08, 34.78, "Bing Maps link"),
            "https://www.bing.com/maps?where1=32.08,34.78": (32.08, 34.78, "Bing Maps link"),
            "https://share.here.com/l/32.08,34.78,Rabin%20Square": (32.08, 34.78, "HERE link"),
            "geo:32.08,34.78?q=32.08,34.78(Rabin Square)": (32.08, 34.78, "geo: URI"),
            "geo:32.08,34.78": (32.08, 34.78, "geo: URI"),
        }
        for link, (lat, lon, src) in cases.items():
            self.assertEqual(self.where(link), {"latitude": lat, "longitude": lon, "source": src}, link)
        names = {"waze://?q=Dizengoff%20Center": "Dizengoff Center", "https://www.google.com/maps/search/?api=1&query=Some+Cafe": "Some Cafe",
                 "https://www.google.com/maps/dir/Tel+Aviv/Haifa+Port/@32.4,34.9,9z": "Haifa Port",
                 "https://www.google.com/maps/place/Cafe+Xoho/": "Cafe Xoho",
                 "https://maps.apple.com/?address=66+Allenby,+Tel+Aviv": "66 Allenby, Tel Aviv", "https://maps.apple.com/?daddr=Dizengoff+Center": "Dizengoff Center",
                 "https://www.bing.com/maps?where1=Haifa%20Port": "Haifa Port", "geo:0,0?q=Dizengoff%20Center": "Dizengoff Center"}
        for link, q in names.items():
            self.assertEqual(self.where(link)["query"], q, link)
        for bad, why in (("https://waze.com/ul?place=ChIJabc", "Waze's own id"), ("https://evil.com/maps?ll=1,2", "not one this device reads"),
                         ("https://waze.com/ul/hsv2!", "geohash"), ("https://waze.com/ul?navigate=yes", "no place")):
            with self.assertRaises(waze.WazeError) as e:
                self.where(bad)
            self.assertIn(why, str(e.exception))

    def test_short_links_followed(self):
        import maplinks
        op = FakeRedirects({"https://maps.app.goo.gl/AbC123": "https://www.google.com/maps/place/X/data=!3d32.0745!4d34.7915"})
        r = maplinks.parse("https://maps.app.goo.gl/AbC123", opener=op)
        self.assertEqual((r["latitude"], r["longitude"]), (32.0745, 34.7915))
        self.assertIn("after following the short link", r["how"])
        self.assertEqual(op.asked, [("HEAD", "https://maps.app.goo.gl/AbC123", 5.0, None)])
        # a short link leading anywhere but a map site is refused, not fetched
        op = FakeRedirects({"https://maps.app.goo.gl/Evil": "https://evil.example/x"})
        r = None
        with self.assertRaises(waze.WazeError) as e:
            maplinks.parse("https://maps.app.goo.gl/Evil", opener=op)
        self.assertIn("not one this device reads", str(e.exception))
        self.assertEqual(len(op.asked), 1)
        loop = FakeRedirects({"https://goo.gl/maps/a": "https://goo.gl/maps/b", "https://goo.gl/maps/b": "https://goo.gl/maps/a"})
        with self.assertRaises(waze.WazeError) as e:
            maplinks.parse("https://goo.gl/maps/a", opener=loop)
        self.assertIn("too many", str(e.exception))
        self.assertEqual(len(loop.asked), 5)

    def test_names(self):
        self.assertEqual(self.where("home"), {"list": "home", "source": "Waze's Home"})
        self.assertEqual(self.where("בית"), {"list": "home", "source": "Waze's Home"})
        self.assertEqual(self.where("Work."), {"list": "work", "source": "Waze's Work"})
        self.assertEqual(self.where("my last destination"), {"list": "recent", "source": "Waze's recent places"})
        self.assertEqual(self.where("דיזנגוף סנטר"), {"query": "דיזנגוף סנטר", "source": "an address or place name"})
        self.assertEqual(self.where("Dizengoff 50, Tel Aviv"), {"query": "Dizengoff 50, Tel Aviv", "source": "an address or place name"})


class TestGates(unittest.TestCase):
    """list_gates and open_gate through the real gates service, against its PalGate mock."""

    @classmethod
    def setUpClass(cls):
        import gates
        gates.GATES = gates.Gates()
        cls.srv = gates.ThreadingHTTPServer(("127.0.0.1", 0), gates.Handler)
        cls.srv.daemon_threads = True
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        import gatectl
        gatectl.GATES_URL = "http://127.0.0.1:%d" % cls.srv.server_address[1]
        cls.gates = gates

    def post(self, route, body):
        r = urllib.request.Request("http://127.0.0.1:%d/api/%s" % (self.srv.server_address[1], route), data=json.dumps(body).encode(),
                                   method="POST", headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(r, timeout=20) as resp:
            return json.loads(resp.read())

    def test_gates(self):
        configure()
        text, st, err = call("list_gates", {})
        self.assertTrue(err)
        self.assertIn("No PalGate account is linked", text)
        # link as the phone app would (the mock approves), then the gate list
        self.post("gatesLink", {"action": "start"})
        deadline = time.time() + 10
        while not PALGATE.pending and time.time() < deadline:
            time.sleep(0.05)
        PALGATE.approve(PALGATE.pending)
        while not self.gates.GATES.gates and time.time() < deadline:
            time.sleep(0.05)
        self.post("gatesSettings", {"gates": [{"id": "4G600002", "label": "Office barrier"}]})
        text, st, err = call("list_gates", {})
        self.assertFalse(err, text)
        self.assertEqual([g["id"] for g in st["gates"]], ["4G600001", "4G600002"], "the Bluetooth-only door is not shown")
        self.assertEqual(st["gates"][1]["name"], "Office barrier")
        self.assertEqual(st["gates"][0]["outputs"], [1, 2])
        self.assertIn("2 (Pedestrian door)", text)
        # opening: the switch, then confirm, then PalGate's own answer
        text, st, err = call("open_gate", {"gate": "Office barrier", "confirm": True})
        self.assertTrue(err)
        self.assertIn("Let Grok open gates", text)
        configure(gates=True)
        text, st, err = call("open_gate", {"gate": "Office barrier", "confirm": False})
        self.assertIn("confirm: true", text)
        self.assertEqual(PALGATE.opens, [])
        text, st, err = call("open_gate", {"gate": "office barrier", "confirm": True})
        self.assertFalse(err, text)
        self.assertEqual((st["opened"], st["palgate"]), (True, "Opened"))
        text, st, err = call("open_gate", {"gate": "4G600001", "output": 2, "confirm": True})
        self.assertFalse(err, text)
        self.assertEqual(PALGATE.opens[-2:], [("4G600002", 1), ("4G600001", 2)])
        text, st, err = call("open_gate", {"gate": "4G600002", "output": 2, "confirm": True})
        self.assertIn("output 1 only", text)
        text, st, err = call("open_gate", {"gate": "Bluetooth door", "confirm": True})
        self.assertIn("No gate shown on the map", text)
        self.gates.GATES.opening.clear()  # the service's 3 s repeat guard
        PALGATE.fail_open = ("device error", "check device")
        text, st, err = call("open_gate", {"gate": "4G600001", "confirm": True})
        PALGATE.fail_open = None
        self.assertTrue(err)
        self.assertIn("did not open", text)
        # every call is in the log (Settings > Grok)
        with open(os.path.join(TMP, "state", "actions.jsonl")) as f:
            log = [json.loads(line) for line in f]
        self.assertEqual(len([e for e in log if e["tool"] == "open_gate"]), 7)
        configure()


class TestControls(unittest.TestCase):
    def setUp(self):
        configure()
        set_mode("car")
        CAR.reset()
        CAR.received.clear()
        CAR.refuse_navigate = None
        CAR.avoid, CAR.sound, CAR.zooms = [False, False, False], 0, []

    def tearDown(self):
        call("stop_navigation", {})

    def drive(self):
        text, st, err = call("navigate_to", {"destination": "Eilat"})
        self.assertFalse(err, text)

    def test_routes_and_options(self):
        text, st, err = call("route_options", {})
        self.assertTrue(err)
        self.assertIn("route preview", text)
        self.drive()
        text, st, err = call("route_options", {})
        self.assertFalse(err, text)
        self.assertEqual(st["routes"][1], {"route": 2, "selected": False, "minutes": 94, "time": "94 min", "distance": "136.3 km", "via": "40; 12"})
        self.assertTrue(st["routes"][0]["selected"])
        self.assertEqual(len(CAR.stack), 1, "back on the drive")
        text, st, err = call("choose_route", {"route": 2})
        self.assertFalse(err, text)
        self.assertEqual(CAR.chosen, 1)
        text, st, err = call("choose_route", {"route": 3})
        self.assertIn("there is no route 3", text)
        text, st, err = call("set_avoid_tolls", {"avoid": True})
        self.assertFalse(err, text)
        self.assertEqual((CAR.avoid, st["changed"]), ([True, False, False], True))
        text, st, err = call("set_avoid_tolls", {"avoid": True})
        self.assertIn("already on", text)
        text, st, err = call("set_avoid_freeways", {"avoid": False})
        self.assertIn("already off", text)
        self.assertEqual(len(CAR.stack), 1)

    def test_sound_zoom_and_screen(self):
        self.drive()
        text, st, err = call("mute_voice", {})
        self.assertFalse(err, text)
        self.assertEqual(CAR.sound, 3)
        text, st, err = call("current_screen", {})
        self.assertIn("sound (off)", st["controls"])
        text, st, err = call("set_sound", {"mode": "only alerts"})
        self.assertEqual(CAR.sound, 2)
        text, st, err = call("set_sound", {"mode": "loud"})
        self.assertIn("Normal, Less chatty, Only alerts, Off", text)
        text, st, err = call("unmute_voice", {})
        self.assertEqual(CAR.sound, 0)
        text, st, err = call("zoom_in", {"steps": 2})
        self.assertFalse(err, text)
        self.assertEqual(CAR.zooms, ["open", "in", "in"])
        text, st, err = call("recenter", {})
        self.assertIn("already follows", text)

    def test_reads(self):
        text, st, err = call("current_speed", {})
        self.assertFalse(st["known"])
        CAR.state({"type": "speedometer", "speed": 72.4, "units": "km/h", "speedLimit": 80, "speedKph": 72.4, "miles": False})
        time.sleep(0.2)
        text, st, err = call("current_speed", {})
        self.assertEqual((st["speed"], st["speedLimit"], st["unit"]), (72, 80, "km/h"))
        CAR.state({"type": "alert", "alert": {"$": "Alert", "alertId": 9, "title": "Police reported ahead", "durationMillis": 10000,
                                              "actions": [fake_carhost.action(801, title="Still there"), fake_carhost.action(802, title="Not there")]}})
        time.sleep(0.2)
        text, st, err = call("waze_alerts", {})
        self.assertEqual(st["alerts"][0]["title"], "Police reported ahead")
        self.assertEqual(st["alerts"][0]["buttons"], ["Still there", "Not there"])
        text, st, err = call("answer_alert", {"answer": "maybe"})
        self.assertIn("Still there, Not there", text)
        text, st, err = call("answer_alert", {"answer": "still there"})
        self.assertFalse(err, text)
        self.assertIn({"type": "click", "cb": 801}, CAR.received)
        self.drive()
        text, st, err = call("next_turn", {})
        self.assertEqual(st["next"]["maneuver"], "turn right")
        self.assertIn("Turn right onto Arlozorov in 150 m", text)
        text, st, err = call("car_position", {})
        self.assertEqual((st["latitude"], st["longitude"], st["headingDegrees"], st["speedKmh"]), (29.558, 34.951, 172, 90))
        text, st, err = call("device_status", {})
        self.assertEqual((st["connection"], st["lte"]["signalPercent"], st["carHotspot"]["clients"]), ("lte", 80, 1))
        self.assertIn("LTE signal 80%", text)
        set_mode("phone")
        for tool in ("current_speed", "next_turn", "route_options", "mute_voice", "recent_places"):
            text, st, err = call(tool, {})
            self.assertTrue(err, tool)
            self.assertIn("Car mode", text)
        set_mode("car")


class TestReports(unittest.TestCase):
    def setUp(self):
        configure()
        set_mode("car")
        CAR.tracking = True
        CAR.reset()
        CAR.reports.clear()
        waze.flows.forget("reports")

    def test_types_read_from_waze(self):
        text, st, err = call("report_types", {})
        self.assertFalse(err, text)
        ids = [c["id"] for c in st["categories"]]
        self.assertEqual(ids, ["traffic", "police", "hazard", "closure", "fail"], "Waze's debug entry is not a report")
        self.assertEqual(st["categories"][1]["kinds"], [{"id": "police", "title": "Police"}, {"id": "police_mobile_camera", "title": "Mobile camera"}])
        self.assertIn("Mobile camera (police_mobile_camera)", text)
        self.assertEqual(st["categories"][3]["asks"], {"question": "Closure Report a closure on your route", "answers": ["Report", "Cancel"]})
        self.assertEqual(len(CAR.stack), 1, "back on the map")
        self.assertEqual(CAR.reports, [])

    def test_report_out_of_sight(self):
        watcher = waze.WebSocket(CAR.addr)
        time.sleep(0.2)
        page = CAR.clients[-1]
        CAR.seen_by[id(page)] = []
        text, st, err = call("report", {"category": "Police", "kind": "Mobile camera"})
        self.assertFalse(err, text)
        self.assertEqual(CAR.reports, ["Mobile camera"])
        self.assertTrue(st["reported"])
        self.assertEqual((st["category"], st["kind"], st["waze"]), ("police", "police_mobile_camera", "Reported"))
        self.assertTrue(all(k == "MapWithContentTemplate" for k in CAR.seen_by[id(page)]), CAR.seen_by[id(page)])
        self.assertNotIn("GridTemplate", json.dumps(CAR.seen_by[id(page)]))
        watcher.close()
        # by id, and the kind left out where it is the category itself
        text, st, err = call("report", {"category": "hazard", "kind": "hazard_pothole"})
        self.assertFalse(err, text)
        text, st, err = call("report", {"category": "police"})
        self.assertFalse(err, text)
        self.assertEqual(CAR.reports, ["Mobile camera", "Pothole", "Police"])
        _, r = rpc("tools/list", version="2025-06-18")
        self.assertIn("report", [t["name"] for t in r["result"]["tools"]])

    def test_refusals_and_questions(self):
        text, st, err = call("report", {"category": "Traffic"})
        self.assertTrue(err)
        self.assertIn("Heavy (traffic_heavy)", text)
        text, st, err = call("report", {"category": "UFO", "kind": "x"})
        self.assertTrue(err)
        self.assertIn("no report category 'UFO'", text)
        text, st, err = call("report", {"category": "Hazard", "kind": "Volcano"})
        self.assertTrue(err)
        self.assertIn("Pothole (hazard_pothole)", text)
        # a kind that asks one more question with no primary answer: nothing is sent until it is answered
        text, st, err = call("report", {"category": "Hazard", "kind": "Object"})
        self.assertTrue(err)
        self.assertIn("Where is it?", text)
        self.assertIn("In lane, On shoulder", text)
        self.assertEqual(CAR.reports, [])
        self.assertEqual(len(CAR.stack), 1)
        text, st, err = call("report", {"category": "Hazard", "kind": "Object", "option": "on shoulder"})
        self.assertFalse(err, text)
        self.assertEqual(CAR.reports, ["Object on shoulder"])
        self.assertEqual(st["title"], "Hazard: Object (On shoulder)")
        # a category that is a confirmation of its own (a closure while driving): the request confirms it
        text, st, err = call("report", {"category": "closure"})
        self.assertFalse(err, text)
        self.assertEqual(CAR.reports[-1], "Closure")
        self.assertEqual(st["title"], "Closure: Closure")
        text, st, err = call("report", {"category": "Closure", "option": "Cancel"})
        self.assertEqual(CAR.reports[-1], "Closure", "cancelled: nothing more sent")
        self.assertIsNone(st["reported"])
        # Waze's own failure
        text, st, err = call("report", {"category": "Fail"})
        self.assertTrue(err)
        self.assertIn("Couldn't send your report", text)
        self.assertEqual(len(CAR.stack), 1)
        # the debug entry is not offered
        text, st, err = call("report", {"category": "Debug", "kind": "Send logs"})
        self.assertTrue(err)

    def test_center_on_me_first(self):
        CAR.tracking = False
        CAR.reset()
        text, st, err = call("report", {"category": "Police"})
        self.assertFalse(err, text)
        self.assertEqual(CAR.reports, ["Police"])
        self.assertTrue(CAR.tracking, "Waze's center-on-me was tapped first")

    def test_phone_mode_and_log(self):
        set_mode("phone")
        text, st, err = call("report", {"category": "Police"})
        self.assertTrue(err)
        self.assertIn("Car mode", text)
        set_mode("car")
        call("report", {"category": "Police", "kind": "police"})
        with urllib.request.urlopen(ADMIN_URL + "/api/grok", timeout=5) as r:
            last = json.loads(r.read())["actions"][0]
        self.assertEqual((last["tool"], last["args"], last["ok"]), ("report", {"category": "Police", "kind": "police"}, True))
        self.assertIn("Reported Police: Police", last["result"])


class TestConnectCloudflare(unittest.TestCase):
    """Connect Cloudflare (services/grok/cfoauth.py) against mocks of Cloudflare's OAuth endpoints and API."""

    def setUp(self):
        configure()
        self.st = mock_cloudflare.State()
        self.api, base = mock_cloudflare.start(self.st)
        self.oauth, obase = mock_cloudflare.start_oauth(self.st)
        envf = os.path.join(TMP, "device.env")
        with open(envf, "w") as f:
            f.write("DEVICE_HOST=car.example.test\n")
        self.saved = (grok.CF_API, grok.DEVICE_ENV, grok.OAUTH.client_id, grok.OAUTH.oauth_base, grok.OAUTH.now)
        grok.CF_API, grok.DEVICE_ENV = base, envf
        grok.OAUTH.client_id, grok.OAUTH.oauth_base = self.st.client_id, obase
        grok.OAUTH.pending, grok.OAUTH.last = {}, None

    def tearDown(self):
        grok.CF_API, grok.DEVICE_ENV, grok.OAUTH.client_id, grok.OAUTH.oauth_base, grok.OAUTH.now = self.saved
        self.api.shutdown()
        self.oauth.shutdown()
        configure()

    def start(self, **body):
        req = urllib.request.Request(ADMIN_URL + "/api/grokOAuthStart", method="POST", headers={"Content-Type": "application/json"},
                                     data=json.dumps(body).encode())
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            return json.loads(e.read())

    def back(self, params):
        with urllib.request.urlopen(ADMIN_URL + "/api/grok/oauth/callback?" + urllib.parse.urlencode(params), timeout=30) as r:
            return r.read().decode()

    def test_sign_in_sets_up_and_revokes(self):
        out = self.start()
        self.assertTrue(out["ok"], out)
        q = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(out["url"]).query))
        self.assertEqual(q["redirect_uri"], "https://car.example.test/api/grok/oauth/callback")
        self.assertTrue(out["shortUrl"].startswith("https://car.example.test/api/grok/oauth/go?s="))
        if "qr" in out:
            self.assertIn("<svg", out["qr"])
        # the short link a phone opens sends it to the same consent page
        import http.client
        c = http.client.HTTPConnection(ADMIN_URL[7:], timeout=5)
        c.request("GET", out["shortUrl"].split("car.example.test", 1)[1])
        r = c.getresponse()
        self.assertEqual((r.status, r.getheader("Location")), (302, out["url"]))
        page = self.back(self.st.consent(out["url"]))
        self.assertIn("Grok remote control is set up", page)
        self.assertIn("mcp.example.com/mcp", page)
        s = grok.settings()
        self.assertTrue(s["enabled"] and s["tokenSet"])
        self.assertEqual((s["hostname"], s["email"], s["teamDomain"]), ("mcp.example.com", "driver@example.com", "https://myteam.cloudflareaccess.com"))
        app = list(self.st.apps.values())[0]
        self.assertEqual(app["policies"][0]["include"], [{"email": {"email": "driver@example.com"}}])
        self.assertEqual(len(self.st.revoked), 1, "the sign-in is revoked")
        self.assertEqual(self.st.oauth_tokens, {})
        for root, _, files in os.walk(TMP):
            for fn in files:
                with open(os.path.join(root, fn), "rb") as f:
                    self.assertNotIn(self.st.revoked[0].encode(), f.read(), fn)
        with urllib.request.urlopen(ADMIN_URL + "/api/grok", timeout=5) as r:
            last = json.loads(r.read())["connect"]["last"]
        self.assertTrue(last["ok"])
        # the code and the state are single use
        self.assertIn("expired or was already used", self.back({"code": "x", "state": q["state"]}))

    def test_declined_unknown_and_expired(self):
        out = self.start()
        state = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(out["url"]).query))["state"]
        self.assertIn("did not allow access", self.back({"error": "access_denied", "state": state}))
        self.assertIn("expired", self.back({"code": "c", "state": state}))
        self.assertIn("expired", self.back({"code": "c", "state": "made-up"}))
        out = self.start()
        t0 = time.time()
        grok.OAUTH.now = lambda: t0 + cfoauth.STATE_TTL_S + 5
        self.assertIn("expired", self.back(self.st.consent(out["url"])))
        self.assertEqual(self.st.revoked, [], "an expired state never reaches the token exchange")
        self.assertEqual(self.st.apps, {}, "nothing was set up")

    def test_wrong_account_and_domain(self):
        self.st.zone = None
        out = self.start()
        page = self.back(self.st.consent(out["url"]))
        self.assertIn("sees no domain in Driver&#x27;s Account", page)
        self.assertEqual(len(self.st.revoked), 1, "revoked after a failure too")
        self.st.zone = "example.com"
        out = self.start(domain="other.org")
        self.assertIn("other.org is not among the domains", self.back(self.st.consent(out["url"])))
        self.assertEqual(len(self.st.revoked), 2)
        self.assertEqual(self.st.apps, {})
        out = self.start()
        self.assertIn("gave no email address", self.back(self.st.consent(out["url"], email="")))

    # the permissions Cloudflare's API schema accepts for each call (x-api-token-group, Nov 2026), and
    # the project client's scope ids with their permission names (GET /client/v4/oauth/scopes)
    TUNNEL_WRITE = {"Cloudflare One Connectors Write", "Cloudflare One Connector: cloudflared Write", "Cloudflare Tunnel Write"}
    NEEDS = [("GET", r"/user$", {"User Details Read", "User Details Write"}),
             ("GET", r"/accounts$", None),  # lists the accounts the grant has; no permission of its own
             ("GET", r"/zones$", {"Zone Read"}),
             ("GET", r"/accounts/\w+/access/(organizations|identity_providers)$", {"Access: Organizations, Identity Providers, and Groups Read"}),
             # list (also accepts the Read variants), create, token, configurations: all accept these three
             ("GET|POST", r"/accounts/\w+/cfd_tunnel$", TUNNEL_WRITE),
             ("GET", r"/accounts/\w+/cfd_tunnel/[\w-]+/token$", TUNNEL_WRITE),
             ("PUT", r"/accounts/\w+/cfd_tunnel/[\w-]+/configurations$", TUNNEL_WRITE),
             ("GET|POST", r"/accounts/\w+/access/apps$", {"Access: Apps and Policies Write"}),
             ("PUT", r"/accounts/\w+/access/apps/[\w-]+$", {"Access: Apps and Policies Write"}),
             ("GET|POST", r"/zones/\w+/dns_records$", {"DNS Write"}),
             ("PATCH", r"/zones/\w+/dns_records/\w+$", {"DNS Write"})]
    SCOPE_NAMES = {"dns.write": "DNS Write", "zone.read": "Zone Read", "access.write": "Access: Apps and Policies Write",
                   "access-acct.read": "Access: Organizations, Identity Providers, and Groups Read",
                   "teams-connector-cloudflared.write": "Cloudflare One Connector: cloudflared Write",
                   "account-settings.read": "Account Settings Read",
                   "user-details.read": "User Details Read"}

    def test_every_call_is_in_the_scopes(self):
        import re
        self.st.records["x"] = {"id": "x", "type": "CNAME", "name": "mcp.example.com", "content": "old.cfargotunnel.com", "proxied": False}
        out = self.start()
        self.assertIn("is set up", self.back(self.st.consent(out["url"])))
        self.assertIn("is set up", self.back(self.st.consent(self.start()["url"])))  # again: the update calls
        granted = {self.SCOPE_NAMES[s] for s in cfoauth.SCOPES}
        self.assertEqual(len(granted), 7)
        seen = set()
        for method, path, _ in self.st.calls:
            if path.startswith("oauth2"):
                continue
            path = path.split("?")[0]
            rule = [r for r in self.NEEDS if re.fullmatch(r[0], method) and re.search(r[1], path)]
            self.assertTrue(rule, "no rule for %s %s" % (method, path))
            need = rule[0][2]
            self.assertTrue(need is None or need & granted, "%s %s needs one of %s" % (method, path, need))
            seen.add((method, rule[0][1]))
        self.assertIn(("PATCH", r"/zones/\w+/dns_records/\w+$"), seen)
        self.assertIn(("PUT", r"/accounts/\w+/access/apps/[\w-]+$"), seen)

    def test_choose_among_domains(self):
        self.st.more_zones = ["other.org"]
        out = self.start()
        page = self.back(self.st.consent(out["url"]))
        self.assertIn("Choose a domain", page)
        self.assertIn('href="/css/settings.css"', page)
        self.assertIn('value="other.org"', page)
        self.assertEqual(self.st.revoked, [], "kept for the choice")
        state = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(out["url"]).query))["state"]
        req = urllib.request.Request(ADMIN_URL + "/api/grok/oauth/choose", method="POST",
                                     data=urllib.parse.urlencode({"state": state, "zone": "example.com"}).encode(),
                                     headers={"Content-Type": "application/x-www-form-urlencoded"})
        with urllib.request.urlopen(req, timeout=30) as r:
            self.assertIn("is set up", r.read().decode())
        self.assertEqual(len(self.st.revoked), 1)
        # a held choice that times out is revoked
        out = self.start()
        self.back(self.st.consent(out["url"]))
        t0 = time.time()
        grok.OAUTH.now = lambda: t0 + cfoauth.STATE_TTL_S + 5
        self.start()
        self.assertEqual(len(self.st.revoked), 2)

    def test_without_a_client_id(self):
        grok.OAUTH.client_id = ""
        out = self.start()
        self.assertFalse(out["ok"])
        self.assertIn("Advanced", out["message"])
        with urllib.request.urlopen(ADMIN_URL + "/api/grok", timeout=5) as r:
            self.assertFalse(json.loads(r.read())["connect"]["available"])


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
