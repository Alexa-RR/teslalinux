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
            # wifi-connect: keep the request; "Home" starts, anything else is not saved
            'if [ "$1" = wifi-connect ]; then r=$(cat); echo "connect $r" >> "%s"; case "$r" in *\'"Home"\'*) '
            'echo \'{"ok": true, "message": "Connecting"}\';; *) echo \'{"ok": false, "message": "That network is not saved"}\';; '
            'esac; exit 0; fi\n' % NETWORK_LOG +
            'echo "$@" >> "%s"; cat >> "%s"; echo >> "%s"\n' % (NETWORK_LOG, NETWORK_LOG, NETWORK_LOG))
os.chmod(os.path.join(TMP, "network"), 0o755)
# the app interface helper: keep the arguments
IFACE_LOG = os.path.join(TMP, "iface.log")
with open(os.path.join(TMP, "set-app-interface"), "w") as f:
    f.write('#!/bin/sh\necho "$@" >> "%s"\n' % IFACE_LOG)
os.chmod(os.path.join(TMP, "set-app-interface"), 0o755)
# the map colour manifest and an installed app with one unpacked skin file
SKIN = b"local Palette = { map_background = rgb(0xFAFCFA), }\n"
os.makedirs(os.path.join(TMP, "appdata", "base.apk_", "skins"))
os.makedirs(os.path.join(TMP, "apps"))
with open(os.path.join(TMP, "mapskin.json"), "w") as f:
    import hashlib
    json.dump({"overlays": [{"package": "com.waze", "path": "skins/day.lua", "replace": [["0xFAFCFA", "0xE4E5E7"]],
                             "verified": [{"versionCode": 1030736, "versionName": "5.25.0.1",
                                           "sha256": hashlib.sha256(SKIN).hexdigest()}]}]}, f)
BOOT_CONFIG = b"arm_64bit=1\n# >>> teslalinux (tools/pios/install.sh rewrites this block) >>>\n[pi4]\nboot_delay=0\n[all]\n# <<< teslalinux <<<\n"
with open(os.path.join(TMP, "config.txt"), "wb") as f:
    f.write(BOOT_CONFIG)
# the test sound's player (pw-play): keep the arguments and what it was given, then play for a second
AUDIO_TEST_LOG = os.path.join(TMP, "audiotest.log")
AUDIO_TEST_WAV = os.path.join(TMP, "audiotest.wav")
with open(os.path.join(TMP, "pw-play"), "w") as f:
    f.write('#!/bin/sh\necho "$@" > "%s"\ncat > "%s"\nsleep 1\n' % (AUDIO_TEST_LOG, AUDIO_TEST_WAV))
os.chmod(os.path.join(TMP, "pw-play"), 0o755)
# teslalinux-carkey's loopback API, faked: its state, and the requests it got
from http.server import BaseHTTPRequestHandler as _BH, ThreadingHTTPServer as _TS  # noqa: E402
CARKEY_GOT = []
CARKEY_STATE = {"vin": "5YJ3E1EA0KF000001", "keyReady": True, "link": "connected", "message": "Reading the car",
                "drive": {"at": 1, "destination": "Herzl St 10", "destLat": 31.7683, "destLon": 35.2137},
                "location": None}


class _FakeCarkey(_BH):
    def log_message(self, *a):
        pass

    def _reply(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        self._reply(200, CARKEY_STATE)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"null")
        CARKEY_GOT.append((self.path, body))
        if self.path == "/vin" and body.get("vin") != "5YJ3E1EA0KF000001":
            return self._reply(400, {"ok": False, "message": "a VIN has 17 letters and digits (no I, O or Q)"})
        if self.path == "/pair":
            return self._reply(200, {"ok": True, "message": "Sent. Tap your key card"})
        self._reply(200, {"ok": True, "message": "Saved"})


