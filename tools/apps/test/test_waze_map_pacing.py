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
        if wp.app_info(os.environ["WAZE_APP_DIR"])[2] != 1030750:
            self.skipTest("needs Waze 5.27.5 (other versions: OtherVersions.test_installed_waze_any)")
        vc, _vn, v, dexes = wp.check_auto(os.environ["WAZE_APP_DIR"])
        self.assertEqual(vc, 1030750)
        self.assertIs(v, V)
        dex = wp.Dex(wp.override_dex(v, dexes))
        self.assertEqual(sorted(d[0] for d in dex.class_defs()), sorted([V["class"], V["loop"]["class"]]))


# a map loop of the same shape as Waze's (a wait of 17 ms within 5 s of a touch, else 33), written here
LOOP = "Lcom/example/Loop;"
CLOCK = ("method", ("Landroid/os/SystemClock;", "uptimeMillis", "()J"))


def loop_dex(waits=(17, 33), cls=LOOP, last="c"):
    LAST = ("field", (cls, last, "J"))
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
    c = {"class": cls, "access": 0x10, "super": wp.OBJECT, "interfaces": ["Ljava/lang/Runnable;"],
         "fields": [(last, "J", 0x2)],
         "methods": [("<init>", "()V", 0x10000, (1, 1, 1, [0x1070, ("method", (wp.OBJECT, "<init>", "()V")), 0x0000, 0x000e])),
                     ("run", "()V", 0x11, (8, 1, 0, run))]}
    return wp.Dex(wp.write_dex([c]))


def loop_entry(dex):
    return {"class": LOOP, "sha256": hashlib.sha256(wp.class_text(dex, LOOP).encode()).hexdigest(),
            "method": ("run", "()V"), "still": 33, "fast": 17,
            "shape": {"sha256": hashlib.sha256(wp.shape_text(dex, LOOP).encode()).hexdigest(), "access": 0x10,
                      "super": wp.OBJECT, "interfaces": ("Ljava/lang/Runnable;",), "fields": 1, "methods": 2}}


# a frame limiter with the verified one's fields and method protos, written here: under the verified
# version's names (the template) and under others, as a new build might rename it
ATOMIC = "Ljava/util/concurrent/atomic/AtomicLong;"
VERIFIED_NAMES = {"a:J": "a", "b": "b", "c": "c", "d": "d", "e": "e", "a(I)J": "a"}
OTHER_NAMES = {"a:J": "x", "b": "y", "c": "p", "d": "r", "e": "s", "a(I)J": "t"}


def limiter_classes(cls, sub, names, user=None, skip=0):
    fa = ("field", (cls, names["a:J"], "J"))
    rate = ("method", (cls, names["a(I)J"], "(I)J"))
    lim = {"class": cls, "access": 0x1, "super": wp.OBJECT, "interfaces": [],
           "fields": [(names["a:J"], "J", 0x42), (names["b"], ATOMIC, 0x12)],
           "methods": [("<init>", "()V", 0x10001, (1, 1, 1, [0x1070, ("method", (wp.OBJECT, "<init>", "()V")), 0x0000, 0x000e])),
                       (names["a(I)J"], "(I)J", 0x1a, (3, 1, 0, [0x2081, 0x0010])),           # int-to-long v0 v2; return-wide v0
                       (names["c"], "(I)V", 0x14, (2, 2, 1, [0x1071, rate, 0x0001, 0x000e])),  # invoke-static {v1} a(I)J
                       (names["d"], "()Z", 0x11, (3, 1, 0, [0x2053, fa, 0x0012 | skip << 12, 0x000f])),  # iget-wide; const/4 v0 SKIP; return
                       (names["e"], "(J)V", 0x11, (3, 3, 0, [0x015a, fa, 0x000e]))]}         # iput-wide v1 v0 a
    subclass = {"class": sub, "access": 0x1, "super": cls, "interfaces": [], "fields": [],
                "methods": [("<init>", "()V", 0x10001, (1, 1, 1, [0x1070, ("method", (cls, "<init>", "()V")), 0x0000, 0x000e]))]}
    out = [lim, subclass]
    if user:  # a class elsewhere asking the subclass whether to skip a frame
        out.append({"class": user, "access": 0x1, "super": wp.OBJECT, "interfaces": [], "fields": [],
                    "methods": [("u", "(" + sub + ")V", 0x9, (1, 1, 1, [0x106e, ("method", (sub, names["d"], "()Z")), 0x0000, 0x000e]))]})
    return out


