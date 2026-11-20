#!/usr/bin/env python3
"""teslalinux: let Waze's car map draw on its 60 frames/s schedule (one verified Waze version).

  waze-map-pacing.py APP_DIR OUT.jar        (APP_DIR: an installed app, /var/lib/teslalinux/apps/com.waze)
  waze-map-pacing.py --check APP_DIR        report only: is this version known and the code as expected

Waze 5.27.5 holds its car map at 30 frames/s in two places, both meant to save a phone's battery and
both lifted to 60 only for 5 s after a touch on the phone's map view, which the car's map never gets
(its gestures come through the car app library's SurfaceCallback):
  - the map loop (com.waze.map.de.run(), on Waze's native looper) draws a frame and posts itself
    again 17 ms later after a touch, else 33 ms later. (On a phone the first touch unlocks 60 for
    good: the touch time is wall-clock time and the comparison uses uptime.)
  - the map renderer's frame limiter (com.waze.bq.a, the base of com.waze.map.opengl.m) skips a frame
    that comes less than 1000/30 ms after the last one; a touch sets it to 60 (opengl.m.a()) and,
    5 s later (opengl.l, through opengl.m.b()), back to 30.
So the car's map draws at most 30 frames/s whatever the hardware does.

This copies the dex that holds those classes out of the installed base.apk, changes those literals
(the loop's 33 ms to 17; the limiter's starting and falling-back 30 frames/s to 60), each checked to
be exactly where and what it should be, recomputes the dex's SHA-1 signature and Adler-32 checksum
and writes the dex as classes.dex of OUT.jar. ATL puts OUT.jar in front of the app's own
classes (ATL_APP_CLASS_OVERRIDES), so that class is loaded from it; the APK itself is never changed
(Google APIs check its signing block). Nothing of Waze is in this repository: the jar is made on the
device from the installed APK.

Only a Waze version listed in VERIFIED, whose method holds exactly the expected constants, is changed;
anything else exits 2 and writes nothing.
"""
import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
APK_INFO = os.path.join(HERE, "apk-info.py")

# versionCode -> edits: (class, method, opcode, the value to change, its replacement, other values the
# method must hold exactly once each, to be sure it is that code). Opcodes, both format 21s (a 16-bit
# literal): 0x13 const/16, 0x16 const-wide/16.
CONST16, CONST_WIDE16 = 0x13, 0x16
VERIFIED = {
    1030750: [  # Waze 5.27.5.0
        ("Lcom/waze/map/de;", "run", CONST_WIDE16, 33, 17, (5000, 17)),
        ("Lcom/waze/bq/a;", "<init>", CONST16, 30, 60, ()),
        ("Lcom/waze/map/opengl/m;", "b", CONST16, 30, 60, ()),  # what opengl.l calls 5 s after a boost
    ],
}
# the version names of VERIFIED, for the settings page (services/api/api.py map_pacing_status)
VERIFIED_NAMES = {1030750: "5.27.5.0"}


def uleb(data, off):
    result = shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, off
        shift += 7


