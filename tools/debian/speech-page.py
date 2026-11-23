#!/usr/bin/env python3
"""speech-page.py [--text TEXT] [--port 9994] [--seconds N]: stands in for the car page's speech
recognizer on ATL's speech channel (docs/UPSTREAM-NOTES.md "Speech recognition"), for testing an app's
voice input without a browser: says hello with recognition, and answers every "listen" with ready,
speechStart, speechEnd and the results [TEXT]. Prints each message it gets and sends ("<" / ">").
Exits after N seconds (default 120)."""
import argparse
import asyncio
import json

import websockets


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--text", default="dizengoff square")
    ap.add_argument("--port", type=int, default=9994)
    ap.add_argument("--seconds", type=float, default=120)
    a = ap.parse_args()

    async def send(ws, m):
        print("> " + json.dumps(m), flush=True)
        await ws.send(json.dumps(m))

    async with websockets.connect(f"ws://127.0.0.1:{a.port}/sockets/speech") as ws:
        await send(ws, {"type": "hello", "recognition": True})

        async def answer():
            async for text in ws:
                print("< " + text, flush=True)
                m = json.loads(text)
                if m.get("type") == "listen":
                    n = m["id"]
                    for t in ("ready", "speechStart"):
                        await send(ws, {"type": t, "id": n})
                    await asyncio.sleep(1)
                    await send(ws, {"type": "speechEnd", "id": n})
                    await send(ws, {"type": "results", "id": n, "texts": [a.text], "confidences": [0.9]})

        try:
            await asyncio.wait_for(answer(), a.seconds)
        except asyncio.TimeoutError:
            pass


asyncio.run(main())
