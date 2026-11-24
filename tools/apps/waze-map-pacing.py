#!/usr/bin/env python3
"""teslalinux: Waze's car map on the display's 60 frames/s schedule (verified Waze versions, and others where the code fits).

  waze-map-pacing.py APP_DIR OUT.jar [--atl API_IMPL_JAR]
                                            make the class overrides (APP_DIR: an installed app,
                                            /var/lib/teslalinux/apps/com.waze); exit 2: not found in this version
                                            (with --atl: and only if this ATL has ATLFrameRateLimiter)
  waze-map-pacing.py --check APP_DIR [--atl API_IMPL_JAR]
                                            report only: is the code as expected (verified, or may not work)
  waze-map-pacing.py --dump APP_DIR         print the classes read, as they are read, with the sha256
                                            of the map loop's (to verify a new version by hand)

Waze 5.27.5 holds its car map at 30 frames/s in two places, both meant to save a phone's battery and
both lifted to 60 only for 5 s after a touch on the phone's map view (or, in the car interface, a pan,
zoom or tap the car host hands its SurfaceCallback):
  - the map loop (com.waze.map.de.run(), on Waze's native looper) has the map engine prepare a frame
    (NativeCanvasRenderer.DrawNTV) and posts itself again 17 ms later after a touch, else 33 ms
    later. (On a phone the first touch unlocks 60 for good: the touch time is wall-clock time and
    the comparison uses uptime.)
  - the map renderer's frame limiter (com.waze.bq.a, the base of com.waze.map.opengl.m) skips a frame
    that comes less than 1000/30 ms after the last one; a touch sets it to 60 (opengl.m.a()) and,
    5 s later (opengl.l, through opengl.m.b()), back to 30. The car interface's render thread
    (com.waze.bq.e, "RenderThread") asks only this limiter before each frame.
So the car's map draws at 30 frames/s while the car drives, whatever the hardware does.

OUT.jar holds one dex with two classes, both made here:
  - the frame limiter class of the same name, written from the names below, extending ATL's
    android.atl.ATLFrameRateLimiter, with the limiter's methods (each calling ATL's for the same
    thing) and Waze's own starting rate. Before writing it, every reference to the class in every
    dex of the APK is checked to be to one of those methods, the class's own code to be the limiter
    it is known to be, and its subclasses to be the one known.
  - the map loop class of the same name, copied from the installed APK (its fields, methods and
    code, references renumbered for the new dex; no debug information), with one change: the 33 ms
    the loop waits while Waze's own touch boost is off is a static field the class sets when it is
    initialised, from ATL's policy: 17 ms (Waze's own boosted wait) under "display", Waze's 33
    under the others. The class must be exactly the one read for this version (the sha256 of its
    text below, `--dump` prints it), defined once, final, and referred to elsewhere only for
    members it has.

The one jar serves every choice of Settings > Waze interface > Map frame rate: ATL's class reads the
policy when the app starts (ATL_FRAME_RATE, which the launcher sets from the choice), and so does
the map loop class:
  Automatic (boost): Waze's rate and its own boosts, and frames at the display's rate for 5 s after
    ATL saw the map move (the car host's map surface touched, the car page's input, a new car
    template, a location fix of a moving car): 60 frames/s while the map moves, 30 while it is still.
    The map loop keeps Waze's own waits, as the automatic mode always did.
  Full (display): the display's rate all the time, and the map loop at Waze's own boosted 17 ms all
    the time (what the earlier whole-dex "full" mode did by changing the literal).
  Waze's own (app): exactly Waze's limiter and loop; also what the phone interface runs with.
The launcher loads the jar whichever is chosen, so the app's class loader context, and with it the
code ART compiled for the app, stays the same when the choice changes.

ATL puts OUT.jar in front of the app's own classes (ATL_APP_CLASS_OVERRIDES), on the same class
loader, so the classes in it are loaded from it (and the map loop stays in its package for the
package-private members it uses); the APK itself is never changed (Google APIs check its signing
block). Nothing of Waze is in this repository: the jar is made on the device from the installed APK.

A Waze version listed in VERIFIED_AUTO gets the jar when its code holds exactly what is expected
("verified"). Another version ("unverified": it may not work) gets it when the same code is there:
first under the names a verified version has; else found by its shape. The frame limiter is then the
one class whose fields, methods (protos and access) and code equal the verified limiter's once its
obfuscated class and member names are put in, extended by as many classes as in the verified version
and referred to only for its API; the map loop is the one class whose text (class_text) equals the
verified loop's with obfuscated names numbered in order (shape_text, its sha256 in "shape"). When no
single class fits, the tool exits 2 ("not found in this Waze version") and writes nothing.
"""
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
APK_INFO = os.path.join(HERE, "apk-info.py")

# versionCode -> the frame limiter as it was read in that version (by hand, from its dex): its
# class and fields, its methods with their code (the disassembly below, references resolved), the
# subclasses that extend it, and Waze's starting rate; and the map loop class ("loop").
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
        # the map loop: copied from the APK when its text (class_text) has this sha256; in METHOD the
        # one const-wide/16 of STILL ms (the wait without Waze's touch boost) becomes the policy's
        # wait, FAST ms (Waze's boosted wait, also there exactly once) under "display"
        "loop": {
            "class": "Lcom/waze/map/de;",
            "sha256": "281ac067b0c8c4b123938347c3e7bb0e37d8993a451a3852f637d6ff53651840",
            "method": ("run", "()V"),
            "still": 33,
            "fast": 17,
            # the same class with renamed names taken out (shape_text), for versions not listed here
            "shape": {"sha256": "c6637c32fad2162cc55fbbd9f5d5ef3bda2f4ceeb876bee6324fcbabd5733976",
                      "access": 0x10, "super": "Ljava/lang/Object;", "interfaces": ("Ljava/lang/Runnable;",),
                      "fields": 3, "methods": 2},
        },
    },
}
# the version names, for the settings page (services/api/api.py map_pacing_status)
VERIFIED_NAMES = {1030750: "5.27.5.0"}

