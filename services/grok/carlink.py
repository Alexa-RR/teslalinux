"""The link to ATL's car app channel (ws://127.0.0.1:9995, docs/UPSTREAM-NOTES.md "Car app templates"),
kept open for the life of the service, so that a tool never waits for a connection or for the state
burst a new renderer gets.

A reader thread holds one WebSocket to the car host (reconnecting after a drop) and keeps what the
host sends as current state: the template (Waze's screen), the navigation state, the trip, the
speedometer, the car's day/night and restrictions, the map surface, and Waze's recent alerts and
toasts. Every message wakes the waiters of a condition variable, so a tool waits exactly until the
template or the answer it needs arrives (no fixed sleeps), and a read tool answers from this state
without touching Waze at all.

CarSession is a tool's view of the link (the API the tools were written against): one per tool
call, closing it does not close the link. The host's hold belongs to this one connection; tools run
one at a time (grok.py's lock), so holds never overlap.
"""
import base64
import collections
import json
import os
import socket
import struct
import threading
import time

ALERT_KEEP = 100
STATE_BURST_S = 1.0       # a new connection's state has come by then (it comes in milliseconds)
REPLY_S = 10.0            # the host's answer to a request


class WazeError(Exception):
    """Something the caller is told as a tool error."""


def _addr(s):
    host, _, port = s.rpartition(":")
    return host, int(port)


