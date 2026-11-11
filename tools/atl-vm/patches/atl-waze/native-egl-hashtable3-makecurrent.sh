#!/bin/sh
# teslalinux (runs after native-egl-hashtable2-backbuffer.sh, whose tl_surface_back_buffer it uses):
# bionic_eglMakeCurrent waited without bound for a free buffer (tl_makecurrent_nowait). The buffers
# of a surface come back only when the GTK main thread consumes frames; while the app's main thread
# is busy (Waze's map view spins in onStop waiting for its GL thread to pause) nothing consumes, so
# the GL thread blocked in eglMakeCurrent forever and the main thread spun forever: a deadlock that
# cannot happen on Android, where SurfaceFlinger consumes a window's buffers whatever the app's
# main thread does. The make-current now waits the bounded time and falls back to the front buffer,
# like the swap path. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
E=src/libandroid/egl.c
if ! grep -q "tl_makecurrent_nowait" "$E"; then
python3 - "$E" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	if (!atl_surface->back_buffer) {
		atl_surface->back_buffer = g_async_queue_pop(atl_surface->unused_buffers);
	}
	glBindFramebuffer(GL_FRAMEBUFFER, atl_surface->back_buffer->gl_framebuffer);
	return ret;
}
'''
new='''	/* tl_makecurrent_nowait: a bounded wait for a buffer, then the front buffer; never a wait on the
	 * app's own main thread (AOSP's buffer queue is consumed by SurfaceFlinger regardless) */
	glBindFramebuffer(GL_FRAMEBUFFER, tl_surface_back_buffer(atl_surface));
	return ret;
}
'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
anchor='EGLBoolean bionic_eglMakeCurrent('
assert s.count(anchor)==1, s.count(anchor)
s=s.replace(anchor, 'static GLuint tl_surface_back_buffer(ATLSurface *atl_surface); /* tl_makecurrent_nowait */\n'+anchor, 1)
# a hidden window's frames are consumed too (SurfaceFlinger latches the buffers of an invisible
# layer): the frame of an unmapped widget is acknowledged at once instead of waiting for a snapshot
old2='''	surface_view_widget_set_texture(atl_surface->surface_view_widget, gdk_gl_texture_builder_build(buffer->texture_builder, destroy_texture, buffer), TRUE);
	// atl_surface reference will be dropped in destroy_texture
	return G_SOURCE_REMOVE;
'''
new2='''	surface_view_widget_set_texture(atl_surface->surface_view_widget, gdk_gl_texture_builder_build(buffer->texture_builder, destroy_texture, buffer), TRUE);
	if (!gtk_widget_get_mapped(GTK_WIDGET(atl_surface->surface_view_widget)) && atl_surface->surface_view_widget->frame_callback) {
		/* tl_makecurrent_nowait: nobody snapshots a hidden widget; consume the frame like SurfaceFlinger does */
		atl_surface->surface_view_widget->frame_callback(atl_surface->surface_view_widget);
		atl_surface->surface_view_widget->frame_callback = NULL;
	}
	// atl_surface reference will be dropped in destroy_texture
	return G_SOURCE_REMOVE;
'''
assert s.count(old2)==1, s.count(old2)
s=s.replace(old2,new2,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_makecurrent_nowait" "$E"
