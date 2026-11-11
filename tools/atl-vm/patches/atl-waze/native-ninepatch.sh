#!/bin/sh
# teslalinux: NinePatch natives (tl_ninepatch). android.graphics.NinePatch (AOSP shape) sits on ATL's
# NinePatchPaintable: decoding straight from the PNG bytes of an asset stream (no copy to external
# storage), the chunk's size/padding/divs/colours for intrinsic size, padding and the transparent
# region, the npLb/npOl chunks (optical and outline insets, AOSP NinePatchPeeker), drawing at a
# location with alpha, and alpha detection on the texture.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
N=src/api-impl-jni/graphics/android_graphics_drawable_NinePatchDrawable.c
if ! grep -q "tl_ninepatch" "$N"; then
if ! grep -q "#include <gtk/gtk.h>" "$N"; then
	sed -i 's|#include <gdk/gdk.h>|#include <gdk/gdk.h>\n#include <gtk/gtk.h>\n#include <string.h>|' "$N"
fi
cat >> "$N" <<'EOF'

/* ---- teslalinux: android.graphics.NinePatch natives (tl_ninepatch) ---- */

JNIEXPORT jlong JNICALL Java_android_graphics_NinePatch_nativeCreate(JNIEnv *env, jclass class, jbyteArray chunk_array, jlong texture_ptr)
{
	return Java_android_graphics_drawable_NinePatchDrawable_nativeCreate___3BJ(env, NULL, chunk_array, texture_ptr);
}

/* the PNG comes from an APK, which is untrusted data: every chunk walk is bounds-checked */
/* insets_array (11 ints, may be NULL), AOSP NinePatchPeeker: [0] 1 when an npLb or npOl chunk exists,
 * [1..4] optical insets (npLb, layout bounds), [5..8] outline insets, [9] outline radius (float bits),
 * [10] outline alpha (npOl). aapt writes both chunks as host-order int32 (memcpy), as AOSP reads them. */
JNIEXPORT jlong JNICALL Java_android_graphics_NinePatch_nativeCreateFromBytes(JNIEnv *env, jclass class, jbyteArray png_array, jintArray insets_array)
{
	jsize size = (*env)->GetArrayLength(env, png_array);
	if (size < 8)
		return 0;
	uint8_t *png = malloc(size);
	(*env)->GetByteArrayRegion(env, png_array, 0, size, (jbyte *)png);
	GBytes *bytes = g_bytes_new_take(png, size);
	GdkTexture *texture = gdk_texture_new_from_bytes(bytes, NULL);
	if (!texture) {
		g_bytes_unref(bytes);
		return 0;
	}
	struct Res_png_9patch *chunk = NULL;
	uint32_t chunk_size = 0;
	jint insets[11] = {0};
	if (png[0] == 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G') {
		size_t pos = 8;
		while (pos + 8 <= (size_t)size) {
			uint32_t len = ntohl(*(uint32_t *)(png + pos));
			const char *tag = (const char *)png + pos + 4;
			const uint8_t *data = png + pos + 8;
			if ((size_t)len > (size_t)size - pos - 8)
				break;
			if (!strncmp(tag, "npTc", 4) && !chunk) {
				chunk_size = len + 4; /* the file loader keeps the 4 byte checksum too */
				if (chunk_size >= sizeof(struct Res_png_9patch) && chunk_size <= 1024 * 1024 && pos + 8 + chunk_size <= (size_t)size) {
					chunk = malloc(chunk_size);
					memcpy(chunk, data, chunk_size);
				}
			} else if (!strncmp(tag, "npLb", 4) && len == 4 * sizeof(int32_t)) {
				insets[0] = 1;
				memcpy(&insets[1], data, 4 * sizeof(int32_t));
			} else if (!strncmp(tag, "npOl", 4) && len == 24) { /* 4 int32, 1 float, 1 int32 holding a byte */
				int32_t alpha;
				insets[0] = 1;
				memcpy(&insets[5], data, 4 * sizeof(int32_t));
				memcpy(&insets[9], data + 16, sizeof(float));
				memcpy(&alpha, data + 20, sizeof(int32_t));
				insets[10] = alpha & 0xff;
			} else if (!strncmp(tag, "IEND", 4)) {
				break;
			}
			pos += 8 + (size_t)len + 4;
		}
	}
	if (insets_array)
		(*env)->SetIntArrayRegion(env, insets_array, 0, 11, insets);
	g_bytes_unref(bytes);
	GdkPaintable *paintable = chunk ? ninepatch_paintable_new(chunk, chunk_size, texture) : NULL;
	if (!paintable) {
		free(chunk);
		g_object_unref(texture);
		return 0;
	}
	return _INTPTR(paintable);
}

