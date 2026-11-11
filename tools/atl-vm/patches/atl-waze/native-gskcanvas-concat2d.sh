#!/bin/sh
# teslalinux: Canvas.concat(Matrix) as a 2D transform. The stock native_concat hands the Android
# matrix to GSK as a generic 4x4 (gtk_snapshot_transform_matrix), which GSK classifies as an
# arbitrary 3D transform; the fallback rendering it then takes for the content drew even-odd fills
# with the winding rule (Compose vector groups, which always concat their group matrix, came out
# as solid squares) and forces offscreen rendering for every concat. An Android canvas matrix is
# 2D affine unless its perspective row is set, so it is decomposed exactly into
#   translate(tx, ty) . rotate(theta) . skewX(phi) . scale(sx, sy)
# (QR of [a c; b d]: theta = atan2(b, a), sx = |(a, b)|, sy = det/sx, shear = (a*c + b*d)/(sx*sy)),
# the chain GSK keeps in its 2D category. Matrices with perspective keep the 4x4 path.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
R=src/api-impl-jni/graphics/android_atl_GskCanvas.c
if ! grep -q "tl_snapshot_concat_android" "$R"; then
python3 - "$R" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
NL = chr(10)
helper = r'''
/* teslalinux: an Android canvas matrix (graphene, row-vector convention: rows 0/1 = x/y axes, row 3 =
 * translation, column 3 = perspective) as a 2D GSK transform when it is affine (native-gskcanvas-concat2d.sh) */
static void tl_snapshot_concat_android(GtkSnapshot *snapshot, const graphene_matrix_t *m)
{
	const float a = graphene_matrix_get_value(m, 0, 0), b = graphene_matrix_get_value(m, 0, 1);
	const float c = graphene_matrix_get_value(m, 1, 0), d = graphene_matrix_get_value(m, 1, 1);
	const float tx = graphene_matrix_get_value(m, 3, 0), ty = graphene_matrix_get_value(m, 3, 1);
	const float p0 = graphene_matrix_get_value(m, 0, 3), p1 = graphene_matrix_get_value(m, 1, 3), p2 = graphene_matrix_get_value(m, 3, 3);
	const float sx = sqrtf(a * a + b * b);
	const float det = a * d - b * c;
	if (p0 != 0.f || p1 != 0.f || p2 != 1.f || sx == 0.f || det == 0.f) {
		gtk_snapshot_transform_matrix(snapshot, m);
		return;
	}
	const float sy = det / sx;
	const float shear = (a * c + b * d) / (sx * sy);
	if (tx != 0.f || ty != 0.f)
		gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(tx, ty));
	if (b != 0.f || a < 0.f)
		gtk_snapshot_rotate(snapshot, atan2f(b, a) * (180.f / (float)M_PI));
	if (shear != 0.f) {
		GskTransform *t = gsk_transform_skew(NULL, atanf(shear) * (180.f / (float)M_PI), 0.f);
		gtk_snapshot_transform(snapshot, t);
		gsk_transform_unref(t);
	}
	if (sx != 1.f || sy != 1.f)
		gtk_snapshot_scale(snapshot, sx, sy);
}
'''
anchor = "JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1concat("
assert s.count(anchor) == 1, "native_concat not found"
i = s.index(anchor)
s = s[:i] + helper.lstrip(NL) + NL + s[i:]
j = s.index(anchor)
k = s.index("gtk_snapshot_transform_matrix(snapshot, matrix);", j)
s = s[:k] + "tl_snapshot_concat_android(snapshot, matrix);" + s[k + len("gtk_snapshot_transform_matrix(snapshot, matrix);"):]
if "<math.h>" not in s:
    s = "#include <math.h>" + NL + s
open(p, "w").write(s)
PY
fi
grep -c "tl_snapshot_concat_android" "$R"
