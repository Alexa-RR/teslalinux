#!/bin/sh
# teslalinux: real text measurement for android.graphics.Paint on top of Pango (AOSP semantics:
# font metrics, advances per UTF-16 unit with cluster handling, measureText, hasGlyph, letter
# spacing), plus android.text.Layout fixes: line top/bottom are layout-relative like AOSP and the
# paint's letter spacing is applied to the layout. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
H=src/api-impl-jni/graphics/AndroidPaint.h
P=src/api-impl-jni/graphics/android_graphics_Paint.c
L=src/api-impl-jni/text/android_text_Layout.c

if ! grep -q "letter_spacing" "$H"; then
python3 - "$H" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old="	PangoAlignment alignment;"
assert s.count(old)==1
s=s.replace(old, old+chr(10)+"	float letter_spacing; /* teslalinux: em units, as Paint.setLetterSpacing */", 1)
open(p,'w').write(s)
PY
fi

if ! grep -q "tl_paint_layout" "$P"; then
cat >> "$P" <<'EOF'

/* ---- teslalinux: text measurement (native-paint-metrics.sh) ---- */

/* The layout a paint would draw `utf8` with: same context, font and letter spacing as drawing. */
PangoLayout *tl_paint_layout(struct AndroidPaint *paint, const char *utf8, int len)
{
	PangoLayout *layout = pango_layout_new(gtk_widget_get_pango_context(window));
	pango_layout_set_font_description(layout, paint->font);
	tl_paint_apply_attrs(paint, layout);
	pango_layout_set_text(layout, utf8, len);
	return layout;
}

/* Applies paint attributes that are not part of the font description (letter spacing). */
void tl_paint_apply_attrs(struct AndroidPaint *paint, PangoLayout *layout)
{
	if (paint->letter_spacing != 0.0f) {
		PangoAttrList *attrs = pango_attr_list_new();
		double size = pango_font_description_get_size(paint->font); /* absolute size in Pango units */
		pango_attr_list_insert(attrs, pango_attr_letter_spacing_new((int)(paint->letter_spacing * size)));
		pango_layout_set_attributes(layout, attrs);
		pango_attr_list_unref(attrs);
	}
}

/* Converts UTF-16 units to UTF-8, recording the byte offset of every unit (a low surrogate shares
 * its pair's offset); returns a malloc'd string, its length in *out_len. */
static char *tl_utf16_to_utf8(const jchar *chars, int count, int *offsets, int *out_len)
{
	GString *out = g_string_sized_new(count * 2 + 1);
	for (int i = 0; i < count; i++) {
		offsets[i] = out->len;
		gunichar c = chars[i];
		if (c >= 0xD800 && c <= 0xDBFF && i + 1 < count && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
			c = 0x10000 + ((c - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
			g_string_append_unichar(out, c);
			i++;
			offsets[i] = offsets[i - 1];
		} else {
			if (c >= 0xD800 && c <= 0xDFFF)
				c = 0xFFFD;
			g_string_append_unichar(out, c);
		}
	}
	*out_len = out->len;
	return g_string_free(out, FALSE);
}

/* AOSP Paint.getFontMetrics: top, ascent, descent, bottom, leading in pixels (ascent/top negative).
 * Pango exposes ascent, descent and the line height of the font; it has no separate maximum glyph
 * extents, so top/bottom equal ascent/descent and leading is the font's extra line gap. */
JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1get_1font_1metrics(JNIEnv *env, jclass clazz, jlong paint_ptr, jfloatArray out)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	PangoContext *context = gtk_widget_get_pango_context(window);
	PangoFontMetrics *metrics = pango_context_get_metrics(context, paint->font, pango_context_get_language(context));
	float ascent = (float)pango_font_metrics_get_ascent(metrics) / PANGO_SCALE;
	float descent = (float)pango_font_metrics_get_descent(metrics) / PANGO_SCALE;
	float height = (float)pango_font_metrics_get_height(metrics) / PANGO_SCALE;
	pango_font_metrics_unref(metrics);
	float leading = height - ascent - descent;
	if (leading < 0)
		leading = 0;
	jfloat values[5] = { -ascent, -ascent, descent, descent, leading };
	(*env)->SetFloatArrayRegion(env, out, 0, 5, values);
}

/* Advance width of the text (logical extents, including letter spacing), in pixels. */
JNIEXPORT jfloat JNICALL Java_android_graphics_Paint_native_1measure_1text(JNIEnv *env, jclass clazz, jlong paint_ptr, jcharArray text, jint index, jint count)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (count <= 0)
		return 0;
	jchar *chars = (*env)->GetCharArrayElements(env, text, NULL);
	int *offsets = g_new(int, count);
	int len;
	char *utf8 = tl_utf16_to_utf8(chars + index, count, offsets, &len);
	(*env)->ReleaseCharArrayElements(env, text, chars, JNI_ABORT);
	g_free(offsets);
	PangoLayout *layout = tl_paint_layout(paint, utf8, len);
	PangoRectangle logical;
	pango_layout_get_extents(layout, NULL, &logical);
	g_object_unref(layout);
	g_free(utf8);
	return (float)logical.width / PANGO_SCALE;
}

