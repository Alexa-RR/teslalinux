#!/usr/bin/env python3
"""teslalinux: CHANGELOG.md is the one source of the release notes. This script turns it into
  - the car page's Release Notes (web/js/release-notes/release-notes-data.js, the data format of
    the vendored release-notes-controller.js, plain text), and
  - the body of a GitHub Release (one version's section, Markdown, links made absolute).

  changelog.py page [--check]            write the car page's data file (--check: only compare, exit 1
                                         when it is out of date)
  changelog.py notes TAG [--repo OWNER/REPO]
                                         print the section of TAG (v0.2.0 or 0.2.0) for a GitHub
                                         Release: image and file links point at the tag on GitHub
  changelog.py versions                  list the versions and their dates

The CHANGELOG format (Keep a Changelog, newest first):
  ## [Unreleased]                  changes since the last release (on the car page as "Unreleased")
  ## [0.2.0] - 2026-11-12          (or "- Unreleased" until the tag is pushed)
  intro paragraphs, images
  ### A topic                      one card on the car page; the first paragraph, when it is one
  *One-line summary.*              italic line, is the card's subtitle
  text and "- " lists
  ### Platform                     for developers: left out of the car page
  #### Added / Changed / ...
  [0.2.0]: https://...             link definitions at the end
Only the standard library; Python 3.7+.
"""
import argparse
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
CHANGELOG = "CHANGELOG.md"
PAGE_DATA = os.path.join("web", "js", "release-notes", "release-notes-data.js")
# sections for developers, kept out of the car page
PAGE_SKIP = {"platform"}

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


# ---------------------------------------------------------------- Markdown -> plain text (car page)
def inline_text(s):
    s = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", s)                 # images
    s = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", s)             # [text](url)
    s = re.sub(r"\[([^\]]+)\]\[[^\]]*\]", r"\1", s)            # [text][ref]
    s = re.sub(r"`([^`]*)`", r"\1", s)                         # code
    s = re.sub(r"\*\*([^*]+)\*\*", r"\1", s)                   # bold
    s = re.sub(r"(?<![\w*])\*([^*\n]+)\*(?![\w*])", r"\1", s)  # italic
    s = re.sub(r"(?<!\w)_([^_\n]+)_(?!\w)", r"\1", s)
    return re.sub(r"[ \t]+", " ", s).strip()


def blocks(lines):
    """Paragraphs and list items as (kind, depth, text), with wrapped lines joined."""
    out = []
    for line in lines:
        if not line.strip():
            out.append(("blank", 0, ""))
            continue
        if line.lstrip().startswith("!["):
            continue  # images: not on the car page
        m = re.match(r"^(\s*)[-*] (.*)$", line)
        if m:
            out.append(("item", len(m.group(1)) // 2, m.group(2)))
            continue
        h = re.match(r"^#{4,6} (.*)$", line)
        if h:
            out.append(("head", 0, h.group(1)))
            continue
        if out and out[-1][0] in ("para", "item"):  # a wrapped line of the same paragraph or item
            kind, depth, text = out[-1]
            out[-1] = (kind, depth, text + " " + line.strip())
            continue
        out.append(("para", 0, line.strip()))
    return out


def to_text(lines):
    parts = []
    prev = "blank"
    for kind, depth, text in blocks(lines):
        if kind == "blank":
            prev = "blank"
            continue
        t = inline_text(text)
        if not t:
            continue
        if kind == "item":
            line = "  " * depth + "• " + t
            parts.append(("\n" if prev == "item" else "\n\n") + line)
        else:
            parts.append("\n\n" + t)
        prev = kind
    return "".join(parts).strip("\n")


def summary(lines):
    """The card subtitle: a first paragraph that is one italic line; else ''."""
    lines = trim(lines)
    if lines and re.match(r"^\*[^*].*\*$", lines[0].strip()) and (len(lines) == 1 or not lines[1].strip()):
        return inline_text(lines[0]), trim(lines[1:])
    return "", lines


def page_data(versions):
    out = []
    for v in versions:
        items = []
        for s in v["sections"]:
            if s["title"].strip().lower() in PAGE_SKIP:
                continue
            short, rest = summary(s["lines"])
            items.append({"title": inline_text(s["title"]), "shortDescription": short,
                          "descriptionMarkdown": to_text(rest)})
        if not items and v["intro"]:
            items.append({"title": "Overview", "shortDescription": "", "descriptionMarkdown": to_text(v["intro"])})
        if not items:
            continue
        date = v["date"] if re.match(r"^\d{4}-\d{2}-\d{2}$", v["date"]) else ""
        # Keep a Changelog's "## [Unreleased]": the changes since the last release, no version yet
        name = "Unreleased" if is_unreleased(v) else "v" + v["version"].lstrip("v")
        out.append({"versionName": name + (" · " + date if date else ""), "changelogItems": items})
    return {"versions": out}


def render_page(versions):
    data = json.dumps(page_data(versions), indent=2, ensure_ascii=False)
    return ("// Generated from CHANGELOG.md by tools/changelog/changelog.py page. Do not edit by hand:\n"
            "// change CHANGELOG.md and run the script again (tools/debvm/pack-dist.sh checks it).\n"
            "(function (global) {\n"
            "  \"use strict\";\n\n"
            "  const TAHtml = global.TAHtml;\n\n"
            "  TAHtml.releaseNotesData = " + data.replace("\n", "\n  ") + ";\n"
            "})(window);\n")


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
    p = sub.add_parser("page")
    p.add_argument("--check", action="store_true")
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
    elif a.cmd == "page":
        want = render_page(versions)
        target = os.path.join(a.root, PAGE_DATA)
        have = open(target, encoding="utf-8").read() if os.path.exists(target) else ""
        if a.check:
            if have != want:
                sys.exit(f"{PAGE_DATA} is out of date with {CHANGELOG}: run tools/changelog/changelog.py page")
            print(f"{PAGE_DATA} matches {CHANGELOG}")
        elif have != want:
            with open(target, "w", encoding="utf-8", newline="\n") as f:
                f.write(want)
            print(f"wrote {PAGE_DATA}")
        else:
            print(f"{PAGE_DATA} is up to date")
    else:
        v = find(versions, a.tag)
        if not v:
            sys.exit(f"{CHANGELOG} has no section for {a.tag} ('## [{a.tag.lstrip('v')}] - YYYY-MM-DD')")
        if a.require_date and not re.match(r"^\d{4}-\d{2}-\d{2}$", v["date"]):
            sys.exit(f"{CHANGELOG}: the section of {a.tag} is not dated ('{v['date'] or 'no date'}')")
        sys.stdout.write(release_notes(v, a.repo, a.tag if a.tag.startswith("v") else "v" + a.tag))


if __name__ == "__main__":
    main()
