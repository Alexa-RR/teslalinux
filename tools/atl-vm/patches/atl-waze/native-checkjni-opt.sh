#!/bin/sh
# teslalinux: checked JNI only on request (tl_checkjni_opt). ATL always started ART with -Xcheck:jni,
# which validates every JNI call (extra checks on each transition, GetObjectClass, string/array
# access) and is meant for debugging. Production runs start without it; ATL_CHECK_JNI=1 turns it
# back on for a debugging session. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
M=src/main-executable/main.c
if ! grep -q "tl_checkjni_opt" "$M"; then
python3 - "$M" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='	options[4].optionString = "-Xcheck:jni";\n'
new='	/* tl_checkjni_opt: checked JNI is a debugging aid; the JIT option in its place is ART\'s default */\n	options[4].optionString = getenv("ATL_CHECK_JNI") ? "-Xcheck:jni" : "-Xusejit:true";\n'
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new,1))
PY
fi
grep -c "tl_checkjni_opt" "$M"
