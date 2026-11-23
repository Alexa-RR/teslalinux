#!/usr/bin/env python3
"""teslalinux: the car page draws Waze's speedometer (one verified Waze version).

  waze-speedometer.py jar APP_DIR OUT.jar [--atl API_IMPL_JAR]
                                              make the class override (APP_DIR: an installed app,
                                              /var/lib/teslalinux/apps/com.waze); exit 2: not this version
                                              (--atl: and only if this ATL has ATLCarSpeedometer)
  waze-speedometer.py check APP_DIR [--atl API_IMPL_JAR]
                                              report only: is this version known and the code as expected
  waze-speedometer.py hide DATA_DIR           turn Waze's own map speedometer off (DATA_DIR: ATL's data
                                              directory for the app, /var/lib/tesla/android/waze), keeping
                                              what it was
  waze-speedometer.py restore DATA_DIR        give Waze back its own speedometer setting
  waze-speedometer.py settings DATA_DIR       print the speedometer settings Waze uses (JSON)

In Waze's car interface the speedometer (the current speed in a white circle, the road's speed limit in
a ringed sign beside it, bottom right) is drawn by Waze's native map (its Godot scene) into the map
surface, so the car page cannot restyle, place or leave it out. Two pieces move it to the page:

1. The readings. Waze's native code calls LocationSensorListenerBase.updateSpeedometerJNI(speed, units,
   speedLimit, speedKph) on every position (whatever its speedometer setting): the speed and the road's
   speed limit in the unit Waze shows (-1: none), the unit's name, the speed in km/h. The Java side
   (LocationSensorListener.updateSpeedometer) wraps them in a small value class (Waze 5.27.5:
   com.waze.bz.o, its SpeedometerModel) for its own speedometer state. That class is the only thing
   this replaces: OUT.jar holds one class of the same name, written here, which extends ATL's
   android.atl.carhost.ATLCarSpeedometer and keeps the class's methods (the getters, equals, hashCode,
   toString, through ATL's class). ATL's class reports every reading to its car-app host, which sends
   it to the car page ({"type":"speedometer"} on /sockets/carapp, docs/UPSTREAM-NOTES.md). ATL puts
   OUT.jar in front of the app's classes (ATL_APP_CLASS_OVERRIDES). Nothing of Waze is in the jar or
   in this repository: the class is generated from the names below, after the installed APK's own class
   and its one construction site are checked to be exactly what they were when the names were read.
2. Waze's own drawing. Its "Show speedometer" setting (the config row user/Map/"Show speedometer V4"
   in waze_config.db, CONFIG_VALUE_MAP_SHOW_SPEEDOMETER on the Java side) gates both the native map's
   speedometer and the Java speedometer state; `hide` sets it to 0 before Waze starts and keeps the
   earlier value (DATA_DIR/teslalinux-speedometer.json), `restore` puts that back (the phone
   interface, or a version this tool does not know). Waze's speed limit sounds follow the same state;
   they are off by default (CONFIG_VALUE_MAP_SPEEDOMETER_SPEED_LIMIT_SOUNDS) and stay off with it.

`settings` reads the speedometer configs Waze mirrors in its shared preferences
(shared_prefs/com.waze.config.offline.xml): the sign style ("us": the US rectangle, else the round
sign) and the over-the-limit offset, which the car page uses as Waze does
(services/api/api.py speedometer_status).

Only a Waze version listed in VERIFIED, whose code is exactly as expected, gets the jar; anything else
exits 2 and writes nothing.
"""
import hashlib
import json
import os
import sqlite3
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
APK_INFO = os.path.join(HERE, "apk-info.py")

ATL_BASE = "Landroid/atl/carhost/ATLCarSpeedometer;"
OBJECT = "Ljava/lang/Object;"
STRING = "Ljava/lang/String;"
INIT_PROTO = "(ILjava/lang/String;ID)V"
# ATL_BASE's getters, by what they return (the generated class's getters call them)
BASE_GETTERS = {"speed": ("getSpeed", "I"), "units": ("getUnits", STRING),
                "speedLimit": ("getSpeedLimit", "I"), "speedKph": ("getSpeedKph", "D")}

