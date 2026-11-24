#!/usr/bin/env python3
"""Tests of tools/apps/waze-speedometer.py that need no Waze APK: the generated class, Waze's own
speedometer setting kept and put back, the settings read from the app's mirrored preferences.

    python3 tools/apps/test/test_waze_speedometer.py

(The checks against an installed Waze run on the device: `waze-speedometer.py check APP_DIR`.)
"""
import importlib.util
import json
import os
import sqlite3
import struct
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("waze_speedometer", os.path.join(HERE, "..", "waze-speedometer.py"))
ws = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ws)

V = ws.VERIFIED[1030750]


class ReadingClass(unittest.TestCase):
    def test_dex(self):
        data = ws.reading_class_dex(V)
        dex = ws.Dex(data)
        # the header's sizes and checksums
        self.assertEqual(data[:8], b"dex\n035\0")
        self.assertEqual(struct.unpack_from("<I", data, 0x20)[0], len(data))
        import hashlib
        import zlib
        self.assertEqual(data[12:32], hashlib.sha1(data[32:]).digest())
        self.assertEqual(struct.unpack_from("<I", data, 8)[0], zlib.adler32(data[12:]) & 0xFFFFFFFF)
        # the sorted tables dex readers require
        strings = [dex.string(i) for i in range(dex.string_ids_size)]
        self.assertEqual(strings, sorted(strings))
        methods = [dex.method(i) for i in range(dex.method_ids_size)]
        types = [dex.type_name(i) for i in range(dex.type_ids_size)]
        self.assertEqual(methods, sorted(methods, key=lambda m: (types.index(m[0]), strings.index(m[1]))))
        # the class: Waze's name, ATL's class as its superclass, the constructor and getters
        cd = dex.class_def(V["class"])
        self.assertEqual((cd["access"], cd["super"]), (0x11, ws.ATL_BASE))
        cls = dex.class_data(cd["data"])
        self.assertEqual([(n, p, a) for n, p, a, _c in cls["direct"]], [("<init>", ws.INIT_PROTO, 0x10001)])
        self.assertEqual(sorted((n, p) for n, p, _a, _c in cls["virtual"]),
                         [("a", "()D"), ("b", "()I"), ("c", "()I"), ("d", "()Ljava/lang/String;")])
        code = {n: c for n, _p, _a, c in cls["direct"] + cls["virtual"]}
        self.assertEqual(ws.disassemble(dex, dex.code(code["<init>"])[3]),
                         ["invoke-direct/range {v0..v5} %s-><init>%s" % (ws.ATL_BASE, ws.INIT_PROTO), "return-void"])
        # each getter returns the value Waze's own returns: a the speed in km/h, b the speed limit,
        # c the speed, d the unit
        for name, base, kind in (("a", "getSpeedKph()D", "move-result-wide v0"), ("b", "getSpeedLimit()I", "move-result v0"),
                                 ("c", "getSpeed()I", "move-result v0"), ("d", "getUnits()Ljava/lang/String;", "move-result-object v0")):
            regs = dex.code(code[name])[0]
            text = ws.disassemble(dex, dex.code(code[name])[3])
            self.assertEqual(text[:2], ["invoke-virtual {v%d} %s->%s" % (regs - 1, ws.ATL_BASE, base), kind], name)

    def test_jar(self):
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "o", "x.jar")
            ws.write_jar(ws.reading_class_dex(V), out)
            with zipfile.ZipFile(out) as z:
                self.assertEqual(z.namelist(), ["classes.dex"])
                self.assertEqual(z.read("classes.dex"), ws.reading_class_dex(V))
                info = z.getinfo("classes.dex")
            # stored at a 4-byte boundary (ART maps it in place)
            with open(out, "rb") as f:
                data = f.read()
            name_len, extra_len = struct.unpack_from("<HH", data, info.header_offset + 26)
            start = info.header_offset + 30 + name_len + extra_len
            self.assertEqual(start % 4, 0)
            self.assertEqual(data[start:start + 4], b"dex\n")


# Waze's reading class and its construction site, written here (with the dex writer of
# waze-map-pacing.py) under the names a build may give them, for the checks of other versions
wspec = importlib.util.spec_from_file_location("waze_map_pacing", os.path.join(HERE, "..", "waze-map-pacing.py"))
wp = importlib.util.module_from_spec(wspec)
wspec.loader.exec_module(wp)
CALLER = V["caller"][0]