CARKEY = _TS(("127.0.0.1", 0), _FakeCarkey)
threading.Thread(target=CARKEY.serve_forever, daemon=True).start()
os.environ.update({
    "TESLALINUX_CARKEY_URL": "http://127.0.0.1:%d" % CARKEY.server_address[1],
    "TESLALINUX_CARSYNC_SETTINGS": os.path.join(TMP, "state", "carsync.json"),
    "TESLALINUX_CARSYNC_STATUS": os.path.join(TMP, "carsync-status.json"),
    "TESLALINUX_AUDIO_TEST_PLAYER": os.path.join(TMP, "pw-play"),
    "TESLALINUX_SET_GPU_SPEED": os.path.join(REPO, "host", "teslalinux-set-gpu-speed"),
    "TESLALINUX_GPU_ENV": os.path.join(TMP, "gpu.env"),
    "TESLALINUX_BOOT_CONFIG": os.path.join(TMP, "config.txt"),
    "TESLALINUX_SET_APP_INTERFACE": os.path.join(TMP, "set-app-interface"),
    "TESLALINUX_INTERFACE_ENV": os.path.join(TMP, "interface.env"),
    "TESLALINUX_APP_DIR": os.path.join(TMP, "apps"),
    "ANDROID_APP_DATA_DIR": os.path.join(TMP, "appdata"),
    "TESLALINUX_MAP_SKIN_MANIFEST": os.path.join(TMP, "mapskin.json"),
    "TESLALINUX_NETWORK": os.path.join(TMP, "network"),
    "TESLALINUX_WIFI_CONNECT_STATE": os.path.join(TMP, "wifi-connect.json"),
    "TESLALINUX_UPLINK_STATE": os.path.join(TMP, "uplink.json"),
    "TESLALINUX_SYSUPDATE_DIR": os.path.join(TMP, "sysupdate"),
    "TESLALINUX_SYSUPDATE_LOG": os.path.join(TMP, "sysupdate.log"),
    "TESLALINUX_APPUPDATE": os.path.join(TMP, "appupdate"),
    "TESLALINUX_APPUPDATE_STATE": os.path.join(TMP, "appupdate-state.json"),
    "TESLALINUX_APPUPDATE_SETTINGS": os.path.join(TMP, "state", "appupdate.json"),
    "TESLALINUX_APPUPDATE_MAX_UPLOAD": str(4 << 20),
    "TESLALINUX_AUDIO_SETTINGS": os.path.join(TMP, "state", "audio.json"),
    "TESLALINUX_AUDIO_STATUS": os.path.join(TMP, "audio-status.json"),
    "TESLALINUX_ENV": os.path.join(REPO, "host", "phase1-pios", "env"),
    "TESLALINUX_STATE": os.path.join(TMP, "props.json"),
    "TESLALINUX_APPLY_DISPLAY": os.path.join(TMP, "apply"),
    "TESLALINUX_APPLY_SUDO": "0",
    "TESLALINUX_CERT_STATE": os.path.join(TMP, "tls.json"),
    "TESLALINUX_BOOTTIME_STATE": os.path.join(TMP, "boottime.json"),
    "TESLALINUX_BOOT_ID": os.path.join(TMP, "boot_id"),
    "TESLALINUX_DIST_MANIFEST": os.path.join(TMP, "MANIFEST"),
})
with open(os.path.join(TMP, "boot_id"), "w") as f:
    f.write("b2\n")
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
        self.assertEqual(cfg["persist.tesla-android.gps.is_active"], 1)  # ours: on (tlprops.UPSTREAM_DEFAULTS)
        self.assertEqual(cfg["persist.tesla-android.browser_audio.is_enabled"], 1)  # ours: on, like GPS
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
                   "isResponsive": 1, "isH264": 1, "quality": 80, "refreshRate": 60}
        # upstream's frontend also sends the rear-display fields: numbers are accepted and not kept
        bad_rear = dict(payload, isRearDisplayEnabled="1")
        self.assertEqual(call("displayState", "POST", json.dumps(bad_rear), "application/json")[0], 500)
        upstream_payload = dict(payload, isRearDisplayEnabled=1, isRearDisplayPrioritised=1)
        code, _, body = call("displayState", "POST", json.dumps(upstream_payload), "application/json")
        self.assertEqual((code, body), (200, b"OK"))
        code, _, body = call("displayState", "POST", json.dumps(payload), "application/json")
        self.assertEqual((code, body), (200, b"OK"))
        ds = json.loads(call("displayState")[2])
        self.assertEqual((ds["width"], ds["height"], ds["renderer"], ds["quality"]), (1280, 768, 1, 80))
        self.assertEqual((ds["isRearDisplayEnabled"], ds["isRearDisplayPrioritised"]), (0, 0))
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
                                          "playEnabled": False, "dispenserUrl": "", "pinVersionCode": 0, "pinVersionName": ""})
        # the versions offered to pin: the map frame rate's verified ones, then the installed one
        self.assertEqual(st["pinChoices"], [{"versionCode": 1030750, "versionName": "5.27.5.0", "reason": "mapPacing"},
                                            {"versionCode": 1030736, "versionName": "5.25.0.1", "reason": "installed"}])
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
        self.assertEqual(st, {"source": "apkpure", "autoUpdate": True, "allowMetered": True, "playEnabled": True, "dispenserUrl": url,
                              "pinVersionCode": 0, "pinVersionName": ""})
        for bad in ({"source": "apkmirror"}, {"playEnabled": "yes"}, {"allowMetered": 1}, {"dispenserUrl": "http://x.org/"}, {"dispenserUrl": "https://a b"},
                    {"dispenserUrl": "https://u:p@x.org/"}, {"other": True}, {}, [], "x"):
            self.assertEqual(call("appUpdateSettings", "POST", json.dumps(bad), "application/json")[0], 500, bad)
        self.assertEqual(call("appUpdateSettings", "POST", json.dumps({"dispenserUrl": "", "allowMetered": False}),
                              "application/json")[0], 200)
        self.assertEqual(json.loads(call("appUpdate")[2])["settings"]["dispenserUrl"], "")
        self.assertEqual(call("appUpdateSettings", "POST", json.dumps({"playEnabled": False}), "application/json")[0], 200)

    def test_version_pin(self):
        def post(obj):
            return call("appUpdateSettings", "POST", json.dumps(obj), "application/json")[0]

        def kicks():
            try:
                with open(KICK_LOG) as f:
                    return f.read().count("kick --force")
            except OSError:
                return 0

        def wait_kicks(n):
            for _ in range(50):
                if kicks() >= n:
                    break
                time.sleep(0.05)
            return kicks()
        before = kicks()
        self.assertEqual(post({"pinVersionCode": 1030750, "pinVersionName": "5.27.5.0"}), 200)
        st = json.loads(call("appUpdate")[2])["settings"]
        self.assertEqual((st["pinVersionCode"], st["pinVersionName"]), (1030750, "5.27.5.0"))
        self.assertEqual(wait_kicks(before + 1), before + 1)  # a changed pin is looked up at once
        self.assertEqual(post({"pinVersionCode": 1030750, "pinVersionName": "5.27.5.0"}), 200)
        time.sleep(0.2)
        self.assertEqual(kicks(), before + 1)  # unchanged: no extra check
        for bad in ({"pinVersionCode": -1}, {"pinVersionCode": True}, {"pinVersionCode": "1030750"}, {"pinVersionCode": 1 << 31},
                    {"pinVersionName": "5.27; rm"}, {"pinVersionName": 5}):
            self.assertEqual(post(bad), 500, bad)
        self.assertEqual(post({"pinVersionCode": 0}), 200)  # back to the latest; the name goes with the pin
        st = json.loads(call("appUpdate")[2])["settings"]
        self.assertEqual((st["pinVersionCode"], st["pinVersionName"]), (0, ""))
        self.assertEqual(wait_kicks(before + 2), before + 2)

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


