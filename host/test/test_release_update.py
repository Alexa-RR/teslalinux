#!/usr/bin/env python3
"""teslalinux-release-update without a network, GitHub, root or a Pi.

    python3 host/test/test_release_update.py

A local HTTP server stands in for GitHub (the releases API and the release downloads, with Range
requests); each release is a small fake dist (tar.xz with a MANIFEST and the fake installer
host/test/fake-install.sh as its install.sh) with a real SHA256SUMS. systemctl is a script that
records its calls and answers is-active from a file. Covered: newer / older / equal releases, the
semver order and what is not offered, a checksum mismatch, the metered refusal and the explicit
mobile-data download, a resumed download, the car-connected and navigating waits, the install,
the health check passing, failing (rollback with the backup set, and without one), a failed
install, going back by hand, the boot install, Alpine and a non-release build, the API's view.
"""
import hashlib
import http.server
import importlib.machinery
import importlib.util
import io
import json
import os
import shutil
import sys
import tarfile
import tempfile
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
FAKE_INSTALL = os.path.join(HERE, "fake-install.sh")
with open(FAKE_INSTALL, "rb") as _f:
    FAKE_INSTALL_BYTES = _f.read()
ROOT = tempfile.mkdtemp(prefix="tl-relupd-")
SRV_FILES = {}       # url path -> bytes
SRV_LOG = []         # (path, Range header)
SRV_STATE = {"releases": [], "fail": None}


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        SRV_LOG.append((self.path, self.headers.get("Range")))
        if SRV_STATE["fail"]:
            self.send_response(SRV_STATE["fail"])
            self.end_headers()
            return
        if self.path.startswith("/repos/owner/tl/releases"):
            body = json.dumps(SRV_STATE["releases"]).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        data = SRV_FILES.get(self.path)
        if data is None:
            self.send_response(404)
            self.end_headers()
            return
        rng = self.headers.get("Range")
        start = int(rng.split("=")[1].split("-")[0]) if rng else 0
        self.send_response(206 if rng else 200)
        self.send_header("Content-Length", str(len(data) - start))
        self.end_headers()
        self.wfile.write(data[start:])


SRV = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
threading.Thread(target=SRV.serve_forever, daemon=True).start()
BASE = "http://127.0.0.1:%d" % SRV.server_address[1]

P = lambda *x: os.path.join(ROOT, *x)  # noqa: E731
ENV = {
    "RELEASE_UPDATE_API": BASE, "RELEASE_UPDATE_DOWNLOAD_BASE": BASE, "RELEASE_UPDATE_REPO": "owner/tl",
    "RELEASE_UPDATE_STATE_DIR": P("state"), "RELEASE_UPDATE_PREFIX": P("opt", "teslalinux"),
    "RELEASE_UPDATE_DISTS": P("opt", "teslalinux.d"), "RELEASE_UPDATE_BACKUP_ROOT": P("backup"),
    "RELEASE_UPDATE_INSTALL_STATE": P("install.state"), "RELEASE_UPDATE_INSTALLER": FAKE_INSTALL,
    "RELEASE_UPDATE_ART_CACHE": P("art"), "RELEASE_UPDATE_APPUPDATE": P("none"),
    "RELEASE_UPDATE_APP_STATE": P("app-state.json"), "RELEASE_UPDATE_APP_APK": P("base.apk"),
    "RELEASE_UPDATE_FAKE_SYSTEMCTL": P("systemctl"), "RELEASE_UPDATE_HEALTH_POLL": "0.05",
    "RELEASE_UPDATE_HEALTH_TIMEOUT": "1", "RELEASE_UPDATE_APPUPDATE_LOCK": P("none"),
    "RELEASE_UPDATE_SYSUPDATE_LOCK": P("none"), "RELEASE_UPDATE_INTERFACE_ENV": P("none"),
    "FAKE_INSTALL_LOG": P("install.log"), "RELEASE_UPDATE_HEALTH_UNITS": "nginx.service tacomp.service",
}
os.environ.update(ENV)