/* out: width, height, padding left/top/right/bottom, numXDivs, numYDivs, numColors */
JNIEXPORT void JNICALL Java_android_graphics_NinePatch_nativeGetInfo(JNIEnv *env, jclass class, jlong paintable_ptr, jintArray out_array)
{
	NinePatchPaintable *p = NINEPATCH_PAINTABLE(_PTR(paintable_ptr));
	struct Res_png_9patch *c = p->chunk;
	jint out[9];
	out[0] = p->width;
	out[1] = p->height;
	if (c->wasDeserialized) {
		out[2] = c->paddingLeft;
		out[3] = c->paddingTop;
		out[4] = c->paddingRight;
		out[5] = c->paddingBottom;
	} else { /* serialized form: network byte order */
		out[2] = ntohl(c->paddingLeft);
		out[3] = ntohl(c->paddingTop);
		out[4] = ntohl(c->paddingRight);
		out[5] = ntohl(c->paddingBottom);
	}
	out[6] = c->numXDivs;
	out[7] = c->numYDivs;
	out[8] = c->numColors;
	(*env)->SetIntArrayRegion(env, out_array, 0, 9, out);
}

/* the divs and colours were converted to host order by ninepatch_paintable_new */
JNIEXPORT void JNICALL Java_android_graphics_NinePatch_nativeGetDivs(JNIEnv *env, jclass class, jlong paintable_ptr, jintArray x_array, jintArray y_array, jintArray color_array)
{
	NinePatchPaintable *p = NINEPATCH_PAINTABLE(_PTR(paintable_ptr));
	struct Res_png_9patch *c = p->chunk;
	(*env)->SetIntArrayRegion(env, x_array, 0, c->numXDivs, (jint *)((void *)c + c->xDivsOffset));
	(*env)->SetIntArrayRegion(env, y_array, 0, c->numYDivs, (jint *)((void *)c + c->yDivsOffset));
	(*env)->SetIntArrayRegion(env, color_array, 0, c->numColors, (jint *)((void *)c + c->colorsOffset));
}

JNIEXPORT void JNICALL Java_android_graphics_NinePatch_nativeDraw(JNIEnv *env, jclass class, jlong paintable_ptr, jlong snapshot_ptr, jfloat x, jfloat y, jfloat width, jfloat height, jfloat alpha)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	gtk_snapshot_save(snapshot);
	if (alpha < 1.0f)
		gtk_snapshot_push_opacity(snapshot, alpha);
	gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(x, y));
	gdk_paintable_snapshot(GDK_PAINTABLE(_PTR(paintable_ptr)), GDK_SNAPSHOT(snapshot), width, height);
	if (alpha < 1.0f)
		gtk_snapshot_pop(snapshot);
	gtk_snapshot_restore(snapshot);
}

JNIEXPORT jboolean JNICALL Java_android_graphics_NinePatch_nativeHasAlpha(JNIEnv *env, jclass class, jlong paintable_ptr)
{
	NinePatchPaintable *p = NINEPATCH_PAINTABLE(_PTR(paintable_ptr));
	int w = gdk_texture_get_width(p->texture), h = gdk_texture_get_height(p->texture);
	size_t n = (size_t)w * h * 4;
	guchar *data = malloc(n);
	gdk_texture_download(p->texture, data, w * 4); /* CAIRO_FORMAT_ARGB32 in native endian: alpha is byte 3 on little-endian */
	jboolean has = JNI_FALSE;
	for (size_t i = 3; i < n; i += 4) {
		if (data[i] != 0xff) {
			has = JNI_TRUE;
			break;
		}
	}
	free(data);
	return has;
}

JNIEXPORT void JNICALL Java_android_graphics_NinePatch_nativeRef(JNIEnv *env, jclass class, jlong paintable_ptr)
{
	g_object_ref(_PTR(paintable_ptr));
}

JNIEXPORT void JNICALL Java_android_graphics_NinePatch_nativeUnref(JNIEnv *env, jclass class, jlong paintable_ptr)
{
	g_object_unref(_PTR(paintable_ptr));
}
EOF
fi
grep -c "tl_ninepatch" "$N"