class AppInterface(unittest.TestCase):
    """The app's interface and its car map's colours (teslalinux-set-app-interface, map_skin_status)"""

    def installed(self, vc):
        with open(os.path.join(TMP, "apps", "installed.json"), "w") as f:
            json.dump({"package": "com.waze", "versionCode": vc, "versionName": "x"}, f)

    def skin(self, data):
        path = os.path.join(TMP, "appdata", "base.apk_", "skins", "day.lua")
        if data is None:
            if os.path.exists(path):
                os.unlink(path)
        else:
            with open(path, "wb") as f:
                f.write(data)

    def state(self):
        return json.loads(call("appInterface")[2])

    def test_map_skin_status(self):
        with open(os.path.join(TMP, "interface.env"), "w") as f:
            f.write("TESLALINUX_APP_INTERFACE=car\nTESLALINUX_MAP_COLOURS=graphite\n")
        self.installed(1030736)
        self.skin(SKIN)
        st = self.state()
        self.assertEqual((st["interface"], st["mapColours"]), ("car", "graphite"))
        self.assertEqual((st["mapSkin"]["state"], st["mapSkin"]["supported"]), ("verified", True))
        self.assertEqual(st["mapSkin"]["verifiedVersions"], [{"versionCode": 1030736, "versionName": "5.25.0.1"}])
        self.skin(None)  # not unpacked yet: the app does it at its next start, ATL checks it then
        self.assertEqual((self.state()["mapSkin"]["state"], self.state()["mapSkin"]["supported"]), ("pending", True))
        self.skin(SKIN.replace(b"FAFCFA", b"FFFFFF"))  # same version, other contents
        self.assertEqual((self.state()["mapSkin"]["state"], self.state()["mapSkin"]["supported"]), ("unverified-files", False))
        self.skin(SKIN)
        self.installed(1030800)  # an update to a version nobody checked
        st = self.state()["mapSkin"]
        self.assertEqual((st["state"], st["supported"], st["versionCode"]), ("unverified-version", False, 1030800))
        os.unlink(os.path.join(TMP, "apps", "installed.json"))
        self.assertEqual(self.state()["mapSkin"]["state"], "not-installed")
        with open(os.path.join(TMP, "interface.env"), "w") as f:
            f.write("TESLALINUX_APP_INTERFACE=phone\n")
        self.assertIsNone(self.state()["mapColours"])  # never chosen: the page keeps its own choice

    def test_set_map_colours(self):
        if os.path.exists(IFACE_LOG):
            os.unlink(IFACE_LOG)
        self.assertEqual(call("appInterface", "POST", json.dumps({"mapColours": "graphite"}), "application/json")[0], 200)
        for bad in ({"mapColours": "pink"}, {"interface": "tablet"}, {}, {"other": 1}, []):
            self.assertEqual(call("appInterface", "POST", json.dumps(bad), "application/json")[0], 500, bad)
        for _ in range(50):
            if os.path.exists(IFACE_LOG):
                break
            time.sleep(0.05)
        with open(IFACE_LOG) as f:
            self.assertEqual(f.read().strip(), "map graphite")

    def test_map_pacing(self):
        with open(os.path.join(TMP, "interface.env"), "w") as f:
            f.write("TESLALINUX_APP_INTERFACE=car\n")
        self.installed(1030750)  # Waze 5.27.5.0, on tools/apps/waze-map-pacing.py's list
        st = self.state()
        self.assertEqual(st["mapPacing"], "auto")  # never chosen: Automatic
        self.assertEqual((st["mapPacingSupport"]["state"], st["mapPacingSupport"]["supported"]), ("verified", True))
        self.assertEqual(st["mapPacingSupport"]["modes"], ["auto", "full"])
        self.assertIn({"versionCode": 1030750, "versionName": "5.27.5.0"}, st["mapPacingSupport"]["verifiedVersions"])
        for chosen in ("full", "app", "auto"):
            with open(os.path.join(TMP, "interface.env"), "w") as f:
                f.write("TESLALINUX_APP_INTERFACE=car\nTESLALINUX_MAP_PACING=%s\n" % chosen)
            self.assertEqual(self.state()["mapPacing"], chosen)
        with open(os.path.join(TMP, "interface.env"), "w") as f:
            f.write("TESLALINUX_APP_INTERFACE=car\nTESLALINUX_MAP_PACING=fast\n")
        self.assertEqual(self.state()["mapPacing"], "auto")  # not a choice: the default
        self.installed(1030800)
        st = self.state()["mapPacingSupport"]
        self.assertEqual((st["state"], st["supported"], st["modes"]), ("unverified-version", False, []))
        os.unlink(os.path.join(TMP, "apps", "installed.json"))
        self.assertEqual(self.state()["mapPacingSupport"]["state"], "not-installed")

    def test_speedometer(self):
        prefs = os.path.join(TMP, "appdata", "base.apk_", "shared_prefs")
        os.makedirs(prefs, exist_ok=True)
        path = os.path.join(prefs, "com.waze.config.offline.xml")
        saved = os.path.join(TMP, "appdata", "teslalinux-speedometer.json")
        self.installed(1030750)  # Waze 5.27.5.0, on tools/apps/waze-speedometer.py's list
        if os.path.exists(path):
            os.unlink(path)
        st = self.state()["speedometerSupport"]
        self.assertEqual((st["state"], st["supported"], st["hidden"]), ("verified", True, False))
        self.assertIn({"versionCode": 1030750, "versionName": "5.27.5.0"}, st["verifiedVersions"])
        self.assertNotIn("signStyle", st)  # the app has not mirrored its settings yet
        with open(path, "w") as f:
            f.write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                    '    <string name="CONFIG_VALUE_MAP_SPEEDOMETER_STYLE">us</string>\n'
                    '    <long name="CONFIG_VALUE_MAP_SPEEDOMETER_SPEED_ALERT_OFFSET" value="5" />\n</map>\n')
        with open(saved, "w") as f:
            json.dump({"row": ["user", "Map", "Show speedometer V4"], "value": None}, f)
        st = self.state()["speedometerSupport"]
        self.assertEqual((st["signStyle"], st["alertOffset"], st["hidden"]), ("us", 5, True))
        os.unlink(saved)
        with open(path, "w") as f:
            f.write('<map><string name="CONFIG_VALUE_MAP_SPEEDOMETER_STYLE">world</string></map>')
        self.assertEqual(self.state()["speedometerSupport"]["signStyle"], "world")
        self.installed(1030800)
        st = self.state()["speedometerSupport"]
        self.assertEqual((st["state"], st["supported"]), ("unverified-version", False))
        os.unlink(os.path.join(TMP, "apps", "installed.json"))
        self.assertEqual(self.state()["speedometerSupport"]["state"], "not-installed")
        os.unlink(path)

    def test_set_map_pacing(self):
        if os.path.exists(IFACE_LOG):
            os.unlink(IFACE_LOG)
        for bad in ({"mapPacing": "fast"}, {"mapPacing": 60}, {"mapPacing": "Auto"}):
            self.assertEqual(call("appInterface", "POST", json.dumps(bad), "application/json")[0], 500, bad)
        for chosen in ("full", "auto"):
            if os.path.exists(IFACE_LOG):
                os.unlink(IFACE_LOG)
            self.assertEqual(call("appInterface", "POST", json.dumps({"mapPacing": chosen}), "application/json")[0], 200)
            for _ in range(50):
                if os.path.exists(IFACE_LOG):
                    break
                time.sleep(0.05)
            with open(IFACE_LOG) as f:
                self.assertEqual(f.read().strip(), "pacing " + chosen)


