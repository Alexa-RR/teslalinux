#!/usr/bin/env python3
"""teslalinux: CHANGELOG.md is the one source of the release notes. This script turns one version's
section into the body of a GitHub Release (Markdown, links made absolute).

  changelog.py notes TAG [--repo OWNER/REPO]
                                         print the section of TAG (v0.2.0 or 0.2.0) for a GitHub
                                         Release: image and file links point at the tag on GitHub
  changelog.py versions                  list the versions and their dates

The CHANGELOG format (Keep a Changelog, newest first):
  ## [Unreleased]                  changes since the last release
  ## [0.2.0] - 2026-11-12          (or "- Unreleased" until the tag is pushed)
  intro paragraphs, images
  ### A topic                      a topic; its first paragraph, when it is one
  *One-line summary.*              italic line, is its subtitle
  text and "- " lists
  ### Platform                     for developers
  #### Added / Changed / ...
  [0.2.0]: https://...             link definitions at the end
Only the standard library; Python 3.7+.
"""
import argparse
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
CHANGELOG = "CHANGELOG.md"

VERSION_RE = re.compile(r"^## \[(?P<ver>[^\]]+)\](?:\s*-\s*(?P<date>.+?))?\s*$")
LINKDEF_RE = re.compile(r"^\[[^\]]+\]:\s*\S+")


def parse(text):
    """[{version, date, intro: [lines], sections: [{title, lines}]}], newest first as written."""
    versions = []
    cur = sec = None
    in_comment = False
    for line in text.splitlines():
        if in_comment:
            if "-->" in line:
                in_comment = False
            continue
        if line.strip().startswith("<!--"):
            in_comment = "-->" not in line
            continue
        m = VERSION_RE.match(line)
        if m:
            cur = {"version": m.group("ver").strip(), "date": (m.group("date") or "").strip(),
                   "intro": [], "sections": []}
            versions.append(cur)
            sec = None
            continue
        if cur is None:
            continue
        if line.startswith("## "):  # any other level-2 heading ends the version
            cur = sec = None
            continue
        if LINKDEF_RE.match(line):
            continue
        if line.startswith("### "):
            sec = {"title": line[4:].strip(), "lines": []}
            cur["sections"].append(sec)
            continue
        (sec["lines"] if sec else cur["intro"]).append(line)
    for v in versions:
        v["intro"] = trim(v["intro"])
        for s in v["sections"]:
            s["lines"] = trim(s["lines"])
    return versions


def trim(lines):
    while lines and not lines[0].strip():
        lines = lines[1:]
    while lines and not lines[-1].strip():
        lines = lines[:-1]
    return lines


def is_unreleased(v):
    return v["version"].strip().lower() == "unreleased"


def find(versions, tag):
    want = tag[1:] if tag.startswith("v") else tag
    for v in versions:
        if not is_unreleased(v) and v["version"].lstrip("v") == want:
            return v
    return None


# ---------------------------------------------------------------- one version for a GitHub Release
def unwrap(lines):
    """Join the hard-wrapped lines of paragraphs and list items: GitHub shows every newline of a
    release body as a line break."""
    out = []
    for line in lines:
        starts_block = (not line.strip() or line.startswith("#") or line.lstrip().startswith("![")
                        or re.match(r"^\s*[-*] ", line))
        prev = out[-1] if out else ""
        joinable = prev.strip() and not prev.startswith("#") and not prev.lstrip().startswith("![")
        if not starts_block and joinable:
            out[-1] = prev.rstrip() + " " + line.strip()
        else:
            out.append(line)
    return out


def release_notes(v, repo, tag):
    lines = list(v["intro"])
    for s in v["sections"]:
        lines += ["", "### " + s["title"], ""] + s["lines"]
    text = "\n".join(unwrap(trim(lines))) + "\n"
    if repo:
        raw = f"https://raw.githubusercontent.com/{repo}/{tag}/"
        blob = f"https://github.com/{repo}/blob/{tag}/"

        def fix(m, base):
            url = m.group(2)
            if re.match(r"^[a-z]+:|^#|^/", url):
                return m.group(0)
            return m.group(1) + base + url + ")"
        text = re.sub(r"(!\[[^\]]*\]\()([^)\s]+)\)", lambda m: fix(m, raw), text)
        text = re.sub(r"((?<!!)\[[^\]]+\]\()([^)\s]+)\)", lambda m: fix(m, blob), text)
    return text


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=ROOT, help="the repository (default: the one this script is in)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    n = sub.add_parser("notes")
    n.add_argument("tag")
    n.add_argument("--repo", default="")
    n.add_argument("--require-date", action="store_true", help="fail when the section is not dated")
    sub.add_parser("versions")
    a = ap.parse_args()

    path = os.path.join(a.root, CHANGELOG)
    with open(path, encoding="utf-8") as f:
        versions = parse(f.read())
    if not versions:
        sys.exit(f"{path}: no '## [version]' sections")

    if a.cmd == "versions":
        for v in versions:
            print(v["version"], v["date"])
    else:
        v = find(versions, a.tag)
        if not v:
            sys.exit(f"{CHANGELOG} has no section for {a.tag} ('## [{a.tag.lstrip('v')}] - YYYY-MM-DD')")
        if a.require_date and not re.match(r"^\d{4}-\d{2}-\d{2}$", v["date"]):
            sys.exit(f"{CHANGELOG}: the section of {a.tag} is not dated ('{v['date'] or 'no date'}')")
        sys.stdout.write(release_notes(v, a.repo, a.tag if a.tag.startswith("v") else "v" + a.tag))


if __name__ == "__main__":
    main()
