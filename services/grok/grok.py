#!/usr/bin/env python3
"""teslalinux grok: Waze controlled by the car's Grok assistant, through a remote MCP server.

    Grok (grok.com custom connector) --HTTPS--> Cloudflare Access (the driver's own zone, Managed
    OAuth: Grok signs in as the driver) --Cloudflare Tunnel--> cloudflared on this device
    --> this, 127.0.0.1:8083 /mcp  (MCP Streamable HTTP; every request must carry a valid Access JWT)
    --> Waze: ATL's car app channel (9995, Car mode) or its intent channel (9993, Phone mode)

    car page --/api/grok*--> nginx --> this, 127.0.0.1:8084 (status, settings, the action log, setup)

Nothing is reachable from outside unless the driver turns it on: the switch (off by default) starts
cloudflared (teslalinux-cloudflared.service) and lets the MCP endpoint answer. docs/GROK.md has the
setup; mcp.py the protocol, accessjwt.py the token check, waze.py the tools, cfsetup.py the
automated Cloudflare setup.

Settings (/etc/teslalinux/grok.env, written by teslalinux-set-grok as root; not secret):
  TESLALINUX_GROK_ENABLED      1 to answer (and run the tunnel)
  TESLALINUX_GROK_HOSTNAME     the public hostname (mcp.example.com)
  TESLALINUX_GROK_TEAM_DOMAIN  https://<team>.cloudflareaccess.com (the Access JWT's issuer)
  TESLALINUX_GROK_AUD          the Access application's AUD tag
  TESLALINUX_GROK_EMAIL        the email address the setup allowed (shown on the page)
  TESLALINUX_GROK_TUNNEL_ID    the tunnel the token is for; TESLALINUX_GROK_TOKEN_SET=1 when stored
The tunnel token itself is in /etc/teslalinux/grok/tunnel.env (root, 0600): this service never
reads it. State (TESLALINUX_GROK_DIR, /var/lib/teslalinux/grok, 0700): actions.jsonl, the last
calls (time, tool, arguments, result, who), shown on the settings page.

Admin routes (JSON; nginx forwards /api/grok* here):
  GET  /api/grok            settings (no secrets), tunnel state, last request, the last actions
  POST /api/grokSettings    {enabled?, hostname?, teamDomain?, aud?}
  POST /api/grokToken       {token} or {clear: true}: the tunnel token, stored root-only
  POST /api/grokSetup       {domain, hostname?, email, apiToken, redirectUris?}: the Cloudflare side
                            made with the driver's API token (used for these calls, never stored)
"""
import json
import os
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import accessjwt
import cfsetup
import mcp
import waze

VERSION = "1.0"
BIND = os.environ.get("TESLALINUX_GROK_BIND", "127.0.0.1")
MCP_PORT = int(os.environ.get("TESLALINUX_GROK_MCP_PORT", "8083"))
ADMIN_PORT = int(os.environ.get("TESLALINUX_GROK_ADMIN_PORT", "8084"))
STATE_DIR = os.environ.get("TESLALINUX_GROK_DIR", "/var/lib/teslalinux/grok")
CONFIG = os.environ.get("TESLALINUX_GROK_CONFIG", "/etc/teslalinux/grok.env")
HELPER = os.environ.get("TESLALINUX_GROK_HELPER", "/usr/local/bin/teslalinux-set-grok")
HELPER_SUDO = os.environ.get("TESLALINUX_GROK_HELPER_SUDO", "1") == "1"
TUNNEL_UNIT = os.environ.get("TESLALINUX_GROK_TUNNEL_UNIT", "teslalinux-cloudflared.service")
TUNNEL_METRICS = os.environ.get("TESLALINUX_GROK_TUNNEL_METRICS", "http://127.0.0.1:20241/ready")
CF_API = os.environ.get("TESLALINUX_GROK_CF_API", cfsetup.API)
# Origins a browser-made request may come from (the MCP spec's DNS-rebinding guard); requests
# without an Origin (Grok's servers) pass, every one still needs Access's token
ORIGINS = os.environ.get("TESLALINUX_GROK_ORIGINS", "https://grok.com https://x.ai").split()
MCP_PATHS = ("/mcp", "/mcp/")
LOG_KEEP = 200
LOG_SHOW = 30
TOOL_WAIT_S = 90        # one tool at a time: Waze's car screens have one driver

