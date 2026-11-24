"""Routes through Waze's car screens (Car mode), out of the driver's sight under the host's hold.

  * Waze's route preview (before a drive: a pane with "Go now", a Routes button and "Avoid") and,
    during a drive, the navigation screen's Routes button open "Alternate routes", a
    RoutePreviewNavigationTemplate: one row per route (its time, distance, "Via ..." and Waze's
    labels such as tolls or traffic), a selected index, and "Go" (or "Resume").
  * The routes list's settings button (and the preview's "Avoid") opens "Route options": toggles
    for toll roads, ferries and freeways, each row with its own icon (icon_tolls_88px,
    icon_ferries_88px, icon_highways_88px).
"""
import re

import waze
from waze import WazeError, plain, span_text, text_of

ROUTES_ICON = re.compile(r"(^|_)routes(_|$)")
SETTINGS_ICON = re.compile(r"(^|_)settings(_|$)")
ROUTE_LIST = "RoutePreviewNavigationTemplate"
AVOID_ICONS = {"tolls": "icon_tolls_88px", "ferries": "icon_ferries_88px", "freeways": "icon_highways_88px"}
LIST_S = 15.0


def car(what):
    if waze.interface() != "car":
        raise WazeError("%s needs Car mode (Settings > Waze interface: Car): in Phone mode Waze's screens are not "
                        "reachable from outside the app." % what)


def action_by_icon(model, pattern):
    return [a for a in waze.walk(model) if a.get("$") == "Action" and isinstance(waze.cb_of(a), int)
            and pattern.search(waze.icon_name(a) or "")]


def open_routes(h):
    """Waze's "Alternate routes" list, from its route preview or its navigation screen."""
    s = h.s
    if s.inner().get("$") != ROUTE_LIST:
        first = lambda m: (lambda a: waze.cb_of(a[0]) if a else None)(action_by_icon(m, ROUTES_ICON))
        if first(s.inner()) is None:
            raise WazeError("Waze offers no routes on its screen now: it lists routes on its route preview (before a "
                            "drive starts) and during a drive.")
        s.press(first, lambda m: m.get("$") == ROUTE_LIST, LIST_S, "Routes button")
    s.wait_template(lambda m: m.get("$") == ROUTE_LIST and not m.get("loading"), LIST_S, "its routes")
    s.settle(waze.SETTLE_S, 1.0)
    return s.inner()


def parse_routes(model):
    il = model.get("itemList") or {}
    sel = il.get("selectedIndex")
    out = []
    for i, r in enumerate(il.get("items") or []):
        if not isinstance(r, dict) or r.get("$") != "Row":
            continue
        e = {"route": i + 1, "selected": i == sel}
        title = r.get("title") or {}
        for sp in title.get("spans") or [] if isinstance(title, dict) else []:
            span = (sp or {}).get("span") or {}
            if span.get("$") == "DurationSpan" and isinstance(span.get("durationSeconds"), (int, float)):
                e["minutes"] = int(round(span["durationSeconds"] / 60.0))
        e["time"] = span_text(title)
        parts = []
        for t in r.get("texts") or []:
            parts += [p.strip() for p in (span_text(t) or "").split("•") if p.strip()]
        for p in parts:
            if re.match(r"^[\d.,]+\s*(km|mi|m|ft)$", p) and "distance" not in e:
                e["distance"] = p
            elif re.match(r"^via\b", p, re.I):
                e["via"] = re.sub(r"^via\s*", "", p, flags=re.I)
            else:
                e.setdefault("labels", []).append(p)
        out.append(e)
    return out, sel


def route_words(r):
    w = "%d. %s" % (r["route"], r.get("time") or "?")
    if r.get("distance"):
        w += ", " + r["distance"]
    if r.get("via"):
        w += ", via " + r["via"]
    if r.get("labels"):
        w += " (%s)" % "; ".join(r["labels"])
    return w + (" [selected]" if r["selected"] else "")


def route_options(args, carapp=None):
    car("Route options")
    h = waze.HeldSearch(carapp)
    try:
        before = "driving" if h.s.navigating else "preview"
        routes, _ = parse_routes(open_routes(h))
    finally:
        h.close()
    st = {"routes": routes, "when": "during the drive" if before == "driving" else "before the drive (Waze's route preview)",
          "flags": "only the labels Waze puts on a route (tolls, traffic, ferries, ...); Waze shows no other route details"}
    if not routes:
        return "Waze lists no routes now.", st
    return "Waze's routes (choose_route with the number):\n" + "\n".join(route_words(r) for r in routes), st


def choose_route(args, carapp=None):
    n = args.get("route")
    if isinstance(n, bool) or not isinstance(n, int) or not 1 <= n <= 10:
        raise WazeError("route must be the number of a route from route_options (1 is the first)")
    car("Choosing a route")
    h = waze.HeldSearch(carapp)
    s = h.s
    try:
        model = open_routes(h)
        routes, sel = parse_routes(model)
        if n > len(routes):
            raise WazeError("Waze lists %d route%s now; there is no route %d." % (len(routes), "s" if len(routes) != 1 else "", n))
        if sel != n - 1:
            cb = ((model.get("itemList") or {}).get("onSelectedDelegate") or {}).get("$cb")
            if not isinstance(cb, int):
                raise WazeError("Waze's routes list takes no choice.")
            s.send({"type": "selected", "cb": cb, "index": n - 1})
            s.wait_template(lambda m: m.get("$") == ROUTE_LIST and (m.get("itemList") or {}).get("selectedIndex") == n - 1,
                            LIST_S, "route %d selected" % n)
            model = s.inner()
        go = model.get("navigateAction") or {}
        if not isinstance(waze.cb_of(go), int):
            raise WazeError("Waze's routes list has no Go button now.")
        label = plain(text_of(go.get("title"))) or "Go"
        s.click(waze.cb_of(go))
        done = s.wait(lambda x: x.navigating and x.inner().get("$") == "NavigationTemplate", LIST_S)
        if done:
            h.keep = True  # Waze left its routes for the drive itself
    finally:
        h.close()
    r = routes[n - 1]
    if not done:
        raise WazeError("Chose route %d, but Waze did not go back to the drive after %s." % (n, label))
    st = waze.summarize(s.trip, s.navigating)
    return "Driving route %s. %s" % (route_words(dict(r, selected=False)), waze.sentence(st)), dict(st, route=r)


