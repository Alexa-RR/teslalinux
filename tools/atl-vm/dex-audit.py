#!/usr/bin/env python3
"""teslalinux: static audit of an app against our patched ATL framework (api-impl).

  dex-audit.py APK_OR_DIR [APK ...] --src out/atl-work-deb/atl-guest/src/api-impl [--out FILE.md]
               [--json FILE.json] [--min-calls N]

Reads every classes*.dex of the APKs, decodes every method body (full Dalvik instruction-width
table, switch/array payloads included) and counts the call sites of each framework method
(invoke-*, invoke-polymorphic). A call made through an app class that does not declare the method
(WazeTextView.setText) is resolved up the app's superclass chain to the framework class it lands
on. Framework = everything the app does not define, minus what ART's libcore provides (java.*,
javax.*, sun.*, libcore.*, dalvik.system, org.json, org.xml, org.w3c, org.apache.harmony, android.icu,
android.system: they come from the boot classpath, not api-impl).

Each framework method is then looked up in the api-impl Java sources (the patched tree hostbuild.sh
compiles), walking `extends` in the sources, and classified:
  MISSING_CLASS     no source file for the class (or its outer class)
  MISSING_METHOD    no method of that name and arity in the class or its source superclasses
  EMPTY             a void method whose body is empty
  CONST             the body is a single `return <literal>;` (null, 0, false, true, "x", new T[0], ...)
  MARKED            the body says STUB / TODO / not implemented / unimplemented, or only throws
  ok                anything else (including native methods: those are checked at run time)
CONST and EMPTY include legitimate cases (a getter of a feature this platform does not have); the
report is a work list for reading, not a verdict. Sorted by call sites, most called first.
"""
import argparse
import collections
import json
import os
import re
import struct
import sys
import zipfile

# ------------------------------------------------------------------ dex parsing

def uleb(buf, off):
    result = shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, off
        shift += 7


def mutf8(buf, off):
    _, off = uleb(buf, off)
    end = buf.index(b"\0", off)
    return buf[off:end].decode("utf-8", "replace")


# Dalvik instruction widths in 16-bit code units, per opcode (dalvik-bytecode formats)
W = [1] * 256
for op, w in {0x02: 2, 0x03: 3, 0x05: 2, 0x06: 3, 0x08: 2, 0x09: 3, 0x13: 2, 0x14: 3, 0x15: 2, 0x16: 2,
              0x17: 3, 0x18: 5, 0x19: 2, 0x1A: 2, 0x1B: 3, 0x1C: 2, 0x1F: 2, 0x20: 2, 0x22: 2, 0x23: 2,
              0x24: 3, 0x25: 3, 0x26: 3, 0x29: 2, 0x2A: 3, 0x2B: 3, 0x2C: 3, 0xFA: 4, 0xFB: 4, 0xFC: 3,
              0xFD: 3, 0xFE: 2, 0xFF: 2}.items():
    W[op] = w
for op in range(0x2D, 0x3E):      # cmp*, if-*
    W[op] = 2
for op in range(0x44, 0x6E):      # aget/aput, iget/iput, sget/sput
    W[op] = 2
for op in list(range(0x6E, 0x73)) + list(range(0x74, 0x79)):   # invoke-*, invoke-*/range
    W[op] = 3
for op in range(0x90, 0xB0):      # binop
    W[op] = 2
for op in range(0xD0, 0xE3):      # binop/lit16, binop/lit8
    W[op] = 2
INVOKES = set(range(0x6E, 0x73)) | set(range(0x74, 0x79)) | {0xFA, 0xFB}


