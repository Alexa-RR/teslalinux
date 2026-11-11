#!/usr/bin/env python3
"""teslalinux-appupdate without a network, a Google account or a VM.

    python3 tools/appupdate/test/test_appupdate.py        (needs out/apps/com.waze/: the installed Waze set)

* verification against the real Waze 5.25 set: accepted when it is newer; refused for an equal or lower
  versionCode, a different signer (a copy re-signed here with a throwaway RSA key: a valid APK, just not
  Google's), a modified byte, a missing split, the wrong ABI, a minSdk above ATL's SDK level
* the metered/unmetered download decision, backoff, the daily-check rule, the car-connected parser
* the state machine end to end with a fake tlplay and fake systemctl: dispenser down -> backoff and
  "unavailable since"; a bad versionCode is skipped; metered uplink waits; download -> verify -> pending;
  car connected -> waits; idle -> install-app.sh (real) -> health ok; a failed health check -> rollback to
  <pkg>.old with the data snapshot, the version marked bad; a staged update installs at boot
"""
import hashlib
import importlib.machinery
import importlib.util
import json
import os
import random
import shutil
import struct
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
WAZE = os.path.join(REPO, "out", "apps", "com.waze")
TOOLS = os.path.join(REPO, "tools", "apps")
sys.path.insert(0, TOOLS)
import apk_verify  # noqa: E402

_loader = importlib.machinery.SourceFileLoader("appupdate", os.path.join(REPO, "tools", "appupdate", "teslalinux-appupdate"))
_spec = importlib.util.spec_from_loader("appupdate", _loader)
au = importlib.util.module_from_spec(_spec)
_loader.exec_module(au)
au.APPS_TOOLS = TOOLS
au.log = lambda msg: None

WAZE_SET = ["base.apk", "split_config.arm64_v8a.apk", "split_config.en.apk", "split_config.xhdpi.apk"]
SIGNER = "03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7"
VC = 1030736


# ------------------------------------------------------------------ a throwaway v2 signer (tests only)

