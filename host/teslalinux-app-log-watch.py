#!/usr/bin/env python3
"""teslalinux: the app's log on its way to the journal, and the milestones of its start in a state file.

  teslalinux-app-log-watch.py STATE_FILE PID STARTED INTERFACE

host/phase1-pios/teslalinux-atl runs the app with its standard error through this (installed as
/usr/local/lib/teslalinux/app-log-watch.py): every line goes on to this process's standard error
unchanged (the unit's journal, as before), and the lines that mark the app's start are written to
STATE_FILE (JSON, replaced atomically) for services/api/api.py's GET /api/appState, which the car
page's start overlay follows (js/display/app-state-overlay.js):
  pid          the app's process (the launcher's own, which execs the app)
  started      when the launcher started (unix time)
  interface    "phone" or "car"
  firstFrame   the app's first picture: SurfaceView's first surfaceChanged (the phone interface's map
               view, the car interface's map surface); the same line host/teslalinux-boottime reads
  mapReady     the phone interface's "Map shown and ready" (Waze's MainActivity)
  exited       the app's standard error closed: the app is gone
Times are unix times. The app keeps running if this process fails: a line it cannot parse or a
state file it cannot write changes nothing but the state file.
"""
import json
import os
import signal
import sys
import time

FRAME = b"surfaceChanged"
FRAME_TAG = b"I/SurfaceView"
MAP_READY = b"Map shown and ready"


def write_state(path, state):
    tmp = "%s.%d.tmp" % (path, os.getpid())
    try:
        with open(tmp, "w") as f:
            json.dump(state, f)
        os.replace(tmp, path)
    except OSError:
        try:
            os.unlink(tmp)
        except OSError:
            pass


def main():
    if len(sys.argv) != 5:
        print(__doc__.split("\n\n")[1], file=sys.stderr)
        return 2
    path = sys.argv[1]
    state = {"pid": int(sys.argv[2]), "started": float(sys.argv[3]), "interface": sys.argv[4],
             "firstFrame": None, "mapReady": None, "exited": None}
    # the app's stop signal reaches this process too (the unit's control group): it stays until the
    # app's standard error closes, so every last line still reaches the journal
    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(sig, signal.SIG_IGN)
    write_state(path, state)
    out = sys.stderr.buffer
    out_ok = True
    for line in sys.stdin.buffer:
        if out_ok:
            try:
                out.write(line)
                out.flush()
            except OSError:
                out_ok = False  # the journal's stream went away: the milestones still count
        if state["firstFrame"] is None and line.startswith(FRAME_TAG) and FRAME in line:
            state["firstFrame"] = time.time()
            write_state(path, state)
        elif state["mapReady"] is None and MAP_READY in line:
            state["mapReady"] = time.time()
            write_state(path, state)
    state["exited"] = time.time()
    write_state(path, state)
    return 0


if __name__ == "__main__":
    sys.exit(main())