def waze_dexes(cls="Lcom/waze/qq/z;", fields=("p", "q", "r", "s"), getters=(("w", "speedKph"), ("x", "speedLimit"), ("y", "speed"), ("z", "units")),
               null_check=("Lo/k/a;", "b", "(Ljava/lang/Object;Ljava/lang/String;)V"), order=(0, 1, 2, 3), flow="speedometerStateFlow"):
    """[("classes.dex", Dex)] with the reading class CLS (its fields named FIELDS, in the order speed,
    units, speedLimit, speedKph; its constructor storing them in ORDER) and the construction site"""
    types = ("I", ws.STRING, "I", "D")
    f = [("field", (cls, n, t)) for n, t in zip(fields, types)]
    store = {0: [0x1259, f[0]], 1: [0x135b, f[1]], 2: [0x1459, f[2]], 3: [0x155a, f[3]]}  # iput v2..v5 into v1
    ctor = [0x001a, ("string", "units"), 0x2071, ("method", null_check), 0x0003,  # const-string v0; invoke-static {v3,v0}
            0x1070, ("method", (ws.OBJECT, "<init>", "()V")), 0x0001] + sum((store[i] for i in order), []) + [0x000e]
    value = {"speed": 0, "units": 1, "speedLimit": 2, "speedKph": 3}
    methods = [("<init>", ws.INIT_PROTO, 0x10001, (7, 6, 2, ctor))]
    for name, what in getters:
        i = value[what]
        op, ret, regs = {"I": (0x52, 0x0f, 2), "D": (0x53, 0x10, 3), ws.STRING: (0x54, 0x11, 2)}[types[i]]
        methods.append((name, "()" + types[i], 0x11, (regs, 1, 0, [op | ((regs - 1) << 12), f[i], ret])))
    to_string = []  # const-string v0 LABEL; iget(-wide, -object) v1 v3 FIELD, for each field in order
    for label, i in zip(V["toString"], range(4)):
        to_string += [0x001a, ("string", label), {"I": 0x3152, "D": 0x3153, ws.STRING: 0x3154}[types[i]], f[i]]
    methods += [("equals", "(Ljava/lang/Object;)Z", 0x11, (2, 2, 0, [0x0012, 0x000f])),
                ("hashCode", "()I", 0x11, (1, 1, 0, [0x0012, 0x000f])),
                ("toString", "()Ljava/lang/String;", 0x11, (4, 1, 0, to_string + [0x0011]))]
    reading = {"class": cls, "access": 0x11, "super": ws.OBJECT, "interfaces": [],
               "fields": [(n, t, 0x12) for n, t in zip(fields, types)], "methods": methods}
    flow_field = ("field", (CALLER, flow, "Lo/k/b;"))
    site = [0x0022, ("type", cls), 0x7101, 0x8207, 0x9301, 0xa404,  # new-instance v0; move v1..v5 from v7..v11
            0x0676, ("method", (cls, "<init>", ws.INIT_PROTO)), 0x0000,  # invoke-direct/range {v0..v5}
            0x6654, flow_field, 0x000e]  # iget-object v6 v6 the flow
    caller = {"class": CALLER, "access": 0x1, "super": ws.OBJECT, "interfaces": [], "fields": [(flow, "Lo/k/b;", 0x12)],
              "methods": [(V["caller"][1], ws.INIT_PROTO, 0x0, (12, 6, 6, site))]}
    return [("classes.dex", ws.Dex(wp.write_dex([reading, caller])))]


class OtherVersions(unittest.TestCase):
    def test_verified_names(self):
        # a build that kept the verified version's names: its entry fits as it is
        dexes = waze_dexes(V["class"], [n for n, _t in V["fields"]], V["getters"], V["null_check"])
        ws.check_dexes("base.apk", dexes, V)
        self.assertEqual(ws.derive(dexes, V), V)

    def test_own_names(self):
        dexes = waze_dexes()
        with self.assertRaises(ws.Mismatch):
            ws.check_dexes("base.apk", dexes, V)  # the verified names are not there
        v = ws.derive(dexes, V)
        ws.check_dexes("base.apk", dexes, v)
        self.assertEqual(v["class"], "Lcom/waze/qq/z;")
        self.assertEqual(v["fields"], (("p", "I"), ("q", ws.STRING), ("r", "I"), ("s", "D")))
        self.assertEqual(v["getters"], (("w", "speedKph"), ("x", "speedLimit"), ("y", "speed"), ("z", "units")))
        self.assertEqual(v["null_check"], ("Lo/k/a;", "b", "(Ljava/lang/Object;Ljava/lang/String;)V"))
        # the class made for it: the app's names, ATL's getters behind each
        dex = ws.Dex(ws.reading_class_dex(v))
        cls = dex.class_data(dex.class_def(v["class"])["data"])
        code = {n: c for n, _p, _a, c in cls["virtual"]}
        self.assertIn("getSpeedLimit", ws.disassemble(dex, dex.code(code["x"])[3])[0])
        self.assertIn("getUnits", ws.disassemble(dex, dex.code(code["z"])[3])[0])
        self.assertEqual(ws.reading_class_dex(v), ws.reading_class_dex(ws.derive(dexes, V)))  # the same bytes

    def test_other_shapes_refused(self):
        # the constructor stores the fields in another order: the getters' meaning is unknown
        with self.assertRaises(ws.Mismatch):
            dexes = waze_dexes(order=(0, 2, 1, 3))
            ws.check_dexes("base.apk", dexes, ws.derive(dexes, V))
        # a getter of the speed that returns the limit field instead... is the limit's getter: two of them
        with self.assertRaises(ws.Mismatch):
            dexes = waze_dexes(getters=(("w", "speedKph"), ("x", "speedLimit"), ("y", "speedLimit"), ("z", "units")))
            ws.check_dexes("base.apk", dexes, ws.derive(dexes, V))
        # no construction site
        with self.assertRaises(ws.Mismatch):
            ws.derive([("classes.dex", ws.Dex(wp.write_dex([{"class": "Lcom/example/A;", "access": 0x1, "super": ws.OBJECT,
                                                               "interfaces": [], "fields": [], "methods": []}])))], V)

    @unittest.skipUnless(os.environ.get("WAZE_APP_DIR"), "WAZE_APP_DIR names no installed Waze")
    def test_installed_waze(self):
        # any Waze: a listed version is verified; another is "unverified" when its code fits (exit 2 otherwise)
        try:
            vc, _vn, v, state = ws.check_any(os.environ["WAZE_APP_DIR"])
        except ws.Mismatch as e:
            self.skipTest("not found in this Waze: %s" % e)
        self.assertEqual(state, "verified" if vc in ws.VERIFIED else "unverified")
        ws.Dex(ws.reading_class_dex(v))