/* AOSP getTextRunAdvances: the advance of every UTF-16 unit of text[index, index+count) into
 * advances[advances_index...]; a cluster's advance goes to its first unit, the others get 0.
 * Returns the total advance. */
JNIEXPORT jfloat JNICALL Java_android_graphics_Paint_native_1get_1text_1advances(JNIEnv *env, jclass clazz, jlong paint_ptr, jcharArray text, jint index, jint count, jfloatArray advances, jint advances_index)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (count <= 0)
		return 0;
	jchar *chars = (*env)->GetCharArrayElements(env, text, NULL);
	int *offsets = g_new(int, count + 1);
	int len;
	char *utf8 = tl_utf16_to_utf8(chars + index, count, offsets, &len);
	offsets[count] = len;
	(*env)->ReleaseCharArrayElements(env, text, chars, JNI_ABORT);
	PangoLayout *layout = tl_paint_layout(paint, utf8, len);
	int n_attrs = 0;
	const PangoLogAttr *attrs = pango_layout_get_log_attrs_readonly(layout, &n_attrs);
	jfloat *out = g_new0(jfloat, count);
	float total = 0;
	int char_index = 0;   /* index of the current byte offset in `attrs` (one entry per character) */
	int prev_offset = -1;
	for (int i = 0; i < count; i++) {
		int offset = offsets[i];
		if (offset == prev_offset)
			continue; /* low surrogate: belongs to the previous unit */
		if (prev_offset >= 0) {
			/* advance char_index over the UTF-8 characters between prev_offset and offset */
			for (const char *p = utf8 + prev_offset; p < utf8 + offset; p = g_utf8_next_char(p))
				char_index++;
		}
		prev_offset = offset;
		if (char_index < n_attrs && !attrs[char_index].is_cursor_position)
			continue; /* inside a grapheme cluster: AOSP gives its advance to the first unit */
		PangoRectangle pos;
		pango_layout_index_to_pos(layout, offset, &pos);
		float advance = (float)ABS(pos.width) / PANGO_SCALE;
		out[i] = advance;
		total += advance;
	}
	if (advances)
		(*env)->SetFloatArrayRegion(env, advances, advances_index, count, out);
	g_free(out);
	g_object_unref(layout);
	g_free(utf8);
	g_free(offsets);
	return total;
}

