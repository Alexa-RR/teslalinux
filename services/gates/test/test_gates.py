#!/usr/bin/env python3
"""services/gates against a local mock of PalGate's API (test/mock_palgate.py); nothing here talks
to PalGate. Needs python3-cryptography.

    python3 services/gates/test/test_gates.py
"""
import json
import os
import socket
import stat
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
TMP = tempfile.mkdtemp(prefix="tl-gates-")

import mock_palgate  # noqa: E402
import palgate  # noqa: E402
import tlqr  # noqa: E402

MOCK = mock_palgate.MockPalGate().start()

# the GPS feed the service reads (gps.py's line protocol), here a test server
FEED = socket.socket()
FEED.bind(("127.0.0.1", 0))
FEED.listen(4)
FEED_CLIENTS = []


def _accept():
    while True:
        c, _ = FEED.accept()
        FEED_CLIENTS.append(c)


threading.Thread(target=_accept, daemon=True).start()

os.environ.update({
    "TESLALINUX_GATES_DIR": os.path.join(TMP, "gates"),
    "TESLALINUX_GATES_API": MOCK.base,
    "TESLALINUX_GATES_GPS_FEED": "127.0.0.1:%d" % FEED.getsockname()[1],
    "TESLALINUX_GATES_PORT": "0",
})
import gates  # noqa: E402

gates.LINK_POLL_S = 0.1


def feed(lat, lon, speed="NaN", acc="5.0"):
    line = ("%.7f %.7f %s NaN %s %d\n" % (lat, lon, speed, acc, int(time.time() * 1000))).encode()
    for c in list(FEED_CLIENTS):
        c.sendall(line)


GATE = (32.0, 34.8)
M_PER_DEG_LAT = 111195.0


def north_of_gate(m):
    return GATE[0] + m / M_PER_DEG_LAT, GATE[1]


class TokenTest(unittest.TestCase):
    def test_vectors(self):
        # the published vectors of the open-source PalGate clients, reproduced with plain AES-128-ECB
        self.assertEqual(palgate.derive_token("000102030405060708090a0b0c0d0e0f", 972500000000, 0, 1700000000),
                         "0100E26D845D009D5046C334E5D64A4B21D74F0DE80208")
        self.assertEqual(palgate.derive_token("000102030405060708090a0b0c0d0e0f", 972500000000, 1, 1700000000)[:2], "11")
        self.assertEqual(palgate.derive_token(bytes(16), 972501234567, 1, 1700000000),
                         "1100E26D97338757588A72386BA26C9B1CC2A3D372CFA6")

    def test_bad_input(self):
        with self.assertRaises(ValueError):
            palgate.derive_token("00", 9725, 1)
        with self.assertRaises(ValueError):
            palgate.derive_token(bytes(16), 9725, 3)

    def test_normalise(self):
        g = palgate.normalise_gate(mock_palgate.DEVICES[0])
        self.assertEqual(g["outputs"], [1, 2])
        self.assertEqual(g["outputNames"]["2"], "Pedestrian door")
        self.assertEqual(palgate.normalise_gate(mock_palgate.DEVICES[1])["outputs"], [1])
        self.assertEqual(palgate.normalise_gate({"id": "x"})["outputs"], [1])
        self.assertIsNone(palgate.normalise_gate({}))


class QrTest(unittest.TestCase):
    def test_shape(self):
        text = json.dumps({"id": "0b7c4d1e-6a2f-4c55-9e1a-3f2d8e7b6a10"})
        m = tlqr.matrix(text)
        self.assertEqual(len(m), 33)  # 45 bytes at level M: version 4
        for (x, y) in ((0, 0), (26, 0), (0, 26)):  # the three finders
            self.assertEqual([m[y][x + i] for i in range(7)], [1] * 7)
            self.assertEqual([m[y + 1][x + i] for i in range(7)], [1, 0, 0, 0, 0, 0, 1])
        self.assertEqual(m[33 - 8][8], 1)  # the dark module
        self.assertIn("<svg", tlqr.svg(text))
        with self.assertRaises(ValueError):
            tlqr.matrix("x" * 300)

    def test_format_bits_decode(self):
        # the 15 format bits read back from the top-left copy are a valid level M code word
        m = tlqr.matrix("teslalinux")
        bits = [m[i][8] for i in range(6)] + [m[7][8], m[8][8], m[8][7]] + [m[8][14 - i] for i in range(9, 15)]
        word = sum(b << i for i, b in enumerate(bits)) ^ 0x5412
        self.assertEqual(word >> 13, 0b00)  # level M
        self.assertEqual(tlqr._bch(word >> 10, 0x537, 11), word)


