#!/usr/bin/env python3
"""teslalinux car sync: the car's own navigation into the app, and the car's GPS as its position.

teslalinux-carkey (services/carkey, loopback 8086) reads the car over Bluetooth with a read-only key:
the route the car's own navigation follows, and the car's GPS. This reads that every second and

  * follows the car's destination (settings "follow", on by default): when the driver starts a
    route on the car's screen, the app gets the same destination the way Grok remote control hands
    it an address (services/grok/waze.py navigate_geo: in Car mode Android Auto's navigate intent
    with the destination's name for Waze's own search, checked against the car's distance and
    replaced by the car's point when it does not match, app_navigate; Waze's Go now pressed when it
    waits on its route preview, routes.start_drive; in Phone mode Waze's ll= deep link). A destination counts once two of the car's reads (2 s apart)
    agree, so one picked and changed again right away is not sent twice. When the car's route ends
    (arrived, or cancelled on its screen), the app's drive to it ends too (Car mode; Phone mode has
    no path for that from outside the app). A drive the driver starts in the app itself is left alone.
  * feeds the car's GPS to the app (settings "carGps", off by default): each new fix of the car goes
    to the GPS bridge (services/gps/gps.py) as one more source; while the car's fixes come, the
    bridge passes them and drops the browser's.

Settings: TESLALINUX_CARSYNC_SETTINGS (/var/lib/teslalinux/state/carsync.json, written by the API):
{"follow": bool, "carGps": bool}. Status for the API: TESLALINUX_CARSYNC_STATUS
($XDG_RUNTIME_DIR/teslalinux-carsync.json): {"sent": the destination last handed to the app or
null, "lastAction": {"at", "text", "ok"} or null, "gpsFeeding": bool}.
"""
import json
import os
import tempfile
import threading
import time
import urllib.request

CARKEY_URL = os.environ.get("TESLALINUX_CARKEY_URL", "http://127.0.0.1:8086")
SETTINGS = os.environ.get("TESLALINUX_CARSYNC_SETTINGS", "/var/lib/teslalinux/state/carsync.json")
STATUS = os.environ.get("TESLALINUX_CARSYNC_STATUS",
                        os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/run/tesla"), "teslalinux-carsync.json"))
GPS_WS = os.environ.get("TESLALINUX_CARSYNC_GPS_WS", "ws://127.0.0.1:9998/")
DEFAULTS = {"follow": True, "carGps": False}

POLL_S = 1.0
STABLE_READS = 2      # the car's drive reads that must agree before a destination (or its end) counts
RETRY_S = 30.0        # a destination the app did not take is offered again after this, up to RETRIES
RETRIES = 6           # 3 minutes: the app may still be starting (after a boot the car has its route first)
FIX_FRESH_S = 3.0     # a car fix older than this (by the daemon's clock) is not fed


def read_settings(path=None):
    s = dict(DEFAULTS)
    try:
        with open(path or SETTINGS) as f:
            d = json.load(f)
    except (OSError, ValueError):
        return s
    if isinstance(d, dict):
        for k in DEFAULTS:
            if isinstance(d.get(k), bool):
                s[k] = d[k]
    return s


def write_status(status, path=None):
    path = path or STATUS
    try:
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path) or ".", prefix=".carsync")
        with os.fdopen(fd, "w") as f:
            json.dump(status, f)
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    except OSError as e:
        print("teslalinux-carsync: status not written: %s" % e, flush=True)


def read_status(path=None):
    try:
        with open(path or STATUS) as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except (OSError, ValueError):
        return {}


def car_state(url=None, timeout=3):
    with urllib.request.urlopen((url or CARKEY_URL) + "/state", timeout=timeout) as r:
        return json.load(r)


def route_of(drive):
    """The car's active route as {"name", "lat", "lon"}, or None (no route, or none with a point)."""
    if not isinstance(drive, dict):
        return None
    lat, lon = drive.get("destLat"), drive.get("destLon")
    if not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
        return None
    r = {"name": (drive.get("destination") or "").strip(), "lat": round(float(lat), 6), "lon": round(float(lon), 6)}
    if isinstance(drive.get("kmToArrival"), (int, float)) and drive["kmToArrival"] > 0:
        r["km"] = round(float(drive["kmToArrival"]), 1)  # to check Waze's search against (app_navigate)
    return r