class GpuSpeed(unittest.TestCase):
    """/api/gpuSpeed through host/teslalinux-set-gpu-speed on a copy of the firmware's config.txt"""

    def test_choose_and_back(self):
        cfg = os.path.join(TMP, "config.txt")
        st = json.loads(call("gpuSpeed")[2])
        self.assertEqual(st["speed"], "standard")
        code, _, body = call("gpuSpeed", "POST", json.dumps({"speed": "fastest"}), "application/json")
        self.assertEqual(code, 200)
        st = json.loads(body)
        self.assertEqual((st["speed"], st["pendingRestart"]), ("fastest", True))  # booted at standard
        with open(cfg, "rb") as f:
            text = f.read()
        self.assertTrue(text.startswith(BOOT_CONFIG))  # the installer's block stays, the GPU block follows
        self.assertIn(b"v3d_freq=700\nover_voltage=2\n[all]\n# <<< teslalinux gpu <<<", text)
        call("gpuSpeed", "POST", json.dumps({"speed": "faster"}), "application/json")
        with open(cfg, "rb") as f:
            text = f.read()
        self.assertEqual(text.count(b"teslalinux gpu"), 2)  # one block, replaced
        self.assertIn(b"v3d_freq=600", text)
        self.assertNotIn(b"over_voltage", text)
        call("gpuSpeed", "POST", json.dumps({"speed": "standard"}), "application/json")
        with open(cfg, "rb") as f:
            self.assertEqual(f.read(), BOOT_CONFIG)
        for bad in ({"speed": "turbo"}, {}, []):
            self.assertEqual(call("gpuSpeed", "POST", json.dumps(bad), "application/json")[0], 500, bad)


