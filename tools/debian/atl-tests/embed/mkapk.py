#!/usr/bin/env python3
"""mkapk.py OUT.apk CLASSES.dex PACKAGE ACTIVITY: a minimal unsigned APK for ATL runtime tests.

Writes a binary AndroidManifest.xml (Android's compiled XML format, as aapt writes it) declaring
PACKAGE with one exported launcher ACTIVITY, and stores it with CLASSES.dex. There are no
resources: the tests build their views in code. ATL needs no signature for an app it is given.
"""
import struct
import sys
import zipfile

ANDROID_NS = "http://schemas.android.com/apk/res/android"
# attribute resource ids (frameworks/base/core/res/res/values/public.xml)
ATTR_IDS = {
    "label": 0x01010001,
    "name": 0x01010003,
    "exported": 0x01010010,
    "minSdkVersion": 0x0101020C,
    "targetSdkVersion": 0x01010270,
}
TYPE_STRING, TYPE_INT_DEC, TYPE_INT_BOOLEAN = 0x03, 0x10, 0x12


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


def manifest(package, activity):
    # the attribute names with resource ids come first in the pool, in the order of the map
    names = list(ATTR_IDS)
    pool = StringPool(names)
    nodes = []

    def start(name, attrs, ns=None):
        out = b""
        attrs = sorted(attrs, key=lambda a: ATTR_IDS.get(a[0], 0xFFFFFFFF) if a[1] else 0)
        for aname, in_ns, kind, value in attrs:
            ns_idx = pool.idx(ANDROID_NS) if in_ns else 0xFFFFFFFF
            name_idx = pool.idx(aname)
            if kind == TYPE_STRING:
                raw = data = pool.idx(value)
            elif kind == TYPE_INT_BOOLEAN:
                raw, data = 0xFFFFFFFF, 0xFFFFFFFF if value else 0
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
    start("manifest", [("package", False, TYPE_STRING, package)])
    start("uses-sdk", [("minSdkVersion", True, TYPE_INT_DEC, 24), ("targetSdkVersion", True, TYPE_INT_DEC, 31)])
    end("uses-sdk")
    start("application", [("label", True, TYPE_STRING, "ATL embed test")])
    start("activity", [("name", True, TYPE_STRING, activity), ("exported", True, TYPE_INT_BOOLEAN, True)])
    start("intent-filter", [])
    start("action", [("name", True, TYPE_STRING, "android.intent.action.MAIN")])
    end("action")
    start("category", [("name", True, TYPE_STRING, "android.intent.category.LAUNCHER")])
    end("category")
    end("intent-filter")
    end("activity")
    end("application")
    end("manifest")
    nodes.append(struct.pack("<HHIIIII", 0x0101, 16, 24, 1, 0xFFFFFFFF, prefix, uri))

    resmap = b"".join(struct.pack("<I", ATTR_IDS[n]) for n in names)
    resmap = struct.pack("<HHI", 0x0180, 8, 8 + len(resmap)) + resmap
    body = pool.chunk() + resmap + b"".join(nodes)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


def main():
    out, dex, package, activity = sys.argv[1:5]
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("AndroidManifest.xml", manifest(package, activity))
        z.write(dex, "classes.dex")


if __name__ == "__main__":
    main()
