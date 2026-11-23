#!/usr/bin/env python3
"""teslalinux: how many frames a Pi delivers while the map moves, with its temperature and clocks.

  pi-bench.py [--label NAME] [--seconds 60] [--warmup 15] [--speed-kmh 50] [--start LAT,LON]
              [--bearing 0] [--no-drive] [--host 127.0.0.1] [--display-port 9091] [--gps-port 9998]
              [--procs NAME,...] [--gpu] [--per-second]

Runs on the Pi (python3-websockets, vcgencmd). A simulated car drives in a straight line through the
GPS WebSocket (gps-drive.py's path, so the map keeps moving), a display client counts tacomp's frames
(tacomp sends a frame only when the screen changed, so this is what a car page gets), and every
2 s the SoC temperature, throttle flags, CPU and GPU clocks and the CPU load are sampled. After
--warmup seconds, --seconds are measured; one JSON line is printed:
  fps (mean), fps_p10 (the slowest tenth of seconds), temp_max, throttled (OR of the flags seen),
  arm_mhz / v3d_mhz (lowest seen), cpu_busy (%), display (TACOMP_SIZE and encoder).
A car page (or another display client) must keep Waze's map shown in Car mode; the benchmark only
reads the stream. --no-drive leaves the GPS to something else: a straight line leaves the roads, and
Waze then stops following the car, so for comparable runs drive a real route with gps-route.py
(a cached --route file, the same stretch every run) and pass --no-drive.
--procs: the CPU share (% of one core) of the processes with these names (/proc/PID/comm, a prefix
is enough: android-transla, tacomp) over the measured seconds, as "cpu": {name: %}. --gpu (as root,
to read other users' DRM fdinfo): each of those processes' share of V3D's engines (bin, render)
over the measured seconds, as "gpu": {name: {engine: %}} (a job counts from its start to its end,
so jobs that overlap add up to more than 100%). --per-second: the frames of every measured second.
"""
import argparse
import asyncio
import json
import math
import os
import subprocess
import time

import websockets


def vc(*args):
    try:
        return subprocess.run(["vcgencmd", *args], capture_output=True, text=True, timeout=5).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def cpu_times():
    with open("/proc/stat") as f:
        v = [int(x) for x in f.readline().split()[1:]]
    idle = v[3] + v[4]
    return sum(v), idle


def display_env():
    out = {}
    try:
        with open("/etc/teslalinux/display.env") as f:
            for line in f:
                if "=" in line and not line.startswith("#"):
                    k, v = line.strip().split("=", 1)
                    out[k] = v
    except OSError:
        pass
    return out


async def drive(url, start, bearing, kmh, seconds):
    lat, lon = start
    mps = kmh / 3.6
    async with websockets.connect(url) as ws:
        for _ in range(seconds):
            await ws.send(json.dumps({
                "latitude": "%.7f" % lat, "longitude": "%.7f" % lon, "speed": "%.2f" % mps,
                "bearing": "%.1f" % bearing, "vertical_accuracy": "5.0",
                "timestamp": str(int(time.time() * 1000)),
            }))
            d = mps / 6371000.0
            br = math.radians(bearing)
            lat += math.degrees(d * math.cos(br))
            lon += math.degrees(d * math.sin(br) / math.cos(math.radians(lat)))
            await asyncio.sleep(1)


def pids_named(prefixes):
    """{prefix: [pid, ...]} of the running processes whose comm or program name (argv[0]'s base name)
    starts with each prefix (ATL's process renames its main thread, so its comm is "main")"""
    out = {p: [] for p in prefixes}
    for d in os.listdir("/proc"):
        if not d.isdigit():
            continue
        try:
            with open("/proc/%s/comm" % d) as f:
                comm = f.read().strip()
            with open("/proc/%s/cmdline" % d, "rb") as f:
                prog = os.path.basename(f.read().split(b"\0", 1)[0].decode("utf-8", "replace"))
        except OSError:
            continue
        for p in prefixes:
            if comm.startswith(p) or (prog and prog.startswith(p)):
                out[p].append(int(d))
    return out


def proc_cpu_ticks(pids):
    total = 0
    for pid in pids:
        try:
            with open("/proc/%d/stat" % pid) as f:
                fields = f.read().rsplit(")", 1)[1].split()
            total += int(fields[11]) + int(fields[12])  # utime, stime
        except (OSError, IndexError, ValueError):
            pass
    return total


def drm_engine_ns(pids):
    """{engine: ns busy} summed over the DRM clients (one per context) the processes hold open; a
    client opened through several fds counts once"""
    out, seen = {}, set()
    for pid in pids:
        try:
            fds = os.listdir("/proc/%d/fdinfo" % pid)
        except OSError:
            continue
        for fd in fds:
            try:
                with open("/proc/%d/fdinfo/%s" % (pid, fd)) as f:
                    text = f.read()
            except OSError:
                continue
            if "drm-engine-" not in text:
                continue
            client = None
            engines = {}
            for line in text.splitlines():
                if line.startswith("drm-client-id:"):
                    client = line.split()[1]
                elif line.startswith("drm-engine-"):
                    k, v = line.split(":", 1)
                    engines[k[len("drm-engine-"):]] = int(v.split()[0])
            if client in seen:
                continue
            seen.add(client)
            for k, v in engines.items():
                out[k] = out.get(k, 0) + v
    return out


