#!/bin/sh
# teslalinux: a RenderNode's path outline clips through an alpha mask of the filled path, like
# Canvas.clipPath (native-canvas-mask-clip.sh): a GskFillNode over the node lost content that came
# from GTK widgets (the Views of a Compose AndroidViewHolder). The mask node is the same clip
# (antialiased, the path's fill rule). The input node keeps its reference. Run by build-atl.sh from
# ~/atl-src after native-rendernode-pathclip.sh. Idempotent.
set -eu
R=src/api-impl-jni/graphics/android_view_RenderNode.c
if ! grep -q "tl_path_clip_mask" "$R"; then
python3 - "$R" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = '''	return _INTPTR(gsk_fill_node_new(node, path, even_odd ? GSK_FILL_RULE_EVEN_ODD : GSK_FILL_RULE_WINDING));
'''
assert s.count(old) == 1, "nativePathClipKeep fill node"
new = '''	/* tl_path_clip_mask: the node masked by the alpha of the filled path */
	graphene_rect_t bounds;
	if (!gsk_path_get_bounds(path, &bounds))
		return _INTPTR(gsk_container_node_new(NULL, 0)); /* an empty path clips everything away */
	GskRenderNode *color = gsk_color_node_new(&(GdkRGBA){0, 0, 0, 1}, &bounds);
	GskRenderNode *fill = gsk_fill_node_new(color, path, even_odd ? GSK_FILL_RULE_EVEN_ODD : GSK_FILL_RULE_WINDING);
	GskRenderNode *masked = gsk_mask_node_new(node, fill, GSK_MASK_MODE_ALPHA);
	gsk_render_node_unref(fill);
	gsk_render_node_unref(color);
	return _INTPTR(masked);
'''
s = s.replace(old, new, 1)
open(p, "w").write(s)
PY
fi
grep -c "tl_path_clip_mask" "$R"
