#!/usr/bin/env python3
"""teslalinux-uplink's LTE modem recovery without a modem, root or a network.

    python3 host/test/test_uplink_modem.py

* a fake Huawei HiLink web API on 127.0.0.1 (SesTokInfo, monitoring/status, device/basic_information,
  dialup/mobile-dataswitch, device/control) with single-use tokens, a session cookie and two login modes
* a fake sysfs tree (USB devices, the stick's network interface, authorized/bind/unbind, the board model),
  fake mmcli / nmcli / uhubctl, an injected clock
* covered: no coverage -> nothing; coverage without internet -> reconnect, modem reset, USB re-enumeration
  at least 2 minutes apart, then the 15 minute pause and the ladder again; a dead API -> straight to the USB
  step; login required (everything, or control only) -> the API steps are skipped; the USB power cycle
  only with the stick alone on USB (auto), never without uhubctl; working LTE resets the ladder; prefer
  "wifi" with a working Wi-Fi and LTE_RECOVERY=off -> nothing; the ModemManager ladder; the state JSON
"""
import http.server
import importlib.machinery
import importlib.util
import json
import os
import re
import shutil
import socket
import sys
import tempfile
import threading
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
_loader = importlib.machinery.SourceFileLoader("uplink", os.path.join(REPO, "host", "teslalinux-uplink"))
_spec = importlib.util.spec_from_loader("uplink", _loader)
up = importlib.util.module_from_spec(_spec)
_loader.exec_module(up)


# ---------------------------------------------------------------- fake HiLink web API

class FakeHiLink:
    def __init__(self):
        self.mode = "open"  # open | login (every API call wants a login) | login-control (status readable)
        self.status = {"ConnectionStatus": "902", "SignalIcon": "4", "SignalStrength": "", "ServiceStatus": "2",
                       "CurrentNetworkType": "19", "CurrentNetworkTypeEx": "101", "RoamingStatus": "0",
                       "SimStatus": "1", "maxsignal": "5"}
        self.session = "SessionID=fakesession0123456789"
        self.tokens, self.used, self.posts, self.n, self.gets = set(), [], [], 0, []
        self.reject_next_post = False
        self.signal_login = False   # /api/device/signal wants a login (many firmwares)
        self.minimal = False        # an old firmware: none of the Status extras exist
        self.converged = True       # /api/monitoring/converged-status exists (else /api/pin/status)
        self.sim_state = "257"
        self.extras = {
            "/api/device/basic_information": {"productfamily": "LTE", "classify": "hilink", "devicename": "E3372"},
            "/api/net/current-plmn": {"State": "0", "FullName": "Carrier Mobile", "ShortName": "Carrier", "Numeric": "26201", "Rat": "7"},
            "/api/device/signal": {"pci": "123", "cell_id": "12345", "rssi": "-67dBm", "rsrp": "-95dBm", "rsrq": "-10dB",
                                   "sinr": "12dB", "band": "3", "mode": "7"},
            "/api/monitoring/traffic-statistics": {"CurrentConnectTime": "3725", "CurrentUpload": "1048576",
                                                   "CurrentDownload": "12345678", "TotalConnectTime": "99999"},
            "/api/monitoring/month_statistics": {"CurrentMonthDownload": "2345678901", "CurrentMonthUpload": "123456789",
                                                 "MonthDuration": "100000"},
        }
        self.lock = threading.Lock()
        fake = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def reply(self, xml, token=None):
                data = ('<?xml version="1.0" encoding="UTF-8"?>' + xml).encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/xml")
                self.send_header("Content-Length", str(len(data)))
                if token:
                    self.send_header("__RequestVerificationToken", token)
                self.end_headers()
                self.wfile.write(data)

            def error(self, code):
                self.reply("<error><code>%s</code><message></message></error>" % code)

            def do_GET(self):
                if self.path == "/api/webserver/SesTokInfo":
                    tok = fake.new_token()
                    return self.reply("<response><SesInfo>%s</SesInfo><TokInfo>%s</TokInfo></response>" % (fake.session, tok))
                fake.gets.append(self.path)
                if fake.mode == "login":
                    return self.error("100003")
                fields = None
                if self.path == "/api/monitoring/status":
                    fields = fake.status
                elif fake.minimal:
                    pass
                elif self.path == "/api/device/signal" and fake.signal_login:
                    return self.error("100003")
                elif self.path == "/api/monitoring/converged-status" and fake.converged:
                    fields = {"SimState": fake.sim_state, "SimLockStatus": "0", "CurrentLanguage": "en-us"}
                elif self.path == "/api/pin/status":
                    fields = {"SimState": fake.sim_state, "PinOptState": "258", "SimPinTimes": "3", "SimPukTimes": "10"}
                else:
                    fields = fake.extras.get(self.path)
                if fields is None:
                    # what HiLink firmwares answer for an API they do not have
                    return self.error("100002")
                self.reply("<response>%s</response>" % "".join("<%s>%s</%s>" % (k, v, k) for k, v in fields.items()))

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
                tok = self.headers.get("__RequestVerificationToken")
                with fake.lock:
                    valid = self.headers.get("Cookie") == fake.session and tok in fake.tokens
                    if valid:
                        fake.tokens.discard(tok)  # single use
                        fake.used.append(tok)
                if not valid:
                    return self.error("125002")
                if fake.reject_next_post:
                    fake.reject_next_post = False
                    return self.error("125002")
                if fake.mode in ("login", "login-control"):
                    return self.error("100003")
                inner = re.sub(r"^.*<request>(.*)</request>.*$", r"\1", body, flags=re.S)
                fake.posts.append((self.path, inner))
                self.reply("<response>OK</response>", token=fake.new_token())

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.host = "127.0.0.1:%d" % self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def new_token(self):
        with self.lock:
            self.n += 1
            tok = "token%04d" % self.n
            self.tokens.add(tok)
            return tok

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def closed_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return "127.0.0.1:%d" % port


