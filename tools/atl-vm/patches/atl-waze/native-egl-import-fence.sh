#!/bin/sh
# teslalinux (runs after native-egl-import-context.sh, whose import context it uses): a SurfaceView
# frame reaches GTK only after the app's GL commands for it have executed.
#
# eglSwapBuffers only flushed (glFlush) before handing the buffer to the main thread, so GTK could
# sample or read back a frame whose drawing was still queued: on a GPU (the Pi's V3D) the commands run
# asynchronously, on llvmpipe the rasterizer threads may still be writing it, and with Mesa's glthread
# the commands may not even have been submitted yet. AOSP's BufferQueue passes an acquire fence with
# every queued buffer, which SurfaceFlinger waits on before using it. Same here:
#  - the swap creates an EGL fence in the app's context (eglCreateSync, EGL_SYNC_FENCE) before the
#    flush, and the buffer carries it;
#  - queue_texture, with the surface's import context current, makes that context wait for it
#    (eglWaitSync: a server-side wait, no CPU block; eglClientWaitSync if the server wait is refused)
#    and puts a GL fence of its own after it, which the GdkGLTexture carries
#    (gdk_gl_texture_builder_set_sync): GTK waits on it before drawing or downloading the frame;
#  - destroy_texture deletes that GL fence when GTK has released the frame.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
E=src/libandroid/egl.c
grep -q "tl_import_context" "$E" || { echo "native-egl-import-fence.sh: run native-egl-import-context.sh first" >&2; exit 1; }
if ! grep -q "tl_frame_fence" "$E"; then
python3 - "$E" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()

def rep(old, new):
    global s
    assert s.count(old) == 1, (old[:80], s.count(old))
    s = s.replace(old, new, 1)

# per buffer: the app's fence and GTK's
rep('''		uint32_t gl_texture;
		uint32_t gl_framebuffer;
	} buffers[NUM_BUFFERS];''', '''		uint32_t gl_texture;
		uint32_t gl_framebuffer;
		EGLSync tl_frame_fence;          /* native-egl-import-fence.sh: the app's commands for this frame */
		EGLDisplay tl_frame_fence_display;
		void *tl_gtk_sync;               /* the GLsync GTK waits on (import context) */
	} buffers[NUM_BUFFERS];''')

# GL fence functions (GLES 3 / GL 3.2), resolved once
rep('''/* runs on main thread */
static void destroy_texture(void *data)
{
	struct atl_surface_buffer *buffer = data;''', '''/* native-egl-import-fence.sh: glFenceSync/glDeleteSync of the import context */
static void *(*tl_gl_fence_sync)(unsigned int condition, unsigned int flags);
static void (*tl_gl_delete_sync)(void *sync);
static void tl_frame_fence_procs(void)
{
	if (!tl_gl_fence_sync) {
		tl_gl_fence_sync = (void *(*)(unsigned int, unsigned int))eglGetProcAddress("glFenceSync");
		tl_gl_delete_sync = (void (*)(void *))eglGetProcAddress("glDeleteSync");
	}
}

/* runs on main thread */
static void destroy_texture(void *data)
{
	struct atl_surface_buffer *buffer = data;
	if (buffer->tl_gtk_sync) {
		gboolean current;
		GdkGLContext *previous = tl_import_context_begin(buffer->surface, FALSE, &current);
		if (current && tl_gl_delete_sync)
			tl_gl_delete_sync(buffer->tl_gtk_sync);
		buffer->tl_gtk_sync = NULL;
		tl_import_context_end(previous);
	}''')

# queue_texture: wait for the app's fence in the import context, then a GL fence for GTK
rep('''	atl_surface->surface_view_widget->frame_callback_data = atl_surface->vsync;''', '''	if (buffer->tl_frame_fence || !buffer->tl_gtk_sync) {
		gboolean current;
		GdkGLContext *previous = tl_import_context_begin(atl_surface, FALSE, &current);
		if (buffer->tl_frame_fence) {
			EGLDisplay fence_display = buffer->tl_frame_fence_display;
			if (!current || !eglWaitSync(fence_display, buffer->tl_frame_fence, 0))
				eglClientWaitSync(fence_display, buffer->tl_frame_fence, 0, 100000000); /* 100 ms */
			eglDestroySync(fence_display, buffer->tl_frame_fence);
			buffer->tl_frame_fence = NULL;
		}
		tl_frame_fence_procs();
		if (current && tl_gl_fence_sync && !buffer->tl_gtk_sync) {
			buffer->tl_gtk_sync = tl_gl_fence_sync(0x9117 /* GL_SYNC_GPU_COMMANDS_COMPLETE */, 0);
			glFlush();
		}
		gdk_gl_texture_builder_set_sync(buffer->texture_builder, buffer->tl_gtk_sync);
		tl_import_context_end(previous);
	}
	atl_surface->surface_view_widget->frame_callback_data = atl_surface->vsync;''')

# the swap: a fence after the frame's commands, before the flush
rep('''	ATLSurface *atl_surface = surface;

	glFlush();''', '''	ATLSurface *atl_surface = surface;

	if (atl_surface->back_buffer) { /* native-egl-import-fence.sh */
		struct atl_surface_buffer *b = atl_surface->back_buffer;
		if (b->tl_frame_fence)
			eglDestroySync(b->tl_frame_fence_display, b->tl_frame_fence);
		b->tl_frame_fence_display = eglGetCurrentDisplay();
		b->tl_frame_fence = eglCreateSync(b->tl_frame_fence_display, EGL_SYNC_FENCE, NULL);
		if (b->tl_frame_fence == EGL_NO_SYNC)
			b->tl_frame_fence = NULL;
	}
	glFlush();''')
open(p, 'w').write(s)
PY
fi
grep -c "tl_frame_fence" "$E"