class Dex:
    def __init__(self, data):
        self.d = d = data
        (self.n_str, self.o_str, self.n_type, self.o_type, self.n_proto, self.o_proto, _, _,
         self.n_meth, self.o_meth, self.n_cls, self.o_cls) = struct.unpack_from("<12I", d, 0x38)
        self._s = {}
        self._m = {}
        self._p = {}
        # the whole file as 16-bit code units (code items are 4-byte aligned, insns 2-byte aligned)
        self.u16 = memoryview(data[: len(data) & ~1]).cast("H")

    def string(self, i):
        if i not in self._s:
            off = struct.unpack_from("<I", self.d, self.o_str + 4 * i)[0]
            self._s[i] = mutf8(self.d, off)
        return self._s[i]

    def type(self, i):
        return self.string(struct.unpack_from("<I", self.d, self.o_type + 4 * i)[0])

    def proto(self, i):
        if i in self._p:
            return self._p[i]
        self._p[i] = r = self._proto(i)
        return r

    def _proto(self, i):
        _, ret, poff = struct.unpack_from("<III", self.d, self.o_proto + 12 * i)
        params = []
        if poff:
            n = struct.unpack_from("<I", self.d, poff)[0]
            params = [self.type(t) for t in struct.unpack_from("<%dH" % n, self.d, poff + 4)]
        return tuple(params), self.type(ret)

    def method(self, i):
        if i in self._m:
            return self._m[i]
        self._m[i] = r = self._method(i)
        return r

    def _method(self, i):
        cls, proto, name = struct.unpack_from("<HHI", self.d, self.o_meth + 8 * i)
        params, ret = self.proto(proto)
        return self.type(cls), self.string(name), params, ret

    def classes(self):
        """yields (descriptor, superclass descriptor, [(method_idx, code_off)])"""
        for c in range(self.n_cls):
            cls, _, sup, _, _, _, data_off, _ = struct.unpack_from("<8I", self.d, self.o_cls + 32 * c)
            methods = []
            if data_off:
                off = data_off
                sf, off = uleb(self.d, off)
                inf, off = uleb(self.d, off)
                dm, off = uleb(self.d, off)
                vm, off = uleb(self.d, off)
                for _ in range(sf + inf):
                    _, off = uleb(self.d, off)
                    _, off = uleb(self.d, off)
                for count in (dm, vm):
                    idx = 0
                    for _ in range(count):
                        diff, off = uleb(self.d, off)
                        idx += diff
                        _, off = uleb(self.d, off)
                        code, off = uleb(self.d, off)
                        methods.append((idx, code))
            yield self.type(cls), (self.type(sup) if sup != 0xFFFFFFFF else None), methods

    def invokes(self, code_off):
        """method indices invoked by the code item at code_off"""
        u = self.u16
        n = struct.unpack_from("<I", self.d, code_off + 12)[0]
        base = (code_off + 16) >> 1
        end = base + n
        pc = base
        w = W
        inv = INVOKES
        while pc < end:
            unit = u[pc]
            op = unit & 0xFF
            if op == 0x00 and unit != 0:          # payload pseudo-instructions
                if unit == 0x0100:                # packed-switch-payload
                    pc += u[pc + 1] * 2 + 4
                elif unit == 0x0200:              # sparse-switch-payload
                    pc += u[pc + 1] * 4 + 2
                elif unit == 0x0300:              # fill-array-data-payload
                    width = u[pc + 1]
                    size = u[pc + 2] | (u[pc + 3] << 16)
                    pc += (size * width + 1) // 2 + 4
                else:
                    pc += 1
                continue
            if op in inv:
                yield u[pc + 1]
            pc += w[op]


def dex_files(paths):
    for p in paths:
        if os.path.isdir(p):
            for f in sorted(os.listdir(p)):
                if f.endswith(".apk"):
                    yield from dex_files([os.path.join(p, f)])
            continue
        with zipfile.ZipFile(p) as z:
            for n in sorted(z.namelist()):
                if re.fullmatch(r"classes\d*\.dex", n):
                    yield p + "!" + n, z.read(n)


# ------------------------------------------------------------------ framework classification

LIBCORE = ("Ljava/", "Ljavax/", "Lsun/", "Llibcore/", "Ldalvik/system/", "Lorg/json/", "Lorg/xml/",
           "Lorg/w3c/", "Lorg/apache/harmony/", "Landroid/icu/", "Landroid/system/", "Ljdk/",
           "Lcom/android/org/conscrypt/", "Lorg/ccil/", "Lorg/kxml2/", "Lorg/xmlpull/")


