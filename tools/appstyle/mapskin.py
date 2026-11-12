#!/usr/bin/env python3
"""Check an ATL file overlay manifest (host/appstyle/waze-map-graphite.json) against an installed app.

The manifest's overlays apply only for the (versionCode, sha256 of the original) pairs listed under
each entry's "verified" (ATL: src/api-impl-jni/file_overlays.c). This tool shows what ATL will do with
the app as installed, previews the transformed files, and adds a checked version to the list.

  mapskin.py check    [--manifest M] [--data-dir D] [--version-code N]
        each overlay: the file's sha256, whether that pair is verified, whether every replacement
        occurs exactly once; exit 0 when all of them would apply
  mapskin.py preview  --out DIR [...]      the transformed files, as ATL would serve them (for diff)
  mapskin.py add      --version-name X [...]   add (versionCode, sha256) of every file whose
        replacements all apply to its entry's "verified" list (after checking the map: docs/MAP-COLOURS.md)

Defaults: the installed manifest (/usr/local/lib/teslalinux/appstyle/) or the repo's, the app's data
folder /var/lib/tesla/android/waze/base.apk_, and the versionCode from the app's installed.json
(tools/apps/install-app.sh). Run it as root or as the app's user (the data folder is theirs).
"""
import argparse
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFESTS = ("/usr/local/lib/teslalinux/appstyle/waze-map-graphite.json",
             os.path.join(HERE, "..", "..", "host", "appstyle", "waze-map-graphite.json"))
DATA_DIR = "/var/lib/tesla/android/waze/base.apk_"
APPS_DIR = "/var/lib/teslalinux/apps"