INSTRUCTIONS = ("Controls Waze, the navigation app on the driver's car screen. To drive somewhere, call "
                "navigate_to with the destination, or search_places first and then navigate_to with the "
                "resultId of the place the driver picks. trip_status says where the car is going and when it "
                "arrives. report sends a Waze report (police, a crash, a hazard, traffic, ...) at the car's position when "
                "the driver asks for one. The driver is driving: keep answers short.")


def log(msg):
    print("teslalinux-grok: " + msg, flush=True)


# ------------------------------------------------------------------ settings

def read_env(path):
    out = {}
    try:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                v = v.strip()
                if len(v) >= 2 and v[0] == v[-1] and v[0] in "'\"":
                    v = v[1:-1]
                out[k.strip()] = v
    except OSError:
        pass
    return out


def settings():
    e = read_env(CONFIG)
    return {
        "enabled": e.get("TESLALINUX_GROK_ENABLED") == "1",
        "hostname": e.get("TESLALINUX_GROK_HOSTNAME", ""),
        "teamDomain": e.get("TESLALINUX_GROK_TEAM_DOMAIN", ""),
        "aud": e.get("TESLALINUX_GROK_AUD", ""),
        "email": e.get("TESLALINUX_GROK_EMAIL", ""),
        "tunnelId": e.get("TESLALINUX_GROK_TUNNEL_ID", ""),
        "tokenSet": e.get("TESLALINUX_GROK_TOKEN_SET") == "1",
    }


