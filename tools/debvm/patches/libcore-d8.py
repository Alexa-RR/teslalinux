#!/usr/bin/env python3
"""libcore-d8.py R8_JAR CLASSES_JAR WORK JAR...: rebuild ART boot class path jars with D8.

art_standalone dexes libcore with dx, which leaves Java 8 lambdas and method references as
invoke-custom call sites: ART bootstraps them through java.lang.invoke.LambdaMetafactory at run
time, which cannot work for altMetafactory (ART cannot invoke a varargs bootstrap method: every
serializable lambda of libcore, e.g. Comparator.comparing, Map.Entry.comparingByKey, throws
BootstrapMethodError) and fails for some plain ones too. D8 desugars them at build time into
classes, as Android's own build does for libcore.

For each JAR (a *-hostdex.jar of the boot class path): the classes its dex files define are taken
from CLASSES_JAR (libcore's javac output, core-all_classes.jar) and dexed with D8 (the rest of
CLASSES_JAR as the class path); a class the jar's dex has but CLASSES_JAR lacks stops the script (it
would be lost). The jar is rewritten with the new classes*.dex and every other entry it had
(resources such as currency data and properties files) unchanged. The original is kept as JAR.dx.
"""
import os
import shutil
import struct
import subprocess
import sys
import zipfile


def uleb(b, o):
    r = s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7F) << s
        s += 7
        if x < 0x80:
            return r, o


def dex_classes(d):
    """the class names (a/b/C) a dex file defines"""
    string_ids_size, string_ids_off, type_ids_size, type_ids_off = struct.unpack_from("<4I", d, 56)
    class_defs_size, class_defs_off = struct.unpack_from("<2I", d, 96)

    def string(i):
        off = struct.unpack_from("<I", d, string_ids_off + 4 * i)[0]
        _, o = uleb(d, off)
        return d[o:d.index(b"\0", o)].decode("utf-8", "replace")

    out = []
    for ci in range(class_defs_size):
        t = struct.unpack_from("<I", d, class_defs_off + 32 * ci)[0]
        desc = string(struct.unpack_from("<I", d, type_ids_off + 4 * t)[0])
        out.append(desc[1:-1])
    return out


def main():
    r8, classes_jar, work = sys.argv[1:4]
    jars = sys.argv[4:]
    os.makedirs(work, exist_ok=True)
    with zipfile.ZipFile(classes_jar) as cz:
        available = {n[:-6]: n for n in cz.namelist() if n.endswith(".class")}
        for jar in jars:
            name = os.path.basename(jar)
            orig = jar + ".dx"
            if not os.path.exists(orig):
                shutil.copy2(jar, orig)
            with zipfile.ZipFile(orig) as jz:
                defined = []
                for n in jz.namelist():
                    if n.startswith("classes") and n.endswith(".dex"):
                        defined += dex_classes(jz.read(n))
                missing = [c for c in defined if c not in available]
                if missing:
                    sys.exit("%s: %d classes are not in %s (e.g. %s)" % (name, len(missing), classes_jar, missing[:5]))
                wdir = os.path.join(work, name)
                shutil.rmtree(wdir, ignore_errors=True)
                os.makedirs(wdir)
                inp = os.path.join(wdir, "in.jar")
                with zipfile.ZipFile(inp, "w") as oz:
                    for c in defined:
                        oz.writestr(available[c], cz.read(available[c]))
                out = os.path.join(wdir, "dex")
                os.makedirs(out)
                cmd = ["java", "-Xmx2g", "-cp", r8, "com.android.tools.r8.D8", "--release", "--min-api", "28",
                       "--classpath", classes_jar, "--output", out, inp]
                subprocess.run(cmd, check=True)
                tmp = jar + ".d8-new"
                with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as nz:
                    for f in sorted(os.listdir(out)):
                        if f.endswith(".dex"):
                            nz.write(os.path.join(out, f), f)
                    for info in jz.infolist():
                        if not (info.filename.startswith("classes") and info.filename.endswith(".dex")):
                            nz.writestr(info, jz.read(info.filename))
                os.replace(tmp, jar)
                print("%s: %d classes dexed with D8 (%s)" % (name, len(defined), ", ".join(sorted(f for f in os.listdir(out)))))


main()