# java.lang.Object / Enum members every class has (name, arity)
OBJECT_METHODS = {("hashCode", 0), ("equals", 1), ("toString", 0), ("getClass", 0), ("notify", 0),
                  ("notifyAll", 0), ("wait", 0), ("wait", 1), ("wait", 2), ("ordinal", 0), ("name", 0),
                  ("compareTo", 1), ("getDeclaringClass", 0), ("clone", 0), ("finalize", 0)}

# java.lang types a source may extend or implement without an import (they come from libcore)
JAVA_LANG = {"Object", "Thread", "CharSequence", "Comparable", "Iterable", "Runnable", "Exception", "RuntimeException", "Error",
             "Throwable", "Iterable", "Comparable", "AutoCloseable", "Cloneable", "Enum", "Number",
             "IllegalStateException", "IllegalArgumentException", "Appendable", "Readable", "ThreadGroup"}


def java_name(desc):
    dims = 0
    while desc.startswith("["):
        dims += 1
        desc = desc[1:]
    prim = {"V": "void", "Z": "boolean", "B": "byte", "S": "short", "C": "char", "I": "int", "J": "long",
            "F": "float", "D": "double"}
    name = prim.get(desc) or desc[1:-1].replace("/", ".")
    return name + "[]" * dims


def simple(desc):
    return java_name(desc).split(".")[-1].split("$")[-1]


# ------------------------------------------------------------------ api-impl sources

DECL = re.compile(r"^[ \t\x01]*(?:(?:public|protected|private|static|final|native|abstract|synchronized|default|strictfp)[ \t\x01]+"
                  r"|@[\w.]+(?:\([^)\n]*\))?\s+)*"
                  r"(?:<[^>\n]*>[ \t\x01]+)?(?:@[\w.]+[ \t]+)?[\w.<>\[\]?, ]*?[\w>\]][ \t\x01]+(\w+)[ \t]*\(((?:[^()]|\([^()]*\))*)\)\s*(?:throws[ \t]+[\w., ]+)?[\s\x01]*([{;])", re.M)
CLASS_DECL = re.compile(r"(?:class|interface|enum)\s+(\w+)(?:\s*<[^{]*?>)?(?:\s+extends\s+([\w.]+))?")
LITERAL = re.compile(r"^return\s+(null|true|false|-?\d+[lLfFdD]?|-?\d*\.\d+[fFdD]?|0x[0-9a-fA-F]+[lL]?|\"[^\"]*\"|'[^']*'|"
                     r"new\s+[\w.]+\s*\[\s*0\s*\]|[\w.]+\.EMPTY\w*|Collections\.empty\w*\(\))\s*;$")


