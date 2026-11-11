/*
 * teslalinux (native-font-files.sh): the font files of android.graphics.fonts.Font, for Pango.
 *
 * AOSP hands every Font's buffer to minikin, which shapes with exactly that file. ATL shapes with
 * Pango, which finds fonts through fontconfig, so a Font's file is added to the UI font map as an
 * application font (pango_font_map_add_font_file: the fontconfig config's application set). A Typeface then
 * names the file's own family and asks for the file's own weight and slant (TlTextAttrs.Face), which
 * selects that file; Pango synthesizes bold or italic only where minikin would fake them.
 *
 * A Font read from an APK or an asset exists only in memory; its bytes go to a content-addressed file
 * under the user cache directory (fontconfig loads fonts from files). A Font built from a file uses
 * that file.
 *
 * Threads: GTK's widgets and ATL's text code use the main thread's font map. A Font registered on
 * the main thread is added at once; one registered on another thread is queued and added the next
 * time the main thread lays out text with a typeface (Font.tlSyncPending).
 *
 * Paint.native_set_face: the family/weight/slant/width of a Paint's typeface on its Pango font
 * description, for Canvas.drawText and the Paint measuring calls.
 */
#include <errno.h>
#include <fontconfig/fontconfig.h>
#include <glib/gstdio.h>
#include <gtk/gtk.h>
#include <jni.h>
#include <pango/pangocairo.h>

#include "../defines.h"
#include "AndroidPaint.h"

extern GtkWidget *window;
extern GThread *main_thread_id;

static GMutex tl_font_lock;
static GPtrArray *tl_font_pending; /* paths queued by other threads (tl_font_lock) */
static GHashTable *tl_font_added;  /* paths in the UI font map (main thread only) */

/* The font map GTK's widgets and ATL's text layouts use. GTK gives widgets the thread-default
 * PangoCairo font map unless one is set; the window's is used when it exists. */
static PangoFontMap *tl_ui_font_map(void)
{
	if (window)
		return pango_context_get_font_map(gtk_widget_get_pango_context(window));
	return pango_cairo_font_map_get_default();
}

/* main thread */
static void tl_font_add(const char *path)
{
	if (!tl_font_added)
		tl_font_added = g_hash_table_new_full(g_str_hash, g_str_equal, g_free, NULL);
	if (g_hash_table_contains(tl_font_added, path))
		return;
	GError *error = NULL;
	if (!pango_font_map_add_font_file(tl_ui_font_map(), path, &error)) {
		g_warning("Font: %s cannot be added to the font map: %s", path, error->message);
		g_error_free(error);
		return;
	}
	g_hash_table_add(tl_font_added, g_strdup(path));
}

/* The file holding the font: its own file, else its bytes in <cache>/atl-fonts/<sha256>.font.
 * Returns a newly allocated path, or NULL. */
static char *tl_font_file(JNIEnv *env, jobject buffer, jstring jpath)
{
	if (jpath) {
		const char *p = (*env)->GetStringUTFChars(env, jpath, NULL);
		char *path = g_file_test(p, G_FILE_TEST_IS_REGULAR) ? g_strdup(p) : NULL;
		(*env)->ReleaseStringUTFChars(env, jpath, p);
		if (path)
			return path;
	}
	if (!buffer)
		return NULL;
	const guint8 *data = (*env)->GetDirectBufferAddress(env, buffer);
	const jlong size = (*env)->GetDirectBufferCapacity(env, buffer);
	if (!data || size <= 0)
		return NULL;
	char *sum = g_compute_checksum_for_data(G_CHECKSUM_SHA256, data, size);
	char *dir = g_build_filename(g_get_user_cache_dir(), "atl-fonts", NULL);
	char *name = g_strconcat(sum, ".font", NULL);
	char *path = g_build_filename(dir, name, NULL);
	g_free(sum);
	g_free(name);
	GStatBuf st;
	if (g_stat(path, &st) != 0 || st.st_size != size) {
		GError *error = NULL;
		if (g_mkdir_with_parents(dir, 0700) != 0 || !g_file_set_contents(path, (const char *)data, size, &error)) {
			g_warning("Font: cannot write %s: %s", path, error ? error->message : g_strerror(errno));
			g_clear_error(&error);
			g_free(path);
			path = NULL;
		}
	}
	g_free(dir);
	return path;
}

