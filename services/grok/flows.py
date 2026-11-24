"""What the tools learned about Waze's car screens, kept per Waze version on the device.

A flow (the report menu's categories and kinds, which control a screen's button is, what a
category asks before it reports, ...) is read from Waze's screens the first time a tool needs it and
kept in <state dir>/flows.json under Waze's versionCode (the installed app's installed.json). The
next call goes straight to the step it needs; when a screen does not match what was learned, the
tool reads it again and the entry is replaced. A new Waze version starts with nothing learned.
Callback ids are never kept: Waze gives new ones with every template, so a flow names what to tap
(an icon's resource name, a title), never a callback.
"""
import json
import os
import threading

STATE_DIR = os.environ.get("TESLALINUX_GROK_DIR", "/var/lib/teslalinux/grok")
APP_DIR = os.environ.get("TESLALINUX_GROK_APP_DIR", "/var/lib/teslalinux/apps/com.waze")

_lock = threading.Lock()
_mem = {"vc": None, "flows": None, "mtime": None}


def version_code():
    """The installed Waze's versionCode, or None when it is not known."""
    try:
        st = os.stat(os.path.join(APP_DIR, "installed.json"))
    except OSError:
        return None
    if _mem["mtime"] == st.st_mtime and _mem["vc"] is not None:
        return _mem["vc"]
    try:
        with open(os.path.join(APP_DIR, "installed.json")) as f:
            vc = json.load(f).get("versionCode")
    except (OSError, ValueError, AttributeError):
        return None
    if not isinstance(vc, int) or isinstance(vc, bool):
        return None
    _mem["mtime"] = st.st_mtime
    if vc != _mem["vc"]:
        _mem["vc"], _mem["flows"] = vc, None
    return vc


def _path():
    return os.path.join(STATE_DIR, "flows.json")


def _load():
    vc = version_code()
    key = str(vc) if vc is not None else "unknown"
    if _mem["flows"] is None:
        flows = {}
        try:
            with open(_path()) as f:
                d = json.load(f)
            if isinstance(d, dict) and d.get("versionCode") == key and isinstance(d.get("flows"), dict):
                flows = d["flows"]
        except (OSError, ValueError):
            pass
        _mem["flows"] = flows
    return key, _mem["flows"]


def get(name):
    with _lock:
        _, flows = _load()
        return flows.get(name)


def put(name, value):
    with _lock:
        key, flows = _load()
        if flows.get(name) == value:
            return
        flows[name] = value
        _save(key, flows)


def forget(name):
    with _lock:
        key, flows = _load()
        if flows.pop(name, None) is not None:
            _save(key, flows)


def _save(key, flows):
    try:
        os.makedirs(STATE_DIR, exist_ok=True)
        tmp = _path() + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump({"versionCode": key, "flows": flows}, f, ensure_ascii=False, separators=(",", ":"))
        os.replace(tmp, _path())
    except OSError:
        pass  # kept in memory for this run; learned again after a restart


def reset_memory():
    """Forgets what was read from disk (the tests change the files under it)."""
    with _lock:
        _mem.update(vc=None, flows=None, mtime=None)
