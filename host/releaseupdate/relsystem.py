"""teslalinux-release-update, part 2: this device (docs/SYSTEM-UPDATES.md).

The release installed and the one before it (the dists tools/pios/install.sh keeps in
/opt/teslalinux.d, /opt/teslalinux linking the active one, and its backup sets, each with the dist
it replaced in dist.before), the repository the device was installed from, the switch back to a
previous dist, and the probes the install rule and the health check use: the car connected (the
app updater's own rule), Waze navigating (Car mode: the car-app host's state), the services up
and the app's first picture (the launcher's state file, the car page's start overlay).
"""
import importlib.machinery
import importlib.util
import json
import os
import re
import shutil
import subprocess
import time

E = os.environ.get
PREFIX = E("RELEASE_UPDATE_PREFIX", "/opt/teslalinux")
DISTS = E("RELEASE_UPDATE_DISTS", "/opt/teslalinux.d")
BACKUP_ROOT = E("RELEASE_UPDATE_BACKUP_ROOT", "/var/lib/teslalinux/backup")
INSTALL_STATE = E("RELEASE_UPDATE_INSTALL_STATE", "/var/lib/teslalinux/install.state")
INSTALLER = E("RELEASE_UPDATE_INSTALLER", "/usr/local/sbin/teslalinux")
ART_CACHE = E("RELEASE_UPDATE_ART_CACHE", "/var/lib/tesla/.cache/art")
APPUPDATE = E("RELEASE_UPDATE_APPUPDATE", "/usr/local/bin/teslalinux-appupdate")
GROK_LIB = E("RELEASE_UPDATE_GROK_LIB", "/usr/local/lib/teslalinux/grok")
INTERFACE_ENV = E("RELEASE_UPDATE_INTERFACE_ENV", "/etc/teslalinux/interface.env")
APP_STATE_FILE = E("RELEASE_UPDATE_APP_STATE", "/run/tesla/teslalinux-app-state.json")
APP_APK = E("RELEASE_UPDATE_APP_APK", "/var/lib/teslalinux/apps/com.waze/base.apk")
APP_UNIT = "teslalinux-atl.service"
# what must run after an update (tools/pios/install.sh doctor's core units), the app's unit too
# when an app is installed
HEALTH_UNITS = E("RELEASE_UPDATE_HEALTH_UNITS", "nginx.service tacomp.service teslalinux-api.service hostapd.service dnsmasq.service").split()
DEFAULT_REPO = "Alexa-RR/teslalinux"
REPO_RE = re.compile(r"[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")


def _read(path, limit=1 << 20):
    try:
        with open(path, errors="replace") as f:
            return f.read(limit)
    except OSError:
        return None


def manifest(dist_dir):
    """{release, version} of a dist directory's MANIFEST, or None without one."""
    text = _read(os.path.join(dist_dir, "MANIFEST"))
    if text is None:
        return None
    out = {}
    for line in text.splitlines():
        k, sep, v = line.partition("=")
        if sep and k in ("release", "version", "release_repo") and k not in out:
            out[k] = v.strip()
    return {"release": out.get("release") or None, "version": out.get("version") or None,
            "releaseRepo": out.get("release_repo") or None, "dist": os.path.realpath(dist_dir)}


def installed():
    return manifest(PREFIX) if os.path.islink(PREFIX) else None


def label(m):
    if not m:
        return "?"
    return m.get("release") or ("build " + m["version"] if m.get("version") else os.path.basename(m.get("dist") or "?"))


def release_repo():
    """The GitHub repository the device was installed from: RELEASE_UPDATE_REPO, else the dist's
    MANIFEST (release_repo=), else the installed installer's TL_RELEASE_REPO default (pack-dist.sh
    writes the release's repository into it), else the project's."""
    cands = [E("RELEASE_UPDATE_REPO"), (installed() or {}).get("releaseRepo")]
    for path in (INSTALLER, os.path.join(PREFIX, "share/teslalinux/tools/pios/install.sh")):
        m = re.search(r'(?m)^TL_RELEASE_REPO="\$\{TL_RELEASE_REPO:-([^}]*)\}"', _read(path) or "")
        cands.append(m.group(1) if m else None)
    for c in cands:
        if c and REPO_RE.fullmatch(c):
            return c
    return DEFAULT_REPO