/*
 * Registers the font (buffer: a direct ByteBuffer; path: its file, or null; ttc_index: the face in a
 * collection) and returns the family name of that face, or null when the data is not a font
 * fontconfig reads. out[0]: the face's weight (OpenType, 1..1000), out[1]: 1 when it is italic or
 * oblique, out[2]: 1 when the font was queued for the main thread (Font.tlSyncPending adds it).
 */
JNIEXPORT jstring JNICALL Java_android_graphics_fonts_Font_native_1register(JNIEnv *env, jclass clazz, jobject buffer, jstring jpath, jint ttc_index, jintArray out)
{
	char *path = tl_font_file(env, buffer, jpath);
	if (!path)
		return NULL;
	int count = 0;
	FcPattern *pattern = FcFreeTypeQuery((const FcChar8 *)path, ttc_index, NULL, &count);
	FcChar8 *family = NULL;
	if (!pattern || FcPatternGetString(pattern, FC_FAMILY, 0, &family) != FcResultMatch) {
		g_warning("Font: %s (face %d) is not a font fontconfig can read", path, ttc_index);
		if (pattern)
			FcPatternDestroy(pattern);
		g_free(path);
		return NULL;
	}
	double fc_weight = FC_WEIGHT_REGULAR;
	FcPatternGetDouble(pattern, FC_WEIGHT, 0, &fc_weight);
	int slant = FC_SLANT_ROMAN;
	FcPatternGetInteger(pattern, FC_SLANT, 0, &slant);
	jint values[3] = {(jint)(FcWeightToOpenTypeDouble(fc_weight) + 0.5), slant != FC_SLANT_ROMAN, 0};
	jstring result = (*env)->NewStringUTF(env, (const char *)family);
	FcPatternDestroy(pattern);

	if (g_thread_self() == main_thread_id) {
		tl_font_add(path);
		g_free(path);
	} else {
		g_mutex_lock(&tl_font_lock);
		if (!tl_font_pending)
			tl_font_pending = g_ptr_array_new_with_free_func(g_free);
		g_ptr_array_add(tl_font_pending, path);
		g_mutex_unlock(&tl_font_lock);
		values[2] = 1;
	}
	(*env)->SetIntArrayRegion(env, out, 0, 3, values);
	return result;
}

/* main thread: adds the fonts other threads registered */
JNIEXPORT void JNICALL Java_android_graphics_fonts_Font_native_1sync_1pending(JNIEnv *env, jclass clazz)
{
	if (g_thread_self() != main_thread_id)
		return;
	g_mutex_lock(&tl_font_lock);
	GPtrArray *pending = tl_font_pending;
	tl_font_pending = NULL;
	g_mutex_unlock(&tl_font_lock);
	if (!pending)
		return;
	for (guint i = 0; i < pending->len; i++)
		tl_font_add(g_ptr_array_index(pending, i));
	g_ptr_array_unref(pending);
}

/* Paint: the face of the paint's typeface (family null: the default family) */
JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1face(JNIEnv *env, jclass clazz, jlong paint_ptr, jstring jfamily, jint weight, jboolean italic, jint stretch)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (jfamily) {
		const char *family = (*env)->GetStringUTFChars(env, jfamily, NULL);
		pango_font_description_set_family(paint->font, family);
		(*env)->ReleaseStringUTFChars(env, jfamily, family);
	} else {
		pango_font_description_unset_fields(paint->font, PANGO_FONT_MASK_FAMILY);
	}
	pango_font_description_set_weight(paint->font, CLAMP(weight, 1, 1000));
	pango_font_description_set_style(paint->font, italic ? PANGO_STYLE_ITALIC : PANGO_STYLE_NORMAL);
	pango_font_description_set_stretch(paint->font, CLAMP(stretch, PANGO_STRETCH_ULTRA_CONDENSED, PANGO_STRETCH_ULTRA_EXPANDED));
}
