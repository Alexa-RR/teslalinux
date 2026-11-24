"""Stops on a Waze drive, the way Waze's car interface adds them (Car mode).

During a drive Waze's place card (search, a result, its card) offers "New drive" and "Add a stop"
(its primary action). "Add a stop" puts the place before the destination: Waze routes through it,
its NavigationTemplate's travel estimate then counts the stops ("1 stop", TravelEstimate.tripText)
and gives the time and distance to the next one, and the trip keeps the final destination's name.
While a drive has a stop, Waze's navigation screen offers no search, so its car interface takes one
stop per drive; it has no screen that lists or removes stops (checked with Waze 5.27 in the
Debian VM): remove_stop says so.
"""
import re
import time

import flows
import places
import waze
from waze import WazeError, plain, text_of

ADD_WAIT_S = 15.0
ESTIMATE_WAIT_S = 10.0
STOP_WORD = re.compile(r"\bstop\b", re.I)
# the stop this service added to the current drive: {"name", "destination", "at"}; only reported
# while Waze's own trip still counts a stop and has the same destination
_added = {}


def trip_text(model):
    """The NavigationTemplate's tripText (Waze's "1 stop"), or None."""
    est = (model or {}).get("destinationTravelEstimate") or {}
    t = plain(text_of(est.get("tripText")))
    return t or None


def stop_count(model):
    """How many stops Waze's navigation screen counts (0 when it shows none)."""
    t = trip_text(model)
    if not t:
        return 0
    m = re.search(r"\d+", t)
    return int(m.group()) if m else 1


def estimate_of(trip):
    """The trip's distance and time left: both change once Waze routes through a stop (it may first
    send an estimate with only one of them new)."""
    e = ((trip or {}).get("destinationTravelEstimates") or [None])[0]
    if not isinstance(e, dict):
        return None, None
    return ((e.get("remainingDistance") or {}).get("displayDistance"), e.get("remainingTimeSeconds"))


def estimate_moved(trip, before):
    now = estimate_of(trip)
    return now[0] != before[0] and now[1] != before[1]


def final_name(trip):
    d = ((trip or {}).get("destinations") or [None])[0]
    return plain(text_of((d or {}).get("name"))) if isinstance(d, dict) else None


def added_stop(s):
    """The stop this service added, while Waze still counts one on the same drive."""
    if not _added or not s.navigating or stop_count(s.nav_screen) == 0:
        return None
    if _added.get("destination") != final_name(s.trip):
        return None
    return _added.get("name")


def query_of(args):
    """The stop in add_stop's arguments, resolved (places.py): any way of naming a place."""
    p = places.resolve_args(args)
    if "resultId" in p:
        waze.parse_any_id(p["resultId"])
        return p
    if "latitude" in p:
        # Waze's car search takes coordinates as text ("lat, lon") and offers the point as a result
        p["query"] = "%s, %s" % (waze._num(p["latitude"]), waze._num(p["longitude"]))
    return p


def add_action(pane):
    """Waze's "Add a stop" on a place card: the action learned for this Waze version, else the
    one whose title names a stop, else the card's primary action during a drive."""
    acts = [a for a in pane.get("actions") or [] if isinstance(a, dict) and isinstance(waze.cb_of(a), int)]
    titles = [plain(text_of(a.get("title"))) or "" for a in acts]
    learned = flows.get("add_stop")
    if learned and learned in titles:
        return acts[titles.index(learned)], titles
    for a, t in zip(acts, titles):
        if STOP_WORD.search(t):
            return a, titles
    primary = [a for a in acts if (a.get("flags") or 0) & 1]
    if len(primary) == 1 and len(acts) > 1:
        return primary[0], titles
    return None, titles


