#!/usr/bin/env python3
"""check-shot.py SHOT.png PROBES.txt LABEL: compares the probe pixels GfxActivity logged
("GFXPROBE x y rrggbb tolerance case name", window coordinates; the window fills the screen) with
a screenshot. Prints PASS/FAIL per case and "COUNT passed total"."""
import sys
from collections import OrderedDict

from PIL import Image


def main():
    shot, probes, label = sys.argv[1:4]
    img = Image.open(shot).convert("RGB")
    cases = OrderedDict()
    with open(probes, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("GFXINK "):
                # GFXINK x0 y0 x1 y1 left|right name: the dark pixels of the rectangle (text) lie
                # wholly in that half of it
                p = line.strip().split(" ", 6)
                x0, y0, x1, y1, side, name = int(p[1]), int(p[2]), int(p[3]), int(p[4]), p[5], p[6]
                xs = [x for x in range(max(x0, 0), min(x1, img.width - 1) + 1)
                      for y in range(max(y0, 0), min(y1, img.height - 1) + 1, 2)
                      if sum(img.getpixel((x, y))) < 3 * 110]
                mid = (x0 + x1) / 2
                if not xs:
                    cases[name] = "no text pixels in %d,%d-%d,%d" % (x0, y0, x1, y1)
                elif (side == "right" and min(xs) > mid) or (side == "left" and max(xs) < mid):
                    cases[name] = True
                else:
                    cases[name] = "text at x %d-%d, want the %s half of %d-%d" % (min(xs), max(xs), side, x0, x1)
                continue
            parts = line.strip().split(" ", 5)
            if len(parts) < 6 or parts[0] != "GFXPROBE":
                continue
            x, y, rgb, tol, name = int(parts[1]), int(parts[2]), parts[3], int(parts[4]), parts[5]
            want = tuple(int(rgb[i:i + 2], 16) for i in (0, 2, 4))
            got = img.getpixel((x, y)) if 0 <= x < img.width and 0 <= y < img.height else None
            ok = got is not None and all(abs(g - w) <= tol for g, w in zip(got, want))
            err = None if ok else "at %d,%d got %s want %s (tol %d)" % (x, y, got, want, tol)
            prev = cases.get(name)
            if prev is None or prev is True:
                cases[name] = True if ok else err
    passed = 0
    for name, res in cases.items():
        if res is True:
            passed += 1
            print("PASS [%s] screen %s" % (label, name))
        else:
            print("FAIL [%s] screen %s  (%s)" % (label, name, res))
    if not cases:
        print("FAIL [%s] no probes logged" % label)
    print("COUNT %d %d" % (passed, max(len(cases), 1)))


if __name__ == "__main__":
    main()
