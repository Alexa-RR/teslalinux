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

Where the app's sound plays (Settings > Audio; services/api/api.py writes SETTINGS): "car" (the car's
browser only, the default), "pi" (a speaker on the Pi only) or "both". For the Pi, this daemon follows
PipeWire's graph (`pw-dump --monitor`), picks the output (the newest USB sound card, else the 3.5 mm
jack, else another ALSA output; WirePlumber's ALSA monitor sees them, HDMI excluded) and runs a
`pw-loopback` from the "android" sink's monitor into it, restarted when the choice changes (a USB card
plugged in or out). Both ends of the loopback are passive: the card is opened only while the app
plays, and suspends after. The speaker volume is the loopback's stream volume (cubic, as wpctl and
Android's volume curves); the card itself is set to full once when chosen. In "pi" the car's stream
carries nothing. What is chosen goes to STATUS for the API.
"""
import asyncio
import json
import os
import signal
import struct
import tempfile
import time

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

# ---------------------------------------------------------------- where the sound plays
SETTINGS = os.environ.get("TESLALINUX_AUDIO_SETTINGS", "/var/lib/teslalinux/state/audio.json")
STATUS = os.environ.get("TESLALINUX_AUDIO_STATUS",
                        os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/run/tesla"), "teslalinux-audio.json"))
APP_SINK = SOURCE[:-len(".monitor")] if SOURCE.endswith(".monitor") else SOURCE
OUTPUTS = ("car", "pi", "both")
DEFAULT_SETTINGS = {"output": "car", "volume": 80}
LOOPBACK = "teslalinux-speaker"  # pw-loopback's name: its streams are input.<name> and output.<name>
CARD_SETTLE_S = 30.0  # after a card appears, a level below full is its power-up report, not the driver's


def read_settings(path=SETTINGS):
    """{"output": "car"|"pi"|"both", "volume": 0..100}; the defaults for anything missing or wrong"""
    s = dict(DEFAULT_SETTINGS)
    try:
        with open(path) as f:
            d = json.load(f)
    except (OSError, ValueError):
        return s
    if isinstance(d, dict):
        if d.get("output") in OUTPUTS:
            s["output"] = d["output"]
        v = d.get("volume")
        if isinstance(v, int) and not isinstance(v, bool) and 0 <= v <= 100:
            s["volume"] = v
    return s


class Graph:
    """PipeWire's objects as `pw-dump --monitor` reports them (by id; an update replaces the parts it
    carries, "info": null removes the object)"""

    def __init__(self):
        self.objects = {}

    def apply(self, updates):
        for o in updates:
            oid = o.get("id")
            if oid is None:
                continue
            if "info" in o and o["info"] is None:
                self.objects.pop(oid, None)
                continue
            cur = self.objects.setdefault(oid, {})
            for k, v in o.items():
                if k == "info" and isinstance(v, dict) and isinstance(cur.get("info"), dict):
                    info = cur["info"]
                    for ik, iv in v.items():
                        info[ik] = iv
                else:
                    cur[k] = v

    def props(self, oid):
        o = self.objects.get(oid) or {}
        return ((o.get("info") or {}).get("props")) or {}

    def nodes(self):
        for oid, o in self.objects.items():
            if o.get("type") == "PipeWire:Interface:Node":
                yield oid, self.props(oid)

    def node_id(self, name):
        for oid, p in self.nodes():
            if p.get("node.name") == name:
                return oid
        return None

    def node_state(self, oid):
        return ((self.objects.get(oid) or {}).get("info") or {}).get("state")

    def node_volume(self, oid):
        """the node's loudest channel volume (linear, 1.0 = full) from its Props, or None"""
        params = (((self.objects.get(oid) or {}).get("info") or {}).get("params")) or {}
        for p in params.get("Props") or []:
            if isinstance(p, dict) and isinstance(p.get("channelVolumes"), list) and p["channelVolumes"]:
                vols = [v for v in p["channelVolumes"] if isinstance(v, (int, float))]
                if vols:
                    return max(vols)
        return None

    def speakers(self):
        """the Pi's own outputs (ALSA sinks of WirePlumber's monitor), the preferred one first: USB
        sound cards (the newest first), the 3.5 mm jack, any other"""
        out = []
        for oid, p in self.nodes():
            if p.get("media.class") != "Audio/Sink" or p.get("node.name") == APP_SINK:
                continue
            if not str(p.get("node.name", "")).startswith("alsa_output."):
                continue
            dev = self.props(p.get("device.id")) if p.get("device.id") is not None else {}
            bus = dev.get("device.bus") or p.get("device.bus") or ""
            card = p.get("alsa.card_name") or p.get("api.alsa.card.name") or ""
            if bus == "usb" or ".usb-" in p["node.name"]:
                kind, rank = "usb", 0
            elif "bcm2835 headphones" in card.lower():
                kind, rank = "jack", 1
            else:
                kind, rank = "other", 2
            out.append((rank, -oid, {"id": oid, "name": p["node.name"], "kind": kind,
                                     "description": p.get("node.description") or dev.get("device.description") or card}))
        return [s for _r, _i, s in sorted(out, key=lambda t: (t[0], t[1]))]