def start_drive(args, carapp=None):
    """Waze's own button for the drive it waits on: Go now on its route preview, Go now / Resume drive
    on its "Driving to X?" card. The driver sees it pressed."""
    car("Starting the drive")
    s = waze.CarSession(carapp)
    m = s.inner()
    q, buttons = waze.question_of(m)
    primary = [b for b in buttons if b["primary"]]
    if waze.is_message(m) or not (waze.is_route_preview(m) or waze.is_start_card(m)):
        primary = []  # only Go now / Resume drive: never an error's OK or a report's question
    if len(primary) != 1:
        if s.navigating:
            return "Waze is already driving. " + waze.sentence(waze.summarize(s.trip, s.navigating)), {"navigating": True, "started": False}
        raise WazeError("Waze waits on no drive to start now (its screen offers: %s)." % (", ".join(b["title"] for b in buttons) or "nothing to start"))
    s.click(primary[0]["cb"])
    # driving: Waze navigates, has its estimates, and shows its navigation screen (it says it
    # navigates a moment before it leaves the preview)
    started = s.wait(lambda x: bool(x.navigating and isinstance(x.trip, dict) and x.trip.get("destinationTravelEstimates"))
                     and x.inner().get("$") == "NavigationTemplate", waze.START_WAIT_S)
    st = waze.summarize(s.trip, s.navigating)
    if not s.navigating:
        raise WazeError("Pressed %s, but Waze did not start driving." % primary[0]["title"])
    return ("Pressed %s. " % primary[0]["title"]) + (waze.sentence(st) if started else "The trip details are not known yet."), dict(st, pressed=primary[0]["title"])


def toggles(model):
    """Route options' toggles by their icon: {"tolls": (checked, cb, title)}."""
    out = {}
    for r in waze.walk(model):
        if r.get("$") != "Row" or not isinstance(r.get("toggle"), dict):
            continue
        res = (((r.get("image") or {}).get("icon")) or {}).get("resName")
        for k, icon in AVOID_ICONS.items():
            if res == icon:
                t = r["toggle"]
                out[k] = (bool(t.get("checked") or t.get("isChecked")), (t.get("onCheckedChangeDelegate") or {}).get("$cb"),
                          plain(text_of(r.get("title"))))
    return out


def open_route_options(h):
    s = h.s
    avoid = [a for a in waze.walk(s.inner()) if a.get("$") == "Action" and (plain(text_of(a.get("title"))) or "").lower() == "avoid"
             and isinstance(waze.cb_of(a), int)]
    if avoid:  # Waze's route preview (or its routes list before a drive): Avoid opens them
        s.click(waze.cb_of(avoid[0]))
    else:
        model = open_routes(h)
        st = action_by_icon(model, SETTINGS_ICON)
        if not st:
            raise WazeError("Waze's routes list offers no route options now.")
        s.click(waze.cb_of(st[0]))
    s.wait_template(lambda m: bool(toggles(m)), LIST_S, "its route options")
    s.settle(waze.SETTLE_S, 1.0)
    return s.inner()


def set_avoid(kind, args, carapp=None):
    on = args.get("avoid")
    if not isinstance(on, bool):
        raise WazeError("avoid must be true (avoid them) or false (allow them)")
    car("Route options")
    h = waze.HeldSearch(carapp)
    s = h.s
    try:
        t = toggles(open_route_options(h))
        if kind not in t:
            raise WazeError("Waze's route options have no %s toggle now (they offer: %s)." % (kind, ", ".join(v[2] or k for k, v in t.items())))
        checked, cb, title = t[kind]
        if checked != on:
            if not isinstance(cb, int):
                raise WazeError("Waze's %s toggle cannot be changed now." % title)
            s.send({"type": "checked", "cb": cb, "checked": on})
            # Waze keeps the switch's new state without sending the screen again: read it back by
            # opening the options once more
            if not s.wait(lambda x: toggles(x.inner()).get(kind, (None,))[0] == on, 0.5):
                opts = s.stack()
                n = s.seq
                s.send({"type": "back"})
                s.wait(lambda x: x.seq > n and len(x.stack()) < len(opts), 5.0)
                if toggles(open_route_options(h)).get(kind, (None,))[0] != on:
                    raise WazeError("Waze did not change %s." % title)
    finally:
        h.close()
    word = "on" if on else "off"
    if checked == on:
        return "%s is already %s in Waze." % (title, word), {"setting": kind, "avoid": on, "changed": False}
    return "%s is %s now; Waze plans the route with it." % (title, word), {"setting": kind, "avoid": on, "changed": True}
