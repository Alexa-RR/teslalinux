#!/usr/bin/env python3
"""Tests of tools/appstyle/mapskin.py: the manifest the launcher hands ATL for this start.

    python3 -m unittest discover -s tools/appstyle/test
"""
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "..", "mapskin.py")
spec = importlib.util.spec_from_file_location("mapskin", TOOL)
ms = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ms)

SKIN = b"local P = { map_background = rgb(0xFAFCFA), roads = rgb(0x8D949B), }\n"
SHA = hashlib.sha256(SKIN).hexdigest()


def manifest():
    return {"replacements": {"day": [["rgb(0xFAFCFA)", "rgb(0xD9DADD)"], ["rgb(0x8D949B)", "rgb(0xFBFBFB)"]]},
            "overlays": [{"name": "day", "package": "com.waze", "path": "skins/day.lua", "replace": "day",
                          "verified": [{"versionCode": 100, "versionName": "1.0", "sha256": SHA}]},
                         {"name": "other app", "package": "com.other", "path": "x.lua", "replace": "day", "verified": []}]}


class Effective(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.data = self.tmp.name
        os.makedirs(os.path.join(self.data, "skins"))

    def tearDown(self):
        self.tmp.cleanup()

    def skin(self, data):
        with open(os.path.join(self.data, "skins", "day.lua"), "wb") as f:
            f.write(data)

    def pairs(self, out, vc):
        return [(v["sha256"], v.get("unverified", False)) for v in out["overlays"][0]["verified"] if v["versionCode"] == vc]

    def test_verified_version_unchanged(self):
        self.skin(SKIN)
        out, report = ms.effective(manifest(), self.data, "com.waze", 100)
        self.assertEqual(out, manifest())
        self.assertEqual(report, [("day", "verified")])

    def test_other_version_same_file(self):
        # a new version that kept the palette file byte for byte: the verified original, marked unverified
        self.skin(SKIN)
        out, report = ms.effective(manifest(), self.data, "com.waze", 200, "2.0")
        self.assertEqual(self.pairs(out, 200), [(SHA, True)])
        self.assertEqual(report, [("day", "applied, may not work")])
        self.assertEqual(out["overlays"][1], manifest()["overlays"][1])  # another app's entry stays

    def test_other_version_not_unpacked(self):
        # before the app's first start: the verified originals, so a file unchanged applies at once
        out, report = ms.effective(manifest(), self.data, "com.waze", 200)
        self.assertEqual(self.pairs(out, 200), [(SHA, True)])
        self.assertEqual(report, [("day", "not unpacked yet")])

    def test_other_version_other_file(self):
        # its own file, every replacement's text once in it: that file too
        other = SKIN.replace(b"local P", b"local Palette")
        self.skin(other)
        out, report = ms.effective(manifest(), self.data, "com.waze", 200)
        self.assertEqual(self.pairs(out, 200), [(SHA, True), (hashlib.sha256(other).hexdigest(), True)])
        self.assertEqual(report, [("day", "applied, may not work")])
        # the transformed file is what ATL serves for that pair
        self.assertEqual(ms.transform(other, manifest()["replacements"]["day"])[0],
                         b"local Palette = { map_background = rgb(0xD9DADD), roads = rgb(0xFBFBFB), }\n")

    def test_other_version_replacement_missing(self):
        # a changed palette line: not that file (Waze's own colours), only the verified originals
        self.skin(SKIN.replace(b"0x8D949B", b"0x8D949C"))
        out, report = ms.effective(manifest(), self.data, "com.waze", 200)
        self.assertEqual(self.pairs(out, 200), [(SHA, True)])
        self.assertIn("Waze's own colours", report[0][1])
        # a text that occurs twice: not applied either (ATL's rule: exactly once)
        self.skin(SKIN + b"-- rgb(0xFAFCFA)\n")
        out, _report = ms.effective(manifest(), self.data, "com.waze", 200)
        self.assertEqual(self.pairs(out, 200), [(SHA, True)])

    def test_command(self):
        self.skin(SKIN)
        with open(os.path.join(self.data, "m.json"), "w") as f:
            json.dump(manifest(), f)
        outs = []
        for name in ("a.json", "b.json"):
            r = subprocess.run([sys.executable, "-I", TOOL, "effective", "--manifest", os.path.join(self.data, "m.json"),
                                "--data-dir", self.data, "--version-code", "200", "--out", os.path.join(self.data, name)],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            with open(os.path.join(self.data, name), "rb") as f:
                outs.append(f.read())
        self.assertEqual(outs[0], outs[1])  # the same manifest for the same files
        self.assertEqual(self.pairs(json.loads(outs[0]), 200), [(SHA, True)])


if __name__ == "__main__":
    unittest.main()