# versionCode -> what was read in that version (by hand, from its dex): the reading class, its fields
# in constructor order (speed, units, speedLimit, speedKph), its getters (name -> the value it returns),
# and the one method that constructs it. The code checks below pin the rest.
VERIFIED = {
    1030750: {  # Waze 5.27.5.0
        "class": "Lcom/waze/bz/o;",
        "fields": (("a", "I"), ("b", STRING), ("c", "I"), ("d", "D")),
        "getters": (("a", "speedKph"), ("b", "speedLimit"), ("c", "speed"), ("d", "units")),
        # Kotlin's Intrinsics.checkNotNullParameter as R8 renamed it
        "null_check": ("Lg/h/b/x;", "g", "(Ljava/lang/Object;Ljava/lang/String;)V"),
        "toString": ("SpeedometerModel(speed=", ", units=", ", maxSpeedRoad=", ", normalizedSpeedKph="),
        "caller": ("Lcom/waze/location/LocationSensorListener;", "updateSpeedometer", INIT_PROTO),
        "caller_flow": "speedometerStateFlow",
    },
}
# the version names of VERIFIED, for the settings page (services/api/api.py speedometer_status)
VERIFIED_NAMES = {1030750: "5.27.5.0"}

# Waze's own speedometer setting in waze_config.db (table config_values, primary key domain, category, name)
SHOW_ROW = ("user", "Map", "Show speedometer V4")
SAVED_NAME = "teslalinux-speedometer.json"
# ATL's data directory for the app: ANDROID_APP_DATA_DIR/<base APK name>_ (ATL's main.c)
APP_DATA_SUBDIR = "base.apk_"


# ---------------------------------------------------------------------------- reading a dex

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
        return self.d[off:end].decode("utf-8", "replace")  # MUTF-8; plain ASCII here

    def type_name(self, idx):
        return self.string(struct.unpack_from("<I", self.d, self.type_ids_off + 4 * idx)[0])

    def proto(self, idx):
        _shorty, ret, params_off = struct.unpack_from("<III", self.d, self.proto_ids_off + 12 * idx)
        params = []
        if params_off:
            n = struct.unpack_from("<I", self.d, params_off)[0]
            params = [self.type_name(struct.unpack_from("<H", self.d, params_off + 4 + 2 * k)[0]) for k in range(n)]
        return "(" + "".join(params) + ")" + self.type_name(ret)

    def method(self, idx):
        cls, proto, name = struct.unpack_from("<HHI", self.d, self.method_ids_off + 8 * idx)
        return self.type_name(cls), self.string(name), self.proto(proto)

    def field(self, idx):
        cls, typ, name = struct.unpack_from("<HHI", self.d, self.field_ids_off + 8 * idx)
        return self.type_name(cls), self.string(name), self.type_name(typ)

    def class_def(self, descriptor):
        for i in range(self.class_defs_size):
            off = self.class_defs_off + 32 * i
            class_idx, access, super_idx, ifaces_off, _src, _ann, data_off, _sv = struct.unpack_from("<8I", self.d, off)
            if self.type_name(class_idx) == descriptor:
                return {"access": access, "super": self.type_name(super_idx), "interfaces": ifaces_off,
                        "data": data_off}
        return None

    def class_data(self, data_off):
        """{"sfields", "ifields": [(name, type, access)], "direct", "virtual": [(name, proto, access, code_off)]}"""
        off = data_off
        counts = []
        for _ in range(4):
            n, off = uleb(self.d, off)
            counts.append(n)
        out = {}
        for key, count in (("sfields", counts[0]), ("ifields", counts[1])):
            idx, items = 0, []
            for _ in range(count):
                diff, off = uleb(self.d, off)
                idx += diff
                access, off = uleb(self.d, off)
                _cls, name, typ = self.field(idx)
                items.append((name, typ, access))
            out[key] = items
        for key, count in (("direct", counts[2]), ("virtual", counts[3])):
            idx, items = 0, []
            for _ in range(count):
                diff, off = uleb(self.d, off)
                idx += diff
                access, off = uleb(self.d, off)
                code, off = uleb(self.d, off)
                _cls, name, proto = self.method(idx)
                items.append((name, proto, access, code))
            out[key] = items
        return out

    def code(self, code_off):
        regs, ins, _outs, tries, _dbg, n = struct.unpack_from("<HHHHII", self.d, code_off)
        return regs, ins, tries, struct.unpack_from("<%dH" % n, self.d, code_off + 16)


