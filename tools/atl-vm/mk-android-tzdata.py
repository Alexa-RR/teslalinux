#!/usr/bin/env python3
"""teslalinux: build Android's packed time zone file (tzdata) from the system's IANA zoneinfo.

  mk-android-tzdata.py [--zoneinfo /usr/share/zoneinfo] OUT
      e.g. OUT = $ANDROID_DATA/misc/zoneinfo/current/tzdata (libcore also reads
      $ANDROID_ROOT/usr/share/zoneinfo/tzdata, but ART looks for its boot image under ANDROID_ROOT too)

java.util.TimeZone in ART's libcore does not read /usr/share/zoneinfo: it reads one packed file,
"tzdata", that AOSP builds with ZoneCompactor (system/timezone). art_standalone ships none, so every
zone resolved to GMT. The format (big-endian):
  header   char[12] "tzdata" + version + NUL (e.g. "tzdata2026f"), int index_offset, int data_offset,
           int zonetab_offset
  index    one 52-byte entry per zone, sorted by name: char[40] name (NUL padded), int offset (from
           data_offset), int length, int unused (0)
  data     the zones' TZif files, concatenated
  zonetab  the text of zone.tab
libcore's ZoneInfoData reads the 64-bit section of TZif version 2+ files and ignores the POSIX footer,
so the input must be "fat" zoneinfo (every transition written out); Debian and Alpine ship fat files.
Slim files (only the transitions up to the footer) are refused. Run again after the tzdata package
changes; the IANA version is taken from tzdata.zi.
"""
import argparse
import os
import struct
import sys

SKIP_DIRS = {"posix", "right"}
SKIP_FILES = {"posixrules", "localtime", "Factory"}


def zones(root):
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for f in filenames:
            if f in SKIP_FILES or "." in f:
                continue
            path = os.path.join(dirpath, f)
            name = os.path.relpath(path, root)
            if len(name.encode()) > 39:
                continue
            with open(path, "rb") as fh:
                data = fh.read()
            if not data.startswith(b"TZif"):
                continue
            out.append((name, data))
    return sorted(out)


def check_fat(name, data):
    """False for a slim TZif v2+ file: no transitions in its 32-bit block but some in the 64-bit one
    (slim files leave the 32-bit block empty and the future to the POSIX footer, which libcore ignores)"""
    if data[4:5] == b"\0":
        return True  # version 1: only the 32-bit block exists
    isut, isstd, leap, timecnt, typecnt, charcnt = struct.unpack(">6l", data[20:44])
    v1_size = timecnt * 5 + typecnt * 6 + charcnt + leap * 8 + isstd + isut
    v2 = 44 + v1_size
    if data[v2:v2 + 4] != b"TZif":
        return True
    timecnt2 = struct.unpack(">l", data[v2 + 32:v2 + 36])[0]
    return not (timecnt == 0 and timecnt2 > 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out")
    ap.add_argument("--zoneinfo", default="/usr/share/zoneinfo")
    a = ap.parse_args()
    version = None
    zi = os.path.join(a.zoneinfo, "tzdata.zi")
    if os.path.exists(zi):
        with open(zi) as f:
            first = f.readline().split()
            if len(first) >= 3 and first[1] == "version":
                version = first[2]
    if not version or len(version) != 5:
        sys.exit("mk-android-tzdata: no IANA version (tzdata.zi) in %s" % a.zoneinfo)
    zs = zones(a.zoneinfo)
    slim = [n for n, d in zs if not check_fat(n, d)]
    if slim and len(slim) > len(zs) // 4:
        sys.exit("mk-android-tzdata: %s holds slim TZif files (%d of %d); libcore needs fat ones" % (a.zoneinfo, len(slim), len(zs)))
    with open(os.path.join(a.zoneinfo, "zone.tab"), "rb") as f:
        zonetab = f.read()
    header_size = 12 + 3 * 4
    index_offset = header_size
    data_offset = index_offset + 52 * len(zs)
    index = bytearray()
    data = bytearray()
    for name, blob in zs:
        index += name.encode().ljust(40, b"\0") + struct.pack(">lll", len(data), len(blob), 0)
        data += blob
    zonetab_offset = data_offset + len(data)
    out = bytearray(("tzdata" + version).encode().ljust(12, b"\0"))
    out += struct.pack(">lll", index_offset, data_offset, zonetab_offset)
    out += index + data + zonetab
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    tmp = a.out + ".tmp"
    with open(tmp, "wb") as f:
        f.write(out)
    os.replace(tmp, a.out)
    print("mk-android-tzdata: %s: tzdata%s, %d zones, %d bytes" % (a.out, version, len(zs), len(out)))


if __name__ == "__main__":
    main()