# ---------------------------------------------------------------- fake sysfs, commands, clock

def put(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def make_sysfs(root, extra_devices=(), stick_pid="14dc", iface="eth1", driver="cdc_ether", model="Raspberry Pi 4 Model B Rev 1.4"):
    """a Pi 4: root hub usb1, the VL805 hub 1-1, the stick at 1-1.3 with its network interface"""
    devs = os.path.join(root, "devices", "platform", "scb", "usb1")
    bus = os.path.join(root, "bus", "usb", "devices")
    os.makedirs(bus, exist_ok=True)
    for d in ("cdc_ether", "huawei_cdc_ncm", "usb", "hub"):
        os.makedirs(os.path.join(root, "bus", "usb", "drivers", d), exist_ok=True)
    put(os.path.join(root, "bus", "usb", "drivers", "usb", "bind"), "")
    put(os.path.join(root, "bus", "usb", "drivers", "usb", "unbind"), "")
    put(os.path.join(root, "firmware", "devicetree", "base", "model"), model)

    def device(path, name, vid, pid, cls):
        for k, v in (("idVendor", vid), ("idProduct", pid), ("bDeviceClass", cls), ("authorized", "1")):
            put(os.path.join(path, k), v)
        os.symlink(path, os.path.join(bus, name))

    device(devs, "usb1", "1d6b", "0002", "09")
    device(os.path.join(devs, "1-1"), "1-1", "2109", "3431", "09")
    stick = os.path.join(devs, "1-1", "1-1.3")
    device(stick, "1-1.3", "12d1", stick_pid, "00")
    for name, vid, pid, cls in extra_devices:
        device(os.path.join(devs, "1-1", name), name, vid, pid, cls)
    if iface:
        intf = os.path.join(stick, "1-1.3:1.0")
        os.makedirs(intf)
        os.symlink(os.path.join(root, "bus", "usb", "drivers", driver), os.path.join(intf, "driver"))
        netdir = os.path.join(intf, "net", iface)  # as the kernel lays it out: .../1-1.3:1.0/net/eth1/device -> ../..
        os.makedirs(netdir)
        os.symlink(intf, os.path.join(netdir, "device"))
        os.makedirs(os.path.join(root, "class", "net"), exist_ok=True)
        os.symlink(netdir, os.path.join(root, "class", "net", iface))
    return root


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


class Commands:
    """fake mmcli / nmcli / uhubctl"""

    def __init__(self, mm=None, nm=False, have=("mmcli", "nmcli", "uhubctl")):
        self.mm, self.nm, self.have, self.calls = mm, nm, set(have), []

    def which(self, name):
        return "/usr/bin/" + name if name in self.have else None

    def run(self, cmd, timeout=30):
        self.calls.append(cmd)
        if cmd[:2] == ["mmcli", "-L"]:
            return 0, json.dumps({"modem-list": ["/org/freedesktop/ModemManager1/Modem/0"] if self.mm else []})
        if cmd[0] == "mmcli" and cmd[-1] == "-J":
            return 0, json.dumps({"modem": self.mm or {}})
        if cmd[:3] == ["nmcli", "-t", "-f"] and cmd[3] == "RUNNING":
            return 0, "running\n" if self.nm else "asleep\n"
        if cmd[:3] == ["nmcli", "-t", "-f"]:
            return 0, "teslalinux-lte\nother\n"
        return 0, ""

    def acted(self):
        return [c for c in self.calls if not (c[-1] == "-J" or c[:3] == ["nmcli", "-t", "-f"])]


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="tl-modem-")
        self.env = os.path.join(self.tmp, "env")
        self.lte_env = os.path.join(self.tmp, "lte.env")
        put(self.env, "AP_IFACE=wlan0\nLTE_RECOVERY_POWER_CYCLE=off\n")
        self.api = FakeHiLink()
        self.clock = Clock()
        self.logs, self.writes = [], []

    def tearDown(self):
        self.api.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def recovery(self, sysfs=None, hosts=None, cmds=None):
        self.cmds = cmds or Commands(have=("uhubctl",))
        sysfs = sysfs or make_sysfs(os.path.join(self.tmp, "sys"))
        r = up.ModemRecovery(sysfs=sysfs, env=self.env, lte_env=self.lte_env, clock=self.clock, wall=lambda: self.clock.t + 1.7e9,
                             sleep=lambda s: None, run=self.cmds.run, which=self.cmds.which, log=self.logs.append,
                             hilink_hosts=hosts if hosts is not None else [self.api.host])
        real_write = r.write

        def write(path, value):
            self.writes.append((os.path.relpath(path, sysfs), value))
            real_write(path, value)
        r.write = write
        return r

    def run_until(self, r, end, lte_ok=False, every=10, **snap):
        """tick every EVERY seconds up to END (inclusive); returns {time: what happened} for the actions"""
        events = {}
        while self.clock.t <= end:
            before = (len(self.api.posts), len(self.writes), len(self.cmds.acted()))
            r.tick(dict({"lte_ok": lte_ok, "gateways": {}}, **snap))
            after = (len(self.api.posts), len(self.writes), len(self.cmds.acted()))
            if after != before:
                events[int(self.clock.t - 1000)] = r.last[0]
            self.clock.t += every
        return events