def disassemble(dex, units):
    """The instructions of a method as text with every reference resolved (the opcodes the checked
    methods use; any other refuses, so an unexpected method never passes)."""
    out, pc = [], 0
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
        elif op in (0x01, 0x04, 0x07):
            out.append("%s v%d v%d" % ({0x01: "move", 0x04: "move-wide", 0x07: "move-object"}[op], a, b)); pc += 1
        elif op == 0x13:
            out.append("const/16 v%d %d" % (aa, struct.unpack("<h", struct.pack("<H", units[pc + 1]))[0])); pc += 2
        elif op == 0x1a:
            out.append("const-string v%d %r" % (aa, dex.string(units[pc + 1]))); pc += 2
        elif op == 0x22:
            out.append("new-instance v%d %s" % (aa, dex.type_name(units[pc + 1]))); pc += 2
        elif 0x52 <= op <= 0x5f:
            names = ["iget", "iget-wide", "iget-object", "iget-boolean", "iget-byte", "iget-char", "iget-short",
                     "iput", "iput-wide", "iput-object", "iput-boolean", "iput-byte", "iput-char", "iput-short"]
            cls, name, typ = dex.field(units[pc + 1])
            out.append("%s v%d v%d %s->%s:%s" % (names[op - 0x52], a, b, cls, name, typ)); pc += 2
        elif 0x6e <= op <= 0x72:
            kind = ["invoke-virtual", "invoke-super", "invoke-direct", "invoke-static", "invoke-interface"][op - 0x6e]
            count, g = aa >> 4, aa & 0xF
            regs = [units[pc + 2] & 0xF, (units[pc + 2] >> 4) & 0xF, (units[pc + 2] >> 8) & 0xF, units[pc + 2] >> 12, g][:count]
            cls, name, proto = dex.method(units[pc + 1])
            out.append("%s {%s} %s->%s%s" % (kind, ",".join("v%d" % r for r in regs), cls, name, proto)); pc += 3
        elif 0x74 <= op <= 0x78:
            kind = ["invoke-virtual", "invoke-super", "invoke-direct", "invoke-static", "invoke-interface"][op - 0x74]
            cls, name, proto = dex.method(units[pc + 1])
            first = units[pc + 2]
            out.append("%s/range {v%d..v%d} %s->%s%s" % (kind, first, first + aa - 1, cls, name, proto)); pc += 3
        else:
            raise ValueError("opcode 0x%02x not expected" % op)
    return out


# ---------------------------------------------------------------------------- the checks

class Mismatch(Exception):
    pass


def expect(what, got, want):
    if got != want:
        raise Mismatch("%s: %s, expected %s" % (what, got, want))


