"""Waze, driven from outside the app: what the MCP tools do.

Car mode (TESLALINUX_APP_INTERFACE=car): ATL's car-app host serves Waze's car interface on the car
app channel (ws://127.0.0.1:9995, docs/UPSTREAM-NOTES.md "Car app templates"). This connects to it
as one more renderer, for a moment:
  * a destination goes as {"type":"navigate","uri":"geo:...","id":N}: the host hands Waze
    CarContext.ACTION_NAVIGATE with the geo: URI through ICarApp.onNewIntent, as Android Auto does
    when the driver asks the car's assistant for a place (Waze searches it and drives there);
  * the end of a drive goes as {"type":"stopNavigation","id":N}: NavigationManagerCallback.
    onStopNavigation, the host's own end-drive control;
  * the trip comes from the state every new renderer gets: {"type":"navigation","active":B} and
    {"type":"trip","trip":T} (Waze's NavigationManager.updateTrip: destination, travel estimates,
    steps, current road).

Phone mode: Waze's phone activities take view intents (deep links). ATL's intent channel
(127.0.0.1:9993, android/atl/ATLIntentChannel) starts one the way `am start -a VIEW -d URI` does:
  * a destination: waze://?q=TEXT&navigate=yes or waze://?ll=LAT,LON&navigate=yes (Waze's
    published deep links);
  * ending a drive, the trip and search have no path from outside in Phone mode: those tools say
    so (stop_navigation below has the details).
"""
import base64
import json
import os
import re
import socket
import struct
import time
import urllib.parse

CARAPP_ADDR = os.environ.get("TESLALINUX_GROK_CARAPP", "127.0.0.1:9995")
INTENT_ADDR = os.environ.get("TESLALINUX_GROK_INTENT", "127.0.0.1:9993")
INTERFACE_ENV = os.environ.get("TESLALINUX_GROK_INTERFACE_ENV", "/etc/teslalinux/interface.env")

STATE_QUIET_S = 0.4      # the burst of state a new renderer gets is over after this much quiet
STATE_MAX_S = 4.0
REPLY_S = 10.0           # the host's answer to navigate / stopNavigation
START_WAIT_S = 25.0      # how long navigate_to waits to see the drive start

DISTANCE_UNITS = {1: "m", 2: "km", 3: "km", 4: "mi", 5: "mi", 6: "ft", 7: "yd"}
# androidx.car.app.navigation.model.Maneuver TYPE_* constants
MANEUVERS = {
    0: "unknown", 1: "depart", 2: "name change", 3: "keep left", 4: "keep right", 5: "slight left",
    6: "slight right", 7: "turn left", 8: "turn right", 9: "sharp left", 10: "sharp right",
    11: "U-turn left", 12: "U-turn right", 13: "on-ramp slight left", 14: "on-ramp slight right",
    15: "on-ramp left", 16: "on-ramp right", 17: "on-ramp sharp left", 18: "on-ramp sharp right",
    19: "on-ramp U-turn left", 20: "on-ramp U-turn right", 21: "exit slight left", 22: "exit slight right",
    23: "exit left", 24: "exit right", 25: "fork left", 26: "fork right", 27: "merge left",
    28: "merge right", 29: "merge", 30: "enter roundabout", 31: "exit roundabout",
    32: "roundabout (clockwise)", 33: "roundabout (clockwise)", 34: "roundabout (counterclockwise)",
    35: "roundabout (counterclockwise)", 36: "straight", 37: "ferry", 38: "train ferry",
    39: "arrive", 40: "arrive ahead", 41: "arrive on the left", 42: "arrive on the right",
    43: "enter roundabout", 44: "exit roundabout", 45: "enter roundabout", 46: "exit roundabout",
    47: "ferry left", 48: "ferry right", 49: "train ferry left", 50: "train ferry right",
}


class WazeError(Exception):
    """Something the caller is told as a tool error."""


def _addr(s):
    host, _, port = s.rpartition(":")
    return host, int(port)


def interface():
    """"car" or "phone": the app interface chosen on the settings page (interface.env)."""
    try:
        with open(INTERFACE_ENV) as f:
            for line in f:
                line = line.strip()
                if line.startswith("TESLALINUX_APP_INTERFACE="):
                    v = line.split("=", 1)[1].strip().strip('"\'')
                    return "car" if v == "car" else "phone"
    except OSError:
        pass
    return "phone"


# ------------------------------------------------------------------ a minimal WebSocket client