# systemctl: every call into a log; is-active from units.down (one unit per line: not active)
os.makedirs(ROOT, exist_ok=True)
with open(P("systemctl"), "w") as f:
    f.write('#!/bin/sh\necho "$*" >> "%s"\ncase "$1" in\n'
            '  is-active) if grep -qx "$2" "%s" 2>/dev/null; then echo failed; else echo active; fi ;;\n'
            '  show) echo 0 ;;\nesac\nexit 0\n' % (P("systemctl.log"), P("units.down")))
os.chmod(P("systemctl"), 0o755)

_loader = importlib.machinery.SourceFileLoader("relupd", os.path.join(REPO, "host", "teslalinux-release-update"))
ru = importlib.util.module_from_spec(importlib.util.spec_from_loader("relupd", _loader))
_loader.exec_module(ru)
ru.log = lambda m: None
gh, sysm = ru.gh, ru.sysm
# the unmetered/car rules come from the app updater; here they are switches
NET = {"unmetered": True, "idle": True, "why": "test"}
REAL_DOWNLOAD_ALLOWED = sysm.download_allowed
sysm.download_allowed =lambda allow: ((True, "unmetered (test)", False) if NET["unmetered"]
                                       else (bool(allow), "uplink is metered (test)", True))
sysm.car_idle = lambda: (NET["idle"], NET["why"])
sysm.unavailable_reason_orig = sysm.unavailable_reason
sysm.unavailable_reason = lambda: None if not NET.get("unavailable") else NET["unavailable"]


def sha(b):
    return hashlib.sha256(b).hexdigest()


def make_dist_dir(version, release):
    """An installed dist directory (what the installer unpacks): MANIFEST and its own installer copy."""
    d = P("opt", "teslalinux.d", version)
    os.makedirs(os.path.join(d, "share/teslalinux/tools/pios"), exist_ok=True)
    with open(os.path.join(d, "MANIFEST"), "w") as f:
        f.write("format=1\nversion=%s\nrelease=%s\narch=arm64\n" % (version, release))
    shutil.copy(FAKE_INSTALL, os.path.join(d, "share/teslalinux/tools/pios/install.sh"))
    return d


def make_release(tag, version, published="2026-12-01T10:00:00Z", corrupt=None, draft=False, prerelease=False, notes=None,
                 features=None):
    """A release on the fake GitHub: its API entry and its four files (features: its installer's
    installer_features=, e.g. "prepare")."""
    top = "teslalinux-dist-%s" % version
    tarball = top + "-arm64.tar.xz"
    manifest = ("format=1\nversion=%s\nrelease=%s\narch=arm64\ntarball=%s\n" % (version, tag, tarball)
                + ("installer_features=%s\n" % features if features else "")).encode()
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:xz") as t:
        for name, data, mode in ((top + "/MANIFEST", manifest, 0o644),
                                 (top + "/share/teslalinux/tools/pios/install.sh", FAKE_INSTALL_BYTES, 0o755),
                                 (top + "/bin/payload", os.urandom(40000), 0o755)):
            ti = tarfile.TarInfo(name)
            ti.size, ti.mode = len(data), mode
            t.addfile(ti, io.BytesIO(data))
    files = {tarball: buf.getvalue(), "MANIFEST": manifest, "install.sh": FAKE_INSTALL_BYTES}
    sums = "".join("%s  %s\n" % (sha(files[n]), n) for n in (tarball, "MANIFEST", "install.sh")).encode()
    files["SHA256SUMS"] = sums
    if corrupt:
        files[corrupt] = files[corrupt][:-10] + b"0123456789"
    for n, data in files.items():
        SRV_FILES["/owner/tl/releases/download/%s/%s" % (tag, n)] = data
    rel = {"tag_name": tag, "draft": draft, "prerelease": prerelease, "published_at": published,
           "body": notes if notes is not None else "Short notes.\n\n### A topic\n\n*Its summary.*\n\n- one\n\n## Install\n\ncurl ...",
           "assets": [{"name": n, "size": len(d)} for n, d in files.items()]}
    return rel


