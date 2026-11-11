#!/usr/bin/env python3
"""teslalinux: drive the car page in a headless Chrome on the Mac over the DevTools protocol, the way
the Tesla browser uses it (the car's hostname mapped to the VM's forwarded port, so SNI and Host stay
car.alexahub.dev), for screenshots and taps without a person at the keyboard.

  car-browser.py [--port 8443] [--host car.alexahub.dev] [--size 1280x900] [--chrome PATH] ACTION...

Actions, run in order:
  goto:URL            open URL (default first action: https://HOST/)
  wait:SECONDS        sleep
  waitfor:JS          poll a JS expression until it is truthy (60 s)
  click:SELECTOR      a mouse click in the middle of the element (pointer events, as a finger)
  clicktext:TEXT      the same on the innermost visible element whose text is exactly TEXT
  tap:X,Y             a tap at app display coordinates (X,Y in the compositor's pixels, scaled onto
                      #touch-layer's box with the display size from --display, default 1088x832)
  shot:FILE.png       a screenshot of the page
  eval:JS             print the value of a JS expression
  geo:LAT,LON         the browser's geolocation (permission granted for the page's origin)
Needs the Python websockets module (host/local/web-local.sh's venv has it: out/web-local/venv/bin/python).
"""
import argparse
import asyncio
import base64
import itertools
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

import websockets

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"


class Cdp:
    def __init__(self, ws):
        self.ws, self.ids, self.waiting = ws, itertools.count(1), {}

    async def pump(self):
        async for msg in self.ws:
            m = json.loads(msg)
            fut = self.waiting.pop(m.get("id"), None)
            if fut and not fut.done():
                fut.set_result(m)

    async def call(self, method, **params):
        i = next(self.ids)
        fut = asyncio.get_running_loop().create_future()
        self.waiting[i] = fut
        await self.ws.send(json.dumps({"id": i, "method": method, "params": params}))
        m = await asyncio.wait_for(fut, 60)
        if "error" in m:
            raise RuntimeError(f"{method}: {m['error']}")
        return m.get("result", {})

    async def js(self, expr):
        r = await self.call("Runtime.evaluate", expression=expr, returnByValue=True, awaitPromise=True)
        if "exceptionDetails" in r:
            raise RuntimeError(f"JS {expr!r}: {r['exceptionDetails'].get('text')}")
        return r["result"].get("value")


