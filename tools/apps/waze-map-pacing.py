#!/usr/bin/env python3
"""teslalinux: Waze's car map on the display's 60 frames/s schedule (verified Waze versions only).

  waze-map-pacing.py [--mode full|auto] APP_DIR OUT.jar [--atl API_IMPL_JAR]
                                            make the class override (APP_DIR: an installed app,
                                            /var/lib/teslalinux/apps/com.waze); exit 2: not this version
                                            (auto with --atl: and only if this ATL has ATLFrameRateLimiter)
  waze-map-pacing.py --check [--mode full|auto] APP_DIR [--atl API_IMPL_JAR]
                                            report only: is this version known and the code as expected

Waze 5.27.5 holds its car map at 30 frames/s in two places, both meant to save a phone's battery and
both lifted to 60 only for 5 s after a touch on the phone's map view (or, in the car interface, a pan,
zoom or tap the car host hands its SurfaceCallback):
  - the map loop (com.waze.map.de.run(), on Waze's native looper) draws a frame and posts itself
    again 17 ms later after a touch, else 33 ms later. (On a phone the first touch unlocks 60 for
    good: the touch time is wall-clock time and the comparison uses uptime.)
  - the map renderer's frame limiter (com.waze.bq.a, the base of com.waze.map.opengl.m) skips a frame
    that comes less than 1000/30 ms after the last one; a touch sets it to 60 (opengl.m.a()) and,
    5 s later (opengl.l, through opengl.m.b()), back to 30. The car interface's render thread
    (com.waze.bq.e, "RenderThread") asks only this limiter before each frame.
So the car's map draws at 30 frames/s while the car drives, whatever the hardware does.

Two modes (Settings > Waze interface > Map frame rate):

full: 60 frames/s all the time. This copies the dex that holds those classes out of the installed
  base.apk, changes those literals (the loop's 33 ms to 17; the limiter's starting and falling-back
  30 frames/s to 60), each checked to be exactly where and what it should be, recomputes the dex's
  SHA-1 signature and Adler-32 checksum and writes the dex as classes.dex of OUT.jar.

auto: 60 frames/s while the map moves, Waze's own 30 while it is still. OUT.jar holds one class
  written here: the frame limiter class of the same name, extending ATL's
  android.atl.ATLFrameRateLimiter, with the limiter's methods (each calling ATL's for the same
  thing) and Waze's own starting rate. ATL's class keeps the app's rate and its own boosts, and lets
  frames come at the display's rate for 5 s after ATL saw the map move: the car host's map surface
  touched, the car page's input, a new car template, a location fix of a moving car. Before writing
  it, every reference to the class in every dex of the APK is checked to be to one of those methods,
  the class's own code to be the limiter it is known to be, and its subclasses to be the one known.

ATL puts OUT.jar in front of the app's own classes (ATL_APP_CLASS_OVERRIDES), so the class(es) in
it are loaded from it; the APK itself is never changed (Google APIs check its signing block).
Nothing of Waze is in this repository: the jar is made on the device from the installed APK.

Only a Waze version listed in VERIFIED (full) or VERIFIED_AUTO (auto), whose code holds exactly what
is expected, is changed; anything else exits 2 and writes nothing.
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

# auto: versionCode -> the frame limiter as it was read in that version (by hand, from its dex): its
# class and fields, its methods with their code (the disassembly below, references resolved), the
# subclasses that extend it, and Waze's starting rate.
LIMITER_5_27_5 = "Lcom/waze/bq/a;"
VERIFIED_AUTO = {
    1030750: {  # Waze 5.27.5.0
        "class": LIMITER_5_27_5,
        "subclasses": ("Lcom/waze/map/opengl/m;",),
        "rate": 30,
        # (name, type, access): a private volatile long (the last frame's start), a private final AtomicLong (the interval)
        "fields": (("a", "J", 0x42), ("b", "Ljava/util/concurrent/atomic/AtomicLong;", 0x12)),
        # the methods other classes may call: (name, proto, access) -> what ATL's class does for it
        "api": {
            ("<init>", "()V", 0x10001): ("<init>", "(I)V"),          # public constructor: Waze's rate
            ("c", "(I)V", 0x14): ("setAppFrameRate", "(I)V"),        # protected final: set the rate
            ("d", "()Z", 0x11): ("isTooSoon", "()Z"),                # public final: skip this frame?
            ("e", "(J)V", 0x11): ("setFrameStarted", "(J)V"),        # public final: a frame started now
        },
        # private static final a(I)J: frames a second to the interval in ms (1000 / fps)
        "private": (("a", "(I)J", 0x1a),),
        "code": {
            ("<init>", "()V"): (4, 1, [
                "invoke-direct {v3} Ljava/lang/Object;-><init>()V",
                "invoke-static {} Landroid/os/SystemClock;->elapsedRealtime()J",
                "move-result-wide v0",
                "iput-wide v0 v3 Lcom/waze/bq/a;->a:J",
                "new-instance v0 Ljava/util/concurrent/atomic/AtomicLong;",
                "const/16 v1 30",
                "invoke-static {v1} Lcom/waze/bq/a;->a(I)J",
                "move-result-wide v1",
                "invoke-direct {v0,v1,v2} Ljava/util/concurrent/atomic/AtomicLong;-><init>(J)V",
                "iput-object v0 v3 Lcom/waze/bq/a;->b:Ljava/util/concurrent/atomic/AtomicLong;",
                "return-void"]),
            ("a", "(I)J"): (5, 1, [
                "sget-object v0 Ljava/util/concurrent/TimeUnit;->SECONDS:Ljava/util/concurrent/TimeUnit;",
                "const-wide/16 v0 1000",
                "int-to-long v2 v4",
                "div-long/2addr v0 v2",
                "return-wide v0"]),
            ("c", "(I)V"): (4, 2, [
                "iget-object v2 v2 Lcom/waze/bq/a;->b:Ljava/util/concurrent/atomic/AtomicLong;",
                "invoke-static {v3} Lcom/waze/bq/a;->a(I)J",
                "move-result-wide v0",
                "invoke-virtual {v2,v0,v1} Ljava/util/concurrent/atomic/AtomicLong;->set(J)V",
                "return-void"]),
            ("d", "()Z"): (9, 1, [
                "iget-object v0 v8 Lcom/waze/bq/a;->b:Ljava/util/concurrent/atomic/AtomicLong;",
                "invoke-virtual {v0} Ljava/util/concurrent/atomic/AtomicLong;->get()J",
                "move-result-wide v0",
                "const-wide/16 v2 -1",
                "cmp-long v2 v0 v2",
                "const/4 v3 0",
                "if-eqz v2 +14",
                "invoke-static {} Landroid/os/SystemClock;->elapsedRealtime()J",
                "move-result-wide v4",
                "iget-wide v6 v8 Lcom/waze/bq/a;->a:J",
                "sub-long/2addr v4 v6",
                "cmp-long v8 v4 v0",
                "if-gez v8 +3",
                "const/4 v3 1",
                "return v3"]),
            ("e", "(J)V"): (3, 3, [
                "iput-wide v1 v0 Lcom/waze/bq/a;->a:J",
                "return-void"]),
        },
    },
}
# the version names, for the settings page (services/api/api.py map_pacing_status)
VERIFIED_NAMES = {1030750: "5.27.5.0"}

ATL_LIMITER = "Landroid/atl/ATLFrameRateLimiter;"
OBJECT = "Ljava/lang/Object;"


class Mismatch(Exception):
    pass


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
         self.proto_ids_size, self.proto_ids_off, self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off, self.class_defs_size, self.class_defs_off) = \
            struct.unpack_from("<12I", data, 0x38)

    def string(self, idx):
        off = struct.unpack_from("<I", self.d, self.string_ids_off + 4 * idx)[0]
        _length, off = uleb(self.d, off)
        end = self.d.index(b"\0", off)
        return self.d[off:end].decode("utf-8", "replace")  # MUTF-8; plain ASCII names here

    def type_name(self, idx):
        return self.string(struct.unpack_from("<I", self.d, self.type_ids_off + 4 * idx)[0])

    def proto(self, idx):
        _shorty, ret, params_off = struct.unpack_from("<III", self.d, self.proto_ids_off + 12 * idx)
        params = []
        if params_off:
            n = struct.unpack_from("<I", self.d, params_off)[0]
            params = [self.type_name(struct.unpack_from("<H", self.d, params_off + 4 + 2 * k)[0]) for k in range(n)]
        return "(" + "".join(params) + ")" + self.type_name(ret)

    def method_name(self, idx):
        _cls, _proto, name = struct.unpack_from("<HHI", self.d, self.method_ids_off + 8 * idx)
        return self.string(name)

    def method(self, idx):
        cls, proto, name = struct.unpack_from("<HHI", self.d, self.method_ids_off + 8 * idx)
        return self.type_name(cls), self.string(name), self.proto(proto)

    def field(self, idx):
        cls, typ, name = struct.unpack_from("<HHI", self.d, self.field_ids_off + 8 * idx)
        return self.type_name(cls), self.string(name), self.type_name(typ)

    def find_class(self, descriptor):
        for i in range(self.class_defs_size):
            off = self.class_defs_off + 32 * i
            class_idx = struct.unpack_from("<I", self.d, off)[0]
            if self.type_name(class_idx) == descriptor:
                return struct.unpack_from("<I", self.d, off + 24)[0]  # class_data_off
        return None

    def class_defs(self):
        """(descriptor, access, superclass, interfaces_off, class_data_off) of every class defined here"""
        out = []
        for i in range(self.class_defs_size):
            off = self.class_defs_off + 32 * i
            class_idx, access, super_idx, ifaces, _src, _ann, data_off, _sv = struct.unpack_from("<8I", self.d, off)
            out.append((self.type_name(class_idx), access,
                        self.type_name(super_idx) if super_idx != 0xFFFFFFFF else None, ifaces, data_off))
        return out

    def methods(self, class_data_off):
        """(name, code_off) of the class's direct and virtual methods"""
        return [(n, c) for n, _p, _a, c in self.class_methods(class_data_off)]

    def class_fields(self, class_data_off):
        """(name, type, access) of the class's static and instance fields"""
        off = class_data_off
        sfs, off = uleb(self.d, off)
        ifs, off = uleb(self.d, off)
        _dms, off = uleb(self.d, off)
        _vms, off = uleb(self.d, off)
        out = []
        for count in (sfs, ifs):
            idx = 0
            for _ in range(count):
                diff, off = uleb(self.d, off)
                idx += diff
                access, off = uleb(self.d, off)
                _cls, name, typ = self.field(idx)
                out.append((name, typ, access))
        return out

    def class_methods(self, class_data_off):
        """(name, proto, access, code_off) of the class's direct and virtual methods"""
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
                access, off = uleb(self.d, off)
                code_off, off = uleb(self.d, off)
                _cls, name, proto = self.method(idx)
                out.append((name, proto, access, code_off))
        return out

    def code(self, code_off):
        regs, ins, _outs, tries, _dbg, n = struct.unpack_from("<HHHHII", self.d, code_off)
        return regs, ins, tries, struct.unpack_from("<%dH" % n, self.d, code_off + 16)

    def method_refs(self):
        """(class, name, proto) of every method id"""
        return [self.method(i) for i in range(self.method_ids_size)]

    def field_refs(self):
        """(class, name, type) of every field id"""
        return [self.field(i) for i in range(self.field_ids_size)]


