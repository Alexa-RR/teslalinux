#!/bin/sh
# teslalinux: Layout.draw on a canvas that is not ATL's own (Compose draws text through its
# TextAndroidCanvas wrapper, so every Compose text takes this path) drew each Pango line with
# Canvas.drawText(lineText, 0, end) where `end` was Pango's line length in UTF-8 bytes used as a
# count of UTF-16 chars. For ASCII the two agree; for Hebrew, Arabic, Cyrillic, Greek (two bytes per
# char) or CJK (three) each line drew about twice its own text: the first line of a wrapped Hebrew
# place name ran over the rest of the paragraph and the next line repeated its words (Waze's
# "Driving to <place>?" card). It also drew every line at x 0, ignoring the line's offset that Pango
# computes for right-aligned, centred or right-to-left lines, and built each line's string with
# NewStringUTF, which takes modified UTF-8 and mangles characters outside the BMP (emoji).
#
# Now the paragraph becomes one UTF-16 string, and each line is drawn as its exact UTF-16 range
# [start, end) at the line's own logical x, as AOSP's Layout.drawText draws line by line at
# getLineLeft. Runs after native-text-layout.sh (tl_byte_to_u16). Idempotent.
set -eu
F=src/api-impl-jni/text/android_text_Layout.c
if ! grep -q "tl_draw_lines_u16" "$F"; then
python3 - "$F" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = '''	const gchar *text = pango_layout_get_text(pango_layout);
	PangoLayoutIter *pango_iter = pango_layout_get_iter(pango_layout);
	do {
		PangoLayoutLine *pango_line = pango_layout_iter_get_line_readonly(pango_iter);

		jstring text_jstr = (*env)->NewStringUTF(env, text + pango_line->start_index);
		jint end = (*env)->GetStringLength(env, text_jstr);
		if (pango_line->length < end)
			end = pango_line->length;
		jfloat y = (float)pango_layout_iter_get_baseline(pango_iter) / PANGO_SCALE;
		(*env)->CallVoidMethod(env, canvas, handle_cache.canvas.drawText, text_jstr, (jint)0, end, (jfloat)0, y, paint);
		(*env)->DeleteLocalRef(env, text_jstr);
	} while (pango_layout_iter_next_line(pango_iter));
'''
assert s.count(old) == 1, "native_draw_custom_canvas body"
new = '''	const gchar *text = pango_layout_get_text(pango_layout);
	/* tl_draw_lines_u16: the paragraph as one UTF-16 string; each line is its UTF-16 range */
	glong text_u16_len = 0;
	gunichar2 *text_u16 = g_utf8_to_utf16(text, -1, NULL, &text_u16_len, NULL);
	jstring text_jstr = (*env)->NewString(env, text_u16 ? text_u16 : (const gunichar2 *)"", text_u16 ? text_u16_len : 0);
	g_free(text_u16);
	PangoLayoutIter *pango_iter = pango_layout_get_iter(pango_layout);
	do {
		PangoLayoutLine *pango_line = pango_layout_iter_get_line_readonly(pango_iter);
		jint start = tl_byte_to_u16(text, pango_line->start_index);
		jint end = tl_byte_to_u16(text, pango_line->start_index + pango_line->length);
		if (end > start) {
			PangoRectangle logical;
			pango_layout_iter_get_line_extents(pango_iter, NULL, &logical);
			jfloat x = (float)logical.x / PANGO_SCALE;
			jfloat y = (float)pango_layout_iter_get_baseline(pango_iter) / PANGO_SCALE;
			(*env)->CallVoidMethod(env, canvas, handle_cache.canvas.drawText, text_jstr, start, end, x, y, paint);
			if ((*env)->ExceptionCheck(env)) {
				(*env)->ExceptionDescribe(env);
				break;
			}
		}
	} while (pango_layout_iter_next_line(pango_iter));
	(*env)->DeleteLocalRef(env, text_jstr);
'''
s = s.replace(old, new, 1)
open(p, "w").write(s)
PY
fi
grep -c "tl_draw_lines_u16" "$F"