async def run(a):
    prof = tempfile.mkdtemp(prefix="tl-chrome-")
    w, h = (int(v) for v in a.size.split("x"))
    rules = f"MAP {a.host}:443 127.0.0.1:{a.port}, MAP *.{a.host}:443 127.0.0.1:{a.port}"
    proc = subprocess.Popen([a.chrome, "--headless=new", "--remote-debugging-port=0", f"--user-data-dir={prof}",
                             f"--host-resolver-rules={rules}", "--ignore-certificate-errors", "--no-first-run",
                             "--autoplay-policy=no-user-gesture-required", f"--window-size={w},{h}", "about:blank"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        port_file = os.path.join(prof, "DevToolsActivePort")
        for _ in range(100):
            if os.path.exists(port_file) and open(port_file).read().strip():
                break
            time.sleep(0.1)
        dport = open(port_file).read().split()[0]
        targets = json.load(urllib.request.urlopen(f"http://127.0.0.1:{dport}/json"))
        page = next(t for t in targets if t["type"] == "page")
        async with websockets.connect(page["webSocketDebuggerUrl"], max_size=64 << 20) as ws:
            cdp = Cdp(ws)
            pump = asyncio.create_task(cdp.pump())
            await cdp.call("Page.enable")
            await cdp.call("Emulation.setDeviceMetricsOverride", width=w, height=h, deviceScaleFactor=1, mobile=False)
            dw, dh = (int(v) for v in a.display.split("x"))
            actions = a.actions or []
            if not actions or not actions[0].startswith("goto:"):
                actions.insert(0, f"goto:https://{a.host}/")
            for act in actions:
                kind, _, arg = act.partition(":")
                t0 = time.time()
                if kind == "goto":
                    await cdp.call("Page.navigate", url=arg)
                    await cdp.js("new Promise(r => { if (document.readyState === 'complete') r(1); else addEventListener('load', () => r(1)); })")
                elif kind == "wait":
                    await asyncio.sleep(float(arg))
                elif kind == "waitfor":
                    while not await cdp.js(arg):
                        if time.time() - t0 > 60:
                            raise RuntimeError(f"waitfor timed out: {arg}")
                        await asyncio.sleep(0.5)
                elif kind in ("click", "clicktext", "tap"):
                    if kind == "clicktext":
                        r = await cdp.js(f"""(() => {{
                            const want = {json.dumps(arg)};
                            const hits = [...document.querySelectorAll('body *')].filter(e => {{
                                const b = e.getBoundingClientRect();
                                return b.width > 0 && b.height > 0 && e.innerText && e.innerText.trim() === want;
                            }});
                            const e = hits.find(h => !hits.some(o => o !== h && h.contains(o))) || hits[0];
                            if (!e) return null;
                            const b = e.getBoundingClientRect();
                            return [b.x + b.width / 2, b.y + b.height / 2];
                        }})()""")
                        if not r:
                            raise RuntimeError(f"no visible element with text {arg!r}")
                        x, y = r
                    elif kind == "click":
                        r = await cdp.js(f"(() => {{ const e = document.querySelector({json.dumps(arg)}); if (!e) return null; const b = e.getBoundingClientRect(); return [b.x + b.width / 2, b.y + b.height / 2]; }})()")
                        if not r:
                            raise RuntimeError(f"no element {arg}")
                        x, y = r
                    else:
                        tx, ty = (float(v) for v in arg.split(","))
                        b = await cdp.js("(() => { const b = document.querySelector('#touch-layer').getBoundingClientRect(); return [b.x, b.y, b.width, b.height]; })()")
                        x, y = b[0] + tx * b[2] / dw, b[1] + ty * b[3] / dh
                    for typ in ("mouseMoved", "mousePressed", "mouseReleased"):
                        await cdp.call("Input.dispatchMouseEvent", type=typ, x=x, y=y, button="left",
                                       buttons=1 if typ == "mousePressed" else 0, clickCount=1)
                        await asyncio.sleep(0.08)
                    print(f"{kind} {arg} -> page {x:.0f},{y:.0f}")
                elif kind == "shot":
                    r = await cdp.call("Page.captureScreenshot", format="png")
                    with open(arg, "wb") as f:
                        f.write(base64.b64decode(r["data"]))
                    print(f"shot {arg}")
                elif kind == "eval":
                    print(json.dumps(await cdp.js(arg)))
                elif kind == "geo":
                    lat, lon = (float(v) for v in arg.split(","))
                    await cdp.call("Browser.grantPermissions", permissions=["geolocation"], origin=f"https://{a.host}")
                    await cdp.call("Emulation.setGeolocationOverride", latitude=lat, longitude=lon, accuracy=10)
                    print(f"geo {lat},{lon}")
                else:
                    raise SystemExit(f"unknown action {act}")
            pump.cancel()
    finally:
        proc.terminate()
        try:
            proc.wait(10)
        except subprocess.TimeoutExpired:
            proc.kill()
        shutil.rmtree(prof, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8443, help="the Mac port forwarded to the guest's 443")
    ap.add_argument("--host", default="car.alexahub.dev")
    ap.add_argument("--size", default="1280x900", help="browser viewport")
    ap.add_argument("--display", default="1088x832", help="the app display size tap: coordinates are in")
    ap.add_argument("--chrome", default=CHROME)
    ap.add_argument("actions", nargs="*")
    asyncio.run(run(ap.parse_args()))


if __name__ == "__main__":
    sys.exit(main())