ATL_LIMITER = "Landroid/atl/ATLFrameRateLimiter;"
OBJECT = "Ljava/lang/Object;"
# ATL's policy fields the map loop class reads (public static final int, set when ATL's class loads)
ATL_POLICY_FIELDS = (("POLICY", "I"), ("POLICY_DISPLAY", "I"))
# the map loop class's own static field, the wait it uses where Waze's has STILL
LOOP_FIELD = ("atl$stillWaitMs", "J", 0x1a)  # private static final


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

    def type_list(self, off):
        if not off:
            return []
        n = struct.unpack_from("<I", self.d, off)[0]
        return [self.type_name(struct.unpack_from("<H", self.d, off + 4 + 2 * k)[0]) for k in range(n)]

    def proto(self, idx):
        _shorty, ret, params_off = struct.unpack_from("<III", self.d, self.proto_ids_off + 12 * idx)
        return "(" + "".join(self.type_list(params_off)) + ")" + self.type_name(ret)

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

    def class_def(self, descriptor):
        """the class_def of DESCRIPTOR as a dict (None: not defined here)"""
        for i in range(self.class_defs_size):
            off = self.class_defs_off + 32 * i
            class_idx, access, super_idx, ifaces, src, ann, data_off, sv = struct.unpack_from("<8I", self.d, off)
            if self.type_name(class_idx) == descriptor:
                return {"access": access, "super": self.type_name(super_idx) if super_idx != 0xFFFFFFFF else None,
                        "interfaces": self.type_list(ifaces), "annotations_off": ann, "data": data_off,
                        "static_values_off": sv}
        return None

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

    def code_outs(self, code_off):
        return struct.unpack_from("<H", self.d, code_off + 4)[0]

    def method_refs(self):
        """(class, name, proto) of every method id"""
        return [self.method(i) for i in range(self.method_ids_size)]

    def field_refs(self):
        """(class, name, type) of every field id"""
        return [self.field(i) for i in range(self.field_ids_size)]


# ---------------------------------------------------------------------------- reading

def app_info(app_dir):
    base = os.path.join(app_dir, "base.apk")
    info = json.loads(subprocess.run([sys.executable, "-I", APK_INFO, base], check=True,
                                     capture_output=True, text=True).stdout)
    return base, info, int(info["versionCode"])


def s16(u):
    return struct.unpack("<h", struct.pack("<H", u))[0]


_BINOPS = ["add-int", "sub-int", "mul-int", "div-int", "rem-int", "and-int", "or-int", "xor-int",
           "shl-int", "shr-int", "ushr-int", "add-long", "sub-long", "mul-long", "div-long", "rem-long",
           "and-long", "or-long", "xor-long", "shl-long", "shr-long", "ushr-long", "add-float",
           "sub-float", "mul-float", "div-float", "rem-float", "add-double", "sub-double", "mul-double",
           "div-double", "rem-double"]
_UNOPS = ["neg-int", "not-int", "neg-long", "not-long", "neg-float", "neg-double", "int-to-long",
          "int-to-float", "int-to-double", "long-to-int", "long-to-float", "long-to-double",
          "float-to-int", "float-to-long", "float-to-double", "double-to-int", "double-to-long",
          "double-to-float", "int-to-byte", "int-to-char", "int-to-short"]
_IFZ = {0x38: "if-eqz", 0x39: "if-nez", 0x3a: "if-ltz", 0x3b: "if-gez", 0x3c: "if-gtz", 0x3d: "if-lez"}
_IF = {0x32: "if-eq", 0x33: "if-ne", 0x34: "if-lt", 0x35: "if-ge", 0x36: "if-gt", 0x37: "if-le"}
_CMP = {0x2d: "cmpl-float", 0x2e: "cmpg-float", 0x2f: "cmpl-double", 0x30: "cmpg-double", 0x31: "cmp-long"}
_FIELDS = ["iget", "iget-wide", "iget-object", "iget-boolean", "iget-byte", "iget-char", "iget-short",
           "iput", "iput-wide", "iput-object", "iput-boolean", "iput-byte", "iput-char", "iput-short"]
_SFIELDS = [f.replace("i", "s", 1) for f in _FIELDS]