async def pw_monitor(graph, changed):
    """`pw-dump --monitor`: every top-level JSON array is one update; restarted when it ends"""
    while True:
        try:
            proc = await asyncio.create_subprocess_exec("pw-dump", "--monitor", "--no-colors",
                                                        stdout=asyncio.subprocess.PIPE,
                                                        stderr=asyncio.subprocess.DEVNULL)
        except OSError as e:
            print("teslalinux-audio: pw-dump: %s" % e, flush=True)
            await asyncio.sleep(10)
            continue
        graph.objects.clear()
        buf = []
        try:
            while True:
                line = await proc.stdout.readline()
                if not line:
                    break
                text = line.decode("utf-8", "replace")
                buf.append(text)
                if text.rstrip("\n") == "]":
                    try:
                        graph.apply(json.loads("".join(buf)))
                    except ValueError:
                        pass
                    buf = []
                    changed.set()
        finally:
            if proc.returncode is None:
                proc.kill()
            await proc.wait()
        await asyncio.sleep(1)


async def run_quiet(*cmd):
    try:
        p = await asyncio.create_subprocess_exec(*cmd, stdout=asyncio.subprocess.DEVNULL,
                                                 stderr=asyncio.subprocess.DEVNULL)
        return await p.wait()
    except OSError:
        return -1