# ---------------------------------------------------------------------------- mode full

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


def app_info(app_dir):
    base = os.path.join(app_dir, "base.apk")
    info = json.loads(subprocess.run([sys.executable, "-I", APK_INFO, base], check=True,
                                     capture_output=True, text=True).stdout)
    return base, info, int(info["versionCode"])


def patch_full(app_dir, out_jar, check_only):
    base, info, vc = app_info(app_dir)
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
            write_jar(bytes(data), out_jar)
            print("waze-map-pacing: wrote %s" % out_jar)
            return 0
    print("waze-map-pacing: none of %s is in a dex of %s" % (", ".join(e[0] for e in edits), base), file=sys.stderr)
    return 2


# ---------------------------------------------------------------------------- mode auto: reading

def s16(u):
    return struct.unpack("<h", struct.pack("<H", u))[0]


def disassemble(dex, units):
    """The instructions of a method as text with every reference resolved, for the opcodes the
    limiter's methods use; any other refuses, so an unexpected method never passes."""
    out, pc = [], 0
    binop_2addr = {0xbb: "add-long/2addr", 0xbc: "sub-long/2addr", 0xbd: "mul-long/2addr", 0xbe: "div-long/2addr"}
    ifz = {0x38: "if-eqz", 0x39: "if-nez", 0x3a: "if-ltz", 0x3b: "if-gez", 0x3c: "if-gtz", 0x3d: "if-lez"}
    fields = ["iget", "iget-wide", "iget-object", "iget-boolean", "iget-byte", "iget-char", "iget-short",
              "iput", "iput-wide", "iput-object", "iput-boolean", "iput-byte", "iput-char", "iput-short"]
    sfields = [f.replace("i", "s", 1) for f in fields]
    while pc < len(units):
        u = units[pc]
        op, aa = u & 0xFF, u >> 8
        a, b = aa & 0xF, aa >> 4
        if op == 0x0e:
            out.append("return-void"); pc += 1
        elif op in (0x0f, 0x10, 0x11):
            out.append("%s v%d" % ({0x0f: "return", 0x10: "return-wide", 0x11: "return-object"}[op], aa)); pc += 1
        elif op in (0x0a, 0x0b, 0x0c):
            out.append("%s v%d" % ({0x0a: "move-result", 0x0b: "move-result-wide", 0x0c: "move-result-object"}[op], aa)); pc += 1
        elif op == 0x12:
            out.append("const/4 v%d %d" % (a, b - 16 if b > 7 else b)); pc += 1
        elif op == 0x13:
            out.append("const/16 v%d %d" % (aa, s16(units[pc + 1]))); pc += 2
        elif op == 0x16:
            out.append("const-wide/16 v%d %d" % (aa, s16(units[pc + 1]))); pc += 2
        elif op == 0x22:
            out.append("new-instance v%d %s" % (aa, dex.type_name(units[pc + 1]))); pc += 2
        elif op == 0x31:
            out.append("cmp-long v%d v%d v%d" % (aa, units[pc + 1] & 0xFF, units[pc + 1] >> 8)); pc += 2
        elif op in ifz:
            out.append("%s v%d %+d" % (ifz[op], aa, s16(units[pc + 1]))); pc += 2
        elif op == 0x81:
            out.append("int-to-long v%d v%d" % (a, b)); pc += 1
        elif op in binop_2addr:
            out.append("%s v%d v%d" % (binop_2addr[op], a, b)); pc += 1
        elif 0x52 <= op <= 0x5f:
            cls, name, typ = dex.field(units[pc + 1])
            out.append("%s v%d v%d %s->%s:%s" % (fields[op - 0x52], a, b, cls, name, typ)); pc += 2
        elif 0x60 <= op <= 0x6d:
            cls, name, typ = dex.field(units[pc + 1])
            out.append("%s v%d %s->%s:%s" % (sfields[op - 0x60], aa, cls, name, typ)); pc += 2
        elif 0x6e <= op <= 0x72:
            kind = ["invoke-virtual", "invoke-super", "invoke-direct", "invoke-static", "invoke-interface"][op - 0x6e]
            count, g = aa >> 4, aa & 0xF
            regs = [units[pc + 2] & 0xF, (units[pc + 2] >> 4) & 0xF, (units[pc + 2] >> 8) & 0xF, units[pc + 2] >> 12, g][:count]
            cls, name, proto = dex.method(units[pc + 1])
            out.append("%s {%s} %s->%s%s" % (kind, ",".join("v%d" % r for r in regs), cls, name, proto)); pc += 3
        else:
            raise Mismatch("opcode 0x%02x not expected" % op)
    return out