def same_route(a, b):
    """The same destination: the same name and a point within ~50 m (the car rounds and re-plans)."""
    if a is None or b is None:
        return a is b
    return a["name"] == b["name"] and abs(a["lat"] - b["lat"]) < 0.0005 and abs(a["lon"] - b["lon"]) < 0.0005


class Follower:
    """Decides, from the car's successive drive reads, what the app is told. The app side is passed
    in (navigate(route) -> text, stop() -> text), so the decisions are tested without Waze."""

    def __init__(self, navigate, stop, sent=None, now=time.monotonic):
        self.navigate, self.stop, self.now = navigate, stop, now
        self.sent = sent            # the destination handed to the app (kept across restarts)
        self.candidate, self.count, self.last_at = None, 0, None
        self.failed_at, self.failures = None, 0
        self.last_action = None

    def _act(self, text, ok):
        self.last_action = {"at": int(time.time()), "text": text, "ok": ok}
        print("teslalinux-carsync: %s" % text, flush=True)

    def observe(self, drive, follow=True):
        """One car state; returns True when something was done."""
        if not isinstance(drive, dict) or drive.get("at") == self.last_at:
            return False  # no new read of the car
        self.last_at = drive.get("at")
        r = route_of(drive)
        if same_route(r, self.candidate):
            self.count += 1
        else:
            self.candidate, self.count = r, 1
        if self.count < STABLE_READS or not follow:
            return False
        if r is None:
            if self.sent is None:
                return False
            # the car's route ended: so does the app's drive to it
            name = self.sent["name"] or "the destination"
            self.sent, self.failures = None, 0
            try:
                self._act("The car's route to %s ended: %s" % (name, self.stop()), True)
            except Exception as e:  # noqa: BLE001 (the app's answer, whatever it is, is reported)
                self._act("The car's route to %s ended; the app's drive was not ended: %s" % (name, e), False)
            return True
        if same_route(r, self.sent):
            if self.failed_at is None or self.failures > RETRIES or self.now() - self.failed_at < RETRY_S:
                return False
        self.sent = r
        label = r["name"] or "%.5f, %.5f" % (r["lat"], r["lon"])
        try:
            text = self.navigate(r)
            self.failed_at, self.failures = None, 0
            self._act("Following the car to %s: %s" % (label, text), True)
        except Exception as e:  # noqa: BLE001
            self.failed_at, self.failures = self.now(), self.failures + 1
            self._act("The car's destination %s did not reach the app: %s" % (label, e), False)
        return True


# ------------------------------------------------------------------ the app side (Grok's Waze code)

KM_PER = {"km": 1.0, "m": 0.001, "mi": 1.609344, "ft": 0.0003048, "yd": 0.0009144}


def distance_km(d):
    """Waze's trip distance ({"value", "unit"}) in km, or None."""
    if not isinstance(d, dict) or d.get("unit") not in KM_PER or not isinstance(d.get("value"), (int, float)):
        return None
    return d["value"] * KM_PER[d["unit"]]


def same_place(waze_km, car_km):
    """Waze's distance left and the car's agree (routes differ, the place must not): within 35 % or 3 km.
    Unknown either way counts as agreeing."""
    if waze_km is None or not isinstance(car_km, (int, float)) or car_km <= 0:
        return True
    return abs(waze_km - car_km) <= max(3.0, 0.35 * car_km)


