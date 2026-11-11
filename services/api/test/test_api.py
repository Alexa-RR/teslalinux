#!/usr/bin/env python3
"""api.py against upstream's configuration-manager contract, in-process (stdlib only, no network).

    python3 services/api/test/test_api.py
"""
import json
import os
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
TMP = tempfile.mkdtemp(prefix="tl-api-")
APPLY_LOG = os.path.join(TMP, "apply.log")
with open(os.path.join(TMP, "apply"), "w") as f:
    f.write('#!/bin/sh\necho "$@" >> "%s"\n' % APPLY_LOG)
os.chmod(os.path.join(TMP, "apply"), 0o755)
with open(os.path.join(TMP, "tls.json"), "w") as f:
    json.dump({"notAfter": time.time() + 10 * 86400, "lastResult": "failed", "slot": "current"}, f)
KICK_LOG = os.path.join(TMP, "kick.log")
UPLOADED = os.path.join(TMP, "uploaded.bin")
with open(os.path.join(TMP, "appupdate"), "w") as f:
    # kick: log the arguments; receive-upload: keep stdin and answer like the real one
    f.write('#!/bin/sh\nif [ "$1" = receive-upload ]; then cat > "%s"; echo "{\\"ok\\": true, \\"state\\": \\"staged\\", \\"message\\": \\"$5 $3\\"}"; '
            'exit 0; fi\necho "$@" >> "%s"\n' % (UPLOADED, KICK_LOG))
os.chmod(os.path.join(TMP, "appupdate"), 0o755)
with open(os.path.join(TMP, "appupdate-state.json"), "w") as f:
    json.dump({"installed": {"versionCode": 1030736, "versionName": "5.25.0.1", "signer": "ab"},
               "latest": {"versionCode": 1030800, "versionName": "5.26.0.0", "checkedAt": 1},
               "lastResult": "dispenser-unavailable", "serverUnavailableSince": 1700000000,
               "notices": [{"at": 1, "text": "rolled back"}], "history": [{"secret": "no"}]}, f)
NETWORK_LOG = os.path.join(TMP, "network.log")
with open(os.path.join(TMP, "network"), "w") as f:
    # apply: keep the arguments and the request; lte-state: answer like the real one
    f.write('#!/bin/sh\nif [ "$1" = lte-state ]; then echo \'{"apn": "internet", "user": "", "roaming": false, "enabled": true, '
            '"passwordSet": false, "managed": true}\'; exit 0; fi\n'
            'if [ "$1" = wifi-state ]; then echo \'{"enabled": true, "prefer": "lte", "iface": "wlan1", "present": true, '
            '"networks": [{"ssid": "Home", "hidden": false, "open": false}]}\'; exit 0; fi\n'
            'if [ "$1" = wifi-scan ]; then echo \'{"iface": "wlan1", "networks": [{"ssid": "Garage", "signalDbm": -60, '
            '"secure": true, "band": "2.4 GHz"}]}\'; exit 0; fi\n'
            'echo "$@" >> "%s"; cat >> "%s"; echo >> "%s"\n' % (NETWORK_LOG, NETWORK_LOG, NETWORK_LOG))
os.chmod(os.path.join(TMP, "network"), 0o755)
os.environ.update({
    "TESLALINUX_NETWORK": os.path.join(TMP, "network"),
    "TESLALINUX_SYSUPDATE_DIR": os.path.join(TMP, "sysupdate"),
    "TESLALINUX_SYSUPDATE_LOG": os.path.join(TMP, "sysupdate.log"),
    "TESLALINUX_APPUPDATE": os.path.join(TMP, "appupdate"),
    "TESLALINUX_APPUPDATE_STATE": os.path.join(TMP, "appupdate-state.json"),
    "TESLALINUX_APPUPDATE_SETTINGS": os.path.join(TMP, "state", "appupdate.json"),
    "TESLALINUX_APPUPDATE_MAX_UPLOAD": str(4 << 20),
    "TESLALINUX_ENV": os.path.join(REPO, "host", "phase1-pios", "env"),
    "TESLALINUX_STATE": os.path.join(TMP, "props.json"),
    "TESLALINUX_APPLY_DISPLAY": os.path.join(TMP, "apply"),
    "TESLALINUX_APPLY_SUDO": "0",
    "TESLALINUX_CERT_STATE": os.path.join(TMP, "tls.json"),
})
sys.path.insert(0, os.path.join(REPO, "services", "api"))
import api  # noqa: E402
from http.server import ThreadingHTTPServer  # noqa: E402

