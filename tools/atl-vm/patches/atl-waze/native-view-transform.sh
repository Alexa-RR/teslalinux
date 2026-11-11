#!/bin/sh
# teslalinux: View transforms (scale, rotation, pivot, rotationX/Y + camera, the view animation's
# Transformation matrix) reach GTK. The stock native_layout only offset the allocation by
# translationX/Y, so every scale and rotation of a View was lost. When the View publishes a matrix in
# its field tlLayerMatrix (float[9], android.graphics.Matrix order, everything but left/top, set by
# View.java+transform.frag; null while the view only translates), the child is allocated with
#   translate(left, top) . M
# as HWUI composes it (RenderNode::setViewProperties: position, animation matrix, transform matrix).
# An affine M becomes an exact 2D GSK chain (translate . rotate . skewX . scale, the QR
# decomposition native-gskcanvas-concat2d.sh uses), so GTK stays on its 2D paths; a perspective M
# (rotationX/Y) becomes a 4x4. GTK's picking honours the transform, so touches hit transformed views
# the way Android maps them through the inverse matrix. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
R=src/api-impl-jni/views/android_view_View.c
if ! grep -q "tl_view_layer_transform" "$R"; then
python3 - "$R" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
NL = chr(10)
helper = r'''
/* teslalinux: an android.graphics.Matrix (MSCALE_X, MSKEW_X, MTRANS_X, MSKEW_Y, MSCALE_Y, MTRANS_Y,
 * MPERSP_0, MPERSP_1, MPERSP_2) appended to a GSK transform chain (native-view-transform.sh) */
static GskTransform *tl_view_layer_transform(GskTransform *t, const jfloat *v)
{
	const float a = v[0], c = v[1], tx = v[2], b = v[3], d = v[4], ty = v[5];
	const float p0 = v[6], p1 = v[7], p2 = v[8];
	const float sx = sqrtf(a * a + b * b);
	const float det = a * d - b * c;
	if (p0 != 0.f || p1 != 0.f || p2 != 1.f || sx == 0.f || det == 0.f) {
		graphene_matrix_t m;
		/* graphene, row-vector convention: rows = x axis, y axis, z axis, translation */
		graphene_matrix_init_from_float(&m, (float[]){
			a, b, 0, p0,
			c, d, 0, p1,
			0, 0, 1, 0,
			tx, ty, 0, p2,
		});
		return gsk_transform_matrix(t, &m);
	}
	const float sy = det / sx;
	const float shear = (a * c + b * d) / (sx * sy);
	if (tx != 0.f || ty != 0.f)
		t = gsk_transform_translate(t, &GRAPHENE_POINT_INIT(tx, ty));
	if (b != 0.f || a < 0.f)
		t = gsk_transform_rotate(t, atan2f(b, a) * (180.f / (float)M_PI));
	if (shear != 0.f)
		t = gsk_transform_skew(t, atanf(shear) * (180.f / (float)M_PI), 0.f);
	if (sx != 1.f || sy != 1.f)
		t = gsk_transform_scale(t, sx, sy);
	return t;
}
'''
anchor = "JNIEXPORT void JNICALL Java_android_view_View_native_1layout("
assert s.count(anchor) == 1, "native_layout not found"
i = s.index(anchor)
s = s[:i] + helper.lstrip(NL) + NL + s[i:]
old = '''	if (wrapper->jobj) {
		allocation.x += _GET_FLOAT_FIELD(wrapper->jobj, "translationX");
		allocation.y += _GET_FLOAT_FIELD(wrapper->jobj, "translationY");
	}

	gtk_widget_size_allocate(widget, &allocation, -1);'''
new = '''	if (wrapper->jobj) {
		/* teslalinux: the view's full matrix (scale, rotation, animation) when it has one */
		static jfieldID layer_matrix_field = NULL;
		if (!layer_matrix_field) {
			layer_matrix_field = (*env)->GetFieldID(env, handle_cache.view.class, "tlLayerMatrix", "[F");
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionClear(env);
		}
		jfloatArray layer_matrix = layer_matrix_field ? (*env)->GetObjectField(env, wrapper->jobj, layer_matrix_field) : NULL;
		if (layer_matrix) {
			jfloat v[9];
			(*env)->GetFloatArrayRegion(env, layer_matrix, 0, 9, v);
			(*env)->DeleteLocalRef(env, layer_matrix);
			GskTransform *t = gsk_transform_translate(NULL, &GRAPHENE_POINT_INIT(allocation.x, allocation.y));
			t = tl_view_layer_transform(t, v);
			gtk_widget_allocate(widget, allocation.width, allocation.height, -1, t);
			return;
		}
		allocation.x += _GET_FLOAT_FIELD(wrapper->jobj, "translationX");
		allocation.y += _GET_FLOAT_FIELD(wrapper->jobj, "translationY");
	}

	gtk_widget_size_allocate(widget, &allocation, -1);'''
assert s.count(old) == 1, "native_layout allocation block not found"
s = s.replace(old, new, 1)
if "<math.h>" not in s:
    s = "#include <math.h>" + NL + s
open(p, "w").write(s)
PY
fi
grep -c "tl_view_layer_transform" "$R"
