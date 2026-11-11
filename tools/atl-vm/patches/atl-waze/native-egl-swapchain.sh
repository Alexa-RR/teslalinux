#!/bin/sh
# teslalinux: a SurfaceView's EGL surface has four buffers, and glBufferData on Mesa's llvmpipe does not
# wait for the rasterizer.
#
# 1. Four buffers (was three). GTK keeps two frames of a SurfaceView alive at any time: the texture
#    the widget shows and the previous one, which the renderer's last frame (kept for its damage diff)
#    still references until the next render finishes. With three buffers the app's GL thread found no
#    free buffer after every eglSwapBuffers until GTK had rendered the frame it just queued, so it
#    could never draw the next frame while GTK drew this one: the map's frames were serialized with
#    GTK's (measured: ~220 ms of every 1.4 s of panning blocked in eglSwapBuffers). AOSP's BufferQueue
#    lets a SurfaceView's producer draw one frame ahead while the consumer holds its buffers; the
#    fourth buffer gives the same. The one-frame queue limit stays (the vsync token in
#    eglSwapBuffers), so the app is never more than one frame ahead of the display.
#
# 2. bionic_glBufferData (resolved for the app's glBufferData by bionic_translation's bionic_ lookup,
#    and returned by eglGetProcAddress). Re-specifying a whole buffer with the same size, which GL
#    engines do every frame (Waze's map: Godot's uniform and vertex buffers), is done by Mesa as an
#    upload into the existing storage marked "discard whole resource"; a GPU driver gives the buffer
#    new storage then, but llvmpipe (the test VM's software GL) has no renaming and waits until every
#    queued draw has been rasterized (measured: ~90 such stalls a second while panning the map). The
#    GL orphaning idiom, glBufferData(NULL) and then glBufferSubData, has llvmpipe allocate new storage
#    instead; the buffer's contents and every GL-visible result are the same. Only on llvmpipe; GPU
#    drivers get the call unchanged.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
E=src/libandroid/egl.c
if grep -q "^#define NUM_BUFFERS 3$" "$E"; then
	sed -i 's/^#define NUM_BUFFERS 3$/#define NUM_BUFFERS 4 \/* teslalinux: GTK holds two frames, the app draws one ahead (native-egl-swapchain.sh) *\//' "$E"
fi
grep -q "^#define NUM_BUFFERS 4" "$E"

if ! grep -q "tl_gl_is_llvmpipe" "$E"; then
python3 - "$E" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
anchor = "void (*bionic_eglGetProcAddress(char const *procname))(void)\n{\n"
assert s.count(anchor) == 1, s.count(anchor)
helper = '''/* teslalinux (native-egl-swapchain.sh): glBufferData without llvmpipe's rasterizer wait */
static int tl_gl_is_llvmpipe(void)
{
	static int is_llvmpipe = -1;
	if (is_llvmpipe < 0) {
		const char *renderer = (const char *)glGetString(GL_RENDERER);
		if (!renderer)
			return 0; /* no current context: decide on a later call */
		is_llvmpipe = strstr(renderer, "llvmpipe") != NULL;
	}
	return is_llvmpipe;
}

void bionic_glBufferData(GLenum target, GLsizeiptr size, const void *data, GLenum usage)
{
	if (data && size > 0 && tl_gl_is_llvmpipe()) {
		glBufferData(target, size, NULL, usage); /* new storage; the queued draws keep the old one */
		glBufferSubData(target, 0, size, data);
		return;
	}
	glBufferData(target, size, data, usage);
}

'''
s = s.replace(anchor, helper + anchor + '''	if (__unlikely__(!strcmp(procname, "glBufferData")))
		return (void (*)(void))bionic_glBufferData;
''', 1)
open(p, 'w').write(s)
PY
fi
grep -c "tl_gl_is_llvmpipe\|bionic_glBufferData" "$E"