SRV = ThreadingHTTPServer(("127.0.0.1", 0), api.Handler)
threading.Thread(target=SRV.serve_forever, daemon=True).start()
BASE = "http://127.0.0.1:%d/api/" % SRV.server_address[1]


def call(route, method="GET", body=None, ctype="text/plain"):
    req = urllib.request.Request(BASE + route, method=method, data=body.encode() if isinstance(body, str) else body)
    if body is not None:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


class Upstream(unittest.TestCase):
    def test_configuration_shape(self):
        code, hdr, body = call("configuration")
        self.assertEqual(code, 200)
        cfg = json.loads(body)
        self.assertEqual(set(cfg), set(api.CONFIGURATION_KEYS))
        self.assertTrue(all(isinstance(v, int) for v in cfg.values()))
        # upstream defaults (vendor.mk) where the env file does not override them
        self.assertEqual(cfg["persist.tesla-android.gps.is_active"], 0)
        self.assertEqual(cfg["persist.tesla-android.browser_audio.is_enabled"], 0)
        self.assertEqual(cfg["persist.tesla-android.offline-mode.telemetry.is_enabled"], 1)
        self.assertEqual(cfg["persist.tesla-android.softap.channel"], 44)
        self.assertEqual(hdr.get("Access-Control-Allow-Origin"), "*")

    def test_text_plain_posts(self):
        for route, value, key in (("gpsState", "1", "persist.tesla-android.gps.is_active"),
                                  ("offlineModeTelemetryState", "0", "persist.tesla-android.offline-mode.telemetry.is_enabled"),
                                  ("softApChannel", "157", "persist.tesla-android.softap.channel"),
                                  ("browserAudioVolume", "120", "persist.tesla-android.browser_audio.volume")):
            code, _, body = call(route, "POST", value)
            self.assertEqual((code, body), (200, b"OK"), route)
            self.assertEqual(json.loads(call("configuration")[2])[key], int(value))

    def test_display_state(self):
        code, _, body = call("displayState")
        ds = json.loads(body)
        self.assertEqual(list(ds), ["width", "height", "density", "refreshRate", "quality", "resolutionPreset",
                                    "renderer", "isResponsive", "isH264", "isHeadless", "isRearDisplayEnabled",
                                    "isRearDisplayPrioritised"])
        self.assertEqual((ds["width"], ds["height"], ds["isHeadless"]), (1088, 832, 1))
        # missing fields -> 500 like upstream
        self.assertEqual(call("displayState", "POST", '{"width": 1088}', "application/json")[0], 500)
        self.assertEqual(call("displayState", "POST", "nonsense", "application/json")[0], 500)
        payload = {"width": 1280, "height": 768, "density": 175, "resolutionPreset": 1, "renderer": 1,
                   "isResponsive": 1, "isH264": 1, "quality": 80, "refreshRate": 60,
                   "isRearDisplayEnabled": 0, "isRearDisplayPrioritised": 0}
        code, _, body = call("displayState", "POST", json.dumps(payload), "application/json")
        self.assertEqual((code, body), (200, b"OK"))
        ds = json.loads(call("displayState")[2])
        self.assertEqual((ds["width"], ds["height"], ds["renderer"], ds["quality"]), (1280, 768, 1, 80))
        for _ in range(50):
            if os.path.exists(APPLY_LOG):
                break
            time.sleep(0.05)
        with open(APPLY_LOG) as f:
            self.assertIn("1280 768 175 60 m2m-h264 80", f.read())

    def test_health_options_404(self):
        code, hdr, body = call("health")
        self.assertEqual((code, body), (200, b""))
        code, hdr, _ = call("gpsState", "OPTIONS")
        self.assertEqual(code, 204)
        self.assertEqual(hdr.get("Access-Control-Max-Age"), "1728000")
        self.assertEqual(call("nope")[0], 404)
        self.assertEqual(call("nope", "POST", "1")[0], 404)

    def test_device_info(self):
        info = json.loads(call("deviceInfo")[2])
        self.assertEqual(set(info), {"cpu_temperature", "serial_number", "device_model", "is_modem_detected",
                                     "is_carplay_detected", "release_type", "ota_url"})
        self.assertEqual(len(info["serial_number"]), 8)

    def test_certificate(self):
        st = json.loads(call("certificate")[2])
        self.assertTrue(9.0 < st["daysLeft"] < 10.1)
        self.assertEqual(st["slot"], "current")
        self.assertEqual(call("certificate", "POST", "x")[0], 404)  # no write route
        self.assertEqual(call("tls/csr")[0], 404)  # the per-device CSR route is gone

    def test_updater_absent(self):
        self.assertEqual(call("openUpdater")[0], 501)