def calls_text(path):
    with open(path) as f:
        return f.read()


def calls(name):
    try:
        with open(P(name)) as f:
            return f.read().splitlines()
    except OSError:
        return []


OLD = ("v0.5.0", "20261124-ef02f5ad")
NEW = ("v0.6.0", "20261201-aaaa1111")


class Base(unittest.TestCase):
    def setUp(self):
        for d in ("state", "opt", "backup", "art"):
            shutil.rmtree(P(d), ignore_errors=True)
        for f in ("install.log", "systemctl.log", "units.down", "app-state.json", "base.apk", "install.state"):
            if os.path.exists(P(f)):
                os.unlink(P(f))
        os.makedirs(P("opt"))
        os.makedirs(P("backup"))
        self.old_dist = make_dist_dir(*reversed(OLD))
        os.symlink(self.old_dist, P("opt", "teslalinux"))
        SRV_FILES.clear()
        del SRV_LOG[:]
        SRV_STATE["fail"] = None
        SRV_STATE["releases"] = [make_release(*NEW)]
        NET.update(unmetered=True, idle=True, why="test", unavailable=None)
        for k in ("FAKE_INSTALL_FAIL", "FAKE_INSTALL_NO_SET", "FAKE_PREPARE_FAIL"):
            os.environ.pop(k, None)
        ru.CHECK_INTERVAL = 86400

    def run_cycle(self, flags=None, auto=True, booting=False):
        os.makedirs(P("state"), exist_ok=True)
        ru.write_json(ru._p("settings.json"), {"autoUpdate": auto})
        st = ru.load_state()
        ru.cycle(st, flags or {}, ru.load_settings(), int(time.time()), booting=booting)
        ru.save_state(st)
        return st

    def prefix_release(self):
        return (sysm.installed() or {}).get("release")

    def app_drew(self):
        with open(P("base.apk"), "w") as f:
            f.write("apk")
        with open(P("app-state.json"), "w") as f:
            json.dump({"pid": 1, "started": time.time(), "firstFrame": time.time() + 1}, f)


class Releases(unittest.TestCase):
    def test_semver_and_choice(self):
        self.assertEqual(gh.semver("v0.10.2"), (0, 10, 2))
        self.assertIsNone(gh.semver("v0.6.0-rc1"))
        self.assertTrue(gh.newer("v0.10.0", "v0.9.9"))
        self.assertFalse(gh.newer("v0.5.0", "v0.5.0"))
        self.assertFalse(gh.newer("v0.4.0", "v0.5.0"))
        rels = [make_release("v0.9.0", "a"), make_release("v0.10.0", "b"), make_release("v0.11.0", "c", draft=True),
                make_release("v0.12.0", "d", prerelease=True), make_release("v1.0.0-rc1", "e")]
        incomplete = make_release("v0.13.0", "f")
        incomplete["assets"] = [a for a in incomplete["assets"] if a["name"] != "SHA256SUMS"]
        self.assertEqual(gh.latest_stable(rels + [incomplete])["tag"], "v0.10.0")

    def test_notes_cut_at_install(self):
        self.assertEqual(gh.release_notes("A\n\n## Install\n\ncurl x"), "A")

    def test_asset_names_checked(self):
        with self.assertRaises(gh.ReleaseError):
            gh.asset_url("owner/tl", "v0.6.0", "../../etc/passwd")
        with self.assertRaises(gh.ReleaseError):
            gh.asset_url("owner/tl", "latest", "MANIFEST")