def limiter_template():
    dex = wp.Dex(wp.write_dex(limiter_classes("Lcom/example/lim/a;", "Lcom/example/lim/m;", VERIFIED_NAMES)))
    data = [d for d in dex.class_defs() if d[0] == "Lcom/example/lim/a;"][0][4]
    code = {(n, p): (dex.code(c)[0], dex.code(c)[1], wp.disassemble(dex, dex.code(c)[3])) for n, p, _a, c in dex.class_methods(data)}
    return dict(V, **{"class": "Lcom/example/lim/a;", "subclasses": ("Lcom/example/lim/m;",), "code": code})


class OtherVersions(unittest.TestCase):
    def test_limiter_verified_names(self):
        t = limiter_template()
        dexes = [("classes.dex", wp.Dex(wp.write_dex(limiter_classes(t["class"], t["subclasses"][0], VERIFIED_NAMES, "Lcom/example/U;"))))]
        self.assertIs(wp.find_limiter(dexes, t), t)

    def test_limiter_found_by_shape(self):
        t = limiter_template()
        B, SUB = "Lcom/example/lim/q;", "Lcom/example/lim/w;"
        dexes = [("classes.dex", wp.Dex(wp.write_dex(limiter_classes(B, SUB, OTHER_NAMES, "Lcom/example/U;"))))]
        v = wp.find_limiter(dexes, t)
        self.assertEqual((v["class"], v["subclasses"]), (B, (SUB,)))
        self.assertEqual(sorted(v["api"].items()), sorted({
            ("<init>", "()V", 0x10001): ("<init>", "(I)V"), ("p", "(I)V", 0x14): ("setAppFrameRate", "(I)V"),
            ("r", "()Z", 0x11): ("isTooSoon", "()Z"), ("s", "(J)V", 0x11): ("setFrameStarted", "(J)V")}.items()))
        self.assertEqual(v["fields"], (("x", "J", 0x42), ("y", ATOMIC, 0x12)))
        # the class made for it: the app's names, each calling ATL's method; the same bytes every time
        dex = wp.Dex(wp.limiter_class_dex(v))
        defs = dex.class_defs()
        self.assertEqual([(d[0], d[2]) for d in defs], [(B, wp.ATL_LIMITER)])
        code = {(n, p): wp.disassemble(dex, dex.code(c)[3]) for n, p, _a, c in dex.class_methods(defs[0][4])}
        self.assertEqual(code[("r", "()Z")][0], "invoke-virtual {v0} %s->isTooSoon()Z" % wp.ATL_LIMITER)
        self.assertEqual(wp.limiter_class_dex(v), wp.limiter_class_dex(wp.find_limiter(dexes, t)))

    def test_limiter_other_shapes_refused(self):
        t = limiter_template()
        B, SUB = "Lcom/example/lim/q;", "Lcom/example/lim/w;"
        # other code (d answers "skip" always)
        dexes = [("classes.dex", wp.Dex(wp.write_dex(limiter_classes(B, SUB, OTHER_NAMES, skip=1))))]
        with self.assertRaises(wp.Mismatch):
            wp.find_limiter(dexes, t)
        # two classes of the shape: which one is Waze's limiter is not known
        two = limiter_classes(B, SUB, OTHER_NAMES) + limiter_classes("Lcom/example/lim/q2;", "Lcom/example/lim/w2;", OTHER_NAMES)
        with self.assertRaises(wp.Mismatch) as e:
            wp.find_limiter([("classes.dex", wp.Dex(wp.write_dex(two)))], t)
        self.assertIn("2 classes", str(e.exception))
        # a second subclass: not the hierarchy read
        more = limiter_classes(B, SUB, OTHER_NAMES) + limiter_classes(B, "Lcom/example/lim/w3;", OTHER_NAMES)[1:]
        with self.assertRaises(wp.Mismatch):
            wp.find_limiter([("classes.dex", wp.Dex(wp.write_dex(more)))], t)

    def test_loop_found_by_shape(self):
        lv = loop_entry(loop_dex())
        other = loop_dex(cls="Lcom/example/qq/b;", last="z")
        found = wp.find_loop([("classes.dex", other)], lv)
        self.assertEqual((found["class"], found["sha256"]), ("Lcom/example/qq/b;", None))
        src, still_at = wp.check_loop([("classes.dex", other)], found)
        out = wp.Dex(wp.write_dex([wp.loop_class(src, found, still_at)]))
        d = out.class_def("Lcom/example/qq/b;")
        self.assertEqual(sorted(out.class_fields(d["data"])), sorted([("z", "J", 0x2), wp.LOOP_FIELD]))
        # under its own name it is the verified text: found as it is
        self.assertIs(wp.find_loop([("classes.dex", loop_dex())], lv), lv)

    def test_loop_other_shapes_refused(self):
        lv = loop_entry(loop_dex())
        with self.assertRaises(wp.Mismatch):
            wp.find_loop([("classes.dex", loop_dex((17, 40), cls="Lcom/example/qq/b;"))], lv)
        with self.assertRaises(wp.Mismatch):  # two of them
            wp.find_loop([("classes.dex", loop_dex(cls="Lcom/example/qq/b;")), ("classes2.dex", loop_dex(cls="Lcom/example/qq/c;"))], lv)

    def test_shape_text_names_out(self):
        a = wp.shape_text(loop_dex(), LOOP)
        self.assertEqual(a, wp.shape_text(loop_dex(cls="Lcom/example/qq/b;", last="z"), "Lcom/example/qq/b;"))
        self.assertIn("Landroid/os/SystemClock;->uptimeMillis()J", a)  # the platform's names stay
        self.assertIn("THIS->f0:J", a)

    @unittest.skipUnless(os.environ.get("WAZE_APP_DIR"), "WAZE_APP_DIR names no installed Waze")
    def test_installed_waze_any(self):
        # any Waze: a listed version is verified; another is "unverified" when its code is found
        try:
            vc, _vn, v, dexes, state = wp.check_any(os.environ["WAZE_APP_DIR"])
        except wp.Mismatch as e:
            self.skipTest("not found in this Waze: %s" % e)
        self.assertEqual(state, "verified" if vc in wp.VERIFIED_AUTO else "unverified")
        wp.Dex(wp.override_dex(v, dexes))

    @unittest.skipUnless(os.environ.get("WAZE_APP_DIR"), "WAZE_APP_DIR names no installed Waze")
    def test_installed_waze_by_shape(self):
        # Waze 5.27.5 as if a build had renamed its limiter and loop: found by their shape, the same jar
        _base, _info, vc = wp.app_info(os.environ["WAZE_APP_DIR"])
        if vc != 1030750:
            self.skipTest("needs Waze 5.27.5")
        dexes = wp.read_dexes(os.path.join(os.environ["WAZE_APP_DIR"], "base.apk"))
        fake = wp.renamed_limiter(V, "Lcom/waze/zz/q;", [("x", "J", 0x42), ("y", ATOMIC, 0x12)],
                                  [("<init>", "()V", 0x10001), ("p", "(I)V", 0x14), ("q", "()Z", 0x11),
                                   ("r", "(J)V", 0x11), ("s", "(I)J", 0x1a)])
        fake["subclasses"] = ("Lcom/waze/zz/r;",)
        v = wp.find_limiter(dexes, fake)
        lv = wp.find_loop(dexes, dict(V["loop"], **{"class": "Lcom/waze/zz/s;", "sha256": "0" * 64}))
        self.assertEqual((v["class"], lv["class"]), (V["class"], V["loop"]["class"]))
        self.assertEqual(wp.override_dex(dict(v, loop=lv), dexes), wp.override_dex(V, dexes))


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
