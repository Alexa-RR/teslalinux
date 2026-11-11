#!/usr/bin/env python3
"""teslalinux /api on 127.0.0.1:8081: upstream's tesla-android-configuration-manager, route for route.

Mirrors upstream/android-external-tesla-android-configuration-manager/tesla-android-configuration-manager.cpp
so the vendored frontend in web/ runs unchanged:

* GET  /api/configuration   flat JSON of the persist.tesla-android.* properties as numbers (cpp:394-421)
* GET  /api/displayState    width/height/density/refreshRate/quality/resolutionPreset/renderer/
                            isResponsive/isH264/isHeadless/isRearDisplay* as numbers (cpp:591-615)
* POST /api/displayState    JSON with all eleven numeric fields, else 500 (cpp:617-669); then the
                            display is reconfigured (configure_virtual_display, cpp:184-268)
* POST /api/<setting>       the raw text/plain body becomes the property value, reply "OK" (cpp:423-590)
* GET  /api/deviceInfo      cpu_temperature, serial_number, device_model, is_modem_detected,
                            is_carplay_detected, release_type, ota_url (cpp:358-384)
* GET  /api/health          200, empty body (cpp:386-388)
* GET  /api/openUpdater     upstream opens Android's system-update screen; see do_GET
* OPTIONS on every route    204 preflight (cpp:160-165); every response carries the CORS headers of
                            the post-routing handler (cpp:673-678)

An addition that changes no upstream shape (a new route, read-only, public data):
* GET /api/certificate  the state of the car's TLS certificate as written by host/tls/teslalinux-tls
                        (served slot, expiry, per-slot status, last fetch result; lastCheck,
                        lastChange, checkSource "dns" | "full") plus daysLeft; the launcher warns
                        from it. There is no write route for anything TLS.

The app updater (tools/appupdate/teslalinux-appupdate, docs/APP-UPDATES.md), new routes only:
* GET  /api/appUpdate          settings + state: installed/latest/pending version, last check and result,
                               "update server unavailable since", rollback notices
* POST /api/appUpdateSettings  JSON with any of source ("apkpure" | "manual" | "play"), autoUpdate,
                               allowMetered, playEnabled (bools), dispenserUrl ("" = Aurora's default,
                               else https://...); 500 on anything else
* POST /api/appUpdateCheck     check now (`teslalinux-appupdate kick --force` through one sudoers rule)
* POST /api/appUpdateUpload?name=FILE   a bundle (.apkm/.xapk/.apks/.zip/.apk, at most 400 MB) streamed
                               into `teslalinux-appupdate receive-upload` (root, sudoers); answers its
                               verification result as JSON {ok, state, message}

The keyboard (new routes only):
* GET  /api/keyboard   {"mode": "builtin" | "device"}: the app's own on-screen keyboard, or the keyboard
                       of the device showing the page (the page then types through tacomp's
                       /sockets/keyboard)
* POST /api/keyboard   JSON {"mode": ...}: `teslalinux-set-keyboard` (one sudoers rule) writes
                       /etc/teslalinux/keyboard.env and restarts the app if the choice changed

The settings page's Status, Network and System update sections (new routes only):
* GET  /api/status        what the device is connected to now: the hotspot (SSID, channel, the car and
                          other clients with signal and DHCP name), the internet uplink (interface, LTE /
                          Wi-Fi / Ethernet, address, bytes since boot, reachability), the LTE modem
                          (HiLink web API or ModemManager: operator, technology, signal), open car pages,
                          the last GPS fix, the app, and the system (OS, kernel, uptime, load, memory,
                          disk, CPU temperature, Pi throttling)
* GET  /api/network       hotspot name and country (the password never leaves the device), LTE settings
                          (APN, user, roaming, enabled; no password) from `teslalinux-network lte-state`
* POST /api/network       JSON {when: "now"|"boot", hotspot: {ssid, psk, country}, lte: {apn, user,
                          password, roaming, enabled, clearPassword}}: `teslalinux-network apply` (root,
                          sudoers) writes the env file / LTE profile; answers {ok, message}
* GET  /api/wifiScan      the networks the backup Wi-Fi card sees (`teslalinux-network wifi-scan`)
  POST /api/network's "wifi": {enabled, prefer: "lte"|"wifi", add: {ssid, psk, hidden, open}, remove}: the
                          backup Wi-Fi (a second card joins a saved network; host/teslalinux-uplink routes
                          through it when LTE has no internet); /api/status's "failover" is that daemon's state
* GET  /api/systemUpdate  the OS updater's state (teslalinux-system-update: apt or apk), updates
                          available, last check/upgrade, reboot needed, log tail, current uplink kind
* POST /api/systemUpdate  JSON {action: "check"|"upgrade"|"reboot"}; 409 while a run is going on

Settings are stored by tlprops (property names and string values as upstream). The hotspot band,
channel and offline-mode switches take effect at the next boot (host/teslalinux-render), exactly as
the frontend says ("Apply it during the next system startup?").
"""
import json
import os
import re
import socket
import subprocess
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import tlprops as tp

PORT = int(os.environ.get("TESLALINUX_API_PORT", "8081"))
BIND = os.environ.get("TESLALINUX_API_BIND", "127.0.0.1")
APPLY_DISPLAY = os.environ.get("TESLALINUX_APPLY_DISPLAY", "/usr/local/bin/teslalinux-apply-display")
# teslalinux extension (not in upstream's API): restart the app from the settings page
RESTART_APP = os.environ.get("TESLALINUX_RESTART_APP", "/usr/local/bin/teslalinux-restart-app")
# teslalinux extension: the device's language/region and time zone (settings page, "Language & region")
SET_LOCALE = os.environ.get("TESLALINUX_SET_LOCALE", "/usr/local/bin/teslalinux-set-locale")
SET_KEYBOARD = os.environ.get("TESLALINUX_SET_KEYBOARD", "/usr/local/bin/teslalinux-set-keyboard")
KEYBOARD_ENV = os.environ.get("TESLALINUX_KEYBOARD_ENV", "/etc/teslalinux/keyboard.env")
KEYBOARD_MODES = ("builtin", "device")
LOCALE_ENV = os.environ.get("TESLALINUX_LOCALE_ENV", "/etc/teslalinux/locale.env")
# the helper restarts services as root: through one sudoers rule (host setup), off for tests
APPLY_SUDO = os.environ.get("TESLALINUX_APPLY_SUDO", "1") != "0"
CERT_STATE = os.environ.get("TESLALINUX_CERT_STATE", "/var/lib/teslalinux/tls-state/tls.json")
APPUPDATE = os.environ.get("TESLALINUX_APPUPDATE", "/usr/local/bin/teslalinux-appupdate")
APPUPDATE_STATE = os.environ.get("TESLALINUX_APPUPDATE_STATE", "/var/lib/teslalinux/appupdate/state.json")
APPUPDATE_SETTINGS = os.environ.get("TESLALINUX_APPUPDATE_SETTINGS", "/var/lib/teslalinux/state/appupdate.json")

ENV = tp.load_env()
_lock = threading.Lock()
_apply_lock = threading.Lock()

