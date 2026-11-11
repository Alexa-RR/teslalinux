#!/bin/sh
# teslalinux: path clipping for android.atl.GskCanvas (clipPath through gtk_snapshot_push_fill,
# clipOutPath through an inverted-alpha mask whose mask child is the filled path) and
# android.graphics.Path.addCircle (gsk_path_builder_add_circle; counter-clockwise circles are added
# through gsk_path_builder_add_reverse_path so the contour direction matches AOSP's Direction).
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
C=src/api-impl-jni/graphics/android_atl_GskCanvas.c
if ! grep -q "native_1clipPath" "$C"; then
cat >> "$C" <<'EOF'

/* ---- teslalinux: path clipping ---------------------------------------------------------------- */

/* clipPath: everything drawn until the matching pop is filled through the path (Canvas.clipPath with
 * Region.Op.INTERSECT, the only intersecting op hardware canvases accept). fill_rule: 0 winding, 1 even-odd. */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1clipPath(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong path_ptr, jint fill_rule)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_push_fill(snapshot, (GskPath *)_PTR(path_ptr), fill_rule ? GSK_FILL_RULE_EVEN_ODD : GSK_FILL_RULE_WINDING);
}

/* clipOutPath (Region.Op.DIFFERENCE): the drawn content is masked by the inverse alpha of the filled
 * path. The mask child is appended and closed here, so the caller still owes exactly one pop. */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1clipOutPath(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong path_ptr, jint fill_rule)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_push_mask(snapshot, GSK_MASK_MODE_INVERTED_ALPHA);
	gtk_snapshot_append_fill(snapshot, (GskPath *)_PTR(path_ptr), fill_rule ? GSK_FILL_RULE_EVEN_ODD : GSK_FILL_RULE_WINDING, &(GdkRGBA){0, 0, 0, 1});
	gtk_snapshot_pop(snapshot);
}
EOF
fi
grep -c "native_1clipPath\|native_1clipOutPath" "$C"

P=src/api-impl-jni/graphics/android_graphics_Path.c
if ! grep -q "native_1add_1circle" "$P"; then
cat >> "$P" <<'EOF'

/* ---- teslalinux: Path.addCircle ---------------------------------------------------------------- */
JNIEXPORT void JNICALL Java_android_graphics_Path_native_1add_1circle(JNIEnv *env, jclass this, jlong builder_ptr, jfloat x, jfloat y, jfloat radius, jboolean clockwise)
{
	GskPathBuilder *builder = _PTR(builder_ptr);
	if (clockwise) {
		gsk_path_builder_add_circle(builder, &GRAPHENE_POINT_INIT(x, y), radius);
	} else {
		GskPathBuilder *circle = gsk_path_builder_new();
		gsk_path_builder_add_circle(circle, &GRAPHENE_POINT_INIT(x, y), radius);
		GskPath *path = gsk_path_builder_free_to_path(circle);
		gsk_path_builder_add_reverse_path(builder, path);
		gsk_path_unref(path);
	}
}
EOF
fi
grep -c "native_1add_1circle" "$P"

# colour-matrix group for drawable tints (Drawable.draw pushes it around its paintable)
if ! grep -q "native_1pushColorMatrix" "$C"; then
cat >> "$C" <<'EOF2'

/* teslalinux: a colour matrix group from a paint's colour filter; closed with native_pop */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1pushColorMatrix(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong paint_ptr)
{
	GdkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	struct AndroidPaint *paint = _PTR(paint_ptr);
	gtk_snapshot_push_color_matrix(snapshot, &paint->color_matrix, &paint->color_offset);
}
EOF2
fi
grep -c "native_1pushColorMatrix" "$C"