class WazeSetting(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.data = self.tmp.name
        os.makedirs(os.path.join(self.data, "base.apk_"))
        self.db = os.path.join(self.data, "base.apk_", "waze_config.db")
        c = sqlite3.connect(self.db)
        c.execute("CREATE TABLE config_values (domain TEXT NOT NULL, category TEXT NOT NULL, name TEXT NOT NULL, "
                  "value TEXT, PRIMARY KEY (domain, category, name))")
        c.execute("INSERT INTO config_values VALUES ('user', 'Map', 'Speedometer speed limit user enabled', 'always')")
        c.commit()
        c.close()

    def tearDown(self):
        self.tmp.cleanup()

    def row(self):
        c = sqlite3.connect(self.db)
        try:
            r = c.execute("SELECT value FROM config_values WHERE domain = 'user' AND category = 'Map' "
                          "AND name = 'Show speedometer V4'").fetchone()
            return r[0] if r else None
        finally:
            c.close()

    def test_unset_hide_restore(self):
        self.assertEqual(ws.hide(self.data), 0)
        self.assertEqual(self.row(), "0")
        self.assertEqual(ws.settings(self.data)["hidden"], True)
        self.assertEqual(ws.hide(self.data), 0)  # again: still remembers "unset", not its own 0
        self.assertEqual(ws.restore(self.data), 0)
        self.assertIsNone(self.row())
        self.assertFalse(os.path.exists(os.path.join(self.data, ws.SAVED_NAME)))
        self.assertEqual(ws.restore(self.data), 0)  # nothing to put back

    def test_user_choice_kept(self):
        c = sqlite3.connect(self.db)
        c.execute("INSERT INTO config_values VALUES ('user', 'Map', 'Show speedometer V4', '1')")
        c.commit()
        c.close()
        ws.hide(self.data)
        self.assertEqual(self.row(), "0")
        with open(os.path.join(self.data, ws.SAVED_NAME)) as f:
            self.assertEqual(json.load(f)["value"], "1")
        ws.restore(self.data)
        self.assertEqual(self.row(), "1")
        # the other rows stay as they were
        c = sqlite3.connect(self.db)
        self.assertEqual(c.execute("SELECT count(*) FROM config_values").fetchone()[0], 2)
        c.close()

    def test_no_config_yet(self):
        os.unlink(self.db)
        self.assertEqual(ws.hide(self.data), 1)
        self.assertFalse(os.path.exists(os.path.join(self.data, ws.SAVED_NAME)))

    def test_settings(self):
        prefs = os.path.join(self.data, "base.apk_", "shared_prefs")
        os.makedirs(prefs)
        with open(os.path.join(prefs, "com.waze.config.offline.xml"), "w") as f:
            f.write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                    '    <string name="CONFIG_VALUE_MAP_SPEEDOMETER_STYLE">world</string>\n'
                    '    <long name="CONFIG_VALUE_MAP_SPEEDOMETER_SPEED_ALERT_OFFSET" value="-3" />\n</map>\n')
        self.assertEqual(ws.settings(self.data), {"hidden": False, "signStyle": "world", "alertOffset": -3})
        self.assertEqual(ws.settings(os.path.join(self.data, "missing")), {"hidden": False})


if __name__ == "__main__":
    unittest.main(argv=[sys.argv[0]] + sys.argv[1:])
