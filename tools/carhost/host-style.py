#!/usr/bin/env python3
"""teslalinux: the AOSP Templates Host's look as data, for the car page's template renderer.

  host-style.py HOST.apk OUT.json [--aapt2 PATH] [--night | --day]

Reads the compiled resources of the host APK (tools/carhost/build-templates-host.sh) with
`aapt2 dump resources`, resolves every dimension, colour, integer, boolean and fraction to its
final value (following @references; color state lists to their default state, read with
`aapt2 dump xmltree`), for the night configuration by default (the car display's dark theme;
values without a night variant use the default one), and writes them as JSON:

  {"dimen": {"template_padding_2": 16.0, ...},     dp (sp for text sizes, as Android scales them)
   "color": {"car_ui_text_color_primary": "#ffffffff", ...},   #aarrggbb
   "integer": {...}, "bool": {...}, "fraction": {...}}

The renderer (web/js/carapp) uses these values only: the sizes, colours and spacing of the
host's layouts, nothing of our own. Standard library only.
"""
import argparse
import json
import re
import subprocess
import sys

RES_RE = re.compile(r"^\s+resource 0x[0-9a-f]+ (\w+)/(\S+)")
VAL_RE = re.compile(r"^\s+\(([^)]*)\) (.*)$")


def parse_dump(text):
    """{(type, name): {config: raw value}} from `aapt2 dump resources`."""
    res = {}
    cur = None
    for line in text.splitlines():
        m = RES_RE.match(line)
        if m:
            cur = (m.group(1), m.group(2))
            res.setdefault(cur, {})
            continue
        m = VAL_RE.match(line)
        if m and cur:
            res[cur][m.group(1)] = m.group(2).strip()
    return res


def pick(configs, night):
    if night and "night" in configs:
        return configs["night"]
    if "" in configs:
        return configs[""]
    return None


def color_state_default(apk, path, aapt2):
    """The default (stateless, last) item of a color state list file in the APK."""
    out = subprocess.run([aapt2, "dump", "xmltree", "--file", path, apk], capture_output=True, text=True).stdout
    items = []
    cur = None
    for line in out.splitlines():
        s = line.strip()
        if s.startswith("E: item"):
            cur = {"state": False, "color": None, "alpha": None}
            items.append(cur)
        elif cur is not None and s.startswith("A: "):
            if "state_" in s:
                cur["state"] = True
            m = re.search(r"android:color\(0x[0-9a-f]+\)=(\S+)", s)
            if m:
                cur["color"] = m.group(1).strip('"')
            m = re.search(r"android:alpha\(0x[0-9a-f]+\)=(\S+)", s)
            if m:
                cur["alpha"] = m.group(1).strip('"')
    for it in reversed(items):
        if not it["state"]:
            return it
    return items[-1] if items else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("out")
    ap.add_argument("--aapt2", default="aapt2")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--night", dest="night", action="store_true", default=True)
    g.add_argument("--day", dest="night", action="store_false")
    a = ap.parse_args()

    dump = subprocess.run([a.aapt2, "dump", "resources", a.apk], capture_output=True, text=True)
    if dump.returncode != 0:
        sys.exit("aapt2 dump resources failed: " + dump.stderr)
    res = parse_dump(dump.stdout)
    ids = {}
    for line in dump.stdout.splitlines():
        m = re.match(r"^\s+resource (0x[0-9a-f]+) (\w+)/(\S+)", line)
        if m:
            ids[m.group(1)] = (m.group(2), m.group(3))

    def resolve(typ, name, depth=0):
        if depth > 20:
            return None
        raw = pick(res.get((typ, name), {}), a.night)
        if raw is None:
            return None
        if raw.startswith("@"):
            ref = raw[1:]
            if "/" in ref:
                rtyp, rname = ref.split("/", 1)
                rname = rname.split(":")[-1]
                return resolve(rtyp, rname, depth + 1)
            return None
        if raw.startswith("(file)"):
            m = re.match(r"\(file\) (\S+)", raw)
            if typ == "color" and m:
                it = color_state_default(a.apk, m.group(1), a.aapt2)
                if not it or not it["color"]:
                    return None
                c = it["color"]
                if c.startswith("@0x") and c[1:] in ids:
                    rtyp, rname = ids[c[1:]]
                    c = resolve(rtyp, rname, depth + 1)
                elif c.startswith("@") and "/" in c:
                    rtyp, rname = c[1:].split("/", 1)
                    c = resolve(rtyp, rname.split(":")[-1], depth + 1)
                elif not c.startswith("#"):
                    return None  # a theme attribute: decided by the theme, not a value
                if c and it["alpha"]:
                    try:
                        alpha = float(it["alpha"]) if not it["alpha"].startswith("@") else None
                    except ValueError:
                        alpha = None
                    if alpha is not None and c.startswith("#") and len(c) == 9:
                        aa = round(int(c[1:3], 16) * alpha)
                        c = "#%02x%s" % (aa, c[3:])
                return c
            return None
        if typ == "dimen":
            m = re.match(r"(-?[0-9.]+)(dp|sp|px|dip)?$", raw)
            return float(m.group(1)) if m else None
        if typ == "color":
            return raw.lower() if raw.startswith("#") else None
        if typ == "integer":
            try:
                return int(raw)
            except ValueError:
                return None
        if typ == "bool":
            return raw == "true"
        if typ == "fraction" or typ == "dimen":
            try:
                return float(raw.rstrip("%"))
            except ValueError:
                return None
        return None

    out = {"dimen": {}, "color": {}, "integer": {}, "bool": {}, "fraction": {}}
    for (typ, name) in sorted(res):
        if typ in out:
            v = resolve(typ, name)
            if v is not None:
                out[typ][name] = v
    with open(a.out, "w") as f:
        json.dump(out, f, indent=1, sort_keys=True)
        f.write("\n")
    print("%s: %s" % (a.out, ", ".join("%d %s" % (len(v), k) for k, v in out.items())))


if __name__ == "__main__":
    main()
