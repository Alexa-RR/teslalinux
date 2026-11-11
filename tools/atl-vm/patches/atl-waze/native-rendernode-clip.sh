#!/bin/sh
# teslalinux: clip helpers for android.view.RenderNode that leave the input node owned by the caller
# (nativeClip unrefs it). Used for clipToBounds and clipToOutline with rectangular and rounded-rect
# outlines (AOSP/HWUI: only those outline kinds clip). Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
R=src/api-impl-jni/graphics/android_view_RenderNode.c
if ! grep -q "nativeClipKeep" "$R"; then
cat >> "$R" <<'EOF'

/* teslalinux: rectangular clip; the input keeps its reference (the clip node takes its own) */
JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeClipKeep(JNIEnv *env, jobject this, jlong node_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom)
{
	GskRenderNode *node = _PTR(node_ptr);
	return _INTPTR(gsk_clip_node_new(node, &GRAPHENE_RECT_INIT(left, top, right - left, bottom - top)));
}

/* teslalinux: rounded-rectangle clip (one radius for all corners); the input keeps its reference */
JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeRoundedClipKeep(JNIEnv *env, jobject this, jlong node_ptr, jfloat left, jfloat top, jfloat right, jfloat bottom, jfloat radius)
{
	GskRenderNode *node = _PTR(node_ptr);
	GskRoundedRect rect = {
		.bounds = GRAPHENE_RECT_INIT(left, top, right - left, bottom - top),
		.corner = { { radius, radius }, { radius, radius }, { radius, radius }, { radius, radius } },
	};
	return _INTPTR(gsk_rounded_clip_node_new(node, &rect));
}
EOF
fi
grep -c "nativeClipKeep" "$R"
