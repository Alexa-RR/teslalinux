#!/usr/bin/env python3
"""teslalinux audio relay.

PipeWire null sink "android" (the app plays into it: ATL's AudioTrack opens ALSA "default", which
pipewire-alsa routes to the default sink)  --> ffmpeg (FLAC in fragmented MP4)
  --> this: split into the init segment (ftyp+moov) and fragments (moof+mdat)
  --> ws /sockets/audio (8080): binary messages, the init segment cached and sent to every new client
      before its first fragment.

The same stream shape as upstream's audio relay so web/js/audio/* is unchanged (docs/UPSTREAM-NOTES.md):
48 kHz stereo, FLAC compression_level 0, movflags empty_moov+separate_moof+default_base_moof+frag_discont,
~40 ms fragments.

ffmpeg runs only while at least one browser listens (browser audio is off by default upstream and
the frontend connects only when it is on), and stops IDLE_STOP_S after the last one leaves.
"""
import asyncio
import os
import struct

import websockets

WS_PORT = int(os.environ.get("TESLALINUX_AUDIO_WS_PORT", "8080"))
WS_BIND = os.environ.get("TESLALINUX_AUDIO_WS_BIND", "127.0.0.1")
SOURCE = os.environ.get("TESLALINUX_AUDIO_SOURCE", "android.monitor")
IDLE_STOP_S = 10.0
MAX_BACKLOG = 25  # fragments (~1 s); the frontend jumps to the live edge past 1 s anyway

FFMPEG = [
    "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
    "-f", "pulse", "-fragment_size", "3840", "-i", SOURCE, "-ac", "2", "-ar", "48000",
    "-c:a", "flac", "-compression_level", "0", "-sample_fmt", "s16",
    "-frag_duration", "40000",
    "-movflags", "empty_moov+separate_moof+default_base_moof+frag_discont",
    "-f", "mp4", "pipe:1",
]


class Client:
    def __init__(self, ws):
        self.ws = ws
        self.queue = asyncio.Queue()
        self.have_init = False


clients: set = set()
state = {"init": b"", "task": None, "idle": None}


def deliver(data: bytes, is_init: bool):
    for c in list(clients):
        if is_init:
            c.have_init = True
        elif not c.have_init:
            continue  # nothing before its init segment
        if c.queue.qsize() >= MAX_BACKLOG:
            while not c.queue.empty():
                c.queue.get_nowait()
        c.queue.put_nowait(data)


async def read_box(stdout):
    hdr = await stdout.readexactly(8)
    size, kind = struct.unpack(">I4s", hdr)
    if size == 1:
        ext = await stdout.readexactly(8)
        size = struct.unpack(">Q", ext)[0]
        return kind, hdr + ext + await stdout.readexactly(size - 16)
    if size < 8:
        raise asyncio.IncompleteReadError(b"", None)
    return kind, hdr + await stdout.readexactly(size - 8)


async def encoder():
    """ffmpeg while there are listeners; restarted when it dies (sink not there yet, pulse restart)."""
    while clients:
        proc = await asyncio.create_subprocess_exec(*FFMPEG, stdout=asyncio.subprocess.PIPE)
        init, moof = b"", None
        try:
            while True:
                kind, box = await read_box(proc.stdout)
                if kind == b"ftyp":
                    init = box
                elif kind == b"moov":
                    state["init"] = init + box
                    deliver(state["init"], True)
                elif kind == b"moof":
                    moof = box
                elif kind == b"mdat" and moof is not None:
                    deliver(moof + box, False)
                    moof = None
                # other boxes (free, sidx, ...) are not part of the browser stream
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        except asyncio.CancelledError:
            raise
        finally:
            if proc.returncode is None:
                proc.kill()
            await proc.wait()
        if clients:
            await asyncio.sleep(1)


async def stop_when_idle():
    await asyncio.sleep(IDLE_STOP_S)
    if not clients and state["task"]:
        state["task"].cancel()
        state["task"] = None
        state["init"] = b""


async def ws_handler(ws, *_):
    c = Client(ws)
    if state["init"]:
        c.queue.put_nowait(state["init"])
        c.have_init = True
    clients.add(c)
    if state["idle"]:
        state["idle"].cancel()
        state["idle"] = None
    if not state["task"] or state["task"].done():
        state["task"] = asyncio.create_task(encoder())

    async def drain_incoming():
        try:
            async for _ in ws:
                pass
        except websockets.ConnectionClosed:
            pass  # dropped without a close frame (the car left the Wi-Fi)

    reader = asyncio.create_task(drain_incoming())
    try:
        while not reader.done():
            getter = asyncio.create_task(c.queue.get())
            done, _ = await asyncio.wait({getter, reader}, return_when=asyncio.FIRST_COMPLETED)
            if getter in done:
                await ws.send(getter.result())
            else:
                getter.cancel()
    except websockets.ConnectionClosed:
        pass
    finally:
        reader.cancel()
        clients.discard(c)
        if not clients:
            state["idle"] = asyncio.create_task(stop_when_idle())


async def main():
    async with websockets.serve(ws_handler, WS_BIND, WS_PORT, ping_interval=5, max_size=None,
                                compression=None):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
