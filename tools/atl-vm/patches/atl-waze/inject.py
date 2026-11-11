#!/usr/bin/env python3
"""teslalinux: append members to existing ATL api-impl classes and apply textual replacements.

members/<path/to/Class.java>.frag        : lines starting with "import " are merged after the package
                                           line; everything else is inserted before the file's final "}".
members/<path/to/Class.java>@Inner.frag  : same, but inside the nested class/interface Inner.
replace/<path/to/Class.java>.repl        : blocks "@@@\n<old>\n===\n<new>\n" applied once.
Either kind may carry a tag, Class.java+tag.frag / Class.java@Inner+tag.frag / Class.java+tag.repl:
the same target, kept in its own file (one topic per file); tagged files run after the untagged one.

Each fragment is wrapped in BEGIN/END marker comments keyed by its name, so re-running with a
changed fragment replaces the previous injection instead of duplicating it.
usage: inject.py <api-impl dir> <patch dir>"""
import hashlib, os, re, sys

api, pd = sys.argv[1], sys.argv[2]

def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def write(p, s):
    with open(p, "w", encoding="utf-8", newline="\n") as f: f.write(s)

def strip_old(src, key):
    # removes exactly the text the insertion below adds ("\n" + markers + body + "\n"), so strip +
    # re-insert is byte-identical; replacing with "\n" grew the file by one line per fragment per run
    # (same code, but shifted line numbers and a different jar on every non --reset build)
    pat = re.compile(r"\n\t/\* teslalinux-frag " + re.escape(key) + r" BEGIN \*/.*?/\* teslalinux-frag " + re.escape(key) + r" END \*/\n", re.S)
    return pat.sub("", src)

def nested_end(src, nested):
    """nested may be dotted (Outer.Inner): each part is searched after the previous match."""
    pos = 0
    for part in nested.split("."):
        m = re.search(r"(class|interface|enum)\s+" + re.escape(part) + r"\b[^{;]*\{", src[pos:])
        if not m: return -1
        m_end = pos + m.end()
        pos = m_end
    depth, i = 0, m_end - 1
    while i < len(src):
        if src[i] == "{": depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0: return i
        i += 1
    return -1

def untag(name):
    """Class.java+tag -> Class.java, Class.java@Inner+tag -> Class.java@Inner (the tag only names the file)"""
    head, _, tail = name.rpartition("/")
    return (head + "/" if head else "") + tail.split("+", 1)[0]

def ordered(files, ext):
    # the untagged file of a target first, then its tagged ones by name
    return sorted((f for f in files if f.endswith(ext)), key=lambda f: (untag(f[:-len(ext)]), "+" in f, f))

mdir = os.path.join(pd, "members")
for root, _, files in os.walk(mdir):
    for fn in ordered(files, ".frag"):
        key = os.path.relpath(os.path.join(root, fn), mdir)[:-5].replace("\\", "/")
        target_key = untag(key)
        rel, nested = (target_key.rsplit("@", 1) + [None])[:2] if "@" in target_key else (target_key, None)
        target = os.path.join(api, rel)
        if not os.path.exists(target):
            print(f"[inject] SKIP {key}: target missing"); continue
        frag = read(os.path.join(root, fn))
        imports = [l for l in frag.splitlines() if l.startswith("import ")]
        body = "\n".join(l for l in frag.splitlines() if not l.startswith("import ")).rstrip()
        src = strip_old(read(target), key)
        for imp in imports:
            if imp not in src:
                i = src.find("\n", src.find("package "))
                src = src[:i+1] + imp + "\n" + src[i+1:]
        last = nested_end(src, nested) if nested else src.rstrip().rfind("}")
        if last < 0:
            print(f"[inject] SKIP {key}: nested class not found"); continue
        block = f"\n\t/* teslalinux-frag {key} BEGIN */\n{body}\n\t/* teslalinux-frag {key} END */\n"
        src = src[:last] + block + src[last:]
        write(target, src)
        print(f"[inject] members -> {key}")

rdir = os.path.join(pd, "replace")
for root, _, files in os.walk(rdir):
    # all .repl files of one target (the untagged one, then its tagged ones) act as one block list
    groups = {}
    for fn in ordered(files, ".repl"):
        groups.setdefault(untag(fn[:-5]), []).append(fn)
    for base, fns in groups.items():
        rel = os.path.relpath(os.path.join(root, base), rdir).replace("\\", "/")
        target = os.path.join(api, rel)
        if not os.path.exists(target):
            print(f"[inject] SKIP replace {rel}: target missing"); continue
        parts = [read(os.path.join(root, fn)) for fn in fns]
        repl = parts[0] + "".join(("" if prev.endswith("\n") else "\n") + part for prev, part in zip(parts, parts[1:]))
        # Blocks of one .repl may chain (a later block rewrites part of an earlier block's result),
        # so on an already patched tree neither old nor new of the earlier block is found and the
        # miss warning below would fire for nothing. A trailing marker records the .repl version a
        # file was patched with and the digest of the result. Both unchanged: nothing to do. A .repl
        # may also rewrite text a member fragment inserted, so when the members step re-inserted
        # its fragment (the file differs from the recorded result), the blocks run again.
        sha1 = lambda s: hashlib.sha1(s.encode("utf-8")).hexdigest()
        relp = rel
        mark_re = re.compile(r"\n// teslalinux-repl " + re.escape(relp) + r" ([0-9a-f]{40}) ([0-9a-f]{40})\n")
        src = read(target); n = 0; missed = 0
        m = mark_re.search(src)
        src = mark_re.sub("\n", src).rstrip("\n") + "\n"
        if m and m.group(1) == sha1(repl) and m.group(2) == sha1(src):
            continue
        for block in repl.split("@@@\n")[1:]:
            old, new = block.split("\n===\n", 1)
            new = new.rstrip("\n")
            if new in src:
                continue                      # already applied (keeps replacements idempotent)
            if old in src:
                src = src.replace(old, new, 1); n += 1
            else:
                # a silent miss here cost a day once (Activity lifecycle dispatch): say it loudly
                missed += 1
                print(f"[inject] WARNING: replace anchor not found in {rel}: {old.strip().splitlines()[0][:90]!r}")
        src = src.rstrip("\n") + "\n"
        if not missed:                        # a miss keeps the marker off, so it warns again next run
            src += "// teslalinux-repl %s %s %s\n" % (relp, sha1(repl), sha1(src))
        write(target, src)
        if n:
            print(f"[inject] replace x{n} -> {rel}")