class Cycle(Base):
    def test_newer_downloads_installs_and_passes_health(self):
        self.app_drew()
        st = self.run_cycle()
        self.assertEqual(self.prefix_release(), "v0.6.0")
        self.assertEqual(st["lastResult"], "restarting")
        self.assertTrue(os.path.exists(ru._p("health.json")))
        self.assertIn("reboot", calls("systemctl.log"))
        self.assertIn("stop teslalinux-atl.service", calls("systemctl.log"))
        inst = [c for c in calls("install.log") if c.startswith("fake-install install")]
        self.assertEqual(len(inst), 1)
        self.assertIn(ru._p("pending", "v0.6.0"), inst[0])  # the downloaded files, nothing fetched again
        self.assertIn("--yes --no-reboot", inst[0])
        self.assertFalse(os.path.exists(ru._p("pending", "v0.6.0")))
        self.assertTrue(os.path.isdir(self.old_dist))  # the previous dist stays
        # the next start: the health check passes
        self.app_drew()
        ru.cmd_health()
        st = ru.load_state()
        self.assertEqual(st["lastResult"], "installed")
        self.assertIsNone(st["health"])
        self.assertFalse(os.path.exists(ru._p("health.json")))
        self.assertEqual(ru.previous_of(st)["release"], "v0.5.0")

    def test_equal_and_older_are_up_to_date(self):
        for rel in (OLD, ("v0.4.0", "20261120-0")):
            SRV_STATE["releases"] = [make_release(*rel)]
            shutil.rmtree(P("state"), ignore_errors=True)
            st = self.run_cycle()
            self.assertEqual(st["lastResult"], "up-to-date", rel)
            self.assertIsNone(st["available"])
            self.assertEqual(self.prefix_release(), "v0.5.0")
        self.assertFalse(calls("install.log"))

    def test_checksum_mismatch_refused(self):
        SRV_STATE["releases"] = [make_release(*NEW, corrupt="teslalinux-dist-%s-arm64.tar.xz" % NEW[1])]
        st = self.run_cycle()
        self.assertEqual(st["lastResult"], "verify-failed")
        self.assertIn("SHA256SUMS", st["lastMessage"])
        self.assertEqual(st["bad"]["v0.6.0"]["kind"], "verify")
        self.assertIsNone(st["pending"])
        self.assertFalse(os.path.exists(ru._p("staging", "v0.6.0")))
        self.assertFalse(calls("install.log"))
        self.assertEqual(self.prefix_release(), "v0.5.0")
        # not downloaded again by the daily cycle; Check now tries again
        n = len(SRV_LOG)
        st = self.run_cycle()
        self.assertEqual(st["lastResult"], "skipped")
        self.assertEqual(len([p for p, _r in SRV_LOG[n:] if "/download/" in p]), 0)
        SRV_STATE["releases"] = [make_release(*NEW)]
        st = self.run_cycle({"check": "1"})
        self.assertEqual(self.prefix_release(), "v0.6.0")

    def test_manifest_of_another_release_refused(self):
        rel = make_release(*NEW)
        # a tarball and MANIFEST of v0.6.0 published under v0.7.0: the sums match, the MANIFEST does not
        for n in ("SHA256SUMS", "MANIFEST", "install.sh", "teslalinux-dist-%s-arm64.tar.xz" % NEW[1]):
            SRV_FILES["/owner/tl/releases/download/v0.7.0/" + n] = SRV_FILES["/owner/tl/releases/download/v0.6.0/" + n]
        rel["tag_name"] = "v0.7.0"
        SRV_STATE["releases"] = [rel]
        st = self.run_cycle()
        self.assertEqual(st["lastResult"], "verify-failed")
        self.assertIn("MANIFEST", st["lastMessage"])

    def test_metered_waits_then_mobile_data_by_hand(self):
        NET["unmetered"] = False
        st = self.run_cycle()
        self.assertEqual(st["lastResult"], "waiting-unmetered")
        self.assertEqual(st["available"]["tag"], "v0.6.0")
        self.assertFalse([p for p, _r in SRV_LOG if "/download/" in p])
        NET["idle"] = False  # the driver taps "Download over mobile data" from the car
        st = self.run_cycle({"metered": "1"})
        self.assertEqual(st["pending"]["tag"], "v0.6.0")
        self.assertEqual(st["lastResult"], "waiting-idle")
        self.assertEqual(self.prefix_release(), "v0.5.0")

    def test_resumed_download(self):
        rel = SRV_STATE["releases"][0]
        name = rel["assets"][0]["name"]
        os.makedirs(ru._p("staging", "v0.6.0"))
        data = SRV_FILES["/owner/tl/releases/download/v0.6.0/" + name]
        with open(ru._p("staging", "v0.6.0", name + ".part"), "wb") as f:
            f.write(data[:5000])
        NET["idle"] = False
        st = self.run_cycle()
        self.assertEqual(st["pending"]["tag"], "v0.6.0")
        self.assertIn(("/owner/tl/releases/download/v0.6.0/" + name, "bytes=5000-"), SRV_LOG)

    def test_car_connected_or_navigating_waits(self):
        NET.update(idle=False, why="1 station(s) on the hotspot wlan0")
        st = self.run_cycle()
        self.assertEqual(st["lastResult"], "waiting-idle")
        self.assertIn("station", st["lastMessage"])
        self.assertFalse(calls("install.log"))
        # Install now from the car page: installs while connected
        st = self.run_cycle({"install-now": "1"})
        self.assertEqual(self.prefix_release(), "v0.6.0")

    def prepare_calls(self):
        return [c for c in calls("install.log") if c.startswith("fake-install prepare")]

    def test_prepared_while_the_app_runs_then_switched(self):
        SRV_STATE["releases"] = [make_release(*NEW, features="prepare")]
        NET.update(idle=False, why="1 station(s) on the hotspot wlan0")
        st = self.run_cycle()
        # made ready with the app running: no stop, no install, the release waits for the car
        self.assertEqual(len(self.prepare_calls()), 1)
        self.assertIn(ru._p("pending", "v0.6.0"), self.prepare_calls()[0])
        self.assertNotIn("stop teslalinux-atl.service", calls("systemctl.log"))
        self.assertFalse([c for c in calls("install.log") if c.startswith("fake-install install")])
        self.assertTrue(st["pending"]["prepared"])
        self.assertEqual(st["lastResult"], "waiting-idle")
        self.assertIn("prepared", [h["result"] for h in st["history"]])
        # the next run with the car gone: the switch, not prepared again
        NET.update(idle=True, why="test")
        self.run_cycle()
        self.assertEqual(self.prefix_release(), "v0.6.0")
        self.assertEqual(len(self.prepare_calls()), 1)

    def test_a_release_without_prepare_installs_as_before(self):
        NET.update(idle=False, why="busy")
        st = self.run_cycle()
        self.assertFalse(self.prepare_calls())
        self.assertNotIn("prepared", st["pending"])

    def test_a_failed_prepare_falls_back_to_the_full_install(self):
        SRV_STATE["releases"] = [make_release(*NEW, features="prepare")]
        os.environ["FAKE_PREPARE_FAIL"] = "1"
        NET.update(idle=False, why="busy")
        st = self.run_cycle()
        self.assertTrue(st["pending"]["prepareFailed"])
        self.assertEqual(st["lastResult"], "waiting-idle")
        NET.update(idle=True, why="test")
        self.run_cycle()
        self.assertEqual(len(self.prepare_calls()), 1)      # not tried again
        self.assertEqual(self.prefix_release(), "v0.6.0")   # installed the full way

    def test_install_now_prepares_first_and_boot_does_not(self):
        SRV_STATE["releases"] = [make_release(*NEW, features="prepare")]
        self.run_cycle({"download": "1"}, auto=False)
        self.assertFalse(self.prepare_calls())               # automatic updates off: nothing in the background
        self.run_cycle({"install-now": "1"}, auto=False)
        log = [c.split()[1] for c in calls("install.log")]
        self.assertEqual(log, ["prepare", "install"])
        # a release still unprepared at a start is installed there as before
        self.setUp()
        SRV_STATE["releases"] = [make_release(*NEW, features="prepare")]
        self.run_cycle({"download": "1"}, auto=False)
        self.run_cycle(booting=True)
        self.assertFalse(self.prepare_calls())
        self.assertEqual(self.prefix_release(), "v0.6.0")

    def test_art_cache_follows_a_switch(self):
        cache, prep = P("art"), P("prepared")
        old, new = os.path.realpath(self.old_dist), P("opt", "teslalinux.d", NEW[1])
        sysm.PREPARED = prep
        try:
            os.makedirs(os.path.join(cache, "arm64"))
            with open(os.path.join(cache, ".teslalinux"), "w") as f:
                f.write("dist=%s\napp=x\ncompiled=1\n" % old)
            pc = os.path.join(prep, os.path.basename(new), "home", ".cache", "art")
            os.makedirs(os.path.join(pc, "arm64"))
            with open(os.path.join(pc, ".teslalinux"), "w") as f:
                f.write("dist=%s\napp=x\ncompiled=1\n" % new)
            sysm.art_switch(old, new, lambda m: None)
            self.assertIn("dist=" + new, calls_text(os.path.join(cache, ".teslalinux")))
            kept = os.path.join(prep, os.path.basename(old), "home", ".cache", "art", ".teslalinux")
            self.assertIn("dist=" + old, calls_text(kept))
            self.assertFalse(os.path.exists(os.path.join(prep, os.path.basename(new))))
            # and back: the kept one returns
            sysm.art_switch(new, old, lambda m: None)
            self.assertIn("dist=" + old, calls_text(os.path.join(cache, ".teslalinux")))
            # no switch (a rollback of files only): the cache stays
            sysm.art_switch(old, old, lambda m: None)
            self.assertIn("dist=" + old, calls_text(os.path.join(cache, ".teslalinux")))
            # a cache of another dist is not taken for a dist it does not name
            sysm.art_switch(old, P("elsewhere"), lambda m: None)
            self.assertFalse(os.path.exists(cache))
        finally:
            sysm.PREPARED = os.environ.get("RELEASE_UPDATE_PREPARED", "/var/lib/teslalinux/prepared")
            shutil.rmtree(prep, ignore_errors=True)

    def test_auto_off(self):
        st = self.run_cycle(auto=False)
        self.assertEqual(st["lastResult"], "available")
        self.assertIsNone(st["pending"])
        st = self.run_cycle({"download": "1"}, auto=False)
        self.assertEqual(st["lastResult"], "ready")
        self.assertEqual(self.prefix_release(), "v0.5.0")
        self.run_cycle(booting=True, auto=False)  # no install at the start with automatic updates off
        self.assertEqual(self.prefix_release(), "v0.5.0")
        self.run_cycle({"install-now": "1"}, auto=False)
        self.assertEqual(self.prefix_release(), "v0.6.0")

    def test_boot_install(self):
        NET["idle"] = False
        self.run_cycle()
        n = len(SRV_LOG)
        st = self.run_cycle(booting=True)
        self.assertEqual(self.prefix_release(), "v0.6.0")
        self.assertEqual(len(SRV_LOG), n)  # no check, no download at the start
        self.assertIn("at the start", st["lastInstall"]["how"])

    def test_github_down_backs_off(self):
        SRV_STATE["fail"] = 503
        st = self.run_cycle()
        self.assertEqual(st["lastResult"], "check-failed")
        self.assertGreater(st["nextTryAt"], time.time())
        n = len(SRV_LOG)
        self.run_cycle()
        self.assertEqual(len(SRV_LOG), n)  # within the backoff: not asked again