# POST /api/<route> -> property; the body is stored verbatim like property_set(key, req.body)
TEXT_ROUTES = {
    "overrideReleaseType": tp.RELEASE_TYPE,
    "overrideOtaUrl": tp.OTA_URL,
    "gpsState": tp.GPS_ACTIVE,
    "browserAudioState": tp.AUDIO_ENABLED,
    "browserAudioVolume": tp.AUDIO_VOLUME,
    "softApBand": tp.BAND_TYPE,
    "softApChannel": tp.CHANNEL,
    "softApChannelWidth": tp.CHANNEL_WIDTH,
    "softApState": tp.SOFTAP_ENABLED,
    "offlineModeState": tp.OFFLINE,
    "offlineModeTelemetryState": tp.OFFLINE_TELEMETRY,
    "offlineModeTeslaFirmwareDownloads": tp.OFFLINE_FIRMWARE,
}
# upstream property_set() rejects values longer than PROPERTY_VALUE_MAX - 1 (91 bytes)
PROPERTY_VALUE_MAX = 92

CONFIGURATION_KEYS = (tp.BAND_TYPE, tp.CHANNEL, tp.CHANNEL_WIDTH, tp.SOFTAP_ENABLED, tp.OFFLINE,
                      tp.OFFLINE_TELEMETRY, tp.OFFLINE_FIRMWARE, tp.AUDIO_ENABLED, tp.AUDIO_VOLUME,
                      tp.GPS_ACTIVE, tp.GPS_HW)
DISPLAY_FIELDS = (("width", tp.VD_WIDTH), ("height", tp.VD_HEIGHT), ("density", tp.VD_DENSITY),
                  ("resolutionPreset", tp.VD_PRESET), ("renderer", tp.VD_RENDERER),
                  ("isResponsive", tp.VD_RESPONSIVE), ("isH264", tp.VD_H264), ("refreshRate", tp.VD_REFRESH),
                  ("quality", tp.VD_QUALITY), ("isRearDisplayEnabled", tp.VD_REAR),
                  ("isRearDisplayPrioritised", tp.VD_REAR_PRIO))
ROUTES_GET = {"configuration", "displayState", "deviceInfo", "health", "openUpdater", "certificate", "appState",
              "appUpdate", "locale", "keyboard", "status", "network", "systemUpdate", "wifiScan"}
ROUTES_POST = set(TEXT_ROUTES) | {"displayState", "appRestart", "appUpdateSettings", "appUpdateCheck", "appUpdateUpload",
                                  "locale", "keyboard", "network", "systemUpdate"}


def props():
    with _lock:
        return tp.load(ENV)


def set_props(changes):
    with _lock:
        tp.save_changes(changes)


# ---------------------------------------------------------------------------- deviceInfo

def _read(path):
    try:
        with open(path, "rb") as f:
            return f.read().decode("utf-8", "replace").strip("\0\n ")
    except OSError:
        return None


def cpu_temperature():
    # get_cpu_temperature(): thermal_zone0 in millidegrees, -1 if unreadable
    v = _read("/sys/class/thermal/thermal_zone0/temp")
    try:
        return int(v) // 1000
    except (TypeError, ValueError):
        return -1


def serial_number():
    # get_serial_number(): fscanf("%x") of the devicetree serial into a uint32, printed %08x
    s = _read("/sys/firmware/devicetree/base/serial-number")
    if s:
        try:
            return "%08x" % (int(s, 16) & 0xFFFFFFFF)
        except ValueError:
            pass
    mid = _read("/etc/machine-id") or _read("/var/lib/dbus/machine-id")
    return mid[:8] if mid else "00000000"


def device_model():
    # ro.product.model upstream is "rpi4" / "cm4" (the frontend maps those two to marketing names)
    model = _read("/sys/firmware/devicetree/base/model") or ""
    if "Compute Module 4" in model:
        return "cm4"
    if "Raspberry Pi 4" in model or "Raspberry Pi 400" in model:
        return "rpi4"
    if "Compute Module 5" in model:
        return "cm5"
    if "Raspberry Pi 5" in model:
        return "rpi5"
    return model or os.uname().machine


# USB network drivers of the modems upstream supports (docs/UPSTREAM-NOTES.md "LTE modems"). Upstream
# probes the HiLink web UI (192.168.1.1/192.168.8.1:80) plus eth1/eth2 because on Android every modem
# becomes ethN; on Linux the interface's driver says it directly.
MODEM_DRIVERS = {"cdc_ether", "cdc_ncm", "huawei_cdc_ncm", "rndis_host", "qmi_wwan", "cdc_mbim", "ipheth"}


def modem_detected():
    try:
        names = os.listdir("/sys/class/net")
    except OSError:
        return 0
    for name in names:
        drv = os.path.realpath("/sys/class/net/%s/device/driver" % name)
        if os.path.basename(drv) in MODEM_DRIVERS and (_read("/sys/class/net/%s/operstate" % name) or "") != "down":
            return 1
    return 0


def usb_present(vendor, product):
    # is_usb_device_present(): scan /sys/bus/usb/devices for idVendor/idProduct
    base = "/sys/bus/usb/devices"
    try:
        entries = os.listdir(base)
    except OSError:
        return 0
    for e in entries:
        if _read(os.path.join(base, e, "idVendor")) == vendor and _read(os.path.join(base, e, "idProduct")) == product:
            return 1
    return 0


def device_info(p):
    return {
        "cpu_temperature": cpu_temperature(),
        "serial_number": serial_number(),
        "device_model": device_model(),
        "is_modem_detected": modem_detected(),
        "is_carplay_detected": 1 if (usb_present("1314", "1520") or usb_present("1314", "1521")) else 0,
        "release_type": p.get(tp.RELEASE_TYPE, "release"),
        "ota_url": p.get(tp.OTA_URL, ""),
    }


# ---------------------------------------------------------------------------- display

def encoder_for(renderer):
    # renderer 0 = MJPEG into <img>; 1 = H.264 WebCodecs; 2 = H.264 Broadway. tacomp's m2m-* modes use
    # the Pi's V4L2 encoders; where there is none (the test VM), m2m-jpeg falls back to the CPU JPEG
    # path and m2m-h264 to software H.264 (x264), so an H.264 renderer always gets H.264.
    return "m2m-jpeg" if renderer == 0 else "m2m-h264"


_display_pending = {"args": None}
_display_cond = threading.Condition()
DISPLAY_SETTLE_S = float(os.environ.get("TESLALINUX_DISPLAY_SETTLE", "1.5"))


def _display_worker():
    """Applies display states one at a time, each only after DISPLAY_SETTLE_S without a newer one:
    a page that resizes several times in a row (fullscreen, rotation, a phone's address bar) posts
    a state per step, and each applied state restarts the compositor and the app; only the last
    one counts (upstream's configure_virtual_display applies them as they come, but its resize does
    not restart the app)."""
    while True:
        with _display_cond:
            while _display_pending["args"] is None:
                _display_cond.wait()
            # settle: wait until no newer state arrived for the settle time
            while True:
                args = _display_pending["args"]
                _display_cond.wait(DISPLAY_SETTLE_S)
                if _display_pending["args"] is args:
                    break
            _display_pending["args"] = None
        cmd = ["sudo", "-n", APPLY_DISPLAY] + args if APPLY_SUDO and os.geteuid() != 0 else [APPLY_DISPLAY] + args
        with _apply_lock:
            try:
                subprocess.run(cmd, check=False, timeout=120)
            except (OSError, subprocess.SubprocessError) as e:
                print("teslalinux-api: %s failed: %s" % (APPLY_DISPLAY, e), flush=True)


threading.Thread(target=_display_worker, daemon=True, name="display-apply").start()