def add_stop(args, mode=None, carapp=None):
    if (mode or waze.interface()) != "car":
        raise WazeError("Adding a stop needs Car mode (Settings > Waze interface: Car). In Phone mode Waze's "
                        "screens are not reachable from outside the app.")
    p = query_of(args)
    query = p.get("query")
    h = waze.HeldSearch(carapp)
    s = h.s
    try:
        if not s.navigating:
            raise WazeError("Waze adds a stop only during a drive: start one with navigate_to first.")
        n0 = stop_count(s.nav_screen)
        if n0 and not waze.has_search(s.inner()):
            raise WazeError("This drive has a stop already (Waze: %s). Waze's car interface takes one stop per drive, "
                            "and offers no way to remove or change it; stop_navigation and navigate_to start over."
                            % trip_text(s.inner()))
        if "resultId" in p:
            i, name = waze.find_result(h, p["resultId"])
        elif "list" in p:
            i, name = places.find_in_list(h, p)
        else:
            h.results(query)
            rows = h.rows()
            if not rows:
                raise WazeError("Waze found nothing for %r." % query)
            i, name = 0, waze.parse_row(rows[0]).get("name") or query
        det, pane = h.details(i)
        act, titles = add_action(pane)
        if act is None:
            raise WazeError("Waze's card for %s offers no way to add it as a stop (it offers: %s)." % (name, ", ".join(t for t in titles if t) or "nothing"))
        dest = final_name(s.trip)
        est0 = estimate_of(s.trip)
        s.click(waze.cb_of(act))
        # Waze goes back to its navigation screen on its own, now counting the stop
        done = s.wait(lambda x: x.inner().get("$") == "NavigationTemplate" and stop_count(x.inner()) > n0, ADD_WAIT_S)
        if done:
            h.keep = True
            flows.put("add_stop", plain(text_of(act.get("title"))))
            # then its route through the stop: the trip's estimate becomes the stop's
            s.wait(lambda x: estimate_moved(x.trip, est0), ESTIMATE_WAIT_S)
        st = waze.summarize(s.trip, s.navigating)
        st["stops"] = stop_count(s.inner())
        st["tripText"] = trip_text(s.inner())
        asks = None if done else waze.question_of(s.inner())
    finally:
        h.close()
    addr = waze.address_of(det, name)
    if not done:
        if asks and asks[1]:
            return ("Asked Waze to add %s as a stop; it waits on the car screen for: %s." % (name, ", ".join(b["title"] for b in asks[1]))), \
                dict(st, stop={"name": name, "address": addr}, added=None, waitsFor=[b["title"] for b in asks[1]])
        raise WazeError("Waze did not add %s as a stop within %d s." % (name, ADD_WAIT_S))
    _added.update(name=name, destination=dest, at=time.time())
    st.update(stop={"name": name, "address": addr}, added=True)
    if "understood" in p:
        st["understood"] = p["understood"]
    how = "" if "resultId" in p or p.get("understood", {}).get("source") == "an address or place name" else waze.understood_words(p) + " "
    return (how + "Added %s as a stop before %s. Waze: %s; %s." % (name, dest or "the destination", st["tripText"],
                                                                    waze.estimate_words(st) or "the new estimate is not known yet")), st


PLACE_KEYS = {"destination", "latitude", "longitude", "name", "resultId", "link"}


def label_of(d):
    p = places.resolve_args(d)
    return waze.parse_result_id(p["resultId"])[2] if "resultId" in p else p["label"]


def navigate_many(places, mode=None, carapp=None):
    """navigate_to with several places in the order they are visited: Waze drives to the last, and
    the ones before it go in as stops as far as Waze's car interface takes them (one per drive)."""
    if not isinstance(places, list) or not 1 <= len(places) <= 10:
        raise WazeError("destinations must be a list of 1 to 10 places")
    for d in places:
        if not isinstance(d, dict) or not d or set(d) - PLACE_KEYS:
            raise WazeError("each destination is an object with destination, latitude and longitude, or resultId (and an optional name)")
        if d.get("resultId") is not None and any(d.get(k) is not None for k in ("destination", "latitude", "longitude")):
            raise WazeError("give each destination as either resultId or a place, not both")
        label_of(d)  # checks it before anything is sent
    if len(places) == 1:
        return waze.navigate_to(places[0], mode, carapp)
    mode = mode or waze.interface()
    if mode != "car":
        raise WazeError("Several destinations need Car mode (Settings > Waze interface: Car): stops are added on Waze's "
                        "car screens. In Phone mode navigate_to takes one destination.")
    final = places[-1]
    text, st = waze.navigate_to(final, mode, carapp)
    added, skipped = [], []
    for d in places[:-1]:
        label = label_of(d)
        if not st.get("navigating"):
            skipped.append({"place": label, "why": "Waze is not driving yet, so it takes no stops"})
            continue
        try:
            _, r = add_stop(d, mode, carapp)
            if r.get("added"):
                added.append(r["stop"]["name"])
                st = r
            else:
                skipped.append({"place": label, "why": "Waze waits on the car screen for: %s" % ", ".join(r.get("waitsFor") or [])})
        except WazeError as e:
            skipped.append({"place": label, "why": str(e)})
    st = dict(st, stopsAdded=added, notAdded=skipped)
    words = [text]
    if added:
        words.append("Stop added: %s (Waze drives there first)." % ", ".join(added))
    for k in skipped:
        words.append("Not added: %s: %s" % (k["place"], k["why"]))
    return " ".join(words), st


def remove_stop(args, mode=None, carapp=None):
    if (mode or waze.interface()) != "car":
        raise WazeError("Stops need Car mode (Settings > Waze interface: Car).")
    raise WazeError("Waze's car interface offers no way to remove a stop: it has no stops list, and its navigation "
                    "screen hides search while a stop is set. To drop the stop, stop_navigation and navigate_to the "
                    "destination again (Waze then drives there without it).")
