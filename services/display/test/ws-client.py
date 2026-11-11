#!/usr/bin/env python3
"""tacomp test client: receive display frames, send touches, save frames to files.

  ws-client.py [--host 127.0.0.1] [--display-port 9091] [--touch-port 9999] [--out /tmp/tacomp]
               [--tap X,Y ...] [--frames N] [--wait S]

Receives frames from /sockets/display (one JPEG per binary message), writes frame-<n>.jpg, sends
each --tap as a down/up pair in the upstream touch protocol (slot 3) and writes the frame that
follows it. Exit code 1 when no frame arrived.
"""
import argparse, asyncio, os, sys, time
import websockets

TOUCH_DOWN = "s 3\nT 7\na 1\nX {x}\nY {y}\ne 0\nS 0\n"
TOUCH_UP = "s 3\nT -1\na 0\ne 0\nS 0\n"


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--display-port", type=int, default=9091)
    ap.add_argument("--touch-port", type=int, default=9999)
    ap.add_argument("--out", default="/tmp/tacomp")
    ap.add_argument("--tap", action="append", default=[])
    ap.add_argument("--frames", type=int, default=2, help="frames to collect before/after the taps")
    ap.add_argument("--wait", type=float, default=2.0, help="seconds after a tap before saving a frame")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    disp = f"ws://{args.host}:{args.display_port}/sockets/display"
    touch = f"ws://{args.host}:{args.touch_port}/sockets/touchscreen"
    n = 0
    sizes = []
    async with websockets.connect(disp, max_size=None) as d:
        t0 = time.time()
        for _ in range(args.frames):
            msg = await asyncio.wait_for(d.recv(), timeout=10)
            n += 1
            sizes.append(len(msg))
            open(os.path.join(args.out, f"frame-{n}.jpg"), "wb").write(msg)
        print(f"{n} frames in {time.time()-t0:.2f}s, sizes {sizes}", flush=True)
        if args.tap:
            async with websockets.connect(touch) as t:
                for tap in args.tap:
                    x, y = (int(v) for v in tap.split(","))
                    await t.send(TOUCH_DOWN.format(x=x, y=y))
                    await asyncio.sleep(0.08)
                    await t.send(TOUCH_UP)
                    await asyncio.sleep(args.wait)
                    # the newest frame: drain for half a second (frames stream continuously)
                    msg = None
                    t1 = time.time()
                    try:
                        while time.time() - t1 < 0.5:
                            msg = await asyncio.wait_for(d.recv(), timeout=0.5)
                    except asyncio.TimeoutError:
                        pass
                    if msg is None:
                        msg = await asyncio.wait_for(d.recv(), timeout=10)
                    n += 1
                    open(os.path.join(args.out, f"frame-{n}.jpg"), "wb").write(msg)
                    print(f"tap {x},{y} -> frame-{n}.jpg ({len(msg)} bytes)", flush=True)
    # frame rate over 3 s
    async with websockets.connect(disp, max_size=None) as d:
        t0 = time.time(); k = 0
        try:
            while time.time() - t0 < 3:
                await asyncio.wait_for(d.recv(), timeout=3); k += 1
        except asyncio.TimeoutError:
            pass
        print(f"rate: {k/3:.1f} frames/s", flush=True)
    return 0 if n else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