def apply_display(p):
    """configure_virtual_display(): resize the compositor (and the app on it) to the new state,
    after the state settled (see _display_worker)."""
    args = [str(tp.atoi(p[tp.VD_WIDTH])), str(tp.atoi(p[tp.VD_HEIGHT])), str(tp.atoi(p[tp.VD_DENSITY])),
            str(tp.atoi(p[tp.VD_REFRESH])), encoder_for(tp.atoi(p[tp.VD_RENDERER])), str(tp.atoi(p[tp.VD_QUALITY]))]
    with _display_cond:
        _display_pending["args"] = args
        _display_cond.notify_all()


_restart_lock = threading.Lock()
_restart_state = {"requested": 0.0, "pending": False}


def restart_app():
    """POST /api/appRestart: restart the app's service (teslalinux-restart-app, as root through one
    sudoers rule). Asynchronous like apply_display: the reply goes out before the app is gone. A
    request while one restart is still running is folded into it."""
    with _restart_lock:
        if _restart_state["pending"]:
            return
        _restart_state["pending"] = True
        _restart_state["requested"] = time.time()
    cmd = ["sudo", "-n", RESTART_APP] if APPLY_SUDO and os.geteuid() != 0 else [RESTART_APP]

    def run():
        try:
            subprocess.run(cmd + ["restart"], check=False, timeout=120)
        except (OSError, subprocess.SubprocessError) as e:
            print("teslalinux-api: %s failed: %s" % (RESTART_APP, e), flush=True)
        finally:
            with _restart_lock:
                _restart_state["pending"] = False

    threading.Thread(target=run, daemon=True).start()


def app_state():
    """GET /api/appState: {"state": active|activating|inactive|failed|restarting|unknown, "requested": unix
    time of the last restart request or 0}. The service state needs no privileges to read."""
    with _restart_lock:
        pending, requested = _restart_state["pending"], _restart_state["requested"]
    state = "restarting" if pending else "unknown"
    if not pending:
        try:
            out = subprocess.run([RESTART_APP, "status"], capture_output=True, text=True, timeout=10).stdout
            state = (out.strip().splitlines() or ["unknown"])[-1] or "unknown"
        except (OSError, subprocess.SubprocessError):
            pass
    return {"state": state, "requested": int(requested)}


# ---------------------------------------------------------------------------- app updates

APPUPDATE_DEFAULTS = {"source": "apkpure", "autoUpdate": True, "allowMetered": False, "playEnabled": False, "dispenserUrl": ""}
APPUPDATE_SOURCES = ("apkpure", "manual", "play")
APPUPDATE_MAX_UPLOAD = int(os.environ.get("TESLALINUX_APPUPDATE_MAX_UPLOAD", str(400 << 20)))
APPUPDATE_DEFAULT_DISPENSER = "https://auroraoss.com/api/auth"
# the same rule as teslalinux-appupdate's valid_dispenser_url (it checks again before use)
DISPENSER_RE = re.compile(r"https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~%!$&'()*+,;=:@/-]*)?")
_appupdate_lock = threading.Lock()


def _read_json(path):
    try:
        with open(path) as f:
            v = json.load(f)
        return v if isinstance(v, dict) else None
    except (OSError, ValueError):
        return None


def appupdate_settings():
    s = dict(APPUPDATE_DEFAULTS)
    raw = _read_json(APPUPDATE_SETTINGS) or {}
    for k in ("autoUpdate", "allowMetered", "playEnabled"):
        if isinstance(raw.get(k), bool):
            s[k] = raw[k]
    if raw.get("source") in APPUPDATE_SOURCES:
        s["source"] = raw["source"]
    if isinstance(raw.get("dispenserUrl"), str) and (raw["dispenserUrl"] == "" or DISPENSER_RE.fullmatch(raw["dispenserUrl"])):
        s["dispenserUrl"] = raw["dispenserUrl"]
    return s


def appupdate_state():
    """GET /api/appUpdate: what the settings page shows. The updater's state file is root's, world-readable;
    nothing secret is in it (the dispensed Play login lives in /etc/teslalinux/play, root only)."""
    st = _read_json(APPUPDATE_STATE) or {}

    def ver(v):
        return {"versionCode": v.get("versionCode"), "versionName": v.get("versionName")} if isinstance(v, dict) else None
    return {
        "settings": appupdate_settings(),
        "defaultDispenser": APPUPDATE_DEFAULT_DISPENSER,
        "installed": ver(st.get("installed")),
        "latest": ver(st.get("latest")),
        "pending": ver(st.get("pending")),
        "health": ver(st.get("health")),
        "lastCheck": st.get("lastCheck") or 0,
        "lastCheckOk": st.get("lastCheckOk") or 0,
        "lastResult": st.get("lastResult") or "never-run",
        "lastMessage": st.get("lastMessage") or "",
        "serverUnavailableSince": st.get("serverUnavailableSince"),
        "nextTryAt": st.get("nextTryAt") or 0,
        "notices": (st.get("notices") or [])[-5:],
        "bad": st.get("bad") or {},
        "upload": _read_json(os.path.join(os.path.dirname(APPUPDATE_STATE), "upload.json")),
        "maxUpload": APPUPDATE_MAX_UPLOAD,
    }


def set_appupdate_settings(req):
    """POST /api/appUpdateSettings: False (-> 500) unless every given field is valid."""
    if not isinstance(req, dict) or not req or set(req) - set(APPUPDATE_DEFAULTS):
        return False
    for k in ("autoUpdate", "allowMetered", "playEnabled"):
        if k in req and not isinstance(req[k], bool):
            return False
    if "source" in req and req["source"] not in APPUPDATE_SOURCES:
        return False
    if "dispenserUrl" in req:
        u = req["dispenserUrl"]
        if not isinstance(u, str) or len(u) > 256 or not (u == "" or DISPENSER_RE.fullmatch(u)):
            return False
    with _appupdate_lock:
        s = appupdate_settings()
        s.update(req)
        d = os.path.dirname(APPUPDATE_SETTINGS)
        os.makedirs(d, exist_ok=True)
        tmp = APPUPDATE_SETTINGS + ".tmp"
        with open(tmp, "w") as f:
            json.dump(s, f)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, 0o644)
        os.replace(tmp, APPUPDATE_SETTINGS)
    return True


def appupdate_upload(handler, length, name):
    """POST /api/appUpdateUpload?name=FILE: the body (a bundle, up to APPUPDATE_MAX_UPLOAD) is streamed
    straight into `teslalinux-appupdate receive-upload` running as root (one sudoers rule), which stores
    it root-only, unpacks, verifies and stages it. This process never writes the file anywhere. Returns
    (http status, result dict)."""
    name = re.sub(r"[^A-Za-z0-9._ ()+-]", "_", os.path.basename(name or "bundle"))[:120] or "bundle"
    base = [APPUPDATE, "receive-upload", "--size", str(length), "--name", name]
    cmd = ["sudo", "-n"] + base if APPLY_SUDO and os.geteuid() != 0 else base
    try:
        proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    except OSError as e:
        return 500, {"ok": False, "state": "error", "message": "cannot start the updater: %s" % e}
    left, broken = length, False
    while left > 0:
        buf = handler.rfile.read(min(1 << 20, left))
        if not buf:
            break
        left -= len(buf)
        if not broken:
            try:
                proc.stdin.write(buf)
            except (BrokenPipeError, OSError):
                broken = True  # keep draining the request so the connection stays usable
    try:
        proc.stdin.close()
    except OSError:
        pass
    try:
        out, _ = proc.communicate(timeout=900)
    except subprocess.TimeoutExpired:
        proc.kill()
        return 500, {"ok": False, "state": "error", "message": "verification took too long"}
    try:
        res = json.loads(out.decode("utf-8", "replace").strip().splitlines()[-1])
    except (ValueError, IndexError):
        return 500, {"ok": False, "state": "error", "message": "the updater gave no result (exit %d)" % proc.returncode}
    if left:
        res = {"ok": False, "state": "rejected", "message": "the upload ended early (%d bytes missing)" % left}
    return 200, res


