#!/bin/sh
# teslalinux: a Canvas on a Bitmap is drawn in software into a memory texture, with one renderer kept
# for the main thread, as AOSP draws a Bitmap's Canvas with Skia's raster backend.
#
# Bitmap.native_create_texture rendered a Bitmap's recorded drawing on the main thread with a new
# renderer of the onscreen type each time (created, realized, used once, unrealized), on a second
# GdkDisplay it opened for that (gdk_display_open: a second Wayland connection). Under GTK's GL
# renderer (the Pi) every Bitmap drawing therefore set up a GL renderer, and its result was a GL
# texture of that other display: never shared with the window's renderer, so GTK read it back through
# the other display's context at every first use, and once GTK's cache had dropped that display's GL
# device the context could no longer be made current ("gdk_gl_context_make_current() failed") and
# the Bitmap drew empty (Waze's compass needle). The cairo renderer draws the same nodes on the CPU
# into a GdkMemoryTexture, which every renderer uses directly (and getPixels reads without a
# readback); one realized cairo renderer serves the main thread, other threads get their own as before.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
B=src/api-impl-jni/graphics/android_graphics_Bitmap.c
if ! grep -q "tl_bitmap_renderer" "$B"; then
python3 - "$B" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = '''	static GType renderer_type = 0;
	static GdkDisplay *off_screen_display;
	GtkSnapshot *snapshot = _PTR(snapshot_ptr);
	GskRenderNode *node = snapshot ? gtk_snapshot_free_to_node(snapshot) : NULL;
	GdkTexture *texture = NULL;
	if (node) {
		graphene_rect_t bounds = GRAPHENE_RECT_INIT(0, 0, width, height);
		GskRenderer *renderer;
		if (g_thread_self() == main_thread_id) {
			GObject *default_display = G_OBJECT(gdk_display_get_default());
			while (default_display->ref_count < 100)
				g_object_ref(default_display); // workaround for https://gitlab.gnome.org/GNOME/gtk/-/issues/7848
			if (!off_screen_display) {
				off_screen_display = gdk_display_open(NULL);
				// Create and destroy a dummy surface to get the renderer type
				GdkSurface *surface = gdk_surface_new_toplevel(off_screen_display);
				GskRenderer *renderer = gsk_renderer_new_for_surface(surface);
				renderer_type = G_OBJECT_TYPE(renderer);
				gsk_renderer_unrealize(renderer);
				g_object_unref(renderer);
				gdk_surface_destroy(surface);
			}
			renderer = g_object_new(renderer_type, NULL);
			gsk_renderer_realize_for_display(renderer, off_screen_display, NULL);
		} else {
			renderer = gsk_cairo_renderer_new();
			gsk_renderer_realize(renderer, NULL, NULL);
		}
		texture = gsk_renderer_render_texture(renderer, node, &bounds);
		gsk_render_node_unref(node);
		gsk_renderer_unrealize(renderer);
		g_object_unref(renderer);
	} else {'''
new = '''	/* teslalinux (native-bitmap-software-render.sh): software rendering, one renderer for the main thread */
	static GskRenderer *tl_bitmap_renderer;
	GtkSnapshot *snapshot = _PTR(snapshot_ptr);
	GskRenderNode *node = snapshot ? gtk_snapshot_free_to_node(snapshot) : NULL;
	GdkTexture *texture = NULL;
	if (node) {
		graphene_rect_t bounds = GRAPHENE_RECT_INIT(0, 0, width, height);
		GskRenderer *renderer;
		const gboolean main_thread = g_thread_self() == main_thread_id;
		if (main_thread && tl_bitmap_renderer) {
			renderer = tl_bitmap_renderer;
		} else {
			renderer = gsk_cairo_renderer_new();
			gsk_renderer_realize(renderer, NULL, NULL);
			if (main_thread)
				tl_bitmap_renderer = renderer;
		}
		texture = gsk_renderer_render_texture(renderer, node, &bounds);
		gsk_render_node_unref(node);
		if (renderer != tl_bitmap_renderer) {
			gsk_renderer_unrealize(renderer);
			g_object_unref(renderer);
		}
	} else {'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)
open(p, 'w').write(s)
PY
fi
grep -c "tl_bitmap_renderer" "$B"