# ---------------------------------------------------------------- tests

class HiLinkLadder(Base):
    def test_no_coverage_no_action(self):
        self.api.status.update(SignalIcon="0", ServiceStatus="1", CurrentNetworkType="0", CurrentNetworkTypeEx="0")
        r = self.recovery()
        self.assertEqual(self.run_until(r, 1000 + 2000), {})
        self.assertEqual(self.api.posts, [])
        self.assertEqual(self.writes, [])
        self.assertEqual(sum("no coverage" in m for m in self.logs), 1)
        st = r.state()
        self.assertEqual((st["type"], st["coverage"], st["lastAction"]), ("hilink", False, None))
        self.assertIn("no coverage", st["status"])

    def test_ladder_steps_and_backoff(self):
        r = self.recovery()
        ev = self.run_until(r, 1000 + 1300)
        # 90 s stuck -> reconnect; 2 min later the modem reboot; 2 min later USB re-enumeration; the
        # power cycle is off; 15 min after the last step the ladder starts again
        self.assertEqual(ev, {90: "reconnect", 210: "modem-reset", 330: "usb-reenumerate", 1230: "reconnect"})
        self.assertEqual(self.api.posts[:3], [("/api/dialup/mobile-dataswitch", "<dataswitch>0</dataswitch>"),
                                              ("/api/dialup/mobile-dataswitch", "<dataswitch>1</dataswitch>"),
                                              ("/api/device/control", "<Control>1</Control>")])
        self.assertEqual(self.writes, [("bus/usb/devices/1-1.3/authorized", "0"), ("bus/usb/devices/1-1.3/authorized", "1")])
        # every POST used its own fresh token
        self.assertEqual(len(set(self.api.used)), len(self.api.used))
        self.assertTrue(any("usb-power-cycle skipped: off" in m for m in self.logs))

    def test_api_dead_goes_to_usb(self):
        r = self.recovery(hosts=[closed_port()])
        ev = self.run_until(r, 1000 + 200)
        self.assertEqual(ev, {90: "usb-reenumerate"})
        st = r.state()
        self.assertEqual((st["type"], st["api"]), ("hilink", "unreachable"))
        self.assertTrue(any("does not answer" in m for m in self.logs))

    def test_stick_without_interface(self):
        """the stick hangs so hard its network interface is gone: found on USB, re-enumerated"""
        sysfs = make_sysfs(os.path.join(self.tmp, "sys"), iface=None)
        r = self.recovery(sysfs=sysfs, hosts=[])
        r.hilink_hosts = None
        self.assertEqual(self.run_until(r, 1000 + 100), {90: "usb-reenumerate"})

    def test_login_required(self):
        self.api.mode = "login"
        r = self.recovery()
        self.assertEqual(self.run_until(r, 1000 + 200), {90: "usb-reenumerate"})
        self.assertEqual(r.state()["api"], "login-required")
        self.assertEqual(self.api.posts, [])

    def test_login_required_for_control_only(self):
        self.api.mode = "login-control"
        r = self.recovery()
        self.assertEqual(self.run_until(r, 1000 + 200), {90: "usb-reenumerate"})
        self.assertEqual(sum("using the USB steps" in m for m in self.logs), 1)
        self.assertEqual(r.state()["api"], "ok")

    def test_stale_token_retried(self):
        self.api.reject_next_post = True
        h = up.HiLink(self.api.host)
        h.post("/api/device/control", "<Control>1</Control>")
        self.assertEqual(self.api.posts, [("/api/device/control", "<Control>1</Control>")])

    def test_power_cycle_auto_only_device(self):
        put(self.env, "LTE_RECOVERY_POWER_CYCLE=auto\n")
        r = self.recovery()
        ev = self.run_until(r, 1000 + 460)
        self.assertEqual(ev[450], "usb-power-cycle")
        self.assertEqual([c for c in self.cmds.calls if c[0] == "uhubctl"], [
            ["uhubctl", "-l", "1-1", "-a", "off", "-r", "3"], ["uhubctl", "-l", "2", "-a", "off", "-r", "3"],
            ["uhubctl", "-l", "2", "-a", "on"], ["uhubctl", "-l", "1-1", "-a", "on"]])

    def test_power_cycle_auto_other_device(self):
        put(self.env, "LTE_RECOVERY_POWER_CYCLE=auto\n")
        sysfs = make_sysfs(os.path.join(self.tmp, "sys"), extra_devices=[("1-1.2", "0781", "5583", "00")])
        r = self.recovery(sysfs=sysfs)
        ev = self.run_until(r, 1000 + 460)
        self.assertNotIn(450, ev)
        self.assertEqual([c for c in self.cmds.calls if c[0] == "uhubctl"], [])
        self.assertTrue(any("other USB devices are plugged in (1-1.2)" in m for m in self.logs))

    def test_power_cycle_on_without_uhubctl(self):
        put(self.env, "LTE_RECOVERY_POWER_CYCLE=on\n")
        r = self.recovery(cmds=Commands(have=()))
        ev = self.run_until(r, 1000 + 460)
        self.assertNotIn(450, ev)
        self.assertTrue(any("uhubctl is not installed" in m for m in self.logs))

    def test_power_cycle_other_board(self):
        put(self.env, "LTE_RECOVERY_POWER_CYCLE=on\n")
        sysfs = make_sysfs(os.path.join(self.tmp, "sys"), model="Some Board")
        r = self.recovery(sysfs=sysfs)
        self.run_until(r, 1000 + 460)
        self.assertEqual([c for c in self.cmds.calls if c[0] == "uhubctl"], [
            ["uhubctl", "-l", "1-1", "-p", "3", "-a", "off", "-r", "3"], ["uhubctl", "-l", "1-1", "-p", "3", "-a", "on"]])

    def test_ladder_resets_after_recovery(self):
        r = self.recovery()
        self.assertEqual(self.run_until(r, 1000 + 90), {90: "reconnect"})
        self.run_until(r, 1000 + 150, lte_ok=True)
        self.assertTrue(any("LTE works again" in m for m in self.logs))
        self.assertEqual(r.step, 0)
        # broken again: 90 s of waiting, then the first step again (not the modem reset)
        ev = self.run_until(r, 1000 + 400)
        self.assertEqual(ev, {250: "reconnect", 370: "modem-reset"})

    def test_stick_gone_while_it_restarts(self):
        """after the modem restart the stick is off USB for a while: the ladder goes on with step 3"""
        r = self.recovery()
        self.assertEqual(self.run_until(r, 1000 + 210), {90: "reconnect", 210: "modem-reset"})
        real = r.observe
        r.observe = lambda gw: dict(real(gw), type="none")
        self.run_until(r, 1000 + 290)
        self.assertEqual(r.state()["status"], "no LTE stick")
        r.observe = real
        self.assertEqual(self.run_until(r, 1000 + 400), {330: "usb-reenumerate"})

    def test_wifi_preferred_and_working(self):
        r = self.recovery()
        self.assertEqual(self.run_until(r, 1000 + 600, wifi_preferred_ok=True), {})
        self.assertIn("preferred Wi-Fi", r.state()["status"])

    def test_recovery_off(self):
        put(self.env, "LTE_RECOVERY=off\n")
        r = self.recovery()
        self.assertEqual(self.run_until(r, 1000 + 600), {})
        self.assertEqual(r.state()["recovery"], "off")

    def test_lte_switched_off(self):
        put(self.lte_env, "LTE_APN='internet'\nLTE_ENABLED='0'\n")
        r = self.recovery()
        self.assertEqual(self.run_until(r, 1000 + 600), {})

    def test_cdc_ether_adapter_is_not_a_modem(self):
        """a USB Ethernet adapter on cdc_ether without a HiLink API: left alone"""
        sysfs = make_sysfs(os.path.join(self.tmp, "sys"), stick_pid="14dc")
        for f in ("idVendor",):
            put(os.path.join(sysfs, "bus", "usb", "devices", "1-1.3", f), "0bda")
        r = self.recovery(sysfs=sysfs, hosts=[closed_port()])
        self.assertEqual(self.run_until(r, 1000 + 600), {})
        self.assertEqual(r.state()["type"], "none")

    def test_state_json(self):
        r = self.recovery()
        self.run_until(r, 1000 + 100)
        st = r.state()
        self.assertEqual(st["type"], "hilink")
        self.assertEqual(st["model"], "E3372")
        self.assertEqual(st["signal"], 80)
        self.assertEqual(st["connection"], "disconnected")
        self.assertEqual(st["api"], "ok")
        self.assertEqual(st["lastAction"], "reconnect")
        self.assertEqual(st["lastResult"], "done")
        self.assertEqual(st["lastActionAt"], int(1000 + 90 + 1.7e9))
        self.assertEqual(st["nextActionAt"], int(1000 + 90 + 120 + 1.7e9))
        self.assertEqual(st["step"], "modem-reset")
        self.assertEqual((st["usb"], st["interface"]), ("1-1.3", "eth1"))
        path = os.path.join(self.tmp, "run", "uplink.json")
        old = up.STATE
        up.STATE = path
        try:
            up.write_state({"time": 1, "modem": st})
        finally:
            up.STATE = old
        with open(path) as f:
            self.assertEqual(json.load(f)["modem"], st)


