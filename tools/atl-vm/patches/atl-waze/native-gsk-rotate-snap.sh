#!/bin/sh
# teslalinux: a rotation within 1e-4 degree of a multiple of 90 degrees is given to GSK as that
# multiple, everywhere api-impl-jni rotates (Canvas.rotate/concat, View and RenderNode transforms,
# ImageView matrices: gtk_snapshot_rotate and gsk_transform_rotate are wrapped in defines.h).
#
# GTK 4.18's GPU (GL) renderer drops a clipped texture rotated by a few millionths of a degree
# (reproduced with plain GTK: clip -> rotate -> texture draws nothing for angles between about 5e-6
# and 1.3e-5 degrees, while 0, 1e-6, 1e-4, 0.5, 30 and 90 draw; the cairo renderer draws them all).
# Apps produce such angles from float matrix math: Waze's compass image matrix is a rotation by
# 1.00179e-5 degrees when heading north, and under the GL renderer (the Pi) the needle vanished.
# The snap is far below what can be seen (1e-4 degree moves a point 4000 px away by 0.007 px).
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
D=src/api-impl-jni/defines.h
if ! grep -q "tl_gsk_angle" "$D"; then
python3 - "$D" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
i = s.rindex("#endif")
block = '''/* teslalinux (native-gsk-rotate-snap.sh): rotations within 1e-4 degree of a multiple of 90 degrees
 * are that multiple (GTK 4.18's GL renderer drops content rotated by a few 1e-6 degrees). The GTK
 * headers come first so their prototypes are not macro-expanded. */
#include <gtk/gtk.h>
#include <math.h>
static inline float tl_gsk_angle(float degrees)
{
	const float quarter = roundf(degrees / 90.0f) * 90.0f;
	return fabsf(degrees - quarter) < 1e-4f ? quarter : degrees;
}
#define gtk_snapshot_rotate(snapshot, angle) (gtk_snapshot_rotate)((snapshot), tl_gsk_angle(angle))
#define gsk_transform_rotate(next, angle) (gsk_transform_rotate)((next), tl_gsk_angle(angle))

'''
s = s[:i] + block + s[i:]
open(p, 'w').write(s)
PY
fi
grep -c "tl_gsk_angle" "$D"