class Sources:
    def __init__(self, root):
        self.root = root
        self.cache = {}

    def file_for(self, desc):
        outer = desc[1:-1].split("$")[0]
        p = os.path.join(self.root, outer + ".java")
        return p if os.path.exists(p) else None

    def parse(self, path):
        if path in self.cache:
            return self.cache[path]
        with open(path, encoding="utf-8", errors="replace") as f:
            src = f.read()
        # strip comments, keep offsets roughly (replace with spaces)
        nocom = strip_comments(src)
        imports = dict((m.group(1).split(".")[-1], m.group(1)) for m in re.finditer(r"import\s+([\w.]+)\s*;", nocom))
        pkg = re.search(r"package\s+([\w.]+)\s*;", nocom)
        info = {"src": src, "nocom": nocom, "imports": imports, "pkg": pkg.group(1) if pkg else "",
                "decls": {}}
        # brace positions outside string/char literals ('{' in a JSON parser is not a block)
        code = mask_literals(nocom)
        info["opens"] = [m.start() for m in re.finditer(r"\{", code)]
        info["closes"] = [m.start() for m in re.finditer(r"\}", code)]
        self.cache[path] = info
        return info

    def class_block(self, info, desc):
        """(start, end) of the class body for desc (nested classes by name), and its extends name (cached)"""
        cache = info.setdefault("blocks", {})
        if desc not in cache:
            cache[desc] = self._class_block(info, desc)
        return cache[desc]

    def _class_block(self, info, desc):
        names = desc[1:-1].split("/")[-1].split("$")
        text = info["nocom"]
        start, end = 0, len(text)
        ext = None
        for n in names:
            m = None
            for cm in CLASS_DECL.finditer(text, start, end):
                if cm.group(1) == n:
                    m = cm
                    break
            if not m:
                return None, None
            brace = text.index("{", m.end())
            close = match_brace(text, brace)
            start, end, ext = brace + 1, close, m.group(2)
        return (start, end), ext

    def interfaces(self, info, desc):
        """the types an interface extends / a class implements (resolved like resolve_ext)"""
        block, _ = self.class_block(info, desc)
        if not block:
            return []
        text = info["nocom"]
        head_start = text.rfind("\n", 0, block[0] - 1)
        head = text[max(0, head_start - 400):block[0]]
        m = re.search(r"(?:implements|interface\s+\w+(?:\s*<[^{]*?>)?\s+extends)\s+([\w.,<>?\s]+?)\s*\{\s*$", head)
        if not m:
            return []
        names = [re.sub(r"<.*", "", n).strip() for n in re.sub(r"<[^<>]*>", "", m.group(1)).split(",")]
        out = []
        for n in names:
            if not n:
                continue
            if n in JAVA_LANG and n not in info["imports"]:
                out.append("Ljava/lang/" + n + ";")
            else:
                out.append(self.resolve_ext(info, n, desc))
        return out

    def resolve_ext(self, info, ext, desc):
        if not ext:
            return None
        if "." not in ext and ext not in info["imports"] and ext in JAVA_LANG:
            return "Ljava/lang/" + ext + ";"
        if "." in ext:
            return "L" + ext.replace(".", "/") + ";"
        if ext in info["imports"]:
            return "L" + info["imports"][ext].replace(".", "/") + ";"
        # same package (or a nested class of the same file)
        return "L" + (info["pkg"] + "." + ext).replace(".", "/") + ";"


def strip_comments(src):
    """comments become a non-blank filler of the same length (newlines kept), string and character
    literals are left alone ("http://..." is not a comment): offsets and line numbers stay those of
    src, and blank runs cannot make the declaration regex backtrack"""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"' or c == "'":
            q = c
            i += 1
            while i < n and src[i] != q and src[i] != "\n":
                if src[i] == "\\":
                    i += 1
                i += 1
            i += 1
        elif c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                out[i] = "\x01"
                i += 1
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            end = src.find("*/", i + 2)
            end = n if end < 0 else end + 2
            for k in range(i, end):
                if src[k] != "\n":
                    out[k] = "\x01"
            i = end
        else:
            i += 1
    return "".join(out)


def mask_literals(text):
    """string and char literal contents replaced by a filler (same length)"""
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"' or c == "'":
            q = c
            i += 1
            while i < n and text[i] != q and text[i] != "\n":
                if text[i] == "\\":
                    out[i] = "\x02"
                    i += 1
                    if i < n:
                        out[i] = "\x02"
                    i += 1
                    continue
                out[i] = "\x02"
                i += 1
            i += 1
        else:
            i += 1
    return "".join(out)


def match_brace(text, i):
    depth = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
        elif c == '"' or c == "'":
            q = c
            i += 1
            while i < n and text[i] != q:
                if text[i] == "\\":
                    i += 1
                i += 1
        i += 1
    return n - 1


def depth_at(info, start, pos):
    """brace depth of pos relative to the class body start (0 = a member of this class)"""
    import bisect
    o, c = info["opens"], info["closes"]
    return (bisect.bisect_left(o, pos) - bisect.bisect_left(o, start)) - (bisect.bisect_left(c, pos) - bisect.bisect_left(c, start))


def member_decls(info, block):
    """the method/constructor declarations directly in a class body: [(name, params, match)], cached"""
    key = block
    if key not in info["decls"]:
        text = info["nocom"]
        out = []
        for m in DECL.finditer(text, block[0], block[1]):
            if depth_at(info, block[0], m.start()) == 0:
                out.append((m.group(1), params_of(m.group(2)), m))
        info["decls"][key] = out
    return info["decls"][key]


