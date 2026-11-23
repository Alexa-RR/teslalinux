#!/usr/bin/env python3
"""teslalinux: look at and drive the app's car templates on ATL's car-app channel (/sockets/carapp,
docs/UPSTREAM-NOTES.md "Car app templates") from a shell, without a browser: what each template
offers (its class, the app's screen stack, every action and row with its title, icon resource name
and callback), for checking what a page can reach in each state.

  carapp-probe.py [--url ws://127.0.0.1:9995/] ACTION...

Actions, run in order (each waits for the template answering it, up to --step seconds):
  show                print the current template's summary
  watch:SECONDS       print every template (and toast, alert) that arrives meanwhile
  click:REGEX         click the first action/row whose icon resName or title matches REGEX
  back                the host's back (AppManager.onBackPressed)
  send:JSON           any message, e.g. send:'{"type":"appSettings","state":"open"}'
  save:FILE.json      the current template's full JSON
  wait:SECONDS        sleep, still reading
  drop                close the socket without a close frame (a page that vanished), then reconnect
Needs the Python websockets module (Debian: python3-websockets).
"""
import argparse
import asyncio
import json
import re
import socket
import sys

import websockets


def text_of(v):
    if isinstance(v, str):
        return v
    if isinstance(v, dict):
        if "text" in v and isinstance(v["text"], str):
            return v["text"]
        for k in ("title", "text"):
            if k in v:
                return text_of(v[k])
    return ""


def icon_name(o):
    ic = o.get("icon") if isinstance(o, dict) else None
    if isinstance(ic, dict):
        inner = ic.get("icon")
        if isinstance(inner, dict):
            return inner.get("resName") or ("icon#%s" % inner.get("icon"))
        if "resName" in ic:
            return ic["resName"]
    img = o.get("image") if isinstance(o, dict) else None
    if isinstance(img, dict):
        return icon_name({"icon": img})
    return ""


def clickables(o, path="", out=None):
    """(path, class, title, icon resName, callback id) of every action, row and item with a click"""
    if out is None:
        out = []
    if isinstance(o, list):
        for i, x in enumerate(o):
            clickables(x, "%s[%d]" % (path, i), out)
    elif isinstance(o, dict):
        cls = o.get("$", "")
        cb = None
        for k in ("onClickDelegate", "onSelectedDelegate", "onCheckedChangeDelegate", "panModeDelegate"):
            d = o.get(k)
            if isinstance(d, dict) and "$cb" in d:
                cb = (k, d["$cb"])
                break
        if cb:
            out.append((path, cls, text_of(o.get("title", "")), icon_name(o), cb))
        for k, v in o.items():
            if isinstance(v, (dict, list)):
                clickables(v, "%s.%s" % (path, k) if path else k, out)
    return out


def stack(wrapper):
    return [i.get("templateId") for i in (wrapper.get("templateInfosForScreenStack") or []) if isinstance(i, dict)]


def summary(seq, wrapper):
    t = wrapper.get("template", {}) if isinstance(wrapper, dict) else {}
    lines = ["template #%s %s stack=%s" % (seq, t.get("$", "?"), stack(wrapper))]
    title = text_of(t.get("title", "")) if isinstance(t, dict) else ""
    if title:
        lines.append("  title: %s" % title)
    for path, cls, ttl, ic, (kind, cb) in clickables(t):
        lines.append("  %-60s %-14s cb=%-4s %-28s %s" % (path[:60], cls, cb, ic, ttl))
    return "\n".join(lines)


class Probe:
    def __init__(self, url, step):
        self.url, self.step = url, step
        self.ws = None
        self.wrapper, self.seq = None, 0
        self.event = asyncio.Event()
        self.printing = False
        self.reader = None

    async def connect(self):
        self.ws = await websockets.connect(self.url, max_size=None)
        self.reader = asyncio.ensure_future(self.read())
        await asyncio.sleep(1.0)  # the state a new renderer gets

    async def read(self):
        try:
            async for msg in self.ws:
                m = json.loads(msg)
                if m.get("type") == "template":
                    self.wrapper, self.seq = m["template"], m["seq"]
                    self.event.set()
                    if self.printing:
                        print(summary(self.seq, self.wrapper), flush=True)
                elif self.printing and m.get("type") in ("toast", "alert", "alertDismiss", "navigation"):
                    print("%s: %s" % (m["type"], json.dumps(m)[:300]), flush=True)
        except websockets.ConnectionClosed:
            pass

    async def answer(self):
        self.event.clear()
        try:
            await asyncio.wait_for(self.event.wait(), self.step)
            await asyncio.sleep(0.3)
            return True
        except asyncio.TimeoutError:
            return False

    async def send(self, m):
        await self.ws.send(json.dumps(m))

    async def run(self, action):
        name, _, arg = action.partition(":")
        if name == "show":
            print(summary(self.seq, self.wrapper) if self.wrapper else "no template", flush=True)
        elif name == "watch":
            self.printing = True
            await asyncio.sleep(float(arg))
            self.printing = False
        elif name == "wait":
            await asyncio.sleep(float(arg))
        elif name == "click":
            rx = re.compile(arg, re.I)
            for path, cls, ttl, ic, (kind, cb) in clickables(self.wrapper.get("template", {})):
                if rx.search(ic or "") or rx.search(ttl or ""):
                    print("click %s (%s %r %s)" % (cb, cls, ttl, ic), flush=True)
                    await self.send({"type": "click", "cb": cb})
                    ok = await self.answer()
                    print(summary(self.seq, self.wrapper) if ok else "  (no new template in %ss)" % self.step, flush=True)
                    return
            print("click: nothing matches %r" % arg, flush=True)
        elif name == "send":
            await self.send(json.loads(arg))
        elif name == "back":
            await self.send({"type": "back"})
            ok = await self.answer()
            print(summary(self.seq, self.wrapper) if ok else "  (no new template in %ss)" % self.step, flush=True)
        elif name == "save":
            with open(arg, "w") as f:
                json.dump(self.wrapper, f, indent=1)
            print("saved %s" % arg, flush=True)
        elif name == "drop":
            # the socket closed under the client, no close frame: a browser that went away
            sock = self.ws.transport.get_extra_info("socket")
            if sock is not None:
                sock.shutdown(socket.SHUT_RDWR)
            self.ws.transport.abort()
            self.reader.cancel()
            await asyncio.sleep(0.5)
            await self.connect()
        else:
            raise SystemExit("unknown action %r" % action)


async def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="ws://127.0.0.1:9995/")
    ap.add_argument("--step", type=float, default=4.0)
    ap.add_argument("actions", nargs="*", default=["show"])
    a = ap.parse_args()
    p = Probe(a.url, a.step)
    await p.connect()
    for action in a.actions:
        await p.run(action)
    await p.ws.close()


if __name__ == "__main__":
    asyncio.run(main())