def decode(dex, units):
    """The instructions of a method: (text with every reference resolved, width in code units, refs),
    refs being (unit within the instruction, kind, reference) for each index the instruction holds
    (kind "type", "field" or "method"; a field or method as (class, name, type or proto)). Only the
    opcodes listed here are known; any other refuses, so an unexpected method never passes (and no
    switch or array payload, whose data a copy would have to carry, ever does)."""
    out, pc = [], 0
    while pc < len(units):
        u = units[pc]
        op, aa = u & 0xFF, u >> 8
        a, b = aa & 0xF, aa >> 4
        refs = []
        if op == 0x0e:
            text, w = "return-void", 1
        elif op in (0x01, 0x04, 0x07):
            text, w = "%s v%d v%d" % ({0x01: "move", 0x04: "move-wide", 0x07: "move-object"}[op], a, b), 1
        elif op in (0x0f, 0x10, 0x11):
            text, w = "%s v%d" % ({0x0f: "return", 0x10: "return-wide", 0x11: "return-object"}[op], aa), 1
        elif op in (0x0a, 0x0b, 0x0c):
            text, w = "%s v%d" % ({0x0a: "move-result", 0x0b: "move-result-wide", 0x0c: "move-result-object"}[op], aa), 1
        elif op == 0x12:
            text, w = "const/4 v%d %d" % (a, b - 16 if b > 7 else b), 1
        elif op == 0x13:
            text, w = "const/16 v%d %d" % (aa, s16(units[pc + 1])), 2
        elif op == 0x16:
            text, w = "const-wide/16 v%d %d" % (aa, s16(units[pc + 1])), 2
        elif op in (0x1c, 0x1f, 0x22):
            t = dex.type_name(units[pc + 1])
            text, w = "%s v%d %s" % ({0x1c: "const-class", 0x1f: "check-cast", 0x22: "new-instance"}[op], aa, t), 2
            refs.append((1, "type", t))
        elif op == 0x28:
            text, w = "goto %+d" % (aa - 256 if aa > 127 else aa), 1
        elif op == 0x29:
            text, w = "goto/16 %+d" % s16(units[pc + 1]), 2
        elif op in _CMP:
            text, w = "%s v%d v%d v%d" % (_CMP[op], aa, units[pc + 1] & 0xFF, units[pc + 1] >> 8), 2
        elif op in _IF:
            text, w = "%s v%d v%d %+d" % (_IF[op], a, b, s16(units[pc + 1])), 2
        elif op in _IFZ:
            text, w = "%s v%d %+d" % (_IFZ[op], aa, s16(units[pc + 1])), 2
        elif 0x52 <= op <= 0x5f:
            f = dex.field(units[pc + 1])
            text, w = "%s v%d v%d %s->%s:%s" % ((_FIELDS[op - 0x52], a, b) + f), 2
            refs.append((1, "field", f))
        elif 0x60 <= op <= 0x6d:
            f = dex.field(units[pc + 1])
            text, w = "%s v%d %s->%s:%s" % ((_SFIELDS[op - 0x60], aa) + f), 2
            refs.append((1, "field", f))
        elif 0x6e <= op <= 0x72:
            kind = ["invoke-virtual", "invoke-super", "invoke-direct", "invoke-static", "invoke-interface"][op - 0x6e]
            count, g = aa >> 4, aa & 0xF
            regs = [units[pc + 2] & 0xF, (units[pc + 2] >> 4) & 0xF, (units[pc + 2] >> 8) & 0xF, units[pc + 2] >> 12, g][:count]
            m = dex.method(units[pc + 1])
            text, w = "%s {%s} %s->%s%s" % ((kind, ",".join("v%d" % r for r in regs)) + m), 3
            refs.append((1, "method", m))
        elif 0x7b <= op <= 0x8f:
            text, w = "%s v%d v%d" % (_UNOPS[op - 0x7b], a, b), 1
        elif 0x90 <= op <= 0xaf:
            text, w = "%s v%d v%d v%d" % (_BINOPS[op - 0x90], aa, units[pc + 1] & 0xFF, units[pc + 1] >> 8), 2
        elif 0xb0 <= op <= 0xcf:
            text, w = "%s/2addr v%d v%d" % (_BINOPS[op - 0xb0], a, b), 1
        else:
            raise Mismatch("opcode 0x%02x not expected" % op)
        if pc + w > len(units):
            raise Mismatch("an instruction runs past the method's end")
        out.append((text, w, refs))
        pc += w
    return out


def disassemble(dex, units):
    """The instructions of a method as text with every reference resolved (decode)"""
    return [text for text, _w, _r in decode(dex, units)]


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


def class_text(dex, cls):
    """The class as read, as text: its definition, its fields, and each method's header and code with
    every reference resolved. Its sha256 identifies the map loop of a verified version."""
    d = dex.class_def(cls)
    lines = ["class %s access 0x%x super %s interfaces %s annotations %s static-values %s"
             % (cls, d["access"], d["super"], ",".join(d["interfaces"]) or "-",
                "yes" if d["annotations_off"] else "no", "yes" if d["static_values_off"] else "no")]
    for n, t, a in dex.class_fields(d["data"]):
        lines.append("field %s:%s 0x%x" % (n, t, a))
    for n, p, a, code_off in dex.class_methods(d["data"]):
        if not code_off:
            lines.append("method %s%s 0x%x no code" % (n, p, a))
            continue
        regs, ins, tries, units = dex.code(code_off)
        lines.append("method %s%s 0x%x registers %d ins %d outs %d tries %d"
                     % (n, p, a, regs, ins, dex.code_outs(code_off), tries))
        lines += ["  " + t for t in disassemble(dex, units)]
    return "\n".join(lines) + "\n"


