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
import time
import urllib.parse

import carlink
import flows
from carlink import WazeError, WebSocket, _addr, depth  # noqa: F401 (the tools' names for them)

CARAPP_ADDR = os.environ.get("TESLALINUX_GROK_CARAPP", "127.0.0.1:9995")
INTENT_ADDR = os.environ.get("TESLALINUX_GROK_INTENT", "127.0.0.1:9993")
INTERFACE_ENV = os.environ.get("TESLALINUX_GROK_INTERFACE_ENV", "/etc/teslalinux/interface.env")

START_WAIT_S = 25.0      # how long navigate_to waits to see the drive start
SETTLE_S = 0.2           # a new screen is complete once Waze sent no refresh of it for this long
REACT_S = 4.0            # Waze shows a destination's search or route within this (1-2 s in the VM)

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


def CarSession(addr=None):
    """A tool's view of the service's persistent link to the car host (carlink.py)."""
    return carlink.CarSession(addr or CARAPP_ADDR)


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
    parts.append("Navigating to %s%s." % (name, (" (%s)" % st["tripText"]) if st.get("tripText") else "") if name else "Navigating.")
    to = ""
    if st.get("stops"):
        stop = (st.get("nextStop") or {}).get("name")
        parts.append("Next stop: %s." % stop if stop else "Waze does not name the stop.")
        to = " to the next stop"
    if "eta" in st:
        parts.append(("Arrival at the next stop: %s." if to else "Arrival at %s.") % st["eta"]["local"])
    if "timeLeftMinutes" in st or "distanceLeft" in st:
        left = []
        if "timeLeftMinutes" in st:
            left.append("%d min" % st["timeLeftMinutes"])
        if "distanceLeft" in st:
            left.append(st["distanceLeft"]["text"])
        parts.append(" and ".join(left) + " left%s." % to)
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


# Waze's own lists on its search screen, by their rows' icons: what recent_places and saved_places read
LIST_ICONS = {"recent": "icon_t2_history_32px", "saved": "car_lib_icon_t2_save_32px"}
ID_PREFIX = {"search": "wz1.", "recent": "wzr1.", "saved": "wzs1."}


def result_id(query, index, name, kind="search"):
    raw = json.dumps({"q": query, "i": index, "t": name}, ensure_ascii=False, separators=(",", ":")).encode()
    return ID_PREFIX[kind] + base64.urlsafe_b64encode(raw).decode().rstrip("=")


def parse_any_id(rid):
    """(kind, query, index, name) of an id from search_places, recent_places or saved_places."""
    bad = WazeError("resultId is not an id from search_places, recent_places or saved_places")
    kind = [k for k, p in ID_PREFIX.items() if isinstance(rid, str) and rid.startswith(p)]
    if not kind or len(rid) > 2000:
        raise bad
    body = rid[len(ID_PREFIX[kind[0]]):]
    try:
        d = json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)).decode())
        q, i, t = d["q"], d["i"], d["t"]
    except (ValueError, KeyError, TypeError, UnicodeDecodeError):
        raise bad
    if not isinstance(q, str) or not isinstance(i, int) or not isinstance(t, str):
        raise bad
    return kind[0], q, i, t


def parse_result_id(rid):
    """(query, index, name) of any place id (query is empty for Waze's own lists)."""
    return parse_any_id(rid)[1:]


def search_actions(model):
    return [a for a in walk(model) if a.get("$") == "Action" and SEARCH_ICON.search(icon_name(a) or "")]


def first_search(model):
    a = search_actions(model)
    return cb_of(a[0]) if a else None


def has_search(model):
    return bool(search_actions(model))


def estimate_words(st):
    """'13 min and 2.3 km left, arrival at 11:36' from a summary, or None."""
    left = []
    if "timeLeftMinutes" in st:
        left.append("%d min" % st["timeLeftMinutes"])
    if "distanceLeft" in st:
        left.append(st["distanceLeft"]["text"])
    out = " and ".join(left) + " left" if left else ""
    if "eta" in st:
        out += (", " if out else "") + "arrival at %s" % st["eta"]["local"]
    return out or None


