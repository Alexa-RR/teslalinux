#!/bin/sh
# teslalinux: a SurfaceView's buffers are imported into GTK with one realized GL context of its own,
# and GTK's current context is left as it was.
#
# queue_texture created a new GdkGLContext for every buffer it imported and made it current without
# realizing it; atl_surface_dispose did the same to delete the imported textures. When that
# make-current failed ("gdk_gl_context_make_current() failed", seen at every start), the import's GL
# calls ran in whatever context was current on the main thread, which is GTK's renderer context:
# glGenTextures/glBindTexture there broke the GL renderer's cached texture bindings, and dispose's
# glDeleteTextures could delete GTK's own textures (glyph and icon atlases) by number. Under GTK's
# GL renderer (the Pi) the whole window drew wrong: no map, no text, blue fills; the VM's cairo
# renderer never noticed. Each import also left its context current (gdk_gl_context_clear_current
# dropped GTK's) and leaked it.
#
# Now each surface has one GdkGLContext for its imports (atl_surface->tl_import_context), created
# and realized on the first import (errors are reported, and a frame that cannot be imported is
# consumed and dropped, as SurfaceFlinger would drop it); imports and the texture deletion run with
# it current, and the context that was current before is restored afterwards.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
E=src/libandroid/egl.c
if ! grep -q "tl_import_context" "$E"; then
python3 - "$E" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()

def rep(old, new):
    global s
    assert s.count(old) == 1, (old[:80], s.count(old))
    s = s.replace(old, new, 1)

# the per-surface import context
rep('''	gboolean destroyed;
};''', '''	gboolean destroyed;
	GdkGLContext *tl_import_context; /* native-egl-import-context.sh */
};''')

# helpers, before atl_surface_dispose
rep('''static void atl_surface_dispose(GObject *object)
{''', '''/* teslalinux (native-egl-import-context.sh): make the surface's import context current, creating and
 * realizing it on first use; returns the context that was current before (to restore), or sets *ok */
static GdkGLContext *tl_import_context_begin(ATLSurface *atl_surface, gboolean create, gboolean *ok)
{
	GdkGLContext *previous = gdk_gl_context_get_current();
	*ok = FALSE;
	if (!atl_surface->tl_import_context && create) {
		GError *error = NULL;
		GdkSurface *surface = tl_surface_for_widget(GTK_WIDGET(atl_surface->surface_view_widget));
		GdkGLContext *context = surface ? gdk_surface_create_gl_context(surface, &error) : NULL;
		if (context && !gdk_gl_context_realize(context, &error))
			g_clear_object(&context);
		if (!context) {
			static int warned;
			if (!warned++)
				fprintf(stderr, "[egl] no GL context to import SurfaceView frames into GTK: %s\\n", error ? error->message : "no GdkSurface");
			g_clear_error(&error);
			return previous;
		}
		atl_surface->tl_import_context = context;
	}
	if (!atl_surface->tl_import_context)
		return previous;
	gdk_gl_context_make_current(atl_surface->tl_import_context);
	*ok = gdk_gl_context_get_current() == atl_surface->tl_import_context;
	return previous;
}

static void tl_import_context_end(GdkGLContext *previous)
{
	if (previous)
		gdk_gl_context_make_current(previous);
	else
		gdk_gl_context_clear_current();
}

static void atl_surface_dispose(GObject *object)
{''')

# dispose: delete the textures in the import context, then drop it
rep('''	GdkGLContext *gtk_gl_context = gdk_surface_create_gl_context(tl_surface_for_widget(GTK_WIDGET(atl_surface->surface_view_widget)), NULL);
	gdk_gl_context_make_current(gtk_gl_context);
	for (int i = 0; i < NUM_BUFFERS; i++) {
		if (atl_surface->buffers[i].texture_builder) {
			GLuint texture_id = gdk_gl_texture_builder_get_id(atl_surface->buffers[i].texture_builder);
			glDeleteTextures(1, &texture_id);
			g_object_unref(atl_surface->buffers[i].texture_builder);
		}
	}''', '''	gboolean current;
	GdkGLContext *previous = tl_import_context_begin(atl_surface, FALSE, &current);
	for (int i = 0; i < NUM_BUFFERS; i++) {
		if (atl_surface->buffers[i].texture_builder) {
			GLuint texture_id = gdk_gl_texture_builder_get_id(atl_surface->buffers[i].texture_builder);
			if (current) /* never in another context: the number may name one of GTK's textures */
				glDeleteTextures(1, &texture_id);
			g_clear_object(&atl_surface->buffers[i].texture_builder);
		}
	}
	tl_import_context_end(previous);
	g_clear_object(&atl_surface->tl_import_context);''')

# queue_texture: import with the surface's context, restore GTK's
rep('''	if (!buffer->texture_builder) {
		GLuint texture_id;
		GdkGLContext *gtk_gl_context = gdk_surface_create_gl_context(tl_surface_for_widget(GTK_WIDGET(atl_surface->surface_view_widget)), NULL);
		gdk_gl_context_make_current(gtk_gl_context);
		glGenTextures(1, &texture_id);''', '''	if (!buffer->texture_builder) {
		GLuint texture_id;
		gboolean current;
		GdkGLContext *previous = tl_import_context_begin(atl_surface, TRUE, &current);
		if (!current) {
			/* no context to import into: the frame is consumed and dropped (its buffer goes back) */
			tl_import_context_end(previous);
			g_async_queue_push(atl_surface->unused_buffers, buffer);
			g_async_queue_push(atl_surface->vsync, GINT_TO_POINTER(1));
			g_object_unref(atl_surface);
			return G_SOURCE_REMOVE;
		}
		glGenTextures(1, &texture_id);''')
rep('''		gdk_gl_texture_builder_set_context(buffer->texture_builder, gtk_gl_context);''',
    '''		gdk_gl_texture_builder_set_context(buffer->texture_builder, atl_surface->tl_import_context);''')
rep('''		gdk_gl_texture_builder_set_height(buffer->texture_builder, atl_surface->height);
		gdk_gl_context_clear_current();
	}''', '''		gdk_gl_texture_builder_set_height(buffer->texture_builder, atl_surface->height);
		tl_import_context_end(previous);
	}''')
open(p, 'w').write(s)
PY
fi
grep -c "tl_import_context" "$E"