def appupdate_check_now():
    """POST /api/appUpdateCheck: the updater runs as root; one sudoers rule allows exactly this command."""
    cmd = ["sudo", "-n", APPUPDATE, "kick", "--force"] if APPLY_SUDO and os.geteuid() != 0 else [APPUPDATE, "kick", "--force"]

    def run():
        try:
            subprocess.run(cmd, check=False, timeout=60)
        except (OSError, subprocess.SubprocessError) as e:
            print("teslalinux-api: %s failed: %s" % (APPUPDATE, e), flush=True)

    threading.Thread(target=run, daemon=True).start()


# languages/regions offered on the settings page (Android's own locale list is per app; this is the
# set Waze and the car's markets need, the current value is always offered too)
LOCALES = ("en_US", "en_GB", "en_IL", "en_AU", "en_CA", "en_IE", "en_NZ", "en_IN", "en_ZA", "he_IL", "ar_IL",
           "ar_AE", "ar_SA", "ru_RU", "ru_IL", "uk_UA", "de_DE", "de_AT", "de_CH", "fr_FR", "fr_BE", "fr_CA",
           "fr_CH", "it_IT", "es_ES", "es_MX", "es_AR", "pt_PT", "pt_BR", "nl_NL", "nl_BE", "pl_PL", "cs_CZ",
           "sk_SK", "hu_HU", "ro_RO", "bg_BG", "el_GR", "tr_TR", "sv_SE", "nb_NO", "da_DK", "fi_FI", "is_IS",
           "et_EE", "lv_LV", "lt_LT", "sl_SI", "hr_HR", "sr_RS", "ja_JP", "ko_KR", "zh_CN", "zh_TW", "zh_HK",
           "th_TH", "vi_VN", "id_ID", "ms_MY", "hi_IN")
LOCALE_RE = re.compile(r"^[a-z]{2,3}(_[A-Z]{2}|_[0-9]{3})?$")
TZ_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+)*$")


def _env_file(path):
    out = {}
    try:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    out[k.strip()] = v.strip().strip('"')
    except OSError:
        pass
    return out


def current_locale():
    """the values the app's launcher uses: locale.env, else the system's (as teslalinux-atl falls back)"""
    env = _env_file(LOCALE_ENV)
    loc = env.get("TESLALINUX_LOCALE") or _env_file("/etc/default/locale").get("LANG") or os.environ.get("LANG", "")
    loc = loc.split(".")[0].split("@")[0]
    if loc in ("C", "POSIX"):
        loc = ""
    tz = env.get("TESLALINUX_TIMEZONE", "")
    if not tz:
        try:
            tz = os.path.realpath("/etc/localtime").split("/zoneinfo/", 1)[1]
        except (OSError, IndexError):
            tz = ""
    return loc, tz


def locale_state():
    """GET /api/locale: {"locale", "timezone", "locales": [...], "timezones": [...]}"""
    loc, tz = current_locale()
    try:
        import zoneinfo
        zones = sorted(z for z in zoneinfo.available_timezones() if "/" in z and not z.startswith(("Etc/", "SystemV/", "US/", "posix", "right")))
    except Exception:
        zones = []
    if tz and tz not in zones:
        zones.insert(0, tz)
    locales = list(LOCALES)
    if loc and loc not in locales:
        locales.insert(0, loc)
    return {"locale": loc, "timezone": tz, "locales": locales, "timezones": zones}


def set_locale(loc, tz):
    """POST /api/locale: validated here and again by the root helper, applied asynchronously"""
    cmd = ["sudo", "-n", SET_LOCALE] if APPLY_SUDO and os.geteuid() != 0 else [SET_LOCALE]

    def run():
        with _apply_lock:
            try:
                subprocess.run(cmd + [loc, tz], check=False, timeout=120)
            except (OSError, subprocess.SubprocessError) as e:
                print("teslalinux-api: %s failed: %s" % (SET_LOCALE, e), flush=True)

    threading.Thread(target=run, daemon=True).start()


# ---------------------------------------------------------------------------- keyboard

def keyboard_state():
    """GET /api/keyboard: {"mode": "builtin"|"device"}: the app's own on-screen keyboard, or the keyboard
    of the device showing the page (teslalinux-set-keyboard; the page then uses /sockets/keyboard)"""
    mode = _env_file(KEYBOARD_ENV).get("TESLALINUX_KEYBOARD", "builtin")
    return {"mode": mode if mode in KEYBOARD_MODES else "builtin"}


def set_keyboard(mode):
    """POST /api/keyboard: the root helper writes keyboard.env and restarts the app if it changed"""
    cmd = ["sudo", "-n", SET_KEYBOARD] if APPLY_SUDO and os.geteuid() != 0 else [SET_KEYBOARD]

    def run():
        with _apply_lock:
            try:
                subprocess.run(cmd + [mode], check=False, timeout=120)
            except (OSError, subprocess.SubprocessError) as e:
                print("teslalinux-api: %s failed: %s" % (SET_KEYBOARD, e), flush=True)

    threading.Thread(target=run, daemon=True).start()


# ---------------------------------------------------------------------------- status

def _run(cmd, timeout=5):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL).stdout
    except (OSError, subprocess.SubprocessError):
        return ""


def _which(name):
    for d in os.environ.get("PATH", "/usr/sbin:/usr/bin:/sbin:/bin").split(":") + ["/usr/sbin", "/sbin"]:
        path = os.path.join(d, name)
        if os.access(path, os.X_OK):
            return path
    return None


_env_cache = {"mtime": None, "env": ENV}


def current_env():
    """/etc/teslalinux/env as it is now (the settings page changes it), re-read when the file changed"""
    try:
        mtime = os.stat(tp.ENV_PATH).st_mtime
    except OSError:
        return _env_cache["env"]
    if mtime != _env_cache["mtime"]:
        _env_cache["env"], _env_cache["mtime"] = tp.load_env(), mtime
    return _env_cache["env"]


def iface_ipv4(name):
    import fcntl
    import struct
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        return socket.inet_ntoa(fcntl.ioctl(s.fileno(), 0x8915, struct.pack("256s", name[:15].encode()))[20:24])  # SIOCGIFADDR
    except OSError:
        return None
    finally:
        s.close()


def default_route():
    """(interface, gateway) of the lowest-metric IPv4 default route, from /proc/net/route"""
    best = None
    try:
        with open("/proc/net/route") as f:
            next(f)
            for line in f:
                c = line.split()
                if len(c) >= 7 and c[1] == "00000000" and int(c[3], 16) & 0x2:
                    gw = socket.inet_ntoa(bytes.fromhex(c[2])[::-1])
                    if best is None or int(c[6]) < best[2]:
                        best = (c[0], gw, int(c[6]))
    except (OSError, ValueError, StopIteration):
        return None, None
    return (best[0], best[1]) if best else (None, None)


def iface_kind(name):
    drv = os.path.basename(os.path.realpath("/sys/class/net/%s/device/driver" % name))
    if drv == "ipheth":
        return "phone"
    if drv in MODEM_DRIVERS or name.startswith("wwan"):
        return "lte"
    if os.path.isdir("/sys/class/net/%s/wireless" % name):
        return "wifi"
    return "ethernet"