class StatusDetails(Base):
    def test_all_fields(self):
        self.api.status.update(ConnectionStatus="901", RoamingStatus="1")
        r = self.recovery()
        self.run_until(r, 1000, lte_ok=True)
        st = r.state()
        self.assertEqual((st["bars"], st["signal"], st["networkType"], st["technology"]), (4, 80, "4G", "LTE"))
        self.assertEqual((st["operator"], st["roaming"], st["connection"], st["model"]), ("Carrier Mobile", True, "connected", "E3372"))
        self.assertEqual(st["radio"], {"rsrp": "-95 dBm", "rsrq": "-10 dB", "sinr": "12 dB", "rssi": "-67 dBm",
                                       "cellId": "12345", "band": "B3"})
        self.assertEqual(st["connectedSeconds"], 3725)
        self.assertEqual(st["session"], {"downBytes": 12345678, "upBytes": 1048576, "down": "12 MB", "up": "1.0 MB"})
        self.assertEqual(st["month"], {"downBytes": 2345678901, "upBytes": 123456789, "down": "2.3 GB", "up": "123 MB"})
        self.assertEqual((st["simState"], st["simProblem"], st["status"]), ("ready", None, "working"))
        json.dumps(st)  # it goes into uplink.json as it is

    def test_signal_needs_login(self):
        self.api.signal_login = True
        r = self.recovery()
        self.run_until(r, 1000, lte_ok=True)
        st = r.state()
        self.assertIsNone(st["radio"])
        self.assertEqual((st["operator"], st["session"]["down"]), ("Carrier Mobile", "12 MB"))
        self.assertEqual(self.logs, [])  # left out quietly

    def test_pin_status_fallback(self):
        self.api.converged = False
        r = self.recovery()
        self.run_until(r, 1000, lte_ok=True)
        self.assertEqual(r.state()["simState"], "ready")
        self.assertIn("/api/pin/status", self.api.gets)

    def test_old_firmware_without_extras(self):
        """nothing but the status: every extra is None and recovery works as before"""
        self.api.minimal = True
        r = self.recovery()
        self.assertEqual(self.run_until(r, 1000 + 100), {90: "reconnect"})
        st = r.state()
        for k in ("operator", "radio", "session", "month", "simState", "model", "connectedSeconds"):
            self.assertIsNone(st[k], k)
        self.assertEqual((st["bars"], st["networkType"]), (4, "4G"))

    def test_sim_pin_required_stops_recovery(self):
        self.api.sim_state = "260"
        r = self.recovery()
        self.assertEqual(self.run_until(r, 1000 + 600), {})
        st = r.state()
        self.assertEqual((st["simState"], st["simProblem"]), ("SIM PIN required", "SIM PIN required"))
        self.assertEqual(sum("recovery cannot help" in m for m in self.logs), 1)

    def test_sim_255_with_valid_sim_status_does_not_block(self):
        self.api.sim_state = "255"
        r = self.recovery()
        self.assertEqual(self.run_until(r, 1000 + 100), {90: "reconnect"})
        self.assertEqual((r.state()["simState"], r.state()["simProblem"]), ("no SIM card", None))

    def test_details_polled_every_30_s(self):
        r = self.recovery()
        self.run_until(r, 1000 + 119, lte_ok=True, every=5)
        # ticks every 5 s while LTE works: the status every 30 s, the extras with it
        self.assertEqual(self.api.gets.count("/api/monitoring/status"), 4)
        self.assertEqual(self.api.gets.count("/api/net/current-plmn"), 4)
        self.assertEqual(self.api.gets.count("/api/device/basic_information"), 1)  # the model once

    def test_status_parsing(self):
        f = up.hilink_status_fields
        self.assertEqual(f({"CurrentNetworkTypeEx": "1011"})["networkType"], "4G+")
        self.assertEqual(f({"CurrentNetworkType": "9"})["networkType"], "3G")
        self.assertEqual(f({"CurrentNetworkTypeEx": "0", "CurrentNetworkType": "19"})["networkType"], "no service")
        self.assertFalse(f({"CurrentNetworkTypeEx": "0"})["coverage"])
        self.assertEqual(f({"SignalStrength": "55"})["bars"], 3)
        self.assertEqual(f({"SignalIcon": "2"})["signal"], 40)
        self.assertFalse(f({"ServiceStatus": "1", "SignalIcon": "3"})["coverage"])
        self.assertEqual(f({"ConnectionStatus": "905"})["connection"], "status 905")
        self.assertEqual(f({}), {"signal": None, "coverage": True})
        self.assertEqual([up.human_bytes(n) for n in (0, 999, 1000, 12345, 999999, 1500000000)],
                         ["0 B", "999 B", "1.0 KB", "12 KB", "1.0 MB", "1.5 GB"])
        self.assertEqual(up.radio_value("-95dBm"), "-95 dBm")
        self.assertEqual(up.radio_value(">=-51dBm"), ">=-51dBm")
        self.assertIsNone(up.radio_value(""))


