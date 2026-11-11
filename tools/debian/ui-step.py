#!/usr/bin/env python3
"""teslalinux: drive the app on the car display and keep a picture of the result, one step at a time.

  ui-step.py OUT.png STEP [STEP ...]     (run in the VM or on the Pi; display and keyboard on loopback)

STEP is one of
  tap:X,Y            a tap (display coordinates)
  long:X,Y           a long press (700 ms)
  swipe:X1,Y1,X2,Y2[,MS]
  pinch:CX,CY,D1,D2[,MS]
  type:TEXT          text into the focused field (Settings > Keyboard set to "device": tacomp's keyboard socket)
  key:enter|backspace|tab|escape
  wait:SECONDS
OUT.png is the last display frame after the steps, scaled to 864 pixels wide (JPEG or H.264 stream,
decoded with ffmpeg). Touch messages are the upstream protocol (docs/UPSTREAM-NOTES.md), one report
per change as the car page sends them.
"""
import asyncio
import os
import subprocess
import sys
import tempfile

import websockets

HOST = os.environ.get("TESLALINUX_UI_HOST", "127.0.0.1")


def down(slot, x, y):
    return f"s {slot}\nT {slot + 7}\na 1\nX {x}\nY {y}\ne 0\nS 0\n"


def up(slot):
    return f"s {slot}\nT -1\na 0\ne 0\nS 0\n"


def move(slot, x, y):
    return f"s {slot}\nX {x}\nY {y}\ne 0\nS 0\n"


async def frames(stop, out):
    """the newest display message, kept until stop is set"""
    async with websockets.connect(f"ws://{HOST}:9091/", max_size=None) as ws:
        while not stop.is_set():
            try:
                msg = await asyncio.wait_for(ws.recv(), 0.2)
            except asyncio.TimeoutError:
                continue
            if isinstance(msg, bytes):
                out.append(msg)
                if len(out) > 240:
                    del out[:-240]


async def run_steps(steps):
    tch = await websockets.connect(f"ws://{HOST}:9999/")
    kbd = None
    for step in steps:
        kind, _, arg = step.partition(":")
        if kind in ("tap", "long"):
            x, y = (int(v) for v in arg.split(","))
            await tch.send(down(3, x, y))
            await asyncio.sleep(0.7 if kind == "long" else 0.08)
            await tch.send(up(3))
        elif kind == "swipe":
            p = [int(v) for v in arg.split(",")]
            x1, y1, x2, y2 = p[:4]
            ms = p[4] if len(p) > 4 else 400
            n = max(2, ms * 60 // 1000)
            await tch.send(down(3, x1, y1))
            for i in range(1, n + 1):
                await asyncio.sleep(ms / 1000 / n)
                await tch.send(move(3, x1 + (x2 - x1) * i // n, y1 + (y2 - y1) * i // n))
            await asyncio.sleep(0.05)
            await tch.send(up(3))
        elif kind == "pinch":
            p = [int(v) for v in arg.split(",")]
            cx, cy, d1, d2 = p[:4]
            ms = p[4] if len(p) > 4 else 600
            n = max(2, ms * 60 // 1000)
            await tch.send(down(2, cx - d1 // 2, cy))
            await asyncio.sleep(0.03)
            await tch.send(down(5, cx + d1 // 2, cy))
            for i in range(1, n + 1):
                await asyncio.sleep(ms / 1000 / n)
                d = d1 + (d2 - d1) * i // n
                await tch.send(move(2, cx - d // 2, cy))
                await tch.send(move(5, cx + d // 2, cy))
            await asyncio.sleep(0.05)
            await tch.send(up(5))
            await asyncio.sleep(0.03)
            await tch.send(up(2))
        elif kind in ("type", "key"):
            if kbd is None:
                kbd = await websockets.connect(f"ws://{HOST}:9996/")
            await kbd.send(("t " + arg if kind == "type" else "k " + arg) + "\n")
            await asyncio.sleep(0.3)
        elif kind == "wait":
            await asyncio.sleep(float(arg))
        else:
            raise SystemExit("ui-step: unknown step " + step)
    await tch.close()
    if kbd is not None:
        await kbd.close()


async def main():
    out_png, steps = sys.argv[1], sys.argv[2:]
    got = []
    stop = asyncio.Event()
    reader = asyncio.create_task(frames(stop, got))
    await asyncio.sleep(0.4)
    await run_steps(steps)
    await asyncio.sleep(0.6)
    stop.set()
    await reader
    if not got:
        raise SystemExit("ui-step: no display frame")
    with tempfile.TemporaryDirectory() as d:
        last = got[-1]
        if last[:2] == b"\xff\xd8":
            src = os.path.join(d, "f.jpg")
            open(src, "wb").write(last)
        else:  # H.264 access units: decode the whole stream, keep the last picture
            src = os.path.join(d, "s.264")
            with open(src, "wb") as f:
                for m in got:
                    f.write(m)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src, "-vf", "scale=864:-1", "-update", "1", out_png], check=True)


asyncio.run(main())