def load_manifest(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def replacements(manifest, entry):
    r = entry.get("replace")
    if isinstance(r, str):
        return manifest.get("replacements", {}).get(r)
    return r


def transform(orig, pairs):
    """ATL's rule: every FROM exactly once in the original, all replaced at once, no overlaps.
    Returns (bytes or None, list of problems)."""
    problems, spans = [], []
    for k, (src, dst) in enumerate(pairs):
        s = src.encode()
        count, at, i = 0, -1, orig.find(s)
        while i >= 0:
            count += 1
            at = at if at >= 0 else i
            i = orig.find(s, i + 1)
        if count != 1:
            problems.append("replacement %d %r occurs %d times, not once" % (k + 1, src, count))
        else:
            spans.append((at, at + len(s), dst.encode()))
    if problems:
        return None, problems
    spans.sort()
    out, pos = [], 0
    for a, b, dst in spans:
        if a < pos:
            return None, ["two replacements overlap at byte %d" % a]
        out += [orig[pos:a], dst]
        pos = b
    out.append(orig[pos:])
    return b"".join(out), []


def dumps(v, level=0):
    """JSON as the manifest is written by hand: two-space indent, a list or object of scalars on one
    line when it fits (the replacement pairs, the verified items)"""
    pad, inner = "  " * level, "  " * (level + 1)
    flat = json.dumps(v, ensure_ascii=False)
    scalars = isinstance(v, (list, dict)) and all(not isinstance(x, (list, dict)) for x in (v.values() if isinstance(v, dict) else v))
    if not isinstance(v, (list, dict)) or (scalars and len(flat) + len(inner) <= 140):
        return flat if not isinstance(v, dict) else json.dumps(v, ensure_ascii=False, separators=(", ", ": "))
    if isinstance(v, list):
        return "[]" if not v else "[\n" + ",\n".join(inner + dumps(x, level + 1) for x in v) + "\n" + pad + "]"
    return "{}" if not v else "{\n" + ",\n".join(inner + json.dumps(k) + ": " + dumps(x, level + 1) for k, x in v.items()) + "\n" + pad + "}"


def installed_version(package):
    try:
        with open(os.path.join(APPS_DIR, package, "installed.json")) as f:
            v = json.load(f)
        return int(v["versionCode"]), v.get("versionName")
    except (OSError, ValueError, KeyError, TypeError):
        return None, None


def file_path(data_dir, entry):
    p = entry["path"]
    return p if p.startswith("/") else os.path.join(data_dir, p)


def inspect(manifest, data_dir, version_code):
    """one row per overlay: name, path, sha256 (None: missing), verified, problems, transformed bytes"""
    rows = []
    for e in manifest.get("overlays", []):
        path = file_path(data_dir, e)
        try:
            with open(path, "rb") as f:
                orig = f.read()
        except OSError:
            rows.append((e, path, None, False, ["the file is not there (the app unpacks it at its first start)"], None))
            continue
        sha = hashlib.sha256(orig).hexdigest()
        verified = any(v.get("versionCode") == version_code and v.get("sha256", "").lower() == sha for v in e.get("verified", []))
        pairs = replacements(manifest, e)
        if "contents" in e:
            out, problems = e["contents"].encode(), []
        elif not pairs:
            out, problems = None, ["no replacement list"]
        else:
            out, problems = transform(orig, pairs)
        rows.append((e, path, sha, verified, problems, out))
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=("check", "preview", "add"))
    ap.add_argument("--manifest", default=next((m for m in MANIFESTS if os.path.exists(m)), MANIFESTS[-1]))
    ap.add_argument("--data-dir", default=DATA_DIR)
    ap.add_argument("--package", default="com.waze")
    ap.add_argument("--version-code", type=int)
    ap.add_argument("--version-name")
    ap.add_argument("--out", help="preview: where to write the transformed files")
    a = ap.parse_args()

    manifest = load_manifest(a.manifest)
    vc, vn = (a.version_code, a.version_name) if a.version_code else installed_version(a.package)
    vn = a.version_name or vn
    if vc is None:
        sys.exit("mapskin: no versionCode (no %s/%s/installed.json); pass --version-code" % (APPS_DIR, a.package))
    manifest["overlays"] = [e for e in manifest.get("overlays", []) if e.get("package") == a.package]
    rows = inspect(manifest, a.data_dir, vc)
    print("%s versionCode %d%s, manifest %s" % (a.package, vc, " (%s)" % vn if vn else "", a.manifest))
    ok = True
    for e, path, sha, verified, problems, out in rows:
        state = "VERIFIED" if verified and not problems else ("applies, not verified" if sha and not problems else "does not apply")
        ok = ok and verified and not problems
        print("\n%s\n  %s\n  sha256 %s\n  %s" % (e.get("name", e["path"]), path, sha or "-", state))
        for p in problems:
            print("  - " + p)
        if out is not None and sha:
            print("  %d bytes -> %d bytes" % (len(open(path, "rb").read()), len(out)))

    if a.command == "preview":
        if not a.out:
            sys.exit("mapskin: preview needs --out DIR")
        for e, path, sha, verified, problems, out in rows:
            if out is not None:
                dst = os.path.join(a.out, e["path"].lstrip("/"))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                with open(dst, "wb") as f:
                    f.write(out)
                print("wrote " + dst)
    elif a.command == "add":
        if not vn:
            sys.exit("mapskin: add needs --version-name (e.g. 5.25.0.1)")
        full = load_manifest(a.manifest)
        added = 0
        for e, path, sha, verified, problems, out in rows:
            if sha is None or problems or verified:
                continue
            for fe in full["overlays"]:
                if fe.get("package") == a.package and fe["path"] == e["path"]:
                    fe.setdefault("verified", []).append({"versionCode": vc, "versionName": vn, "sha256": sha})
                    added += 1
        with open(a.manifest + ".tmp", "w", encoding="utf-8") as f:
            f.write(dumps(full) + "\n")
        os.replace(a.manifest + ".tmp", a.manifest)
        print("\nadded %d (versionCode, sha256) pair(s) to %s" % (added, a.manifest))
        return 0
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