class WebSocket:
    """RFC 6455 client for one loopback text-frame channel: masked frames out, pings answered.
    Sends are locked: the reader thread answers pings while a tool sends a request."""

    def __init__(self, addr, timeout=5.0):
        host, port = _addr(addr)
        self.send_lock = threading.Lock()
        try:
            self.sock = socket.create_connection((host, port), timeout=timeout)
        except OSError as e:
            raise WazeError("Waze's car interface is not reachable (%s:%d: %s). Waze may be starting or stopped." % (host, port, e.strerror or e))
        key = base64.b64encode(os.urandom(16)).decode()
        req = ("GET / HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n") % (host, port, key)
        self.buf = b""
        try:
            self.sock.sendall(req.encode())
            head = self._read_until(b"\r\n\r\n", 16384)
        except (OSError, WazeError) as e:
            self.sock.close()
            raise WazeError("the car app channel did not answer (%s). Waze may be starting." % e)
        if not head.startswith(b"HTTP/1.1 101"):
            self.close()
            raise WazeError("the car app channel refused the connection")
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    def _read_until(self, mark, limit):
        while mark not in self.buf:
            if len(self.buf) > limit:
                raise WazeError("bad handshake")
            chunk = self.sock.recv(4096)
            if not chunk:
                raise WazeError("the car app channel closed")
            self.buf += chunk
        head, self.buf = self.buf.split(mark, 1)
        return head

    def _read_exact(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(max(65536, n - len(self.buf)))
            if not chunk:
                raise ConnectionError("closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def send_text(self, text):
        self._send(0x1, text.encode("utf-8"))

    def _send(self, opcode, payload):
        mask = os.urandom(4)
        n = len(payload)
        hdr = bytes([0x80 | opcode])
        if n < 126:
            hdr += bytes([0x80 | n])
        elif n < 65536:
            hdr += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            hdr += bytes([0x80 | 127]) + struct.pack(">Q", n)
        m = (mask * (n // 4 + 1))[:n]
        masked = (int.from_bytes(payload, "big") ^ int.from_bytes(m, "big")).to_bytes(n, "big") if n else b""
        with self.send_lock:
            self.sock.sendall(hdr + mask + masked)

    def recv_text(self, timeout):
        """The next text message, or None when nothing came within timeout seconds."""
        self.sock.settimeout(timeout)
        message = b""
        while True:
            try:
                b0, b1 = self._read_exact(2)
            except socket.timeout:
                return None
            self.sock.settimeout(5.0)  # the rest of a frame that has begun
            opcode, n = b0 & 0x0F, b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._read_exact(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._read_exact(8))[0]
            mask = self._read_exact(4) if b1 & 0x80 else None
            payload = self._read_exact(n)
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            if opcode == 0x9:
                self._send(0xA, payload)
            elif opcode == 0x8:
                raise ConnectionError("closed by the host")
            elif opcode in (0x0, 0x1):
                message += payload
                if b0 & 0x80:
                    return message.decode("utf-8", "replace")
            self.sock.settimeout(timeout)

    def close(self):
        try:
            self._send(0x8, b"\x03\xe8")
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


class CarLink:
    """One persistent connection to the car host and the state it keeps sending."""

    def __init__(self, addr):
        self.addr = addr
        self.cv = threading.Condition()
        self.ws = None
        self.connected = False
        self.error = None
        self.gen = 0                 # connections made (a new one resends the state)
        self.connected_at = 0.0
        self.seq = 0                 # templates received
        self.template = None
        self.template_at = 0.0
        self.nav_screen = None       # the last NavigationTemplate of the current drive
        self.nav_at = 0.0
        self.navigating = None
        self.trip = None
        self.seen_trip = False
        self.speedometer = None
        self.day_night = None
        self.restrictions = None
        self.surface = None
        self.alerts = collections.deque(maxlen=ALERT_KEEP)   # (n, time, alert)
        self.alert_count = 0
        self.dismissed = set()       # alert ids Waze took back
        self.toasts = collections.deque(maxlen=ALERT_KEEP)   # (time, text)
        self.results = {}
        self._id = 0
        t = threading.Thread(target=self._run, daemon=True, name="carlink")
        t.start()

    # the reader ----------------------------------------------------------------------

    def _run(self):
        while True:
            try:
                self._connect_and_read()
            except Exception as e:  # never let the reader die: the service would lose Waze for good
                with self.cv:
                    self.connected, self.ws, self.error = False, None, "the car link failed (%s)" % e
                    self.template, self.navigating, self.trip, self.seen_trip = None, None, None, False
                    self.cv.notify_all()
                time.sleep(1.0)

    def _connect_and_read(self):
        delay = 0.2
        while True:
            try:
                ws = WebSocket(self.addr, timeout=3.0)
            except (WazeError, OSError) as e:
                with self.cv:
                    self.connected, self.error = False, str(e)
                    self.cv.notify_all()
                time.sleep(delay)
                delay = min(delay * 2, 3.0)
                continue
            delay = 0.2
            with self.cv:
                self.ws, self.connected, self.error = ws, True, None
                self.gen += 1
                self.connected_at = time.monotonic()
                self.cv.notify_all()
            try:
                while True:
                    text = ws.recv_text(30.0)
                    if text is not None:
                        self._take(text)
            except (OSError, ConnectionError, ValueError) as e:
                with self.cv:
                    self.connected, self.ws, self.error = False, None, "the car app channel closed (%s)" % e
                    # a new connection gets the whole state again
                    self.template, self.navigating, self.trip, self.seen_trip = None, None, None, False
                    self.cv.notify_all()
                ws.close()

    def _take(self, text):
        try:
            m = json.loads(text)
        except ValueError:
            return
        t = m.get("type")
        with self.cv:
            if t == "template":
                self.template, self.template_at = m.get("template"), time.monotonic()
                self.seq += 1
                inner = (self.template or {}).get("template") or {}
                if inner.get("$") == "NavigationTemplate":
                    # what Waze's navigation screen showed last, for the tools that read it while
                    # another screen is up (its travel estimate counts the stops)
                    self.nav_screen, self.nav_at = inner, time.time()
            elif t == "navigation":
                self.navigating = bool(m.get("active"))
                if not self.navigating:
                    self.nav_screen = None
            elif t == "trip":
                self.trip, self.seen_trip = m.get("trip"), True
            elif t == "alert" and isinstance(m.get("alert"), dict):
                self.alert_count += 1
                self.alerts.append((self.alert_count, time.time(), m["alert"]))
            elif t == "alertDismiss":
                self.dismissed.add(m.get("id"))
            elif t == "toast" and isinstance(m.get("text"), str):
                self.toasts.append((time.time(), m["text"]))
            elif t == "speedometer":
                self.speedometer = dict(m, at=time.time())
            elif t == "dayNight":
                self.day_night = m.get("night")
            elif t == "restrictions":
                self.restrictions = m
            elif t == "surface":
                self.surface = m
            elif isinstance(t, str) and t.endswith("Result") and "id" in m:
                self.results[m["id"]] = m
            self.cv.notify_all()

    # for the tools ---------------------------------------------------------------------

    def wait(self, pred, seconds):
        """Waits until pred() is true (checked on every message) or seconds pass; pred()'s value."""
        end = time.monotonic() + seconds
        with self.cv:
            while True:
                v = pred()
                if v:
                    return v
                left = end - time.monotonic()
                if left <= 0:
                    return v
                self.cv.wait(left)

    def ensure(self, seconds=6.0):
        """The link is up and has Waze's screen; WazeError otherwise."""
        if not self.wait(lambda: self.connected and self.template is not None, seconds):
            raise WazeError(self.error or "Waze's car session has not started yet.")

    def ready(self, seconds=6.0, burst=STATE_BURST_S):
        """Waits for the connection and the state a new connection gets (Waze's screen, or `burst`
        seconds of a connection without one: Waze's car session has not started); whether it is up."""
        return bool(self.wait(lambda: self.connected and (self.template is not None
                                                          or time.monotonic() - self.connected_at > burst), seconds)
                    and self.connected)

    def send(self, msg):
        ws = self.ws
        if ws is None:
            raise WazeError(self.error or "the car app channel is not connected")
        try:
            ws.send_text(json.dumps(msg))
        except OSError as e:
            raise WazeError("the car app channel failed: %s" % e)

    def request(self, msg, seconds=REPLY_S):
        """Sends a request with an id; its answer ({ok, error}) or WazeError."""
        with self.cv:
            self._id += 1
            rid = self._id
        self.send(dict(msg, id=rid))
        if not self.wait(lambda: rid in self.results, seconds):
            raise WazeError("the car host did not answer (this runtime may not support %s)" % msg["type"])
        with self.cv:
            return self.results.pop(rid)


_links = {}
_links_lock = threading.Lock()


def link(addr):
    """The service's link to the car host at addr (made on first use, kept)."""
    with _links_lock:
        if addr not in _links:
            _links[addr] = CarLink(addr)
        return _links[addr]


def depth(stack, base):
    """How many screens lie above `base` in `stack` (template ids, top first), as ATL's host counts
    them: the screens under the base's top keep their ids; the base's top may be refreshed."""
    n = len(base)
    if n == 0:
        return 0
    under = base[1:]
    if len(stack) > n and (not under or stack[-len(under):] == under):
        return len(stack) - n
    return 0


class CarSession:
    """A tool's view of the link: Waze's current state, and taps that wait on the screens they cause.
    Predicates given to wait() get this view and run under the link's lock on every message."""

    def __init__(self, addr):
        self.link = link(addr)
        if not self.link.ready():
            raise WazeError(self.link.error or "Waze's car interface is not reachable. Waze may be starting or stopped.")

    def close(self):
        """Nothing to close: the link stays for the next tool."""

    @property
    def template(self):
        return self.link.template

    @property
    def navigating(self):
        return self.link.navigating

    @property
    def trip(self):
        return self.link.trip

    @property
    def seen_trip(self):
        return self.link.seen_trip

    @property
    def seq(self):
        return self.link.seq

    @property
    def nav_screen(self):
        return self.link.nav_screen

    @property
    def alert_count(self):
        return self.link.alert_count

    def alerts_since(self, n):
        """The alerts Waze showed after the n-th: [alert]."""
        return [a for k, _, a in list(self.link.alerts) if k > n]

    def inner(self):
        """The current template's own model (the wrapper's "template"), or {}."""
        return (self.link.template or {}).get("template") or {}

    def stack(self):
        return [i.get("templateId") for i in (self.link.template or {}).get("templateInfosForScreenStack") or [] if isinstance(i, dict)]

    def wait(self, until, seconds):
        """Waits until until(self) is true or seconds pass; whether it became true."""
        return bool(self.link.wait(lambda: until(self), seconds))

    def wait_template(self, pred, seconds, what):
        """Waits for a template the predicate accepts (given the inner model); WazeError if none comes."""
        if self.wait(lambda x: bool(x.template) and pred(x.inner()), seconds):
            return self.inner()
        if not self.link.connected:
            raise WazeError(self.link.error or "the car app channel closed")
        raise WazeError("Waze did not show %s within %g s." % (what, seconds))

    def wait_new(self, seq, seconds):
        """Waits for a template after the seq-th; whether one came."""
        return self.wait(lambda x: x.seq > seq, seconds)

    def settle(self, quiet=0.25, most=2.0):
        """Waits until no template came for `quiet` seconds (Waze refreshes a new screen a few times
        right after showing it); returns at once when the last one is older than that."""
        lk = self.link
        end = time.monotonic() + most
        with lk.cv:
            while True:
                now = time.monotonic()
                since = now - lk.template_at
                if since >= quiet or now >= end:
                    return
                lk.cv.wait(min(quiet - since, end - now))

    def send(self, msg):
        self.link.send(msg)

    def request(self, msg, seconds=REPLY_S):
        return self.link.request(msg, seconds)

    def click(self, cb):
        if not isinstance(cb, int):
            raise WazeError("Waze's screen has nothing to tap there.")
        self.link.send({"type": "click", "cb": cb})

    def tap(self, cb, seconds=8.0):
        """Taps and waits for the template answering it; whether one came."""
        n = self.seq
        self.click(cb)
        return self.wait_new(n, seconds)

    def press(self, find, pred, seconds, what, per_try=0.7):
        """Presses the control find(model) names on the current screen (its callback id, or None)
        until a screen pred(model) accepts comes: Waze can drop a press that arrives while it
        changes screens (seen right after a screen was popped), so a press that brought nothing
        within per_try seconds is made again with the screen's current callback."""
        end = time.monotonic() + seconds
        depth0 = len(self.stack())
        ok = lambda x: bool(x.template) and pred(x.inner())
        while True:
            cb = find(self.inner())
            if not isinstance(cb, int):
                raise WazeError("Waze's screen has no %s now." % what)
            self.link.send({"type": "click", "cb": cb})
            left = end - time.monotonic()
            if self.wait(ok, max(0.05, min(per_try, left))):
                return self.inner()
            if len(self.stack()) != depth0:
                # the press was taken (a screen came): never press again over it
                if self.wait(ok, max(0.05, end - time.monotonic())):
                    return self.inner()
                break
            if time.monotonic() >= end:
                break
        raise WazeError("Waze did not answer its %s within %g s." % (what, seconds))

    def back_to(self, base, seconds=10.0):
        """Back, one at a time, each after the template answering the one before, until the app's
        screen stack is `base` again (as the host does for screens a renderer left open)."""
        end = time.monotonic() + seconds
        for _ in range(12):
            if depth(self.stack(), base) == 0:
                return True
            n = self.seq
            self.link.send({"type": "back"})
            if not self.wait_new(n, max(0.1, end - time.monotonic())):
                return False
        return depth(self.stack(), base) == 0