class Rollback(Base):
    def test_health_failure_rolls_back_with_the_backup_set(self):
        self.run_cycle()
        self.assertEqual(self.prefix_release(), "v0.6.0")
        with open(P("units.down"), "w") as f:
            f.write("tacomp.service\n")
        ru.cmd_health()
        st = ru.load_state()
        self.assertEqual(st["lastResult"], "rolled-back")
        self.assertIn("tacomp", st["lastMessage"])
        self.assertEqual(self.prefix_release(), "v0.5.0")
        self.assertEqual(st["bad"]["v0.6.0"]["kind"], "health")
        self.assertIsNone(st["health"])
        self.assertTrue([c for c in calls("install.log") if c.startswith("fake-install rollback --yes")])
        self.assertTrue([n for n in os.listdir(P("backup")) if n.startswith("rolled-back-")])
        self.assertEqual(calls("systemctl.log").count("reboot"), 2)
        # not installed again automatically, Check now included; "Install anyway" does
        os.unlink(P("units.down"))
        st = self.run_cycle({"check": "1"})
        self.assertEqual(st["lastResult"], "skipped")
        self.assertEqual(self.prefix_release(), "v0.5.0")
        self.run_cycle({"download": "1"})
        self.assertEqual(self.prefix_release(), "v0.6.0")

    def test_app_never_draws(self):
        with open(P("base.apk"), "w") as f:
            f.write("apk")
        self.run_cycle()
        ru.cmd_health()
        st = ru.load_state()
        self.assertEqual(st["lastResult"], "rolled-back")
        self.assertIn("picture", st["lastMessage"])
        self.assertEqual(self.prefix_release(), "v0.5.0")

    def test_rollback_without_a_backup_set(self):
        # an installer run that changed nothing but the link leaves no set: the link alone goes back,
        # and an older set is not rolled back by mistake
        os.makedirs(P("backup", "20261101-000000"))
        with open(P("backup", "20261101-000000", "dist.before"), "w") as f:
            f.write(P("opt", "teslalinux.d", "older") + "\n")
        os.environ["FAKE_INSTALL_NO_SET"] = "1"
        self.run_cycle()
        self.assertEqual(self.prefix_release(), "v0.6.0")
        with open(P("units.down"), "w") as f:
            f.write("nginx.service\n")
        ru.cmd_health()
        self.assertEqual(self.prefix_release(), "v0.5.0")
        self.assertTrue(os.path.isdir(P("backup", "20261101-000000")))
        self.assertFalse([c for c in calls("install.log") if c.startswith("fake-install rollback")])

    def test_failed_install_goes_back(self):
        os.environ["FAKE_INSTALL_FAIL"] = "after-switch"
        st = self.run_cycle()
        self.assertEqual(st["lastResult"], "install-failed")
        self.assertIn("exited 1", st["lastMessage"])
        self.assertEqual(self.prefix_release(), "v0.5.0")
        self.assertIsNone(st["health"])
        self.assertEqual(st["bad"]["v0.6.0"]["kind"], "install")

    def test_go_back_by_hand(self):
        self.app_drew()
        self.run_cycle()
        self.app_drew()
        ru.cmd_health()
        self.assertEqual(ru.load_state()["lastResult"], "installed")
        st = self.run_cycle({"go-back": "1"})
        self.assertEqual(st["lastResult"], "went-back")
        self.assertEqual(self.prefix_release(), "v0.5.0")
        self.assertEqual(st["bad"]["v0.6.0"]["kind"], "went-back")
        st = self.run_cycle({"check": "1"})
        self.assertEqual(self.prefix_release(), "v0.5.0")
        self.assertIsNone(ru.previous_of(st))  # nothing older to go back to now

    def test_go_back_without_previous(self):
        st = self.run_cycle({"go-back": "1"})
        self.assertEqual(st["lastResult"], "rollback-impossible")
        self.assertEqual(self.prefix_release(), "v0.5.0")