def params_of(s):
    s = re.sub(r"<[^<>]*>", "", re.sub(r"<[^<>]*>", "", s))   # generics (two levels)
    s = re.sub(r"@\w+(\([^)]*\))?", "", s)
    parts = [p.strip() for p in s.split(",") if p.strip()]
    out = []
    for p in parts:
        toks = p.replace("...", "[] ").split()
        toks = [t for t in toks if t != "final"]
        if len(toks) >= 2:
            t = toks[-2] + ("[]" if toks[-1].endswith("[]") else "")
            out.append(t.split(".")[-1].replace("[ ]", "[]"))
    return out


_CLASSIFY = {}


def classify(srcs, desc, name, params, seen=None):
    """status, where (file:line), body excerpt (memoised per method)"""
    key = (desc, name, tuple(params))
    if seen is None and key in _CLASSIFY:
        return _CLASSIFY[key]
    r = _classify(srcs, desc, name, params, seen)
    if seen is None:
        _CLASSIFY[key] = r
    return r


def _classify(srcs, desc, name, params, seen=None):
    seen = seen or set()
    if desc in seen or not desc.startswith("Landroid") and not desc.startswith("Lcom/android") and not desc.startswith("Lorg/apache/http"):
        return None
    seen.add(desc)
    path = srcs.file_for(desc)
    if not path:
        return ("MISSING_CLASS", desc, "")
    info = srcs.parse(path)
    block, ext = srcs.class_block(info, desc)
    if not block:
        return ("MISSING_CLASS", desc + " (not in " + os.path.relpath(path, srcs.root) + ")", "")
    text = info["nocom"]
    want = [simple(p) for p in params]
    if name == "<init>":
        name = desc[1:-1].split("/")[-1].split("$")[-1]
    decls = member_decls(info, block)
    cands = [(m, ps) for (n, ps, m) in decls if n == name and len(ps) == len(want)]
    if not cands and not want and name == desc[1:-1].split("/")[-1].split("$")[-1] \
            and not any(n == name for (n, _, _) in decls):
        # no constructor declared: Java's implicit default constructor
        return ("ok", "%s (implicit constructor)" % os.path.relpath(path, srcs.root), "")
    if cands:
        exact = [c for c in cands if [p.replace("[]", "") .split("$")[-1] for p in c[1]] == [w.replace("[]", "") for w in want]]
        m, _ = (exact or cands)[0]
        line = text.count("\n", 0, m.start()) + 1
        where = "%s:%d" % (os.path.relpath(path, srcs.root), line)
        if m.group(3) == ";":
            return ("ok", where, "native/abstract")
        b0 = m.end() - 1
        b1 = match_brace(text, b0)
        body = info["src"][b0 + 1:b1].strip()
        flat = " ".join(body.split())
        bodyc = " ".join(text[b0 + 1:b1].split())
        if re.search(r"STUB|not implemented|unimplemented|TODO|FIXME", body, re.I) and len(bodyc) < 300:
            return ("MARKED", where, flat[:120])
        if re.fullmatch(r"throw new \w+(Exception|Error)\([^;]*\);", bodyc):
            return ("MARKED", where, flat[:120])
        if bodyc == "":
            return ("EMPTY", where, "")
        if LITERAL.match(bodyc):
            return ("CONST", where, bodyc[:120])
        return ("ok", where, "")
    if (name, len(want)) in OBJECT_METHODS:
        return ("ok", "java.lang.Object/Enum", "")
    sup = srcs.resolve_ext(info, ext, desc)
    if sup:
        if not sup.startswith(("Landroid", "Lcom/android", "Lorg/apache/http")):
            # inherited from a libcore class (Thread, Exception, ...): ART provides it
            return ("ok", "inherited from " + java_name(sup), "")
        r = classify(srcs, sup, name if name != desc[1:-1].split("/")[-1].split("$")[-1] else "<init>", params, seen)
        if r:
            return r
    for iface in srcs.interfaces(info, desc):
        if iface.startswith("Ljava/") or iface.split("/")[-1][:-1] in JAVA_LANG:
            continue
        r = classify(srcs, iface, name, params, seen)
        if r and r[0] != "MISSING_METHOD" and r[0] != "MISSING_CLASS":
            return r
    return ("MISSING_METHOD", "%s (%s)" % (os.path.relpath(path, srcs.root), desc), "")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("apks", nargs="+")
    ap.add_argument("--src", required=True)
    ap.add_argument("--out")
    ap.add_argument("--json")
    ap.add_argument("--min-calls", type=int, default=1)
    a = ap.parse_args()

    app_super = {}
    app_methods = collections.defaultdict(set)
    calls = collections.Counter()
    callers = collections.defaultdict(collections.Counter)
    dexes = []
    for name, data in dex_files(a.apks):
        dx = Dex(data)
        cl = list(dx.classes())
        dexes.append((name, dx, cl))
        for cls, sup, methods in cl:
            app_super[cls] = sup
            for idx, _ in methods:
                c, n, p, r = dx.method(idx)
                app_methods[cls].add((n, p))
        print("[dex-audit] %s: %d classes" % (name, len(cl)), file=sys.stderr, flush=True)
    for name, dx, cl in dexes:
        for cls, sup, methods in cl:
            pkg = "/".join(cls[1:-1].split("/")[:2])
            for idx, code in methods:
                if not code:
                    continue
                for target in dx.invokes(code):
                    c, n, p, r = dx.method(target)
                    # resolve calls through app classes to the framework class they land on
                    seen = 0
                    while c in app_super and (n, p) not in app_methods[c] and seen < 40:
                        c = app_super[c] or "Ljava/lang/Object;"
                        seen += 1
                    if c in app_super or c.startswith(LIBCORE) or c.startswith("["):
                        continue
                    key = (c, n, p, r)
                    calls[key] += 1
                    callers[key][pkg] += 1
        print("[dex-audit] %s: %d framework call sites so far" % (name, sum(calls.values())), file=sys.stderr, flush=True)

    srcs = Sources(a.src)
    rows = []
    for (c, n, p, r), count in calls.most_common():
        if count < a.min_calls:
            continue
        st = classify(srcs, c, n, p)
        if st is None:
            st = ("ok", "", "")
        rows.append({"class": java_name(c), "method": n, "params": [java_name(x) for x in p], "ret": java_name(r),
                     "calls": count, "status": st[0], "where": st[1], "body": st[2],
                     "callers": ", ".join("%s(%d)" % kv for kv in callers[(c, n, p, r)].most_common(3))})
    summary = collections.Counter(r["status"] for r in rows)
    calls_by = collections.Counter()
    for r in rows:
        calls_by[r["status"]] += r["calls"]
    if a.json:
        with open(a.json, "w") as f:
            json.dump(rows, f, indent=1)
    out = [ "# Framework API audit: %s" % ", ".join(os.path.basename(x) for x in a.apks), "",
            "Source: `%s` (the patched api-impl). %d framework methods, %d call sites." % (a.src, len(rows), sum(r["calls"] for r in rows)), "",
            "| status | methods | call sites |", "|---|---|---|"]
    for s in ("MISSING_CLASS", "MISSING_METHOD", "MARKED", "EMPTY", "CONST", "ok"):
        out.append("| %s | %d | %d |" % (s, summary[s], calls_by[s]))
    for s in ("MISSING_CLASS", "MISSING_METHOD", "MARKED", "EMPTY", "CONST"):
        sel = [r for r in rows if r["status"] == s]
        if not sel:
            continue
        out += ["", "## %s (%d)" % (s, len(sel)), "", "| calls | method | where | body | called from |", "|---|---|---|---|---|"]
        for r in sel:
            sig = "%s.%s(%s)" % (r["class"], r["method"], ", ".join(x.split(".")[-1] for x in r["params"]))
            out.append("| %d | `%s` | %s | %s | %s |" % (r["calls"], sig, r["where"], ("`%s`" % r["body"]) if r["body"] else "", r["callers"]))
    text = "\n".join(out) + "\n"
    if a.out:
        with open(a.out, "w") as f:
            f.write(text)
        print("[dex-audit] report -> %s" % a.out, file=sys.stderr)
    else:
        sys.stdout.write(text)
    for s in ("MISSING_CLASS", "MISSING_METHOD", "MARKED", "EMPTY", "CONST", "ok"):
        print("[dex-audit] %-15s %5d methods %7d call sites" % (s, summary[s], calls_by[s]), file=sys.stderr)


if __name__ == "__main__":
    main()
