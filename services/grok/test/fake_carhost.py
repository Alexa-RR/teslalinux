"""A stand-in for ATL's car app channel (ws://127.0.0.1:PORT) with a small Waze behind it, for the
tests: the messages and template shapes are the ones the real host sent in the Debian VM with
Waze 5.27 (docs/UPSTREAM-NOTES.md "Car app templates"). Also a stand-in for ATL's intent channel."""
import base64
import hashlib
import json
import socket
import struct
import threading
import time

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


def action(cb, res=None, title=None):
    a = {"$": "Action", "type": 1, "enabled": True, "onClickDelegate": {"$": "OnClickDelegateImpl", "$cb": cb}}
    if res:
        a["icon"] = {"$": "CarIcon", "icon": {"$": "IconCompat", "icon": 1, "resName": res, "type": 2}, "type": 1}
    if title:
        a["title"] = title
    return a


def row(cb, title, texts=()):
    return {"$": "Row", "title": title, "texts": list(texts), "metadata": {"$": "Metadata"},
            "onClickDelegate": {"$": "OnClickDelegateImpl", "$cb": cb}}


PLACES = [("Dor Alon", "37 Derech Shlomo, Tel Aviv", 300.0), ("Paz", "35 Eilat, Tel Aviv", 1200.0), ("Sonol", "43 Eilat, Tel Aviv", 2.4)]


def trip_for(name, address):
    est = {"$": "TravelEstimate", "remainingDistance": {"$": "Distance", "displayDistance": 2.3, "displayUnit": 2},
           "remainingTimeSeconds": 780, "arrivalTimeAtDestination": {"$": "DateTimeWithZone", "timeSinceEpochMillis": 1795513011000,
                                                                      "zoneOffsetSeconds": 7200, "zoneShortName": "Asia/Jerusalem"}}
    step = {"$": "TravelEstimate", "remainingDistance": {"$": "Distance", "displayDistance": 150.0, "displayUnit": 1}, "remainingTimeSeconds": 30}
    return {"$": "Trip", "currentRoad": "Ibn Gabirol", "destinations": [{"$": "Destination", "name": name, "address": address}],
            "destinationTravelEstimates": [est], "steps": [{"$": "Step", "cue": "Turn right onto Arlozorov", "road": "Arlozorov",
                                                            "maneuver": {"$": "Maneuver", "type": 8}}], "stepTravelEstimates": [step]}


