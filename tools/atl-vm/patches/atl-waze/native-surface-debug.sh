#!/bin/sh
# teslalinux: ATL_DEBUG_SURFACE=1 traces the native window / EGL path of SurfaceViews: when a
# wl_subsurface + wl_egl_window is created for a Surface (size, position) and how often buffers get
# swapped on it. Off by default (one getenv per call site, cached in a static).
set -eu
F=src/libandroid/native_window.c
if ! grep -q "ATL_DEBUG_SURFACE" "$F"; then
python3 - "$F" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''		struct wl_egl_window *egl_window = wl_egl_window_create(wayland_surface, width, height);
'''
new='''		struct wl_egl_window *egl_window = wl_egl_window_create(wayland_surface, width, height);
		if (getenv("ATL_DEBUG_SURFACE"))
			fprintf(stderr, "[surface] ANativeWindow_fromSurface: subsurface %p egl_window %p %dx%d at %d,%d\\n", (void *)subsurface, (void *)egl_window, width, height, (int)pos.x, (int)pos.y);
'''
assert old in s, "wl_egl_window_create line not found"
s=s.replace(old,new,1)
open(p,'w').write(s)
PY
fi
grep -c "ATL_DEBUG_SURFACE" "$F"

E=src/libandroid/egl.c
if ! grep -q "ATL_DEBUG_SURFACE" "$E"; then
python3 - "$E" <<'PY'
import sys, re
p=sys.argv[1]; s=open(p).read()
# the eglSwapBuffers wrapper: add a counter trace to its body
m=re.search(r"\n(EGLBoolean|int|unsigned int) bionic_eglSwapBuffers\([^)]*\)\n\{\n", s)
assert m, "bionic_eglSwapBuffers not found"
ins='''	if (getenv("ATL_DEBUG_SURFACE")) {
		static int swaps = 0;
		if ((++swaps % 50) == 1)
			fprintf(stderr, "[surface] eglSwapBuffers #%d surface=%p\\n", swaps, (void *)surface);
	}
'''
s=s[:m.end()]+ins+s[m.end():]
open(p,'w').write(s)
PY
fi
grep -c "ATL_DEBUG_SURFACE" "$E"

# Frame probe: every 100th swap on an ATL-managed surface, read back two pixels of the frame the app
# just finished (centre and 10,10) so "renders but shows black" can be told apart from "renders black".
if ! grep -q "tl_frame_probe" "$E"; then
python3 - "$E" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
i=s.index("EGLBoolean bionic_eglSwapBuffers(")
key=chr(9)+"glFlush();"+chr(10)
j=s.index(key, i)
old=key
new='''	glFlush();
	if (getenv("ATL_DEBUG_SURFACE")) { /* tl_frame_probe */
		static int probes = 0;
		if ((++probes % 100) == 1 && atl_surface->back_buffer) {
			unsigned char c[4] = {0}, e[4] = {0};
			int w = atl_surface->width, h = atl_surface->height;
			glReadPixels(w / 2, h / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, c);
			glReadPixels(10, 10, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, e);
			fprintf(stderr, "[surface] frame #%d %dx%d fbo=%u centre=%02x%02x%02x%02x edge=%02x%02x%02x%02x glerr=%x\\n", probes, w, h,
			        atl_surface->back_buffer->gl_framebuffer, c[0], c[1], c[2], c[3], e[0], e[1], e[2], e[3], glGetError());
		}
	}
'''
s=s[:j]+new+s[j+len(old):]
open(p,'w').write(s)
PY
fi
grep -c "tl_frame_probe" "$E"

# Widget side: trace texture hand-over and snapshots of the SurfaceView widget.
W=src/api-impl-jni/widgets/android_view_SurfaceView.c
if ! grep -q "tl_widget_probe" "$W"; then
python3 - "$W" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	surface_view_widget->texture = texture;
	surface_view_widget->needs_flip = needs_flip;
	gtk_widget_queue_draw(GTK_WIDGET(surface_view_widget));
'''
new='''	surface_view_widget->texture = texture;
	surface_view_widget->needs_flip = needs_flip;
	if (getenv("ATL_DEBUG_SURFACE")) { /* tl_widget_probe */
		static int sets = 0;
		if ((++sets % 100) == 1)
			fprintf(stderr, "[surface] set_texture #%d widget %p texture %dx%d flip=%d widget %dx%d mapped=%d visible=%d\\n", sets, (void *)surface_view_widget,
			        texture ? gdk_texture_get_width(texture) : -1, texture ? gdk_texture_get_height(texture) : -1, needs_flip,
			        gtk_widget_get_width(GTK_WIDGET(surface_view_widget)), gtk_widget_get_height(GTK_WIDGET(surface_view_widget)),
			        gtk_widget_get_mapped(GTK_WIDGET(surface_view_widget)), gtk_widget_get_visible(GTK_WIDGET(surface_view_widget)));
	}
	gtk_widget_queue_draw(GTK_WIDGET(surface_view_widget));
'''
assert old in s, "set_texture body not found"
s=s.replace(old,new,1)
old2='''	if (surface_view_widget->texture) {
		graphene_rect_t bounds = GRAPHENE_RECT_INIT(0, 0, gtk_widget_get_width(widget), gtk_widget_get_height(widget));
'''
new2='''	if (getenv("ATL_DEBUG_SURFACE")) {
		static int snaps = 0;
		if ((++snaps % 100) == 1)
			fprintf(stderr, "[surface] snapshot #%d widget %p texture=%p %dx%d\\n", snaps, (void *)widget, (void *)surface_view_widget->texture,
			        gtk_widget_get_width(widget), gtk_widget_get_height(widget));
	}
	if (surface_view_widget->texture) {
		graphene_rect_t bounds = GRAPHENE_RECT_INIT(0, 0, gtk_widget_get_width(widget), gtk_widget_get_height(widget));
'''
assert old2 in s, "snapshot body not found"
s=s.replace(old2,new2,1)
if "#include <stdio.h>" not in s:
    s = "#include <stdio.h>\n#include <stdlib.h>\n" + s
open(p,'w').write(s)
PY
fi
grep -c "tl_widget_probe" "$W"