def check_loop(dexes, lv):
    """The map loop class is the one read for this version (its text's sha256), defined in one dex,
    final (nothing extends it), with no annotations, static values or exception handlers to carry;
    every reference to it elsewhere names a member it has; its METHOD holds STILL and FAST once each.
    An entry without "sha256" (a loop found by its shape, find_loop) is held to the sha256 of its
    shape_text instead. Returns (that dex, the instruction index in METHOD whose wait the copy changes)."""
    cls = lv["class"]
    defining = [(name, dex) for name, dex in dexes if dex.class_def(cls) is not None]
    if len(defining) != 1:
        raise Mismatch("%s is defined in %d dexes, expected one" % (cls, len(defining)))
    name, dex = defining[0]
    if lv.get("sha256"):
        text = class_text(dex, cls)
        digest = hashlib.sha256(text.encode()).hexdigest()
        if digest != lv["sha256"]:
            raise Mismatch("%s in %s is not the map loop read for this version (sha256 %s)" % (cls, name, digest))
    else:
        digest = hashlib.sha256(shape_text(dex, cls).encode()).hexdigest()
        if digest != lv["shape"]["sha256"]:
            raise Mismatch("%s in %s is not of the map loop's shape (sha256 %s)" % (cls, name, digest))
    d = dex.class_def(cls)
    # what the sha256 already covers, stated: the copy relies on each
    expect(cls + " final", bool(d["access"] & 0x10), True)
    expect(cls + " annotations", d["annotations_off"], 0)
    expect(cls + " static values", d["static_values_off"], 0)
    fields = dex.class_fields(d["data"])
    methods = dex.class_methods(d["data"])
    if any(n == LOOP_FIELD[0] for n, _t, _a in fields) or any(n == "<clinit>" for n, _p, _a, _c in methods):
        raise Mismatch("%s already has %s or a class initialiser" % (cls, LOOP_FIELD[0]))
    for n, p, _a, code_off in methods:
        if not code_off or dex.code(code_off)[2]:
            raise Mismatch("%s.%s%s is abstract, native or has exception handlers" % (cls, n, p))
    code = [c for n, p, _a, c in methods if (n, p) == lv["method"]]
    if len(code) != 1:
        raise Mismatch("%s has no %s%s" % (cls, lv["method"][0], lv["method"][1]))
    insns = decode(dex, dex.code(code[0])[3])
    waits = {}
    for i, (t, _w, _r) in enumerate(insns):
        m = re.match(r"const-wide/16 v\d+ (-?\d+)$", t)
        if m and int(m.group(1)) in (lv["still"], lv["fast"]):
            waits.setdefault(int(m.group(1)), []).append(i)
    if len(waits.get(lv["still"], [])) != 1 or len(waits.get(lv["fast"], [])) != 1:
        raise Mismatch("%s.%s: its waits %s, expected %d ms and %d ms once each"
                       % (cls, lv["method"][0], waits, lv["still"], lv["fast"]))
    own_methods = set((n, p) for n, p, _a, _c in methods)
    own_fields = set((n, t) for n, t, _a in fields)
    for dname, other in dexes:
        for c, n, p in other.method_refs():
            if c == cls and (n, p) not in own_methods and (n, p) not in (
                    ("equals", "(Ljava/lang/Object;)Z"), ("hashCode", "()I"),
                    ("toString", "()Ljava/lang/String;"), ("getClass", "()Ljava/lang/Class;")):
                raise Mismatch("%s refers to %s->%s%s, not a method it has" % (dname, c, n, p))
        for c, n, t in other.field_refs():
            if c == cls and (n, t) not in own_fields:
                raise Mismatch("%s refers to %s->%s:%s, not a field it has" % (dname, c, n, t))
        for d2 in other.class_defs():
            if d2[2] == cls:
                raise Mismatch("%s extends %s" % (d2[0], cls))
    return dex, waits[lv["still"]][0]