def app_navigate(r, waze=None, routes=None):
    """The car's destination to Waze. Car mode: its name as text for Waze's own search near the car,
    as Grok remote control sends an address (geo:0,0?q=TEXT), so Waze drives to its own place under
    its own name; the coordinates only alone ("Received location" in Waze's lists). The name has no
    town ("Herzl St 10"), so the drive is checked against the car's: Waze's distance left must
    match the car's (same_place); a search that starts no drive or finds another place is replaced
    by the coordinates. Phone mode: the coordinates (ll= deep link): its trip cannot be read back to
    check a search there."""
    if waze is None:
        import waze
    if routes is None:
        import routes
    mode = waze.interface()
    label = r["name"] or "%.5f, %.5f" % (r["lat"], r["lon"])
    if mode != "car":
        return waze.navigate_geo(None, r["lat"], r["lon"], label, mode)[0]

    def drive(dest, lat, lon):
        text, st = waze.navigate_geo(dest, lat, lon, label, mode)
        # the driver chose this on the car's own screen: Waze's route preview is not asked again
        if st.get("waitsFor") and not st.get("navigating"):
            text, st = routes.start_drive({})
        return text, st

    if r["name"]:
        try:
            text, st = drive(r["name"], None, None)
        except Exception as e:  # noqa: BLE001 (Waze found nothing, or refused: the coordinates next)
            text, st = "Waze's search for %s: %s" % (label, e), {}
        if st.get("navigating"):
            waze_km = distance_km(st.get("distanceLeft"))
            if same_place(waze_km, r.get("km")):
                return text
            # another place of that name: that drive ends, the car's point goes instead
            waze.stop_navigation({})
            why = "Waze's search found a place %.0f km away, the car's is %.0f km" % (waze_km, r["km"])
        else:
            why = "Waze's search started no drive (%s)" % text
        text, _ = drive(None, r["lat"], r["lon"])
        return "%s; sent the car's point instead: %s" % (why, text)
    return drive(None, r["lat"], r["lon"])[0]


def app_stop():
    import waze
    if waze.interface() != "car":
        return "in Phone mode the app's drive cannot be ended from outside the app"
    text, _ = waze.stop_navigation({})
    return text


# ------------------------------------------------------------------ the car's GPS

def fix_message(loc, drive, now=None):
    """The GPS bridge's browser message (all strings, docs/UPSTREAM-NOTES.md) for a car fix, marked
    as the car's; None when the fix is stale or not valid. The car's accuracy field reads 9999.99
    even with a good fix, so accuracy goes as unknown (the app's reader then assumes 10 m)."""
    now = time.time() if now is None else now
    if not isinstance(loc, dict) or not loc.get("valid") or now - (loc.get("at") or 0) > FIX_FRESH_S:
        return None
    speed = "null"
    if isinstance(drive, dict) and now - (drive.get("at") or 0) <= FIX_FRESH_S and isinstance(drive.get("speedKph"), (int, float)):
        speed = "%.2f" % (drive["speedKph"] / 3.6)
    return json.dumps({"latitude": "%.7f" % loc["lat"], "longitude": "%.7f" % loc["lon"], "speed": speed,
                       "bearing": "%.1f" % loc.get("heading", 0), "vertical_accuracy": "null",
                       "timestamp": str(int(loc.get("carAt") or now * 1000)), "source": "car"})


class GpsFeed:
    """A WebSocket to the GPS bridge, opened while the car's fixes are fed."""

    def __init__(self, url=None):
        self.url, self.ws, self.last = url or GPS_WS, None, None

    def send(self, msg):
        if msg is None or msg == self.last:
            return False
        try:
            from websockets.sync.client import connect
            if self.ws is None:
                self.ws = connect(self.url, open_timeout=3)
            self.ws.send(msg)
            self.last = msg
            return True
        except Exception as e:  # noqa: BLE001 (the bridge restarting: tried again next fix)
            print("teslalinux-carsync: GPS bridge: %s" % e, flush=True)
            self.close()
            return False

    def close(self):
        if self.ws is not None:
            try:
                self.ws.close()
            except Exception:  # noqa: BLE001
                pass
        self.ws = None


def main():
    prev = read_status()
    follower = Follower(app_navigate, app_stop, sent=prev.get("sent") if isinstance(prev.get("sent"), dict) else None)
    feed = GpsFeed()
    last_written = None
    lock = threading.Lock()
    while True:
        s = read_settings()
        try:
            st = car_state()
        except (OSError, ValueError):
            st = None
        feeding = False
        if st and st.get("link") == "connected":
            with lock:
                follower.observe(st.get("drive"), s["follow"])
            if s["carGps"]:
                msg = fix_message(st.get("location"), st.get("drive"))
                feed.send(msg)
                feeding = msg is not None and feed.ws is not None
        if not s["carGps"]:
            feed.close()
        status = {"sent": follower.sent, "lastAction": follower.last_action, "gpsFeeding": feeding}
        if status != last_written:
            write_status(status)
            last_written = status
        time.sleep(POLL_S)


if __name__ == "__main__":
    main()