def iface_bytes(name):
    rx, tx = _read("/sys/class/net/%s/statistics/rx_bytes" % name), _read("/sys/class/net/%s/statistics/tx_bytes" % name)
    try:
        return int(rx), int(tx)
    except (TypeError, ValueError):
        return None, None


_internet = {"at": 0.0, "value": None}


def internet_check():
    """Can the device reach the internet: a TCP connection to two public anycast resolvers, cached 15 s"""
    now = time.monotonic()
    if _internet["value"] is not None and now - _internet["at"] < 15:
        return _internet["value"]
    result = {"reachable": False, "latencyMs": None}
    for host, port in (("1.1.1.1", 443), ("8.8.8.8", 53)):
        t0 = time.monotonic()
        try:
            socket.create_connection((host, port), timeout=2).close()
            result = {"reachable": True, "latencyMs": int((time.monotonic() - t0) * 1000)}
            break
        except OSError:
            continue
    _internet["at"], _internet["value"] = now, result
    return result


def _mmcli_json(args):
    try:
        return json.loads(_run(["mmcli", "-J"] + args, timeout=4) or "null")
    except ValueError:
        return None


def modemmanager_modem():
    """The first ModemManager modem (QMI/MBIM/serial LTE sticks): operator, technology, signal, state"""
    if not _which("mmcli"):
        return None
    lst = _mmcli_json(["-L"]) or {}
    paths = lst.get("modem-list") or []
    if not paths:
        return None
    m = (_mmcli_json(["-m", paths[0].rsplit("/", 1)[-1]]) or {}).get("modem") or {}
    gen, g3 = m.get("generic") or {}, m.get("3gpp") or {}
    sig = (gen.get("signal-quality") or {}).get("value")
    return {"source": "modemmanager", "model": " ".join(x for x in (gen.get("manufacturer"), gen.get("model")) if x and x != "--"),
            "state": gen.get("state"), "operator": g3.get("operator-name") if g3.get("operator-name") != "--" else None,
            "technology": ", ".join(t.upper() for t in (gen.get("access-technologies") or [])) or None,
            "signalPercent": int(sig) if str(sig).isdigit() else None, "registration": g3.get("registration-state")}


# Huawei HiLink CurrentNetworkType(Ex) values
HILINK_NET = {0: "No service", 1: "GSM", 2: "GPRS", 3: "EDGE", 4: "WCDMA", 5: "HSDPA", 6: "HSUPA", 7: "HSPA",
              8: "TD-SCDMA", 9: "HSPA+", 19: "LTE", 41: "WCDMA", 42: "HSDPA", 43: "HSUPA", 44: "HSPA", 45: "HSPA+",
              46: "DC-HSPA+", 61: "TD-SCDMA", 101: "LTE", 1011: "LTE CA", 111: "5G NSA", 112: "5G SA"}
HILINK_STATUS = {900: "connecting", 901: "connected", 902: "disconnected", 903: "disconnecting"}
_hilink = {"at": 0.0, "gw": None, "value": None}


def hilink_modem(gateway):
    """A USB "HiLink" modem (Huawei E3372 and kin: a router of its own, the upstream default): its web
    API at the gateway answers operator, network type and signal bars. Cached 10 s; None if no HiLink."""
    import urllib.request
    import xml.etree.ElementTree as ET
    now = time.monotonic()
    if _hilink["gw"] == gateway and now - _hilink["at"] < 10:
        return _hilink["value"]

    def get(path, headers):
        req = urllib.request.Request("http://%s%s" % (gateway, path), headers=headers)
        with urllib.request.urlopen(req, timeout=1.5) as r:
            root = ET.fromstring(r.read(16384))
        if root.tag == "error":
            raise ValueError(root.findtext("code"))
        return {c.tag: (c.text or "") for c in root}

    value = None
    try:
        headers = {}
        try:
            tok = get("/api/webserver/SesTokInfo", {})
            headers = {"Cookie": tok.get("SesInfo", ""), "__RequestVerificationToken": tok.get("TokInfo", "")}
        except (OSError, ValueError, ET.ParseError):
            pass
        st = get("/api/monitoring/status", headers)
        value = {"source": "hilink", "model": None, "operator": None,
                 "state": HILINK_STATUS.get(int(st.get("ConnectionStatus") or 0), st.get("ConnectionStatus")),
                 "technology": HILINK_NET.get(int(st.get("CurrentNetworkTypeEx") or st.get("CurrentNetworkType") or 0)),
                 "signalPercent": int(st["SignalIcon"]) * 20 if (st.get("SignalIcon") or "").isdigit() else None,
                 "registration": None}
        try:
            value["operator"] = get("/api/net/current-plmn", headers).get("FullName") or None
        except (OSError, ValueError, ET.ParseError):
            pass
        try:
            value["model"] = get("/api/device/basic_information", headers).get("devicename") or None
        except (OSError, ValueError, ET.ParseError):
            pass
    except (OSError, ValueError, ET.ParseError):
        value = None
    _hilink.update({"at": now, "gw": gateway, "value": value})
    return value


def lte_modem():
    """Whichever modem is there: a HiLink router behind a modem interface, else a ModemManager modem"""
    try:
        names = os.listdir("/sys/class/net")
    except OSError:
        names = []
    for name in names:
        if iface_kind(name) != "lte" or (_read("/sys/class/net/%s/operstate" % name) or "") == "down":
            continue
        gw = None
        try:
            with open("/proc/net/route") as f:
                for line in f:
                    c = line.split()
                    if c[0] == name and c[2] != "00000000":
                        gw = socket.inet_ntoa(bytes.fromhex(c[2])[::-1])
                        break
        except (OSError, ValueError):
            pass
        if gw:
            m = hilink_modem(gw)
            if m:
                m["interface"] = name
                return m
    m = modemmanager_modem()
    return m


def parse_iw_info(text):
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("ssid "):
            out["ssid"] = line[5:]
        elif line.startswith("channel "):
            m = re.match(r"channel (\d+) \((\d+) MHz\)(?:, width: (\d+) MHz)?", line)
            if m:
                out["channel"], out["frequencyMhz"] = int(m.group(1)), int(m.group(2))
                out["widthMhz"] = int(m.group(3)) if m.group(3) else None
        elif line.startswith("type "):
            out["mode"] = line[5:]
    return out


def parse_stations(text):
    stations, cur = [], None
    for line in text.splitlines():
        m = re.match(r"^Station ([0-9a-f:]{17})", line)
        if m:
            cur = {"mac": m.group(1)}
            stations.append(cur)
            continue
        if cur is None or ":" not in line:
            continue
        k, v = (x.strip() for x in line.split(":", 1))
        if k == "signal":
            m = re.match(r"(-?\d+)", v)
            cur["signalDbm"] = int(m.group(1)) if m else None
        elif k == "connected time":
            cur["connectedSeconds"] = int(v.split()[0]) if v.split()[0].isdigit() else None
        elif k == "tx bitrate":
            cur["txMbps"] = float(v.split()[0]) if re.match(r"^[\d.]+", v) else None
        elif k == "rx bitrate":
            cur["rxMbps"] = float(v.split()[0]) if re.match(r"^[\d.]+", v) else None
        elif k == "inactive time":
            cur["inactiveMs"] = int(v.split()[0]) if v.split()[0].isdigit() else None
    return stations


