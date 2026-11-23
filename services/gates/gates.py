#!/usr/bin/env python3
"""teslalinux gates: PalGate gates and barriers opened from the car page, without the app.

The device is linked to the driver's PalGate account as a linked device (the PalGate app's
Device Linking > Link a Device scans a QR code the car page shows), then lists the account's gates
and opens them through PalGate's cloud API (services/gates/palgate.py, docs/GATES.md).

    car page --/api/gates*--> services/api/api.py --> this (127.0.0.1:8082) --> PalGate's API
    browser geolocation --> services/gps/gps.py feed (9997) --> this: auto-open near a gate

State (TESLALINUX_GATES_DIR, /var/lib/teslalinux/gates, 0700, the service user's):
  account.json   the linked account: phone number, session token, token type (0600; never logged,
                 never sent to the page, never in the repository or a release)
  settings.json  per gate: shown, order, label, output, auto-open (0600: gate positions are private)
  gates.json     the last gate list PalGate returned (0600)

Routes (all JSON; api.py forwards /api/gates* here unchanged):
  GET  /api/gates                    linked or not, the masked phone number, the gates with their
                                     settings, the car's last position, the link state
  POST /api/gatesLink {"action":"start"|"cancel"}   start (or stop) linking: the QR code as SVG
  GET  /api/gatesLink                the link state: idle | waiting | linked | expired | failed
  POST /api/gatesUnlink              forget the account (the phone app lists the device until it is
                                     removed there)
  POST /api/gatesRefresh             read the gate list again
  POST /api/gatesSettings {"gates":[{id, shown?, order?, label?, output?, auto?}]}  or
                          {"order":[id, ...]}
  POST /api/gatesPosition {"id"}     the gate's auto-open spot: the car's position now
  POST /api/gatesOpen {"id", "output"?}  open it now: {ok, message, event}
  GET  /api/gatesEvents?after=N&wait=S   opens and auto-opens after event N (long poll, at most 25 s)

Auto-open (off for every gate until the driver turns it on for that gate and sets its spot): the
gate opens when the car enters its circle (radius) from outside it, moving at a speed between the
gate's limits and, with approach-only, getting closer to it; then not again until the car has left
the circle (by a margin) and the cooldown has passed, so a car parked inside never opens it twice.
A fix older than a few seconds or less accurate than the circle allows is not used.
"""
import json
import math
import os
import socket
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import palgate
import tlqr

PORT = int(os.environ.get("TESLALINUX_GATES_PORT", "8082"))
BIND = os.environ.get("TESLALINUX_GATES_BIND", "127.0.0.1")
STATE_DIR = os.environ.get("TESLALINUX_GATES_DIR", "/var/lib/teslalinux/gates")
GPS_FEED = os.environ.get("TESLALINUX_GATES_GPS_FEED", "127.0.0.1:9997")  # "" = no auto-open
API_BASE = os.environ.get("TESLALINUX_GATES_API", palgate.DEFAULT_BASE)

LINK_TIMEOUT_S = 180.0      # how long a QR code stays valid on the page
LINK_POLL_S = 2.0
REFRESH_S = 6 * 3600        # the gate list is read again this often while linked
OPEN_DEBOUNCE_S = 3.0       # a second tap within this time is the same open
FIX_STALE_S = 5.0
MAX_EVENTS = 50

AUTO_DEFAULTS = {"enabled": False, "lat": None, "lon": None, "radius": 60, "approachOnly": True,
                 "minSpeed": 3, "maxSpeed": 50, "cooldown": 10}
AUTO_LIMITS = {"radius": (15, 500), "minSpeed": (0, 60), "maxSpeed": (5, 130), "cooldown": (1, 240)}
LABEL_MAX = 40


def log(msg):
    print("teslalinux-gates: " + msg, flush=True)


# ------------------------------------------------------------------ state files

def _path(name):
    return os.path.join(STATE_DIR, name)


def read_json(name, default):
    try:
        with open(_path(name)) as f:
            v = json.load(f)
        return v if isinstance(v, type(default)) else default
    except (OSError, ValueError):
        return default