class AppUpdate(unittest.TestCase):
    """teslalinux routes for tools/appupdate (not upstream)."""

    def test_status_shape(self):
        st = json.loads(call("appUpdate")[2])
        self.assertEqual(st["settings"], {"source": "apkpure", "autoUpdate": True, "allowMetered": False,
                                          "playEnabled": False, "dispenserUrl": ""})
        self.assertEqual(st["maxUpload"], 4 << 20)
        self.assertEqual(st["installed"], {"versionCode": 1030736, "versionName": "5.25.0.1"})  # no signer, no history
        self.assertEqual(st["latest"]["versionName"], "5.26.0.0")
        self.assertEqual(st["serverUnavailableSince"], 1700000000)
        self.assertNotIn("history", st)

    def test_settings_roundtrip_and_validation(self):
        ok = call("appUpdateSettings", "POST", json.dumps({"allowMetered": True}), "application/json")
        self.assertEqual(ok[0], 200)
        url = "https://dispenser.example.org/api/auth"
        self.assertEqual(call("appUpdateSettings", "POST", json.dumps({"dispenserUrl": url}), "application/json")[0], 200)
        for src in ("manual", "play", "apkpure"):
            self.assertEqual(call("appUpdateSettings", "POST", json.dumps({"source": src}), "application/json")[0], 200)
        self.assertEqual(call("appUpdateSettings", "POST", json.dumps({"playEnabled": True}), "application/json")[0], 200)
        st = json.loads(call("appUpdate")[2])["settings"]
        self.assertEqual(st, {"source": "apkpure", "autoUpdate": True, "allowMetered": True, "playEnabled": True, "dispenserUrl": url})
        for bad in ({"source": "apkmirror"}, {"playEnabled": "yes"}, {"allowMetered": 1}, {"dispenserUrl": "http://x.org/"}, {"dispenserUrl": "https://a b"},
                    {"dispenserUrl": "https://u:p@x.org/"}, {"other": True}, {}, [], "x"):
            self.assertEqual(call("appUpdateSettings", "POST", json.dumps(bad), "application/json")[0], 500, bad)
        self.assertEqual(call("appUpdateSettings", "POST", json.dumps({"dispenserUrl": "", "allowMetered": False}),
                              "application/json")[0], 200)
        self.assertEqual(json.loads(call("appUpdate")[2])["settings"]["dispenserUrl"], "")
        self.assertEqual(call("appUpdateSettings", "POST", json.dumps({"playEnabled": False}), "application/json")[0], 200)

    def test_upload_streams_into_the_root_helper(self):
        data = os.urandom(3 << 20)
        code, _, body = call("appUpdateUpload?name=Waze%205.26.apkm", "POST", data, "application/octet-stream")
        self.assertEqual(code, 200, body)
        res = json.loads(body)
        self.assertEqual(res["state"], "staged")
        self.assertEqual(res["message"], "Waze 5.26.apkm %d" % len(data))  # the file name and the exact size
        with open(UPLOADED, "rb") as f:
            self.assertEqual(f.read(), data)
        # over the limit: refused from the headers alone, before any body is read
        import http.client
        c = http.client.HTTPConnection("127.0.0.1", SRV.server_address[1], timeout=5)
        c.putrequest("POST", "/api/appUpdateUpload?name=x.apkm")
        c.putheader("Content-Length", str((4 << 20) + 1))
        c.endheaders()
        r = c.getresponse()
        self.assertEqual(r.status, 413)
        c.close()
        self.assertEqual(call("appUpdateUpload", "OPTIONS")[0], 204)

    def test_check_now_kicks(self):
        self.assertEqual(call("appUpdateCheck", "POST", "1")[0], 200)
        for _ in range(50):
            if os.path.exists(KICK_LOG):
                break
            time.sleep(0.05)
        with open(KICK_LOG) as f:
            self.assertEqual(f.read().strip(), "kick --force")
        self.assertEqual(call("appUpdateCheck", "OPTIONS")[0], 204)