DNSMASQ_LEASES = ("/var/lib/misc/dnsmasq.leases", "/var/lib/dnsmasq/dnsmasq.leases", "/tmp/dnsmasq.leases")


def dhcp_leases():
    out = {}
    for path in DNSMASQ_LEASES:
        try:
            with open(path) as f:
                for line in f:
                    c = line.split()
                    if len(c) >= 4:
                        out[c[1].lower()] = {"ip": c[2], "name": None if c[3] == "*" else c[3]}
            break
        except OSError:
            continue
    return out


def service_active(name):
    if os.path.isdir("/run/systemd/system"):
        return _run(["systemctl", "is-active", name], timeout=3).strip() == "active"
    return _run(["rc-service", name, "status"], timeout=3).find("started") >= 0


def hotspot_status(env):
    iface = env.get("AP_IFACE", "wlan0")
    present = os.path.isdir("/sys/class/net/" + iface)
    st = {"interface": iface, "present": present, "ssid": env.get("AP_SSID"), "running": False,
          "address": iface_ipv4(iface) if present else None, "clients": []}
    if not present:
        return st
    if _which("iw"):
        info = parse_iw_info(_run(["iw", "dev", iface, "info"], timeout=3))
        st.update({k: v for k, v in info.items() if k != "ssid"})
        st["running"] = info.get("mode") == "AP" and service_active("hostapd")
        if info.get("ssid"):
            st["ssid"] = info["ssid"]
        leases = dhcp_leases()
        for sta in parse_stations(_run(["iw", "dev", iface, "station", "dump"], timeout=3)):
            sta.update(leases.get(sta["mac"], {}))
            st["clients"].append(sta)
    return st


def established_on(port):
    """Established TCP connections to a local port (the page's display socket: one per open car page)"""
    n = 0
    for path in ("/proc/net/tcp", "/proc/net/tcp6"):
        try:
            with open(path) as f:
                next(f)
                for line in f:
                    c = line.split()
                    if len(c) > 3 and c[3] == "01" and int(c[1].rsplit(":", 1)[1], 16) == port:
                        n += 1
        except (OSError, ValueError, StopIteration):
            continue
    return n


GPS_FEED_PORT = int(os.environ.get("TESLALINUX_GPS_FEED_PORT", "9997"))


def gps_status():
    """The last fix the car sent (services/gps/gps.py hands the latest fix younger than 10 s to every new
    feed reader), or none"""
    try:
        s = socket.create_connection(("127.0.0.1", GPS_FEED_PORT), timeout=0.5)
    except OSError:
        return {"service": False, "fix": None}
    try:
        s.settimeout(0.4)
        data = b""
        while b"\n" not in data and len(data) < 512:
            chunk = s.recv(256)
            if not chunk:
                break
            data += chunk
    except OSError:
        pass
    finally:
        s.close()
    c = data.split(b"\n", 1)[0].decode("ascii", "replace").split()
    if len(c) < 6:
        return {"service": True, "fix": None}

    def f(x):
        try:
            v = float(x)
            return None if v != v else v
        except ValueError:
            return None
    return {"service": True, "fix": {"latitude": f(c[0]), "longitude": f(c[1]), "speedMps": f(c[2]),
                                     "bearing": f(c[3]), "accuracyM": f(c[4]),
                                     "ageSeconds": max(0, int(time.time() - int(c[5]) / 1000)) if c[5].isdigit() else None}}


def system_status():
    mem = {}
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                k, v = line.split(":", 1)
                mem[k] = int(v.split()[0]) * 1024
    except (OSError, ValueError):
        pass
    try:
        vfs = os.statvfs("/")
        disk = {"total": vfs.f_blocks * vfs.f_frsize, "free": vfs.f_bavail * vfs.f_frsize}
    except OSError:
        disk = None
    try:
        uptime = int(float((_read("/proc/uptime") or "0").split()[0]))
    except ValueError:
        uptime = None
    try:
        load = [float(x) for x in (_read("/proc/loadavg") or "").split()[:3]]
    except ValueError:
        load = []
    throttled = None
    if _which("vcgencmd"):
        m = re.search(r"throttled=(0x[0-9a-fA-F]+)", _run(["vcgencmd", "get_throttled"], timeout=3))
        if m:
            v = int(m.group(1), 16)
            # bits 0-3 now: under-voltage, frequency capped, throttled, soft temperature limit
            throttled = {"underVoltage": bool(v & 1), "capped": bool(v & 2), "throttled": bool(v & 4),
                         "softTempLimit": bool(v & 8), "sinceBoot": bool(v & 0xF0000)}
    return {"hostname": socket.gethostname(), "os": os_name(), "kernel": os.uname().release,
            "model": _read("/sys/firmware/devicetree/base/model") or os.uname().machine,
            "uptimeSeconds": uptime, "load": load, "cpuTemperature": cpu_temperature(), "cpus": os.cpu_count(),
            "memory": {"total": mem.get("MemTotal"), "available": mem.get("MemAvailable")}, "disk": disk,
            "throttled": throttled}


def os_name():
    for path in ("/etc/os-release", "/usr/lib/os-release"):
        env = _env_file(path)
        if env.get("PRETTY_NAME"):
            return env["PRETTY_NAME"]
    return os.uname().sysname


def status():
    """GET /api/status: what the device is connected to right now, for the settings page's Status"""
    env = current_env()
    iface, gw = default_route()
    uplink = None
    if iface:
        rx, tx = iface_bytes(iface)
        uplink = {"interface": iface, "kind": iface_kind(iface), "address": iface_ipv4(iface), "gateway": gw,
                  "rxBytes": rx, "txBytes": tx}
    return {"time": int(time.time()), "system": system_status(), "hotspot": hotspot_status(env),
            "uplink": uplink, "internet": internet_check(), "modem": lte_modem(), "failover": uplink_state(),
            "displayViewers": established_on(int(os.environ.get("TESLALINUX_DISPLAY_PORT", "9091"))),
            "gps": gps_status(), "app": app_state()}


# ---------------------------------------------------------------------------- network settings

NETWORK = os.environ.get("TESLALINUX_NETWORK", "/usr/local/bin/teslalinux-network")
SSID_MAX = 32
PSK_RE = re.compile(r"^(?:[\x20-\x7e]{8,63}|[0-9A-Fa-f]{64})$")
COUNTRY_RE = re.compile(r"^[A-Z]{2}$")
APN_RE = re.compile(r"^(?:[A-Za-z0-9][A-Za-z0-9.-]{0,62})?$")
CRED_RE = re.compile(r"^[\x20-\x7e]{0,64}$")


def _root(cmd):
    return ["sudo", "-n"] + cmd if APPLY_SUDO and os.geteuid() != 0 else cmd


def network_state():
    """GET /api/network: hotspot name/country (never the password), LTE settings (password left out)"""
    env = current_env()
    iface = env.get("AP_IFACE", "wlan0")
    lte = None
    out = _run(_root([NETWORK, "lte-state"]), timeout=10)
    try:
        lte = json.loads(out) if out.strip() else None
    except ValueError:
        lte = None
    wifi = None
    out = _run(_root([NETWORK, "wifi-state"]), timeout=10)
    try:
        wifi = json.loads(out) if out.strip() else None
    except ValueError:
        wifi = None
    if isinstance(wifi, dict):
        live = (uplink_state() or {}).get("wifi") or {}
        wifi["ssid"], wifi["signalDbm"] = live.get("ssid"), live.get("signalDbm")
    return {"wifi": wifi,
            "hotspot": {"ssid": env.get("AP_SSID", ""), "country": env.get("AP_COUNTRY", ""),
                        "pskSet": bool(env.get("AP_PSK")), "interface": iface,
                        "present": os.path.isdir("/sys/class/net/" + iface)},
            "lte": lte, "modemDetected": bool(modem_detected()),
            "modemManager": bool(_which("mmcli")) and bool(_which("nmcli"))}


