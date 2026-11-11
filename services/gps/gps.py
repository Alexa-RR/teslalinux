#!/usr/bin/env python3
"""teslalinux GPS bridge.

Browser --wss /sockets/gps (JSON, upstream format)--> this --TCP line feed--> the app's LocationManager

Upstream contract (docs/UPSTREAM-NOTES.md, web/js/gps/gps-controller.js): one JSON text message per
second, every value a string: {"latitude","longitude","speed","bearing","vertical_accuracy","timestamp"}.
The browser's coords.speed and coords.heading are null when the car stands still or the fix has no
course, and the frontend sends String(null), i.e. "null"; "vertical_accuracy" carries coords.accuracy.

Feed (TESLALINUX_GPS_FEED_PORT, 9997): one line per fix,
  lat lon speed_mps bearing_deg accuracy_m timestamp_ms\n
with unknown values written as "NaN". That spelling matters: the reader is Java
(tools/atl-vm/patches/atl-waze/android/location/LocationManager.java, Double.parseDouble), which
accepts "NaN" but throws on Python's default "nan" and would drop the connection on every fix.
The feed binds to loopback by default, because the app (ATL) runs on the same host;
TESLALINUX_GPS_FEED_BIND overrides the address.
"""
import asyncio
import json
import math
import os
import time

import websockets

WS_PORT = int(os.environ.get("TESLALINUX_GPS_WS_PORT", "9998"))
WS_BIND = os.environ.get("TESLALINUX_GPS_WS_BIND", "127.0.0.1")
FEED_PORT = int(os.environ.get("TESLALINUX_GPS_FEED_PORT", "9997"))
FEED_BIND = os.environ.get("TESLALINUX_GPS_FEED_BIND", "127.0.0.1")
STALE_S = 10.0

latest = {"line": None, "at": 0.0}
feed_clients: set = set()


def num(v):
    """A finite float from a frontend string ("12.3", "null", "NaN", missing) or None."""
    if v is None:
        return None
    try:
        x = float(v)
    except (TypeError, ValueError):
        return None
    return x if math.isfinite(x) else None


def fmt(x, digits):
    return "NaN" if x is None else "%.*f" % (digits, x)


def to_line(msg: dict):
    lat, lon = num(msg.get("latitude")), num(msg.get("longitude"))
    if lat is None or lon is None or not (-90.0 <= lat <= 90.0) or not (-180.0 <= lon <= 180.0):
        return None
    speed = num(msg.get("speed"))
    if speed is not None and speed < 0:
        speed = None
    bearing = num(msg.get("bearing"))
    if bearing is not None:
        bearing %= 360.0
    acc = num(msg.get("vertical_accuracy"))
    if acc is not None and acc < 0:
        acc = None
    ts = num(msg.get("timestamp"))
    ts_ms = int(ts) if ts is not None and ts > 0 else int(time.time() * 1000)
    return "%s %s %s %s %s %d\n" % (fmt(lat, 7), fmt(lon, 7), fmt(speed, 2), fmt(bearing, 1), fmt(acc, 1), ts_ms)


def broadcast(line: str):
    data = line.encode()
    for w in list(feed_clients):
        if w.is_closing():
            feed_clients.discard(w)
            continue
        # a reader that stops reading must not stall the browser socket: drop it past 64 KiB queued
        if w.transport.get_write_buffer_size() > 65536:
            w.close()
            feed_clients.discard(w)
            continue
        w.write(data)


async def ws_handler(ws, *_):
    try:
        async for raw in ws:
            if isinstance(raw, bytes):
                continue
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            if not isinstance(msg, dict):
                continue
            line = to_line(msg)
            if line is None:
                continue
            latest["line"], latest["at"] = line, time.monotonic()
            broadcast(line)
    except websockets.ConnectionClosed:
        pass  # the car left the Wi-Fi without a close frame: normal, not an error


async def feed_handler(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
    feed_clients.add(writer)
    try:
        # the last fix right away so the app has a position before the browser's next tick
        if latest["line"] and time.monotonic() - latest["at"] < STALE_S:
            writer.write(latest["line"].encode())
        while await reader.read(256):
            pass
    except (ConnectionError, OSError):
        pass
    finally:
        feed_clients.discard(writer)
        writer.close()


async def main():
    feed = await asyncio.start_server(feed_handler, FEED_BIND, FEED_PORT)
    async with websockets.serve(ws_handler, WS_BIND, WS_PORT, ping_interval=5, max_size=4096):
        async with feed:
            await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