/* AOSP hasGlyph: true when the font renders the string as a single, known glyph. */
JNIEXPORT jboolean JNICALL Java_android_graphics_Paint_native_1has_1glyph(JNIEnv *env, jclass clazz, jlong paint_ptr, jstring text)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	const char *str = (*env)->GetStringUTFChars(env, text, NULL);
	PangoLayout *layout = tl_paint_layout(paint, str, -1);
	(*env)->ReleaseStringUTFChars(env, text, str);
	jboolean result = JNI_FALSE;
	PangoLayoutLine *line = pango_layout_get_line_readonly(layout, 0);
	if (line && line->runs && !line->runs->next) {
		PangoGlyphString *glyphs = ((PangoGlyphItem *)line->runs->data)->glyphs;
		if (glyphs->num_glyphs == 1 && !(glyphs->glyphs[0].glyph & PANGO_GLYPH_UNKNOWN_FLAG) && glyphs->glyphs[0].glyph != PANGO_GLYPH_EMPTY)
			result = JNI_TRUE;
	}
	g_object_unref(layout);
	return result;
}

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1letter_1spacing(JNIEnv *env, jclass clazz, jlong paint_ptr, jfloat spacing)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	paint->letter_spacing = spacing;
}
EOF
# prototypes near the top so the drawing/layout code can use the helpers
python3 - "$P" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
key="extern GtkWidget *window;"
assert s.count(key)==1
proto="PangoLayout *tl_paint_layout(struct AndroidPaint *paint, const char *utf8, int len);"+chr(10)+"void tl_paint_apply_attrs(struct AndroidPaint *paint, PangoLayout *layout);"+chr(10)
s=s.replace(key, key+chr(10)+proto, 1)
open(p,'w').write(s)
PY
fi

if ! grep -q "tl_paint_apply_attrs" "$L"; then
python3 - "$L" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
# letter spacing of the paint applies to the layout it draws
old='''	pango_layout_set_font_description(layout, android_paint->font);
	const char *str = (*env)->GetStringUTFChars(env, text, NULL);'''
new='''	pango_layout_set_font_description(layout, android_paint->font);
	tl_paint_apply_attrs(android_paint, layout);
	const char *str = (*env)->GetStringUTFChars(env, text, NULL);'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
# AOSP Layout.getLineTop/getLineBottom are relative to the layout's top, not to the line's baseline
old_top='''JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1top(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(PANGO_LAYOUT(_PTR(layout)), line);
	PangoRectangle logical_rect;
	pango_layout_line_get_extents(pango_line, &logical_rect, NULL);
	return (logical_rect.y) / PANGO_SCALE;
}'''
new_top='''static void tl_line_layout_extents(PangoLayout *pango_layout, int line, PangoRectangle *logical_rect)
{
	/* teslalinux: layout-relative logical extents of a line (pango_layout_line_get_extents is baseline-relative) */
	PangoLayoutIter *iter = pango_layout_get_iter(pango_layout);
	while (line-- > 0 && pango_layout_iter_next_line(iter))
		;
	pango_layout_iter_get_line_extents(iter, NULL, logical_rect);
	pango_layout_iter_free(iter);
}

JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1top(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoRectangle logical_rect;
	tl_line_layout_extents(PANGO_LAYOUT(_PTR(layout)), line, &logical_rect);
	return logical_rect.y / PANGO_SCALE;
}'''
assert s.count(old_top)==1, s.count(old_top)
s=s.replace(old_top,new_top,1)
old_bottom='''JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1bottom(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(PANGO_LAYOUT(_PTR(layout)), line);
	PangoRectangle logical_rect;
	pango_layout_line_get_extents(pango_line, &logical_rect, NULL);
	return (logical_rect.y + logical_rect.height) / PANGO_SCALE;
}'''
new_bottom='''JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1line_1bottom(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoRectangle logical_rect;
	tl_line_layout_extents(PANGO_LAYOUT(_PTR(layout)), line, &logical_rect);
	return (logical_rect.y + logical_rect.height) / PANGO_SCALE;
}'''
assert s.count(old_bottom)==1, s.count(old_bottom)
s=s.replace(old_bottom,new_bottom,1)
if "tl_paint_apply_attrs(struct AndroidPaint" not in s:
    key='#include "../generated_headers/android_text_Layout.h"'
    assert s.count(key)==1, s.count(key)
    s=s.replace(key, key+chr(10)+"struct AndroidPaint;"+chr(10)+"void tl_paint_apply_attrs(struct AndroidPaint *paint, PangoLayout *layout);", 1)
open(p,'w').write(s)
PY
fi
grep -c "letter_spacing" "$H"; grep -c "tl_paint_layout" "$P"; grep -c "tl_line_layout_extents" "$L"