def expect(what, got, want):
    if got != want:
        raise Mismatch("%s: %s, expected %s" % (what, got, want))


def check_limiter(dex, v):
    """the class's definition and code are exactly the limiter read for this version"""
    cls = v["class"]
    defs = [d for d in dex.class_defs() if d[0] == cls]
    _name, access, sup, ifaces, data_off = defs[0]
    expect(cls + " access", access, 0x1)  # public, not final: its subclasses extend it
    expect(cls + " superclass", sup, OBJECT)
    expect(cls + " interfaces", ifaces, 0)
    expect(cls + " fields", sorted(dex.class_fields(data_off)), sorted(v["fields"]))
    methods = dex.class_methods(data_off)
    expect(cls + " methods", sorted((n, p, a) for n, p, a, _c in methods),
           sorted(list(v["api"]) + list(v["private"])))
    for n, p, _a, code_off in methods:
        regs, ins, tries, units = dex.code(code_off)
        want_regs, want_ins, want = v["code"][(n, p)]
        expect("%s.%s%s" % (cls, n, p), (regs, ins, tries, disassemble(dex, units)), (want_regs, want_ins, 0, want))


def check_references(dexes, v):
    """every use of the limiter anywhere in the APK is one the generated class provides: its
    subclasses are the known ones, method references on it (or through a subclass) name the API,
    and its private members are used by its own dex only"""
    cls = v["class"]
    subclasses = set(v["subclasses"])
    api = set((n, p) for n, p, _a in v["api"])
    private = set((n, p) for n, p, _a in v["private"])
    own = {}  # subclass -> its own (name, proto) methods and (name, type) fields
    defining = None
    for name, dex in dexes:
        for d in dex.class_defs():
            if d[0] == cls:
                if defining is not None:
                    raise Mismatch("%s is defined in more than one dex" % cls)
                defining = name
            if d[2] == cls and d[0] not in subclasses:
                raise Mismatch("%s extends %s (only %s are known to)" % (d[0], cls, ", ".join(sorted(subclasses))))
            if d[0] in subclasses:
                if d[2] != cls:
                    raise Mismatch("%s no longer extends %s" % (d[0], cls))
                if d[1] & 0x400:
                    raise Mismatch("%s is abstract (not the class read)" % d[0])
                own[d[0]] = (set((n, p) for n, p, _a, _c in dex.class_methods(d[4])),
                             set((n, t) for n, t, _a in dex.class_fields(d[4])))
    if defining is None:
        raise Mismatch("no dex holds %s" % cls)
    if set(own) != subclasses:
        raise Mismatch("subclasses of %s found: %s, expected %s" % (cls, sorted(own), sorted(subclasses)))
    for name, dex in dexes:
        for c, n, p in dex.method_refs():
            if c == cls:
                if (n, p) in api or ((n, p) in private and name == defining):
                    continue
                raise Mismatch("%s refers to %s->%s%s" % (name, c, n, p))
            if c in subclasses:
                if (n, p) in own[c][0] or ((n, p) in api and n != "<init>"):
                    continue
                if (n, p) in (("equals", "(Ljava/lang/Object;)Z"), ("hashCode", "()I"), ("toString", "()Ljava/lang/String;"),
                              ("getClass", "()Ljava/lang/Class;")):
                    continue  # java.lang.Object's, through the class
                raise Mismatch("%s refers to %s->%s%s (neither its own nor the limiter's)" % (name, c, n, p))
        for c, n, t in dex.field_refs():
            if c == cls and name != defining:
                raise Mismatch("%s refers to %s->%s (a private field) outside its dex" % (name, c, n))
            if c in subclasses and (n, t) not in own[c][1]:
                raise Mismatch("%s refers to %s->%s:%s, not a field of its own" % (name, c, n, t))
    return defining


