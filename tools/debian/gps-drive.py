#!/usr/bin/env python3
"""teslalinux: drive a simulated car through the car page's GPS WebSocket (the browser's path).

  gps-drive.py [--url ws://127.0.0.1:9998/] [--start 32.0700,34.7800] [--bearing 0]
               [--speed-kmh 50] [--seconds 90]

Sends one fix per second in the upstream JSON format (docs/UPSTREAM-NOTES.md: every value a string;
"vertical_accuracy" carries the horizontal accuracy), moving along a straight line, the way
web/js/gps/gps-controller.js reports the browser's geolocation. Useful to bring up Waze's
speedometer and driving UI without a phone.
"""
import argparse
import asyncio
import json
import math
import time

import websockets


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="ws://127.0.0.1:9998/")
    ap.add_argument("--start", default="32.0700,34.7800")
    ap.add_argument("--bearing", type=float, default=0.0)
    ap.add_argument("--speed-kmh", type=float, default=50.0)
    ap.add_argument("--seconds", type=int, default=90)
    a = ap.parse_args()
    lat, lon = (float(v) for v in a.start.split(","))
    mps = a.speed_kmh / 3.6
    async with websockets.connect(a.url) as ws:
        for _ in range(a.seconds):
            msg = {
                "latitude": "%.7f" % lat,
                "longitude": "%.7f" % lon,
                "speed": "%.2f" % mps,
                "bearing": "%.1f" % a.bearing,
                "vertical_accuracy": "5.0",
                "timestamp": str(int(time.time() * 1000)),
            }
            await ws.send(json.dumps(msg))
            d = mps / 6371000.0
            br = math.radians(a.bearing)
            lat += math.degrees(d * math.cos(br))
            lon += math.degrees(d * math.sin(br) / math.cos(math.radians(lat)))
            await asyncio.sleep(1)


if __name__ == "__main__":
    asyncio.run(main())