def span_text(v):
    """A CarText with its spans' values put in (Waze writes "_DISTANCE_PLACEHOLDER_ away" with a
    DistanceSpan or DurationSpan over the placeholder), as plain text."""
    if not isinstance(v, dict) or not v.get("spans"):
        return plain(text_of(v))
    s = text_of(v) or ""
    reps = []
    for sp in v.get("spans") or []:
        span = (sp or {}).get("span") or {}
        if span.get("$") == "DistanceSpan":
            d = distance_of(span.get("distance"))
            rep = d["text"] if d else None
        elif span.get("$") == "DurationSpan" and isinstance(span.get("durationSeconds"), (int, float)):
            rep = "%d min" % max(1, round(span["durationSeconds"] / 60.0))
        else:
            rep = None
        if rep is not None and isinstance(sp.get("start"), int) and isinstance(sp.get("end"), int):
            reps.append((sp["start"], sp["end"], rep))
    for a, b, rep in sorted(reps, reverse=True):
        s = s[:a] + rep + s[b:]
    return plain(s)


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
        acts = search_actions(s.inner())
        if not acts:
            if s.navigating and s.inner().get("$") == "NavigationTemplate":
                raise WazeError("Waze's navigation screen offers no search now (Waze hides it while the drive has a stop).")
            raise WazeError("Waze's current car screen offers no search (it may be showing a question or a menu).")
        box = s.press(first_search, lambda m: m.get("$") == "SearchTemplate", 8, "search button")
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

    def open_list(self, kind):
        """Opens one of Waze's own lists from its search screen (recent or saved places)."""
        s = self.s
        acts = search_actions(s.inner())
        if not acts:
            raise WazeError("Waze's current car screen offers no search, where it keeps its %s places." % kind)
        s.press(first_search, lambda m: m.get("$") == "SearchTemplate", 8, "search button")
        s.settle(SETTLE_S, 1.0)
        box = s.inner()
        rows = [r for r in walk(box) if r.get("$") == "Row" and (((r.get("image") or {}).get("icon")) or {}).get("resName") == LIST_ICONS[kind]]
        if not rows:
            offered = [plain(text_of(r.get("title"))) for r in walk(box) if r.get("$") == "Row"]
            raise WazeError("Waze lists no %s places now (its search offers: %s)%s." % (
                kind, ", ".join(o for o in offered if o) or "nothing",
                "; it lists saved places once some are saved in the driver's Waze account" if kind == "saved" else ""))
        s.click(cb_of(rows[0]))
        s.wait_template(lambda m: m.get("$") in ("ListTemplate", PLACE_LIST) and not m.get("loading"), 10, "its %s places" % kind)
        s.settle(SETTLE_S, 1.0)
        self.list_stack = s.stack()
        return s.inner()

    def rows(self):
        m = self.s.inner()
        items = (m.get("itemList") or m.get("singleList") or {}).get("items") or []
        return [r for r in items if isinstance(r, dict) and r.get("$") == "Row"]

    def details(self, index):
        """Opens result `index`'s place card, reads it, and returns to the list: [(title, text)], the
        card's model."""
        s = self.s
        rows = self.rows()
        if index >= len(rows):
            raise WazeError("the result is no longer in Waze's list")
        s.click(cb_of(rows[index]))
        s.wait_template(lambda m: m.get("$") == "MapTemplate" and isinstance(m.get("pane"), dict), 10, "the place's card")
        s.settle(SETTLE_S, 1.5)
        pane = s.inner().get("pane") or {}
        out = []
        for r in pane.get("rows") or []:
            if isinstance(r, dict):
                out.append((span_text(r.get("title")) or "", " • ".join(x for x in (span_text(t) for t in r.get("texts") or []) if x)))
        return out, pane

    def back_to_list(self):
        if not self.s.back_to(self.list_stack, 8.0):
            raise WazeError("Waze did not go back to its search results.")
        self.s.settle(SETTLE_S, 1.0)


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
    addresses = args.get("addresses", False)
    if not isinstance(addresses, bool):
        raise WazeError("addresses must be true or false")
    h = HeldSearch(carapp)
    try:
        h.results(query)
        rows = h.rows()
        out = []
        for i, row in enumerate(rows[:n]):
            r = parse_row(row)
            if not r.get("name"):
                continue
            if addresses:
                # Waze's list shows no address: each place's card has it (one card at a time)
                try:
                    det, _ = h.details(i)
                    addr = address_of(det, r["name"])
                    if addr:
                        r["address"] = addr
                    h.back_to_list()
                except WazeError:
                    h.back_to_list()  # the card did not open: the row's own line is all there is
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


def find_result(h, rid):
    """Runs a search_places result's search again and finds the same place (by its name; its
    position in the list when the name repeats): (index, name)."""
    kind, query, index, name = parse_any_id(rid)
    if kind == "search":
        h.results(query)
    else:
        h.open_list(kind)
    names = [parse_row(r).get("name") for r in h.rows()]
    if index < len(names) and names[index] == name:
        return index, name
    if name in names:
        return names.index(name), name
    if kind != "search":
        raise WazeError("%s is no longer among Waze's %s places." % (name, kind))
    raise WazeError("%s is no longer among Waze's results for %r; search again." % (name, query))