class TrackerTest(unittest.TestCase):
    CFG = dict(gates.AUTO_DEFAULTS, enabled=True, lat=GATE[0], lon=GATE[1], radius=60, minSpeed=3, maxSpeed=50, cooldown=10)

    def drive(self, tr, metres, t0=0.0, dt=1.0, speed=8.0, cfg=None):
        fired = []
        for i, m in enumerate(metres):
            lat, lon = north_of_gate(m)
            go, _ = tr.decide(cfg or self.CFG, {"lat": lat, "lon": lon, "speed": speed, "accuracy": 5.0}, t0 + i * dt)
            if go:
                fired.append(m)
        return fired

    def test_approach_fires_once(self):
        tr = gates.AutoTracker()
        fired = self.drive(tr, range(200, -1, -8))
        self.assertEqual(len(fired), 1)
        self.assertLessEqual(fired[0], 60)

    def test_parked_inside_never_fires(self):
        tr = gates.AutoTracker()
        self.assertEqual(self.drive(tr, [20, 21, 19, 20, 18] * 20, speed=0.0), [])
        tr = gates.AutoTracker()
        # started inside (the device booted in the garage), then drives a little inside: unarmed
        self.assertEqual(self.drive(tr, [30, 25, 20, 15, 10]), [])

    def test_cooldown_and_rearm(self):
        tr = gates.AutoTracker()
        self.assertEqual(len(self.drive(tr, range(200, -1, -8))), 1)
        # leaves and comes back within the cooldown: no second open
        self.assertEqual(self.drive(tr, list(range(0, 201, 8)) + list(range(200, -1, -8)), t0=100), [])
        # back after the cooldown: opens again
        self.assertEqual(len(self.drive(tr, list(range(0, 201, 8)) + list(range(200, -1, -8)), t0=1000)), 1)

    def test_limits(self):
        tr = gates.AutoTracker()
        self.assertEqual(self.drive(tr, range(200, -1, -8), speed=20.0), [])  # 72 km/h > 50
        tr = gates.AutoTracker()
        self.assertEqual(self.drive(tr, range(200, -1, -8), speed=0.5), [])  # 1.8 km/h < 3

    def test_jitter_inside_is_not_approaching(self):
        tr = gates.AutoTracker()
        cfg = dict(self.CFG, minSpeed=0)
        tr.decide(cfg, {"lat": north_of_gate(300)[0], "lon": GATE[1], "speed": 8.0, "accuracy": 5}, 0)
        # it came in during a gap in the fixes and stands still inside: only the first fix in counts as closing in,
        # and a car that stays put while the position wanders does not count as approaching
        tr2 = gates.AutoTracker()
        tr2.decide(cfg, {"lat": north_of_gate(300)[0], "lon": GATE[1], "speed": 0.0, "accuracy": 5}, 0)
        self.assertEqual(self.drive(tr2, [30, 31, 30, 32, 31, 33, 32], t0=30, speed=0.0, cfg=cfg), [])

    def test_unknown_speed_uses_distance(self):
        tr = gates.AutoTracker()
        self.assertEqual(len(self.drive(tr, range(200, -1, -8), speed=None)), 1)

    def test_inaccurate_fix_ignored(self):
        tr = gates.AutoTracker()
        tr.decide(self.CFG, {"lat": north_of_gate(300)[0], "lon": GATE[1], "speed": 8.0, "accuracy": 5}, 0)
        lat, lon = north_of_gate(10)
        self.assertEqual(tr.decide(self.CFG, {"lat": lat, "lon": lon, "speed": 8.0, "accuracy": 200}, 1), (False, "position not accurate enough"))

    def test_off(self):
        tr = gates.AutoTracker()
        self.assertEqual(self.drive(tr, range(200, -1, -8), cfg=dict(self.CFG, enabled=False)), [])


class ServiceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        gates.GATES = gates.Gates()
        threading.Thread(target=gates.GATES.gps_reader, daemon=True).start()
        cls.srv = gates.ThreadingHTTPServer(("127.0.0.1", 0), gates.Handler)
        cls.srv.daemon_threads = True
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.url = "http://127.0.0.1:%d/api/" % cls.srv.server_address[1]
        # api.py in front of it, as on the device
        sys.path.insert(0, os.path.join(HERE, "..", "..", "api"))
        os.environ["TESLALINUX_GATES_URL"] = "http://127.0.0.1:%d" % cls.srv.server_address[1]
        os.environ["TESLALINUX_STATE"] = os.path.join(TMP, "props.json")
        import api
        api.GATES_URL = os.environ["TESLALINUX_GATES_URL"]
        cls.api = api
        cls.api_srv = api.ThreadingHTTPServer(("127.0.0.1", 0), api.Handler)
        threading.Thread(target=cls.api_srv.serve_forever, daemon=True).start()
        cls.api_url = "http://127.0.0.1:%d/api/" % cls.api_srv.server_address[1]

    def req(self, route, body=None, via_api=True):
        url = (self.api_url if via_api else self.url) + route
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(url, data=data, method="POST" if data is not None else "GET",
                                   headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(r, timeout=40) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"null")

    def wait_for(self, pred, timeout=10):
        end = time.time() + timeout
        while time.time() < end:
            v = pred()
            if v:
                return v
            time.sleep(0.05)
        self.fail("timed out")

    def test_1_flow(self):
        code, st = self.req("gates")
        self.assertEqual(code, 200)
        self.assertFalse(st["linked"])
        self.assertEqual(self.req("gatesOpen", {"id": "4G600001"})[0], 400)  # no such gate yet

        # linking: a QR code, the phone approves, the device is linked and lists the gates
        code, link = self.req("gatesLink", {"action": "start"})
        self.assertEqual((code, link["state"]), (200, "waiting"))
        self.assertIn("<svg", link["qr"])
        self.assertNotIn("id", link)
        pending = self.wait_for(lambda: MOCK.pending)
        MOCK.approve(pending)
        self.wait_for(lambda: self.req("gates")[1]["linked"])
        st = self.wait_for(lambda: (lambda s: s if s["gates"] else None)(self.req("gates")[1]))
        self.assertEqual([g["id"] for g in st["gates"]], ["4G600001", "4G600002", "BT000003"])
        self.assertEqual(st["account"]["phone"], "•" * 9 + "123")

        # the secrets stay on the device: not in any answer, files 0600 in a 0700 directory
        blob = json.dumps(st) + json.dumps(self.req("gatesLink")[1])
        self.assertNotIn(mock_palgate.SESSION, blob)
        self.assertNotIn(mock_palgate.PHONE, blob)
        d = os.environ["TESLALINUX_GATES_DIR"]
        self.assertEqual(stat.S_IMODE(os.stat(d).st_mode), 0o700)
        for f in ("account.json", "gates.json"):
            self.assertEqual(stat.S_IMODE(os.stat(os.path.join(d, f)).st_mode), 0o600)
        with open(os.path.join(d, "account.json")) as f:
            self.assertEqual(json.load(f)["token"], mock_palgate.SESSION)

        # settings: order, shown, label, output; bad ones refused
        code, st = self.req("gatesSettings", {"order": ["4G600002", "4G600001", "BT000003"]})
        self.assertEqual(code, 200)
        self.assertEqual(st["gates"][0]["id"], "4G600002")
        code, st = self.req("gatesSettings", {"gates": [{"id": "BT000003", "shown": False}, {"id": "4G600001", "label": "Home", "output": 2}]})
        g1 = next(g for g in st["gates"] if g["id"] == "4G600001")
        self.assertEqual((g1["label"], g1["output"]), ("Home", 2))
        self.assertFalse(next(g for g in st["gates"] if g["id"] == "BT000003")["shown"])
        self.assertEqual(self.req("gatesSettings", {"gates": [{"id": "4G600002", "output": 2}]})[0], 400)
        self.assertEqual(self.req("gatesSettings", {"gates": [{"id": "4G600002", "auto": {"enabled": True}}]})[0], 400)  # no spot
        self.assertEqual(self.req("gatesSettings", {"gates": [{"id": "4G600002", "auto": {"radius": 5000}}]})[0], 400)
        self.assertEqual(self.req("gatesSettings", {"gates": [{"id": "nope"}]})[0], 400)

        # open: through the mock only; the output chosen; the reason when PalGate refuses
        _, ev0 = self.req("gatesEvents?after=0")
        code, res = self.req("gatesOpen", {"id": "4G600001"})
        self.assertEqual((code, res["ok"]), (200, True))
        self.assertEqual(MOCK.opens[-1], ("4G600001", 2))
        self.assertEqual(self.req("gatesOpen", {"id": "4G600001"})[1]["message"], "Already sent.")  # a double tap
        MOCK.fail_open = ("device error", "check device")
        code, res = self.req("gatesOpen", {"id": "4G600002"})
        self.assertEqual((code, res["ok"]), (502, False))
        self.assertIn("check device", res["message"])
        MOCK.fail_open = None
        self.assertEqual(self.req("gatesOpen", {"id": "4G600002"})[0], 200)  # a failed open may be retried at once
        code, res = self.req("gatesOpen", {"id": "BT000003"})
        self.assertIn("Bluetooth", res["message"])
        _, evs = self.req("gatesEvents?after=%d" % ev0["seq"])
        self.assertEqual([(e["gate"], e["ok"]) for e in evs["events"]], [("4G600001", True), ("4G600002", False), ("4G600002", True)])
        self.assertEqual(evs["events"][0]["name"], "Home")

        # auto-open: the spot from the car's position, then a drive towards it through the GPS feed
        gates.OPEN_DEBOUNCE_S = 0  # the tap above is not this open
        self.assertEqual(self.req("gatesPosition", {"id": "4G600002"})[0], 409)  # no position yet
        self.wait_for(lambda: FEED_CLIENTS)
        feed(*GATE)
        self.wait_for(lambda: self.req("gates")[1]["position"])
        code, pos = self.req("gatesPosition", {"id": "4G600002"})
        self.assertEqual(code, 200)
        self.assertAlmostEqual(pos["lat"], GATE[0])
        code, st = self.req("gatesSettings", {"gates": [{"id": "4G600002", "auto": {"enabled": True, "radius": 40, "minSpeed": 2}}]})
        self.assertEqual(code, 200)
        seq = st["seq"]
        n = len(MOCK.opens)
        # a long poll answers when the auto-open happens
        got = {}
        t = threading.Thread(target=lambda: got.update(self.req("gatesEvents?after=%d&wait=20" % seq)[1]))
        t.start()
        for m in range(300, -1, -10):
            feed(*north_of_gate(m), speed="7.0")
            time.sleep(0.03)
        t.join(25)
        self.assertEqual(MOCK.opens[n:], [("4G600002", 1)])
        self.assertEqual(got["events"][0]["kind"], "auto")
        # parked at the gate: nothing more
        for _ in range(20):
            feed(*GATE, speed="0.0")
        time.sleep(0.3)
        self.assertEqual(MOCK.opens[n:], [("4G600002", 1)])

        # a revoked link: the reason, in words
        MOCK.revoked = True
        code, res = self.req("gatesRefresh", {})
        self.assertEqual(code, 502)
        self.assertIn("Link it again", res["message"])
        MOCK.revoked = False

        # unlink: the account and the list are gone, PalGate asked to drop the device
        self.assertEqual(self.req("gatesUnlink", {})[0], 200)
        self.assertTrue(MOCK.unlinked)
        st = self.req("gates")[1]
        self.assertFalse(st["linked"])
        self.assertFalse(os.path.exists(os.path.join(d, "account.json")))

    def test_2_link_expires_and_cancels(self):
        old = gates.LINK_TIMEOUT_S
        gates.LINK_TIMEOUT_S = 0.5
        try:
            self.req("gatesLink", {"action": "start"})
            self.wait_for(lambda: self.req("gatesLink")[1]["state"] == "expired", 5)
        finally:
            gates.LINK_TIMEOUT_S = old
        self.req("gatesLink", {"action": "start"})
        self.assertEqual(self.req("gatesLink", {"action": "cancel"})[1]["state"], "idle")

    def test_3_bad_requests(self):
        self.assertEqual(self.req("gatesLink", {"action": "nope"})[0], 400)
        self.assertEqual(self.req("gatesEvents?after=x")[0], 400)
        self.assertEqual(self.req("nothing", via_api=False)[0], 404)


class ForwardDownTest(unittest.TestCase):
    def test_service_down(self):
        sys.path.insert(0, os.path.join(HERE, "..", "..", "api"))
        import api
        old = api.GATES_URL
        api.GATES_URL = "http://127.0.0.1:1"
        try:
            code, body = api.gates_forward("GET", "/api/gates", None)
        finally:
            api.GATES_URL = old
        self.assertEqual(code, 503)
        self.assertIn("not running", json.loads(body)["message"])


if __name__ == "__main__":
    unittest.main(verbosity=1)