def check_atl(api_impl, v):
    """ATL's api-impl.jar has ATLFrameRateLimiter with what the generated classes use (an older ATL
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
            if "loop" in v:
                statics = set((n, t) for n, t, a in dex.class_fields(data_off) if a & 0x9 == 0x9)  # public static
                missing = [n for n, t in ATL_POLICY_FIELDS if (n, t) not in statics]
                if missing:
                    raise Mismatch("%s in %s lacks the policy fields %s" % (ATL_LIMITER, api_impl, ", ".join(missing)))
            return
    raise Mismatch("ATL's %s has no %s (an ATL older than the automatic map frame rate)" % (api_impl, ATL_LIMITER))


def read_dexes(base):
    with zipfile.ZipFile(base) as z:
        return [(name, Dex(z.read(name))) for name in sorted(n for n in z.namelist()
                                                            if n.startswith("classes") and n.endswith(".dex"))]


def check_auto(app_dir):
    """(versionCode, versionName, the version's VERIFIED_AUTO entry, the APK's dexes) when the
    installed app is a verified version whose limiter and map loop are as expected; raises Mismatch
    otherwise"""
    base, info, vc = app_info(app_dir)
    if info.get("package") != "com.waze" or vc not in VERIFIED_AUTO:
        raise Mismatch("%s versionCode %s is not a verified Waze version (%s)"
                       % (info.get("package"), vc, ", ".join(str(k) for k in VERIFIED_AUTO)))
    v = VERIFIED_AUTO[vc]
    dexes = read_dexes(base)
    defining = check_references(dexes, v)
    check_limiter(dict(dexes)[defining], v)
    if "loop" in v:
        check_loop(dexes, v["loop"])
    return vc, info.get("versionName"), v, dexes


# ---------------------------------------------------------------------------- other Waze versions

# classes whose names R8 leaves alone (the platform's, the desugared library's, Kotlin's own)
PLATFORM = ("Ljava/", "Ljavax/", "Landroid/", "Ldalvik/", "Lj$/", "Lkotlin/")
_DESC = re.compile(r"L[^;\s{}(),:]+;")
_REF = re.compile(r"(L[^;\s{}(),:]+;)(?:->([^:(\s]+)(:\S+|\(\S+))?")


def obfuscated_class(desc):
    """an R8-renamed class: outside PLATFORM, with a package or name of one or two lowercase letters
    (Lcom/waze/bq/a;, Lcom/waze/map/de;, Lkotlinx/coroutines/b/bj;)"""
    return not desc.startswith(PLATFORM) and any(re.fullmatch(r"[a-z]{1,2}", s) for s in desc[1:-1].split("/")[1:])


def shape_text(dex, cls):
    """class_text with the names a new build may change taken out: CLS is THIS, its fields and methods
    are numbered in the order of their type (or proto) and access, renamed classes and the renamed
    members of other app classes are numbered as they first appear. Everything else (access flags,
    the platform's and the app's kept names, every instruction and constant) stays as it is."""
    d = dex.class_def(cls)
    fields = dex.class_fields(d["data"])
    methods = dex.class_methods(d["data"])

    def rough(t):  # the sort key: renamed classes all alike, so the order does not depend on their names
        return _DESC.sub(lambda m: "THIS" if m.group(0) == cls else ("?" if obfuscated_class(m.group(0)) else m.group(0)), t)

    own = {}
    for i, (n, t, _a) in enumerate(sorted(fields, key=lambda f: (rough(f[1]), f[2], f[0]))):
        own[(n, ":" + t)] = "f%d" % i
    for i, (n, p, _a, _c) in enumerate(sorted(methods, key=lambda m: (rough(m[1]), m[2], m[0]))):
        own[(n, p)] = n if n in ("<init>", "<clinit>") else "m%d" % i
    classes, members = {cls: "THIS"}, {}

    def cname(desc):
        if desc not in classes:
            classes[desc] = "C%d" % len(classes) if obfuscated_class(desc) else desc
        return classes[desc]

    def norm(text):
        def ref(m):
            desc, name, sig = m.group(1), m.group(2), m.group(3)
            out = cname(desc)
            if name is None:
                return out
            if desc == cls:
                member = own.get((name, sig), name)
            elif name in ("<init>", "<clinit>") or desc.startswith(PLATFORM) or not (
                    obfuscated_class(desc) or re.fullmatch(r"[a-zA-Z]{1,2}", name)):
                member = name
            else:
                member = members.setdefault((desc, name, sig), "x%d" % len(members))
            return "%s->%s%s" % (out, member, _DESC.sub(lambda s: cname(s.group(0)), sig))
        return _REF.sub(ref, text)

    lines = ["class THIS access 0x%x super %s interfaces %s annotations %s static-values %s"
             % (d["access"], norm(d["super"]), ",".join(norm(i) for i in d["interfaces"]) or "-",
                "yes" if d["annotations_off"] else "no", "yes" if d["static_values_off"] else "no")]
    for n, t, a in sorted(fields, key=lambda f: own[(f[0], ":" + f[1])]):
        lines.append("field %s:%s 0x%x" % (own[(n, ":" + t)], norm(t), a))
    for n, p, a, code_off in sorted(methods, key=lambda m: own[(m[0], m[1])]):
        if not code_off:
            lines.append("method %s%s 0x%x no code" % (own[(n, p)], norm(p), a))
            continue
        regs, ins, tries, units = dex.code(code_off)
        lines.append("method %s%s 0x%x registers %d ins %d outs %d tries %d"
                     % (own[(n, p)], norm(p), a, regs, ins, dex.code_outs(code_off), tries))
        lines += ["  " + norm(t) for t in disassemble(dex, units)]
    return "\n".join(lines) + "\n"


def renamed_limiter(v, cls, fields, methods):
    """V (a VERIFIED_AUTO entry) for a limiter CLS with these FIELDS [(name, type, access)] and METHODS
    [(name, proto, access)]: each of V's members matched to the one of the same type (or proto) and
    access, and every name in V's code replaced; None when the members do not pair up one to one"""
    def pair(want, have):
        out = {}
        for n, sig, a in want:
            same = [h for h in have if h[1:] == (sig, a)]
            if len(same) != 1:
                return None
            out[(n, sig)] = same[0][0]
        return out if len(set(out.values())) == len(out) == len(have) else None
    fmap = pair(v["fields"], fields)
    mmap = pair(list(v["api"]) + list(v["private"]), methods)
    if fmap is None or mmap is None:
        return None
    old = v["class"]

    def ref(m):
        if m.group(1) != old:
            return m.group(0)
        if m.group(2) is None:
            return cls
        name, sig = m.group(2), m.group(3)
        new = fmap.get((name, sig[1:])) if sig[0] == ":" else mmap.get((name, sig))
        if new is None:
            raise Mismatch("%s->%s%s is not a member of the limiter" % (old, name, sig))
        return "%s->%s%s" % (cls, new, sig.replace(old, cls))
    try:
        code = {(mmap[(n, p)], p): (regs, ins, [_REF.sub(ref, t) for t in text])
                for (n, p), (regs, ins, text) in v["code"].items()}
    except Mismatch:
        return None
    return dict(v, **{
        "class": cls,
        "fields": tuple((fmap[(n, t)], t, a) for n, t, a in v["fields"]),
        "api": {(mmap[(n, p)], p, a): target for (n, p, a), target in v["api"].items()},
        "private": tuple((mmap[(n, p)], p, a) for n, p, a in v["private"]),
        "code": code})


def find_limiter(dexes, template):
    """TEMPLATE with the limiter of these DEXES: under the template's own names when they fit (the
    exact checks), else the one class of the limiter's shape (renamed_limiter, then the exact checks
    on its names) and the classes that extend it. Raises Mismatch."""
    try:
        defining = check_references(dexes, template)
        check_limiter(dict(dexes)[defining], template)
        return template
    except Mismatch:
        pass
    want_fields = sorted((t, a) for _n, t, a in template["fields"])
    want_methods = sorted((p, a) for _n, p, a in list(template["api"]) + list(template["private"]))
    found = []
    for _name, dex in dexes:
        for desc, access, sup, ifaces, data_off in dex.class_defs():
            if access != 0x1 or sup != OBJECT or ifaces or not data_off:
                continue
            fields = dex.class_fields(data_off)
            if sorted((t, a) for _n, t, a in fields) != want_fields:
                continue
            methods = [(n, p, a) for n, p, a, _c in dex.class_methods(data_off)]
            if sorted((p, a) for _n, p, a in methods) != want_methods:
                continue
            v = renamed_limiter(template, desc, fields, methods)
            if v is None:
                continue
            try:
                check_limiter(dex, v)
            except Mismatch:
                continue
            found.append(v)
    if len(found) != 1:
        raise Mismatch("%d classes of the frame limiter's shape, expected one" % len(found))
    v = found[0]
    subclasses = sorted(set(d[0] for _n, dex in dexes for d in dex.class_defs() if d[2] == v["class"]))
    if len(subclasses) != len(template["subclasses"]):
        raise Mismatch("%s is extended by %d classes, expected %d" % (v["class"], len(subclasses), len(template["subclasses"])))
    v["subclasses"] = tuple(subclasses)
    check_references(dexes, v)
    return v


