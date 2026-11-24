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
import html
import json
import os
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import accessjwt
import cfoauth
import cfsetup
import controls
import gatectl
import info
import mcp
import routes
import stops
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
# the device's settings: DEVICE_HOST, the name the car page and the hotspot's phones reach the
# device by (Cloudflare's sign-in comes back to https://<DEVICE_HOST>/api/grok/oauth/callback)
DEVICE_ENV = os.environ.get("TESLALINUX_ENV", "/etc/teslalinux/env")
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
        "gates": e.get("TESLALINUX_GROK_GATES") == "1",
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

def _tool(fn, name, lock=True):
    """A tool's handler: logged, and run one at a time when it works Waze's screens (lock); a read
    tool that answers from the state the car link keeps runs at once, beside them."""
    def run(args, context):
        who = (context or {}).get("who")
        t0 = time.time()
        if lock and not TOOL_LOCK.acquire(timeout=TOOL_WAIT_S):
            text, structured, err = "Another request is still working with Waze; try again in a moment.", None, True
        else:
            try:
                text, structured = fn(args)
                err = False
            except waze.WazeError as e:
                text, structured, err = str(e), None, True
            finally:
                if lock:
                    TOOL_LOCK.release()
        ACTIONS.add({"t": round(t0, 3), "tool": name, "args": args, "ok": not err, "result": text[:300],
                     "ms": int((time.time() - t0) * 1000), "who": who})
        log("%s %s by %s: %s" % (name, json.dumps(args, ensure_ascii=False)[:200], who or "?", "ok" if not err else "error: " + text[:120]))
        return text, structured, err
    return run


DESTINATION = {"type": "string", "description": "Where to go, in any form: an address or place name in any language (Waze's "
                                                "search), a map link (Waze, Google, Apple, OpenStreetMap, Bing, HERE, geo:), "
                                                "coordinates (decimal, DM, DMS, N/S/E/W), a Plus Code, a geohash, an Israeli "
                                                "grid (ITM) E N pair, Home, Work, a saved place's name, or 'my last destination'."}
LATITUDE = {"type": "number", "minimum": -90, "maximum": 90}
LONGITUDE = {"type": "number", "minimum": -180, "maximum": 180}
RESULT_ID = {"type": "string", "description": "A resultId from search_places, recent_places or saved_places."}
LINK = {"type": "string", "description": "A map link (the same as giving it as destination): read on the device; a short "
                                         "link (maps.app.goo.gl) is followed to its target first."}
PLACE = {"type": "object", "properties": {
    "destination": DESTINATION, "latitude": LATITUDE, "longitude": LONGITUDE,
    "name": {"type": "string", "description": "A name for the coordinates, used in the answer only."},
    "resultId": RESULT_ID, "link": LINK}, "additionalProperties": False}
