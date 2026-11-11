#!/usr/bin/env python3
"""teslalinux: record tacomp's display stream around an input, to look at animations frame by frame.

  record-frames.py OUTDIR [--seconds 2.5] [--tap X,Y | --swipe X1,Y1,X2,Y2[,MS] | --pinch CX,CY,D1,D2[,MS]]
                   [--before 0.3]
                   [--host 127.0.0.1] [--display-port 9091] [--touch-port 9999]

Connects to the display WebSocket (renderer 0: one JPEG per message), records --before seconds, sends
the gesture on the touch WebSocket in the upstream touch protocol (one slot, 60 Hz moves for a swipe;
two slots for a pinch: fingers on a horizontal line through CX,CY, from D1 to D2 pixels apart),
and keeps recording until --seconds after it. Writes OUTDIR/NNNN.jpg plus OUTDIR/times.txt
("index milliseconds-since-gesture size"), so dropped or repeated frames are visible as gaps or equal
sizes. Make a contact sheet with: ffmpeg -i OUTDIR/%04d.jpg -vf "scale=368:-1,tile=6x6" sheet.png
"""
import argparse
import asyncio
import os
import time

import websockets


def touch(slot, x, y, down):
    if down:
        return f"s {slot}\nT {slot + 7}\na 1\nX {x}\nY {y}\ne 0\nS 0\n"
    return f"s {slot}\nT -1\na 0\ne 0\nS 0\n"


def move(slot, x, y):
    return f"s {slot}\nX {x}\nY {y}\nS 0\n"


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--seconds", type=float, default=2.5)
    ap.add_argument("--before", type=float, default=0.3)
    ap.add_argument("--tap")
    ap.add_argument("--swipe")
    ap.add_argument("--pinch")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--display-port", type=int, default=9091)
    ap.add_argument("--touch-port", type=int, default=9999)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    frames = []
    t0 = None
    async with websockets.connect(f"ws://{a.host}:{a.display_port}/sockets/display", max_size=None) as disp, \
            websockets.connect(f"ws://{a.host}:{a.touch_port}/sockets/touchscreen") as tch:
        async def gesture():
            nonlocal t0
            await asyncio.sleep(a.before)
            t0 = time.monotonic()
            if a.tap:
                x, y = (int(v) for v in a.tap.split(","))
                await tch.send(touch(3, x, y, True))
                await asyncio.sleep(0.08)
                await tch.send(touch(3, x, y, False))
            elif a.pinch:
                p = [int(v) for v in a.pinch.split(",")]
                cx, cy, d1, d2 = p[:4]
                ms = p[4] if len(p) > 4 else 500
                steps = max(2, ms * 60 // 1000)
                await tch.send(touch(2, cx - d1 // 2, cy, True))
                await asyncio.sleep(0.03)
                await tch.send(touch(5, cx + d1 // 2, cy, True))
                for i in range(1, steps + 1):
                    await asyncio.sleep(ms / 1000 / steps)
                    d = d1 + (d2 - d1) * i // steps
                    await tch.send(move(2, cx - d // 2, cy) + move(5, cx + d // 2, cy))
                await tch.send(touch(5, cx + d2 // 2, cy, False))
                await asyncio.sleep(0.03)
                await tch.send(touch(2, cx - d2 // 2, cy, False))
            elif a.swipe:
                p = [int(v) for v in a.swipe.split(",")]
                x1, y1, x2, y2 = p[:4]
                ms = p[4] if len(p) > 4 else 300
                steps = max(2, ms * 60 // 1000)
                await tch.send(touch(3, x1, y1, True))
                for i in range(1, steps + 1):
                    await asyncio.sleep(ms / 1000 / steps)
                    await tch.send(move(3, x1 + (x2 - x1) * i // steps, y1 + (y2 - y1) * i // steps))
                await tch.send(touch(3, x2, y2, False))
        g = asyncio.create_task(gesture())
        start = time.monotonic()
        while time.monotonic() - start < a.before + a.seconds:
            try:
                msg = await asyncio.wait_for(disp.recv(), timeout=0.5)
            except asyncio.TimeoutError:
                continue
            if isinstance(msg, (bytes, bytearray)):
                frames.append((time.monotonic(), bytes(msg)))
        await g
    with open(os.path.join(a.out, "times.txt"), "w") as f:
        for i, (t, data) in enumerate(frames, 1):
            with open(os.path.join(a.out, "%04d.jpg" % i), "wb") as j:
                j.write(data)
            f.write("%d %.0f %d\n" % (i, (t - (t0 or start)) * 1000, len(data)))
    print(f"{len(frames)} frames in {a.before + a.seconds:.1f} s -> {a.out}")


if __name__ == "__main__":
    asyncio.run(main())