MM_MODEM = {"generic": {"manufacturer": "huawei", "model": "E3372", "state": "registered", "device":
                        "/sys/devices/platform/scb/usb1/1-1/1-1.3", "ports": ["cdc-wdm0 (qmi)", "wwan0 (net)"],
                        "signal-quality": {"value": "60", "recent": "yes"}, "access-technologies": ["lte"]},
            "3gpp": {"registration-state": "home", "operator-name": "Carrier"}}


class ModemManagerLadder(Base):
    def sysfs(self):
        return make_sysfs(os.path.join(self.tmp, "sys"), stick_pid="1506", iface="wwan0", driver="huawei_cdc_ncm")

    def test_ladder(self):
        put(self.lte_env, "LTE_APN='internet'\nLTE_USER=''\nLTE_PASSWORD=''\nLTE_ENABLED='1'\n")
        cmds = Commands(mm=MM_MODEM, nm=False, have=("mmcli", "uhubctl"))
        r = self.recovery(sysfs=self.sysfs(), hosts=[], cmds=cmds)
        r.hilink_hosts = None
        ev = self.run_until(r, 1000 + 400)
        self.assertEqual(ev, {90: "reconnect", 210: "modem-reset", 330: "usb-reenumerate"})
        acted = cmds.acted()
        self.assertEqual(acted[:3], [["mmcli", "-m", "0", "--simple-disconnect"],
                                     ["mmcli", "-m", "0", '--simple-connect=apn="internet"'],
                                     ["mmcli", "-m", "0", "--reset"]])
        st = r.state()
        self.assertEqual((st["type"], st["model"], st["signal"], st["interface"], st["usb"]),
                         ("modemmanager", "huawei E3372", 60, "wwan0", "1-1.3"))
        self.assertEqual((st["bars"], st["networkType"], st["technology"], st["operator"], st["roaming"]),
                         (3, "4G", "LTE", "Carrier", False))

    def test_networkmanager_reconnects(self):
        cmds = Commands(mm=MM_MODEM, nm=True, have=("mmcli", "nmcli"))
        r = self.recovery(sysfs=self.sysfs(), hosts=[], cmds=cmds)
        r.hilink_hosts = None
        self.assertEqual(self.run_until(r, 1000 + 100), {90: "reconnect"})
        self.assertIn(["nmcli", "--wait", "45", "connection", "up", "teslalinux-lte"], cmds.calls)

    def test_searching_is_no_coverage(self):
        m = json.loads(json.dumps(MM_MODEM))
        m["generic"]["state"], m["3gpp"]["registration-state"] = "searching", "searching"
        cmds = Commands(mm=m, have=("mmcli",))
        r = self.recovery(sysfs=self.sysfs(), hosts=[], cmds=cmds)
        r.hilink_hosts = None
        self.assertEqual(self.run_until(r, 1000 + 600), {})

    def test_sim_locked(self):
        m = json.loads(json.dumps(MM_MODEM))
        m["generic"]["state"] = "locked"
        r = self.recovery(sysfs=self.sysfs(), hosts=[], cmds=Commands(mm=m, have=("mmcli",)))
        r.hilink_hosts = None
        self.assertEqual(self.run_until(r, 1000 + 600), {})
        self.assertTrue(any("locked" in x for x in self.logs))

    def test_stick_mode_without_modemmanager(self):
        r = self.recovery(sysfs=self.sysfs(), hosts=[], cmds=Commands(mm=None, have=("mmcli",)))
        r.hilink_hosts = None
        self.assertEqual(self.run_until(r, 1000 + 100), {90: "usb-reenumerate"})


if __name__ == "__main__":
    unittest.main(verbosity=1)