def write_json(name, obj):
    """atomically, 0600, in a 0700 directory"""
    os.makedirs(STATE_DIR, mode=0o700, exist_ok=True)
    try:
        os.chmod(STATE_DIR, 0o700)
    except OSError:
        pass
    tmp = _path("." + name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(obj, f, indent=1)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, _path(name))


def remove(name):
    try:
        os.remove(_path(name))
    except FileNotFoundError:
        pass


def mask_phone(phone):
    s = "".join(c for c in str(phone or "") if c.isdigit())
    return ("•" * max(0, len(s) - 3)) + s[-3:] if s else ""


# ------------------------------------------------------------------ geometry

def distance_m(lat1, lon1, lat2, lon2):
    r = 6371008.8
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def parse_fix(line):
    """gps.py's feed line: lat lon speed_mps bearing accuracy_m timestamp_ms (NaN = unknown)"""
    parts = line.split()
    if len(parts) < 6:
        return None
    try:
        vals = [float(x) for x in parts[:5]]
    except ValueError:
        return None
    lat, lon, speed, bearing, acc = vals
    if not (math.isfinite(lat) and math.isfinite(lon)):
        return None
    fin = lambda x: x if math.isfinite(x) else None
    return {"lat": lat, "lon": lon, "speed": fin(speed), "bearing": fin(bearing), "accuracy": fin(acc)}


class AutoTracker:
    """One gate's auto-open state, fed one fix at a time; decide() says whether to open now."""

    def __init__(self):
        self.armed = False      # seen outside the circle since the last open (or since start)
        self.inside = False
        self.last_open = None   # monotonic time of the last open
        self.history = []       # (monotonic time, distance) of the last fixes
        self.distance = None

    def decide(self, cfg, fix, now):
        if not cfg.get("enabled") or cfg.get("lat") is None or cfg.get("lon") is None:
            self.armed = False
            self.history = []
            self.distance = None
            return False, None
        d = distance_m(fix["lat"], fix["lon"], cfg["lat"], cfg["lon"])
        self.distance = d
        radius = float(cfg["radius"])
        acc = fix.get("accuracy")
        if acc is not None and acc > max(50.0, radius * 1.5):
            return False, "position not accurate enough"
        self.history = [(t, x) for (t, x) in self.history if now - t <= 15.0] + [(now, d)]
        margin = max(20.0, radius * 0.5)
        if d > radius + margin and (self.last_open is None or now - self.last_open >= float(cfg["cooldown"]) * 60):
            self.armed = True
        self.inside = d <= radius
        if not self.inside or not self.armed:
            return False, None
        speed_kmh = self._speed_kmh(fix, now)
        if speed_kmh is not None:
            if speed_kmh < float(cfg["minSpeed"]):
                return False, "too slow"
            if speed_kmh > float(cfg["maxSpeed"]):
                return False, "too fast"
        elif float(cfg["minSpeed"]) > 0 and not self._closing():
            return False, "not moving"
        if cfg.get("approachOnly", True) and not self._closing():
            return False, "not approaching"
        self.armed = False
        self.last_open = now
        return True, None

    def _closing(self):
        """the distance fell by at least 3 m over the last 2 to 15 seconds"""
        if len(self.history) < 2:
            return False
        t_now, d_now = self.history[-1]
        older = [x for (t, x) in self.history[:-1] if t_now - t >= 1.5]
        if not older:
            older = [self.history[0][1]]
        return max(older) - d_now >= 3.0

    def _speed_kmh(self, fix, now):
        if fix.get("speed") is not None:
            return fix["speed"] * 3.6
        return None


# ------------------------------------------------------------------ the service