class FakeCarHost:
    def __init__(self):
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(8)
        self.port = self.sock.getsockname()[1]
        self.addr = "127.0.0.1:%d" % self.port
        self.lock = threading.Lock()
        self.clients = []
        self.received = []
        self.holder = None
        self.seen_by = {}          # client -> template kinds it was sent
        self.navigating = False
        self.trip = None
        self.stack = []            # [(id, kind, model)], top last
        self.n = 0
        self.refuse_navigate = None
        self.base_len = 1
        self.tracking = True
        self.reports = []
        self.kinds = []
        self.reset()

    # the app's screens ---------------------------------------------------------------

    def reset(self):
        self.navigating, self.trip = False, None
        self.stack = [self._map()]

    def _map(self):
        # Waze's map strip: its Report button while the map follows the car, else center-on-me
        strip = [action(26, "icon_report_button")] if self.tracking else [action(27, "ic_icon_recenter_button")]
        return self._screen("MapWithContentTemplate", {"actionStrip": {"$": "ActionStrip", "actions": [
            action(23, "saved_outline_24px"), action(24, "icon_outline_search_24px"), action(25, "settings_outline_24px")]},
            "mapController": {"$": "MapController", "mapActionStrip": {"$": "ActionStrip", "actions": strip}}})

    # Waze's report menu (the shapes the VM showed with Waze 5.27: Closure, offered while driving,
    # is a confirmation screen of its own, Report (primary) or Cancel; "Object" here stands for a
    # kind that asks one more question without a primary answer; "Fail" for a report Waze could not
    # send)
    REPORTS = [("Traffic", "icon_report_traffic_vector", [("Heavy", "car_report_menu_traffic_heavy"), ("Standstill", "car_report_menu_traffic_standstill")]),
               ("Police", "icon_report_police_vector", [("Police", "car_report_menu_police"), ("Mobile camera", "car_report_menu_police_mobile_camera")]),
               ("Hazard", "icon_report_hazard_vector", [("Object", "car_report_menu_hazard_object"), ("Pothole", "car_report_menu_hazard_pothole")]),
               ("Closure", "icon_report_closure_vector", None),
               ("Fail", "icon_report_fail_vector", [("Fail", "car_report_menu_fail")]),
               ("Debug", "icon_report_debug", [("Send logs", "car_report_menu_debug_logs")])]

    def _grid(self, title, items, base_cb):
        return {"contentTemplate": {"$": "GridTemplate", "header": {"$": "Header", "title": title, "endHeaderActions": [action(299, "ic_icon_outline_close_88px")]},
                                    "singleList": {"$": "ItemList", "items": [
                                        {"$": "GridItem", "title": t, "image": {"$": "CarIcon", "icon": {"$": "IconCompat", "resName": r, "type": 2}},
                                         "onClickDelegate": {"$": "OnClickDelegateImpl", "$cb": base_cb + i}} for i, (t, r) in enumerate(items)]}}}

    def _alert(self, ok, title):
        self.state({"type": "alert", "alert": {"$": "Alert", "alertId": 7, "title": title, "durationMillis": 5000,
                                               "icon": {"$": "CarIcon", "icon": {"$": "IconCompat", "resName": "car_lib_icon_outline_checkmark_with_bg_36px" if ok else "icon_report_error", "type": 2}}}})

    def _reported(self, name):
        self.reports.append(name)
        self.stack = self.stack[:1]
        self.publish()
        self._alert(name != "Fail", "Reported" if name != "Fail" else "Couldn't send your report")

    def _screen(self, kind, model):
        self.n += 1
        m = dict(model, **{"$": kind})
        return ("t%d" % self.n, kind, m)

    def _template_msg(self):
        tid, kind, m = self.stack[-1]
        wrapper = {"$": "TemplateWrapper", "template": m,
                   "templateInfosForScreenStack": [{"$": "TemplateInfo", "templateId": s[0]} for s in reversed(self.stack)]}
        return {"type": "template", "seq": self.n, "template": wrapper}

    def push(self, kind, model):
        self.stack.append(self._screen(kind, model))
        self.publish()

    def publish(self):
        msg = self._template_msg()
        with self.lock:
            targets = [self.holder] if self.holder else list(self.clients)
        for c in targets:
            self._send(c, msg)

    def state(self, msg):
        with self.lock:
            targets = list(self.clients)
        for c in targets:
            self._send(c, msg)

    # the WebSocket -------------------------------------------------------------------

    def start(self):
        threading.Thread(target=self._accept, daemon=True).start()
        return self

    def _accept(self):
        while True:
            try:
                c, _ = self.sock.accept()
            except OSError:
                return
            threading.Thread(target=self._client, args=(c,), daemon=True).start()

    def _send(self, c, obj):
        data = json.dumps(obj).encode()
        n = len(data)
        hdr = bytes([0x81]) + (bytes([n]) if n < 126 else bytes([126]) + struct.pack(">H", n) if n < 65536 else bytes([127]) + struct.pack(">Q", n))
        if obj.get("type") == "template":
            self.seen_by.setdefault(id(c), []).append(obj["template"]["template"]["$"])
        try:
            c.sendall(hdr + data)
        except OSError:
            pass

    def _client(self, c):
        buf = b""
        while b"\r\n\r\n" not in buf:
            d = c.recv(4096)
            if not d:
                return
            buf += d
        head = buf.split(b"\r\n\r\n", 1)[0].decode()
        key = [l.split(":", 1)[1].strip() for l in head.split("\r\n") if l.lower().startswith("sec-websocket-key")][0]
        acc = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
        c.sendall(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\n\r\n" % acc).encode())
        with self.lock:
            self.clients.append(c)
        if self.navigating:
            self._send(c, {"type": "navigation", "active": True})
        if self.trip is not None:
            self._send(c, {"type": "trip", "trip": self.trip})
        self._send(c, {"type": "icon", "id": 1, "png": "AAAA"})
        self._send(c, self._template_msg())
        f = c.makefile("rb")
        try:
            while True:
                b0, b1 = f.read(2)
                n = b1 & 0x7F
                if n == 126:
                    n = struct.unpack(">H", f.read(2))[0]
                elif n == 127:
                    n = struct.unpack(">Q", f.read(8))[0]
                mask = f.read(4)
                p = bytes(b ^ mask[i % 4] for i, b in enumerate(f.read(n)))
                if b0 & 0x0F == 0x8:
                    break
                if b0 & 0x0F == 0x1:
                    self._on(c, json.loads(p.decode()))
        except (ValueError, OSError):
            pass
        finally:
            with self.lock:
                if c in self.clients:
                    self.clients.remove(c)
                gone_holder = self.holder is c
            if gone_holder:
                # as the real host: pops the holder's screens back, then everyone sees the current one
                del self.stack[self.base_len:]
                with self.lock:
                    self.holder = None
                self.publish()

    def _reply(self, c, m, ok=True, error=None):
        if "id" in m:
            r = {"type": m["type"] + "Result", "id": m["id"], "ok": ok}
            if error:
                r["error"] = error
            self._send(c, r)

    def _start_drive(self, name, address):
        def later():
            time.sleep(0.2)
            self.navigating = True
            self.state({"type": "navigation", "active": True})
            self.trip = {"$": "Trip", "currentRoad": ""}
            self.state({"type": "trip", "trip": self.trip})
            time.sleep(0.2)
            self.trip = trip_for(name, address)
            self.state({"type": "trip", "trip": self.trip})
            self.stack = [self._screen("NavigationTemplate", {"actionStrip": {"$": "ActionStrip", "actions": [action(90, "icon_outline_search_30px")]}})]
            self.publish()
        threading.Thread(target=later, daemon=True).start()

    def _on(self, c, m):
        self.received.append(m)
        t = m.get("type")
        top = self.stack[-1]
        if t == "navigate":
            if self.refuse_navigate:
                return self._reply(c, m, False, self.refuse_navigate)
            self._reply(c, m)
            q = m["uri"].split("q=", 1)[1] if "q=" in m["uri"] else "?"
            self._start_drive(q, "")
        elif t == "stopNavigation":
            if not self.navigating:
                return self._reply(c, m, False, "the app is not navigating")
            self._reply(c, m)
            self.reset()
            self.state({"type": "navigation", "active": False})
            self.state({"type": "trip", "trip": None})
            self.publish()
        elif t == "hold":
            with self.lock:
                if m["on"]:
                    if self.holder not in (None, c):
                        ok = False
                    else:
                        self.holder, ok, self.base_len = c, True, len(self.stack)
                else:
                    ok = self.holder is c
                    if ok:
                        self.holder = None
            self._reply(c, m, ok, None if ok else "another renderer holds the app's screens")
            if ok and not m["on"]:
                self.publish()
        elif t == "back":
            if len(self.stack) > 1:
                self.stack.pop()
                self.publish()
        elif t == "click" and top[1] == "MapWithContentTemplate" and m["cb"] == 27 and len(self.stack) == 1:
            self.tracking = True
            self.stack = [self._map()]
            self.publish()
        elif t == "click" and top[1] == "MapWithContentTemplate" and m["cb"] == 26 and len(self.stack) == 1:
            self.push("MapWithContentTemplate", self._grid("What do you see?", [(c, r) for c, r, _ in self.REPORTS], 400))
        elif t == "click" and len(self.stack) == 2 and 400 <= m["cb"] < 400 + len(self.REPORTS):
            c, r, kinds = self.REPORTS[m["cb"] - 400]
            if kinds is None:
                report = dict(action(611, title="Report"), flags=5)
                self.push("MapWithContentTemplate", {"contentTemplate": {"$": "MessageTemplate", "title": "Closure",
                                                                         "message": "Report a closure on your route",
                                                                         "actions": [report, action(612, title="Cancel")]}})
            else:
                self.kinds = kinds
                self.push("MapWithContentTemplate", self._grid(c, kinds, 500))
        elif t == "click" and len(self.stack) == 3 and 500 <= m["cb"] < 500 + len(self.kinds):
            name = self.kinds[m["cb"] - 500][0]
            if name == "Object":
                self.push("MessageTemplate", {"title": "Object", "message": "Where is it?",
                                              "actions": [action(601, title="In lane"), action(602, title="On shoulder")]})
            else:
                self._reported(name)
        elif t == "click" and top[1] == "MessageTemplate" and m["cb"] in (601, 602):
            self._reported("Object " + ("in lane" if m["cb"] == 601 else "on shoulder"))
        elif t == "click" and len(self.stack) == 3 and m["cb"] == 611:
            self._reported("Closure")
        elif t == "click" and len(self.stack) == 3 and m["cb"] == 612:
            self.stack = self.stack[:1]
            self.publish()
        elif t == "click":
            cb = m["cb"]
            if top[1] in ("MapWithContentTemplate", "NavigationTemplate") and cb in (24, 90):
                self.push("SearchTemplate", {"searchCallbackDelegate": {"$": "SearchCallbackDelegateImpl", "$cb": 42}, "searchHint": "Where to?"})
            elif top[1] == "PlaceListNavigationTemplate" and 55 <= cb < 55 + len(PLACES):
                name, addr, _ = PLACES[cb - 55]
                self.push("MapTemplate", {"pane": {"$": "Pane", "rows": [row(None, name, ["Open now"]), row(None, "‎Location⁩", [addr])],
                                                   "actions": [action(146 + cb - 55, title="Go now")]}})
            elif top[1] == "MapTemplate" and cb >= 146:
                name, addr, _ = PLACES[cb - 146]
                self._start_drive(name, addr)
        elif t == "search" and m.get("cb") == 42 and m.get("submit"):
            self.push("PlaceListNavigationTemplate", {"loading": True})
            self.stack.pop()
            rows = [row(55 + i, "‎%s⁩" % n, [{"$": "CarText", "text": "Open now"},
                                                      {"$": "CarText", "text": "_DISTANCE_PLACEHOLDER_ away", "spans": [
                                                          {"start": 0, "end": 22, "span": {"$": "DistanceSpan", "distance": {"$": "Distance", "displayDistance": d, "displayUnit": 1}}}]}])
                    for i, (n, a, d) in enumerate(PLACES)]
            self.push("PlaceListNavigationTemplate", {"itemList": {"$": "ItemList", "items": rows}})


class FakeIntentChannel:
    """ATL's intent channel: one JSON line in, one out."""

    def __init__(self, answer=None):
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(4)
        self.addr = "127.0.0.1:%d" % self.sock.getsockname()[1]
        self.requests = []
        self.answer = answer or {"ok": True, "activity": "com.waze.MainActivity"}
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while True:
            c, _ = self.sock.accept()
            with c:
                line = c.makefile("rb").readline()
                self.requests.append(json.loads(line.decode()))
                c.sendall((json.dumps(self.answer) + "\n").encode())