def place_list(kind, args, mode=None, carapp=None):
    """Waze's recent or saved places, as its search screen lists them: names and ids for
    navigate_to, add_stop or place_details."""
    if (mode or interface()) != "car":
        raise WazeError("Waze's %s places are listed in Car mode only (Settings > Waze interface: Car)." % kind)
    h = HeldSearch(carapp)
    try:
        h.open_list(kind)
        out = []
        for i, r in enumerate(h.rows()[:20]):
            p = parse_row(r)
            if p.get("name"):
                p["resultId"] = result_id("", i, p["name"], kind)
                out.append(p)
    finally:
        h.close()
    st = {"places": out, "list": kind}
    if not out:
        return "Waze's %s places list is empty." % kind, st
    return ("Waze's %s places (navigate_to or add_stop with a resultId):\n" % kind) + "\n".join(
        "%d. %s%s resultId=%s" % (k + 1, p["name"], (" · " + p["info"]) if p.get("info") else "", p["resultId"]) for k, p in enumerate(out)), st


def place_details(args, mode=None, carapp=None):
    """What Waze's card for a search_places result shows: its rows (the place, its address, its
    hours or other lines Waze has) and the actions it offers."""
    rid = args.get("resultId")
    parse_result_id(rid)
    if (mode or interface()) != "car":
        raise WazeError("Place details need Car mode (Settings > Waze interface: Car).")
    h = HeldSearch(carapp)
    try:
        i, name = find_result(h, rid)
        det, pane = h.details(i)
    finally:
        h.close()
    acts = [plain(text_of(a.get("title"))) for a in pane.get("actions") or [] if isinstance(a, dict)]
    st = {"name": name, "address": address_of(det, name), "rows": [{"title": t, "text": x} for t, x in det if t or x],
          "actions": [a for a in acts if a], "resultId": rid,
          "coordinates": "not given: Waze's car card shows no coordinates"}
    lines = ["%s%s" % (t, (": " + x) if x else "") for t, x in det if t or x]
    return ("Waze's card for %s:\n" % name) + "\n".join(lines) + \
        ("\nIt offers: %s." % ", ".join(st["actions"]) if st["actions"] else ""), st


def navigate_result(rid, carapp=None, wait_s=START_WAIT_S, place=None):
    """Drives to a search_places result (Waze's search again with the same query, the same place
    picked), or to one of Waze's own places (place: Home, Work, saved, recent), by its card's first
    action (Waze's "Go now", or "New drive" during a drive)."""
    import places
    if place is None:
        parse_result_id(rid)
    h = HeldSearch(carapp)
    try:
        pick, name = places.find_in_list(h, place) if place else find_result(h, rid)
        _, pane = h.details(pick)
        go = [a for a in pane.get("actions") or [] if isinstance(a, dict) and isinstance(cb_of(a), int)]
        if not go:
            raise WazeError("Waze's card for %s offers no way to drive there." % name)
        n0 = h.s.seq
        h.s.click(cb_of(go[0]))
        started = h.s.wait(lambda x: bool(x.navigating and isinstance(x.trip, dict)
                                          and x.trip.get("destinationTravelEstimates"))
                           and x.inner().get("$") == "NavigationTemplate"
                           or x.seq > n0 and is_route_preview(x.inner()), wait_s)
        preview = not h.s.navigating and is_route_preview(h.s.inner())
        if h.s.navigating or preview:
            h.keep = True  # the drive, or Waze's route preview for it, is what the car screen shows now
        st = summarize(h.s.trip, h.s.navigating)
        waits = [b["title"] for b in question_of(h.s.inner())[1]] if preview else []
    finally:
        h.close()
    if preview:
        return ("Waze is waiting on the route preview for %s: start_drive presses Go now, route_options lists the "
                "routes." % name), dict(st, mode="car", place=name, waitsFor=waits)
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
        s.press(lambda m: (lambda a: cb_of(a[0]) if a else None)(acts()), is_report_grid, 8, "Report button")
        s.settle(SETTLE_S, 1.5)
        self.menu_stack = s.stack()
        return [i for i in grid_items(s.inner()) if i["res"] not in NOT_A_REPORT]

    def open_category(self, item):
        """The category's kinds (a grid), or None when Waze answers the tap with a question of its own
        (a closure while driving: "Report a closure on your route", Report or Cancel)."""
        s = self.s
        # the category by its id on the menu as it is now (a press Waze dropped is made again with
        # the menu's current callback)
        find = lambda m: next((i["cb"] for i in grid_items(m) if i["id"] == item["id"]), None)
        s.press(find, lambda m: len(s.stack()) > len(self.menu_stack), 8, "%s button" % item["title"])
        s.settle(SETTLE_S, 1.5)
        return grid_items(s.inner()) if is_report_grid(s.inner()) else None

    def back_to_menu(self):
        if not self.s.back_to(self.menu_stack, 8.0):
            raise WazeError("Waze did not go back to its report menu.")
        self.s.settle(SETTLE_S, 1.0)