def unavailable_reason():
    """Why this device cannot update itself to a release, the way AOSP reports a missing feature:
    None when it can."""
    osr = _read("/etc/os-release") or ""
    if re.search(r"(?m)^ID=\"?alpine", osr):
        return "The Alpine image is updated by writing a new image to its card. Releases install on Raspberry Pi OS."
    if not os.path.isdir("/run/systemd/system") and not E("RELEASE_UPDATE_FAKE_SYSTEMCTL"):
        return "This system does not run systemd, which the release installer needs."
    m = installed()
    if not m:
        return "Not installed from a release package (no %s/MANIFEST), so there is nothing to update from." % PREFIX
    if not m.get("release"):
        return "This device runs build %s, built from the source, not a release." % (m.get("version") or "?")
    if not os.access(INSTALLER, os.X_OK):
        return "The release installer (%s) is missing." % INSTALLER
    return None


def backup_sets():
    """The installer's backup sets that can still be rolled back, oldest first (their names are times)."""
    try:
        return sorted(n for n in os.listdir(BACKUP_ROOT) if n[:1] == "2" and os.path.isdir(os.path.join(BACKUP_ROOT, n)))
    except OSError:
        return []


def dist_before(set_name):
    v = (_read(os.path.join(BACKUP_ROOT, set_name, "dist.before")) or "").strip()
    return v or None


def previous():
    """The release `teslalinux rollback` returns to: the dist the latest backup set replaced, when it
    is still on disk and is not the active one. Its manifest, or None."""
    sets = backup_sets()
    if not sets:
        return None
    before = dist_before(sets[-1])
    cur = os.path.realpath(PREFIX)
    if not before or os.path.realpath(before) == cur or not os.path.isdir(before):
        return None
    return manifest(before) or {"release": None, "version": os.path.basename(before), "dist": before}


def installer_of(dist_dir):
    """A dist's own copy of the installer (its rollback code is the one that dist was tested with),
    else the installed one."""
    p = os.path.join(dist_dir, "share/teslalinux/tools/pios/install.sh")
    return p if os.path.isfile(p) else INSTALLER


# ---------------------------------------------------------------------------- commands

def run(cmd, log, timeout=600, env=None):
    log("$ " + " ".join(cmd))
    e = dict(os.environ)
    e.update(env or {})
    try:
        return subprocess.run(cmd, stdin=subprocess.DEVNULL, timeout=timeout, env=e).returncode
    except subprocess.TimeoutExpired:
        log("%s: timed out after %d s" % (cmd[0], timeout))
        return 124
    except OSError as ex:
        log("%s: %s" % (cmd[0], ex))
        return 127


def systemctl(*args, log=lambda m: None, timeout=120):
    fake = E("RELEASE_UPDATE_FAKE_SYSTEMCTL")  # tests: a script that records the call
    return run([fake or "systemctl"] + list(args), log, timeout=timeout)


def systemctl_out(*args):
    fake = E("RELEASE_UPDATE_FAKE_SYSTEMCTL")
    try:
        return subprocess.run([fake or "systemctl"] + list(args), capture_output=True, text=True, timeout=20).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def switch_dist(dist_dir, log):
    """/opt/teslalinux back to dist_dir by itself (a run that stopped before it changed anything else
    left no backup set): the link, the loader cache, ART's cache of the other dist's jars, and the
    installer's step record (its next run redoes every step)."""
    tmp = PREFIX + ".new"
    if os.path.lexists(tmp):
        os.unlink(tmp)
    os.symlink(dist_dir, tmp)
    os.replace(tmp, PREFIX)
    run(["ldconfig"], log, timeout=120)
    shutil.rmtree(ART_CACHE, ignore_errors=True)
    try:
        os.unlink(INSTALL_STATE)
    except OSError:
        pass


def roll_back_to(from_dist, sets_before, log):
    """Back to from_dist after an install run that began with the backup sets sets_before: with the
    set that run made (its files and units as they were, `teslalinux rollback`), else the link alone.
    True when /opt/teslalinux is from_dist afterwards."""
    sets = backup_sets()
    new = [s for s in sets if s not in (sets_before or [])]
    if new and sets[-1] in new and os.path.realpath(dist_before(sets[-1]) or "/none") == os.path.realpath(from_dist):
        rc = run(["bash", installer_of(from_dist), "rollback", "--yes"], log, timeout=1800)
        if rc != 0:
            log("the installer's rollback exited %d" % rc)
    if os.path.realpath(PREFIX) != os.path.realpath(from_dist):
        switch_dist(from_dist, log)
    return os.path.realpath(PREFIX) == os.path.realpath(from_dist)


# ---------------------------------------------------------------------------- probes

_appupdate = None