READ = {"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False}


def navigate(a):
    if a.get("destinations") is not None:
        if any(a.get(k) is not None for k in PLACE["properties"]):
            raise waze.WazeError("give either destinations or one destination, not both")
        return stops.navigate_many(a["destinations"])
    return waze.navigate_to(a)


ACT = {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False}
NO_ARGS = {"type": "object", "properties": {}, "additionalProperties": False}
AVOID = {"type": "object", "properties": {"avoid": {"type": "boolean", "description": "true: avoid them; false: allow them."}},
         "required": ["avoid"], "additionalProperties": False}
STEPS = {"type": "object", "properties": {"steps": {"type": "integer", "minimum": 1, "maximum": 5, "default": 1}},
         "additionalProperties": False}


def tool(name, title, description, schema, fn, hints, lock=True):
    return mcp.Tool(name, title, description, schema, _tool(fn, name, lock), hints)


EXTRA_TOOLS = [
    tool("route_options", "Route options", "The routes Waze offers now: on its route preview before a drive starts, or "
         "during a drive (its alternate routes). Each with its time, distance, the roads it goes via and Waze's labels "
         "(tolls, traffic, ...), and which one is selected. Car mode.", NO_ARGS, lambda a: routes.route_options(a), READ),
    tool("choose_route", "Choose a route", "Drive one of the routes route_options listed (Waze's routes list: the route, "
         "then Go). Car mode.", {"type": "object", "properties": {"route": {"type": "integer", "minimum": 1, "maximum": 10,
                                                                             "description": "The route's number from route_options."}},
                                  "required": ["route"], "additionalProperties": False},
         lambda a: routes.choose_route(a), dict(ACT, idempotentHint=False)),
    tool("start_drive", "Start the drive", "Press the button Waze waits on to start a drive: Go now on its route preview, "
         "or Go now / Resume drive on its 'Driving to ...?' card. Car mode.", NO_ARGS, lambda a: routes.start_drive(a), ACT),
    tool("set_avoid_tolls", "Avoid toll roads", "Turn Waze's 'Avoid toll roads' route option on or off. Car mode, "
         "on Waze's route preview or during a drive.", AVOID, lambda a: routes.set_avoid("tolls", a), ACT),
    tool("set_avoid_ferries", "Avoid ferries", "Turn Waze's 'Avoid ferries' route option on or off. Car mode, on "
         "Waze's route preview or during a drive.", AVOID, lambda a: routes.set_avoid("ferries", a), ACT),
    tool("set_avoid_freeways", "Avoid freeways", "Turn Waze's 'Avoid freeways' route option on or off. Car mode, on "
         "Waze's route preview or during a drive.", AVOID, lambda a: routes.set_avoid("freeways", a), ACT),
    tool("set_sound", "Waze's sound", "Set Waze's sound to one of its modes: Normal, Less chatty, Only alerts, Off (and "
         "Smart mute or Beep where Waze offers them). Car mode.",
         {"type": "object", "properties": {"mode": {"type": "string", "description": "A mode as Waze names it."}},
          "required": ["mode"], "additionalProperties": False}, lambda a: controls.set_sound(a), ACT),
    tool("mute_voice", "Mute Waze", "Turn Waze's sound off (its Sound mode Off). Car mode.", NO_ARGS,
         lambda a: controls.set_sound({"mode": "Off"}), ACT),
    tool("unmute_voice", "Unmute Waze", "Turn Waze's sound back on (its Sound mode Normal). Car mode.", NO_ARGS,
         lambda a: controls.set_sound({"mode": "Normal"}), ACT),
    tool("zoom_in", "Zoom in", "Zoom Waze's map in, as its zoom button does (steps presses). Car mode.", STEPS,
         lambda a: controls.zoom("in", a), dict(ACT, idempotentHint=False)),
    tool("zoom_out", "Zoom out", "Zoom Waze's map out, as its zoom button does (steps presses). Car mode.", STEPS,
         lambda a: controls.zoom("out", a), dict(ACT, idempotentHint=False)),
    tool("recenter", "Center on the car", "Make Waze's map follow the car again after it was moved. Car mode.", NO_ARGS,
         lambda a: controls.recenter(a), ACT),
    tool("answer_alert", "Answer Waze's alert", "Press a button of the alert Waze shows now (for example 'Still there' "
         "or 'Not there' when it asks about a report ahead). Other drivers see the answer. Car mode.",
         {"type": "object", "properties": {"answer": {"type": "string", "description": "The button's title."}},
          "required": ["answer"], "additionalProperties": False}, lambda a: controls.answer_alert(a),
         dict(ACT, idempotentHint=False, openWorldHint=True)),
    tool("recent_places", "Recent places", "Waze's recent destinations, as its search screen lists them, with a resultId "
         "for navigate_to, add_stop or place_details. Car mode.", NO_ARGS, lambda a: waze.place_list("recent", a), READ),
    tool("saved_places", "Saved places", "Waze's saved places (when the driver's Waze account has some), with a resultId "
         "for navigate_to, add_stop or place_details. Car mode.", NO_ARGS, lambda a: waze.place_list("saved", a), READ),
    tool("next_turn", "Next turn", "Waze's next turn and the one after: instruction, maneuver, road, distance, roundabout "
         "exit and lanes when Waze gives them, and the current road. Car mode; answered from what Waze last sent.",
         NO_ARGS, lambda a: info.next_turn(a), READ, lock=False),
    tool("current_speed", "Speed and limit", "The car's speed and the road's speed limit as Waze's speedometer shows "
         "them, in Waze's unit. Car mode; answered from Waze's last reading.", NO_ARGS, lambda a: info.speed(a), READ, lock=False),
    tool("waze_alerts", "Waze's alerts", "The alerts and messages Waze showed on the car screen in the last minutes "
         "(police or hazards ahead, report confirmations, ...). Car mode.",
         {"type": "object", "properties": {"minutes": {"type": "integer", "minimum": 1, "maximum": 60, "default": 10}},
          "additionalProperties": False}, lambda a: info.waze_alerts(a), READ, lock=False),
    tool("current_screen", "Waze's screen", "What Waze's car screen shows now: the kind of screen, its titles and "
         "items, the buttons it waits for and the controls it offers, and day or night. Car mode.", NO_ARGS,
         lambda a: info.current_screen(a), READ, lock=False),
    tool("car_position", "Car position", "The car's GPS position, heading and speed, from the fix the car's browser "
         "sends the device.", NO_ARGS, lambda a: info.car_position(a), READ, lock=False),
    tool("device_status", "Device status", "The device's connection: LTE, Wi-Fi or Ethernet, signal, internet "
         "reachability and latency, and the car hotspot.", NO_ARGS, lambda a: info.device_status(a), READ, lock=False),
    tool("list_gates", "PalGate gates", "The PalGate gates the driver shows on the map: id, name, outputs (two-output "
         "gates), whether they open by themselves on arrival, and the distance from the car when both positions are known.",
         NO_ARGS, lambda a: gatectl.list_gates(a), dict(READ, openWorldHint=True), lock=False),
    tool("open_gate", "Open a gate", "Open a PalGate gate shown on the map, by id or name (output: which one of a "
         "two-output gate). A physical action: only when the driver asks, with confirm: true, and only while the driver "
         "allows it (Settings > Grok > Let Grok open gates). Returns PalGate's answer.",
         {"type": "object", "properties": {
             "gate": {"type": "string", "description": "The gate's id or name from list_gates."},
             "output": {"type": "integer", "minimum": 1, "maximum": 2, "description": "For a two-output gate: 1 or 2."},
             "confirm": {"type": "boolean", "description": "Must be true: the driver asked to open this gate now."},
         }, "required": ["gate", "confirm"], "additionalProperties": False},
         lambda a: gatectl.open_gate(a, settings()["gates"]),
         {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": True}, lock=False),
]

TOOLS = [
    mcp.Tool(
        "navigate_to", "Navigate to a place",
        "Start Waze navigation: destination (text Waze searches), latitude and longitude, a Waze link, or a resultId. Or "
        "destinations, places in visiting order: Waze drives to the last, the others become stops as far as Waze "
        "allows (one per drive; the answer says what was not added). Returns the trip, or what Waze waits for.",
        {"type": "object", "properties": dict(PLACE["properties"], destinations={
            "type": "array", "minItems": 1, "maxItems": 10, "items": PLACE,
            "description": "Several places in visiting order, each {destination} or {latitude, longitude} or {resultId}."}),
         "additionalProperties": False},
        _tool(navigate, "navigate_to"),
        {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False}),
    mcp.Tool(
        "search_places", "Search places in Waze",
        "Waze's own search around the car (Car mode): places with name, distance, Waze's info line and a resultId for "
        "navigate_to, add_stop or place_details. addresses: true also reads each place's card (slower). The car "
        "screen keeps its panels.",
        {"type": "object", "properties": {
            "query": {"type": "string", "description": "What to look for: a name, an address, or a kind of place ('gas station')."},
            "limit": {"type": "integer", "minimum": 1, "maximum": 10, "default": 5},
            "addresses": {"type": "boolean", "default": False, "description": "Read each place's address from its card."},
        }, "required": ["query"], "additionalProperties": False},
        _tool(lambda a: waze.search_places(a), "search_places"),
        READ),
    mcp.Tool(
        "place_details", "A place's card",
        "What Waze's card shows for a search_places result: its address, opening hours, rating, prices or other "
        "lines Waze has, and the actions it offers (Car mode only).",
        {"type": "object", "properties": {"resultId": RESULT_ID}, "required": ["resultId"], "additionalProperties": False},
        _tool(lambda a: waze.place_details(a), "place_details"),
        READ),
    mcp.Tool(
        "add_stop", "Add a stop",
        "During a drive, add a stop before the destination as Waze's car screen does (a place's card, Add a stop): "
        "place (Waze's first result is taken), latitude and longitude, a Waze link, or a resultId. One stop per drive; "
        "a second is refused with the reason (Car mode).",
        {"type": "object", "properties": {
            "place": {"type": "string", "description": "A name or an address Waze searches."},
            "latitude": LATITUDE, "longitude": LONGITUDE,
            "name": {"type": "string", "description": "A name for the coordinates, used in the answer only."},
            "resultId": RESULT_ID, "link": LINK,
        }, "additionalProperties": False},
        _tool(lambda a: stops.add_stop(a), "add_stop"),
        {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False}),
    mcp.Tool(
        "remove_stop", "Remove a stop",
        "Remove the drive's stop. Waze's car interface has no way to remove a stop, so this explains what to do instead.",
        {"type": "object", "properties": {}, "additionalProperties": False},
        _tool(lambda a: stops.remove_stop(a), "remove_stop", lock=False),
        {"readOnlyHint": False, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False}),
    mcp.Tool(
        "trip_status", "Current trip",
        "Waze's current trip: whether it is navigating, the destination, arrival time, distance and time left, the "
        "stops Waze counts (with the estimates then to the next stop), and the next turn (Car mode only). Answered "
        "from what Waze last sent, without touching its screens.",
        {"type": "object", "properties": {}, "additionalProperties": False},
        _tool(lambda a: waze.trip_status(a), "trip_status", lock=False),
        READ),
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
] + EXTRA_TOOLS
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
            "tokenPermissions": [" ".join(p) for p in cfsetup.TOKEN_PERMISSIONS],
            "connect": {"available": OAUTH.available(), "scopes": cfoauth.SCOPES, "last": OAUTH.last,
                        "redirectUri": "https://%s%s" % (device_host(), cfoauth.CALLBACK_PATH)}}


def post_settings(req):
    out = {}
    for k in ("enabled", "hostname", "teamDomain", "aud", "gates"):
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


# ------------------------------------------------------------------ Connect Cloudflare (OAuth)

def device_host():
    return read_env(DEVICE_ENV).get("DEVICE_HOST") or "car.alexahub.dev"


OAUTH = cfoauth.Flow(client_id=os.environ.get("TESLALINUX_GROK_CF_CLIENT_ID", cfoauth.DEFAULT_CLIENT_ID),
                     oauth_base=os.environ.get("TESLALINUX_GROK_CF_OAUTH", cfoauth.OAUTH_BASE), api_base=CF_API)


def store_setup(r, email):
    """What the tunnel needs, stored by teslalinux-set-grok (the tunnel token root-only); remote control on."""
    return run_helper({"hostname": r["hostname"], "teamDomain": r["teamDomain"], "aud": r["aud"], "email": email,
                       "tunnelToken": r["tunnelToken"], "enabled": True})


def post_oauth_start(req):
    OAUTH.device_host = device_host()
    OAUTH.api_base = CF_API
    try:
        state, url = OAUTH.start(req.get("domain") if isinstance(req.get("domain"), str) else None,
                                 req.get("hostname") if isinstance(req.get("hostname"), str) else None)
    except cfoauth.OAuthError as e:
        return 400, {"ok": False, "message": str(e)}
    short = "https://%s/api/grok/oauth/go?s=%s" % (OAUTH.device_host, state)
    out = {"ok": True, "url": url, "shortUrl": short, "expiresIn": cfoauth.STATE_TTL_S}
    try:
        import tlqr  # services/gates/tlqr.py, installed next to the gates service
        out["qr"] = tlqr.svg(short, module=6)
    except (ImportError, ValueError):
        pass
    return 200, out


def page(title, body, ok=None):
    """The small page the browser sees on the device after Cloudflare's sign-in."""
    tone = "#1a7f37" if ok else "#b42318" if ok is False else "#333"
    return ("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            "<title>%s</title><style>body{font:18px/1.45 system-ui,sans-serif;margin:0;padding:24px;background:#f6f6f6;color:#222}"
            "main{max-width:560px;margin:auto;background:#fff;border-radius:14px;padding:24px}h1{font-size:22px;color:%s}"
            "button{font:inherit;padding:10px 18px;margin:6px 6px 0 0;border-radius:10px;border:1px solid #888;background:#fff}"
            "li{margin:4px 0}.fine{color:#666;font-size:15px}</style></head><body><main><h1>%s</h1>%s"
            "<p class=\"fine\">You can close this page. Settings &gt; Grok shows the result too.</p></main></body></html>"
            % (html.escape(title), tone, html.escape(title), body)).encode()


def result_page(res):
    d = res["done"]
    items = "".join("<li>%s</li>" % html.escape(s) for s in d["steps"])
    note = "<p>%s</p>" % html.escape(d["note"]) if d.get("note") else ""
    return page("Grok remote control is set up", "<p>%s</p><ol>%s</ol>%s" % (html.escape(d["message"]), items, note), True)


def oauth_callback(params, chosen=None):
    try:
        if chosen is not None:
            res = OAUTH.choose(params.get("state", ""), chosen, store_setup)
        else:
            res = OAUTH.callback(params, store_setup)
    except cfoauth.OAuthError as e:
        OAUTH.fail(str(e))
        log("connect cloudflare: " + str(e))
        return page("Not set up", "<p>%s</p>" % html.escape(str(e)), False)
    if "choose" in res:
        opts = "".join("<button name=\"zone\" value=\"%s\">%s</button>" % (html.escape(z), html.escape(z)) for z in res["choose"])
        return page("Which domain?", "<p>Signed in as %s. Which domain should Grok use (mcp.&lt;domain&gt;)?</p>"
                    "<form method=\"post\" action=\"/api/grok/oauth/choose\"><input type=\"hidden\" name=\"state\" value=\"%s\">%s</form>"
                    % (html.escape(res["email"]), html.escape(res["state"]), opts))
    log("connect cloudflare: set up %s" % res["done"]["hostname"])
    return result_page(res)


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

    def _html(self, code, body, location=None):
        self.send_response(code)
        if location:
            self.send_header("Location", location)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urllib.parse.urlsplit(self.path)
        route = u.path.rstrip("/")
        params = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        if route == "/api/grok":
            return self._send(200, status())
        if route == "/api/grok/oauth/go":
            try:
                return self._html(302, b"", OAUTH.authorize_url(params.get("s", "")))
            except cfoauth.OAuthError as e:
                return self._html(410, page("Link expired", "<p>%s</p>" % html.escape(str(e)), False))
        if route == "/api/grok/oauth/callback":
            return self._html(200, oauth_callback(params))
        self._send(404, {"ok": False, "message": "not found"})

    def do_POST(self):
        route = urllib.parse.urlsplit(self.path).path.rstrip("/")
        n = int(self.headers.get("Content-Length") or 0)
        if not 0 <= n <= 65536:
            return self._send(413, {"ok": False, "message": "too large"})
        if route == "/api/grok/oauth/choose":
            form = {k: v[0] for k, v in urllib.parse.parse_qs(self.rfile.read(n).decode(errors="replace")).items()}
            return self._html(200, oauth_callback(form, chosen=form.get("zone", "")))
        try:
            req = json.loads(self.rfile.read(n).decode() or "{}") if n else {}
        except ValueError:
            return self._send(400, {"ok": False, "message": "not JSON"})
        if not isinstance(req, dict):
            return self._send(400, {"ok": False, "message": "not an object"})
        routes = {"/api/grokSettings": post_settings, "/api/grokToken": post_token, "/api/grokSetup": post_setup,
                  "/api/grokOAuthStart": post_oauth_start}
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