def find_loop(dexes, lv):
    """LV (a map loop entry) for these DEXES: under its own name when its text is the one read
    (check_loop), else the one class whose shape_text has the sha256 of the verified loop's. Raises
    Mismatch."""
    try:
        check_loop(dexes, lv)
        return lv
    except Mismatch:
        pass
    shape = lv["shape"]
    found = []
    for _name, dex in dexes:
        for desc, access, sup, ifaces, data_off in dex.class_defs():
            if (access != shape["access"] or sup != shape["super"] or not data_off
                    or tuple(dex.type_list(ifaces)) != tuple(shape["interfaces"])):
                continue
            if (len(dex.class_fields(data_off)), len(dex.class_methods(data_off))) != (shape["fields"], shape["methods"]):
                continue
            try:
                if hashlib.sha256(shape_text(dex, desc).encode()).hexdigest() == shape["sha256"]:
                    found.append(desc)
            except Mismatch:  # code the disassembler does not know: not the loop
                continue
    if len(found) != 1:
        raise Mismatch("%d classes of the map loop's shape, expected one" % len(found))
    out = dict(lv, **{"class": found[0], "sha256": None})
    check_loop(dexes, out)
    return out


def check_any(app_dir):
    """(versionCode, versionName, entry, the APK's dexes, state): "verified" for a VERIFIED_AUTO
    version (check_auto); for any other Waze "unverified" when its limiter and map loop are found as
    a verified version has them (find_limiter, find_loop; the newest verified version first). Raises
    Mismatch when they are not."""
    base, info, vc = app_info(app_dir)
    if info.get("package") != "com.waze":
        raise Mismatch("%s is not Waze" % info.get("package"))
    if vc in VERIFIED_AUTO:
        vc, vn, v, dexes = check_auto(app_dir)
        return vc, vn, v, dexes, "verified"
    dexes = read_dexes(base)
    problems = []
    for known in sorted(VERIFIED_AUTO, reverse=True):
        try:
            v = find_limiter(dexes, VERIFIED_AUTO[known])
            if "loop" in v:
                v = dict(v, loop=find_loop(dexes, v["loop"]))
            return vc, info.get("versionName"), v, dexes, "unverified"
        except Mismatch as e:
            problems.append("as in %d: %s" % (known, e))
    raise Mismatch("versionCode %d: the map's frame limiter was not found in this Waze version (%s)"
                   % (vc, "; ".join(problems)))


# ---------------------------------------------------------------------------- writing

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
        j = i
        while params[j] == "[":
            j += 1
        if params[j] == "L":
            j = params.index(";", j)
        out.append(params[i:j + 1])
        i = j + 1
    return out


def shorty(proto):
    ret = proto.split(")")[1]
    return "".join("L" if t[0] in "L[" else t for t in [ret] + param_types(proto))


def is_direct(name, access):
    return name in ("<init>", "<clinit>") or access & 0x1000a  # static, private or a constructor