def run_helper(req):
    """teslalinux-set-grok as root (one sudoers rule), the request as JSON on its standard input
    (a token never appears on a command line): (ok, message)."""
    cmd = (["sudo", "-n", HELPER] if HELPER_SUDO and os.geteuid() != 0 else [HELPER])
    try:
        p = subprocess.run(cmd, input=json.dumps(req), capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as e:
        return False, "the settings helper did not run: %s" % e
    msg = (p.stdout.strip().splitlines() or [""])[-1] if p.returncode == 0 else (p.stderr.strip().splitlines() or ["failed"])[-1]
    return p.returncode == 0, msg


# ------------------------------------------------------------------ the action log

class ActionLog:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        self.entries = []
        try:
            with open(path) as f:
                for line in f:
                    try:
                        self.entries.append(json.loads(line))
                    except ValueError:
                        pass
        except OSError:
            pass
        self.entries = self.entries[-LOG_KEEP:]
        self.last_request = None

    def add(self, entry):
        with self.lock:
            self.entries.append(entry)
            self.entries = self.entries[-LOG_KEEP:]
            try:
                os.makedirs(os.path.dirname(self.path), exist_ok=True)
                tmp = self.path + ".tmp"
                with open(tmp, "w") as f:
                    for e in self.entries:
                        f.write(json.dumps(e, ensure_ascii=False) + "\n")
                os.chmod(tmp, 0o600)
                os.replace(tmp, self.path)
            except OSError as e:
                log("action log not written: %s" % e)

    def request(self, ok, status, who, what):
        self.last_request = {"t": time.time(), "ok": ok, "status": status, "who": who, "what": what}

    def recent(self, n=LOG_SHOW):
        with self.lock:
            return list(reversed(self.entries[-n:]))


ACTIONS = ActionLog(os.path.join(STATE_DIR, "actions.jsonl"))
KEYS = accessjwt.KeySet()
TOOL_LOCK = threading.Lock()


# ------------------------------------------------------------------ the tools

def _tool(fn, name):
    def run(args, context):
        who = (context or {}).get("who")
        t0 = time.time()
        if not TOOL_LOCK.acquire(timeout=TOOL_WAIT_S):
            text, structured, err = "Another request is still working with Waze; try again in a moment.", None, True
        else:
            try:
                text, structured = fn(args)
                err = False
            except waze.WazeError as e:
                text, structured, err = str(e), None, True
            finally:
                TOOL_LOCK.release()
        ACTIONS.add({"t": round(t0, 3), "tool": name, "args": args, "ok": not err, "result": text[:300],
                     "ms": int((time.time() - t0) * 1000), "who": who})
        log("%s %s by %s: %s" % (name, json.dumps(args, ensure_ascii=False)[:200], who or "?", "ok" if not err else "error: " + text[:120]))
        return text, structured, err
    return run


DESTINATION = {"type": "string", "description": "Where to go: an address or a place name, searched by Waze (for example 'Dizengoff Center, Tel Aviv')."}
TOOLS = [
    mcp.Tool(
        "navigate_to", "Navigate to a place",
        "Start Waze navigation on the car screen. Give destination (text Waze searches), or latitude and longitude, "
        "or the resultId of a search_places result. Returns the trip once Waze is driving.",
        {"type": "object", "properties": {
            "destination": DESTINATION,
            "latitude": {"type": "number", "minimum": -90, "maximum": 90},
            "longitude": {"type": "number", "minimum": -180, "maximum": 180},
            "name": {"type": "string", "description": "A name for the coordinates, used in the answer only."},
            "resultId": {"type": "string", "description": "A resultId from search_places."},
        }, "additionalProperties": False},
        _tool(lambda a: waze.navigate_to(a), "navigate_to"),
        {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False}),
    mcp.Tool(
        "search_places", "Search places in Waze",
        "Search with Waze's own search, around the car's position (Car mode only). Returns up to limit places with "
        "name, address, distance and a resultId to pass to navigate_to. The car screen keeps its panels; Waze's map "
        "may show the results for a few seconds.",
        {"type": "object", "properties": {
            "query": {"type": "string", "description": "What to look for: a name, an address, or a kind of place ('gas station')."},
            "limit": {"type": "integer", "minimum": 1, "maximum": 10, "default": 5},
        }, "required": ["query"], "additionalProperties": False},
        _tool(lambda a: waze.search_places(a), "search_places"),
        {"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False}),
    mcp.Tool(
        "trip_status", "Current trip",
        "Waze's current trip: whether it is navigating, the destination, arrival time, distance and time left, and the "
        "next turn (Car mode only).",
        {"type": "object", "properties": {}, "additionalProperties": False},
        _tool(lambda a: waze.trip_status(a), "trip_status"),
        {"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False}),
    mcp.Tool(
        "stop_navigation", "Stop navigation",
        "End Waze's current navigation (Car mode only).",
        {"type": "object", "properties": {}, "additionalProperties": False},
        _tool(lambda a: waze.stop_navigation(a), "stop_navigation"),
        {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False}),
    mcp.Tool(
        "report", "Report to Waze",
        "Send a Waze report at the car's position (Car mode): category (Traffic, Police, Hazard, Crash, Blocked lane, "
        "Bad weather, Animal, Map Issue) and kind (Standstill, Mobile camera, Pothole, Left lane, ...). Other drivers "
        "see it: report only what the driver asks for. report_types lists all.",
        {"type": "object", "properties": {
            "category": {"type": "string", "description": "A category title or id from report_types (e.g. 'Police', 'hazard')."},
            "kind": {"type": "string", "description": "The kind's title or id (e.g. 'Mobile camera', 'hazard_pothole'). "
                                                      "May be left out when the kind is the category itself (Police, Crash, Animal)."},
            "option": {"type": "string", "description": "The answer when Waze asks one more question (for example ahead or behind)."},
        }, "required": ["category"], "additionalProperties": False},
        _tool(lambda a: waze.report(a), "report"),
        {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": True}),
    mcp.Tool(
        "report_types", "Waze's report types",
        "The report categories and kinds Waze's car screen offers now, with ids for report (Car mode only).",
        {"type": "object", "properties": {}, "additionalProperties": False},
        _tool(lambda a: waze.report_types(a), "report_types"),
        {"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False}),
]
SERVER = mcp.Server("teslalinux-waze", VERSION, TOOLS, INSTRUCTIONS)


# ------------------------------------------------------------------ the MCP endpoint

def origin_ok(origin, hostname):
    if not origin:
        return True
    o = origin.strip().rstrip("/").lower()
    allowed = [x.lower().rstrip("/") for x in ORIGINS]
    if hostname:
        allowed.append("https://" + hostname.lower())
    return o in allowed


def check_request(headers, cfg, now=None):
    """(status, message, claims): 200 with the token's claims, else the refusal."""
    if not cfg["enabled"]:
        return 503, "Remote control is turned off on the device (Settings > Grok).", None
    if not cfg["teamDomain"] or not cfg["aud"]:
        return 503, "Remote control is not set up on the device (the Access team domain and AUD tag).", None
    if not origin_ok(headers.get("Origin"), cfg["hostname"]):
        return 403, "Origin not allowed.", None
    token = headers.get("Cf-Access-Jwt-Assertion")
    if not token:
        return 401, "Missing Cloudflare Access token.", None
    try:
        claims = accessjwt.verify(token, cfg["teamDomain"], cfg["aud"], KEYS, now)
    except accessjwt.TokenError as e:
        return 403, "Cloudflare Access token refused: %s." % e, None
    return 200, None, claims


class McpHandler(BaseHTTPRequestHandler):
    server_version = "teslalinux-grok"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, headers, body):
        self.send_response(code)
        for k, v in headers.items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body and self.command != "HEAD":
            self.wfile.write(body)

    def _refuse(self, code, message):
        body = json.dumps({"jsonrpc": "2.0", "id": None, "error": {"code": -32001 if code in (401, 403) else -32000, "message": message}}).encode()
        self._send(code, {"Content-Type": "application/json", "Cache-Control": "no-store"}, body)

    def _handle(self):
        path = urllib.parse.urlsplit(self.path).path
        cfg = settings()
        n = int(self.headers.get("Content-Length") or 0)
        if n < 0 or n > mcp.MAX_BODY:
            self._refuse(413, "request body too large")
            self.close_connection = True
            return
        body = self.rfile.read(n) if n else b""
        status, why, claims = check_request(self.headers, cfg)
        if status != 200:
            ACTIONS.request(False, status, None, why)
            self._refuse(status, why)
            return
        who = claims.get("email") or claims.get("sub") or claims.get("common_name") or "?"
        if path not in MCP_PATHS:
            ACTIONS.request(False, 404, who, path)
            self._refuse(404, "Not found: the MCP endpoint is /mcp.")
            return
        code, headers, out = SERVER.handle_http(self.command, self.headers, body, {"who": who})
        what = None
        try:
            m = json.loads(body.decode()) if body else None
            what = m.get("method") if isinstance(m, dict) else "batch"
        except ValueError:
            pass
        ACTIONS.request(code < 400, code, who, what)
        self._send(code, headers, out)

    do_POST = _handle
    do_GET = _handle
    do_DELETE = _handle
    do_PUT = _handle


# ------------------------------------------------------------------ the settings API

def tunnel_state():
    st = {"unit": "unknown", "connections": None}
    try:
        p = subprocess.run(["systemctl", "is-active", TUNNEL_UNIT], capture_output=True, text=True, timeout=5)
        st["unit"] = p.stdout.strip() or "unknown"
    except (OSError, subprocess.TimeoutExpired):
        pass
    if st["unit"] == "active":
        try:
            with urllib.request.urlopen(TUNNEL_METRICS, timeout=2) as r:
                d = json.loads(r.read(65536).decode() or "{}")
                st["connections"] = d.get("readyConnections")
        except (OSError, ValueError) as e:
            # /ready answers 503 while no connection is up
            body = getattr(e, "read", None)
            try:
                d = json.loads(body().decode()) if body else {}
                st["connections"] = d.get("readyConnections", 0)
            except (OSError, ValueError, AttributeError):
                st["connections"] = 0
    st["connected"] = bool(st["connections"])
    return st


def status():
    cfg = settings()
    return {"ok": True, "settings": cfg, "tunnel": tunnel_state(), "lastRequest": ACTIONS.last_request,
            "actions": ACTIONS.recent(), "interface": waze.interface(),
            "endpoint": ("https://%s/mcp" % cfg["hostname"]) if cfg["hostname"] else None,
            "tokenPermissions": [" ".join(p) for p in cfsetup.TOKEN_PERMISSIONS]}


def post_settings(req):
    out = {}
    for k in ("enabled", "hostname", "teamDomain", "aud"):
        if k in req:
            out[k] = req[k]
    if not out:
        return 400, {"ok": False, "message": "nothing to change"}
    ok, msg = run_helper(out)
    return (200 if ok else 400), {"ok": ok, "message": msg, "settings": settings()}


def post_token(req):
    if req.get("clear"):
        ok, msg = run_helper({"clearToken": True})
    else:
        tok = req.get("token")
        if not isinstance(tok, str) or not tok.strip():
            return 400, {"ok": False, "message": "no token"}
        ok, msg = run_helper({"tunnelToken": tok.strip()})
    return (200 if ok else 400), {"ok": ok, "message": msg, "settings": settings()}


def post_setup(req):
    try:
        r = cfsetup.setup(req.get("apiToken"), req.get("domain"), req.get("hostname"), req.get("email"),
                          req.get("redirectUris"), base=CF_API, log=lambda m: log("setup: " + m))
    except cfsetup.SetupError as e:
        return 400, {"ok": False, "message": str(e)}
    ok, msg = run_helper({"hostname": r["hostname"], "teamDomain": r["teamDomain"], "aud": r["aud"],
                          "email": req.get("email", "").strip(), "tunnelToken": r["tunnelToken"], "enabled": True})
    if not ok:
        return 500, {"ok": False, "message": "Cloudflare is set up, but the device did not store it: " + msg, "steps": r["steps"]}
    note = None
    if "onetimepin" not in r["loginMethods"]:
        note = ("Your Zero Trust organization has no One-time PIN login: sign in with %s (or add One-time PIN in "
                "Zero Trust > Integrations > Identity providers)." % (", ".join(r["loginMethods"]) or "its login method"))
    return 200, {"ok": True, "message": "Set up. Add https://%s/mcp as a custom connector on grok.com." % r["hostname"],
                 "steps": r["steps"], "note": note, "settings": settings()}


class AdminHandler(BaseHTTPRequestHandler):
    server_version = "teslalinux-grok"
    sys_version = ""

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        route = urllib.parse.urlsplit(self.path).path.rstrip("/")
        if route == "/api/grok":
            return self._send(200, status())
        self._send(404, {"ok": False, "message": "not found"})

    def do_POST(self):
        route = urllib.parse.urlsplit(self.path).path.rstrip("/")
        n = int(self.headers.get("Content-Length") or 0)
        if not 0 <= n <= 65536:
            return self._send(413, {"ok": False, "message": "too large"})
        try:
            req = json.loads(self.rfile.read(n).decode() or "{}") if n else {}
        except ValueError:
            return self._send(400, {"ok": False, "message": "not JSON"})
        if not isinstance(req, dict):
            return self._send(400, {"ok": False, "message": "not an object"})
        routes = {"/api/grokSettings": post_settings, "/api/grokToken": post_token, "/api/grokSetup": post_setup}
        fn = routes.get(route)
        if not fn:
            return self._send(404, {"ok": False, "message": "not found"})
        code, obj = fn(req)
        self._send(code, obj)


def serve(port, handler):
    srv = ThreadingHTTPServer((BIND, port), handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True, name="http-%d" % port).start()
    return srv


def main():
    os.makedirs(STATE_DIR, exist_ok=True)
    serve(MCP_PORT, McpHandler)
    serve(ADMIN_PORT, AdminHandler)
    log("MCP endpoint on %s:%d/mcp, settings on %s:%d (%s)" % (BIND, MCP_PORT, BIND, ADMIN_PORT,
                                                               "on" if settings()["enabled"] else "off"))
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
