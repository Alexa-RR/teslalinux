"""PalGate gates for Grok, through the gates service the car page uses (services/gates, loopback
8082, docs/GATES.md): GET /api/gates for the list, POST /api/gatesOpen for an open. Nothing of
PalGate is done here; the answer is the service's (PalGate's) own.

Opening is a physical action: open_gate needs the driver's switch (Settings > Grok > Let Grok open
gates, off by default) and confirm: true on every call. Only the gates the driver shows on the map
(Settings > Gates) are listed and opened.
"""
import json
import math
import os
import urllib.error
import urllib.request

from carlink import WazeError

GATES_URL = os.environ.get("TESLALINUX_GROK_GATES_URL", "http://127.0.0.1:8082")
TIMEOUT_S = 15.0


def _call(route, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GATES_URL.rstrip("/") + "/api/" + route, data=data, method="POST" if data is not None else "GET",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"null")
        except ValueError:
            return e.code, None
    except (OSError, ValueError) as e:
        raise WazeError("The gates service is not answering (%s); the car page's gates are off or the device is starting." % e)


def _distance(a_lat, a_lon, b_lat, b_lon):
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    dp, dl = p2 - p1, math.radians(b_lon - a_lon)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))


def shown_gates():
    code, st = _call("gates")
    if code != 200 or not isinstance(st, dict):
        raise WazeError("The gates service did not answer the list (HTTP %d)." % code)
    if not st.get("linked"):
        raise WazeError("No PalGate account is linked: the driver links one in Settings > Gates.")
    pos = st.get("position") or None
    out = []
    for g in st.get("gates") or []:
        if not g.get("shown"):
            continue
        auto = g.get("auto") or {}
        e = {"id": g["id"], "name": (g.get("label") or "").strip() or g.get("name") or g["id"], "output": g.get("output"),
             "outputs": g.get("outputs") or [1], "autoOpen": bool(auto.get("enabled"))}
        if len(e["outputs"]) > 1 and g.get("outputNames"):
            e["outputNames"] = g["outputNames"]
        if pos and isinstance(auto.get("lat"), (int, float)) and isinstance(auto.get("lon"), (int, float)):
            e["distanceMeters"] = round(_distance(pos["lat"], pos["lon"], auto["lat"], auto["lon"]))
        if g.get("localOnly"):
            e["bluetoothOnly"] = True
        out.append(e)
    return out, pos


def list_gates(args):
    gates, pos = shown_gates()
    st = {"gates": gates, "position": "known" if pos else "not known (no recent fix from the car)"}
    if not gates:
        return "No gates are shown on the map (Settings > Gates chooses them).", st
    lines = []
    for g in gates:
        w = "%s (id %s)" % (g["name"], g["id"])
        if len(g["outputs"]) > 1:
            names = g.get("outputNames") or {}
            w += ", outputs %s; opens %s by default" % (", ".join("%d%s" % (o, (" (%s)" % names[str(o)]) if names.get(str(o)) else "")
                                                                for o in g["outputs"]), g["output"])
        if "distanceMeters" in g:
            w += ", %d m away" % g["distanceMeters"]
        if g["autoOpen"]:
            w += ", opens by itself on arrival"
        if g.get("bluetoothOnly"):
            w += ", Bluetooth only (from the phone)"
        lines.append(w)
    return "The gates on the map:\n" + "\n".join(lines), st


def open_gate(args, allowed):
    if not allowed:
        raise WazeError("Opening gates is off: the driver turns it on in Settings > Grok > Let Grok open gates.")
    if args.get("confirm") is not True:
        raise WazeError("Opening a gate is a physical action: call again with confirm: true once the driver has asked for it.")
    want = args.get("gate")
    if not isinstance(want, str) or not want.strip():
        raise WazeError("give the gate: its id or name from list_gates")
    output = args.get("output")
    if output is not None and (isinstance(output, bool) or not isinstance(output, int)):
        raise WazeError("output must be a whole number (1 or 2)")
    gates, _ = shown_gates()
    w = want.strip().casefold()
    hits = [g for g in gates if g["id"].casefold() == w] or [g for g in gates if g["name"].casefold() == w] or \
        [g for g in gates if w in g["name"].casefold()]
    if not hits:
        raise WazeError("No gate shown on the map is called %r (there are: %s)." % (want, ", ".join(g["name"] for g in gates) or "none"))
    if len(hits) > 1:
        raise WazeError("%r fits several gates: %s; say which." % (want, ", ".join("%s (id %s)" % (g["name"], g["id"]) for g in hits)))
    g = hits[0]
    if output is not None and output not in g["outputs"]:
        raise WazeError("%s has output%s %s only." % (g["name"], "s" if len(g["outputs"]) > 1 else "", "/".join(str(o) for o in g["outputs"])))
    code, r = _call("gatesOpen", {"id": g["id"], "output": output} if output is not None else {"id": g["id"]})
    r = r if isinstance(r, dict) else {}
    st = {"gate": g["id"], "name": g["name"], "output": output or g["output"], "opened": bool(r.get("ok")), "palgate": r.get("message")}
    if not r.get("ok"):
        raise WazeError("%s did not open: %s" % (g["name"], r.get("message") or "the gates service answered HTTP %d" % code))
    return "%s: %s." % (g["name"], r.get("message") or "Opened"), st