UPLINK_STATE = os.environ.get("TESLALINUX_UPLINK_STATE", "/run/teslalinux/uplink.json")


def uplink_state():
    """teslalinux-uplink's view: the uplink in use and why, each candidate's health, the backup Wi-Fi's
    network. None when the daemon does not run (or its state is older than a minute)."""
    st = _read_json(UPLINK_STATE)
    if not st or time.time() - (st.get("time") or 0) > 60:
        return None
    return st


def wifi_scan():
    """GET /api/wifiScan: the networks the backup Wi-Fi card sees (teslalinux-network wifi-scan, root)"""
    out = _run(_root([NETWORK, "wifi-scan"]), timeout=45)
    try:
        return json.loads(out)
    except ValueError:
        return {"iface": None, "networks": [], "error": "scan failed"}


def valid_wifi_ops(w):
    if not isinstance(w, dict):
        return False
    if "enabled" in w and not isinstance(w["enabled"], bool):
        return False
    if "prefer" in w and w["prefer"] not in ("lte", "wifi"):
        return False
    if "remove" in w and not isinstance(w["remove"], str):
        return False
    add = w.get("add")
    if add is not None:
        if not isinstance(add, dict):
            return False
        ssid = add.get("ssid")
        if not isinstance(ssid, str) or not 1 <= len(ssid.encode("utf-8")) <= SSID_MAX \
                or any(ord(c) < 0x20 or ord(c) == 0x7f for c in ssid):
            return False
        psk = add.get("psk")
        if psk not in (None, "") and (not isinstance(psk, str) or not PSK_RE.match(psk)):
            return False
        for k in ("hidden", "open"):
            if k in add and not isinstance(add[k], bool):
                return False
    return True


def valid_network_request(req):
    if not isinstance(req, dict) or req.get("when") not in ("now", "boot"):
        return False
    hot, lte = req.get("hotspot"), req.get("lte")
    if "wifi" in req and not valid_wifi_ops(req["wifi"]):
        return False
    if hot is not None:
        if not isinstance(hot, dict):
            return False
        ssid = hot.get("ssid")
        if ssid is not None and (not isinstance(ssid, str) or not 1 <= len(ssid.encode("utf-8")) <= SSID_MAX
                                 or any(ord(c) < 0x20 or ord(c) == 0x7f for c in ssid)):
            return False
        psk = hot.get("psk")
        if psk not in (None, "") and (not isinstance(psk, str) or not PSK_RE.match(psk)):
            return False
        country = hot.get("country")
        if country is not None and (not isinstance(country, str) or not COUNTRY_RE.match(country)):
            return False
    if lte is not None:
        if not isinstance(lte, dict):
            return False
        for k, rx in (("apn", APN_RE), ("user", CRED_RE), ("password", CRED_RE)):
            if k in lte and (not isinstance(lte[k], str) or not rx.match(lte[k])):
                return False
        for k in ("roaming", "enabled", "clearPassword"):
            if k in lte and not isinstance(lte[k], bool):
                return False
    return True


def set_network(req):
    """POST /api/network: the root helper writes the env file / LTE profile; "now" also restarts the
    hotspot a moment after this reply. Returns (ok, message)."""
    try:
        r = subprocess.run(_root([NETWORK, "apply", req["when"]]), input=json.dumps(req), capture_output=True,
                           text=True, timeout=60)
    except (OSError, subprocess.SubprocessError) as e:
        return False, str(e)
    if r.returncode != 0:
        return False, (r.stderr.strip().splitlines() or ["failed"])[-1].replace("teslalinux-network: ", "")
    return True, "ok"


# ---------------------------------------------------------------------------- system update

SYSUPDATE = os.environ.get("TESLALINUX_SYSUPDATE", "/usr/local/bin/teslalinux-system-update")
SYSUPDATE_DIR = os.environ.get("TESLALINUX_SYSUPDATE_DIR", "/var/lib/teslalinux/sysupdate")
SYSUPDATE_LOG = os.environ.get("TESLALINUX_SYSUPDATE_LOG", "/var/log/teslalinux/system-update.log")


def sysupdate_running():
    import fcntl
    try:
        fd = os.open(os.path.join(SYSUPDATE_DIR, "lock"), os.O_RDONLY)
    except OSError:
        return False
    try:
        fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
        fcntl.flock(fd, fcntl.LOCK_UN)
        return False
    except OSError:
        return True
    finally:
        os.close(fd)


LOG_CHUNK = 65536


def sysupdate_log(offset):
    """The run's log from byte OFFSET, whole lines only (a line still being written comes next time):
    (text, new offset, reset). An offset past the end means a new run started: it reads from 0."""
    try:
        with open(SYSUPDATE_LOG, "rb") as f:
            size = os.fstat(f.fileno()).st_size
            reset = offset > size
            if reset:
                offset = 0
            f.seek(offset)
            data = f.read(LOG_CHUNK)
    except OSError:
        return "", 0, offset > 0
    end = data.rfind(b"\n")
    if end < 0:
        return "", offset, reset
    data = data[:end + 1]
    return data.decode("utf-8", "replace"), offset + len(data), reset


def sysupdate_state(log_from=None):
    """GET /api/systemUpdate[?log=OFFSET]: the helper's state file, whether a run is going on, its live
    progress, the log (its tail, or with ?log= the new lines since OFFSET, for the page's terminal), and
    what the device would download over (LTE data is metered)"""
    st = _read_json(os.path.join(SYSUPDATE_DIR, "state.json")) or {
        "phase": "idle", "lastCheck": 0, "lastUpgrade": 0, "available": [], "result": "", "message": ""}
    running = sysupdate_running()
    if not running:
        st["phase"] = "idle"  # a run cut short (power loss) leaves its phase behind
    st["running"] = running
    st["progress"] = _read_json(os.path.join(SYSUPDATE_DIR, "progress.json")) if running else None
    st["manager"] = "apt" if _which("apt-get") else ("apk" if _which("apk") else None)
    st["os"] = os_name()
    st["kernel"] = os.uname().release
    if log_from is not None:
        st["log"], st["logOffset"], st["logReset"] = sysupdate_log(log_from)
    else:
        try:
            with open(SYSUPDATE_LOG, "rb") as f:
                f.seek(0, 2)
                f.seek(max(0, f.tell() - 4096))
                st["logTail"] = f.read().decode("utf-8", "replace").splitlines()[-12:]
        except OSError:
            st["logTail"] = []
    iface, _gw = default_route()
    st["uplink"] = iface_kind(iface) if iface else None
    if "rebootRequired" not in st:
        st["rebootRequired"] = False
    return st


