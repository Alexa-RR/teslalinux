#!/bin/sh
# teslalinux: the layer properties of android.view.RenderNode the way HWUI applies them
# (frameworks/base/libs/hwui/RenderProperties.cpp updateMatrix(), RenderNode::prepareTree/
# RenderNodeDrawable): alpha as an opacity node around the node's content, and one transform
#   M = T(translation + pivot) . [camera: rotateX, rotateY, perspective] . rotateZ . scale . T(-pivot)
# with the camera distance in inches (AOSP RenderNode unit; Skia's camera works at 72 points per
# inch, so the perspective depth is 72 * distance pixels; the default camera sits 8 inches away).
# The stock nativeTransform composes the GskTransform chain in reverse (the scale and rotation then
# act on the translation), which only agrees with HWUI when there is no scale and no rotation.
# Both helpers leave the input node referenced (the new node takes its own reference).
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
R=src/api-impl-jni/graphics/android_view_RenderNode.c
if ! grep -q "nativeOpacityKeep" "$R"; then
cat >> "$R" <<'EOF'

/* teslalinux: layer alpha (HWUI draws the node's content at this opacity); the input keeps its reference */
JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeOpacityKeep(JNIEnv *env, jobject this, jlong node_ptr, jfloat alpha)
{
	GskRenderNode *node = _PTR(node_ptr);
	if (alpha >= 1.f)
		return _INTPTR(gsk_render_node_ref(node));
	return _INTPTR(gsk_opacity_node_new(node, alpha < 0.f ? 0.f : alpha));
}

/* teslalinux: the HWUI layer matrix (RenderProperties::updateMatrix); the input keeps its reference */
JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeLayerTransformKeep(JNIEnv *env, jobject this, jlong node_ptr,
		jfloat scale_x, jfloat scale_y, jfloat translate_x, jfloat translate_y, jfloat rotate_z,
		jfloat rotate_x, jfloat rotate_y, jfloat camera_distance_inches, jfloat pivot_x, jfloat pivot_y)
{
	GskRenderNode *node = _PTR(node_ptr);
	/* GskTransform chains read like CSS: the first operation is the outermost one */
	GskTransform *t = gsk_transform_translate(NULL, &GRAPHENE_POINT_INIT(translate_x + pivot_x, translate_y + pivot_y));
	if (rotate_x != 0.f || rotate_y != 0.f) {
		float inches = camera_distance_inches != 0.f ? fabsf(camera_distance_inches) : 8.f;
		t = gsk_transform_perspective(t, 72.f * inches);
		/* Skia's Sk3DView rotates about +X and -Y in screen coordinates (y points down) */
		t = gsk_transform_rotate_3d(t, rotate_x, graphene_vec3_x_axis());
		t = gsk_transform_rotate_3d(t, -rotate_y, graphene_vec3_y_axis());
	}
	t = gsk_transform_rotate(t, rotate_z);
	t = gsk_transform_scale(t, scale_x, scale_y);
	t = gsk_transform_translate(t, &GRAPHENE_POINT_INIT(-pivot_x, -pivot_y));
	if (!t)
		return _INTPTR(gsk_render_node_ref(node));
	if (gsk_transform_get_category(t) == GSK_TRANSFORM_CATEGORY_IDENTITY) {
		gsk_transform_unref(t);
		return _INTPTR(gsk_render_node_ref(node));
	}
	GskRenderNode *transformed = gsk_transform_node_new(node, t);
	gsk_transform_unref(t);
	return _INTPTR(transformed);
}
EOF
grep -q '<math.h>' "$R" || sed -i '1i #include <math.h>' "$R"
fi
grep -c "nativeOpacityKeep\|nativeLayerTransformKeep" "$R"