class AudioOutput(unittest.TestCase):
    """/api/audioOutput: the settings audio.py follows, and its status"""

    def post(self, req):
        return call("audioOutput", "POST", json.dumps(req), "application/json")

    def test_defaults_choice_and_validation(self):
        settings = os.path.join(TMP, "state", "audio.json")
        status = os.path.join(TMP, "audio-status.json")
        for p in (settings, status):
            if os.path.exists(p):
                os.remove(p)
        st = json.loads(call("audioOutput")[2])
        self.assertEqual(st, {"output": "car", "volume": 80, "speaker": None, "speakers": [], "playing": False,
                              "running": False})
        # audio.py's status: the jack is there, nothing chosen yet
        jack = {"name": "alsa_output.platform-fe00b840.mailbox.stereo-fallback", "description": "Built-in Audio Stereo",
                "kind": "jack"}
        with open(status, "w") as f:
            json.dump({"output": "car", "volume": 80, "speaker": None, "speakers": [jack], "playing": False}, f)
        code, _, body = self.post({"output": "both"})
        self.assertEqual(code, 200)
        st = json.loads(body)
        # the status is still the old choice's: the output that will be chosen is shown
        self.assertEqual((st["output"], st["volume"], st["speaker"], st["running"]), ("both", 80, jack, True))
        with open(settings) as f:
            self.assertEqual(json.load(f), {"output": "both", "volume": 80})
        usb = {"name": "alsa_output.usb-Generic_USB_Audio-00.analog-stereo", "description": "USB Audio",
               "kind": "usb"}
        with open(status, "w") as f:
            json.dump({"output": "both", "volume": 35, "speaker": usb, "speakers": [usb, jack], "playing": True}, f)
        st = json.loads(self.post({"volume": 35})[2])
        self.assertEqual((st["output"], st["volume"], st["speaker"], st["playing"]), ("both", 35, usb, True))
        st = json.loads(self.post({"output": "car"})[2])
        self.assertEqual((st["output"], st["volume"], st["speaker"]), ("car", 35, None))
        for bad in ({}, {"output": "hdmi"}, {"volume": 101}, {"volume": -1}, {"volume": True}, {"volume": 5.5},
                    {"output": "pi", "extra": 1}, [], "car"):
            self.assertEqual(self.post(bad)[0], 500, bad)
        with open(settings) as f:
            self.assertEqual(json.load(f), {"output": "car", "volume": 35})

    def test_test_sound(self):
        """POST /api/audioTest: the chime as a WAV on the player's stdin, into the app's sink; one at a time"""
        code, _, body = call("audioTest", "POST", "{}", "application/json")
        self.assertEqual(code, 200)
        res = json.loads(body)
        self.assertFalse(res["already"])
        self.assertGreater(res["seconds"], 1)
        self.assertIn(res["audio"]["output"], ("car", "pi", "both"))
        # the fake player holds on for a second: a second press meanwhile plays no second chime
        self.assertTrue(json.loads(call("audioTest", "POST", "{}", "application/json")[2])["already"])
        for _ in range(50):
            if os.path.exists(AUDIO_TEST_WAV) and os.path.getsize(AUDIO_TEST_WAV) == len(api.audio_test_wav()):
                break
            time.sleep(0.1)
        with open(AUDIO_TEST_LOG) as f:
            self.assertEqual(f.read().split(), ["--target", "android", "-"])
        with open(AUDIO_TEST_WAV, "rb") as f:
            wav = f.read()
        self.assertEqual(wav[:4] + wav[8:16], b"RIFFWAVEfmt ")
        self.assertEqual(int.from_bytes(wav[24:28], "little"), 48000)