class Settings(unittest.TestCase):
    """The settings page's Status, Wi-Fi & LTE and System update routes"""

    def test_iw_parsing(self):
        info = api.parse_iw_info("Interface wlan0\n\tssid Teslalinux\n\ttype AP\n"
                                 "\tchannel 44 (5220 MHz), width: 80 MHz, center1: 5210 MHz\n")
        self.assertEqual(info, {"ssid": "Teslalinux", "mode": "AP", "channel": 44, "frequencyMhz": 5220, "widthMhz": 80})
        st = api.parse_stations("Station 4c:fc:aa:01:02:03 (on wlan0)\n\tinactive time:\t120 ms\n"
                                "\tsignal:  \t-52 [-55, -54] dBm\n\ttx bitrate:\t433.3 MBit/s VHT-MCS 9\n"
                                "\trx bitrate:\t390.0 MBit/s\n\tconnected time:\t3605 seconds\n")
        self.assertEqual(st, [{"mac": "4c:fc:aa:01:02:03", "inactiveMs": 120, "signalDbm": -52, "txMbps": 433.3,
                               "rxMbps": 390.0, "connectedSeconds": 3605}])

    def test_status_shape(self):
        st = json.loads(call("status")[2])
        for k in ("system", "hotspot", "uplink", "internet", "modem", "displayViewers", "gps", "app"):
            self.assertIn(k, st)
        self.assertEqual(st["hotspot"]["ssid"], "Teslalinux")

    def test_network(self):
        st = json.loads(call("network")[2])
        self.assertEqual(st["hotspot"]["ssid"], "Teslalinux")
        self.assertNotIn("psk", st["hotspot"])  # the password never leaves the device
        self.assertEqual(st["lte"]["apn"], "internet")
        req = {"when": "boot", "hotspot": {"ssid": "Car", "psk": "longenough", "country": "DE"},
               "lte": {"apn": "web.vodafone.de", "roaming": True}}
        code, _h, body = call("network", "POST", json.dumps(req), "application/json")
        self.assertEqual((code, json.loads(body)["ok"]), (200, True))
        with open(NETWORK_LOG) as f:
            log = f.read()
        self.assertIn("apply boot", log)
        sent = [json.loads(line) for line in log.splitlines() if line.startswith("{")]
        self.assertEqual([r for r in sent if "hotspot" in r][-1]["hotspot"]["psk"], "longenough")
        for bad in ({"when": "later"}, {"when": "now", "hotspot": {"psk": "short"}},
                    {"when": "now", "hotspot": {"ssid": "x" * 33}}, {"when": "now", "hotspot": {"country": "de"}},
                    {"when": "now", "lte": {"apn": "bad apn"}}, {"when": "now", "lte": {"roaming": "yes"}}):
            self.assertEqual(call("network", "POST", json.dumps(bad), "application/json")[0], 400, bad)

    def test_backup_wifi(self):
        st = json.loads(call("network")[2])
        self.assertEqual(st["wifi"]["networks"][0]["ssid"], "Home")
        self.assertNotIn("psk", st["wifi"]["networks"][0])
        self.assertEqual(json.loads(call("wifiScan")[2])["networks"][0]["ssid"], "Garage")
        ok = {"when": "now", "wifi": {"add": {"ssid": "Garage", "psk": "garagepass", "hidden": False, "open": False},
                                      "prefer": "wifi", "enabled": True}}
        self.assertEqual(call("network", "POST", json.dumps(ok), "application/json")[0], 200)
        with open(NETWORK_LOG) as f:
            self.assertEqual(json.loads(f.read().splitlines()[-1])["wifi"]["prefer"], "wifi")
        for bad in ({"add": {"ssid": ""}}, {"add": {"ssid": "x", "psk": "short"}}, {"prefer": "both"},
                    {"enabled": "yes"}, {"remove": 3}, "x"):
            self.assertEqual(call("network", "POST", json.dumps({"when": "now", "wifi": bad}), "application/json")[0], 400, bad)

    def test_system_update_state(self):
        os.makedirs(os.path.join(TMP, "sysupdate"), exist_ok=True)
        with open(os.path.join(TMP, "sysupdate", "state.json"), "w") as f:
            json.dump({"phase": "upgrading", "lastCheck": 5, "available": [{"name": "nginx", "from": "1", "to": "2", "held": False}]}, f)
        st = json.loads(call("systemUpdate")[2])
        self.assertEqual(st["phase"], "idle")  # no run holds the lock: a stale phase is not reported
        self.assertFalse(st["running"])
        self.assertEqual(st["available"][0]["name"], "nginx")
        self.assertEqual(call("systemUpdate", "POST", json.dumps({"action": "rm -rf"}), "application/json")[0], 500)


if __name__ == "__main__":
    unittest.main(verbosity=1)
