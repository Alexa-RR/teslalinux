#!/bin/sh
# teslalinux (gfx): canvas layers, transfer modes, shaders, mask filters, PathMeasure and path
# iteration on GSK (native/atl_gfx_canvas.c, native/atl_gfx_path.c), plus two fixes in ATL's C:
#  - RenderNode patch_node() only looked into container, transform and clip nodes, so a child
#    RenderNode recorded under any other node (clipPath = fill node, saveLayerAlpha = opacity node,
#    a colour filter, a rounded clip, a mask from a transfer mode, a blur...) was never patched in
#    and stayed invisible. Every GSK node type with children is now traversed.
#  - Path native_create_path leaked a builder for a never-touched Path.
# Run by build-atl.sh from ~/atl-src; the C files are pushed to ~/atl-native-patches/files/. Idempotent.
set -eu
for f in atl_gfx_canvas.c atl_gfx_path.c; do
  cp "$HOME/atl-native-patches/files/$f" "src/api-impl-jni/graphics/$f"
  if ! grep -q "api-impl-jni/graphics/$f" meson.build; then
    sed -i "s|'src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c',|'src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c',\n\t'src/api-impl-jni/graphics/$f',|" meson.build
  fi
done
grep -c "atl_gfx_" meson.build

R=src/api-impl-jni/graphics/android_view_RenderNode.c
if ! grep -q "teslalinux-gfx: every node type" "$R"; then
python3 - "$R" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
anchor = "\t\tcase GSK_CLIP_NODE: {"
assert anchor in s, "patch_node anchor"
C1 = r"""
#define TL_PATCH1(GET, MAKE) { \
			GskRenderNode *child = patch_node(GET(node), old_child, new_child); \
			new_node = child != GET(node) ? (MAKE) : gsk_render_node_ref(node); \
			gsk_render_node_unref(child); \
			break; \
		}
"""
cases = """		/* teslalinux-gfx: every node type with children, so a child under any of them is patched */
		case GSK_OPACITY_NODE:
			TL_PATCH1(gsk_opacity_node_get_child, gsk_opacity_node_new(child, gsk_opacity_node_get_opacity(node)))
		case GSK_COLOR_MATRIX_NODE:
			TL_PATCH1(gsk_color_matrix_node_get_child, gsk_color_matrix_node_new(child, gsk_color_matrix_node_get_color_matrix(node), gsk_color_matrix_node_get_color_offset(node)))
		case GSK_ROUNDED_CLIP_NODE:
			TL_PATCH1(gsk_rounded_clip_node_get_child, gsk_rounded_clip_node_new(child, gsk_rounded_clip_node_get_clip(node)))
		case GSK_FILL_NODE:
			TL_PATCH1(gsk_fill_node_get_child, gsk_fill_node_new(child, gsk_fill_node_get_path(node), gsk_fill_node_get_fill_rule(node)))
		case GSK_STROKE_NODE:
			TL_PATCH1(gsk_stroke_node_get_child, gsk_stroke_node_new(child, gsk_stroke_node_get_path(node), gsk_stroke_node_get_stroke(node)))
		case GSK_BLUR_NODE:
			TL_PATCH1(gsk_blur_node_get_child, gsk_blur_node_new(child, gsk_blur_node_get_radius(node)))
		case GSK_DEBUG_NODE:
			TL_PATCH1(gsk_debug_node_get_child, gsk_debug_node_new(child, g_strdup(gsk_debug_node_get_message(node))))
		case GSK_REPEAT_NODE: {
			graphene_rect_t rb;
			gsk_render_node_get_bounds(node, &rb);
			TL_PATCH1(gsk_repeat_node_get_child, gsk_repeat_node_new(&rb, child, gsk_repeat_node_get_child_bounds(node)))
		}
		case GSK_SHADOW_NODE: {
			gsize ns = gsk_shadow_node_get_n_shadows(node);
			GskShadow shadows[ns > 0 ? ns : 1];
			for (gsize i = 0; i < ns; i++)
				shadows[i] = *gsk_shadow_node_get_shadow(node, i);
			TL_PATCH1(gsk_shadow_node_get_child, gsk_shadow_node_new(child, shadows, ns))
		}
		case GSK_MASK_NODE: {
			GskRenderNode *src = patch_node(gsk_mask_node_get_source(node), old_child, new_child);
			GskRenderNode *msk = patch_node(gsk_mask_node_get_mask(node), old_child, new_child);
			if (src != gsk_mask_node_get_source(node) || msk != gsk_mask_node_get_mask(node))
				new_node = gsk_mask_node_new(src, msk, gsk_mask_node_get_mask_mode(node));
			else
				new_node = gsk_render_node_ref(node);
			gsk_render_node_unref(src);
			gsk_render_node_unref(msk);
			break;
		}
		case GSK_BLEND_NODE: {
			GskRenderNode *bot = patch_node(gsk_blend_node_get_bottom_child(node), old_child, new_child);
			GskRenderNode *top = patch_node(gsk_blend_node_get_top_child(node), old_child, new_child);
			if (bot != gsk_blend_node_get_bottom_child(node) || top != gsk_blend_node_get_top_child(node))
				new_node = gsk_blend_node_new(bot, top, gsk_blend_node_get_blend_mode(node));
			else
				new_node = gsk_render_node_ref(node);
			gsk_render_node_unref(bot);
			gsk_render_node_unref(top);
			break;
		}
		case GSK_CROSS_FADE_NODE: {
			GskRenderNode *a = patch_node(gsk_cross_fade_node_get_start_child(node), old_child, new_child);
			GskRenderNode *b = patch_node(gsk_cross_fade_node_get_end_child(node), old_child, new_child);
			if (a != gsk_cross_fade_node_get_start_child(node) || b != gsk_cross_fade_node_get_end_child(node))
				new_node = gsk_cross_fade_node_new(a, b, gsk_cross_fade_node_get_progress(node));
			else
				new_node = gsk_render_node_ref(node);
			gsk_render_node_unref(a);
			gsk_render_node_unref(b);
			break;
		}
"""
fn = "static GskRenderNode *patch_node("
assert fn in s, "patch_node definition"
s = s.replace(fn, C1.lstrip("\n") + "\n" + fn, 1)
s = s.replace(anchor, cases + anchor, 1)
open(p, "w").write(s)
PYEOF
fi
grep -c "teslalinux-gfx: every node type" "$R"

P=src/api-impl-jni/graphics/android_graphics_Path.c
if ! grep -q "teslalinux-gfx: empty path" "$P"; then
python3 - "$P" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
old = "\tif (!builder)\n\t\tbuilder = gsk_path_builder_new();\n\treturn _INTPTR(gsk_path_builder_to_path(builder));"
new = "\tif (!builder) /* teslalinux-gfx: empty path without leaking a builder */\n\t\treturn _INTPTR(gsk_path_builder_free_to_path(gsk_path_builder_new()));\n\treturn _INTPTR(gsk_path_builder_to_path(builder));"
assert old in s, "native_create_path anchor"
s = s.replace(old, new, 1)
open(p, "w").write(s)
PYEOF
fi
grep -c "teslalinux-gfx: empty path" "$P"