def sysupdate_action(action):
    """POST /api/systemUpdate {"action": check|upgrade|reboot}: (http code, message)"""
    try:
        r = subprocess.run(_root([SYSUPDATE, action]), capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as e:
        return 500, str(e)
    if r.returncode == 3:
        return 409, "an update is already running"
    if r.returncode != 0:
        return 500, (r.stderr.strip().splitlines() or ["failed"])[-1]
    return 200, "ok"


# ---------------------------------------------------------------------------- certificate

def certificate_state():
    try:
        with open(CERT_STATE) as f:
            st = json.load(f)
    except (OSError, ValueError):
        return None
    if isinstance(st, dict) and isinstance(st.get("notAfter"), (int, float)):
        st["daysLeft"] = round((st["notAfter"] - time.time()) / 86400.0, 1)
    return st


# ---------------------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "teslalinux-api"
    sys_version = ""

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, body=b"", ctype=None, extra=()):
        self.send_response(code)
        # set_post_routing_handler (cpp:673-678)
        self.send_header("Allow", "GET, POST, HEAD, OPTIONS")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "X-Requested-With, Content-Type, Accept, Origin, Authorization")
        self.send_header("Access-Control-Allow-Methods", "OPTIONS, GET, POST, HEAD")
        for k, v in extra:
            self.send_header(k, v)
        if ctype:
            self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, obj):
        self._send(200, json.dumps(obj, indent="\t").encode(), "application/json")

    def _ok(self):
        self._send(200, b"OK", "text/plain")  # handle_post_success

    def _error(self):
        self._send(500, b"Internal Server Error", "text/plain")  # handle_error

    def _route(self):
        path = self.path.split("?", 1)[0]
        return path[len("/api/"):] if path.startswith("/api/") else None

    def do_OPTIONS(self):
        route = self._route()
        if route in ROUTES_GET or route in ROUTES_POST:
            # handle_preflight (cpp:160-165)
            return self._send(204, b"", "text/plain; charset=utf-8", (("Access-Control-Max-Age", "1728000"),))
        self._send(404)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        route = self._route()
        p = props()
        if route == "health":
            return self._send(200)
        if route == "configuration":
            return self._json({k: tp.atoi(p.get(k)) for k in CONFIGURATION_KEYS})
        if route == "displayState":
            ds = {name: tp.atoi(p.get(key)) for name, key in DISPLAY_FIELDS}
            ds["isHeadless"] = tp.atoi(p.get(tp.HEADLESS))
            order = ("width", "height", "density", "refreshRate", "quality", "resolutionPreset", "renderer",
                     "isResponsive", "isH264", "isHeadless", "isRearDisplayEnabled", "isRearDisplayPrioritised")
            return self._json({k: ds[k] for k in order})
        if route == "deviceInfo":
            return self._json(device_info(p))
        if route == "appState":
            return self._json(app_state())
        if route == "locale":
            return self._json(locale_state())
        if route == "keyboard":
            return self._json(keyboard_state())
        if route == "appUpdate":
            return self._json(appupdate_state())
        if route == "status":
            return self._json(status())
        if route == "network":
            return self._json(network_state())
        if route == "wifiScan":
            return self._json(wifi_scan())
        if route == "systemUpdate":
            q = urllib.parse.parse_qs(self.path.split("?", 1)[1]) if "?" in self.path else {}
            log_from = (q.get("log") or [None])[0]
            return self._json(sysupdate_state(int(log_from) if log_from and log_from.isdigit() else None))
        if route == "openUpdater":
            # Upstream runs `am start -a android.settings.SYSTEM_UPDATE_SETTINGS` on the Android
            # screen. This platform has no system-update activity (the car image is replaced as a
            # whole, apps through tools/apps/install-app.sh), so the request reports that absence
            # instead of pretending: the frontend logs the error and stays where it is.
            return self._send(501, b"no system updater on this device", "text/plain")
        if route == "certificate":
            st = certificate_state()
            return self._json(st) if st is not None else self._send(404)
        self._send(404)

    def do_POST(self):
        route = self._route()
        if route == "appUpdateUpload":
            # streamed, not read into memory like the small settings bodies below
            if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Length") is None:
                return self._send(411, b"Content-Length required", "text/plain")
            n = int(self.headers.get("Content-Length") or 0)
            if not 0 < n <= APPUPDATE_MAX_UPLOAD:
                self.close_connection = True
                return self._send(413, b"bundle too large or empty", "text/plain")
            q = urllib.parse.parse_qs(self.path.split("?", 1)[1]) if "?" in self.path else {}
            code, res = appupdate_upload(self, n, (q.get("name") or ["bundle"])[0])
            return self._send(code, json.dumps(res).encode(), "application/json")
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n > 0 else b""
        if route in TEXT_ROUTES:
            value = body.decode("utf-8", "replace")
            if len(value.encode()) >= PROPERTY_VALUE_MAX or "\0" in value:
                return self._error()
            set_props({TEXT_ROUTES[route]: value})
            return self._ok()
        if route == "appRestart":
            restart_app()
            return self._ok()
        if route == "locale":
            try:
                req = json.loads(body.decode("utf-8") or "null")
            except (ValueError, UnicodeDecodeError):
                return self._error()
            if not isinstance(req, dict):
                return self._error()
            loc, tz = req.get("locale"), req.get("timezone")
            if not isinstance(loc, str) or not isinstance(tz, str) or not LOCALE_RE.match(loc) or not TZ_RE.match(tz) \
                    or not os.path.isfile(os.path.join("/usr/share/zoneinfo", tz)):
                return self._error()
            set_locale(loc, tz)
            return self._ok()
        if route == "keyboard":
            try:
                req = json.loads(body.decode("utf-8") or "null")
            except (ValueError, UnicodeDecodeError):
                return self._error()
            if not isinstance(req, dict) or req.get("mode") not in KEYBOARD_MODES:
                return self._error()
            set_keyboard(req["mode"])
            return self._ok()
        if route == "appUpdateSettings":
            try:
                req = json.loads(body.decode("utf-8") or "null")
            except (ValueError, UnicodeDecodeError):
                return self._error()
            try:
                ok = set_appupdate_settings(req)
            except OSError:
                return self._error()
            return self._ok() if ok else self._error()
        if route == "appUpdateCheck":
            appupdate_check_now()
            return self._ok()
        if route == "network":
            try:
                req = json.loads(body.decode("utf-8") or "null")
            except (ValueError, UnicodeDecodeError):
                return self._error()
            if not valid_network_request(req):
                return self._send(400, json.dumps({"ok": False, "message": "invalid settings"}).encode(), "application/json")
            ok, msg = set_network(req)
            return self._send(200 if ok else 500, json.dumps({"ok": ok, "message": msg}).encode(), "application/json")
        if route == "systemUpdate":
            try:
                req = json.loads(body.decode("utf-8") or "null")
            except (ValueError, UnicodeDecodeError):
                return self._error()
            if not isinstance(req, dict) or req.get("action") not in ("check", "upgrade", "reboot"):
                return self._error()
            code, msg = sysupdate_action(req["action"])
            return self._send(code, json.dumps({"ok": code == 200, "message": msg}).encode(), "application/json")
        if route == "displayState":
            try:
                req = json.loads(body.decode("utf-8") or "null")
            except (ValueError, UnicodeDecodeError):
                return self._error()
            if not isinstance(req, dict):
                return self._error()
            changes = {}
            for name, key in DISPLAY_FIELDS:
                v = req.get(name)
                # cJSON_IsNumber: true/false/strings are not numbers
                if isinstance(v, bool) or not isinstance(v, (int, float)):
                    return self._error()
                changes[key] = str(int(v))  # cJSON valueint truncates
            set_props(changes)
            self._ok()
            apply_display(props())
            return None
        self._send(404)


def main():
    srv = ThreadingHTTPServer((BIND, PORT), Handler)
    srv.daemon_threads = True
    srv.socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    srv.serve_forever()


if __name__ == "__main__":
    main()