def check_reading_class(dex, v):
    cls = v["class"]
    cd = dex.class_def(cls)
    if cd is None:
        return False
    expect(cls + " access", cd["access"], 0x11)  # public final
    expect(cls + " superclass", cd["super"], OBJECT)
    expect(cls + " interfaces", cd["interfaces"], 0)
    data = dex.class_data(cd["data"])
    expect(cls + " static fields", data["sfields"], [])
    expect(cls + " fields", [(n, t, a) for n, t, a in data["ifields"]],
           [(n, t, 0x12) for n, t in v["fields"]])  # private final
    want_virtual = sorted([(name, "()" + _type_of(v, value), 0x11) for name, value in v["getters"]] +
                          [("equals", "(Ljava/lang/Object;)Z", 0x11), ("hashCode", "()I", 0x11),
                           ("toString", "()Ljava/lang/String;", 0x11)])
    expect(cls + " methods", sorted((n, p, a) for n, p, a, _c in data["virtual"]), want_virtual)
    expect(cls + " constructors", [(n, p, a) for n, p, a, _c in data["direct"]], [("<init>", INIT_PROTO, 0x10001)])
    f = {value: (cls, name, typ) for (name, typ), value in zip(v["fields"], ("speed", "units", "speedLimit", "speedKph"))}
    # the constructor stores its parameters in order: (this v1) speed v2, units v3, speedLimit v4, speedKph v5-v6
    regs, ins, tries, units = dex.code(data["direct"][0][3])
    nc = v["null_check"]
    expect(cls + ".<init>", (regs, ins, tries, disassemble(dex, units)), (7, 6, 0, [
        "const-string v0 'units'",
        "invoke-static {v3,v0} %s->%s%s" % nc,
        "invoke-direct {v1} Ljava/lang/Object;-><init>()V",
        "iput v2 v1 %s->%s:%s" % f["speed"],
        "iput-object v3 v1 %s->%s:%s" % f["units"],
        "iput v4 v1 %s->%s:%s" % f["speedLimit"],
        "iput-wide v5 v1 %s->%s:%s" % f["speedKph"],
        "return-void"]))
    # each getter returns its field
    code = {n: c for n, _p, _a, c in data["virtual"]}
    for name, value in v["getters"]:
        regs, ins, tries, units = dex.code(code[name])
        kind = {"I": ("iget", "return"), "D": ("iget-wide", "return-wide"), STRING: ("iget-object", "return-object")}[f[value][2]]
        self_reg = regs - 1
        expect("%s.%s" % (cls, name), (ins, tries, disassemble(dex, units)),
               (1, 0, ["%s v0 v%d %s->%s:%s" % ((kind[0], self_reg) + f[value]), "%s v0" % kind[1]]))
    # toString names the fields in that order: what each one is, as Waze's own class says
    _regs, _ins, _tries, units = dex.code(code["toString"])
    text = disassemble(dex, units)
    labels = [line.split(" ", 2)[2] for line in text if line.startswith("const-string")]
    expect(cls + ".toString labels", labels, [repr(s) for s in v["toString"]])
    gets = [line.split(" ")[-1] for line in text if line.startswith("iget")]
    expect(cls + ".toString fields", gets, ["%s->%s:%s" % f[k] for k in ("speed", "units", "speedLimit", "speedKph")])
    return True


def _field_of(v, value):
    return dict(zip(("speed", "units", "speedLimit", "speedKph"), [n for n, _t in v["fields"]]))[value]


def _type_of(v, value):
    return dict(v["fields"])[_field_of(v, value)]


def check_caller(dex, v):
    cls, method, proto = v["caller"]
    cd = dex.class_def(cls)
    if cd is None:
        return False
    data = dex.class_data(cd["data"])
    found = [c for n, p, _a, c in data["direct"] + data["virtual"] if n == method and p == proto and c]
    expect("%s.%s%s" % (cls, method, proto), len(found), 1)
    regs, ins, tries, units = dex.code(found[0])
    text = disassemble(dex, units)
    # new reading(speed, units, speedLimit, speedKph) from the method's parameters (this v6, v7..v11)
    expect("%s.%s" % (cls, method), (regs, ins, tries, text[:6]), (12, 6, 0, [
        "new-instance v0 " + v["class"],
        "move v1 v7", "move-object v2 v8", "move v3 v9", "move-wide v4 v10",
        "invoke-direct/range {v0..v5} %s-><init>%s" % (v["class"], INIT_PROTO)]))
    expect("%s.%s stores it in" % (cls, method), text[6].split(" ")[3].split(":")[0], "%s->%s" % (cls, v["caller_flow"]))
    return True


