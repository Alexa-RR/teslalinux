"""A place as navigate_to and add_stop take it, resolved (locate.py), and Waze's own lists: Home and
Work on its search screen, its saved and recent places.

resolve_args(args) -> the place locate.resolve found, plus "label" (what the answer calls it) and
"understood" (what the tool result says it took the place for), or {"resultId"}.
"""
import re

import flows
import locate
from carlink import WazeError

SEARCH_ICONS = {"home": "icon_t2_home_32px", "work": "icon_t2_work_32px"}


def car_fix():
    """(lat, lon) of the car's last GPS fix, or None (for short Plus Codes)."""
    import info  # info.py builds on waze.py, which imports this module
    try:
        _, st = info.car_position({})
    except WazeError:
        return None
    return (st["latitude"], st["longitude"]) if st.get("known") else None


def resolve_args(args):
    """The place in navigate_to's / add_stop's arguments: destination (or place) in any form, link,
    latitude and longitude, or resultId."""
    rid = args.get("resultId")
    text = args.get("destination") if args.get("destination") is not None else args.get("place")
    link, lat, lon, name = args.get("link"), args.get("latitude"), args.get("longitude"), args.get("name")
    given = [k for k, v in (("destination", text), ("link", link), ("latitude", lat if lat is not None else lon), ("resultId", rid))
             if v is not None]
    if not given:
        raise WazeError("give a destination: an address or place name, coordinates, a map link, or a resultId")
    if len(given) > 1:
        raise WazeError("give the place one way only (destination, link, latitude and longitude, or resultId), not both")
    if name is not None and not isinstance(name, str):
        raise WazeError("name must be text")
    if rid is not None:
        return {"resultId": rid}
    if lat is not None or lon is not None:
        if lat is None or lon is None:
            raise WazeError("give both latitude and longitude, or neither")
        if any(isinstance(v, bool) or not isinstance(v, (int, float)) for v in (lat, lon)):
            raise WazeError("latitude and longitude must be numbers")
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise WazeError("latitude must be -90..90 and longitude -180..180")
        p = {"latitude": float(lat), "longitude": float(lon), "source": "coordinates", "how": "latitude and longitude"}
    else:
        t = link if link is not None else text
        if not isinstance(t, str):
            raise WazeError("the place must be text")
        if len(t) > 4000:
            raise WazeError("the place is longer than 4000 characters")
        saved = re.match(r"^\s*(?:saved|favou?rite|my saved place)[:\s]+(.+)$", t, re.I)
        if saved:
            p = {"list": "saved", "name": saved.group(1).strip(), "source": "Waze's saved places", "how": "a saved place's name"}
        elif (t.strip().casefold() in [n.casefold() for n in flows.get("saved_names") or []]):
            p = {"list": "saved", "name": t.strip(), "source": "Waze's saved places", "how": "a saved place's name"}
        else:
            p = locate.resolve(t, near=car_fix() if re.search(r"\+", t) else None)
    label = (name or "").strip() or p.get("name") or p.get("query") or (
        "%s,%s" % (_num(p["latitude"]), _num(p["longitude"])) if "latitude" in p else p.get("source"))
    understood = {k: p[k] for k in ("source", "how", "name", "query", "latitude", "longitude", "list") if k in p}
    return dict(p, label=label, understood=understood)


def _num(x):
    return ("%.6f" % float(x)).rstrip("0").rstrip(".")


def find_in_list(h, p):
    """Opens the Waze list a place is in and finds it: (row index, name) for h.details()."""
    import waze
    kind = p["list"]
    if kind in SEARCH_ICONS:
        s = h.s
        if not waze.search_actions(s.inner()):
            raise WazeError("Waze's current car screen offers no search, where it keeps %s." % p["name"])
        s.press(waze.first_search, lambda m: m.get("$") == "SearchTemplate", 8, "search button")
        s.settle(waze.SETTLE_S, 1.0)
        h.list_stack = s.stack()
        for i, r in enumerate(h.rows()):
            if (((r.get("image") or {}).get("icon")) or {}).get("resName") == SEARCH_ICONS[kind]:
                return i, waze.plain(waze.text_of(r.get("title"))) or p["name"]
        raise WazeError("Waze has no %s place set (its search lists it once it is set in the driver's Waze)." % p["name"])
    h.open_list(kind)
    names = [waze.parse_row(r).get("name") or "" for r in h.rows()]
    if kind == "saved":
        flows.put("saved_names", [n for n in names if n][:50])
    if "index" in p:
        if p["index"] < len(names):
            return p["index"], names[p["index"]]
        raise WazeError("Waze's %s places list is empty." % kind)
    want = p["name"].casefold()
    for test in (lambda n: n.casefold() == want, lambda n: want in n.casefold()):
        hits = [i for i, n in enumerate(names) if n and test(n)]
        if hits:
            return hits[0], names[hits[0]]
    raise WazeError("%r is not among Waze's %s places (%s)." % (p["name"], kind, ", ".join(n for n in names if n) or "none"))