class CarLink(unittest.TestCase):
    """/api/carLink: teslalinux-carkey's state and requests, and the follow settings"""

    def post(self, req):
        code, _, body = call("carLink", "POST", json.dumps(req), "application/json")
        return code, (json.loads(body) if code == 200 else None)

    def test_state_settings_and_forwarding(self):
        with open(os.path.join(TMP, "carsync-status.json"), "w") as f:
            json.dump({"sent": {"name": "Herzl St 10", "lat": 31.7683, "lon": 35.2137},
                       "lastAction": {"at": 1, "text": "Following", "ok": True}, "gpsFeeding": False}, f)
        st = json.loads(call("carLink")[2])
        self.assertTrue(st["running"])
        self.assertEqual(st["car"]["link"], "connected")
        self.assertEqual(st["settings"], {"follow": True, "carGps": False})
        self.assertEqual(st["sync"]["sent"]["name"], "Herzl St 10")
        code, res = self.post({"carGps": True})
        self.assertEqual((code, res["ok"], res["state"]["settings"]), (200, True, {"follow": True, "carGps": True}))
        self.assertEqual(oct(os.stat(os.path.join(TMP, "state", "carsync.json")).st_mode & 0o777), "0o644")
        code, res = self.post({"vin": "5YJ3E1EA0KF000001"})
        self.assertEqual((code, res["ok"]), (200, True))
        code, res = self.post({"vin": "nope"})
        self.assertEqual((code, res["ok"]), (200, False))
        self.assertIn("17 letters", res["message"])
        code, res = self.post({"action": "pair"})
        self.assertEqual((res["ok"], res["message"]), (True, "Sent. Tap your key card"))
        self.post({"action": "forget"})
        self.assertEqual([p for p, _ in CARKEY_GOT[-4:]], ["/vin", "/vin", "/pair", "/forget"])
        for bad in ({}, {"action": "unlock"}, {"follow": "yes"}, {"vin": 5}, {"follow": True, "action": "pair"}, []):
            self.assertEqual(self.post(bad)[0], 500, bad)
        self.post({"carGps": False})

    def test_not_running(self):
        saved = api.CARKEY_URL
        api.CARKEY_URL = "http://127.0.0.1:9"
        try:
            st = json.loads(call("carLink")[2])
            self.assertEqual((st["running"], st["car"]), (False, None))
            self.assertEqual(self.post({"action": "pair"})[1]["message"], "The car link service is not running.")
        finally:
            api.CARKEY_URL = saved


