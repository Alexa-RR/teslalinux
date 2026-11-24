"""Read-only tools: what Waze already sent the car host (kept by the car link, carlink.py) and what
the device knows, answered without touching Waze's screens. Nothing here is guessed: a value Waze
or the device did not give is said to be unknown. Each answer holds only what the driver can see
on the car screen or the settings page (the car's position is the one the driver's car sends).
"""
import json
import os
import re
import socket
import time
import urllib.request

import waze
from waze import WazeError, plain, span_text, text_of

GPS_FEED = os.environ.get("TESLALINUX_GROK_GPS_FEED", "127.0.0.1:9997")
API_STATUS = os.environ.get("TESLALINUX_GROK_API_STATUS", "http://127.0.0.1:8081/api/status")

# androidx.car.app.navigation.model.LaneDirection SHAPE_*
LANE_SHAPES = {0: "unknown", 1: "straight", 2: "slight left", 3: "slight right", 4: "left", 5: "right",
               6: "sharp left", 7: "sharp right", 8: "U-turn left", 9: "U-turn right"}
# what Waze's car controls are, by their icons' resource names (the same in every language)
CONTROLS = [(r"search", "search"), (r"routes", "routes"), (r"sound_(on|brief|smart_mute|beep)", "sound"),
            (r"sound_alerts", "sound (only alerts)"), (r"sound_off", "sound (off)"), (r"zoom_in_out", "zoom controls"),
            (r"zoom_in", "zoom in"), (r"zoom_out", "zoom out"), (r"report_button", "report"), (r"panning", "move the map"),
            (r"recenter", "center on the car"), (r"settings", "settings"), (r"saved", "saved places"),
            (r"home", "home"), (r"work", "work"), (r"close", "close"), (r"phone", "call"), (r"chevron", "details"),
            (r"info", "information"), (r"no_network", "offline")]


def car_only(what):
    if waze.interface() != "car":
        raise WazeError("%s is known in Car mode only (Settings > Waze interface: Car): in Phone mode Waze sends "
                        "nothing to the device." % what)
    return waze.CarSession()


def control_name(action):
    t = plain(text_of(action.get("title")))
    if t:
        return t
    res = waze.icon_name(action) or ""
    for pat, word in CONTROLS:
        if re.search(pat, res):
            return word
    return None


def lanes_of(step):
    out = []
    for lane in (step or {}).get("lanes") or []:
        dirs = [d for d in (lane or {}).get("directions") or [] if isinstance(d, dict)]
        shapes = [LANE_SHAPES.get(d.get("shape"), "unknown") for d in dirs]
        out.append({"directions": shapes, "recommended": any(d.get("isRecommended") or d.get("recommended") for d in dirs)})
    return out


def next_turn(args):
    s = car_only("The next turn")
    if not s.navigating:
        return "Waze is not navigating, so there is no next turn.", {"navigating": False}
    trip = s.trip or {}
    steps = trip.get("steps") or []
    ests = trip.get("stepTravelEstimates") or []
    st = {"navigating": True}
    turns = []
    for i, step in enumerate(steps[:2]):
        if not isinstance(step, dict):
            continue
        t = {}
        cue = plain(text_of(step.get("cue")))
        if cue:
            t["instruction"] = cue
        man = step.get("maneuver") or {}
        if isinstance(man.get("type"), int) and man["type"] in waze.MANEUVERS and man["type"] != 0:
            t["maneuver"] = waze.MANEUVERS[man["type"]]
        if isinstance(man.get("roundaboutExitNumber"), int) and man["roundaboutExitNumber"] > 0:
            t["roundaboutExit"] = man["roundaboutExitNumber"]
        road = plain(text_of(step.get("road")))
        if road:
            t["road"] = road
        if i < len(ests) and isinstance(ests[i], dict):
            d = waze.distance_of(ests[i].get("remainingDistance"))
            if d and (d["value"] > 0 or (ests[i].get("remainingTimeSeconds") or -1) >= 0):
                t["in"] = d
        lanes = lanes_of(step)
        if lanes:
            t["lanes"] = lanes
        elif step.get("lanesImage"):
            t["lanes"] = "Waze draws the lanes as a picture only"
        if t:
            turns.append(t)
    if turns:
        st["next"] = turns[0]
    if len(turns) > 1:
        st["then"] = turns[1]
    road = plain(text_of(trip.get("currentRoad")))
    if road:
        st["currentRoad"] = road
    st["junctionImage"] = "not given: Waze's car interface sends no junction pictures"
    if "next" not in st:
        return "Waze has not sent its next turn yet.", st
    n = st["next"]
    words = "Next: %s%s" % (n.get("instruction") or n.get("maneuver") or "a turn", (" in " + n["in"]["text"]) if "in" in n else "")
    if isinstance(n.get("lanes"), list):
        rec = [str(k + 1) for k, l in enumerate(n["lanes"]) if l["recommended"]]
        words += "; %d lanes%s" % (len(n["lanes"]), (", use lane %s (from the left)" % ", ".join(rec)) if rec else "")
    if "then" in st:
        words += ". Then: %s" % (st["then"].get("instruction") or st["then"].get("maneuver"))
    return words + ".", st


