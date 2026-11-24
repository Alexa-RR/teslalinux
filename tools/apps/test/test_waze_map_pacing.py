#!/usr/bin/env python3
"""Tests of tools/apps/waze-map-pacing.py's automatic mode that need no Waze APK: the generated frame
limiter class and the checks refusing what they must.

    python3 tools/apps/test/test_waze_map_pacing.py
    WAZE_APP_DIR=/var/lib/teslalinux/apps/com.waze python3 tools/apps/test/test_waze_map_pacing.py
                                         (also checks an installed Waze 5.27.5 as the launcher does)
"""
import hashlib
import importlib.util
import os
import struct
import sys
import tempfile
import unittest
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("waze_map_pacing", os.path.join(HERE, "..", "waze-map-pacing.py"))
wp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wp)

V = wp.VERIFIED_AUTO[1030750]


class LimiterClass(unittest.TestCase):
    def test_dex(self):
        data = wp.limiter_class_dex(V)
        dex = wp.Dex(data)
        self.assertEqual(data[:8], b"dex\n035\0")
        self.assertEqual(struct.unpack_from("<I", data, 0x20)[0], len(data))
        self.assertEqual(data[12:32], hashlib.sha1(data[32:]).digest())
        self.assertEqual(struct.unpack_from("<I", data, 8)[0], zlib.adler32(data[12:]) & 0xFFFFFFFF)
        strings = [dex.string(i) for i in range(dex.string_ids_size)]
        self.assertEqual(strings, sorted(strings))
        # one class: Waze's limiter by name, public and open to its subclass, extending ATL's
        defs = dex.class_defs()
        self.assertEqual([(d[0], d[1], d[2]) for d in defs], [(V["class"], 0x1, wp.ATL_LIMITER)])
        methods = {(n, p): (a, c) for n, p, a, c in dex.class_methods(defs[0][4])}
        self.assertEqual(sorted((n, p, a) for (n, p), (a, _c) in methods.items()), sorted(V["api"]))
        self.assertEqual(dex.class_fields(defs[0][4]), [])
        code = {k: wp.disassemble(dex, dex.code(c)[3]) for k, (_a, c) in methods.items()}
        L = wp.ATL_LIMITER
        self.assertEqual(code[("<init>", "()V")], ["const/16 v0 30", "invoke-direct {v1,v0} %s-><init>(I)V" % L, "return-void"])
        self.assertEqual(code[("c", "(I)V")], ["invoke-virtual {v0,v1} %s->setAppFrameRate(I)V" % L, "return-void"])
        self.assertEqual(code[("d", "()Z")], ["invoke-virtual {v0} %s->isTooSoon()Z" % L, "move-result v0", "return v0"])
        self.assertEqual(code[("e", "(J)V")], ["invoke-virtual {v0,v1,v2} %s->setFrameStarted(J)V" % L, "return-void"])
        # registers: the parameters are the last ones (this, then a long's two)
        regs = {k: dex.code(c)[:2] for k, (_a, c) in methods.items()}
        self.assertEqual(regs, {("<init>", "()V"): (2, 1), ("c", "(I)V"): (2, 2), ("d", "()Z"): (1, 1), ("e", "(J)V"): (3, 3)})

    def test_checks_refuse_other_code(self):
        # the generated class is not Waze's limiter: it extends ATL's class and has no fields
        dex = wp.Dex(wp.limiter_class_dex(V))
        with self.assertRaises(wp.Mismatch):
            wp.check_limiter(dex, V)
        # an APK whose dexes lack the subclass the version has is refused before anything is written
        with self.assertRaises(wp.Mismatch):
            wp.check_references([("classes.dex", dex)], V)

    def test_atl_without_the_class(self):
        with tempfile.TemporaryDirectory() as d:
            jar = os.path.join(d, "api-impl.jar")
            with zipfile.ZipFile(jar, "w") as z:
                z.writestr("classes.dex", wp.limiter_class_dex(V))
            with self.assertRaises(wp.Mismatch) as e:
                wp.check_atl(jar, V)
            self.assertIn("ATLFrameRateLimiter", str(e.exception))

    def test_disassembler_refuses_unknown(self):
        with self.assertRaises(wp.Mismatch):
            wp.disassemble(None, [0x00ff])

    def test_atl_without_the_policy_fields(self):
        # an ATL whose limiter has the methods but not POLICY / POLICY_DISPLAY: the map loop class
        # could not initialise
        atl = {"class": wp.ATL_LIMITER, "access": 0x1, "super": wp.OBJECT, "interfaces": [], "fields": [],
               "methods": [(n, p, 0x1 if n != "<init>" else 0x10001, (4, 4, 0, [0x000e]))
                           for n, p in sorted(V["api"].values())]}
        with tempfile.TemporaryDirectory() as d:
            jar = os.path.join(d, "api-impl.jar")
            with zipfile.ZipFile(jar, "w") as z:
                z.writestr("classes.dex", wp.write_dex([atl]))
            with self.assertRaises(wp.Mismatch) as e:
                wp.check_atl(jar, V)
            self.assertIn("POLICY", str(e.exception))
            atl["fields"] = [("POLICY", "I", 0x19), ("POLICY_DISPLAY", "I", 0x19)]
            with zipfile.ZipFile(jar, "w") as z:
                z.writestr("classes.dex", wp.write_dex([atl]))
            wp.check_atl(jar, V)

    @unittest.skipUnless(os.environ.get("WAZE_APP_DIR"), "WAZE_APP_DIR names no installed Waze")
    def test_installed_waze(self):
        vc, _vn, v, dexes = wp.check_auto(os.environ["WAZE_APP_DIR"])
        self.assertEqual(vc, 1030750)
        self.assertIs(v, V)
        dex = wp.Dex(wp.override_dex(v, dexes))
        self.assertEqual(sorted(d[0] for d in dex.class_defs()), sorted([V["class"], V["loop"]["class"]]))


