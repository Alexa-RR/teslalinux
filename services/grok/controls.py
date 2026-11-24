"""Waze's car controls, one tool each (Car mode): its sound, the map's zoom and center-on-car, and
the buttons of an alert Waze shows ("Still there?"). Sound is changed on Waze's Sound list out of
the driver's sight; the map controls are pressed where the driver sees them (that is what they
are for).
"""
import re
import time

import waze
from waze import WazeError, plain, text_of

SOUND_ICON = re.compile(r"(^|_)sound_[a-z_]*outline")
SETTINGS_ICON = re.compile(r"(^|_)settings?_outline")
ZOOM_BOTH = re.compile(r"zoom_in_out")
ZOOM = {"in": re.compile(r"zoom_in_\d"), "out": re.compile(r"zoom_out_\d")}
RECENTER = re.compile(r"recenter")
# Waze's sound modes by its sound button's icon (t1_sound_<mode>_outline_32px)
SOUND_MODES = {"on": "Normal", "brief": "Less chatty", "smart_mute": "Smart mute", "beep": "Beep", "alerts": "Only alerts", "off": "Off"}
WAIT_S = 8.0


def car(what):
    if waze.interface() != "car":
        raise WazeError("%s needs Car mode (Settings > Waze interface: Car): in Phone mode Waze's controls are not "
                        "reachable from outside the app." % what)


def actions(model, pattern):
    return [a for a in waze.walk(model) if a.get("$") == "Action" and isinstance(waze.cb_of(a), int)
            and pattern.search(waze.icon_name(a) or "")]


def sound_now(model):
    for a in actions(model, SOUND_ICON):
        m = re.search(r"sound_([a-z_]+?)_outline", waze.icon_name(a) or "")
        if m and m.group(1) in SOUND_MODES:
            return SOUND_MODES[m.group(1)]
    return None


def open_sound(h):
    s = h.s
    first = lambda m: (lambda a: waze.cb_of(a[0]) if a else None)(actions(m, SOUND_ICON))
    row = lambda m: (lambda r: waze.cb_of(r[0]) if r else None)(
        [r for r in waze.walk(m) if r.get("$") == "Row" and (plain(text_of(r.get("title"))) or "").lower() == "sound"
         and isinstance(waze.cb_of(r), int)])
    is_list = lambda m: sound_list(m) is not None
    if first(s.inner()) is not None:
        s.press(first, is_list, WAIT_S, "sound button")
    elif row(s.inner()) is not None:  # Waze's settings list, where it keeps Sound outside a drive
        s.press(row, is_list, WAIT_S, "Sound entry")
    else:
        st = (lambda a: waze.cb_of(a[0]) if a else None)
        if st(actions(s.inner(), SETTINGS_ICON)) is None:
            raise WazeError("Waze's screen offers no sound control now (it has one on its map during a drive, and in "
                            "its settings).")
        s.press(lambda m: st(actions(m, SETTINGS_ICON)), lambda m: row(m) is not None, WAIT_S, "settings button")
        s.settle(waze.SETTLE_S, 1.0)
        s.press(row, is_list, WAIT_S, "Sound entry")
    s.settle(waze.SETTLE_S, 1.0)
    return s.inner()


def sound_list(model):
    """(titles, selected index, the list's select callback) of Waze's Sound list, or None."""
    for il in waze.walk(model):
        if il.get("$") != "ItemList" or not isinstance((il.get("onSelectedDelegate") or {}).get("$cb"), int):
            continue
        titles = [plain(text_of(r.get("title"))) or "" for r in il.get("items") or [] if isinstance(r, dict)]
        if set(SOUND_MODES.values()) & set(titles):
            return titles, il.get("selectedIndex"), il["onSelectedDelegate"]["$cb"]
    return None