def check_atl(api_impl, v):
    """ATL's api-impl.jar has ATLFrameRateLimiter with what the generated class calls (an older ATL
    without it would make Waze's limiter class fail to load: no map)"""
    want = set(v["api"].values())
    with zipfile.ZipFile(api_impl) as z:
        for name in sorted(n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")):
            dex = Dex(z.read(name))
            data_off = dex.find_class(ATL_LIMITER)
            if data_off is None:
                continue
            access = [d[1] for d in dex.class_defs() if d[0] == ATL_LIMITER][0]
            if access & 0x10 or not access & 0x1:
                raise Mismatch("%s in %s is not a public class open to subclasses" % (ATL_LIMITER, api_impl))
            have = set((n, p) for n, p, a, _c in dex.class_methods(data_off) if a & 0x5)  # public or protected
            missing = want - have
            if missing:
                raise Mismatch("%s in %s lacks %s" % (ATL_LIMITER, api_impl, ", ".join(n + p for n, p in sorted(missing))))
            return
    raise Mismatch("ATL's %s has no %s (an ATL older than the automatic map frame rate)" % (api_impl, ATL_LIMITER))


def check_auto(app_dir):
    """(versionCode, versionName, the version's VERIFIED_AUTO entry) when the installed app is a
    verified version whose limiter is as expected; raises Mismatch otherwise"""
    base, info, vc = app_info(app_dir)
    if info.get("package") != "com.waze" or vc not in VERIFIED_AUTO:
        raise Mismatch("%s versionCode %s is not a verified Waze version (%s)"
                       % (info.get("package"), vc, ", ".join(str(k) for k in VERIFIED_AUTO)))
    v = VERIFIED_AUTO[vc]
    with zipfile.ZipFile(base) as z:
        dexes = [(name, Dex(z.read(name))) for name in sorted(n for n in z.namelist()
                                                             if n.startswith("classes") and n.endswith(".dex"))]
    defining = check_references(dexes, v)
    check_limiter(dict(dexes)[defining], v)
    return vc, info.get("versionName"), v


# ---------------------------------------------------------------------------- mode auto: writing

def write_uleb(value):
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def align(buf, n=4):
    while len(buf) % n:
        buf.append(0)


def param_types(proto):
    params = proto[1:].split(")")[0]
    out, i = [], 0
    while i < len(params):
        if params[i] == "L":
            j = params.index(";", i)
            out.append(params[i:j + 1])
            i = j + 1
        elif params[i] == "[":
            raise ValueError("arrays not used here")
        else:
            out.append(params[i])
            i += 1
    return out


def shorty(proto):
    ret = proto.split(")")[1]
    return "".join("L" if t[0] == "L" else t for t in [ret] + param_types(proto))


def limiter_class_dex(v):
    """A dex with one class: v["class"], public, extending ATL_LIMITER: a constructor that hands
    ATL's Waze's starting rate, and each of the limiter's methods calling ATL's for the same thing."""
    cls = v["class"]
    api = sorted(v["api"].items())
    methods = [(ATL_LIMITER, an, ap) for (_n, _p, _a), (an, ap) in api] + [(cls, n, p) for (n, p, _a), _t in api]
    protos = sorted(set(p for _c, _n, p in methods))
    types = set([cls, ATL_LIMITER])
    for p in protos:
        types.update(param_types(p))
        types.add(p.split(")")[1])
    strings = sorted(set(types) | set(n for _c, n, _p in methods) | set(shorty(p) for p in protos))
    sidx = {s: i for i, s in enumerate(strings)}  # ASCII: code point order is Java's String order
    types = sorted(types, key=lambda t: sidx[t])
    tidx = {t: i for i, t in enumerate(types)}
    protos = sorted(protos, key=lambda p: (tidx[p.split(")")[1]], [tidx[t] for t in param_types(p)]))
    pidx = {p: i for i, p in enumerate(protos)}
    methods = sorted(methods, key=lambda m: (tidx[m[0]], sidx[m[1]], pidx[m[2]]))
    midx = {m: i for i, m in enumerate(methods)}

    header_size = 0x70
    string_ids_off = header_size
    type_ids_off = string_ids_off + 4 * len(strings)
    proto_ids_off = type_ids_off + 4 * len(types)
    method_ids_off = proto_ids_off + 12 * len(protos)
    class_defs_off = method_ids_off + 8 * len(methods)
    data_off = class_defs_off + 32
    data = bytearray()
    sections = []  # (map type, count, offset)

    def here():
        return data_off + len(data)

    type_list_off = {}
    first = None
    for p in protos:
        params = param_types(p)
        if not params:
            continue
        align(data)
        type_list_off[p] = here()
        first = first if first is not None else here()
        data += struct.pack("<I", len(params)) + b"".join(struct.pack("<H", tidx[t]) for t in params)
    if type_list_off:
        sections.append((0x1001, len(type_list_off), first))

    def code_item(regs, ins, outs, insns):
        align(data)
        off = here()
        data.extend(struct.pack("<HHHHII", regs, ins, outs, 0, 0, len(insns)))
        data.extend(b"".join(struct.pack("<H", u) for u in insns))
        return off

    # the code: the parameters are the last registers (this, then the arguments; a long takes two)
    align(data)
    code_start = here()
    codes = {}
    for (n, p, _a), (an, ap) in api:
        target = midx[(ATL_LIMITER, an, ap)]
        if n == "<init>":
            # const/16 v0, rate; invoke-direct {v1, v0} ATL-><init>(I)V; return-void
            rate = v["rate"]
            if not 0 < rate < 0x8000:
                raise ValueError("rate %r" % rate)
            insns, regs, ins, outs = [0x0013, rate, 0x2070, target, 0x0001, 0x000e], 2, 1, 2
        else:
            width = 1 + sum(2 if t in ("J", "D") else 1 for t in param_types(p))
            ret = p.split(")")[1]
            # invoke-virtual {v0..v(width-1)} ATL's method (all of this method's registers, in order)
            arg_regs = 0
            for i in range(width):
                arg_regs |= i << (4 * i)
            insns = [(width << 12) | 0x006e, target, arg_regs]
            if ret == "V":
                insns.append(0x000e)
            elif ret in ("Z", "I"):
                insns += [0x000a, 0x000f]  # move-result v0; return v0
            elif ret in ("J", "D"):
                insns += [0x000b, 0x0010]  # move-result-wide v0; return-wide v0
            else:
                raise ValueError("return type %s not used here" % ret)
            regs, ins, outs = max(width, 2 if ret in ("J", "D") else 1), width, width
        codes[(cls, n, p)] = code_item(regs, ins, outs, insns)
    sections.append((0x2001, len(codes), code_start))

    string_data_off = []
    for s in strings:
        string_data_off.append(here())
        data += write_uleb(len(s)) + s.encode("ascii") + b"\0"
    sections.append((0x2002, len(strings), string_data_off[0]))

    # class data: the constructor (direct), the other methods (virtual), each in method index order
    class_data_off = here()
    direct = sorted((midx[(cls, n, p)], a) for (n, p, a), _t in api if n == "<init>")
    virtual = sorted((midx[(cls, n, p)], a) for (n, p, a), _t in api if n != "<init>")
    data += write_uleb(0) + write_uleb(0) + write_uleb(len(direct)) + write_uleb(len(virtual))
    for group in (direct, virtual):
        prev = None
        for m, access in group:
            data += write_uleb(m if prev is None else m - prev) + write_uleb(access) + write_uleb(codes[methods[m]])
            prev = m
    sections.append((0x2000, 1, class_data_off))

    align(data)
    map_off = here()
    items = [(0x0000, 1, 0), (0x0001, len(strings), string_ids_off), (0x0002, len(types), type_ids_off),
             (0x0003, len(protos), proto_ids_off), (0x0005, len(methods), method_ids_off),
             (0x0006, 1, class_defs_off)] + sections + [(0x1000, 1, map_off)]
    items.sort(key=lambda it: it[2])
    data += struct.pack("<I", len(items))
    for typ, count, off in items:
        data += struct.pack("<HHII", typ, 0, count, off)
    align(data)

    out = bytearray(data_off)
    file_size = data_off + len(data)
    struct.pack_into("<8s", out, 0, b"dex\n035\0")
    struct.pack_into("<20I", out, 0x20,
                     file_size, header_size, 0x12345678, 0, 0, map_off,
                     len(strings), string_ids_off, len(types), type_ids_off,
                     len(protos), proto_ids_off, 0, 0, len(methods), method_ids_off,
                     1, class_defs_off, len(data), data_off)
    for i, off in enumerate(string_data_off):
        struct.pack_into("<I", out, string_ids_off + 4 * i, off)
    for i, t in enumerate(types):
        struct.pack_into("<I", out, type_ids_off + 4 * i, sidx[t])
    for i, p in enumerate(protos):
        struct.pack_into("<III", out, proto_ids_off + 12 * i, sidx[shorty(p)], tidx[p.split(")")[1]], type_list_off.get(p, 0))
    for i, (c, n, p) in enumerate(methods):
        struct.pack_into("<HHI", out, method_ids_off + 8 * i, tidx[c], pidx[p], sidx[n])
    struct.pack_into("<8I", out, class_defs_off, tidx[cls], 0x1, tidx[ATL_LIMITER], 0, 0xFFFFFFFF, 0, class_data_off, 0)
    out += data
    out[12:32] = hashlib.sha1(bytes(out[32:])).digest()
    struct.pack_into("<I", out, 8, zlib.adler32(bytes(out[12:])) & 0xFFFFFFFF)
    return bytes(out)


def write_jar(dex_bytes, out_jar):
    os.makedirs(os.path.dirname(os.path.abspath(out_jar)), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(os.path.abspath(out_jar)), suffix=".jar")
    os.close(fd)
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as out:
        # the dex 4-byte aligned in the jar (the first entry's data starts after its 30-byte header,
        # the name and this extra field: zipalign's 0xD935 with the alignment and one padding byte),
        # so ART maps it in place instead of extracting a copy
        info = zipfile.ZipInfo("classes.dex", (1981, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_STORED
        info.extra = struct.pack("<HHH", 0xD935, 3, 4) + b"\0"
        out.writestr(info, dex_bytes)
    os.chmod(tmp, 0o644)
    os.replace(tmp, out_jar)


def patch_auto(app_dir, out_jar, check_only, atl):
    try:
        vc, vn, v = check_auto(app_dir)
        if atl:
            check_atl(atl, v)
    except Mismatch as e:
        print("waze-map-pacing: %s: nothing changed" % e, file=sys.stderr)
        return 2
    except (OSError, zipfile.BadZipFile, ValueError) as e:
        print("waze-map-pacing: %s: nothing changed" % e, file=sys.stderr)
        return 1
    dex = limiter_class_dex(v)
    print("waze-map-pacing: Waze %s (%d): the frame limiter %s as expected; it extends %s (starting at %d frames/s)"
          % (vn, vc, v["class"], ATL_LIMITER, v["rate"]))
    if not check_only:
        write_jar(dex, out_jar)
        print("waze-map-pacing: wrote %s" % out_jar)
    return 0


def main():
    args = sys.argv[1:]
    usage = __doc__.split("\n\n")[1]
    check = "--check" in args
    args = [a for a in args if a != "--check"]
    mode, atl = "full", None
    for opt in ("--mode", "--atl"):
        if opt in args:
            i = args.index(opt)
            if i + 1 >= len(args):
                print(usage, file=sys.stderr)
                return 2
            if opt == "--mode":
                mode = args[i + 1]
            else:
                atl = args[i + 1]
            args = args[:i] + args[i + 2:]
    if mode not in ("full", "auto") or (check and len(args) != 1) or (not check and len(args) != 2):
        print(usage, file=sys.stderr)
        return 2
    if mode == "auto":
        return patch_auto(args[0], None if check else args[1], check, atl)
    return patch_full(args[0], None if check else args[1], check)


if __name__ == "__main__":
    sys.exit(main())