def _is_probable_prime(n, rnd):
    if n < 4:
        return n in (2, 3)
    for p in (2, 3, 5, 7, 11, 13, 17, 19, 23, 29):
        if n % p == 0:
            return n == p
    d, r = n - 1, 0
    while d % 2 == 0:
        d //= 2
        r += 1
    for _ in range(24):
        x = pow(rnd.randrange(2, n - 1), d, n)
        if x in (1, n - 1):
            continue
        for _ in range(r - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def rsa_key(bits=1024, seed=1):
    rnd = random.Random(seed)
    e = 65537
    while True:
        ps = []
        while len(ps) < 2:
            c = rnd.getrandbits(bits // 2) | (1 << (bits // 2 - 1)) | 1
            if _is_probable_prime(c, rnd) and (c - 1) % e:
                ps.append(c)
        n = ps[0] * ps[1]
        if n.bit_length() == bits:
            return n, e, pow(e, -1, (ps[0] - 1) * (ps[1] - 1))


def _der(tag, content):
    n = len(content)
    if n < 128:
        ln = bytes([n])
    else:
        b = n.to_bytes((n.bit_length() + 7) // 8, "big")
        ln = bytes([0x80 | len(b)]) + b
    return bytes([tag]) + ln + content


def _der_int(v):
    b = v.to_bytes(v.bit_length() // 8 + 1, "big")
    return _der(0x02, b)


def test_certificate(n, e):
    seq = lambda *xs: _der(0x30, b"".join(xs))
    null = b"\x05\x00"
    rsa_oid = _der(0x06, bytes.fromhex("2a864886f70d010101"))
    sha256rsa = seq(_der(0x06, bytes.fromhex("2a864886f70d01010b")), null)
    spki = seq(seq(rsa_oid, null), _der(0x03, b"\x00" + seq(_der_int(n), _der_int(e))))
    name = seq(_der(0x31, seq(_der(0x06, bytes.fromhex("550403")), _der(0x0C, b"teslalinux test key"))))
    validity = seq(_der(0x17, b"260101000000Z"), _der(0x17, b"360101000000Z"))
    tbs = seq(_der(0xA0, _der_int(2)), _der_int(1), sha256rsa, name, validity, name, spki)
    return seq(tbs, sha256rsa, _der(0x03, b"\x00" + b"\x01" * 16)), spki


def resign(src, dst, key):
    """Replace src's APK Signing Block with a v2-only one signed by key (RSASSA-PKCS1-v1_5, SHA-256)."""
    n, e, d = key
    lp = lambda b: struct.pack("<I", len(b)) + b
    with open(src, "rb") as f:
        lay = apk_verify.read_layout(f)
        f.seek(0)
        entries = f.read(lay["block_off"])
        f.seek(lay["cd_off"])
        cd = f.read(lay["cd_size"])
        digest = apk_verify.content_digest(f, lay, "sha256")
    cert, spki = test_certificate(n, e)
    signed = lp(lp(struct.pack("<I", 0x0103) + lp(digest))) + lp(lp(cert)) + lp(b"")
    k = (n.bit_length() + 7) // 8
    t = apk_verify.DIGEST_INFO["sha256"] + hashlib.sha256(signed).digest()
    em = b"\x00\x01" + b"\xff" * (k - len(t) - 3) + b"\x00" + t
    sig = pow(int.from_bytes(em, "big"), d, n).to_bytes(k, "big")
    signer = lp(signed) + lp(lp(struct.pack("<I", 0x0103) + lp(sig))) + lp(spki)
    value = lp(lp(signer))
    pair = struct.pack("<QI", len(value) + 4, apk_verify.V2_ID) + value
    size = len(pair) + 8 + 16
    block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + b"APK Sig Block 42"
    eocd = bytearray(lay["eocd"])
    struct.pack_into("<I", eocd, 16, len(entries) + len(block))
    with open(dst, "wb") as f:
        f.write(entries + block + cd + bytes(eocd))


KEY = rsa_key()


def rd(path):
    with open(path) as f:
        return f.read()


def make_set(dst, link=WAZE_SET, drop=(), extra=None):
    os.makedirs(dst)
    for f in link:
        if f not in drop:
            os.symlink(os.path.join(WAZE, f), os.path.join(dst, f))
    for name, maker in (extra or {}).items():
        maker(os.path.join(dst, name))
    return dst


@unittest.skipUnless(os.path.isfile(os.path.join(WAZE, "base.apk")), "needs out/apps/com.waze")
class Verification(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="tl-au-verify-")

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def d(self, name):
        return os.path.join(self.tmp, name)

    def installed(self, vc):
        return {"package": "com.waze", "versionCode": vc, "versionName": "x", "signer": SIGNER}

    def test_newer_set_accepted(self):
        ok, facts = au.verify_candidate(WAZE, self.installed(VC - 1))
        self.assertTrue(ok, facts)
        self.assertEqual(facts["versionCode"], VC)
        self.assertEqual(facts["files"], sorted(WAZE_SET))

    def test_equal_or_lower_refused(self):
        for vc in (VC, VC + 1):
            ok, why = au.verify_candidate(WAZE, self.installed(vc))
            self.assertFalse(ok)
            self.assertIn("not higher", why)

    def test_different_signer_refused(self):
        cand = make_set(self.d("resigned"), drop=("base.apk",), extra={"base.apk": lambda p: resign(os.path.join(WAZE, "base.apk"), p, KEY)})
        # the re-signed base is a valid APK (our verifier accepts it) ...
        scheme, signer = apk_verify.verify(os.path.join(cand, "base.apk"), 32)
        self.assertEqual(scheme, "v2")
        self.assertNotEqual(signer, SIGNER)
        # ... and is refused because its certificate is not the installed app's
        ok, why = au.verify_candidate(cand, self.installed(VC - 1))
        self.assertFalse(ok)
        self.assertIn("signing certificate", why)

    def test_first_install_needs_the_pinned_signer(self):
        # nothing installed: Waze's known signer is the reference
        self.assertEqual(au.FIRST_SIGNER, SIGNER)
        ok, facts = au.verify_candidate(WAZE, None)
        self.assertTrue(ok, facts)
        cand = make_set(self.d("resigned-first"), drop=("base.apk",), extra={"base.apk": lambda p: resign(os.path.join(WAZE, "base.apk"), p, KEY)})
        ok, why = au.verify_candidate(cand, None)
        self.assertFalse(ok)
        self.assertIn("pinned", why)
        saved = au.FIRST_SIGNER
        try:
            au.FIRST_SIGNER = ""
            ok, why = au.verify_candidate(WAZE, None)
            self.assertFalse(ok)
            self.assertIn("no pinned signer", why)
        finally:
            au.FIRST_SIGNER = saved

    def test_one_foreign_split_refused(self):
        cand = make_set(self.d("resigned-split"), drop=("split_config.en.apk",),
                        extra={"split_config.en.apk": lambda p: resign(os.path.join(WAZE, "split_config.en.apk"), p, KEY)})
        ok, why = au.verify_candidate(cand, self.installed(VC - 1))
        self.assertFalse(ok)
        self.assertIn("split_config.en.apk is not com.waze signed by", why)

    def test_modified_byte_refused(self):
        def flip(p):
            shutil.copy(os.path.join(WAZE, "split_config.xhdpi.apk"), p)
            with open(p, "r+b") as f:
                f.seek(4096)
                b = f.read(1)
                f.seek(4096)
                f.write(bytes([b[0] ^ 0x40]))
        cand = make_set(self.d("tampered"), drop=("split_config.xhdpi.apk",), extra={"split_config.xhdpi.apk": flip})
        ok, why = au.verify_candidate(cand, self.installed(VC - 1))
        self.assertFalse(ok)
        self.assertIn("content digest mismatch", why)

    def test_truncated_refused(self):
        def cut(p):
            with open(os.path.join(WAZE, "split_config.en.apk"), "rb") as s, open(p, "wb") as f:
                f.write(s.read(60000))
        cand = make_set(self.d("truncated"), drop=("split_config.en.apk",), extra={"split_config.en.apk": cut})
        ok, why = au.verify_candidate(cand, self.installed(VC - 1))
        self.assertFalse(ok)
        self.assertIn("split_config.en.apk", why)

    def test_missing_split_refused(self):
        for drop, expect in (("split_config.xhdpi.apk", "split_config.xhdpi.apk missing"),
                             ("split_config.en.apk", "split_config.en.apk missing"),
                             ("split_config.arm64_v8a.apk", "no native code for arm64_v8a")):
            cand = make_set(self.d("missing-" + drop), drop=(drop,))
            ok, why = au.verify_candidate(cand, self.installed(VC - 1))
            self.assertFalse(ok, drop)
            self.assertIn(expect, why)

    def test_wrong_abi_refused(self):
        ok, why = au.verify_candidate(WAZE, self.installed(VC - 1), abi="x86_64")
        self.assertFalse(ok)
        self.assertIn("no native code for x86_64", why)

    def test_split_named_wrongly_refused(self):
        cand = make_set(self.d("misnamed"), drop=("split_config.arm64_v8a.apk",),
                        extra={"split_config.arm64_v8a.apk": lambda p: os.symlink(os.path.join(WAZE, "split_config.en.apk"), p)})
        ok, why = au.verify_candidate(cand, self.installed(VC - 1))
        self.assertFalse(ok)
        self.assertIn("manifest says split 'config.en'", why)

    def test_min_sdk_above_atl_refused(self):
        ok, why = au.verify_candidate(WAZE, self.installed(VC - 1), sdk=31)
        self.assertFalse(ok)
        self.assertIn("minSdk 32", why)

    def test_no_density_split_refused(self):
        # no density split at all is refused; a nearer one than ours is taken (density_order), as with
        # APKPure's xxhdpi-only Waze 5.26 (the live test in docs/APP-UPDATES.md)
        cand = make_set(self.d("nodensity"), drop=("split_config.xhdpi.apk",))
        ok, facts = au.verify_candidate(cand, self.installed(VC - 1))
        self.assertFalse(ok)
        self.assertIn("no other density split", facts)
        ok, facts = au.verify_candidate(WAZE, self.installed(VC - 1), density="xxhdpi")
        self.assertTrue(ok, facts)  # xxhdpi wanted, only xhdpi there: the nearest lower one is taken
        self.assertEqual(facts["density"], "xhdpi")

    def test_language_split_rules(self):
        cand = make_set(self.d("no-lang"), drop=("split_config.en.apk",))
        # Play offered no split for the language: base.apk's strings are used, the set is fine
        ok, why = au.verify_candidate(cand, self.installed(VC - 1), lang_required=False)
        self.assertTrue(ok, why)
        # a language Play does offer must be there
        ok, why = au.verify_candidate(WAZE, self.installed(VC - 1), lang="he")
        self.assertFalse(ok)
        self.assertIn("split_config.he.apk missing", why)
        self.assertEqual(au.lang_splits("he"), ["he", "iw"])

    def test_other_package_refused(self):
        inst = self.installed(VC - 1)
        inst["package"] = "com.example"
        ok, why = au.verify_candidate(WAZE, inst)
        self.assertFalse(ok)
        self.assertIn("not the installed com.example", why)


class Decisions(unittest.TestCase):
    def test_metered(self):
        # NMMetered: 0 unknown, 1 yes, 2 no, 3 guess-yes (LTE modem), 4 guess-no (Wi-Fi, Ethernet)
        self.assertEqual([au.download_allowed(m, False)[0] for m in (0, 1, 2, 3, 4, None)], [False, False, True, False, True, False])
        self.assertTrue(all(au.download_allowed(m, True)[0] for m in (0, 1, 2, 3, 4, None)))
        self.assertIn("waiting for Wi-Fi", au.download_allowed(3, False)[1])

    def test_backoff(self):
        mid = lambda: 0.5
        self.assertEqual(au.backoff_delay(0, mid), 0)
        self.assertEqual(au.backoff_delay(1, mid), au.BACKOFF_BASE)
        self.assertEqual(au.backoff_delay(2, mid), 2 * au.BACKOFF_BASE)
        self.assertEqual(au.backoff_delay(30, mid), au.BACKOFF_MAX)
        lo, hi = au.backoff_delay(3, lambda: 0.0), au.backoff_delay(3, lambda: 0.999)
        self.assertTrue(0.79 * 4 * au.BACKOFF_BASE <= lo < hi <= 1.2 * 4 * au.BACKOFF_BASE)

    def test_check_due(self):
        st, now = au.new_state(), 10 ** 9
        self.assertTrue(au.check_due(st, now, False))  # never checked
        st["lastCheckOk"] = now - 3600
        self.assertFalse(au.check_due(st, now, False))
        self.assertTrue(au.check_due(st, now, True))
        st["lastCheckOk"] = now - 2 * 86400
        self.assertTrue(au.check_due(st, now, False))
        st.update(failures=2, nextTryAt=now + 60, lastCheckOk=now - 3600)
        self.assertFalse(au.check_due(st, now, False))  # backing off
        self.assertTrue(au.check_due(st, now + 61, False))  # retry due

    def test_established_ports(self):
        tcp = ("  sl  local_address rem_address   st tx_queue rx_queue\n"
               "   0: 0100007F:2383 0100007F:D2F0 01 00000000:00000000\n"  # 127.0.0.1:9091 ESTABLISHED
               "   1: 00000000:270F 00000000:0000 0A 00000000:00000000\n")  # :9999 LISTEN
        tcp6 = ("  sl  local_address rem_address st\n"
                "   0: 00000000000000000000000001000000:01BB 0000000000000000FFFF00000A0A0A02:C350 01 0\n")
        self.assertEqual(au.established_local_ports([tcp, tcp6]), {9091, 443})

    def test_dispenser_url_rule(self):
        for u in ("", "https://auroraoss.com/api/auth", "https://my.dispenser.example:8443/v1/auth"):
            self.assertTrue(au.valid_dispenser_url(u), u)
        for u in ("http://auroraoss.com/api/auth", "https://a b/", "https://user:pw@x.org/", "file:///etc/passwd", "https://x/\"y"):
            self.assertFalse(au.valid_dispenser_url(u), u)

    def test_device_locale(self):
        saved = au.LOCALE_ENV
        try:
            with tempfile.NamedTemporaryFile("w", suffix=".env", delete=False) as f:
                f.write("# x\nTESLALINUX_LOCALE=he_IL\nTESLALINUX_TIMEZONE=Asia/Jerusalem\n")
            au.LOCALE_ENV = f.name
            self.assertEqual(au.device_locale(), "he_IL")
            au.LOCALE_ENV = f.name + ".missing"
            self.assertEqual(au.device_locale(), "en_US")
        finally:
            au.LOCALE_ENV = saved
            os.unlink(f.name)

    def test_density_order(self):
        self.assertEqual(au.density_order("xhdpi"), ["xhdpi", "xxhdpi", "xxxhdpi", "hdpi", "mdpi", "ldpi"])
        self.assertEqual(au.density_order("hdpi")[:3], ["hdpi", "xhdpi", "xxhdpi"])

    def test_health_verdict(self):
        self.assertEqual(au.health_verdict(True, 5, 0, 900, 3), "ok")
        self.assertEqual(au.health_verdict(False, 5, 0, 900, 3), "wait")
        self.assertTrue(au.health_verdict(False, 901, 0, 900, 3).startswith("fail: no MainScreenVisible"))
        self.assertTrue(au.health_verdict(False, 30, 3, 900, 3).startswith("fail: the app crashed 3 times"))


FAKE_TLPLAY = r'''#!/usr/bin/env python3
import json, os, shutil, sys
mode = json.load(open(os.environ["FAKE_TLPLAY_MODE"]))
args = sys.argv[1:]
with open(os.environ["FAKE_TLPLAY_LOG"], "a") as f:
    f.write(" ".join(args) + "\n")
def out(o, code=0):
    print(json.dumps(o)); sys.exit(code)
if mode.get("dispenserDown"):
    out({"ok": False, "error": "dispenser", "message": "https://auroraoss.com/api/auth answered HTTP 503", "exitCode": 10}, 10)
cmd = [a for a in args if not a.startswith("--")]
if "details" in args:
    out({"ok": True, "package": "com.waze", "versionCode": mode["latest"], "versionName": "v%d" % mode["latest"]})
if "download" in args:
    dst = args[args.index("--out") + 1]
    for f in os.listdir(mode["source"]):
        if f.endswith(".apk"):
            shutil.copy(os.path.join(mode["source"], f), os.path.join(dst, f))
    out({"ok": True, "totalBytes": 190000000, "files": []})
out({"ok": False, "error": "usage", "message": "?"}, 2)
'''


@unittest.skipUnless(os.path.isfile(os.path.join(WAZE, "base.apk")), "needs out/apps/com.waze")
class StateMachine(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="tl-au-sm-")
        t = lambda *p: os.path.join(self.tmp, *p)
        self.t = t
        os.makedirs(t("apps"))
        os.makedirs(t("data"))
        with open(t("tlplay"), "w") as f:
            f.write(FAKE_TLPLAY)
        os.chmod(t("tlplay"), 0o755)
        with open(t("appctl"), "w") as f:
            f.write('#!/bin/sh\necho "$1" >> "%s"\n' % t("appctl.log"))
        os.chmod(t("appctl"), 0o755)
        au.LANG = "en"
        os.environ.update(FAKE_TLPLAY_MODE=t("mode.json"), FAKE_TLPLAY_LOG=t("tlplay.log"), APPUPDATE_FAKE_APPCTL=t("appctl"),
                          APPUPDATE_IDLE_RECHECK="0")
        self.saved = {k: getattr(au, k) for k in ("APPS_DIR", "STATE_DIR", "SETTINGS_FILE", "AUTH_FILE", "TLPLAY", "APP_DATA_DIR",
                                                   "ART_CACHE", "HEALTH_ENV", "APP_USER", "nm_metered", "car_state", "marker_logged",
                                                   "unit_restarts", "boot_time")}
        au.APPS_DIR, au.STATE_DIR, au.SETTINGS_FILE = t("apps"), t("state"), t("settings.json")
        au.AUTH_FILE, au.TLPLAY, au.APP_DATA_DIR = t("play", "auth.json"), t("tlplay"), t("data", "waze")
        au.ART_CACHE, au.HEALTH_ENV, au.APP_USER = t("art"), t("run", "health.env"), ""
        self.metered = 4
        self.connected = False
        self.marker = True
        au.nm_metered = lambda: self.metered
        au.car_state = lambda: (self.connected, "test")
        au.marker_logged = lambda since: self.marker
        au.unit_restarts = lambda: 0
        au.boot_time = lambda: 0
        self.mode(latest=VC)
        # "installed": the real set (symlinks; install-app.sh parses it) labelled as an older versionCode
        # in installed.json, which is what the updater reads
        self.fake_install(t("apps", "com.waze"), VC - 100, "5.24")
        os.makedirs(t("art", "arm64"))
        open(t("art", "arm64", "apps@com.waze@base.apk@classes.dex"), "w").close()
        os.makedirs(au.APP_DATA_DIR)
        with open(os.path.join(au.APP_DATA_DIR, "db"), "w") as f:
            f.write("old-data")

    def tearDown(self):
        for k, v in self.saved.items():
            setattr(au, k, v)
        shutil.rmtree(self.tmp, ignore_errors=True)

    def mode(self, **kw):
        m = {"latest": VC, "source": WAZE}
        m.update(kw)
        with open(self.t("mode.json"), "w") as f:
            json.dump(m, f)

    def fake_install(self, d, vc, vn):
        make_set(d)
        with open(os.path.join(d, "installed.json"), "w") as f:
            json.dump({"package": "com.waze", "versionCode": vc, "versionName": vn, "signer": SIGNER}, f)

    def state(self):
        return au.load_state()

    def calls(self, name):
        try:
            with open(self.t(name)) as f:
                return f.read().split("\n")[:-1]
        except OSError:
            return []

    def test_dispenser_down_backs_off_and_reports(self):
        self.mode(dispenserDown=True)
        au.cmd_run(False)
        st = self.state()
        self.assertEqual(st["lastResult"], "dispenser-unavailable")
        self.assertIsNotNone(st["serverUnavailableSince"])
        self.assertEqual(st["failures"], 1)
        self.assertGreater(st["nextTryAt"], st["lastCheck"])
        since = st["serverUnavailableSince"]
        au.cmd_run(False)  # inside the backoff: Play is not asked again
        self.assertEqual(len(self.calls("tlplay.log")), 1)
        au.cmd_run(True)  # "Check now" ignores the backoff; "unavailable since" keeps its first time
        st = self.state()
        self.assertEqual((st["failures"], st["serverUnavailableSince"]), (2, since))
        self.mode(latest=VC - 100)
        au.cmd_run(True)
        st = self.state()
        self.assertEqual((st["lastResult"], st["failures"], st["serverUnavailableSince"]), ("up-to-date", 0, None))
        self.assertEqual(self.calls("appctl.log"), [])  # the app was never touched

    def test_metered_waits_then_downloads_verifies_and_waits_for_idle(self):
        self.metered = 3  # LTE: guess-yes
        self.connected = True
        au.cmd_run(False)
        st = self.state()
        self.assertEqual(st["lastResult"], "waiting-unmetered")
        self.assertEqual(st["wanted"]["versionCode"], VC)
        self.assertFalse(any("download" in c for c in self.calls("tlplay.log")))
        self.metered = 4  # Wi-Fi came up (the NM hook kicks a run)
        au.cmd_run(False)
        st = self.state()
        self.assertEqual(st["pending"]["versionCode"], VC, st["lastMessage"])
        self.assertEqual(st["lastResult"], "waiting-idle")
        self.assertTrue(os.path.isfile(self.t("state", "pending", "base.apk")))
        self.assertEqual(len([c for c in self.calls("tlplay.log") if c.startswith("--source apkpure") and "details" in c]), 1)
        dl = [c for c in self.calls("tlplay.log") if "download" in c][0]
        for s in ("config.arm64_v8a", "config.xhdpi", "config.en"):
            self.assertIn("--split " + s, dl)
        self.assertEqual(self.calls("appctl.log"), [])  # car connected: nothing installed

    def test_allow_over_lte_setting(self):
        self.metered = 3
        self.connected = True
        with open(au.SETTINGS_FILE, "w") as f:
            json.dump({"allowMetered": True}, f)
        au.cmd_run(False)
        self.assertEqual(self.state()["pending"]["versionCode"], VC)

    def test_auto_update_off(self):
        with open(au.SETTINGS_FILE, "w") as f:
            json.dump({"autoUpdate": False}, f)
        au.cmd_run(False)
        self.assertEqual(self.state()["lastResult"], "disabled")
        self.assertEqual(self.calls("tlplay.log"), [])

    def test_verify_failure_marks_bad_and_never_downloads_again(self):
        bad = os.path.join(self.tmp, "badset")
        make_set(bad, drop=("split_config.xhdpi.apk",))
        self.mode(source=bad)
        au.cmd_run(False)
        st = self.state()
        self.assertEqual(st["lastResult"], "verify-failed")
        self.assertIn(str(VC), st["bad"])
        self.assertIn("split_config.xhdpi.apk missing", st["bad"][str(VC)]["reason"])
        self.assertFalse(os.path.exists(self.t("state", "staging", str(VC))))
        au.cmd_run(True)
        self.assertEqual(self.state()["lastResult"], "skipping-bad")
        self.assertEqual(len([c for c in self.calls("tlplay.log") if "download" in c]), 1)

    def test_idle_install_health_ok(self):
        au.cmd_run(False)
        st = self.state()
        self.assertEqual(st["lastResult"], "updated", st["lastMessage"])
        self.assertEqual(st["installed"]["versionCode"], VC)
        self.assertIsNone(st["health"])
        self.assertIsNone(st["pending"])
        self.assertEqual(self.calls("appctl.log"), ["stop", "start"])
        inst = os.path.join(au.APPS_DIR, "com.waze")
        self.assertEqual(sorted(f for f in os.listdir(inst) if f.endswith(".apk")), sorted(WAZE_SET))
        self.assertEqual(json.loads(rd(os.path.join(inst + ".old", "installed.json")))["versionCode"], VC - 100)
        self.assertEqual(os.listdir(self.t("art", "arm64")), [])  # stale AOT code removed
        self.assertFalse(os.path.exists(au.HEALTH_ENV))
        self.assertFalse(os.path.exists(self.t("state", "data-before")))
        self.assertIn("Waze 5.25.0.1 is running", st["notices"][-1]["text"])

    def test_health_failure_rolls_back_with_data(self):
        self.marker = False
        au.HEALTH_TIMEOUT, saved_timeout = 0, au.HEALTH_TIMEOUT
        try:
            # the new version would have migrated the data: the snapshot must bring the old one back
            orig_install = au.install_pending

            def install_and_migrate(st, now):
                orig_install(st, now)
                with open(os.path.join(au.APP_DATA_DIR, "db"), "w") as f:
                    f.write("migrated-by-new-version")
            au.install_pending = install_and_migrate
            au.cmd_run(False)
        finally:
            au.HEALTH_TIMEOUT = saved_timeout
            au.install_pending = orig_install
        st = self.state()
        self.assertEqual(st["lastResult"], "rolled-back", st["lastMessage"])
        self.assertEqual(st["installed"]["versionCode"], VC - 100)
        self.assertIn(str(VC), st["bad"])
        self.assertIn("no MainScreenVisible", st["bad"][str(VC)]["reason"])
        self.assertEqual(rd(os.path.join(au.APP_DATA_DIR, "db")), "old-data")
        self.assertFalse(os.path.exists(os.path.join(au.APPS_DIR, "com.waze.old")))
        self.assertEqual(self.calls("appctl.log"), ["stop", "start", "stop", "start"])
        self.assertIn("failed its health check", st["notices"][-1]["text"])
        # the same version is never tried again; a newer one is
        au.cmd_run(True)
        self.assertEqual(self.state()["lastResult"], "skipping-bad")

    def test_boot_install_then_health_service(self):
        self.connected = True
        au.cmd_run(False)
        self.assertEqual(self.state()["lastResult"], "waiting-idle")
        au.cmd_boot()  # before teslalinux-atl.service
        st = self.state()
        self.assertEqual(st["lastResult"], "installed")
        self.assertEqual(st["health"]["versionCode"], VC)
        self.assertTrue(os.path.isfile(self.t("state", "health.json")))  # the health unit's condition
        with open(au.HEALTH_ENV) as f:
            self.assertIn("ANDROID_LOG_TAGS=*:d", f.read())
        self.assertEqual(self.calls("appctl.log"), [])  # at boot the app is not running yet
        au.cmd_health()  # after teslalinux-atl.service
        st = self.state()
        self.assertEqual(st["lastResult"], "updated")
        self.assertFalse(os.path.exists(self.t("state", "health.json")))

    def test_manual_rollback(self):
        au.cmd_run(False)
        self.assertEqual(au.cmd_rollback(), 0)
        st = self.state()
        self.assertEqual(st["installed"]["versionCode"], VC - 100)
        self.assertIn(str(VC), st["bad"])
        self.assertEqual(au.cmd_rollback(), 1)  # no .old left: refused, said so
        self.assertEqual(self.state()["lastResult"], "rollback-impossible")

    def test_play_source_needs_the_advanced_switch(self):
        with open(au.SETTINGS_FILE, "w") as f:
            json.dump({"source": "play"}, f)
        au.cmd_run(True)
        self.assertEqual(self.state()["lastResult"], "manual-only")
        self.assertEqual(self.calls("tlplay.log"), [])  # Play is never contacted while it is off
        with open(au.SETTINGS_FILE, "w") as f:
            json.dump({"source": "play", "playEnabled": True, "dispenserUrl": "https://d.example/auth"}, f)
        au.cmd_run(True)
        c = self.calls("tlplay.log")[0]
        self.assertTrue(c.startswith("--source play --auth-file"), c)
        self.assertIn("--dispenser https://d.example/auth", c)

    def test_manual_source_never_checks(self):
        with open(au.SETTINGS_FILE, "w") as f:
            json.dump({"source": "manual"}, f)
        au.cmd_run(True)
        self.assertEqual(self.state()["lastResult"], "manual-only")
        self.assertEqual(self.calls("tlplay.log"), [])

    def upload(self, path, name):
        """receive-upload with the file on stdin, as api.py runs it; returns its JSON line."""
        import io
        import contextlib
        saved_stdin = sys.stdin
        with open(path, "rb") as f:
            data = f.read()
        sys.stdin = io.TextIOWrapper(io.BytesIO(data))
        out = io.StringIO()
        try:
            with contextlib.redirect_stdout(out):
                au.cmd_receive_upload(len(data), name)
        finally:
            sys.stdin = saved_stdin
        return json.loads(out.getvalue().strip().splitlines()[-1])

    def bundle(self, name, members):
        import zipfile
        p = self.t(name)
        with zipfile.ZipFile(p, "w", zipfile.ZIP_STORED) as z:
            for arc, src in members:
                if src is None:
                    z.writestr(arc, "{}")
                else:
                    z.write(src, arc)
        return p

    def test_upload_same_version_nothing_to_do(self):
        # out/apps/com.waze as an APKMirror-style .apkm (base.apk + split_config.*.apk + info.json)
        os.unlink(os.path.join(au.APPS_DIR, "com.waze", "installed.json"))  # installed = the real 5.25.0.1
        b = self.bundle("waze.apkm", [(f, os.path.join(WAZE, f)) for f in WAZE_SET] + [("info.json", None)])
        res = self.upload(b, "Waze 5.25.0.1.apkm")
        self.assertEqual(res["state"], "nothing-to-do", res)
        self.assertIn("same version, nothing to do", res["message"])
        self.assertIsNone(self.state()["pending"])
        self.assertEqual(au.upload_status()["state"], "nothing-to-do")
        self.assertFalse(os.path.exists(self.t("state", "upload", "bundle")))  # never kept

    def test_upload_resigned_rejected(self):
        resigned = self.t("resigned-base.apk")
        resign(os.path.join(WAZE, "base.apk"), resigned, KEY)
        b = self.bundle("evil.apkm", [("base.apk", resigned)] + [(f, os.path.join(WAZE, f)) for f in WAZE_SET[1:]])
        res = self.upload(b, "evil.apkm")
        self.assertEqual(res["state"], "rejected", res)
        self.assertIn("signing certificate", res["message"])
        st = self.state()
        self.assertIsNone(st["pending"])
        self.assertEqual(st["bad"], {})  # a refused upload marks no version bad
        self.assertEqual(self.calls("appctl.log"), [])

    def test_upload_newer_xapk_staged_and_installed_when_idle(self):
        # installed.json says 5.24 (VC-100): the real set is "newer"; XAPK naming (<pkg>.apk, config.X.apk)
        members = [("com.waze.apk", os.path.join(WAZE, "base.apk")), ("manifest.json", None), ("icon.png", None)]
        members += [(f[len("split_"):], os.path.join(WAZE, f)) for f in WAZE_SET[1:]]
        res = self.upload(self.bundle("waze.xapk", members), "waze.xapk")
        self.assertEqual(res["state"], "staged", res)
        st = self.state()
        self.assertEqual(st["pending"]["versionCode"], VC)
        self.assertEqual(sorted(os.listdir(self.t("state", "pending"))), sorted(WAZE_SET))
        self.assertEqual(self.calls("appctl.log"), ["kick"])  # the service is started to install when idle
        au.cmd_run(False)  # what the kicked service does: car not connected -> install + health
        self.assertEqual(self.state()["installed"]["versionCode"], VC)
        self.assertEqual(self.state()["lastResult"], "updated")

    def test_upload_bundletool_apks_and_plain_zip(self):
        names = {"base.apk": "splits/base-master.apk", "split_config.arm64_v8a.apk": "splits/base-arm64_v8a.apk",
                 "split_config.xhdpi.apk": "splits/base-xhdpi.apk", "split_config.en.apk": "splits/base-en.apk"}
        members = [(names[f], os.path.join(WAZE, f)) for f in WAZE_SET] + [("toc.pb", None)]
        res = self.upload(self.bundle("waze.apks", members), "waze.apks")
        self.assertEqual(res["state"], "staged", res)
        os.unlink(self.t("state", "upload.json"))
        res = self.upload(self.bundle("junk.zip", [("readme.txt", None)]), "junk.zip")
        self.assertEqual((res["state"], "holds no APK" in res["message"]), ("rejected", True), res)
        res = self.upload(os.path.join(REPO, "tools", "appupdate", "Cargo.toml"), "x.apkm")
        self.assertEqual((res["state"], "not a ZIP" in res["message"]), ("rejected", True), res)

    def test_upload_missing_abi_rejected(self):
        res = self.upload(self.bundle("noabi.apkm", [(f, os.path.join(WAZE, f)) for f in WAZE_SET if "arm64" not in f]), "noabi.apkm")
        self.assertEqual(res["state"], "rejected", res)
        self.assertIn("no native code for arm64_v8a", res["message"])

    def test_upload_size_limit(self):
        import io
        import contextlib
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(au.cmd_receive_upload(au.MAX_UPLOAD + 1, "big.apkm"), 1)
        self.assertIn("not within", out.getvalue())

    def test_status_has_no_secrets(self):
        os.makedirs(os.path.dirname(au.AUTH_FILE))
        with open(au.AUTH_FILE, "w") as f:
            json.dump({"email": "x@y", "authToken": "SECRET-TOKEN"}, f)
        au.cmd_run(False)
        s = json.dumps(au.status())
        self.assertNotIn("SECRET-TOKEN", s)
        self.assertTrue(au.status()["authCached"])


if __name__ == "__main__":
    unittest.main(verbosity=1)
