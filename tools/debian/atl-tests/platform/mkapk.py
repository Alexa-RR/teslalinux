#!/usr/bin/env python3
"""mkapk.py OUT.apk CLASSES.dex: the minimal unsigned APK of the platform tests (PlatformTest).

Writes a binary AndroidManifest.xml (Android's compiled XML format, as aapt writes it) for package
org.teslalinux.platformtest, targetSdkVersion 31, with the <uses-permission> entries the
permission checks are about (one limited by maxSdkVersion, one <uses-permission-sdk-23>), a
signature-level <permission> of its own and an <application>, and stores it with CLASSES.dex.
"""
import struct
import sys
import zipfile

ANDROID_NS = "http://schemas.android.com/apk/res/android"
# attribute resource ids (frameworks/base/core/res/res/values/public.xml)
ATTR_IDS = {
    "label": 0x01010001,
    "name": 0x01010003,
    "protectionLevel": 0x01010009,
    "minSdkVersion": 0x0101020C,
    "targetSdkVersion": 0x01010270,
    "maxSdkVersion": 0x01010271,
}
TYPE_STRING, TYPE_INT_DEC, TYPE_INT_HEX = 0x03, 0x10, 0x11

PACKAGE = "org.teslalinux.platformtest"
P = "android.permission."
USES = [
    (P + "INTERNET", None),                   # normal
    (P + "CAMERA", None),                     # dangerous
    (P + "READ_CONTACTS", None),              # dangerous (splits READ_CALL_LOG only below 16)
    (P + "ACCESS_FINE_LOCATION", None),       # dangerous, implies ACCESS_COARSE_LOCATION (split)
    (P + "RECORD_AUDIO", None),               # dangerous, the host gives no microphone in the test
    (P + "FOREGROUND_SERVICE_LOCATION", None),  # newer than framework-res: normal
    (P + "BLUETOOTH", 30),                    # maxSdkVersion 30: not requested on SDK 32
    (P + "WRITE_SECURE_SETTINGS", None),      # signature|privileged|development: not for this app
    (P + "SYSTEM_ALERT_WINDOW", None),        # signature|appop
    ("org.example.UNDEFINED_PERMISSION", None),  # defined by nobody
    (PACKAGE + ".OWN_SIGNATURE", None),       # its own signature permission
    ("com.google.android.c2dm.permission.RECEIVE", None),  # Play services' (stand-in)
]
USES_SDK23 = [P + "READ_CALENDAR"]


class StringPool:
    def __init__(self, first):
        self.strings = list(first)

    def idx(self, s):
        if s not in self.strings:
            self.strings.append(s)
        return self.strings.index(s)

    def chunk(self):
        offsets, data = [], b""
        for s in self.strings:
            b = s.encode("utf-8")
            assert len(s) < 0x80 and len(b) < 0x80
            offsets.append(len(data))
            data += bytes([len(s), len(b)]) + b + b"\0"
        while len(data) % 4:
            data += b"\0"
        header = 28
        start = header + 4 * len(offsets)
        body = b"".join(struct.pack("<I", o) for o in offsets) + data
        return struct.pack("<HHIIIIII", 0x0001, header, header + len(body), len(self.strings), 0, 0x100, start, 0) + body


def manifest():
    # the attribute names with resource ids come first in the pool, in the order of the map
    names = list(ATTR_IDS)
    pool = StringPool(names)
    nodes = []

    def start(name, attrs):
        out = b""
        attrs = sorted(attrs, key=lambda a: ATTR_IDS.get(a[0], 0xFFFFFFFF) if a[1] else 0)
        for aname, in_ns, kind, value in attrs:
            ns_idx = pool.idx(ANDROID_NS) if in_ns else 0xFFFFFFFF
            name_idx = pool.idx(aname)
            if kind == TYPE_STRING:
                raw = data = pool.idx(value)
            else:
                raw, data = 0xFFFFFFFF, value
            out += struct.pack("<IIIHBBI", ns_idx, name_idx, raw, 8, 0, kind, data)
        ext = struct.pack("<IIHHHHHH", 0xFFFFFFFF, pool.idx(name), 20, 20, len(attrs), 0, 0, 0)
        body = struct.pack("<II", 1, 0xFFFFFFFF) + ext + out
        nodes.append(struct.pack("<HHI", 0x0102, 16, 8 + len(body)) + body)

    def end(name):
        body = struct.pack("<IIII", 1, 0xFFFFFFFF, 0xFFFFFFFF, pool.idx(name))
        nodes.append(struct.pack("<HHI", 0x0103, 16, 8 + len(body)) + body)

    prefix, uri = pool.idx("android"), pool.idx(ANDROID_NS)
    nodes.append(struct.pack("<HHIIIII", 0x0100, 16, 24, 1, 0xFFFFFFFF, prefix, uri))
    start("manifest", [("package", False, TYPE_STRING, PACKAGE)])
    start("uses-sdk", [("minSdkVersion", True, TYPE_INT_DEC, 24), ("targetSdkVersion", True, TYPE_INT_DEC, 31)])
    end("uses-sdk")
    start("permission", [("name", True, TYPE_STRING, PACKAGE + ".OWN_SIGNATURE"), ("protectionLevel", True, TYPE_INT_HEX, 2)])
    end("permission")
    for perm, max_sdk in USES:
        attrs = [("name", True, TYPE_STRING, perm)]
        if max_sdk is not None:
            attrs.append(("maxSdkVersion", True, TYPE_INT_DEC, max_sdk))
        start("uses-permission", attrs)
        end("uses-permission")
    for perm in USES_SDK23:
        start("uses-permission-sdk-23", [("name", True, TYPE_STRING, perm)])
        end("uses-permission-sdk-23")
    start("application", [("label", True, TYPE_STRING, "ATL platform test")])
    end("application")
    end("manifest")
    nodes.append(struct.pack("<HHIIIII", 0x0101, 16, 24, 1, 0xFFFFFFFF, prefix, uri))

    resmap = b"".join(struct.pack("<I", ATTR_IDS[n]) for n in names)
    resmap = struct.pack("<HHI", 0x0180, 8, 8 + len(resmap)) + resmap
    body = pool.chunk() + resmap + b"".join(nodes)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


def main():
    out, dex = sys.argv[1:3]
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("AndroidManifest.xml", manifest())
        z.write(dex, "classes.dex")


if __name__ == "__main__":
    main()
