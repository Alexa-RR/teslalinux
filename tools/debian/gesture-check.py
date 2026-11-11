#!/usr/bin/env python3
"""teslalinux: send a gesture on the touch socket and keep the display stream around it, in either
renderer (one JPEG per message, or H.264 Annex-B access units).

  gesture-check.py OUTDIR (--tap X,Y | --dtap X,Y[,GAPMS] | --swipe X1,Y1,X2,Y2[,MS] | --pinch CX,CY,D1,D2[,MS])
                   [--before 0.5] [--after 1.5] [--host 127.0.0.1]

Writes OUTDIR/before.png (the last frame before the gesture) and OUTDIR/after.png (the last frame
--after seconds after it), plus OUTDIR/stream.264 or OUTDIR/NNNN.jpg. H.264 is decoded with ffmpeg.
Touch messages use the upstream protocol (docs/UPSTREAM-NOTES.md); a pinch uses two slots on a
horizontal line through CX,CY, from D1 to D2 pixels apart.
"""
import argparse
import asyncio
import os
import subprocess
import time

import websockets


def down(slot, x, y):
    return f"s {slot}\nT {slot + 7}\na 1\nX {x}\nY {y}\ne 0\nS 0\n"


def up(slot):
    return f"s {slot}\nT -1\na 0\ne 0\nS 0\n"


def move(slot, x, y):
    return f"s {slot}\nX {x}\nY {y}\nS 0\n"


async def gesture(tch, a):
    if a.tap:
        x, y = (int(v) for v in a.tap.split(","))
        await tch.send(down(3, x, y))
        await asyncio.sleep(0.08)
        await tch.send(up(3))
    elif a.dtap:
        p = [int(v) for v in a.dtap.split(",")]
        x, y = p[:2]
        gap = p[2] if len(p) > 2 else 120
        for n in range(2):
            await tch.send(down(3, x, y))
            await asyncio.sleep(0.06)
            await tch.send(up(3))
            if n == 0:
                await asyncio.sleep(gap / 1000)
    elif a.swipe:
        p = [int(v) for v in a.swipe.split(",")]
        x1, y1, x2, y2 = p[:4]
        ms = p[4] if len(p) > 4 else 400
        steps = max(2, ms * 60 // 1000)
        await tch.send(down(3, x1, y1))
        for i in range(1, steps + 1):
            await asyncio.sleep(ms / 1000 / steps)
            await tch.send(move(3, x1 + (x2 - x1) * i // steps, y1 + (y2 - y1) * i // steps))
        await asyncio.sleep(0.05)
        await tch.send(up(3))
    elif a.pinch:
        p = [int(v) for v in a.pinch.split(",")]
        cx, cy, d1, d2 = p[:4]
        ms = p[4] if len(p) > 4 else 600
        steps = max(2, ms * 60 // 1000)
        await tch.send(down(2, cx - d1 // 2, cy))
        await asyncio.sleep(0.03)
        await tch.send(down(5, cx + d1 // 2, cy))
        for i in range(1, steps + 1):
            await asyncio.sleep(ms / 1000 / steps)
            d = d1 + (d2 - d1) * i // steps
            await tch.send(move(2, cx - d // 2, cy) + move(5, cx + d // 2, cy))
        await asyncio.sleep(0.05)
        await tch.send(up(5))
        await asyncio.sleep(0.03)
        await tch.send(up(2))


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--tap")
    ap.add_argument("--dtap")
    ap.add_argument("--swipe")
    ap.add_argument("--pinch")
    ap.add_argument("--before", type=float, default=0.5)
    ap.add_argument("--after", type=float, default=1.5)
    ap.add_argument("--host", default="127.0.0.1")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    msgs = []  # (time, bytes)
    async with websockets.connect(f"ws://{a.host}:9091/sockets/display", max_size=None) as disp, \
            websockets.connect(f"ws://{a.host}:9999/sockets/touchscreen") as tch:
        t_start = time.monotonic()
        t_gesture = t_done = None

        async def run():
            nonlocal t_gesture, t_done
            await asyncio.sleep(a.before)
            t_gesture = time.monotonic()
            await gesture(tch, a)
            t_done = time.monotonic()

        g = asyncio.create_task(run())
        while not t_done or time.monotonic() - t_done < a.after:
            try:
                m = await asyncio.wait_for(disp.recv(), 0.5)
            except asyncio.TimeoutError:
                continue
            if isinstance(m, (bytes, bytearray)):
                msgs.append((time.monotonic(), bytes(m)))
            if time.monotonic() - t_start > 30:
                break
        await g
    h264 = any(m[:4] == b"\x00\x00\x00\x01" or m[:3] == b"\x00\x00\x01" for _, m in msgs[:3])
    n_before = sum(1 for t, _ in msgs if t < t_gesture)
    if h264:
        path = os.path.join(a.out, "stream.264")
        with open(path, "wb") as f:
            for _, m in msgs:
                f.write(m)
        # decoded frame i corresponds to message i (one access unit per message, starting at an IDR)
        for name, idx in (("before", max(0, n_before - 1)), ("after", len(msgs) - 1)):
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", path, "-vf", f"select=eq(n\\,{idx})",
                            "-frames:v", "1", "-update", "1", os.path.join(a.out, name + ".png")], check=False)
    else:
        for i, (_, m) in enumerate(msgs, 1):
            with open(os.path.join(a.out, "%04d.jpg" % i), "wb") as f:
                f.write(m)
        for name, idx in (("before", max(0, n_before - 1)), ("after", len(msgs) - 1)):
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", os.path.join(a.out, "%04d.jpg" % (idx + 1)),
                            os.path.join(a.out, name + ".png")], check=False)
    print(f"{len(msgs)} frames ({'H.264' if h264 else 'JPEG'}), {n_before} before the gesture -> {a.out}/before.png, after.png")


if __name__ == "__main__":
    asyncio.run(main())