class Unavailable(Base):
    def test_reasons(self):
        real = sysm.unavailable_reason_orig
        self.assertIsNone(real())  # a release install (systemd stands in through the fake systemctl)
        os.unlink(P("opt", "teslalinux"))
        os.symlink(make_dist_dir("20261124-local", ""), P("opt", "teslalinux"))
        self.assertIn("built from the source", real())
        os.unlink(P("opt", "teslalinux"))
        self.assertIn("Not installed from a release", real())
        NET["unavailable"] = "the Alpine image"
        st = self.run_cycle()
        self.assertEqual(st["lastResult"], "unavailable")
        self.assertFalse(SRV_LOG)

    def test_repo_from_manifest_or_installer(self):
        os.environ.pop("RELEASE_UPDATE_REPO")
        try:
            self.assertEqual(sysm.release_repo(), "Alexa-RR/teslalinux")
            with open(os.path.join(self.old_dist, "MANIFEST"), "a") as f:
                f.write("release_repo=fork/teslalinux\n")
            self.assertEqual(sysm.release_repo(), "fork/teslalinux")
        finally:
            os.environ["RELEASE_UPDATE_REPO"] = "owner/tl"


class UplinkRule(unittest.TestCase):
    def test_the_app_updaters_rule(self):
        sysm.APPUPDATE = os.path.join(REPO, "tools", "appupdate", "teslalinux-appupdate")
        sysm._appupdate = None
        au = sysm.appupdate()
        try:
            # an LTE stick NetworkManager guesses unmetered (guess-no) is still metered
            au.uplink_metered = lambda: (4, True, "eth1")
            self.assertEqual(REAL_DOWNLOAD_ALLOWED(False)[0], False)
            self.assertEqual(REAL_DOWNLOAD_ALLOWED(True)[0], True)  # "Download over mobile data"
            au.uplink_metered = lambda: (4, False, "wlan1")
            self.assertEqual(REAL_DOWNLOAD_ALLOWED(False)[:1], (True,))
            au.uplink_metered = lambda: (0, False, None)
            self.assertFalse(REAL_DOWNLOAD_ALLOWED(False)[0])  # unknown counts as metered
        finally:
            sysm.APPUPDATE, sysm._appupdate = P("none"), None