def speed(args):
    s = car_only("The speed")
    sp = s.link.speedometer
    if not sp:
        return ("Not known: Waze has sent no speedometer reading (the device reads Waze's speedometer only for the "
                "Waze versions it knows, and only while it has a GPS fix)."), {"known": False}
    unit = sp.get("units") or ("mph" if sp.get("miles") else "km/h")
    st = {"known": True, "unit": unit, "ageSeconds": int(time.time() - sp["at"])}
    if isinstance(sp.get("speed"), (int, float)) and sp["speed"] >= 0:
        st["speed"] = round(sp["speed"])
    if isinstance(sp.get("speedLimit"), (int, float)) and sp["speedLimit"] > 0:
        st["speedLimit"] = round(sp["speedLimit"])
    words = ("Speed %d %s" % (st["speed"], unit)) if "speed" in st else "Speed not known"
    words += (", limit %d %s." % (st["speedLimit"], unit)) if "speedLimit" in st else ", no speed limit known for this road."
    return words, st


def waze_alerts(args):
    minutes = args.get("minutes", 10)
    if isinstance(minutes, bool) or not isinstance(minutes, int) or not 1 <= minutes <= 60:
        raise WazeError("minutes must be a whole number from 1 to 60")
    s = car_only("Waze's alerts")
    since = time.time() - minutes * 60
    alerts = []
    for _, at, a in list(s.link.alerts):
        if at < since:
            continue
        e = {"title": span_text(a.get("title")), "minutesAgo": int((time.time() - at) / 60)}
        sub = span_text(a.get("subtitle"))
        if sub:
            e["subtitle"] = sub
        buttons = [plain(text_of(b.get("title"))) for b in a.get("actions") or [] if isinstance(b, dict)]
        if any(buttons):
            e["buttons"] = [b for b in buttons if b]
        alerts.append(e)
    toasts = [{"text": plain(t), "minutesAgo": int((time.time() - at) / 60)} for at, t in list(s.link.toasts) if at >= since]
    st = {"alerts": alerts, "messages": toasts, "minutes": minutes}
    if not alerts and not toasts:
        return "Waze showed no alerts or messages in the last %d minutes." % minutes, st
    lines = ["%s%s (%d min ago)" % (a["title"] or "alert", (": " + a["subtitle"]) if a.get("subtitle") else "", a["minutesAgo"]) for a in alerts]
    lines += ["%s (%d min ago)" % (t["text"], t["minutesAgo"]) for t in toasts]
    return "Waze showed:\n" + "\n".join(lines), st


def current_screen(args):
    s = car_only("Waze's screen")
    m = s.inner()
    if not m:
        return "Waze's car session has not started yet.", {"known": False}
    titles = []
    for d in waze.walk(m):
        if d.get("$") == "Header" or d is m:
            t = span_text(d.get("title"))
            if t and t not in titles:
                titles.append(t)
    controls = []
    for d in waze.walk(m):
        if d.get("$") == "Action" and (waze.cb_of(d) is not None or d.get("type") == 65540):
            n = control_name(d)
            if n and n not in controls:
                controls.append(n)
    rows = [span_text(d.get("title")) for d in waze.walk(m) if d.get("$") in ("Row", "GridItem")]
    q, buttons = waze.question_of(m) if m.get("$") in ("MessageTemplate",) or m.get("pane") or (m.get("contentTemplate") or {}).get("$") == "MessageTemplate" else (None, [])
    st = {"template": m.get("$"), "navigating": bool(s.navigating), "titles": titles, "controls": controls,
          "items": [r for r in rows if r][:12], "night": s.link.day_night,
          "drivingRestrictions": bool((s.link.restrictions or {}).get("active"))}
    if buttons:
        st["waitsFor"] = [b["title"] for b in buttons]
    kind = {"NavigationTemplate": "its map" + (" with the drive" if s.navigating else ""), "SearchTemplate": "its search",
            "PlaceListNavigationTemplate": "search results", "RoutePreviewNavigationTemplate": "a list of routes",
            "GridTemplate": "a grid", "ListTemplate": "a list", "MessageTemplate": "a message"}.get(m.get("$"), "its map with a panel")
    words = "Waze shows %s%s." % (kind, (": " + " / ".join(titles)) if titles else "")
    if st["items"]:
        words += " Items: %s." % ", ".join(st["items"][:6])
    if buttons:
        words += " It waits for: %s." % ", ".join(st["waitsFor"])
    if controls:
        words += " Controls: %s." % ", ".join(controls)
    return words, st