class BootTime(unittest.TestCase):
    """status.system.boot from host/teslalinux-boottime's file, and how that file is made"""

    def setUp(self):
        import importlib.util
        from importlib.machinery import SourceFileLoader
        loader = SourceFileLoader("boottime", os.path.join(REPO, "host", "teslalinux-boottime"))
        self.bt = importlib.util.module_from_spec(importlib.util.spec_from_loader("boottime", loader))
        loader.exec_module(self.bt)
        self.path = os.environ["TESLALINUX_BOOTTIME_STATE"]
        if os.path.exists(self.path):
            os.remove(self.path)

    def boot(self):
        return json.loads(call("status")[2])["system"]["boot"]

    def test_journal_lines(self):
        found = {"appFrameSeconds": None, "appMapSeconds": None}
        for line in ["[    3.100000] pi systemd[1]: Started teslalinux-atl.service",
                     "[   27.612345] pi teslalinux-atl[812]: SurfaceView: surfaceChanged 1216x928",
                     "[   28.000000] pi teslalinux-atl[812]: SurfaceView: surfaceChanged 1216x928",
                     "[   41.349000] pi teslalinux-atl[812]: WAZE: Map shown and ready",
                     "garbage without a time"]:
            self.bt.app_line(line, found, 180)
        self.assertEqual(found, {"appFrameSeconds": 27.61, "appMapSeconds": 41.35})
        # a later start of the app (after the cap) is not the boot's
        late = {"appFrameSeconds": None, "appMapSeconds": None}
        self.assertTrue(self.bt.app_line("[   12.000000] pi teslalinux-atl[999]: starting", late, 180))
        self.assertFalse(self.bt.app_line("[  400.000000] pi teslalinux-atl[999]: Map shown and ready", late, 180))
        self.assertEqual(late["appMapSeconds"], None)
        self.assertEqual((self.bt.secs(2208208), self.bt.secs(0), self.bt.secs(None)), (2.21, None, None))

    def test_history_one_per_boot_newest_first(self):
        state = {}
        for i in range(12):
            state = self.bt.merge(state, {"bootId": "b%d" % i, "at": 1000 + i, "pageSeconds": 11.0 + i, "final": True})
        # the same boot again (values arriving) replaces its entry instead of adding one
        state = self.bt.merge(state, {"bootId": "b11", "at": 1011, "pageSeconds": 11.4, "appMapSeconds": 41.3})
        self.assertEqual(len(state["history"]), 10)
        self.assertEqual([h["bootId"] for h in state["history"][:3]], ["b11", "b10", "b9"])
        self.assertEqual((state["history"][0]["pageSeconds"], state["history"][0]["appMapSeconds"]), (11.4, 41.3))
        self.assertEqual(state["current"]["bootId"], "b11")

    def test_status_field(self):
        self.assertIsNone(self.boot())  # never measured: the page leaves the group out
        rec = {"bootId": "b2", "at": 1795000000, "kernelSeconds": 2.21, "userspaceSeconds": 25.47, "hotspotSeconds": 7.81,
               "pageSeconds": 8.36, "appFrameSeconds": 27.61, "appMapSeconds": None, "firmwareCounted": False, "final": False}
        state = self.bt.merge({"history": [{"bootId": "b1", "at": 1794990000, "pageSeconds": 8.5, "appMapSeconds": 40.1}]}, rec)
        with open(self.path, "w") as f:
            json.dump(state, f)
        b = self.boot()
        self.assertEqual((b["kernelSeconds"], b["hotspotSeconds"], b["pageSeconds"], b["appFrameSeconds"], b["appMapSeconds"]),
                         (2.21, 7.81, 8.36, 27.61, None))
        self.assertEqual((b["final"], b["firmwareCounted"], b["at"]), (False, False, 1795000000))
        self.assertEqual([h["pageSeconds"] for h in b["history"]], [8.36, 8.5])
        self.assertNotIn("bootId", b["history"][0])
        # after a restart the file still holds the last boot: history only until it is measured again
        with open(os.environ["TESLALINUX_BOOT_ID"], "w") as f:
            f.write("b3\n")
        try:
            b = self.boot()
            self.assertEqual((b["pageSeconds"], b["final"], len(b["history"])), (None, False, 2))
        finally:
            with open(os.environ["TESLALINUX_BOOT_ID"], "w") as f:
                f.write("b2\n")
        # a broken file is no field, not an error
        with open(self.path, "w") as f:
            f.write("{nope")
        self.assertIsNone(self.boot())


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

    def test_teslalinux_release(self):
        path = os.environ["TESLALINUX_DIST_MANIFEST"]
        sysst = lambda: json.loads(call("status")[2])["system"]["teslalinux"]  # noqa: E731
        self.assertIsNone(sysst())  # no MANIFEST: an install from a repository copy
        try:
            with open(path, "w") as f:
                f.write("# teslalinux dist\nformat=1\nversion=20261124-00a574a\nrelease=v0.4.0\nrelease=v9\n")
            self.assertEqual(sysst(), {"release": "v0.4.0", "version": "20261124-00a574a"})
            with open(path, "w") as f:
                f.write("version=local build\nrelease=\n")  # a local pack: no tag; a value that is not a name
            self.assertEqual(sysst(), {"release": None, "version": None})
        finally:
            os.remove(path)

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

    def test_wifi_connect(self):
        post = lambda req: call("wifiConnect", "POST", json.dumps(req), "application/json")  # noqa: E731
        code, _h, body = post({"ssid": "Home"})
        self.assertEqual((code, json.loads(body)), (200, {"ok": True, "message": "Connecting"}))
        with open(NETWORK_LOG) as f:
            self.assertIn('connect {"ssid": "Home"}', f.read())
        code, _h, body = post({"ssid": "Nowhere"})
        self.assertEqual((code, json.loads(body)["message"]), (409, "That network is not saved"))
        for bad in ({"ssid": ""}, {"ssid": "x" * 33}, {"ssid": "a\nb"}, {"ssid": 3}, "Home", None):
            self.assertEqual(post(bad)[0], 400, bad)
        path = os.environ["TESLALINUX_WIFI_CONNECT_STATE"]
        self.assertEqual(json.loads(call("wifiConnect")[2]), {"attempt": None, "current": None})
        now = int(time.time())
        with open(path, "w") as f:
            json.dump({"ssid": "Home", "state": "connecting", "started": now, "pid": 42}, f)
        with open(os.environ["TESLALINUX_UPLINK_STATE"], "w") as f:
            json.dump({"time": now, "wifi": {"ssid": "Home", "signalDbm": -58}}, f)
        st = json.loads(call("wifiConnect")[2])
        self.assertEqual((st["attempt"]["state"], st["current"]), ("connecting", {"ssid": "Home", "signalDbm": -58}))
        self.assertNotIn("pid", st["attempt"])
        with open(path, "w") as f:  # an attempt that never finished is reported as stopped
            json.dump({"ssid": "Home", "state": "connecting", "started": now - 600}, f)
        self.assertEqual(json.loads(call("wifiConnect")[2])["attempt"]["state"], "failed")
        os.remove(path)
        os.remove(os.environ["TESLALINUX_UPLINK_STATE"])

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
