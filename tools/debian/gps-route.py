#!/usr/bin/env python3
"""teslalinux: drive a simulated car along a real road route through the car page's GPS WebSocket.

  gps-route.py [--from LAT,LON] [--to LAT,LON] [--hold SECONDS] [--speed-factor F]
               [--route FILE] [--url ws://127.0.0.1:9998/] [--osrm URL]

Defaults: from Ben Gurion Airport (Terminal 3) to Dizengoff Square, Tel Aviv.

The route comes from an OSRM server (default: the public demo server, router.project-osrm.org):
the road geometry with each segment's length and estimated driving time. --route FILE caches it:
an existing file is used as is, otherwise the fetched route is saved there.

The car first stands still at the start for --hold seconds (time to pick the destination in Waze),
then follows the road, heading along it: at --highway-kmh (default 120) on segments OSRM estimates
at 70 km/h or more, elsewhere at OSRM's estimate but at least --min-kmh (default 40), everything
times --speed-factor; one fix per second in the upstream JSON format the browser sends (docs/UPSTREAM-NOTES.md,
the same messages as gps-drive.py), and finally stands at the destination for --end-hold seconds.
"""
import argparse
import asyncio
import json
import math
import os
import sys
import time
import urllib.request

import websockets

EARTH_R = 6371000.0
HIGHWAY_EST_KMH = 70.0  # a segment OSRM expects to be driven at this speed or more is a highway


def fetch_route(osrm, start, end):
    """[(lat, lon), ...] and per-segment [(metres, seconds), ...] of OSRM's driving route."""
    url = "%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&annotations=distance,duration" % (
        osrm.rstrip("/"), start[1], start[0], end[1], end[0])
    req = urllib.request.Request(url, headers={"User-Agent": "teslalinux-gps-route"})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.load(r)
    if data.get("code") != "Ok" or not data.get("routes"):
        raise SystemExit("OSRM: no route (%s)" % data.get("code"))
    route = data["routes"][0]
    points = [(lat, lon) for lon, lat in route["geometry"]["coordinates"]]
    segs = []
    for leg in route["legs"]:
        ann = leg["annotation"]
        segs += list(zip(ann["distance"], ann["duration"]))
    return {"points": points, "segments": segs, "distance": route["distance"], "duration": route["duration"]}


def bearing(a, b):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    y = math.sin(lo2 - lo1) * math.cos(la2)
    x = math.cos(la1) * math.sin(la2) - math.sin(la1) * math.cos(la2) * math.cos(lo2 - lo1)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def lerp(a, b, f):
    return (a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)


def fix(pos, mps, heading):
    return json.dumps({
        "latitude": "%.7f" % pos[0],
        "longitude": "%.7f" % pos[1],
        "speed": "%.2f" % mps,
        "bearing": "%.1f" % heading,
        "vertical_accuracy": "5.0",
        "timestamp": str(int(time.time() * 1000)),
    })


async def drive(a, route):
    pts, segs = route["points"], route["segments"]
    total = route["distance"]
    async with websockets.connect(a.url) as ws:
        first_heading = bearing(pts[0], pts[1]) if len(pts) > 1 else 0.0
        for s in range(a.hold):
            await ws.send(fix(pts[0], 0.0, first_heading))
            if s % 10 == 0:
                print("holding at the start: %d s left" % (a.hold - s), flush=True)
            await asyncio.sleep(1)
        seg, into, done, t = 0, 0.0, 0.0, 0
        heading = first_heading
        while seg < len(segs):
            dist, dur = segs[seg]
            est = dist / dur if dur > 0 else 0.0  # OSRM's estimated speed, m/s
            if a.highway_kmh and est * 3.6 >= HIGHWAY_EST_KMH:
                mps = a.highway_kmh / 3.6
            else:
                mps = max(est, a.min_kmh / 3.6)
            mps *= a.speed_factor
            step = mps  # metres this second
            while step > 0 and seg < len(segs):
                dist, dur = segs[seg]
                left = dist - into
                if step < left:
                    into += step
                    done += step
                    step = 0
                else:
                    step -= left
                    done += left
                    seg, into = seg + 1, 0.0
            if seg >= len(segs):
                break
            a_pt, b_pt = pts[seg], pts[seg + 1]
            pos = lerp(a_pt, b_pt, into / segs[seg][0] if segs[seg][0] > 0 else 0.0)
            if segs[seg][0] > 0:
                heading = bearing(a_pt, b_pt)
            await ws.send(fix(pos, mps, heading))
            if t % 10 == 0:
                print("%5d s  %6.2f / %.2f km  %3.0f km/h" % (t, done / 1000, total / 1000, mps * 3.6), flush=True)
            t += 1
            await asyncio.sleep(1)
        end_heading = heading
        for s in range(a.end_hold):
            await ws.send(fix(pts[-1], 0.0, end_heading))
            await asyncio.sleep(1)
        print("arrived after %d s of driving" % t, flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--from", dest="start", default="32.0055,34.8857", help="LAT,LON (default: Ben Gurion Airport T3)")
    ap.add_argument("--to", dest="end", default="32.0778,34.7741", help="LAT,LON (default: Dizengoff Square)")
    ap.add_argument("--hold", type=int, default=60, help="seconds standing at the start")
    ap.add_argument("--end-hold", type=int, default=30, help="seconds standing at the destination")
    ap.add_argument("--highway-kmh", type=float, default=120.0,
                    help="speed on segments OSRM estimates at %d km/h or more (0: OSRM's estimate)" % HIGHWAY_EST_KMH)
    ap.add_argument("--min-kmh", type=float, default=40.0, help="lowest speed on the other segments")
    ap.add_argument("--speed-factor", type=float, default=1.0, help="multiplies every segment's speed")
    ap.add_argument("--route", help="route cache file (JSON)")
    ap.add_argument("--osrm", default="https://router.project-osrm.org")
    ap.add_argument("--url", default="ws://127.0.0.1:9998/")
    a = ap.parse_args()
    start = tuple(float(v) for v in a.start.split(","))
    end = tuple(float(v) for v in a.end.split(","))
    if a.route and os.path.exists(a.route):
        with open(a.route) as f:
            route = json.load(f)
    else:
        route = fetch_route(a.osrm, start, end)
        if a.route:
            with open(a.route, "w") as f:
                json.dump(route, f)
    print("route: %.1f km, about %.0f min at the estimated speeds, %d points" % (
        route["distance"] / 1000, route["duration"] / 60 / a.speed_factor, len(route["points"])), flush=True)
    try:
        asyncio.run(drive(a, route))
    except KeyboardInterrupt:
        sys.exit(130)


if __name__ == "__main__":
    main()