class Api(Base):
    def test_state_and_actions(self):
        sys.path.insert(0, os.path.join(REPO, "services", "api"))
        os.environ.update({"TESLALINUX_RELEASE_UPDATE": os.path.join(REPO, "host", "teslalinux-release-update"),
                           "TESLALINUX_RELEASE_UPDATE_DIR": P("state"), "TESLALINUX_APPLY_SUDO": "0",
                           "TESLALINUX_DIST_MANIFEST": P("opt", "teslalinux", "MANIFEST"),
                           "TESLALINUX_BACKUP_ROOT": P("backup")})
        import api
        api.DIST_MANIFEST = P("opt", "teslalinux", "MANIFEST")
        api.RELEASE_UPDATE_DIR = P("state")
        NET["unmetered"] = False
        self.run_cycle()
        s = api.release_update_state()
        self.assertTrue(s["supported"] or "Alpine" in (s["unsupportedReason"] or ""))
        self.assertEqual(s["installed"]["release"], "v0.5.0")
        self.assertEqual(s["available"]["tag"], "v0.6.0")
        self.assertIn("A topic", s["available"]["notes"])
        self.assertNotIn("curl", s["available"]["notes"])
        self.assertEqual(s["lastResult"], "waiting-unmetered")
        self.assertTrue(s["settings"]["autoUpdate"])
        for bad in (None, [], {}, {"action": "rm -rf"}, {"autoUpdate": "yes"}, {"action": "check", "x": 1}):
            self.assertEqual(api.release_update_action(bad)[0], 400, bad)
        self.assertEqual(api.RELEASE_ACTIONS["downloadMetered"], ["download", "--metered"])


if __name__ == "__main__":
    try:
        unittest.main(verbosity=1)
    finally:
        shutil.rmtree(ROOT, ignore_errors=True)