async def count_frames(url, stamps, stop):
    async with websockets.connect(url, max_size=None) as ws:
        while not stop.is_set():
            try:
                msg = await asyncio.wait_for(ws.recv(), 1.0)
            except asyncio.TimeoutError:
                continue
            if isinstance(msg, (bytes, bytearray)) and msg:
                stamps.append(time.monotonic())


async def sample(samples, stop):
    prev = cpu_times()
    while not stop.is_set():
        await asyncio.sleep(2)
        now = cpu_times()
        total, idle = now[0] - prev[0], now[1] - prev[1]
        prev = now
        temp = vc("measure_temp").replace("temp=", "").replace("'C", "")
        thr = vc("get_throttled").replace("throttled=", "")
        arm = vc("measure_clock", "arm").split("=")[-1]
        v3d = vc("measure_clock", "v3d").split("=")[-1]
        samples.append({
            "t": time.monotonic(),
            "temp": float(temp) if temp else None,
            "throttled": int(thr, 16) if thr else 0,
            "arm_mhz": round(int(arm) / 1e6) if arm.isdigit() else None,
            "v3d_mhz": round(int(v3d) / 1e6) if v3d.isdigit() else None,
            "cpu_busy": round(100.0 * (total - idle) / total, 1) if total else None,
        })


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", default="")
    ap.add_argument("--seconds", type=int, default=60)
    ap.add_argument("--warmup", type=int, default=15)
    ap.add_argument("--speed-kmh", type=float, default=50.0)
    ap.add_argument("--start", default="32.0700,34.7800")
    ap.add_argument("--bearing", type=float, default=0.0)
    ap.add_argument("--no-drive", action="store_true", help="send no GPS (gps-route.py drives)")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--display-port", type=int, default=9091)
    ap.add_argument("--gps-port", type=int, default=9998)
    ap.add_argument("--procs", default="", help="comma-separated process name prefixes: their CPU share")
    ap.add_argument("--gpu", action="store_true", help="and their V3D share (DRM fdinfo; root)")
    ap.add_argument("--per-second", action="store_true", help="print every measured second's frames")
    a = ap.parse_args()
    procs = [p for p in a.procs.split(",") if p]
    start = tuple(float(v) for v in a.start.split(","))
    stamps, samples, stop = [], [], asyncio.Event()
    total = a.warmup + a.seconds
    tasks = [] if a.no_drive else [
        asyncio.create_task(drive(f"ws://{a.host}:{a.gps_port}/", start, a.bearing, a.speed_kmh, total + 2))]
    tasks += [
        asyncio.create_task(count_frames(f"ws://{a.host}:{a.display_port}/sockets/display", stamps, stop)),
        asyncio.create_task(sample(samples, stop)),
    ]
    t0 = time.monotonic()
    await asyncio.sleep(a.warmup)
    # the processes' CPU and GPU time over the measured seconds
    pids = pids_named(procs)
    hz = os.sysconf("SC_CLK_TCK")
    cpu0 = {p: proc_cpu_ticks(v) for p, v in pids.items()}
    gpu0 = {p: drm_engine_ns(v) for p, v in pids.items()} if a.gpu else {}
    w0 = time.monotonic()
    await asyncio.sleep(a.seconds)
    w1 = time.monotonic()
    cpu = {p: round(100.0 * (proc_cpu_ticks(v) - cpu0[p]) / hz / (w1 - w0), 1) for p, v in pids.items()}
    gpu = {}
    for p, v in pids.items() if a.gpu else ():
        now = drm_engine_ns(v)
        gpu[p] = {k: round(100.0 * (now[k] - gpu0[p].get(k, 0)) / 1e9 / (w1 - w0), 1)
                  for k in now if now[k] - gpu0[p].get(k, 0) > 0}
    stop.set()
    await asyncio.sleep(2.5)
    for t in tasks:
        t.cancel()
    m0, m1 = t0 + a.warmup, t0 + total
    per_second = [0] * a.seconds
    for s in stamps:
        if m0 <= s < m1:
            per_second[int(s - m0)] += 1
    window = [s for s in samples if m0 <= s["t"] <= m1 + 2]
    flags = 0
    for s in samples:
        flags |= s["throttled"]
    ordered = sorted(per_second)

    def low(key):
        vals = [s[key] for s in window if s[key] is not None]
        return min(vals) if vals else None

    temps = [s["temp"] for s in window if s["temp"] is not None]
    busy = [s["cpu_busy"] for s in window if s["cpu_busy"] is not None]
    env = display_env()
    out = {
        "label": a.label,
        "display": "%s %s" % (env.get("TACOMP_SIZE", "?"), env.get("TACOMP_ENCODER", "?")),
        "fps": round(sum(per_second) / a.seconds, 1),
        "fps_p10": ordered[len(ordered) // 10] if ordered else 0,
        "temp_max": max(temps) if temps else None,
        "throttled": "0x%x" % flags,
        "arm_mhz": low("arm_mhz"),
        "v3d_mhz": low("v3d_mhz"),
        "cpu_busy": round(sum(busy) / len(busy), 1) if busy else None,
    }
    if procs:
        out["cpu"] = cpu
    if a.gpu:
        out["gpu"] = gpu
    if a.per_second:
        out["per_second"] = per_second
    print(json.dumps(out))


if __name__ == "__main__":
    asyncio.run(main())
