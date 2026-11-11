#!/usr/bin/env python3
"""teslalinux: bionic_translation sysconf() behaviour for names the host libc lacks.

bionic_sysconf_to_glibc_sysconf() abort()ed on any bionic sysconf name without a host equivalent.
The cache geometry names (_SC_LEVEL1_ICACHE_SIZE .. _SC_LEVEL4_CACHE_LINESIZE, 0x8f..0x9d) are a
glibc extension musl does not define, so their cases are compiled out and Waze's native code, which
asks for _SC_LEVEL1_DCACHE_SIZE (0x92), took the whole process down. glibc reports 0 for cache
parameters it cannot determine; do the same. Any other unknown name is EINVAL (POSIX).

Run from the bionic_translation source root (patch-bionic.sh does).
"""
import re

p = 'libc/libc-sysconf.h'
s = open(p).read()
old = ('\tdefault:\n'
       '\t\tfprintf(stderr, "sysconf constant (%d) is not mapped\\n", name);\n'
       '\t\tabort();\n'
       '\t}\n'
       '\treturn 0xFFFF;\n'
       '}')
new = ('\tdefault:\n'
       '\t\treturn -1; /* teslalinux: no host equivalent, bionic_sysconf() decides */\n'
       '\t}\n'
       '}')
assert old in s, "sysconf default branch not found"
open(p, 'w').write(s.replace(old, new, 1))

p = 'libc/libc.c'
s = open(p).read()
m = re.search(r'long bionic_sysconf\(int name\)\n\{.*?\n\}\n', s, re.S)
assert m, "bionic_sysconf not found"
new = '''long bionic_sysconf(int name)
{
	/* teslalinux: sysconf names map to the host libc where it has them. Cache geometry
	 * (_SC_LEVEL1_ICACHE_SIZE .. _SC_LEVEL4_CACHE_LINESIZE, 0x8f..0x9d) is a glibc extension musl
	 * lacks; glibc itself reports 0 when it cannot determine a value, so do the same (Waze's
	 * native code asks for _SC_LEVEL1_DCACHE_SIZE). Anything else unknown is EINVAL, as POSIX says,
	 * rather than abort()ing the whole process. */
	int host_name = bionic_sysconf_to_glibc_sysconf(name);
	if (host_name == -1) {
		if (name >= 0x008f && name <= 0x009d)
			return 0;
		fprintf(stderr, "sysconf constant (%d) is not mapped\\n", name);
		errno = EINVAL;
		return -1;
	}
	return sysconf(host_name);
}
'''
s = s[:m.start()] + new + s[m.end():]
if '#include <errno.h>' not in s:
    s = '#include <errno.h>\n' + s
open(p, 'w').write(s)
print("bionic sysconf patched")
