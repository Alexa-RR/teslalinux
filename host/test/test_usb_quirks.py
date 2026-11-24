#!/usr/bin/env python3
"""host/teslalinux-usb-quirks with a fake usb-modeswitch-data and a fake usb-storage quirks parameter.

    python3 host/test/test_usb_quirks.py

* the ids: Huawei sources that switch to a HiLink id (14db, 14dc), from configPack.tar.gz and single files;
  12d1:1f01 always; other Huawei sticks (stick mode) and other vendors left out
* cmdline: merged into an existing usb-storage.quirks= (foreign entries kept first, ours replaced, the
  127-character limit), idempotent, every other word kept in order
* check, and apply to the running kernel's parameter
"""
import os
import shutil
import subprocess
import tarfile
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "..", "teslalinux-usb-quirks")
CONFIGS = {
    "12d1:1f01": 'TargetVendor=0x12d1\nTargetProductList="14db,14dc"\nHuaweiNewMode=1\n',
    "12d1:1f03": "TargetVendor=0x12d1\nTargetProduct=0x14db\nHuaweiNewMode=1\n",
    "12d1:157d": 'TargetVendor=0x12d1\nTargetProductList="14db,14dc"\nHuaweiNewMode=1\n',
    "12d1:1446": 'TargetVendor=0x12d1\nTargetProductList="1001,1406,1506"\n# not 14dc\n',
    "19d2:0166": "TargetVendor=0x19d2\nTargetProduct=0x14dc\n",
}
LINE = "console=tty1 root=PARTUUID=d05f71f3-02 rootwait ds=nocloud;i=rpi-imager-1 quiet loglevel=3"


class UsbQuirks(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="tl-quirks-")
        self.data = os.path.join(self.tmp, "usb_modeswitch")
        self.local = os.path.join(self.tmp, "usb_modeswitch.d")
        os.makedirs(self.data)
        os.makedirs(self.local)
        pack = os.path.join(self.tmp, "pack")
        os.makedirs(pack)
        with tarfile.open(os.path.join(self.data, "configPack.tar.gz"), "w:gz") as t:
            for name, text in CONFIGS.items():
                with open(os.path.join(pack, name), "w") as f:
                    f.write(text)
                t.add(os.path.join(pack, name), arcname=name)
        with open(os.path.join(self.local, "12d1:1f02"), "w") as f:
            f.write("TargetVendor=0x12d1\nTargetProduct=0x14dc\n")
        self.sysq = os.path.join(self.tmp, "quirks")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_script(self, *args, ok=True):
        env = dict(os.environ, TESLALINUX_USB_STORAGE_QUIRKS=self.sysq)
        r = subprocess.run(["sh", SCRIPT] + list(args) + [self.data, self.local], capture_output=True, text=True, env=env)
        if ok:
            self.assertEqual(r.returncode, 0, r.stderr)
        return r

    def test_ids(self):
        ids = self.run_script("ids").stdout.split()
        self.assertEqual(ids[0], "12d1:1f01")
        self.assertEqual(sorted(ids), ["12d1:157d", "12d1:1f01", "12d1:1f02", "12d1:1f03"])

    def test_ids_without_data(self):
        r = subprocess.run(["sh", SCRIPT, "ids", os.path.join(self.tmp, "none")], capture_output=True, text=True)
        self.assertEqual(r.stdout.split(), ["12d1:1f01"])

    def test_cmdline_added_and_idempotent(self):
        out = self.run_script("cmdline", LINE).stdout.strip()
        self.assertTrue(out.startswith(LINE + " usb-storage.quirks="), out)
        quirks = out.split("usb-storage.quirks=")[1].split(",")
        self.assertEqual(sorted(quirks), ["12d1:157d:i", "12d1:1f01:i", "12d1:1f02:i", "12d1:1f03:i"])
        self.assertEqual(self.run_script("cmdline", out).stdout.strip(), out)
        self.assertEqual(self.run_script("check", out).returncode, 0)

    def test_cmdline_keeps_foreign_quirks(self):
        line = "root=/dev/sda2 usb_storage.quirks=152d:0578:u,12D1:1F01:u rootwait"
        out = self.run_script("cmdline", line).stdout.strip()
        self.assertTrue(out.startswith("root=/dev/sda2 rootwait usb-storage.quirks=152d:0578:u,"), out)
        self.assertNotIn("12D1:1F01:u", out)
        self.assertEqual(out.count("usb-storage.quirks="), 1)
        self.assertEqual(out.count("quirks="), 1)

    def test_limit(self):
        foreign = ",".join("abcd:%04x:u" % i for i in range(9))  # 107 characters
        r = self.run_script("cmdline", "root=x usb-storage.quirks=" + foreign)
        value = r.stdout.strip().split("usb-storage.quirks=")[1]
        self.assertLessEqual(len(value), 127)
        self.assertTrue(value.startswith(foreign + ",12d1:1f01:i"))
        self.assertIn("is full", r.stderr)

    def test_check_missing(self):
        r = self.run_script("check", LINE + " usb-storage.quirks=12d1:1f01:i", ok=False)
        self.assertEqual(r.returncode, 1)
        self.assertEqual(sorted(r.stdout.split()), ["12d1:157d", "12d1:1f02", "12d1:1f03"])

    def test_apply(self):
        with open(self.sysq, "w") as f:
            f.write("152d:0578:u")
        self.run_script("apply")
        with open(self.sysq) as f:
            value = f.read()
        self.assertTrue(value.startswith("152d:0578:u,12d1:1f01:i,"), value)
        self.run_script("apply")
        with open(self.sysq) as f:
            self.assertEqual(f.read(), value)


if __name__ == "__main__":
    unittest.main(verbosity=1)
