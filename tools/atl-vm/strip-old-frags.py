"""Remove fragment blocks injected by the first (hash-tagged) version of inject.py. Those blocks were
appended just before the class's final brace, so everything from the tag to that brace goes."""
import io, os, re, sys

root = sys.argv[1]; n = 0
for d, _, fs in os.walk(root):
    for f in fs:
        if not f.endswith(".java"):
            continue
        p = os.path.join(d, f)
        s = io.open(p, encoding="utf-8").read()
        m = re.search(r"\n\t/\* teslalinux-frag [0-9a-f]{12} \*/\n", s)
        if not m:
            continue
        last = s.rstrip().rfind("}")
        s = s[:m.start()] + "\n" + s[last:]
        io.open(p, "w", encoding="utf-8", newline="\n").write(s)
        n += 1
print(f"  stripped old fragment blocks from {n} files")
