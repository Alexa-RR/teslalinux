#!/usr/bin/env python3
"""mkapk.py OUT.apk CLASSES.dex [--disavow]: the minimal unsigned APK of BluetoothTest.

Writes a binary AndroidManifest.xml (Android's compiled XML format, as aapt writes it; the string
pool and node writer of ../platform/mkapk.py) for package org.teslalinux.bttest, targetSdkVersion
31, requesting what Waze requests for its beacon scan: BLUETOOTH and BLUETOOTH_ADMIN up to SDK 30,
BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION. With --disavow, BLUETOOTH_SCAN carries
usesPermissionFlags="neverForLocation" and no location permission is requested.
"""
import importlib.util
import os
import struct
import sys
import zipfile

_spec = importlib.util.spec_from_file_location(
    "platform_mkapk", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "platform", "mkapk.py"))
_base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_base)

ANDROID_NS = _base.ANDROID_NS
ATTR_IDS = dict(_base.ATTR_IDS)
ATTR_IDS["usesPermissionFlags"] = 0x01010644
TYPE_STRING, TYPE_INT_DEC, TYPE_INT_HEX = _base.TYPE_STRING, _base.TYPE_INT_DEC, _base.TYPE_INT_HEX
NEVER_FOR_LOCATION = 0x00010000

PACKAGE = "org.teslalinux.bttest"
P = "android.permission."


def uses(disavow):
    out = [
        (P + "BLUETOOTH", 30, 0),
        (P + "BLUETOOTH_ADMIN", 30, 0),
        (P + "BLUETOOTH_SCAN", None, NEVER_FOR_LOCATION if disavow else 0),
        (P + "BLUETOOTH_CONNECT", None, 0),
    ]
    if not disavow:
        out.append((P + "ACCESS_FINE_LOCATION", None, 0))
    return out


def manifest(disavow):
    names = list(ATTR_IDS)
    pool = _base.StringPool(names)
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
    for perm, max_sdk, flags in uses(disavow):
        attrs = [("name", True, TYPE_STRING, perm)]
        if max_sdk is not None:
            attrs.append(("maxSdkVersion", True, TYPE_INT_DEC, max_sdk))
        if flags:
            attrs.append(("usesPermissionFlags", True, TYPE_INT_HEX, flags))
        start("uses-permission", attrs)
        end("uses-permission")
    start("application", [("label", True, TYPE_STRING, "ATL Bluetooth test")])
    end("application")
    end("manifest")
    nodes.append(struct.pack("<HHIIIII", 0x0101, 16, 24, 1, 0xFFFFFFFF, prefix, uri))

    resmap = b"".join(struct.pack("<I", ATTR_IDS[n]) for n in names)
    resmap = struct.pack("<HHI", 0x0180, 8, 8 + len(resmap)) + resmap
    body = pool.chunk() + resmap + b"".join(nodes)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


def main():
    out, dex = sys.argv[1:3]
    disavow = "--disavow" in sys.argv[3:]
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("AndroidManifest.xml", manifest(disavow))
        z.write(dex, "classes.dex")


if __name__ == "__main__":
    main()