class Dex:
    def __init__(self, data):
        if data[:4] != b"dex\n":
            raise ValueError("not a dex file")
        self.d = data
        (self.string_ids_size, self.string_ids_off, self.type_ids_size, self.type_ids_off,
         _proto_size, _proto_off, _field_size, _field_off, self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from("<12I", data, 0x38)

    def string(self, idx):
        off = struct.unpack_from("<I", self.d, self.string_ids_off + 4 * idx)[0]
        _length, off = uleb(self.d, off)
        end = self.d.index(b"\0", off)
        return self.d[off:end].decode("utf-8", "replace")  # MUTF-8; plain ASCII names here

    def type_name(self, idx):
        return self.string(struct.unpack_from("<I", self.d, self.type_ids_off + 4 * idx)[0])

    def method_name(self, idx):
        _cls, _proto, name = struct.unpack_from("<HHI", self.d, self.method_ids_off + 8 * idx)
        return self.string(name)

    def find_class(self, descriptor):
        for i in range(self.class_defs_size):
            off = self.class_defs_off + 32 * i
            class_idx = struct.unpack_from("<I", self.d, off)[0]
            if self.type_name(class_idx) == descriptor:
                return struct.unpack_from("<I", self.d, off + 24)[0]  # class_data_off
        return None

    def methods(self, class_data_off):
        """(name, code_off) of the class's direct and virtual methods"""
        off = class_data_off
        sfs, off = uleb(self.d, off)
        ifs, off = uleb(self.d, off)
        dms, off = uleb(self.d, off)
        vms, off = uleb(self.d, off)
        for _ in range(sfs + ifs):
            _, off = uleb(self.d, off)
            _, off = uleb(self.d, off)
        out = []
        for count in (dms, vms):
            idx = 0
            for _ in range(count):
                diff, off = uleb(self.d, off)
                idx += diff
                _access, off = uleb(self.d, off)
                code_off, off = uleb(self.d, off)
                out.append((self.method_name(idx), code_off))
        return out


def literals(insns_off, insns_units, data, opcode):
    """(offset of the literal, register, value) of every instruction OPCODE of format 21s"""
    out = []
    pc = 0
    # Walking the instruction stream needs every opcode's width; this scans 16-bit units for the
    # opcode instead. A unit that only looks like one adds a value, and the exact counts checked
    # below then refuse the edit: a miss is safe, never a wrong change.
    while pc < insns_units:
        unit = struct.unpack_from("<H", data, insns_off + 2 * pc)[0]
        if unit & 0xFF == opcode and pc + 1 < insns_units:
            value = struct.unpack_from("<h", data, insns_off + 2 * (pc + 1))[0]
            out.append((insns_off + 2 * (pc + 1), unit >> 8, value))
        pc += 1
    return out


def patch(app_dir, out_jar, check_only):
    base = os.path.join(app_dir, "base.apk")
    info = json.loads(subprocess.run([sys.executable, "-I", APK_INFO, base], check=True,
                                     capture_output=True, text=True).stdout)
    vc = int(info["versionCode"])
    if info.get("package") != "com.waze" or vc not in VERIFIED:
        print("waze-map-pacing: %s versionCode %s is not a verified Waze version (%s): nothing changed"
              % (info.get("package"), vc, ", ".join(str(v) for v in VERIFIED)), file=sys.stderr)
        return 2
    edits = VERIFIED[vc]
    with zipfile.ZipFile(base) as z:
        for name in sorted(n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")):
            data = bytearray(z.read(name))
            dex = Dex(data)
            found = [(e, dex.find_class(e[0])) for e in edits]
            if all(cd is None for _, cd in found):
                continue
            # every edit's class must be in this one dex: the jar carries one dex
            missing = [e[0] for e, cd in found if cd is None]
            if missing:
                print("waze-map-pacing: %s holds some of the classes but not %s: nothing changed"
                      % (name, ", ".join(missing)), file=sys.stderr)
                return 2
            planned = []
            for (descriptor, method, opcode, old, new, others), class_data in found:
                code = [c for n, c in dex.methods(class_data) if n == method and c]
                if len(code) != 1:
                    print("waze-map-pacing: %s.%s not found once in %s: nothing changed" % (descriptor, method, name), file=sys.stderr)
                    return 2
                insns_units = struct.unpack_from("<I", data, code[0] + 12)[0]
                consts = literals(code[0] + 16, insns_units, data, opcode)
                values = [v for _, _, v in consts]
                expected = (old,) + tuple(others)
                if any(values.count(v) != 1 for v in expected):
                    print("waze-map-pacing: %s %s.%s: literals %s, expected exactly one each of %s: nothing changed"
                          % (name, descriptor, method, values, ", ".join(str(v) for v in expected)), file=sys.stderr)
                    return 2
                where = [off for off, _, v in consts if v == old][0]
                planned.append((where, new))
                print("waze-map-pacing: Waze %s (%d): %s %s.%s: %d -> %d (dex offset 0x%x)"
                      % (info.get("versionName"), vc, name, descriptor, method, old, new, where))
            if check_only:
                return 0
            for where, new in planned:
                struct.pack_into("<h", data, where, new)
            data[12:32] = hashlib.sha1(bytes(data[32:])).digest()
            struct.pack_into("<I", data, 8, zlib.adler32(bytes(data[12:])) & 0xFFFFFFFF)
            os.makedirs(os.path.dirname(os.path.abspath(out_jar)), exist_ok=True)
            fd, tmp = tempfile.mkstemp(dir=os.path.dirname(os.path.abspath(out_jar)), suffix=".jar")
            os.close(fd)
            with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as out:
                out.writestr("classes.dex", bytes(data))
            os.chmod(tmp, 0o644)
            os.replace(tmp, out_jar)
            print("waze-map-pacing: wrote %s" % out_jar)
            return 0
    print("waze-map-pacing: none of %s is in a dex of %s" % (", ".join(e[0] for e in edits), base), file=sys.stderr)
    return 2


def main():
    args = sys.argv[1:]
    check = "--check" in args
    args = [a for a in args if a != "--check"]
    if (check and len(args) != 1) or (not check and len(args) != 2):
        print(__doc__.split("\n\n")[1], file=sys.stderr)
        return 2
    return patch(args[0], None if check else args[1], check)


if __name__ == "__main__":
    sys.exit(main())
