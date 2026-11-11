#!/bin/sh
# teslalinux: android.view.RenderNode clips to an outline path (AOSP 13 HWUI clips any outline): the
# node becomes the fill of the path (GskFillNode) with the path's fill rule. The input node keeps its
# reference, like the other *Keep clip helpers (native-rendernode-clip.sh). Run by build-atl.sh from
# ~/atl-src. Idempotent.
set -eu
R=src/api-impl-jni/graphics/android_view_RenderNode.c
if ! grep -q "nativePathClipKeep" "$R"; then
cat >> "$R" <<'EOF'

/* teslalinux: path clip (native-rendernode-pathclip.sh); the input keeps its reference */
JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativePathClipKeep(JNIEnv *env, jclass clazz, jlong node_ptr, jlong path_ptr, jboolean even_odd)
{
	GskRenderNode *node = _PTR(node_ptr);
	GskPath *path = _PTR(path_ptr);
	if (!node)
		return 0;
	if (!path)
		return _INTPTR(gsk_render_node_ref(node));
	return _INTPTR(gsk_fill_node_new(node, path, even_odd ? GSK_FILL_RULE_EVEN_ODD : GSK_FILL_RULE_WINDING));
}
EOF
fi
grep -c "nativePathClipKeep" "$R"