def set_sound(args, carapp=None):
    mode = args.get("mode")
    if not isinstance(mode, str) or not mode.strip():
        raise WazeError("give the mode: one of Waze's sound choices (Normal, Less chatty, Only alerts, Off, ...)")
    car("Changing Waze's sound")
    h = waze.HeldSearch(carapp)
    s = h.s
    try:
        titles, sel, cb = sound_list(open_sound(h))
        norm = lambda x: re.sub(r"[\s_-]+", " ", x.strip().lower())
        want = [i for i, t in enumerate(titles) if norm(t) == norm(mode)]
        if not want:
            raise WazeError("Waze offers no sound mode %r; it offers: %s." % (mode, ", ".join(titles)))
        i = want[0]
        if sel != i:
            s.send({"type": "selected", "cb": cb, "index": i})
            done = lambda x: (sound_list(x.inner()) or (0, None))[1] == i or sound_now(x.inner()) == titles[i]
            # during a drive Waze goes back to its map with the new sound icon; from its settings it
            # keeps the choice without sending the list again: read it back by opening the list anew
            if not s.wait(done, 0.7):
                if sound_list(s.inner()) is not None:
                    depth0, n = len(s.stack()), s.seq
                    s.send({"type": "back"})
                    s.wait(lambda x: x.seq > n and len(x.stack()) < depth0, 5.0)
                if not done(s) and (sound_list(open_sound(h)) or (0, None))[1] != i:
                    raise WazeError("Waze did not change its sound to %s." % titles[i])
    finally:
        h.close()
    if sel == i:
        return "Waze's sound is already %s." % titles[i], {"sound": titles[i], "changed": False}
    return "Waze's sound is %s now." % titles[i], {"sound": titles[i], "changed": True}


def zoom(direction, args, carapp=None):
    steps = args.get("steps", 1)
    if isinstance(steps, bool) or not isinstance(steps, int) or not 1 <= steps <= 5:
        raise WazeError("steps must be a whole number from 1 to 5")
    car("Zooming Waze's map")
    s = waze.CarSession(carapp)
    for k in range(steps):
        acts = actions(s.inner(), ZOOM[direction])
        if not acts:
            both = actions(s.inner(), ZOOM_BOTH)
            if not both:
                raise WazeError("Waze's screen shows no zoom buttons now.")
            # during a drive Waze folds its zoom buttons into one, which opens them
            if not s.tap(waze.cb_of(both[0]), WAIT_S) or not s.wait(lambda x: bool(actions(x.inner(), ZOOM[direction])), WAIT_S):
                raise WazeError("Waze did not show its zoom buttons.")
            acts = actions(s.inner(), ZOOM[direction])
        n = s.seq
        s.click(waze.cb_of(acts[0]))
        if k + 1 < steps:
            s.wait_new(n, 0.5)  # Waze answers a zoom with a new strip; the next press goes after it
    return "Zoomed Waze's map %s%s." % (direction, (" %d steps" % steps) if steps > 1 else ""), {"zoom": direction, "steps": steps}


def recenter(args, carapp=None):
    car("Centering Waze's map")
    s = waze.CarSession(carapp)
    acts = actions(s.inner(), RECENTER)
    if not acts:
        return "Waze's map already follows the car (it offers center-on-car only after the map was moved).", {"recentered": False}
    s.click(waze.cb_of(acts[0]))
    s.wait(lambda x: not actions(x.inner(), RECENTER), WAIT_S)
    return "Waze's map follows the car again.", {"recentered": True}


def active_alert(link):
    """The newest alert Waze still shows, with buttons: (alert, [(title, cb)]) or (None, [])."""
    for _, at, a in reversed(list(link.alerts)):
        if a.get("alertId") in link.dismissed:
            continue
        dur = a.get("durationMillis") or 0
        if dur and time.time() - at > dur / 1000.0:
            continue
        buttons = [(plain(text_of(b.get("title"))), waze.cb_of(b)) for b in a.get("actions") or [] if isinstance(b, dict)]
        buttons = [(t, cb) for t, cb in buttons if t and isinstance(cb, int)]
        if buttons:
            return a, buttons
    return None, []


def answer_alert(args, carapp=None):
    ans = args.get("answer")
    if not isinstance(ans, str) or not ans.strip():
        raise WazeError("give the answer: one of the alert's buttons (for example 'Still there' or 'Not there')")
    car("Answering Waze's alert")
    s = waze.CarSession(carapp)
    a, buttons = active_alert(s.link)
    if a is None:
        raise WazeError("Waze shows no alert with buttons now.")
    pick = [b for b in buttons if b[0].lower() == ans.strip().lower()]
    if not pick:
        raise WazeError("Waze's alert (%s) offers: %s." % (waze.span_text(a.get("title")) or "alert", ", ".join(b[0] for b in buttons)))
    s.click(pick[0][1])
    return "Answered Waze's alert (%s): %s." % (waze.span_text(a.get("title")) or "alert", pick[0][0]), \
        {"alert": waze.span_text(a.get("title")), "answer": pick[0][0]}
