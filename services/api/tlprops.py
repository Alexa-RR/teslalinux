"""teslalinux settings store: upstream's persist.tesla-android.* system properties on Linux.

Upstream keeps every frontend setting in Android system properties (the configuration manager reads
and writes them, netd and the Wi-Fi stack read them when the hotspot starts). Here the same property
names and string values live in one JSON file, so services/api/api.py can answer exactly like
upstream's tesla-android-configuration-manager and host/teslalinux-render can render hostapd,
dnsmasq, nftables and nginx from them at boot ("applied during the next system startup", as the
frontend says for network settings).

Defaults are upstream's build properties (upstream/android-vendor-tesla-android/vendor.mk:68-89),
overridden by /etc/teslalinux/env where that file sets them (display size and rate, hotspot band and
channel, offline mode switches); values written through the API override both.
"""
import json
import os
import subprocess

ENV_PATH = os.environ.get("TESLALINUX_ENV", "/etc/teslalinux/env")
STATE_PATH = os.environ.get("TESLALINUX_STATE", "/var/lib/teslalinux/state/props.json")

P = "persist.tesla-android."
BAND_TYPE = P + "softap.band_type"
CHANNEL = P + "softap.channel"
CHANNEL_WIDTH = P + "softap.channel_width"
SOFTAP_ENABLED = P + "softap.is_enabled"
OFFLINE = P + "offline-mode.is_enabled"
OFFLINE_TELEMETRY = P + "offline-mode.telemetry.is_enabled"
OFFLINE_FIRMWARE = P + "offline-mode.tesla-firmware-downloads"
VD = P + "virtual-display."
VD_WIDTH = VD + "resolution.width"
VD_HEIGHT = VD + "resolution.height"
VD_DENSITY = VD + "density"
VD_PRESET = VD + "resolutionPreset"
VD_RENDERER = VD + "renderer"
VD_RESPONSIVE = VD + "is_responsive"
VD_H264 = VD + "is_h264"
VD_REFRESH = VD + "refresh_rate"
VD_QUALITY = VD + "quality"
VD_REAR = VD + "is-rear-display-enabled"
VD_REAR_PRIO = VD + "is-rear-display-prioritised"
AUDIO_ENABLED = P + "browser_audio.is_enabled"
AUDIO_VOLUME = P + "browser_audio.volume"
RELEASE_TYPE = P + "releasetype"
OTA_URL = P + "updater.uri"
GPS_ACTIVE = P + "gps.is_active"
GPS_HW = P + "gps_hw.is_detected"
HEADLESS = "persist.drm_hwc.headless.is_enabled"

# upstream/android-vendor-tesla-android/vendor.mk:68-89 (PRODUCT_PROPERTY_OVERRIDES)
UPSTREAM_DEFAULTS = {
    BAND_TYPE: "2", CHANNEL: "44", CHANNEL_WIDTH: "3", SOFTAP_ENABLED: "1",
    OFFLINE: "1", OFFLINE_TELEMETRY: "1", OFFLINE_FIRMWARE: "1",
    VD_WIDTH: "1088", VD_HEIGHT: "832", VD_REFRESH: "30", VD_QUALITY: "70", VD_DENSITY: "200",
    VD_PRESET: "0", VD_RENDERER: "0", VD_REAR: "0", VD_REAR_PRIO: "0",
    AUDIO_ENABLED: "0", AUDIO_VOLUME: "100", VD_RESPONSIVE: "0", VD_H264: "0", GPS_ACTIVE: "0",
    # not in vendor.mk: no USB GPS receiver support on this platform (upstream sets it from its
    # USB networking initialiser when one is plugged in), the compositor is always headless
    GPS_HW: "0", HEADLESS: "1",
}

ENV_KEYS = (
    "DEVICE_HOST", "HIJACK_IP", "AP_IFACE", "AP_SSID", "AP_PSK", "AP_COUNTRY", "AP_CHANNEL", "AP_HW_MODE",
    "AP_SUBNET", "DISPLAY_W", "DISPLAY_H", "DISPLAY_FPS", "DISPLAY_DENSITY", "TLS_CERT", "TLS_KEY",
    "OFFLINE_MODE", "OFFLINE_MODE_TELEMETRY", "OFFLINE_MODE_FIRMWARE_DOWNLOADS", "RELEASE_TYPE",
)


def load_env(path=None):
    """The env file with shell semantics (it is a sourced sh file: comments, quotes, expansions)."""
    path = path or ENV_PATH
    if not os.path.exists(path):
        return {}
    script = 'set -a; . "$1" >/dev/null 2>&1; for k in %s; do eval "v=\\${$k-}"; printf "%%s=%%s\\0" "$k" "$v"; done' % " ".join(ENV_KEYS)
    out = subprocess.run(["sh", "-c", script, "sh", path], capture_output=True, check=False).stdout
    env = {}
    for item in out.split(b"\0"):
        if b"=" in item:
            k, v = item.decode("utf-8", "replace").split("=", 1)
            if v != "":
                env[k] = v
    return env


def atoi(value, default=-1):
    """C atoi() on a property value, -1 when unset (get_system_property_int in upstream)."""
    if value is None:
        return default
    s = str(value).strip()
    n, i = "", 0
    if s[:1] in "+-":
        n, i = s[0], 1
    while i < len(s) and s[i].isdigit():
        n += s[i]
        i += 1
    try:
        return int(n)
    except ValueError:
        return 0


def defaults(env):
    d = dict(UPSTREAM_DEFAULTS)
    if env.get("DISPLAY_W"):
        d[VD_WIDTH] = env["DISPLAY_W"]
    if env.get("DISPLAY_H"):
        d[VD_HEIGHT] = env["DISPLAY_H"]
    if env.get("DISPLAY_FPS"):
        d[VD_REFRESH] = env["DISPLAY_FPS"]
    if env.get("DISPLAY_DENSITY"):
        d[VD_DENSITY] = env["DISPLAY_DENSITY"]
    if env.get("AP_CHANNEL"):
        d[CHANNEL] = env["AP_CHANNEL"]
        # SoftApConfiguration.BAND_2GHZ=1 (40 MHz), BAND_5GHZ=2 (80 MHz): the frontend's band list
        if env.get("AP_HW_MODE", "a") == "g":
            d[BAND_TYPE], d[CHANNEL_WIDTH] = "1", "2"
        else:
            d[BAND_TYPE], d[CHANNEL_WIDTH] = "2", "3"
    for key, prop in (("OFFLINE_MODE", OFFLINE), ("OFFLINE_MODE_TELEMETRY", OFFLINE_TELEMETRY),
                      ("OFFLINE_MODE_FIRMWARE_DOWNLOADS", OFFLINE_FIRMWARE)):
        if env.get(key) in ("0", "1"):
            d[prop] = env[key]
    d[RELEASE_TYPE] = env.get("RELEASE_TYPE", "release")
    d[OTA_URL] = ""
    return d


def load(env=None, path=None):
    env = load_env() if env is None else env
    props = defaults(env)
    try:
        with open(path or STATE_PATH) as f:
            saved = json.load(f)
        if isinstance(saved, dict):
            props.update({str(k): str(v) for k, v in saved.items()})
    except (OSError, ValueError):
        pass
    return props


def save_changes(changes, path=None):
    """Persist only what was set through the API (like persist.* props in /data), atomically."""
    path = path or STATE_PATH
    try:
        with open(path) as f:
            saved = json.load(f)
        if not isinstance(saved, dict):
            saved = {}
    except (OSError, ValueError):
        saved = {}
    saved.update({k: str(v) for k, v in changes.items()})
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(saved, f, indent=1, sort_keys=True)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)
