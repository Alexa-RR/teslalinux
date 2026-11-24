#!/usr/bin/env python3
"""teslalinux: pan the map the way the car does and time every frame the car would get.

  pan-bench.py OUTDIR [--seconds 180] [--from 300,420] [--to 800,420] [--drag-ms 500]
               [--pause-ms 900] [--hold-ms 120] [--oneway] [--host 127.0.0.1] [--label NAME]

Runs on the Pi (python3-websockets). One finger drags on tacomp's touch socket (upstream touch
protocol, docs/UPSTREAM-NOTES.md) from --from to --to in --drag-ms with a move every 16.7 ms (a
browser's touchmove rate), stays --hold-ms, lifts, waits --pause-ms and drags back, again and
again for --seconds (--oneway: always from --from to --to, so every drag brings new map in, as
exploring the map does; back and forth stays over map already loaded). A display client takes every frame tacomp sends (what a car page gets) and
notes its arrival time, size and whether it is a key frame. Every 0.5 s the SoC temperature and
the CPU and V3D clocks are read.

"Active" is from a touch-down to 1 s after its lift (the map pans or glides). For the frame
intervals while active it prints one JSON line: p50, p95, p99, max (ms), how many are over 50 and
100 ms, frames a second; and writes OUTDIR/frames.tsv (wall-clock and monotonic time, interval, size, key frame,
active), OUTDIR/touch.tsv (each down/up) and OUTDIR/stalls.tsv (every interval over 50 ms with
its wall-clock time, for lining up with the journal: journalctl -o short-precise).
"""
import argparse
import asyncio
import json
import os
import time

import websockets


def down(slot, x, y):
    return f"s {slot}\nT {slot + 7}\na 1\nX {x}\nY {y}\ne 0\nS 0\n"


def up(slot):
    return f"s {slot}\nT -1\na 0\ne 0\nS 0\n"


def move(slot, x, y):
    return f"s {slot}\nX {x}\nY {y}\nS 0\n"


def read(path, scale=1.0):
    try:
        with open(path) as f:
            return int(f.read().split()[0]) * scale
    except (OSError, ValueError, IndexError):
        return None


def key_frame(m):
    """an H.264 access unit with an IDR slice (NAL type 5); JPEG frames are all key frames"""
    if m[:2] == b"\xff\xd8":
        return True
    i = m.find(b"\x00\x00\x01")
    while i >= 0 and i + 3 < len(m):
        if m[i + 3] & 0x1f == 5:
            return True
        i = m.find(b"\x00\x00\x01", i + 3)
    return False