# a map loop of the same shape as Waze's (a wait of 17 ms within 5 s of a touch, else 33), written here
LOOP = "Lcom/example/Loop;"
CLOCK = ("method", ("Landroid/os/SystemClock;", "uptimeMillis", "()J"))
LAST = ("field", (LOOP, "c", "J"))


def loop_dex(waits=(17, 33)):
    run = [0x0071, CLOCK, 0x0000,          # invoke-static {} uptimeMillis
           0x010b,                         # move-result-wide v1
           0x0516, 5000,                   # const-wide/16 v5, 5000
           0x0031, 0x0501,                 # cmp-long v0, v1, v5
           0x003b, 5,                      # if-gez v0, +5
           0x0316, waits[0],               # const-wide/16 v3, fast
           0x0328,                         # goto +3
           0x0316, waits[1],               # const-wide/16 v3, still
           0x735a, LAST,                   # iput-wide v3, v7, c
           0x000e]
    cls = {"class": LOOP, "access": 0x10, "super": wp.OBJECT, "interfaces": ["Ljava/lang/Runnable;"],
           "fields": [("c", "J", 0x2)],
           "methods": [("<init>", "()V", 0x10000, (1, 1, 1, [0x1070, ("method", (wp.OBJECT, "<init>", "()V")), 0x0000, 0x000e])),
                       ("run", "()V", 0x11, (8, 1, 0, run))]}
    return wp.Dex(wp.write_dex([cls]))


def loop_entry(dex):
    return {"class": LOOP, "sha256": hashlib.sha256(wp.class_text(dex, LOOP).encode()).hexdigest(),
            "method": ("run", "()V"), "still": 33, "fast": 17}


class LoopClass(unittest.TestCase):
    def test_copy(self):
        dex = loop_dex()
        lv = loop_entry(dex)
        src, still_at = wp.check_loop([("classes.dex", dex)], lv)
        self.assertIs(src, dex)
        out = wp.Dex(wp.write_dex([wp.limiter_class(V), wp.loop_class(dex, lv, still_at)]))
        self.assertEqual(sorted(d[0] for d in out.class_defs()), sorted([V["class"], LOOP]))
        d = out.class_def(LOOP)
        self.assertEqual((d["access"], d["super"], d["interfaces"]), (0x10, wp.OBJECT, ["Ljava/lang/Runnable;"]))
        self.assertEqual(sorted(out.class_fields(d["data"])), sorted([("c", "J", 0x2), wp.LOOP_FIELD]))
        code = {(n, p): wp.disassemble(out, out.code(c)[3]) for n, p, _a, c in out.class_methods(d["data"])}
        F = "%s->%s:%s" % (LOOP, wp.LOOP_FIELD[0], wp.LOOP_FIELD[1])
        # only the still wait changed, to the field of the same width (the branches stay as they were)
        self.assertEqual(code[("run", "()V")], [
            "invoke-static {} Landroid/os/SystemClock;->uptimeMillis()J", "move-result-wide v1",
            "const-wide/16 v5 5000", "cmp-long v0 v1 v5", "if-gez v0 +5", "const-wide/16 v3 17", "goto +3",
            "sget-wide v3 " + F, "iput-wide v3 v7 %s->c:J" % LOOP, "return-void"])
        self.assertEqual(code[("<clinit>", "()V")], [
            "sget v0 %s->POLICY:I" % wp.ATL_LIMITER, "sget v1 %s->POLICY_DISPLAY:I" % wp.ATL_LIMITER,
            "const-wide/16 v2 33", "if-ne v0 v1 +4", "const-wide/16 v2 17", "sput-wide v2 " + F, "return-void"])
        self.assertEqual(code[("<init>", "()V")], ["invoke-direct {v0} Ljava/lang/Object;-><init>()V", "return-void"])
        data = wp.write_dex([wp.loop_class(dex, lv, still_at)])
        self.assertEqual(data[12:32], hashlib.sha1(data[32:]).digest())
        strings = [out.string(i) for i in range(out.string_ids_size)]
        self.assertEqual(strings, sorted(strings))

    def test_refuses(self):
        dex = loop_dex()
        lv = loop_entry(dex)
        # another class text
        with self.assertRaises(wp.Mismatch):
            wp.check_loop([("classes.dex", dex)], dict(lv, sha256="0" * 64))
        # defined twice
        with self.assertRaises(wp.Mismatch):
            wp.check_loop([("classes.dex", dex), ("classes2.dex", dex)], lv)
        # the still wait twice (and the fast one missing): the text matches its own hash, the waits do not
        other = loop_dex((33, 33))
        with self.assertRaises(wp.Mismatch) as e:
            wp.check_loop([("classes.dex", other)], loop_entry(other))
        self.assertIn("once each", str(e.exception))
        # another dex refers to a field the class does not have
        ref = {"class": "Lcom/example/User;", "access": 0x1, "super": wp.OBJECT, "interfaces": [], "fields": [],
               "methods": [("u", "()V", 0x9, (2, 0, 0, [0x0061, ("field", (LOOP, "x", "J")), 0x000e]))]}
        with self.assertRaises(wp.Mismatch):
            wp.check_loop([("classes.dex", dex), ("classes2.dex", wp.Dex(wp.write_dex([ref])))], lv)


if __name__ == "__main__":
    unittest.main()