def write_dex(classes):
    """A dex holding CLASSES, each a dict: "class" (descriptor), "access", "super", "interfaces"
    (descriptors), "fields" [(name, type, access)], "methods" [(name, proto, access, code)], code
    (registers, ins, outs, insns), insns a list of 16-bit code units where a reference index is
    ("type", descriptor), ("string", text), ("field", (class, name, type)) or ("method", (class, name, proto)). No
    debug information, annotations or static values."""
    types, protos, fields, methods, names = set(), set(), set(), set(), set()

    def add_proto(p):
        protos.add(p)
        types.update(param_types(p))
        types.add(p.split(")")[1])

    def add_field(f):
        fields.add(f)
        types.update((f[0], f[2]))
        names.add(f[1])

    def add_method(m):
        methods.add(m)
        types.add(m[0])
        names.add(m[1])
        add_proto(m[2])

    for c in classes:
        types.add(c["class"])
        if c["super"]:
            types.add(c["super"])
        types.update(c["interfaces"])
        for n, t, _a in c["fields"]:
            add_field((c["class"], n, t))
        for n, p, _a, code in c["methods"]:
            add_method((c["class"], n, p))
            for u in code[3]:
                if isinstance(u, tuple):
                    {"type": types.add, "string": names.add, "field": add_field, "method": add_method}[u[0]](u[1])
    strings = sorted(types | names | set(shorty(p) for p in protos))
    for s in strings:
        s.encode("ascii")  # ASCII: Python's order is the dex's (UTF-16 code unit) order
    sidx = {s: i for i, s in enumerate(strings)}
    types = sorted(types, key=lambda t: sidx[t])
    tidx = {t: i for i, t in enumerate(types)}
    protos = sorted(protos, key=lambda p: (tidx[p.split(")")[1]], [tidx[t] for t in param_types(p)]))
    pidx = {p: i for i, p in enumerate(protos)}
    fields = sorted(fields, key=lambda f: (tidx[f[0]], sidx[f[1]], tidx[f[2]]))
    fidx = {f: i for i, f in enumerate(fields)}
    methods = sorted(methods, key=lambda m: (tidx[m[0]], sidx[m[1]], pidx[m[2]]))
    midx = {m: i for i, m in enumerate(methods)}
    index = {"type": tidx, "string": sidx, "field": fidx, "method": midx}
    defined = set(c["class"] for c in classes)
    order, placed = [], set()
    while len(order) < len(classes):  # a class defined here after its superclass and interfaces
        ready = [c for c in classes if c["class"] not in placed
                 and all(t in placed or t not in defined for t in [c["super"]] + list(c["interfaces"]))]
        if not ready:
            raise ValueError("a cycle in the classes' hierarchy")
        c = min(ready, key=lambda c: tidx[c["class"]])
        order.append(c)
        placed.add(c["class"])

    header_size = 0x70
    string_ids_off = header_size
    type_ids_off = string_ids_off + 4 * len(strings)
    proto_ids_off = type_ids_off + 4 * len(types)
    field_ids_off = proto_ids_off + 12 * len(protos)
    method_ids_off = field_ids_off + 8 * len(fields)
    class_defs_off = method_ids_off + 8 * len(methods)
    data_off = class_defs_off + 32 * len(order)
    data = bytearray()
    sections = []  # (map type, count, offset)

    def here():
        return data_off + len(data)

    type_list_off = {}
    lists = [tuple(param_types(p)) for p in protos] + [tuple(c["interfaces"]) for c in order]
    first = None
    for tl in lists:
        if not tl or tl in type_list_off:
            continue
        align(data)
        type_list_off[tl] = here()
        first = first if first is not None else here()
        data += struct.pack("<I", len(tl)) + b"".join(struct.pack("<H", tidx[t]) for t in tl)
    if type_list_off:
        sections.append((0x1001, len(type_list_off), first))

    align(data)
    code_start = here()
    codes = {}
    for c in order:
        for n, p, _a, (regs, ins, outs, insns) in c["methods"]:
            align(data)
            codes[(c["class"], n, p)] = here()
            units = []
            for u in insns:
                if isinstance(u, tuple):
                    i = index[u[0]][u[1]]
                    if i > 0xFFFF:
                        raise ValueError("index %d does not fit an instruction" % i)
                    units.append(i)
                else:
                    units.append(u)
            data.extend(struct.pack("<HHHHII", regs, ins, outs, 0, 0, len(units)))
            data.extend(b"".join(struct.pack("<H", u) for u in units))
    sections.append((0x2001, len(codes), code_start))

    string_data_off = []
    for s in strings:
        string_data_off.append(here())
        data += write_uleb(len(s)) + s.encode("ascii") + b"\0"
    sections.append((0x2002, len(strings), string_data_off[0]))

    class_data_off = {}
    for c in order:
        class_data_off[c["class"]] = here()
        sf = sorted((fidx[(c["class"], n, t)], a) for n, t, a in c["fields"] if a & 0x8)
        inst = sorted((fidx[(c["class"], n, t)], a) for n, t, a in c["fields"] if not a & 0x8)
        direct = sorted((midx[(c["class"], n, p)], a) for n, p, a, _c in c["methods"] if is_direct(n, a))
        virtual = sorted((midx[(c["class"], n, p)], a) for n, p, a, _c in c["methods"] if not is_direct(n, a))
        data += b"".join(write_uleb(len(g)) for g in (sf, inst, direct, virtual))
        for group in (sf, inst):
            prev = None
            for f, access in group:
                data += write_uleb(f if prev is None else f - prev) + write_uleb(access)
                prev = f
        for group in (direct, virtual):
            prev = None
            for m, access in group:
                data += write_uleb(m if prev is None else m - prev) + write_uleb(access) + write_uleb(codes[methods[m]])
                prev = m
    sections.append((0x2000, len(order), class_data_off[order[0]["class"]]))

    align(data)
    map_off = here()
    items = [(0x0000, 1, 0), (0x0001, len(strings), string_ids_off), (0x0002, len(types), type_ids_off),
             (0x0003, len(protos), proto_ids_off)]
    if fields:
        items.append((0x0004, len(fields), field_ids_off))
    items += [(0x0005, len(methods), method_ids_off), (0x0006, len(order), class_defs_off)] + sections + [(0x1000, 1, map_off)]
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
                     len(protos), proto_ids_off, len(fields), field_ids_off if fields else 0,
                     len(methods), method_ids_off, len(order), class_defs_off, len(data), data_off)
    for i, off in enumerate(string_data_off):
        struct.pack_into("<I", out, string_ids_off + 4 * i, off)
    for i, t in enumerate(types):
        struct.pack_into("<I", out, type_ids_off + 4 * i, sidx[t])
    for i, p in enumerate(protos):
        struct.pack_into("<III", out, proto_ids_off + 12 * i, sidx[shorty(p)], tidx[p.split(")")[1]],
                         type_list_off.get(tuple(param_types(p)), 0))
    for i, (c, n, t) in enumerate(fields):
        struct.pack_into("<HHI", out, field_ids_off + 8 * i, tidx[c], tidx[t], sidx[n])
    for i, (c, n, p) in enumerate(methods):
        struct.pack_into("<HHI", out, method_ids_off + 8 * i, tidx[c], pidx[p], sidx[n])
    for i, c in enumerate(order):
        struct.pack_into("<8I", out, class_defs_off + 32 * i, tidx[c["class"]], c["access"],
                         tidx[c["super"]] if c["super"] else 0xFFFFFFFF,
                         type_list_off.get(tuple(c["interfaces"]), 0), 0xFFFFFFFF, 0,
                         class_data_off[c["class"]], 0)
    out += data
    out[12:32] = hashlib.sha1(bytes(out[32:])).digest()
    struct.pack_into("<I", out, 8, zlib.adler32(bytes(out[12:])) & 0xFFFFFFFF)
    return bytes(out)


def limiter_class(v):
    """v["class"], public, extending ATL_LIMITER: a constructor that hands ATL's Waze's starting
    rate, and each of the limiter's methods calling ATL's for the same thing (write_dex's form)"""
    methods = []
    for (n, p, a), (an, ap) in sorted(v["api"].items()):
        target = ("method", (ATL_LIMITER, an, ap))
        if n == "<init>":
            # const/16 v0, rate; invoke-direct {v1, v0} ATL-><init>(I)V; return-void
            rate = v["rate"]
            if not 0 < rate < 0x8000:
                raise ValueError("rate %r" % rate)
            code = (2, 1, 2, [0x0013, rate, 0x2070, target, 0x0001, 0x000e])
        else:
            # the parameters are the last registers (this, then the arguments; a long takes two)
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
            code = (max(width, 2 if ret in ("J", "D") else 1), width, width, insns)
        methods.append((n, p, a, code))
    return {"class": v["class"], "access": 0x1, "super": ATL_LIMITER, "interfaces": [], "fields": [],
            "methods": methods}


