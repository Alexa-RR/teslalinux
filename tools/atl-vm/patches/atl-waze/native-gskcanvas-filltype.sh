#!/bin/sh
# teslalinux: Canvas.drawPath honours Path.FillType (AOSP/Skia): the stock native drawPath always
# filled with the non-zero winding rule, so every even-odd path (icons with cut-outs: a note inside a
# rounded square, a ring, a pin with a hole; VectorDrawable and Compose vectors with
# android:fillType="evenOdd") came out as a solid blob. The inverse fill types fill everything inside
# the given bounds (the canvas clip) except the path, as Skia does. Strokes ignore the fill type.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
R=src/api-impl-jni/graphics/android_atl_GskCanvas.c
if ! grep -q "native_1drawPathFillType" "$R"; then
cat >> "$R" <<'EOF'

/* teslalinux: drawPath with the path's fill type: 0 WINDING, 1 EVEN_ODD, 2 INVERSE_WINDING,
 * 3 INVERSE_EVEN_ODD (Path.FillType ordinals); the inverse types fill (left, top, right, bottom)
 * minus the path. */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1drawPathFillType(JNIEnv *env, jclass this_class, jlong snapshot_ptr, jlong path_ptr, jlong paint_ptr,
		jint fill_type, jfloat left, jfloat top, jfloat right, jfloat bottom)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	GskPath *path = _PTR(path_ptr);
	struct AndroidPaint *paint = _PTR(paint_ptr);
	GskFillRule rule = (fill_type == 1 || fill_type == 3) ? GSK_FILL_RULE_EVEN_ODD : GSK_FILL_RULE_WINDING;
	gboolean inverse = fill_type >= 2;
	if (paint->use_color_filter)
		gtk_snapshot_push_color_matrix(snapshot, &paint->color_matrix, &paint->color_offset);
	if (paint->is_stroke)
		gtk_snapshot_append_stroke(snapshot, path, paint->gsk_stroke, &paint->color);
	if (paint->is_fill) {
		if (!inverse) {
			gtk_snapshot_append_fill(snapshot, path, rule, &paint->color);
		} else if (right > left && bottom > top) {
			/* the colour over the bounds, masked by the inverted coverage of the path */
			gtk_snapshot_push_mask(snapshot, GSK_MASK_MODE_INVERTED_ALPHA);
			gtk_snapshot_append_fill(snapshot, path, rule, &(GdkRGBA){0, 0, 0, 1});
			gtk_snapshot_pop(snapshot);
			gtk_snapshot_append_color(snapshot, &paint->color, &GRAPHENE_RECT_INIT(left, top, right - left, bottom - top));
			gtk_snapshot_pop(snapshot);
		}
	}
	if (paint->use_color_filter)
		gtk_snapshot_pop(snapshot);
}
EOF
fi
grep -c "native_1drawPathFillType" "$R"
