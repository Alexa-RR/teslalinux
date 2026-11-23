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

    @unittest.skipUnless(os.environ.get("WAZE_APP_DIR"), "WAZE_APP_DIR names no installed Waze")
    def test_installed_waze(self):
        vc, _vn, v = wp.check_auto(os.environ["WAZE_APP_DIR"])
        self.assertEqual(vc, 1030750)
        self.assertIs(v, V)


if __name__ == "__main__":
    unittest.main()
