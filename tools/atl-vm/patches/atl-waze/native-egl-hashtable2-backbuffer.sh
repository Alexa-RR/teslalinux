#!/bin/sh
# teslalinux (runs after native-egl-hashtable.sh, whose glBindFramebuffer it extends): bionic_glBindFramebuffer(0) crashed (NULL->gl_framebuffer) when the surface had no back
# buffer: eglSwapBuffers gives up after 100 ms when the GTK side returns no buffer (a surface whose
# widget is hidden, e.g. the map of a stopped activity). AOSP's dequeueBuffer waits for a buffer;
# do the same with a bound wait, then fall back to the front buffer rather than crash (the surface is
# not visible anyway). Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
E=src/libandroid/egl.c
if ! grep -q "tl_surface_back_buffer" "$E"; then
python3 - "$E" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''void bionic_glBindFramebuffer(GLenum target, GLuint framebuffer)
{
	if (getenv("ATL_DIRECT_EGL") || framebuffer != 0)
		return glBindFramebuffer(target, framebuffer);
	ATLSurface *atl_surface = tl_table_lookup(draw_surface_hashtable, eglGetCurrentContext());
	return glBindFramebuffer(target, atl_surface ? atl_surface->back_buffer->gl_framebuffer : 0);
}'''
new='''/* teslalinux: the framebuffer to render into; waits for a free buffer like AOSP's dequeueBuffer */
static GLuint tl_surface_back_buffer(ATLSurface *atl_surface)
{
	if (!atl_surface->back_buffer && atl_surface->unused_buffers)
		atl_surface->back_buffer = g_async_queue_timeout_pop(atl_surface->unused_buffers, 500000); // 500 ms
	if (atl_surface->back_buffer)
		return atl_surface->back_buffer->gl_framebuffer;
	if (atl_surface->front_buffer) {
		static int warned = 0;
		if (!warned++)
			fprintf(stderr, "[egl] no free buffer for surface %p (its widget is not consuming frames); drawing into the front buffer\\n", (void *)atl_surface);
		return atl_surface->front_buffer->gl_framebuffer;
	}
	return 0;
}

void bionic_glBindFramebuffer(GLenum target, GLuint framebuffer)
{
	if (getenv("ATL_DIRECT_EGL") || framebuffer != 0)
		return glBindFramebuffer(target, framebuffer);
	ATLSurface *atl_surface = tl_table_lookup(draw_surface_hashtable, eglGetCurrentContext());
	return glBindFramebuffer(target, atl_surface ? tl_surface_back_buffer(atl_surface) : 0);
}'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_surface_back_buffer" "$E"