def limiter_class_dex(v):
    """A dex with the limiter class alone"""
    return write_dex([limiter_class(v)])


def copy_units(dex, units, replace=None):
    """A method's code in write_dex's form: its units with each reference index as its reference;
    REPLACE {instruction index: units} puts other units of the same width in an instruction's place"""
    out, pc = [], 0
    for i, (_t, w, refs) in enumerate(decode(dex, units)):
        if replace and i in replace:
            if len(replace[i]) != w:
                raise ValueError("a replacement of another width")
            out += replace[i]
        else:
            insn = list(units[pc:pc + w])
            for at, kind, ref in refs:
                insn[at] = (kind, ref)
            out += insn
        pc += w
    return out


def loop_class(dex, lv, still_at):
    """The map loop class copied from DEX, the wait at instruction STILL_AT of its METHOD read from
    LOOP_FIELD, which its class initialiser sets from ATL's policy (write_dex's form)"""
    cls = lv["class"]
    d = dex.class_def(cls)
    field = ("field", (cls, LOOP_FIELD[0], LOOP_FIELD[1]))
    methods = []
    for n, p, a, code_off in dex.class_methods(d["data"]):
        regs, ins, _tries, units = dex.code(code_off)
        replace = None
        if (n, p) == lv["method"]:
            insn = decode(dex, units)
            pc = sum(w for _t, w, _r in insn[:still_at])
            reg = units[pc] >> 8
            # const-wide/16 vAA, STILL -> sget-wide vAA, LOOP_FIELD (both two units)
            replace = {still_at: [0x0061 | reg << 8, field]}
        methods.append((n, p, a, (regs, ins, dex.code_outs(code_off), copy_units(dex, units, replace))))
    for value in (lv["still"], lv["fast"]):
        if not 0 < value < 0x8000:
            raise ValueError("wait %r" % value)
    # <clinit>: v0 = POLICY; v1 = POLICY_DISPLAY; v2:v3 = STILL; if v0 == v1 then v2:v3 = FAST; the field = v2:v3
    clinit = [0x0060, ("field", (ATL_LIMITER,) + ATL_POLICY_FIELDS[0]),
              0x0160, ("field", (ATL_LIMITER,) + ATL_POLICY_FIELDS[1]),
              0x0216, lv["still"],
              0x1033, 4,                  # if-ne v0, v1, +4 (over the next instruction)
              0x0216, lv["fast"],
              0x0268, field,              # sput-wide v2, the field
              0x000e]
    methods.append(("<clinit>", "()V", 0x10008, (4, 0, 0, clinit)))
    return {"class": cls, "access": d["access"], "super": d["super"], "interfaces": d["interfaces"],
            "fields": dex.class_fields(d["data"]) + [LOOP_FIELD], "methods": methods}


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


def override_dex(v, dexes):
    """the jar's dex: the limiter class, and the map loop class copied from DEXES (checked)"""
    classes = [limiter_class(v)]
    if "loop" in v:
        dex, still_at = check_loop(dexes, v["loop"])
        classes.append(loop_class(dex, v["loop"], still_at))
    return write_dex(classes)


def patch_auto(app_dir, out_jar, check_only, atl):
    try:
        vc, vn, v, dexes, state = check_any(app_dir)
        if atl:
            check_atl(atl, v)
        dex = override_dex(v, dexes)
    except Mismatch as e:
        print("waze-map-pacing: %s: nothing changed" % e, file=sys.stderr)
        return 2
    except (OSError, zipfile.BadZipFile, ValueError) as e:
        print("waze-map-pacing: %s: nothing changed" % e, file=sys.stderr)
        return 1
    print("waze-map-pacing: Waze %s (%d, %s): the frame limiter %s as expected; it extends %s (starting at %d frames/s)"
          % (vn, vc, state if state == "verified" else "not verified, may not work", v["class"], ATL_LIMITER, v["rate"]))
    if "loop" in v:
        lv = v["loop"]
        print("waze-map-pacing: the map loop %s as expected; its %d ms wait is %d ms under ATL_FRAME_RATE=display"
              % (lv["class"], lv["still"], lv["fast"]))
    if not check_only:
        write_jar(dex, out_jar)
        print("waze-map-pacing: wrote %s" % out_jar)
    return 0


def dump(app_dir):
    base, info, vc = app_info(app_dir)
    v = VERIFIED_AUTO.get(vc)
    classes = [v["class"], v["loop"]["class"]] if v else [LIMITER_5_27_5, VERIFIED_AUTO[1030750]["loop"]["class"]]
    print("# %s %s (%s)" % (info.get("package"), info.get("versionName"), vc))
    for name, dex in read_dexes(base):
        for cls in classes:
            if dex.class_def(cls) is None:
                continue
            try:
                text = class_text(dex, cls)
            except Mismatch as e:
                print("# %s %s: %s" % (name, cls, e))
                continue
            print("# %s, sha256 %s" % (name, hashlib.sha256(text.encode()).hexdigest()))
            sys.stdout.write(text)
    return 0


def main():
    args = sys.argv[1:]
    usage = __doc__.split("\n\n")[1]
    if len(args) == 2 and args[0] == "--dump":
        return dump(args[1])
    check = "--check" in args
    args = [a for a in args if a != "--check"]
    atl = None
    if "--atl" in args:
        i = args.index("--atl")
        if i + 1 >= len(args):
            print(usage, file=sys.stderr)
            return 2
        atl = args[i + 1]
        args = args[:i] + args[i + 2:]
    if (check and len(args) != 1) or (not check and len(args) != 2):
        print(usage, file=sys.stderr)
        return 2
    return patch_auto(args[0], None if check else args[1], check, atl)


if __name__ == "__main__":
    sys.exit(main())