def pct(v, p):
    if not v:
        return None
    s = sorted(v)
    return round(s[min(len(s) - 1, int(len(s) * p / 100))], 1)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--seconds", type=float, default=180)
    ap.add_argument("--from", dest="p1", default="300,420")
    ap.add_argument("--to", dest="p2", default="800,420")
    ap.add_argument("--drag-ms", type=int, default=500)
    ap.add_argument("--hold-ms", type=int, default=120)
    ap.add_argument("--pause-ms", type=int, default=900)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--display-port", type=int, default=9091)
    ap.add_argument("--touch-port", type=int, default=9999)
    ap.add_argument("--oneway", action="store_true")
    ap.add_argument("--label", default="")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    x1, y1 = (int(v) for v in a.p1.split(","))
    x2, y2 = (int(v) for v in a.p2.split(","))
    frames = []   # (wall, mono, size, key)
    touches = []  # (wall, mono, "down"/"up")
    samples = []  # (wall, temp C, arm MHz, v3d MHz)
    done = False

    async with websockets.connect(f"ws://{a.host}:{a.display_port}/sockets/display", max_size=None) as disp, \
            websockets.connect(f"ws://{a.host}:{a.touch_port}/sockets/touchscreen") as tch:

        async def receive():
            while not done:
                try:
                    m = await asyncio.wait_for(disp.recv(), 0.5)
                except asyncio.TimeoutError:
                    continue
                if isinstance(m, (bytes, bytearray)):
                    frames.append((time.time(), time.monotonic(), len(m), key_frame(m)))

        async def sample():
            while not done:
                samples.append((time.time(), read("/sys/class/thermal/thermal_zone0/temp", 0.001),
                                read("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", 0.001),
                                read("/sys/kernel/debug/clk/v3d/clk_rate", 1e-6)))
                await asyncio.sleep(0.5)

        rx = asyncio.create_task(receive())
        sm = asyncio.create_task(sample())
        await asyncio.sleep(2)  # the stream starts (key frame), the map settles
        t_end = time.monotonic() + a.seconds
        steps = max(2, a.drag_ms * 60 // 1000)
        forward = True
        while time.monotonic() < t_end:
            sx, sy, ex, ey = (x1, y1, x2, y2) if forward else (x2, y2, x1, y1)
            t0 = time.monotonic()
            await tch.send(down(3, sx, sy))
            touches.append((time.time(), time.monotonic(), "down"))
            for i in range(1, steps + 1):
                # paced from the gesture's start, so a late wake-up does not stretch the drag
                await asyncio.sleep(max(0.0, t0 + a.drag_ms / 1000 * i / steps - time.monotonic()))
                await tch.send(move(3, sx + (ex - sx) * i // steps, sy + (ey - sy) * i // steps))
            await asyncio.sleep(a.hold_ms / 1000)
            await tch.send(up(3))
            touches.append((time.time(), time.monotonic(), "up"))
            await asyncio.sleep(a.pause_ms / 1000)
            if not a.oneway:
                forward = not forward
        await asyncio.sleep(1.2)
        done = True
        await rx
        await sm

    # active windows: touch-down to 1 s after the lift
    wins = []
    for (_, m, k) in touches:
        if k == "down":
            wins.append([m, None])
        elif wins and wins[-1][1] is None:
            wins[-1][1] = m + 1.0

    def active(m):
        return any(s <= m <= (e if e is not None else m) for s, e in wins)

    iv, rows, stalls = [], [], []
    for i in range(1, len(frames)):
        w, m, size, key = frames[i]
        d = (m - frames[i - 1][1]) * 1000
        act = active(m) and active(frames[i - 1][1])
        rows.append(f"{w:.3f}\t{m:.4f}\t{d:.1f}\t{size}\t{int(key)}\t{int(act)}")
        if act:
            iv.append(d)
            if d > 50:
                stalls.append(f"{w:.3f}\t{m:.4f}\t{time.strftime('%H:%M:%S', time.localtime(w))}.{int(w * 1000) % 1000:03d}"
                              f"\t{d:.1f}\t{size}\t{int(key)}")
    with open(os.path.join(a.out, "frames.tsv"), "w") as f:
        f.write("wall\tmono\tinterval_ms\tbytes\tkey\tactive\n" + "\n".join(rows) + "\n")
    with open(os.path.join(a.out, "touch.tsv"), "w") as f:
        f.write("".join(f"{w:.3f}\t{m:.4f}\t{k}\n" for w, m, k in touches))
    with open(os.path.join(a.out, "stalls.tsv"), "w") as f:
        f.write("wall\tmono\tlocal\tinterval_ms\tbytes\tkey\n" + "\n".join(stalls) + "\n")
    active_s = sum((e or s) - s for s, e in wins)
    temps = [t for _, t, _, _ in samples if t]
    arms = [c for _, _, c, _ in samples if c]
    v3ds = [c for _, _, _, c in samples if c]
    print(json.dumps({
        "label": a.label, "frames": len(iv), "active_s": round(active_s, 1),
        "fps": round(len(iv) / active_s, 1) if active_s else None,
        "p50": pct(iv, 50), "p95": pct(iv, 95), "p99": pct(iv, 99), "max": round(max(iv), 1) if iv else None,
        "over50": sum(1 for d in iv if d > 50), "over100": sum(1 for d in iv if d > 100),
        "key_frames": sum(1 for f in frames if f[3]), "drags": sum(1 for t in touches if t[2] == "down"),
        "temp_max": round(max(temps), 1) if temps else None,
        "arm_mhz_min": round(min(arms)) if arms else None, "v3d_mhz_min": round(min(v3ds)) if v3ds else None,
    }))


if __name__ == "__main__":
    asyncio.run(main())