def appupdate():
    """The app updater (tools/appupdate/teslalinux-appupdate) as a module: its uplink and car rules
    are the ones this updater follows too. None when it is not installed."""
    global _appupdate
    if _appupdate is None and os.path.isfile(APPUPDATE):
        loader = importlib.machinery.SourceFileLoader("tl_appupdate", APPUPDATE)
        mod = importlib.util.module_from_spec(importlib.util.spec_from_loader("tl_appupdate", loader))
        loader.exec_module(mod)
        mod.log = lambda msg: None
        _appupdate = mod
    return _appupdate


def download_allowed(allow_metered):
    """(allowed, why, metered): the app updater's rule. LTE is metered; Wi-Fi and Ethernet that
    NetworkManager calls unmetered are not. Without the app updater nothing counts as unmetered."""
    au = appupdate()
    if au is None:
        return bool(allow_metered), "the uplink cannot be classified (no teslalinux-appupdate)", True
    metered, cellular, _iface = au.uplink_metered()
    ok, why = au.download_allowed(metered, False, cellular)
    if ok:
        return True, why, False
    return bool(allow_metered), why, True


def app_interface():
    for line in (_read(INTERFACE_ENV) or "").splitlines():
        if line.strip().startswith("TESLALINUX_APP_INTERFACE="):
            return "car" if line.split("=", 1)[1].strip().strip("\"'") == "car" else "phone"
    return "phone"


def navigating():
    """True / False in Car mode (the car-app host's navigation state, as Grok's trip_status reads
    it), None when it cannot be known (Phone mode has no path to it from outside the app)."""
    fake = E("RELEASE_UPDATE_FAKE_NAVIGATING")
    if fake in ("0", "1"):
        return fake == "1"
    if app_interface() != "car" or not os.path.isfile(os.path.join(GROK_LIB, "waze.py")):
        return None
    try:
        loader = importlib.machinery.SourceFileLoader("tl_waze", os.path.join(GROK_LIB, "waze.py"))
        waze = importlib.util.module_from_spec(importlib.util.spec_from_loader("tl_waze", loader))
        loader.exec_module(waze)
        s = waze.CarSession()
        try:
            return bool(s.navigating) if s.navigating is not None else None
        finally:
            s.close()
    except Exception:  # the host is not up (the app is starting or stopped): not navigating now
        return None


def car_idle():
    """(idle, why): the app updater's rule (no station on the hotspot and no display or touch
    connection, twice, 20 s apart), and Waze not navigating."""
    au = appupdate()
    if au is None:
        return False, "the car's connection cannot be checked (no teslalinux-appupdate)"
    idle, why = au.car_idle()
    if not idle:
        return False, why
    if navigating():
        return False, "Waze is navigating"
    return True, why


def other_update_busy():
    """The app updater or the system updater in the middle of a run (their lock files), or None."""
    import fcntl
    for name, path in (("a Waze update", E("RELEASE_UPDATE_APPUPDATE_LOCK", "/var/lib/teslalinux/appupdate/lock")),
                       ("a system update", E("RELEASE_UPDATE_SYSUPDATE_LOCK", "/var/lib/teslalinux/sysupdate/lock"))):
        try:
            fd = os.open(path, os.O_RDONLY)
        except OSError:
            continue
        try:
            fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
            fcntl.flock(fd, fcntl.LOCK_UN)
        except OSError:
            return name
        finally:
            os.close(fd)
    return None


def free_bytes(path):
    while path and not os.path.exists(path):
        path = os.path.dirname(path)
    try:
        v = os.statvfs(path or "/")
        return v.f_bavail * v.f_frsize
    except OSError:
        return 0


def boot_time():
    try:
        return time.time() - float((_read("/proc/uptime") or "0").split()[0])
    except ValueError:
        return 0


def units_down():
    """The core units that are not active (the app's too when an app is installed)."""
    units = HEALTH_UNITS + ([APP_UNIT] if os.path.isfile(APP_APK) else [])
    return [u for u in units if systemctl_out("is-active", u) != "active"]


def app_restarts():
    try:
        return int(systemctl_out("show", "-p", "NRestarts", "--value", APP_UNIT) or 0)
    except ValueError:
        return 0


def app_first_frame(since):
    """Whether the app drew its first picture after `since` (the launcher's state file: a start at
    or after it with firstFrame set, as the car page's start overlay sees it). True without an app."""
    if not os.path.isfile(APP_APK):
        return True
    try:
        with open(APP_STATE_FILE) as f:
            st = json.load(f)
    except (OSError, ValueError):
        return False
    started, frame = st.get("started"), st.get("firstFrame")
    return isinstance(frame, (int, float)) and isinstance(started, (int, float)) and started >= since - 5
