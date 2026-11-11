#!/usr/bin/env python3
"""teslalinux: art_standalone builds with Debian trixie's GCC 14 (Alpine edge builds it with GCC 15).

One statement in liblog (upstream 66a5d907, Aug 2026, "liblog: add color support") declares a
variable inside an if condition, `if (char *liblog_color = getenv("LIBLOG_COLOR"))`: C2y's
if-declaration, which GCC 15 accepts and GCC 14 cannot parse in any C mode. Hoisting the declaration
in front of the if is the same program (the variable is used nowhere else in the function). The rest
of the tree's C23 use (bool/true/false without <stdbool.h>) is handled by -std=gnu2x in
build-dist.sh. Idempotent; run from the art_standalone source root.
"""
p = 'system/core/liblog/fake_log_device.c'
s = open(p).read()
old = '    if (char *liblog_color = getenv("LIBLOG_COLOR")) {\n'
new = ('    /* teslalinux: declaration hoisted out of the condition for GCC 14 (tools/debvm/patches) */\n'
       '    char *liblog_color = getenv("LIBLOG_COLOR");\n'
       '    if (liblog_color) {\n')
if new in s:
    print("art_standalone-gcc14: already applied")
else:
    assert s.count(old) == 1, "if-declaration in fake_log_device.c not found"
    open(p, 'w').write(s.replace(old, new, 1))
    print("art_standalone-gcc14: fake_log_device.c if-declaration hoisted")