class Speaker:
    """the pw-loopback from the app's sink into the chosen output"""

    def __init__(self):
        self.proc = None
        self.target = None
        self.full = {}          # output id -> when it was set to full volume after it appeared
        self.volume_set = None  # (loopback node id, volume) last applied

    def running(self):
        return self.proc is not None and self.proc.returncode is None

    async def stop(self):
        if self.running():
            self.proc.terminate()
            try:
                await asyncio.wait_for(self.proc.wait(), 3)
            except asyncio.TimeoutError:
                self.proc.kill()
                await self.proc.wait()
        self.proc, self.target, self.volume_set = None, None, None

    async def ensure(self, target):
        if target is None:
            await self.stop()
            return
        if self.running() and self.target == target:
            return
        await self.stop()
        # passive on both ends: the loopback does not keep the app's sink or the card running; the
        # card opens when the app plays and suspends after. No reconnect, no fallback: never into
        # the default sink (the app's own: a loop); the next choice restarts it.
        self.proc = await asyncio.create_subprocess_exec(
            "pw-loopback", "-n", LOOPBACK,
            "--capture-props", "{ stream.capture.sink = true target.object = \"%s\" node.passive = true "
                               "node.dont-reconnect = true media.name = \"app sound to the Pi's speaker\" }" % APP_SINK,
            "--playback-props", "{ target.object = \"%s\" node.passive = true node.dont-reconnect = true "
                                "node.dont-fallback = true media.role = \"Navigation\" }" % target,
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
        self.target = target
        print("teslalinux-audio: the app's sound plays on %s too" % target, flush=True)

    async def apply_volume(self, graph, volume, target_id, now=None):
        # The card plays at full; the speaker's level is the loopback stream's (Settings > Sound). A
        # card WirePlumber just found starts at its default (0.4), and a USB speaker with its own
        # volume (a Jabra Speak2) reports that level again a few seconds after it powers up, after
        # this set it to full, which WirePlumber then saved as the card's level (seen: every restart
        # left the speaker at -18 dB). So during CARD_SETTLE_S after it appeared, a card below full
        # is put back to full; later changes (the speaker's own volume buttons) are the driver's.
        if target_id is not None:
            now = time.monotonic() if now is None else now
            first = self.full.get(target_id)
            vol = graph.node_volume(target_id)
            if first is None or (now - first < CARD_SETTLE_S and vol is not None and vol < 0.99):
                if await run_quiet("wpctl", "set-volume", str(target_id), "1.0") == 0 and first is None:
                    self.full[target_id] = now
        oid = graph.node_id("output." + LOOPBACK)
        if oid is None or self.volume_set == (oid, volume):
            return
        if await run_quiet("wpctl", "set-volume", str(oid), "%.2f" % (volume / 100.0)) == 0:
            self.volume_set = (oid, volume)


def write_status(status, path=STATUS):
    try:
        d = os.path.dirname(path) or "."
        fd, tmp = tempfile.mkstemp(dir=d, prefix=".audio-status")
        with os.fdopen(fd, "w") as f:
            json.dump(status, f)
        os.replace(tmp, path)
    except OSError as e:
        print("teslalinux-audio: status not written: %s" % e, flush=True)


def car_stream_wanted():
    return state["settings"]["output"] in ("car", "both")


async def router(graph, changed):
    speaker = Speaker()
    last_status = None
    try:
        while True:
            await changed.wait()
            changed.clear()
            s = read_settings()
            state["settings"] = s
            speakers = graph.speakers()
            choice = speakers[0] if speakers and s["output"] in ("pi", "both") else None
            await speaker.ensure(choice["name"] if choice else None)
            await speaker.apply_volume(graph, s["volume"], choice["id"] if choice else None)
            # forget cards that went away: one plugged in again starts at WirePlumber's default
            ids = {sp["id"] for sp in speakers}
            speaker.full = {k: v for k, v in speaker.full.items() if k in ids}
            # the car's stream: only in "car" and "both"
            if car_stream_wanted():
                if clients and (not state["task"] or state["task"].done()):
                    state["task"] = asyncio.create_task(encoder())
            elif state["task"]:
                state["task"].cancel()
                state["task"] = None
                state["init"] = b""
            playing = False
            if choice:
                playing = graph.node_state(choice["id"]) == "running"
            status = {"output": s["output"], "volume": s["volume"],
                      "speaker": ({k: choice[k] for k in ("name", "description", "kind")} if choice else None),
                      "speakers": [{k: sp[k] for k in ("name", "description", "kind")} for sp in speakers],
                      "playing": playing, "carStream": car_stream_wanted()}
            if status != last_status:
                write_status(status)
                last_status = status
    finally:
        await speaker.stop()


async def settings_watch(changed, interval=0.5):
    """SETTINGS is written by the API (another process): its change time, twice a second"""
    last = None
    while True:
        try:
            st = os.stat(SETTINGS)
            cur = (st.st_mtime_ns, st.st_size, st.st_ino)
        except OSError:
            cur = None
        if cur != last:
            last = cur
            changed.set()
        await asyncio.sleep(interval)


class Client:
    def __init__(self, ws):
        self.ws = ws
        self.queue = asyncio.Queue()
        self.have_init = False


clients: set = set()
state = {"init": b"", "task": None, "idle": None, "settings": read_settings()}


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
    if car_stream_wanted() and (not state["task"] or state["task"].done()):
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
    graph, changed = Graph(), asyncio.Event()
    tasks = [asyncio.create_task(pw_monitor(graph, changed)), asyncio.create_task(settings_watch(changed)),
             asyncio.create_task(router(graph, changed))]
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop.set)
    async with websockets.serve(ws_handler, WS_BIND, WS_PORT, ping_interval=5, max_size=None,
                                compression=None):
        await stop.wait()
    # the loopback goes with this daemon (router's finally)
    for t in tasks:
        t.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)


if __name__ == "__main__":
    asyncio.run(main())