def check_atl(api_impl):
    """ATL's api-impl.jar has ATL_BASE with the constructor and getters the generated class calls
    (an older ATL without it would make the app's reading class fail to load)"""
    want = set([("<init>", INIT_PROTO)] + [(n, "()" + t) for n, t in BASE_GETTERS.values()])
    with zipfile.ZipFile(api_impl) as z:
        for name in sorted(n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")):
            dex = Dex(z.read(name))
            cd = dex.class_def(ATL_BASE)
            if cd is None:
                continue
            if cd["access"] & 0x10 or not cd["access"] & 0x1:  # public, not final
                raise Mismatch("%s in %s is not a public class open to subclasses" % (ATL_BASE, api_impl))
            data = dex.class_data(cd["data"])
            have = set((n, p) for n, p, a, _c in data["direct"] + data["virtual"] if a & 0x1)
            missing = want - have
            if missing:
                raise Mismatch("%s in %s lacks %s" % (ATL_BASE, api_impl, ", ".join(n + p for n, p in sorted(missing))))
            return
    raise Mismatch("ATL's %s has no %s (an ATL older than the car page's speedometer)" % (api_impl, ATL_BASE))


def check(app_dir):
    """(versionCode, versionName, the version's VERIFIED entry) when the installed app is a verified
    version whose code is as expected; raises Mismatch otherwise"""
    base = os.path.join(app_dir, "base.apk")
    info = json.loads(subprocess.run([sys.executable, "-I", APK_INFO, base], check=True,
                                     capture_output=True, text=True).stdout)
    vc = int(info["versionCode"])
    if info.get("package") != "com.waze" or vc not in VERIFIED:
        raise Mismatch("%s versionCode %s is not a verified Waze version (%s)"
                       % (info.get("package"), vc, ", ".join(str(k) for k in VERIFIED)))
    v = VERIFIED[vc]
    seen_class = seen_caller = False
    with zipfile.ZipFile(base) as z:
        for name in sorted(n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")):
            dex = Dex(z.read(name))
            if dex.class_def(v["class"]) is not None:
                if seen_class:
                    raise Mismatch("%s is defined in more than one dex" % v["class"])
                seen_class = check_reading_class(dex, v)
            if dex.class_def(v["caller"][0]) is not None:
                seen_caller = check_caller(dex, v)
    if not seen_class:
        raise Mismatch("no dex of %s holds %s" % (base, v["class"]))
    if not seen_caller:
        raise Mismatch("no dex of %s holds %s" % (base, v["caller"][0]))
    return vc, info.get("versionName"), v


# ---------------------------------------------------------------------------- writing the class

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


def shorty(proto):
    params, ret = proto[1:].split(")")
    out, i = [], 0
    while i < len(params):
        c = params[i]
        if c == "L":
            i = params.index(";", i)
        elif c == "[":
            raise ValueError("arrays not used here")
        out.append(c)
        i += 1
    return (ret if ret[0] != "L" else "L") + "".join(out)


def param_types(proto):
    params = proto[1:].split(")")[0]
    out, i = [], 0
    while i < len(params):
        if params[i] == "L":
            j = params.index(";", i)
            out.append(params[i:j + 1])
            i = j + 1
        else:
            out.append(params[i])
            i += 1
    return out


def reading_class_dex(v):
    """A dex with one class: v["class"], public final, extending ATL_BASE, with the constructor of the
    app's class and its getters (each calling ATL_BASE's for the same value); equals, hashCode and
    toString are ATL_BASE's."""
    cls = v["class"]
    getters = [(name, value, "()" + BASE_GETTERS[value][1]) for name, value in v["getters"]]
    # methods: (class, name, proto)
    methods = [(ATL_BASE, "<init>", INIT_PROTO)] + [(ATL_BASE, BASE_GETTERS[val][0], p) for _n, val, p in getters] \
        + [(cls, "<init>", INIT_PROTO)] + [(cls, n, p) for n, _val, p in getters]
    protos = sorted(set(p for _c, _n, p in methods))
    types = set([cls, ATL_BASE])
    for p in protos:
        types.update(param_types(p))
        types.add(p.split(")")[1])
    strings = set(types) | set(n for _c, n, _p in methods) | set(shorty(p) for p in protos)
    strings = sorted(strings)  # ASCII: code point order is Java's String order
    sidx = {s: i for i, s in enumerate(strings)}
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

    # type lists (the protos' parameters)
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
    sections.append((0x1001, len(type_list_off), first))

    # code: the constructor passes its six registers (this, speed, units, speedLimit, speedKph) to
    # ATL_BASE's; each getter calls ATL_BASE's getter and returns what it returns
    codes = {}

    def code_item(regs, ins, outs, insns):
        align(data)
        off = here()
        data.extend(struct.pack("<HHHHII", regs, ins, outs, 0, 0, len(insns)))
        data.extend(b"".join(struct.pack("<H", u) for u in insns))
        return off

    align(data)
    code_start = here()
    codes[(cls, "<init>", INIT_PROTO)] = code_item(6, 6, 6, [0x0676, midx[(ATL_BASE, "<init>", INIT_PROTO)], 0x0000, 0x000e])
    for name, value, proto in getters:
        base = midx[(ATL_BASE, BASE_GETTERS[value][0], proto)]
        ret = proto.split(")")[1]
        if ret == "D":
            insns, regs = [0x106e, base, 0x0002, 0x000b, 0x0010], 3  # move-result-wide v0; return-wide v0
        elif ret == "I":
            insns, regs = [0x106e, base, 0x0001, 0x000a, 0x000f], 2  # move-result v0; return v0
        else:
            insns, regs = [0x106e, base, 0x0001, 0x000c, 0x0011], 2  # move-result-object v0; return-object v0
        codes[(cls, name, proto)] = code_item(regs, 1, 1, insns)
    sections.append((0x2001, len(codes), code_start))

    # string data
    string_data_off = []
    for s in strings:
        string_data_off.append(here())
        data += write_uleb(len(s)) + s.encode("ascii") + b"\0"
    sections.append((0x2002, len(strings), string_data_off[0]))

    # class data: the constructor (direct), the getters (virtual), in method index order
    class_data_off = here()
    ctor = midx[(cls, "<init>", INIT_PROTO)]
    virt = sorted(midx[(cls, n, p)] for n, _v, p in getters)
    data += write_uleb(0) + write_uleb(0) + write_uleb(1) + write_uleb(len(virt))
    data += write_uleb(ctor) + write_uleb(0x10001) + write_uleb(codes[(cls, "<init>", INIT_PROTO)])
    prev = 0
    for i, m in enumerate(virt):
        data += write_uleb(m - prev if i else m) + write_uleb(0x11) + write_uleb(codes[methods[m]])
        prev = m
    sections.append((0x2000, 1, class_data_off))

    # the map
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
    struct.pack_into("<IIIIIIIIIIIIIIIIIIII", out, 0x20,
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
    struct.pack_into("<8I", out, class_defs_off, tidx[cls], 0x11, tidx[ATL_BASE], 0, 0xFFFFFFFF, 0, class_data_off, 0)
    out += data
    out[12:32] = hashlib.sha1(bytes(out[32:])).digest()
    struct.pack_into("<I", out, 8, zlib.adler32(bytes(out[12:])) & 0xFFFFFFFF)
    return bytes(out)


def write_jar(dex_bytes, out_jar):
    os.makedirs(os.path.dirname(os.path.abspath(out_jar)), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(os.path.abspath(out_jar)), suffix=".jar")
    os.close(fd)
    # the dex stored at a 4-byte boundary, so ART maps it in place (zipalign's padding: the extra
    # field 0xd935 with the alignment, here after the 30-byte local header and the 11-byte name)
    info = zipfile.ZipInfo("classes.dex", date_time=(2026, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_STORED
    pad = (-(30 + len(info.filename) + 6)) % 4
    info.extra = struct.pack("<HHH", 0xD935, 2 + pad, 4) + b"\0" * pad
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as out:
        out.writestr(info, dex_bytes)
    os.chmod(tmp, 0o644)
    os.replace(tmp, out_jar)


# ---------------------------------------------------------------------------- Waze's own speedometer

def config_db(data_dir):
    return os.path.join(data_dir, APP_DATA_SUBDIR, "waze_config.db")


def read_row(db):
    c = sqlite3.connect(db, timeout=10)
    try:
        r = c.execute("SELECT value FROM config_values WHERE domain = ? AND category = ? AND name = ?", SHOW_ROW).fetchone()
        return c, (r[0] if r else None)
    except sqlite3.Error:
        c.close()
        raise


def hide(data_dir):
    """Waze's "Show speedometer" off; its earlier value kept once (a second hide keeps the first)"""
    db = config_db(data_dir)
    if not os.path.isfile(db):
        # a first start: Waze makes its config at its first start, with its own default (on); the
        # launcher hides it before the next start
        print("waze-speedometer: no %s yet; Waze's own speedometer stays until its next start" % db, file=sys.stderr)
        return 1
    saved = os.path.join(data_dir, SAVED_NAME)
    c, value = read_row(db)
    try:
        if not os.path.isfile(saved):
            tmp = saved + ".tmp"
            with open(tmp, "w") as f:
                json.dump({"row": list(SHOW_ROW), "value": value}, f)
            os.replace(tmp, saved)
        if value != "0":
            with c:
                c.execute("INSERT INTO config_values (domain, category, name, value) VALUES (?, ?, ?, '0') "
                          "ON CONFLICT (domain, category, name) DO UPDATE SET value = '0'", SHOW_ROW)
            print("waze-speedometer: Waze's own speedometer off (it was %s)" % ("unset" if value is None else value))
    finally:
        c.close()
    return 0


def restore(data_dir):
    """Waze's "Show speedometer" back to what it was before hide"""
    saved = os.path.join(data_dir, SAVED_NAME)
    if not os.path.isfile(saved):
        return 0
    with open(saved) as f:
        value = json.load(f).get("value")
    db = config_db(data_dir)
    if os.path.isfile(db):
        c, _now = read_row(db)
        try:
            with c:
                if value is None:
                    c.execute("DELETE FROM config_values WHERE domain = ? AND category = ? AND name = ?", SHOW_ROW)
                else:
                    c.execute("INSERT INTO config_values (domain, category, name, value) VALUES (?, ?, ?, ?) "
                              "ON CONFLICT (domain, category, name) DO UPDATE SET value = excluded.value",
                              SHOW_ROW + (str(value),))
        finally:
            c.close()
        print("waze-speedometer: Waze's own speedometer setting restored (%s)" % ("unset" if value is None else value))
    os.remove(saved)
    return 0


def settings(data_dir):
    """The speedometer configs Waze mirrors in its shared preferences: {"signStyle": "us"|"world",
    "alertOffset": N, "hidden": whether hide is in effect}; missing ones are left out."""
    out = {"hidden": os.path.isfile(os.path.join(data_dir, SAVED_NAME))}
    path = os.path.join(data_dir, APP_DATA_SUBDIR, "shared_prefs", "com.waze.config.offline.xml")
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError):
        return out
    for e in root:
        name = e.get("name")
        if name == "CONFIG_VALUE_MAP_SPEEDOMETER_STYLE" and e.tag == "string":
            out["signStyle"] = "us" if (e.text or "").strip() == "us" else "world"
        elif name == "CONFIG_VALUE_MAP_SPEEDOMETER_SPEED_ALERT_OFFSET" and e.tag == "long":
            try:
                out["alertOffset"] = int(e.get("value"))
            except (TypeError, ValueError):
                pass
    return out


def main():
    args = sys.argv[1:]
    usage = __doc__.split("\n\n")[1]
    if not args:
        print(usage, file=sys.stderr)
        return 2
    cmd, rest = args[0], args[1:]
    atl = None
    if "--atl" in rest:
        i = rest.index("--atl")
        if i + 1 >= len(rest):
            print(usage, file=sys.stderr)
            return 2
        atl = rest[i + 1]
        rest = rest[:i] + rest[i + 2:]
    try:
        if cmd in ("jar", "check") and len(rest) == (2 if cmd == "jar" else 1):
            vc, vn, v = check(rest[0])
            if atl:
                check_atl(atl)
            dex = reading_class_dex(v)
            print("waze-speedometer: Waze %s (%d): %s and %s.%s as expected; %s extends %s"
                  % (vn, vc, v["class"], v["caller"][0], v["caller"][1], v["class"], ATL_BASE))
            if cmd == "jar":
                write_jar(dex, rest[1])
                print("waze-speedometer: wrote %s" % rest[1])
            return 0
        if cmd == "hide" and len(rest) == 1:
            return hide(rest[0])
        if cmd == "restore" and len(rest) == 1:
            return restore(rest[0])
        if cmd == "settings" and len(rest) == 1:
            print(json.dumps(settings(rest[0])))
            return 0
    except Mismatch as e:
        print("waze-speedometer: %s: nothing changed" % e, file=sys.stderr)
        return 2
    except (sqlite3.Error, OSError, ValueError) as e:
        print("waze-speedometer: %s" % e, file=sys.stderr)
        return 1
    print(usage, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