def _num(x):
    try:
        v = float(x)
    except ValueError:
        return None
    return v if v == v else None


def car_position(args):
    host, _, port = GPS_FEED.rpartition(":")
    try:
        c = socket.create_connection((host, int(port)), timeout=0.5)
    except OSError:
        raise WazeError("The car's position is not known: the GPS bridge is not running.")
    data = b""
    try:
        c.settimeout(0.4)
        while b"\n" not in data and len(data) < 512:
            chunk = c.recv(256)
            if not chunk:
                break
            data += chunk
    except OSError:
        pass
    finally:
        c.close()
    f = data.split(b"\n", 1)[0].decode("ascii", "replace").split()
    if len(f) < 6:
        return "The car's position is not known: the car sent no GPS fix in the last 10 seconds.", {"known": False}
    lat, lon, mps, bearing, acc, ts = (_num(x) for x in f[:6])
    st = {"known": True, "latitude": lat, "longitude": lon}
    if mps is not None:
        st["speedKmh"] = round(mps * 3.6)
    if bearing is not None:
        st["headingDegrees"] = round(bearing)
    if acc is not None:
        st["accuracyMeters"] = round(acc)
    if ts is not None:
        st["ageSeconds"] = max(0, int(time.time() - ts / 1000))
    words = "The car is at %.5f, %.5f" % (lat, lon)
    if "headingDegrees" in st:
        words += ", heading %d°" % st["headingDegrees"]
    if "speedKmh" in st:
        words += ", %d km/h" % st["speedKmh"]
    return words + " (from the car's GPS).", st


def device_status(args):
    try:
        with urllib.request.urlopen(API_STATUS, timeout=6) as r:
            d = json.loads(r.read())
    except (OSError, ValueError) as e:
        raise WazeError("The device's status is not known (its settings service did not answer: %s)." % e)
    up = d.get("uplink") or {}
    net = d.get("internet") or {}
    modem = d.get("modem") or {}
    wifi = ((d.get("failover") or {}).get("wifi")) or {}
    st = {"connection": up.get("kind"), "interface": up.get("interface"), "internet": net.get("reachable"),
          "internetLatencyMs": net.get("latencyMs"), "battery": "not applicable: the device runs on the car's power"}
    if modem:
        st["lte"] = {k: modem.get(k) for k in ("operator", "technology", "signalPercent", "state") if modem.get(k) is not None}
    if wifi.get("ssid"):
        st["wifi"] = {"ssid": wifi.get("ssid"), "signalDbm": wifi.get("signalDbm")}
    hs = d.get("hotspot") or {}
    if hs.get("running") is not None:
        st["carHotspot"] = {"running": hs.get("running"), "clients": len(hs.get("clients") or [])}
    temp = (d.get("system") or {}).get("cpuTemperature")
    if isinstance(temp, (int, float)) and temp > 0:
        st["cpuTemperatureC"] = temp
    kind = {"lte": "LTE", "wifi": "Wi-Fi", "ethernet": "Ethernet"}.get(up.get("kind"), up.get("kind") or "no connection")
    words = "Online over %s" % kind if net.get("reachable") else "Offline (uplink: %s)" % kind
    if st.get("lte", {}).get("signalPercent") is not None:
        words += ", LTE signal %d%%" % st["lte"]["signalPercent"]
    if st.get("wifi", {}).get("signalDbm") is not None:
        words += ", Wi-Fi %s at %s dBm" % (st["wifi"]["ssid"], st["wifi"]["signalDbm"])
    if net.get("latencyMs") is not None:
        words += ", %d ms to the internet" % net["latencyMs"]
    return words + ".", st