def report_types(args=None, mode=None, carapp=None, fresh=False):
    """Waze's report categories and their kinds, as its car screens offer them now."""
    if (mode or interface()) != "car":
        raise WazeError("Reports need Car mode (Settings > Waze interface: Car). In Phone mode Waze's report "
                        "screens are not reachable from outside the app.")
    types = None if fresh or (args or {}).get("fresh") else flows.get("reports")
    learned = types is not None
    if types is None:
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
        flows.put("reports", types)
    lines =["%s (%s): %s" % (c["title"], c["id"], ", ".join("%s (%s)" % (k["title"], k["id"]) for k in c["kinds"])
                              if c["kinds"] else "no kinds; Waze asks: %s (%s)" % (c["asks"]["question"], ", ".join(c["asks"]["answers"])))
             for c in types]
    return ("Waze's report types (category: kinds; ids in brackets):\n" + "\n".join(lines),
            {"categories": types, "source": "learned from Waze %s's screens earlier" % (flows.version_code() or "")
             if learned else "read from Waze's screens now"})


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
        menu = h.open_menu()
        known = flows.get("reports")
        if known is not None and [c["id"] for c in known] != [c["id"] for c in menu]:
            flows.forget("reports")  # Waze's menu changed: report_types reads it again
        cat = pick(menu, category, "report category")
        before = s.alert_count
        verdict = lambda x: [a for a in x.alerts_since(before) if alert_icon(a) in (REPORTED_ICON, REPORT_ERROR_ICON)]
        kinds = h.open_category(cat)
        if kinds is None:
            # the category is the report itself, and Waze asks to confirm it (a closure)
            if kind and kind.strip():
                pick([cat], kind, "kind of %s" % cat["title"])  # only the category itself fits
            k = {"id": cat["id"], "title": cat["title"]}
            answered = answer(s, option, cat["title"])
            s.wait(lambda x: bool(verdict(x)), REPORT_RESULT_S)
        else:
            was = [c for c in known or [] if c["id"] == cat["id"]]
            if was and [x["id"] for x in was[0]["kinds"]] != [x["id"] for x in kinds]:
                flows.forget("reports")
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
                s.settle(SETTLE_S, 1.0)
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


def understood_words(p):
    """What the answer says it took the place for."""
    u = p.get("understood") or {}
    what = ("%s, %s" % (_num(u["latitude"]), _num(u["longitude"]))) if "latitude" in u else u.get("query") or u.get("name")
    return "Read as %s (%s)%s." % (u.get("source", "a place"), u.get("how", ""), (": " + what) if what else "")


def navigate_to(args, mode=None, carapp=None, intent=None, wait_s=START_WAIT_S):
    import places
    p = places.resolve_args(args)
    mode = mode or interface()
    if "resultId" in p:
        if mode != "car":
            raise WazeError("Search results exist in Car mode only; give the destination as text instead.")
        return navigate_result(p["resultId"], carapp, wait_s)
    if "list" in p:
        if mode != "car":
            raise WazeError("Waze's own places (Home, Work, saved, recent) are reachable in Car mode only.")
        text, st = navigate_result(None, carapp, wait_s, place=p)
    else:
        text, st = navigate_geo(p.get("query"), p.get("latitude"), p.get("longitude"), p["label"], mode, carapp, intent, wait_s)
    if p["understood"].get("source") not in ("coordinates", "an address or place name"):
        text = understood_words(p) + " " + text
    return text, dict(st, understood=p["understood"])