class Gates:
    def __init__(self, client=None):
        self.client = client or palgate.Client(API_BASE)
        self.lock = threading.RLock()
        self.cond = threading.Condition(self.lock)
        self.account = read_json("account.json", {}) or None
        self.settings = read_json("settings.json", {})
        self.settings.setdefault("gates", {})
        cache = read_json("gates.json", {})
        self.gates = cache.get("gates", []) if isinstance(cache.get("gates"), list) else []
        self.listed_at = cache.get("at", 0)
        self.list_error = None
        self.link = {"state": "idle"}
        self.events = []
        self.seq = 0
        self.fix = None
        self.fix_at = 0.0
        self.trackers = {}
        self.opening = {}  # gate id -> monotonic time of the last open sent
        self._link_gen = 0

    # ---------------- what the page sees
    def gate_settings(self, gid):
        s = self.settings["gates"].get(gid) or {}
        auto = dict(AUTO_DEFAULTS)
        auto.update({k: v for k, v in (s.get("auto") or {}).items() if k in AUTO_DEFAULTS})
        # a gate the cloud cannot open (Bluetooth only) is not on the map unless the driver shows it
        local = any(g.get("localOnly") for g in self.gates if g["id"] == gid)
        return {"shown": s.get("shown", not local), "order": s.get("order"), "label": s.get("label", ""),
                "output": s.get("output"), "auto": auto}

    def state(self):
        with self.lock:
            gates = []
            for i, g in enumerate(self.gates):
                s = self.gate_settings(g["id"])
                tr = self.trackers.get(g["id"])
                outputs = g.get("outputs") or [1]
                out = s["output"] if s["output"] in outputs else outputs[0]
                gates.append(dict(g, shown=bool(s["shown"]), order=s["order"] if isinstance(s["order"], int) else 1000 + i,
                                  label=s["label"], output=out, auto=s["auto"],
                                  autoState={"armed": bool(tr and tr.armed),
                                             "distance": None if not tr or tr.distance is None else round(tr.distance)}))
            gates.sort(key=lambda g: (g["order"], g["name"].lower()))
            fresh = self.fix is not None and time.monotonic() - self.fix_at < FIX_STALE_S
            return {
                "linked": self.account is not None,
                "account": {"phone": mask_phone(self.account.get("phone")), "linkedAt": self.account.get("linkedAt", 0)}
                if self.account else None,
                "gates": gates,
                "listedAt": self.listed_at,
                "listError": self.list_error,
                "link": self.link_state(),
                "position": {"lat": self.fix["lat"], "lon": self.fix["lon"], "accuracy": self.fix["accuracy"],
                             "age": round(time.monotonic() - self.fix_at, 1)} if fresh else None,
                "autoOpenFeed": bool(GPS_FEED),
                "seq": self.seq,
            }

    def link_state(self):
        st = {k: v for k, v in self.link.items() if k != "id"}
        if st.get("state") == "waiting":
            st["expiresIn"] = max(0, round(self.link["expires"] - time.monotonic()))
            st.pop("expires", None)
        return st

    # ---------------- events
    def event(self, kind, gate, ok, message):
        with self.cond:
            self.seq += 1
            ev = {"seq": self.seq, "at": time.time(), "kind": kind, "gate": gate.get("id"),
                  "name": gate.get("label") or gate.get("name"), "ok": ok, "message": message}
            self.events = (self.events + [ev])[-MAX_EVENTS:]
            self.cond.notify_all()
            return ev

    def events_after(self, after, wait):
        deadline = time.monotonic() + min(25.0, max(0.0, wait))
        with self.cond:
            while True:
                evs = [e for e in self.events if e["seq"] > after]
                left = deadline - time.monotonic()
                if evs or left <= 0:
                    return {"seq": self.seq, "events": evs}
                self.cond.wait(left)

    # ---------------- linking
    def link_start(self):
        with self.lock:
            self._link_gen += 1
            gen = self._link_gen
            link_id, qr_text = self.client.link_begin()
            self.link = {"state": "waiting", "id": link_id, "expires": time.monotonic() + LINK_TIMEOUT_S,
                         "qr": tlqr.svg(qr_text, module=6)}
        threading.Thread(target=self._link_wait, args=(gen, link_id), daemon=True).start()
        return self.link_state()

    def link_cancel(self):
        with self.lock:
            self._link_gen += 1
            if self.link.get("state") == "waiting":
                self.link = {"state": "idle"}
            return self.link_state()

    def _link_wait(self, gen, link_id):
        while True:
            with self.lock:
                if gen != self._link_gen:
                    return
                if time.monotonic() > self.link["expires"]:
                    self.link = {"state": "expired", "message": "The code was not scanned in time."}
                    return
            try:
                acc = self.client.link_poll(link_id)
            except palgate.Error as e:
                if e.fatal:
                    with self.lock:
                        if gen == self._link_gen:
                            self.link = {"state": "failed", "message": str(e)}
                    return
                acc = None
            if acc is not None:
                with self.lock:
                    if gen != self._link_gen:
                        return
                    acc["linkedAt"] = int(time.time())
                    write_json("account.json", acc)
                    self.account = acc
                    self.link = {"state": "linked"}
                log("linked to an account")
                self.refresh()
                return
            time.sleep(LINK_POLL_S)

    def unlink(self):
        with self.lock:
            acc = self.account
        if acc:
            try:
                self.client.unlink(acc)
            except palgate.Error as e:
                # the device forgets the account anyway; the phone app may list it until removed there
                log("PalGate did not confirm the unlink: %s" % e.code)
        with self.lock:
            self._link_gen += 1
            self.account = None
            self.link = {"state": "idle"}
            self.gates = []
            self.listed_at = 0
            self.list_error = None
            remove("account.json")
            remove("gates.json")
        log("unlinked")

    # ---------------- the gate list
    def refresh(self):
        with self.lock:
            acc = self.account
        if not acc:
            return False, "Not linked to a PalGate account."
        try:
            gates = self.client.devices(acc)
        except palgate.Error as e:
            with self.lock:
                self.list_error = str(e)
            log("gate list not read: %s" % e.code)
            return False, str(e)
        with self.lock:
            if self.account is not acc:
                return False, "Unlinked meanwhile."
            self.gates = gates
            self.listed_at = int(time.time())
            self.list_error = None
            write_json("gates.json", {"at": self.listed_at, "gates": gates})
        return True, "%d gates" % len(gates)

    def refresher(self):
        while True:
            time.sleep(60)
            with self.lock:
                due = self.account is not None and time.time() - self.listed_at > REFRESH_S
            if due:
                self.refresh()

    # ---------------- settings
    def update_settings(self, req):
        if not isinstance(req, dict):
            raise ValueError("not an object")
        with self.lock:
            known = {g["id"]: g for g in self.gates}
            new = json.loads(json.dumps(self.settings))
            gs = new["gates"]
            if "order" in req:
                order = req["order"]
                if not isinstance(order, list) or not all(isinstance(x, str) and x in known for x in order):
                    raise ValueError("order")
                for i, gid in enumerate(order):
                    gs.setdefault(gid, {})["order"] = i
            for item in req.get("gates", []) if isinstance(req.get("gates", []), list) else [None]:
                if not isinstance(item, dict) or item.get("id") not in known:
                    raise ValueError("gate")
                s = gs.setdefault(item["id"], {})
                g = known[item["id"]]
                for k, v in item.items():
                    if k == "id":
                        continue
                    if k == "shown" and isinstance(v, bool):
                        s["shown"] = v
                    elif k == "order" and isinstance(v, int) and not isinstance(v, bool) and 0 <= v < 1000:
                        s["order"] = v
                    elif k == "label" and isinstance(v, str) and len(v) <= LABEL_MAX and "\n" not in v:
                        s["label"] = v.strip()
                    elif k == "output" and v in (g.get("outputs") or [1]):
                        s["output"] = v
                    elif k == "auto" and isinstance(v, dict):
                        s["auto"] = self._valid_auto(s.get("auto") or {}, v)
                    else:
                        raise ValueError(k)
            self.settings = new
            write_json("settings.json", new)
            for item in req.get("gates", []) if isinstance(req.get("gates"), list) else []:
                if "auto" in item:
                    self.trackers.pop(item["id"], None)  # a changed spot or circle starts unarmed
        return self.state()

    @staticmethod
    def _valid_auto(cur, v):
        out = dict(AUTO_DEFAULTS)
        out.update({k: x for k, x in cur.items() if k in AUTO_DEFAULTS})
        for k, x in v.items():
            if k in ("enabled", "approachOnly"):
                if not isinstance(x, bool):
                    raise ValueError(k)
                out[k] = x
            elif k in AUTO_LIMITS:
                lo, hi = AUTO_LIMITS[k]
                if isinstance(x, bool) or not isinstance(x, (int, float)) or not lo <= x <= hi:
                    raise ValueError(k)
                out[k] = x
            elif k in ("lat", "lon"):
                lim = 90 if k == "lat" else 180
                if x is not None and (isinstance(x, bool) or not isinstance(x, (int, float)) or not -lim <= x <= lim):
                    raise ValueError(k)
                out[k] = x
            else:
                raise ValueError(k)
        if out["minSpeed"] >= out["maxSpeed"]:
            raise ValueError("speeds")
        if out["enabled"] and (out["lat"] is None or out["lon"] is None):
            raise ValueError("an auto-open gate needs its position")
        return out

    def set_position(self, gid):
        with self.lock:
            if gid not in {g["id"] for g in self.gates}:
                raise ValueError("gate")
            if self.fix is None or time.monotonic() - self.fix_at > FIX_STALE_S:
                return None
            acc = self.fix.get("accuracy")
            if acc is not None and acc > 50:
                raise ValueError("The car's position is not accurate enough (%d m). Try again in the open." % round(acc))
            cur = (self.settings["gates"].get(gid) or {}).get("auto") or {}
            lat, lon = round(self.fix["lat"], 6), round(self.fix["lon"], 6)
        self.update_settings({"gates": [{"id": gid, "auto": dict(cur, lat=lat, lon=lon)}]})
        return {"lat": lat, "lon": lon, "accuracy": acc}

    # ---------------- opening
    def open(self, gid, output=None, kind="open"):
        with self.lock:
            g = next((x for x in self.gates if x["id"] == gid), None)
            acc = self.account
            if g is None:
                raise ValueError("gate")
            s = self.gate_settings(gid)
            outputs = g.get("outputs") or [1]
            if output is None:
                output = s["output"] if s["output"] in outputs else outputs[0]
            if output not in outputs:
                raise ValueError("output")
            gv = dict(g, label=s["label"])
            if acc is None:
                return False, "Not linked to a PalGate account.", None
            if g.get("localOnly"):
                return False, "This gate opens only over Bluetooth, from the phone.", None
            now = time.monotonic()
            if now - self.opening.get(gid, -1e9) < OPEN_DEBOUNCE_S:
                return True, "Already sent.", None
            self.opening[gid] = now
        try:
            self.client.open(acc, gid, output)
            ok, msg, code = True, "Opened", "ok"
        except palgate.Error as e:
            ok, msg, code = False, str(e), e.code
            with self.lock:
                self.opening.pop(gid, None)  # a failed open may be tried again at once
        log("%s gate %s output %d: %s" % ("auto-open" if kind == "auto" else "open", gid, output, code))
        return ok, msg, self.event(kind, gv, ok, msg)

    # ---------------- the car's position
    def on_fix(self, fix):
        fire = []
        with self.lock:
            self.fix, self.fix_at = fix, time.monotonic()
            now = self.fix_at
            if self.account is None:
                return
            for g in self.gates:
                cfg = self.gate_settings(g["id"])["auto"]
                tr = self.trackers.setdefault(g["id"], AutoTracker())
                go, _why = tr.decide(cfg, fix, now)
                if go:
                    fire.append(g["id"])
        for gid in fire:
            threading.Thread(target=self.open, args=(gid, None, "auto"), daemon=True).start()

    def gps_reader(self):
        host, _, port = GPS_FEED.rpartition(":")
        while True:
            try:
                with socket.create_connection((host or "127.0.0.1", int(port)), timeout=30) as s:
                    s.settimeout(None)
                    buf = b""
                    while True:
                        chunk = s.recv(4096)
                        if not chunk:
                            break
                        buf += chunk
                        while b"\n" in buf:
                            line, buf = buf.split(b"\n", 1)
                            fix = parse_fix(line.decode("ascii", "replace"))
                            if fix:
                                self.on_fix(fix)
            except (OSError, ValueError):
                pass
            time.sleep(2)