class WebSocket:
    """RFC 6455 client for one loopback text-frame channel: masked frames out, pings answered."""

    def __init__(self, addr, timeout=5.0):
        host, port = _addr(addr)
        try:
            self.sock = socket.create_connection((host, port), timeout=timeout)
        except OSError as e:
            raise WazeError("Waze's car interface is not reachable (%s:%d: %s). Waze may be starting or stopped." % (host, port, e.strerror or e))
        key = base64.b64encode(os.urandom(16)).decode()
        req = ("GET / HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n") % (host, port, key)
        self.sock.sendall(req.encode())
        self.buf = b""
        head = self._read_until(b"\r\n\r\n", 16384)
        if not head.startswith(b"HTTP/1.1 101"):
            self.close()
            raise WazeError("the car app channel refused the connection")

    def _read_until(self, mark, limit):
        while mark not in self.buf:
            if len(self.buf) > limit:
                raise WazeError("bad handshake")
            chunk = self.sock.recv(4096)
            if not chunk:
                raise WazeError("the car app channel closed")
            self.buf += chunk
        head, self.buf = self.buf.split(mark, 1)
        return head

    def _read_exact(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(max(4096, n - len(self.buf)))
            if not chunk:
                raise ConnectionError("closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def send_text(self, text):
        self._send(0x1, text.encode("utf-8"))

    def _send(self, opcode, payload):
        mask = os.urandom(4)
        n = len(payload)
        hdr = bytes([0x80 | opcode])
        if n < 126:
            hdr += bytes([0x80 | n])
        elif n < 65536:
            hdr += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            hdr += bytes([0x80 | 127]) + struct.pack(">Q", n)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(hdr + mask + masked)

    def recv_text(self, timeout):
        """The next text message, or None when nothing came within timeout seconds."""
        self.sock.settimeout(timeout)
        message = b""
        while True:
            try:
                b0, b1 = self._read_exact(2)
            except socket.timeout:
                return None
            self.sock.settimeout(5.0)  # the rest of a frame that has begun
            opcode, n = b0 & 0x0F, b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._read_exact(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._read_exact(8))[0]
            mask = self._read_exact(4) if b1 & 0x80 else None
            payload = self._read_exact(n)
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if opcode == 0x9:
                self._send(0xA, payload)
            elif opcode == 0x8:
                raise ConnectionError("closed by the host")
            elif opcode in (0x0, 0x1):
                message += payload
                if b0 & 0x80:
                    return message.decode("utf-8", "replace")
            self.sock.settimeout(timeout)

    def close(self):
        try:
            self._send(0x8, b"\x03\xe8")
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


class CarSession:
    """One short connection to the car app channel: the state it sends, then requests."""

    def __init__(self, addr=None):
        self.ws = WebSocket(addr or CARAPP_ADDR)
        self.navigating = None
        self.trip = None
        self.template = None
        self.seen_trip = False
        # the app's alerts (IAppHost.showAlert) and toasts that arrived while connected
        self.alerts = []
        self.toasts = []
        self._id = 0
        try:
            self._drain(STATE_QUIET_S, STATE_MAX_S)
        except (OSError, ConnectionError) as e:
            self.ws.close()
            raise WazeError("the car app channel failed: %s" % e)

    def close(self):
        self.ws.close()

    def _take(self, text):
        try:
            m = json.loads(text)
        except ValueError:
            return None
        t = m.get("type")
        if t == "navigation":
            self.navigating = bool(m.get("active"))
        elif t == "trip":
            self.trip = m.get("trip")
            self.seen_trip = True
        elif t == "template":
            self.template = m.get("template")
        elif t == "alert" and isinstance(m.get("alert"), dict):
            self.alerts.append(m["alert"])
        elif t == "toast" and isinstance(m.get("text"), str):
            self.toasts.append(m["text"])
        return m

    def _drain(self, quiet, most):
        end = time.monotonic() + most
        while time.monotonic() < end:
            text = self.ws.recv_text(quiet)
            if text is None:
                return
            self._take(text)

    def request(self, msg):
        """Sends a request with an id; its answer ({ok, error}) or WazeError."""
        self._id += 1
        msg = dict(msg, id=self._id)
        want = msg["type"] + "Result"
        try:
            self.ws.send_text(json.dumps(msg))
            end = time.monotonic() + REPLY_S
            while time.monotonic() < end:
                text = self.ws.recv_text(max(0.05, end - time.monotonic()))
                if text is None:
                    break
                m = self._take(text)
                if m and m.get("type") == want and m.get("id") == self._id:
                    return m
        except (OSError, ConnectionError) as e:
            raise WazeError("the car app channel failed: %s" % e)
        raise WazeError("the car host did not answer (this runtime may not support %s)" % msg["type"])

    def wait(self, until, seconds):
        """Reads state until until(self) is true or seconds pass; whether it became true."""
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if until(self):
                return True
            try:
                text = self.ws.recv_text(max(0.05, min(1.0, end - time.monotonic())))
            except (OSError, ConnectionError):
                return until(self)
            if text is not None:
                self._take(text)
        return until(self)

    def send(self, msg):
        try:
            self.ws.send_text(json.dumps(msg))
        except OSError as e:
            raise WazeError("the car app channel failed: %s" % e)

    # the app's screens ---------------------------------------------------------------

    def inner(self):
        """The current template's own model (the wrapper's "template"), or {}."""
        return (self.template or {}).get("template") or {}

    def stack(self):
        return [i.get("templateId") for i in (self.template or {}).get("templateInfosForScreenStack") or [] if isinstance(i, dict)]

    def wait_template(self, pred, seconds, what):
        """Waits for a template the predicate accepts (given the inner model); WazeError if none comes."""
        if self.wait(lambda x: bool(x.template) and pred(x.inner()), seconds):
            return self.inner()
        raise WazeError("Waze did not show %s within %d s." % (what, seconds))

    def settle(self, quiet=0.8, most=4.0):
        """Reads templates until none came for `quiet` seconds (an app refreshes a screen a few times)."""
        end = time.monotonic() + most
        while time.monotonic() < end:
            try:
                text = self.ws.recv_text(quiet)
            except (OSError, ConnectionError):
                return
            if text is None:
                return
            self._take(text)

    def click(self, cb):
        if not isinstance(cb, int):
            raise WazeError("Waze's screen has nothing to tap there.")
        self.send({"type": "click", "cb": cb})

    def back_to(self, base, seconds=10.0):
        """Back, one at a time, each after the template answering the one before, until the app's
        screen stack is `base` again (as the host does for screens a renderer left open)."""
        end = time.monotonic() + seconds
        for _ in range(12):
            if depth(self.stack(), base) == 0:
                return True
            t0 = self.template
            self.send({"type": "back"})
            if not self.wait(lambda x: x.template is not t0, max(0.1, end - time.monotonic())):
                return False
        return depth(self.stack(), base) == 0


def depth(stack, base):
    """How many screens lie above `base` in `stack` (template ids, top first), as ATL's host counts
    them: the screens under the base's top keep their ids; the base's top may be refreshed."""
    n = len(base)
    if n == 0:
        return 0
    under = base[1:]
    if len(stack) > n and (not under or stack[-len(under):] == under):
        return len(stack) - n
    return 0


def start_intent(uri, addr=None):
    """Hands Waze's phone activities a view intent through ATL's intent channel."""
    host, port = _addr(addr or INTENT_ADDR)
    try:
        with socket.create_connection((host, port), timeout=5) as s:
            s.sendall((json.dumps({"type": "startActivity", "action": "android.intent.action.VIEW", "data": uri}) + "\n").encode())
            s.settimeout(10)
            data = b""
            while not data.endswith(b"\n"):
                chunk = s.recv(4096)
                if not chunk:
                    break
                data += chunk
    except OSError as e:
        raise WazeError("Waze is not reachable (%s:%d: %s). Waze may be starting or stopped." % (host, port, e.strerror or e))
    try:
        r = json.loads(data.decode())
    except ValueError:
        raise WazeError("Waze's runtime gave no answer to the intent")
    if not r.get("ok"):
        raise WazeError("Waze did not take the intent: %s" % r.get("error"))
    return r


# ------------------------------------------------------------------ trip summaries

def text_of(v):
    """A CarText (a string, or {"$":"CarText","text":S,...}) as plain text."""
    if v is None:
        return None
    if isinstance(v, str):
        return v
    if isinstance(v, dict):
        t = v.get("text")
        if isinstance(t, str):
            return t
    return None


def distance_of(d):
    if not isinstance(d, dict):
        return None
    value = d.get("displayDistance")
    unit = DISTANCE_UNITS.get(d.get("displayUnit"))
    if not isinstance(value, (int, float)) or unit is None:
        return None
    v = round(value, 1)
    return {"value": int(v) if v == int(v) else v, "unit": unit, "text": "%s %s" % (int(v) if v == int(v) else v, unit)}


def arrival_of(t):
    if not isinstance(t, dict):
        return None
    ms = t.get("timeSinceEpochMillis")
    if not isinstance(ms, (int, float)) or ms <= 0:
        return None
    off = t.get("zoneOffsetSeconds") or 0
    local = time.gmtime(ms / 1000 + off)
    return {"epochMillis": int(ms), "local": time.strftime("%H:%M", local), "zone": t.get("zoneShortName")}


def summarize(trip, navigating):
    """The structured trip_status answer from the car host's state."""
    out = {"navigating": bool(navigating), "known": True}
    if not navigating:
        return out
    if not isinstance(trip, dict):
        out["known"] = False
        out["note"] = "Waze is navigating but has not reported its trip details yet."
        return out
    dests = trip.get("destinations") or []
    ests = trip.get("destinationTravelEstimates") or []
    if dests:
        d = dests[0] if isinstance(dests[0], dict) else {}
        out["destination"] = {k: v for k, v in (("name", text_of(d.get("name"))), ("address", text_of(d.get("address")))) if v}
    if ests and isinstance(ests[0], dict):
        e = ests[0]
        dist = distance_of(e.get("remainingDistance"))
        if dist:
            out["distanceLeft"] = dist
        secs = e.get("remainingTimeSeconds")
        if isinstance(secs, (int, float)) and secs >= 0:
            out["timeLeftMinutes"] = int(round(secs / 60.0))
        arr = arrival_of(e.get("arrivalTimeAtDestination"))
        if arr:
            out["eta"] = arr
    steps = trip.get("steps") or []
    sests = trip.get("stepTravelEstimates") or []
    if steps and isinstance(steps[0], dict):
        s = steps[0]
        nxt = {}
        cue = text_of(s.get("cue"))
        if cue:
            nxt["instruction"] = cue
        man = s.get("maneuver")
        if isinstance(man, dict) and isinstance(man.get("type"), int) and man["type"] in MANEUVERS and man["type"] != 0:
            nxt["maneuver"] = MANEUVERS[man["type"]]
            if isinstance(man.get("roundaboutExitNumber"), int) and man["roundaboutExitNumber"] > 0:
                nxt["roundaboutExit"] = man["roundaboutExitNumber"]
        road = text_of(s.get("road"))
        if road:
            nxt["road"] = road
        if sests and isinstance(sests[0], dict):
            dist = distance_of(sests[0].get("remainingDistance"))
            # Waze sends 0 m with no time for a step it has no distance for (before the route)
            if dist and (dist["value"] > 0 or (sests[0].get("remainingTimeSeconds") or -1) >= 0):
                nxt["in"] = dist
        if nxt:
            out["nextTurn"] = nxt
    road = text_of(trip.get("currentRoad"))
    if road:
        out["currentRoad"] = road
    if trip.get("loading"):
        out["loading"] = True
    return out


def sentence(st):
    """One line an assistant can read out."""
    if not st.get("navigating"):
        return "Waze is not navigating."
    if not st.get("known", True) or "destination" not in st and "distanceLeft" not in st:
        return "Waze is navigating; the trip details are not known yet."
    parts = []
    d = st.get("destination") or {}
    name = d.get("name") or d.get("address")
    parts.append("Navigating to %s." % name if name else "Navigating.")
    if "eta" in st:
        parts.append("Arrival at %s." % st["eta"]["local"])
    if "timeLeftMinutes" in st or "distanceLeft" in st:
        left = []
        if "timeLeftMinutes" in st:
            left.append("%d min" % st["timeLeftMinutes"])
        if "distanceLeft" in st:
            left.append(st["distanceLeft"]["text"])
        parts.append(" and ".join(left) + " left.")
    n = st.get("nextTurn")
    if n:
        what = n.get("instruction") or n.get("maneuver")
        if what:
            parts.append("Next: %s%s." % (what, (" in " + n["in"]["text"]) if "in" in n else ""))
    return " ".join(parts)


# ------------------------------------------------------------------ Waze's car search

SEARCH_ICON = re.compile(r"(^|_)search(_|$)")
BIDI = re.compile("[‎‏‪-‮⁦-⁩]")
SEARCH_LIMIT = 5
PLACE_LIST = "PlaceListNavigationTemplate"


def plain(s):
    return BIDI.sub("", s).strip() if isinstance(s, str) else None


def walk(o):
    if isinstance(o, dict):
        yield o
        for v in o.values():
            yield from walk(v)
    elif isinstance(o, list):
        for v in o:
            yield from walk(v)


def cb_of(d):
    return ((d or {}).get("onClickDelegate") or {}).get("$cb")


def icon_name(action):
    return ((((action or {}).get("icon") or {}).get("icon")) or {}).get("resName")


def parse_row(row):
    """A result row of Waze's search list: its title, its information line, its distance."""
    out = {"name": plain(text_of(row.get("title")))}
    info = []
    for t in row.get("texts") or []:
        dist = None
        for sp in (t.get("spans") or []) if isinstance(t, dict) else []:
            span = sp.get("span") if isinstance(sp, dict) else None
            if isinstance(span, dict) and span.get("$") == "DistanceSpan":
                dist = distance_of(span.get("distance"))
        if dist:
            out["distance"] = dist
            continue
        s = plain(text_of(t))
        if s:
            info.append(s)
    if info:
        out["info"] = " • ".join(info)
    return out


def result_id(query, index, name):
    raw = json.dumps({"q": query, "i": index, "t": name}, ensure_ascii=False, separators=(",", ":")).encode()
    return "wz1." + base64.urlsafe_b64encode(raw).decode().rstrip("=")


def parse_result_id(rid):
    if not isinstance(rid, str) or not rid.startswith("wz1.") or len(rid) > 2000:
        raise WazeError("resultId is not an id from search_places")
    try:
        raw = base64.urlsafe_b64decode(rid[4:] + "=" * (-len(rid[4:]) % 4))
        d = json.loads(raw.decode())
        q, i, t = d["q"], d["i"], d["t"]
    except (ValueError, KeyError, TypeError, UnicodeDecodeError):
        raise WazeError("resultId is not an id from search_places")
    if not isinstance(q, str) or not isinstance(i, int) or not isinstance(t, str):
        raise WazeError("resultId is not an id from search_places")
    return q, i, t


class HeldSearch:
    """Waze's car search run out of the driver's sight: the car host's hold keeps the car screen
    on what it shows while the search screens go to this session alone; at the end the screens are
    popped back (or, after a drive was started, left as the app shows them) and the hold released.
    If this process dies meanwhile, the host pops them back itself."""

    def __init__(self, carapp=None):
        self.s = CarSession(carapp)
        if not self.s.template:
            self.s.close()
            raise WazeError("Waze's car session has not started yet.")
        r = self.s.request({"type": "hold", "on": True})
        if not r.get("ok"):
            self.s.close()
            raise WazeError("Waze's screens are busy: %s" % r.get("error"))
        self.base = self.s.stack()
        self.keep = False

    def close(self):
        try:
            if not self.keep:
                self.s.back_to(self.base)
            self.s.request({"type": "hold", "on": False})
        except WazeError:
            pass  # the host pops back what is left when the connection goes
        finally:
            self.s.close()

    def results(self, query):
        """Opens Waze's search, submits the query, and returns the result list's model."""
        s = self.s
        acts = [a for a in walk(s.inner()) if a.get("$") == "Action" and SEARCH_ICON.search(icon_name(a) or "")]
        if not acts:
            raise WazeError("Waze's current car screen offers no search (it may be showing a question or a menu).")
        s.click(cb_of(acts[0]))
        box = s.wait_template(lambda m: m.get("$") == "SearchTemplate", 8, "its search screen")
        cb = (box.get("searchCallbackDelegate") or {}).get("$cb")
        if not isinstance(cb, int):
            raise WazeError("Waze's search screen takes no text.")
        s.send({"type": "search", "cb": cb, "text": query, "submit": True})
        s.wait_template(lambda m: m.get("$") == PLACE_LIST and not m.get("loading"), 20, "search results")
        s.settle()
        model = s.inner()
        if model.get("$") != PLACE_LIST:
            raise WazeError("Waze left its search results before they could be read.")
        self.list_stack = s.stack()
        return model

    def rows(self):
        return [r for r in ((self.s.inner().get("itemList") or {}).get("items") or []) if isinstance(r, dict) and r.get("$") == "Row"]

    def details(self, index):
        """Opens result `index`'s place card, reads it, and returns to the list: [(title, text)], the
        card's model."""
        s = self.s
        rows = self.rows()
        if index >= len(rows):
            raise WazeError("the result is no longer in Waze's list")
        s.click(cb_of(rows[index]))
        s.wait_template(lambda m: m.get("$") == "MapTemplate" and isinstance(m.get("pane"), dict), 10, "the place's card")
        s.settle(0.6, 3.0)
        pane = s.inner().get("pane") or {}
        out = []
        for r in pane.get("rows") or []:
            if isinstance(r, dict):
                out.append((plain(text_of(r.get("title"))) or "", " ".join(x for x in (plain(text_of(t)) for t in r.get("texts") or []) if x)))
        return out, pane

    def back_to_list(self):
        if not self.s.back_to(self.list_stack, 8.0):
            raise WazeError("Waze did not go back to its search results.")
        self.s.settle(0.5, 2.0)


def address_of(details, name):
    """The address on a place card: its Location row (Waze's card: the place, then its location)."""
    for title, text in details:
        if title.lower() in ("location", "address") and text:
            return text
    rest = [d for d in details if d[0] != name]
    return rest[0][1] if rest and rest[0][1] else None


def search_places(args, mode=None, carapp=None, limit=SEARCH_LIMIT):
    query = args.get("query")
    if not isinstance(query, str) or not query.strip():
        raise WazeError("give the query text (a place, an address, or a kind of place such as 'gas station')")
    query = query.strip()
    if len(query) > 200:
        raise WazeError("the query is longer than 200 characters")
    if args.get("latitude") is not None or args.get("longitude") is not None:
        raise WazeError("Waze's car search looks around the car's position only; it cannot search near "
                        "other coordinates. Put the area in the query instead (for example 'pizza in Haifa').")
    n = args.get("limit", limit)
    if isinstance(n, bool) or not isinstance(n, int) or not 1 <= n <= 10:
        raise WazeError("limit must be a whole number from 1 to 10")
    if (mode or interface()) != "car":
        raise WazeError("Searching needs Car mode (Settings > Waze interface: Car). In Phone mode Waze's "
                        "search runs only on its own screen; navigate_to with the place's name searches it there.")
    h = HeldSearch(carapp)
    try:
        h.results(query)
        rows = h.rows()
        out = []
        for i, row in enumerate(rows[:n]):
            r = parse_row(row)
            if not r.get("name"):
                continue
            try:
                det, _ = h.details(i)
                addr = address_of(det, r["name"])
                if addr:
                    r["address"] = addr
                h.back_to_list()
            except WazeError:
                # the card did not open: the row's own line is all there is
                h.back_to_list()
            r["resultId"] = result_id(query, i, r["name"])
            out.append(r)
    finally:
        h.close()
    st = {"query": query, "results": out, "near": "the car's position",
          "coordinates": "not given: Waze's car search shows no coordinates"}
    if not out:
        return "Waze found nothing for %r." % query, st
    lines = ["%d. %s%s%s%s resultId=%s" % (k + 1, r["name"], (" — " + r["address"]) if r.get("address") else "",
                                           (" (%s)" % r["distance"]["text"]) if r.get("distance") else "",
                                           (" · " + r["info"]) if r.get("info") and r.get("info") != r.get("address") else "",
                                           r["resultId"]) for k, r in enumerate(out)]
    return ("Waze's results for %r (navigate_to with a resultId drives there):\n" % query) + "\n".join(lines), st


def navigate_result(rid, carapp=None, wait_s=START_WAIT_S):
    """Drives to a search_places result: Waze's search again with the same query, the same place
    picked (by its name; its position in the list when the name repeats), and its card's first
    action (Waze's "Go now")."""
    query, index, name = parse_result_id(rid)
    h = HeldSearch(carapp)
    try:
        h.results(query)
        rows = h.rows()
        names = [parse_row(r).get("name") for r in rows]
        if index < len(names) and names[index] == name:
            pick = index
        elif name in names:
            pick = names.index(name)
        else:
            raise WazeError("%s is no longer among Waze's results for %r; search again." % (name, query))
        _, pane = h.details(pick)
        go = [a for a in pane.get("actions") or [] if isinstance(a, dict) and isinstance(cb_of(a), int)]
        if not go:
            raise WazeError("Waze's card for %s offers no way to drive there." % name)
        h.s.click(cb_of(go[0]))
        started = h.s.wait(lambda x: bool(x.navigating and isinstance(x.trip, dict)
                                          and x.trip.get("destinationTravelEstimates")), wait_s)
        if h.s.navigating:
            h.keep = True  # the drive is what the car screen shows now
        st = summarize(h.s.trip, h.s.navigating)
    finally:
        h.close()
    if started:
        return sentence(st), dict(st, mode="car", place=name)
    if st.get("navigating"):
        return "Waze started driving to %s; the trip details are not known yet." % name, dict(st, mode="car", place=name)
    raise WazeError("Waze did not start driving to %s." % name)


# ------------------------------------------------------------------ Waze's car reports
#
# Waze's car interface reports through its map's Report button (resource icon_report_button, in the
# map action strip while the map follows the car): a grid of categories ("What do you see?"), then a
# grid of the category's kinds; tapping a kind sends the report at the position the car had when the
# Report button was tapped, and Waze shows an alert: a checkmark (car_lib_icon_outline_checkmark_
# with_bg_36px) when it was reported, icon_report_error when it failed. A kind can ask one more
# question (a closure: ahead or behind), answered with `option`. The categories and kinds are read
# from Waze's screens each time (they change with Waze's version, region and state); their ids are
# the icons' resource names (the same in every language), shortened.

REPORT_ICON = re.compile(r"(^|_)report_button$|^icon_report_button")
RECENTER_ICON = re.compile(r"recenter")
REPORTED_ICON = "car_lib_icon_outline_checkmark_with_bg_36px"
REPORT_ERROR_ICON = "icon_report_error"
# Waze's diagnostics entry in the same grid (it sends Waze's logs, not a road report)
NOT_A_REPORT = {"icon_report_debug"}
REPORT_RESULT_S = 12.0
TYPES_CACHE_S = 1800
_types_cache = {"t": 0.0, "types": None}


def report_id(res_name, title):
    if res_name:
        s = re.sub(r"^(icon_report_|car_report_menu_)", "", res_name)
        return re.sub(r"(_vector(_with_lane)?)$", "", s)
    return re.sub(r"[^a-z0-9]+", "_", (title or "").lower()).strip("_")


def grid_items(model):
    """The items of a grid screen (a GridTemplate, alone or as a MapWithContentTemplate's content):
    [{title, id, res, cb}]."""
    out = []
    for d in walk(model):
        if d.get("$") != "GridItem":
            continue
        res = ((((d.get("image") or {}).get("icon")) or {}).get("resName"))
        title = plain(text_of(d.get("title")))
        out.append({"title": title, "id": report_id(res, title), "res": res, "cb": cb_of(d)})
    return out


def is_report_grid(model):
    items = grid_items(model)
    return bool(items) and all((i["res"] or "").startswith(("icon_report_", "car_report_menu_")) for i in items)


def pick(items, want, what):
    """The item whose id or title is `want` (any case, spaces or underscores alike)."""
    norm = lambda s: re.sub(r"[\s_-]+", " ", (s or "").strip().lower())
    w = norm(want)
    for i in items:
        if w and (norm(i["id"]) == w or norm(i["title"]) == w):
            return i
    raise WazeError("Waze offers no %s %r now. It offers: %s." % (what, want, ", ".join("%s (%s)" % (i["title"], i["id"]) for i in items)))


class HeldReports(HeldSearch):
    """Waze's report screens worked out of the driver's sight, as HeldSearch does for search."""

    def open_menu(self):
        s = self.s
        acts = lambda: [a for a in walk(s.inner()) if a.get("$") == "Action" and REPORT_ICON.search(icon_name(a) or "")]
        if not acts():
            # Waze shows its Report button only while its map follows the car; its own
            # center-on-me button brings that back, as the driver would tap it
            rc = [a for a in walk(s.inner()) if a.get("$") == "Action" and RECENTER_ICON.search(icon_name(a) or "")]
            if rc:
                t0 = s.template
                s.click(cb_of(rc[0]))
                s.wait(lambda x: x.template is not t0 and bool(acts()), 5)
        if not acts():
            raise WazeError("Waze's car screen offers no Report button now (Waze shows it on its map while the map "
                            "follows the car, not over another screen or question).")
        s.click(cb_of(acts()[0]))
        s.wait_template(is_report_grid, 8, "its report menu")
        s.settle(0.6, 3.0)
        self.menu_stack = s.stack()
        return [i for i in grid_items(s.inner()) if i["res"] not in NOT_A_REPORT]

    def open_category(self, item):
        """The category's kinds (a grid), or None when Waze answers the tap with a question of its own
        (a closure while driving: "Report a closure on your route", Report or Cancel)."""
        s = self.s
        t0 = s.template
        s.click(item["cb"])
        s.wait_template(lambda m: s.template is not t0 and len(s.stack()) > len(self.menu_stack),
                        8, "the kinds of %s" % item["title"])
        s.settle(0.6, 3.0)
        return grid_items(s.inner()) if is_report_grid(s.inner()) else None

    def back_to_menu(self):
        if not self.s.back_to(self.menu_stack, 8.0):
            raise WazeError("Waze did not go back to its report menu.")
        self.s.settle(0.5, 2.0)


def report_types(args=None, mode=None, carapp=None, fresh=False):
    """Waze's report categories and their kinds, as its car screens offer them now."""
    if (mode or interface()) != "car":
        raise WazeError("Reports need Car mode (Settings > Waze interface: Car). In Phone mode Waze's report "
                        "screens are not reachable from outside the app.")
    now = time.monotonic()
    if not fresh and _types_cache["types"] is not None and now - _types_cache["t"] < TYPES_CACHE_S:
        types = _types_cache["types"]
    else:
        h = HeldReports(carapp)
        try:
            types = []
            order = [c["id"] for c in h.open_menu()]
            for cid in order:
                # the menu comes back with new callbacks after each back: read it again
                cats = {c["id"]: c for c in grid_items(h.s.inner()) if c["res"] not in NOT_A_REPORT}
                if cid not in cats:
                    raise WazeError("Waze left its report menu.")
                kinds = h.open_category(cats[cid])
                entry = {"id": cid, "title": cats[cid]["title"], "kinds": [{"id": k["id"], "title": k["title"]} for k in kinds or []]}
                if kinds is None:
                    q, buttons = question_of(h.s.inner())
                    entry["asks"] = {"question": q, "answers": [b["title"] for b in buttons]}
                types.append(entry)
                h.back_to_menu()
        finally:
            h.close()
        _types_cache.update(t=time.monotonic(), types=types)
    lines = ["%s (%s): %s" % (c["title"], c["id"], ", ".join("%s (%s)" % (k["title"], k["id"]) for k in c["kinds"])
                              if c["kinds"] else "no kinds; Waze asks: %s (%s)" % (c["asks"]["question"], ", ".join(c["asks"]["answers"])))
             for c in types]
    return "Waze's report types (category: kinds; ids in brackets):\n" + "\n".join(lines), {"categories": types}


def report(args, mode=None, carapp=None):
    """Files a report through Waze's car report screens; Waze's own answer says whether it took it."""
    category, kind, option = args.get("category"), args.get("kind"), args.get("option")
    for name, v in (("category", category), ("kind", kind), ("option", option)):
        if v is not None and (not isinstance(v, str) or len(v) > 80):
            raise WazeError("%s must be a short text (a title or an id from report_types)" % name)
    if not category or not category.strip():
        raise WazeError("give the category (for example Police, Crash, Hazard, Traffic; report_types lists them)")
    if (mode or interface()) != "car":
        raise WazeError("Reports need Car mode (Settings > Waze interface: Car). In Phone mode Waze's report "
                        "screens are not reachable from outside the app.")
    h = HeldReports(carapp)
    s = h.s
    answered = None
    try:
        cat = pick(h.open_menu(), category, "report category")
        before = len(s.alerts)
        verdict = lambda x: [a for a in x.alerts[before:] if alert_icon(a) in (REPORTED_ICON, REPORT_ERROR_ICON)]
        kinds = h.open_category(cat)
        if kinds is None:
            # the category is the report itself, and Waze asks to confirm it (a closure)
            if kind and kind.strip():
                pick([cat], kind, "kind of %s" % cat["title"])  # only the category itself fits
            k = {"id": cat["id"], "title": cat["title"]}
            answered = answer(s, option, cat["title"])
            s.wait(lambda x: bool(verdict(x)), REPORT_RESULT_S)
        else:
            if kind and kind.strip():
                k = pick(kinds, kind, "kind of %s" % cat["title"])
            else:
                same = [x for x in kinds if x["id"] == cat["id"] or (x["title"] or "").lower() == (cat["title"] or "").lower()]
                if len(kinds) == 1:
                    k = kinds[0]
                elif same:
                    k = same[0]
                else:
                    raise WazeError("Say which kind of %s: %s." % (cat["title"], ", ".join("%s (%s)" % (x["title"], x["id"]) for x in kinds)))
            t0 = s.template
            s.click(k["cb"])
            # Waze answers with its alert (after leaving its report screens), or asks one more
            # question on a screen of its own above the map first
            asks = lambda x: x.template is not t0 and not is_report_grid(x.inner()) and depth(x.stack(), h.base) > 0
            s.wait(lambda x: bool(verdict(x)) or asks(x), REPORT_RESULT_S)
            if not verdict(s) and asks(s):
                s.settle(0.5, 2.0)
                answered = answer(s, option, k["title"])
                s.wait(lambda x: bool(verdict(x)), REPORT_RESULT_S)
        got = verdict(s)
    finally:
        h.close()
    what = "%s: %s" % (cat["title"], k["title"]) + ((" (%s)" % answered) if answered and answered != "confirmed" else "")
    st = {"category": cat["id"], "kind": k["id"], "title": what}
    if not got:
        st["reported"] = None
        return ("Sent %s to Waze, but Waze did not confirm it within %d s." % (what, REPORT_RESULT_S)), st
    a = got[0]
    st["reported"] = alert_icon(a) == REPORTED_ICON
    st["waze"] = plain(text_of(a.get("title")))
    if not st["reported"]:
        raise WazeError("Waze could not send the report (%s): %s" % (what, st["waze"] or "it reported an error"))
    return "Reported %s. Waze: %s" % (what, st["waze"] or "reported"), st


def question_of(model):
    """A question screen of Waze's (a MessageTemplate or a pane): its text and its titled buttons
    [{title, id, cb, primary}]."""
    texts = []
    for d in walk(model):
        if d.get("$") in ("Header", "Row", "MessageTemplate", "LongMessageTemplate", "Pane"):
            for x in (plain(text_of(d.get("title"))), plain(text_of(d.get("message")))):
                if x and x not in texts:
                    texts.append(x)
    buttons = []
    for a in walk(model):
        t = plain(text_of(a.get("title"))) if a.get("$") == "Action" else None
        if t and isinstance(cb_of(a), int):
            # Action.FLAG_PRIMARY (1): the answer the app offers first
            buttons.append({"title": t, "id": t, "cb": cb_of(a), "primary": bool((a.get("flags") or 0) & 1)})
    return " ".join(texts), buttons


def answer(s, option, label):
    """Answers the question Waze asks before it sends a report: `option` by its title; without one,
    Waze's primary button when it offers exactly one (its confirmation: the driver's request is the
    confirmation); otherwise nothing is sent and the question goes back to the caller."""
    q, buttons = question_of(s.inner())
    if not buttons:
        raise WazeError("Waze showed a screen this cannot answer after %s. Nothing was reported." % label)
    if option and option.strip():
        b = pick(buttons, option, "answer")
    else:
        primary = [b for b in buttons if b["primary"]]
        if len(primary) != 1:
            raise WazeError("Waze asks: %s Answer with option: %s. Nothing was reported." % (
                q or "one more question.", ", ".join(b["title"] for b in buttons)))
        b = primary[0]
    s.click(b["cb"])
    return b["title"] if option else "confirmed"


def alert_icon(alert):
    return ((((alert or {}).get("icon") or {}).get("icon")) or {}).get("resName")


# ------------------------------------------------------------------ the tools

def geo_uri(destination=None, lat=None, lon=None):
    if lat is not None:
        return "geo:%s,%s?q=%s" % (_num(lat), _num(lon), urllib.parse.quote("%s,%s" % (_num(lat), _num(lon)), safe=","))
    return "geo:0,0?q=" + urllib.parse.quote(destination, safe="")


def waze_uri(destination=None, lat=None, lon=None):
    if lat is not None:
        return "waze://?ll=%s,%s&navigate=yes" % (_num(lat), _num(lon))
    return "waze://?q=%s&navigate=yes" % urllib.parse.quote(destination, safe="")


def _num(x):
    return ("%.6f" % float(x)).rstrip("0").rstrip(".")


def check_destination(args):
    """(destination, lat, lon, name) from navigate_to's arguments, or WazeError."""
    dest = args.get("destination")
    lat, lon, name = args.get("latitude"), args.get("longitude"), args.get("name")
    if dest is not None and not isinstance(dest, str):
        raise WazeError("destination must be text")
    dest = (dest or "").strip() or None
    if (lat is None) != (lon is None):
        raise WazeError("give both latitude and longitude, or neither")
    if lat is not None:
        if isinstance(lat, bool) or isinstance(lon, bool) or not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
            raise WazeError("latitude and longitude must be numbers")
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise WazeError("latitude must be -90..90 and longitude -180..180")
    if dest is None and lat is None:
        raise WazeError("give a destination (an address or place name) or latitude and longitude")
    if dest is not None and len(dest) > 300:
        raise WazeError("destination is longer than 300 characters")
    if name is not None and not isinstance(name, str):
        raise WazeError("name must be text")
    return (None if lat is not None else dest), lat, lon, (name or "").strip() or None


def navigate_to(args, mode=None, carapp=None, intent=None, wait_s=START_WAIT_S):
    rid = args.get("resultId")
    if rid is not None:
        if any(args.get(k) is not None for k in ("destination", "latitude", "longitude")):
            raise WazeError("give either resultId or a destination, not both")
        if (mode or interface()) != "car":
            raise WazeError("Search results exist in Car mode only; give the destination as text instead.")
        return navigate_result(rid, carapp, wait_s)
    dest, lat, lon, name = check_destination(args)
    label = name or dest or "%s,%s" % (_num(lat), _num(lon))
    mode = mode or interface()
    if mode == "car":
        uri = geo_uri(dest, lat, lon)
        s = CarSession(carapp)
        try:
            r = s.request({"type": "navigate", "uri": uri})
            if not r.get("ok"):
                raise WazeError("Waze did not take the destination: %s" % r.get("error"))
            # started: navigating, with a trip that has its estimates (the first trip Waze reports
            # is often an empty one, before the route is computed)
            started = s.wait(lambda x: bool(x.navigating and isinstance(x.trip, dict)
                                            and x.trip.get("destinationTravelEstimates")), wait_s)
            st = summarize(s.trip, s.navigating)
            # Waze may show the route first and wait for the driver (its route preview: Go now,
            # Avoid ...): say what it waits for
            waits = [b["title"] for b in question_of(s.inner())[1]] if not s.navigating else []
        finally:
            s.close()
        if started:
            return sentence(st), dict(st, sent=uri, mode="car")
        if s.navigating:
            return "Waze started navigating to %s; the trip details are not known yet." % label, dict(st, sent=uri, mode="car")
        if waits:
            return ("Sent %s to Waze. It shows the route and waits on the car screen for: %s." % (label, ", ".join(waits))), \
                dict(st, sent=uri, mode="car", waitsFor=waits)
        return ("Sent %s to Waze, but it has not started navigating yet: it may be showing search results or "
                "asking to pick one on the car screen." % label), dict(st, sent=uri, mode="car")
    uri = waze_uri(dest, lat, lon)
    start_intent(uri, intent)
    return ("Sent %s to Waze (Phone mode). Waze searches it and starts driving there; "
            "in Phone mode the trip cannot be read back." % label), {"sent": uri, "mode": "phone"}


def trip_status(args, mode=None, carapp=None):
    mode = mode or interface()
    if mode != "car":
        raise WazeError("Trip status is only known in Car mode (Settings > Waze interface: Car). "
                        "In Phone mode Waze does not report its trip to the device.")
    s = CarSession(carapp)
    try:
        if s.navigating and not s.seen_trip:
            s.wait(lambda x: x.seen_trip, 2.0)
        st = summarize(s.trip, s.navigating)
    finally:
        s.close()
    if s.navigating is None and s.template is None:
        st = {"navigating": False, "known": False, "note": "Waze's car session has not started yet."}
        return "Waze's car session has not started yet, so the trip is not known.", st
    # the host sends the navigation state once a drive started or ended: none yet is not navigating
    return sentence(st), st


def stop_navigation(args, mode=None, carapp=None, intent=None):
    mode = mode or interface()
    if mode == "car":
        s = CarSession(carapp)
        try:
            if not s.navigating:
                return "Waze is not navigating; nothing to stop.", {"stopped": False, "navigating": False}
            r = s.request({"type": "stopNavigation"})
            if not r.get("ok"):
                raise WazeError("Waze did not stop: %s" % r.get("error"))
            s.wait(lambda x: x.navigating is False, 5.0)
        finally:
            s.close()
        return "Navigation stopped.", {"stopped": True, "navigating": bool(s.navigating)}
    # Phone mode: Waze's intents only start drives. Its driving assistant's actions (geo:?act=
    # exit_navigation) are handled only when they reach MainActivity as VIEW intents, but every
    # filter of Waze sits on FreeMapAppActivity, which passes MainActivity the data without the
    # action (checked in the Debian VM, Waze 5.27): nothing from outside can end a drive there.
    raise WazeError("Stopping navigation needs Car mode (Settings > Waze interface: Car). In Phone mode Waze "
                    "offers no way for another app to end a drive; the driver can end it on the screen.")