def navigate_geo(dest, lat, lon, label, mode, carapp=None, intent=None, wait_s=START_WAIT_S):
    """Hands Waze the place as Android Auto's assistant would (geo:), or its deep link in Phone mode."""
    if mode == "car":
        uri = geo_uri(dest, lat, lon)
        s = CarSession(carapp)
        n0 = s.seq
        r = s.request({"type": "navigate", "uri": uri})
        if not r.get("ok"):
            raise WazeError("Waze did not take the destination: %s" % r.get("error"))
        # started: navigating, with a trip that has its estimates (the first trip Waze reports
        # is often an empty one, before the route is computed); or Waze shows its route preview
        # and waits for the driver (Go now, Routes, Avoid)
        done = lambda x: bool(x.navigating and isinstance(x.trip, dict) and x.trip.get("destinationTravelEstimates")) \
            or (x.seq > n0 and (is_route_preview(x.inner()) or is_message(x.inner())))
        depth0 = len(s.stack())
        # Waze drops a destination that comes while it is still ending a drive (seen right after
        # stop_navigation): no screen of its own within REACT_S, and it goes once more
        if not s.wait(lambda x: done(x) or len(x.stack()) > depth0 or bool(x.navigating), REACT_S):
            r = s.request({"type": "navigate", "uri": uri})
            if not r.get("ok"):
                raise WazeError("Waze did not take the destination: %s" % r.get("error"))
        started = s.wait(done, wait_s - REACT_S)
        if not s.navigating and is_message(s.inner()):
            # Waze's own refusal ("Something went wrong - try again", no route): said as it is; its
            # dialog is closed (its only button) so the car's map is free again
            q, buttons = question_of(s.inner())
            if len(buttons) == 1:
                s.click(buttons[0]["cb"])
            raise WazeError("Waze could not start a drive to %s. Waze says: %s" % (label, q or "an error"))
        st = summarize(s.trip, s.navigating)
        started = bool(s.navigating) and started
        waits = [b["title"] for b in question_of(s.inner())[1]] if not s.navigating else []
        if waits and is_route_preview(s.inner()):
            route = " ".join(x for x in (span_text(r.get("title")) for r in walk(s.inner()) if r.get("$") == "Row") if x)
            return ("Waze is waiting on the route preview for %s%s: start_drive presses Go now, route_options lists "
                    "the routes." % (label, (" (%s)" % route.replace("\n", ", ")) if route else "")), \
                dict(st, sent=uri, mode="car", waitsFor=waits, routePreview=route or None)
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


def is_message(model):
    """A message of Waze's (its MessageTemplate, alone or over the map): an error or a question."""
    return model.get("$") == "MessageTemplate" or (model.get("contentTemplate") or {}).get("$") == "MessageTemplate"


def is_start_card(model):
    """Waze's "Driving to X? Go now" / "Still driving to X? Resume drive" card: a pane on the map
    with one primary button."""
    pane = model.get("pane") if model.get("$") == "MapTemplate" else None
    if not isinstance(pane, dict):
        return False
    prim = [a for a in pane.get("actions") or [] if isinstance(a, dict) and (a.get("flags") or 0) & 1]
    return len(prim) == 1


def is_route_preview(model):
    """Waze's route preview: a primary Go button beside its Routes button or Avoid."""
    _, buttons = question_of(model)
    if not any(b["primary"] for b in buttons):
        return False
    return any(b["title"].lower() == "avoid" for b in buttons) or any(
        a.get("$") == "Action" and re.search(r"(^|_)routes(_|$)", icon_name(a) or "") for a in walk(model))


def trip_status(args, mode=None, carapp=None):
    mode = mode or interface()
    if mode != "car":
        raise WazeError("Trip status is only known in Car mode (Settings > Waze interface: Car). "
                        "In Phone mode Waze does not report its trip to the device.")
    s = CarSession(carapp)
    if s.navigating and not s.seen_trip:
        s.wait(lambda x: x.seen_trip, 2.0)
    st = summarize(s.trip, s.navigating)
    if s.navigating is None and s.template is None:
        st = {"navigating": False, "known": False, "note": "Waze's car session has not started yet."}
        return "Waze's car session has not started yet, so the trip is not known.", st
    if st.get("navigating"):
        add_stops(st, s)
    # the host sends the navigation state once a drive started or ended: none yet is not navigating
    return sentence(st), st


def add_stops(st, s):
    """The stops Waze's navigation screen counts ("1 stop"), the one this service added, and what
    the estimates then measure (Waze gives them to the next stop)."""
    import stops  # stops.py builds on this module
    nav = s.nav_screen
    if not nav:
        return
    n = stops.stop_count(nav)
    st["stops"] = n
    if n:
        st["tripText"] = stops.trip_text(nav)
        st["estimatesTo"] = "the next stop"
        name = stops.added_stop(s)
        if name:
            st["nextStop"] = {"name": name}


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