# ------------------------------------------------------------------ HTTP

GATES = None
ROUTES_GET = {"gates", "gatesLink", "gatesEvents"}
ROUTES_POST = {"gatesLink", "gatesUnlink", "gatesRefresh", "gatesSettings", "gatesPosition", "gatesOpen"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "teslalinux-gates"
    sys_version = ""

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _route(self):
        u = urllib.parse.urlsplit(self.path)
        return (u.path[len("/api/"):] if u.path.startswith("/api/") else None), urllib.parse.parse_qs(u.query)

    def do_GET(self):
        route, q = self._route()
        if route == "gates":
            return self._send(200, GATES.state())
        if route == "gatesLink":
            with GATES.lock:
                return self._send(200, GATES.link_state())
        if route == "gatesEvents":
            try:
                after = int((q.get("after") or ["0"])[0])
                wait = float((q.get("wait") or ["0"])[0])
            except ValueError:
                return self._send(400, {"ok": False, "message": "bad query"})
            return self._send(200, GATES.events_after(after, wait))
        self._send(404, {"ok": False, "message": "no such route"})

    def do_POST(self):
        route, _q = self._route()
        n = int(self.headers.get("Content-Length") or 0)
        if n > 65536:
            return self._send(413, {"ok": False, "message": "too large"})
        try:
            req = json.loads(self.rfile.read(n).decode("utf-8") or "{}") if n else {}
        except (ValueError, UnicodeDecodeError):
            return self._send(400, {"ok": False, "message": "not JSON"})
        if not isinstance(req, dict):
            return self._send(400, {"ok": False, "message": "not an object"})
        try:
            if route == "gatesLink":
                if req.get("action") == "start":
                    return self._send(200, GATES.link_start())
                if req.get("action") == "cancel":
                    return self._send(200, GATES.link_cancel())
                return self._send(400, {"ok": False, "message": "action"})
            if route == "gatesUnlink":
                GATES.unlink()
                return self._send(200, {"ok": True})
            if route == "gatesRefresh":
                ok, msg = GATES.refresh()
                return self._send(200 if ok else 502, {"ok": ok, "message": msg})
            if route == "gatesSettings":
                return self._send(200, GATES.update_settings(req))
            if route == "gatesPosition":
                pos = GATES.set_position(req.get("id"))
                if pos is None:
                    return self._send(409, {"ok": False, "message": "The car's position is not known. Turn on Car's position in Settings > Waze."})
                return self._send(200, dict(pos, ok=True))
            if route == "gatesOpen":
                out = req.get("output")
                if out is not None and (isinstance(out, bool) or not isinstance(out, int)):
                    return self._send(400, {"ok": False, "message": "output"})
                ok, msg, ev = GATES.open(req.get("id"), out)
                return self._send(200 if ok else 502, {"ok": ok, "message": msg, "event": ev})
        except palgate.Error as e:
            return self._send(502, {"ok": False, "message": str(e)})
        except ValueError as e:
            return self._send(400, {"ok": False, "message": str(e)})
        self._send(404, {"ok": False, "message": "no such route"})


def main():
    global GATES
    GATES = Gates()
    threading.Thread(target=GATES.refresher, daemon=True).start()
    if GPS_FEED:
        threading.Thread(target=GATES.gps_reader, daemon=True).start()
    if GATES.account and not GATES.gates:
        threading.Thread(target=GATES.refresh, daemon=True).start()
    srv = ThreadingHTTPServer((BIND, PORT), Handler)
    srv.daemon_threads = True
    srv.serve_forever()


if __name__ == "__main__":
    main()
