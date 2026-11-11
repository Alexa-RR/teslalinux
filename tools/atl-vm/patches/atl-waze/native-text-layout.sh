#!/bin/sh
# teslalinux: android.text.Layout on Pango with AOSP offsets and spans (tl_text_layout).
#  - Text goes in as proper UTF-8 decoded from UTF-16 (JNI "modified UTF-8" broke emoji and other
#    supplementary characters), and every offset the Java side sees or passes is a UTF-16 index, as
#    in AOSP: getLineStart/End, getOffsetForHorizontal, getLineForOffset, get{Primary,Secondary}
#    Horizontal used UTF-8 byte indices or code points, so Hebrew/Arabic (2-byte) text, emoji and
#    everything after them got wrong cursor, selection and line offsets (Compose text, links).
#  - tl_text_attr_list(): the Pango attribute list for a text and TextView/Layout's encoded
#    attributes (sizes, colours, faces, spans; see android.text.TlTextAttrs). Shared with
#    android_widget_TextView.c (native-text-textview.sh).
#  - native_set_text_attrs: a layout's spans and paint face as Pango attributes.
#  - native_set_max_lines: an ellipsized layout keeps maxLines lines (Pango's default height of -1
#    ellipsized every paragraph to one line, so Compose's two-line ellipsized texts showed one line).
#  - native_set_alignment: Layout.Alignment as the Pango alignment (it was stored and never applied).
#  - getEllipsisCount/Start from the ellipsis run Pango puts on the line (was 0/1 for every line).
# Must run after native-paint-metrics.sh (it rewrites this file before). Idempotent.
set -eu
L=src/api-impl-jni/text/android_text_Layout.c

if ! grep -q "tl_text_layout" "$L"; then
python3 - "$L" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()

helpers = r'''
/* ---- teslalinux: UTF-16 offsets and Pango attributes (tl_text_layout, native-text-layout.sh) ---- */
#include <string.h>

/* UTF-16 to UTF-8 with surrogate pairs decoded; map (n + 1 entries, may be NULL) gets the byte
 * offset of every UTF-16 unit, map[n] the length */
char *tl_text_utf16_to_utf8(const jchar *s, jsize n, int *map)
{
	char *out = g_malloc((gsize)n * 3 + 1);
	int o = 0;
	for (jsize i = 0; i < n; i++) {
		if (map)
			map[i] = o;
		gunichar c = s[i];
		if (c >= 0xD800 && c < 0xDC00 && i + 1 < n && s[i + 1] >= 0xDC00 && s[i + 1] < 0xE000) {
			c = 0x10000 + ((c - 0xD800) << 10) + (s[i + 1] - 0xDC00);
			o += g_unichar_to_utf8(c, out + o);
			i++;
			if (map)
				map[i] = o;
			continue;
		}
		if ((c >= 0xD800 && c < 0xE000) || c == 0)
			c = 0xFFFD; /* a lone surrogate or NUL has no UTF-8 form; one unit stays one character */
		o += g_unichar_to_utf8(c, out + o);
	}
	if (map)
		map[n] = o;
	out[o] = 0;
	return out;
}

/* UTF-16 index -> byte index in the layout's UTF-8 text (past the end: the length) */
static int tl_u16_to_byte(const char *text, int off)
{
	const char *p = text;
	int u = 0;
	while (*p && u < off) {
		gunichar c = g_utf8_get_char(p);
		u += c >= 0x10000 ? 2 : 1;
		p = g_utf8_next_char(p);
	}
	return p - text;
}

/* byte index -> UTF-16 index */
static int tl_byte_to_u16(const char *text, int idx)
{
	const char *p = text, *end = text + idx;
	int u = 0;
	while (*p && p < end) {
		gunichar c = g_utf8_get_char(p);
		u += c >= 0x10000 ? 2 : 1;
		p = g_utf8_next_char(p);
	}
	return u;
}

static void tl_layout_set_text_utf16(JNIEnv *env, PangoLayout *layout, jstring text)
{
	jsize n = text ? (*env)->GetStringLength(env, text) : 0;
	const jchar *chars = n ? (*env)->GetStringChars(env, text, NULL) : NULL;
	char *utf8 = tl_text_utf16_to_utf8(chars, n, NULL);
	if (chars)
		(*env)->ReleaseStringChars(env, text, chars);
	pango_layout_set_text(layout, utf8, -1);
	g_free(utf8);
}

/* attribute kinds, as android.text.TlTextAttrs encodes them (type, start, end, value); start and
 * end are UTF-16 offsets (end -1: to the end of the text) */
enum {
	TL_A_SIZE = 1,        /* float px */
	TL_A_FG = 2,          /* ARGB */
	TL_A_BG = 3,          /* ARGB */
	TL_A_WEIGHT = 4,      /* 100..1000 */
	TL_A_ITALIC = 5,      /* 0/1 */
	TL_A_UNDERLINE = 6,   /* 0/1 */
	TL_A_STRIKE = 7,      /* 0/1 */
	TL_A_FAMILY = 8,      /* index into strings */
	TL_A_LETTERSP = 9,    /* float px */
	TL_A_RISE = 10,       /* float px, up */
	TL_A_SCALE = 11,      /* float factor */
	TL_A_LINEHEIGHT = 12, /* float px */
	TL_A_FEATURES = 13,   /* index into strings, CSS font-feature-settings syntax */
	TL_A_STRETCH = 14,    /* PangoStretch */
	TL_A_LANGUAGE = 15,   /* index into strings, BCP 47 */
	TL_A_VARIATIONS = 16, /* index into strings, Pango syntax "wght=700,wdth=80" */
};

/* the attribute list for `text` (may be NULL: whole-text attributes only); *utf8_out (may be
 * NULL) receives the text as UTF-8, to be g_free'd */
PangoAttrList *tl_text_attr_list(JNIEnv *env, jstring text, jintArray attrs_arr, jobjectArray strings, char **utf8_out)
{
	jsize n = 0;
	int *map = NULL;
	char *utf8 = NULL;
	if (text) {
		n = (*env)->GetStringLength(env, text);
		const jchar *chars = (*env)->GetStringChars(env, text, NULL);
		map = g_new(int, n + 1);
		utf8 = tl_text_utf16_to_utf8(chars, n, map);
		(*env)->ReleaseStringChars(env, text, chars);
	}
	PangoAttrList *list = pango_attr_list_new();
	jsize count = attrs_arr ? (*env)->GetArrayLength(env, attrs_arr) : 0;
	jint *a = count ? (*env)->GetIntArrayElements(env, attrs_arr, NULL) : NULL;
	jsize nstrings = strings ? (*env)->GetArrayLength(env, strings) : 0;
	for (jsize i = 0; a && i + 3 < count; i += 4) {
		int type = a[i], start = a[i + 1], end = a[i + 2];
		jint value = a[i + 3];
		float f;
		memcpy(&f, &value, sizeof(f));
		guint16 r = ((value >> 16) & 0xff) * 257, g = ((value >> 8) & 0xff) * 257, b = (value & 0xff) * 257;
		int alpha = (value >> 24) & 0xff;
		PangoAttribute *attr = NULL, *attr2 = NULL;
		const char *str = NULL;
		jstring jstr = NULL;
		if ((type == TL_A_FAMILY || type == TL_A_FEATURES || type == TL_A_LANGUAGE || type == TL_A_VARIATIONS)
		    && value >= 0 && value < nstrings) {
			jstr = (*env)->GetObjectArrayElement(env, strings, value);
			if (jstr)
				str = (*env)->GetStringUTFChars(env, jstr, NULL);
		}
		switch (type) {
			case TL_A_SIZE:
				if (f > 0)
					attr = pango_attr_size_new_absolute((int)(f * PANGO_SCALE + 0.5f));
				break;
			case TL_A_FG:
				attr = pango_attr_foreground_new(r, g, b);
				if (alpha != 0xff)
					attr2 = pango_attr_foreground_alpha_new(MAX(1, alpha * 257));
				break;
			case TL_A_BG:
				if (alpha) {
					attr = pango_attr_background_new(r, g, b);
					if (alpha != 0xff)
						attr2 = pango_attr_background_alpha_new(alpha * 257);
				}
				break;
			case TL_A_WEIGHT:
				attr = pango_attr_weight_new(CLAMP(value, 100, 1000));
				break;
			case TL_A_ITALIC:
				attr = pango_attr_style_new(value ? PANGO_STYLE_ITALIC : PANGO_STYLE_NORMAL);
				break;
			case TL_A_UNDERLINE:
				attr = pango_attr_underline_new(value ? PANGO_UNDERLINE_SINGLE : PANGO_UNDERLINE_NONE);
				break;
			case TL_A_STRIKE:
				attr = pango_attr_strikethrough_new(value != 0);
				break;
			case TL_A_FAMILY:
				if (str && *str)
					attr = pango_attr_family_new(str);
				break;
			case TL_A_LETTERSP:
				attr = pango_attr_letter_spacing_new((int)(f * PANGO_SCALE));
				break;
			case TL_A_RISE:
				attr = pango_attr_rise_new((int)(f * PANGO_SCALE));
				break;
			case TL_A_SCALE:
				if (f > 0)
					attr = pango_attr_scale_new(f);
				break;
			case TL_A_LINEHEIGHT:
				if (f > 0)
					attr = pango_attr_line_height_new_absolute((int)(f * PANGO_SCALE + 0.5f));
				break;
			case TL_A_FEATURES:
				if (str && *str)
					attr = pango_attr_font_features_new(str);
				break;
			case TL_A_STRETCH:
				attr = pango_attr_stretch_new((PangoStretch)CLAMP(value, PANGO_STRETCH_ULTRA_CONDENSED, PANGO_STRETCH_ULTRA_EXPANDED));
				break;
			case TL_A_LANGUAGE:
				if (str && *str)
					attr = pango_attr_language_new(pango_language_from_string(str));
				break;
			case TL_A_VARIATIONS:
				if (str && *str) {
					PangoFontDescription *desc = pango_font_description_new();
					pango_font_description_set_variations(desc, str);
					attr = pango_attr_font_desc_new(desc);
					pango_font_description_free(desc);
				}
				break;
		}
		if (str)
			(*env)->ReleaseStringUTFChars(env, jstr, str);
		if (jstr)
			(*env)->DeleteLocalRef(env, jstr);
		guint s_idx = 0, e_idx = PANGO_ATTR_INDEX_TO_TEXT_END;
		if (map) {
			if (start > 0)
				s_idx = map[MIN(start, n)];
			if (end >= 0)
				e_idx = map[MIN(end, n)];
		}
		if (attr) {
			attr->start_index = s_idx;
			attr->end_index = e_idx;
			pango_attr_list_insert(list, attr);
		}
		if (attr2) {
			attr2->start_index = s_idx;
			attr2->end_index = e_idx;
			pango_attr_list_insert(list, attr2);
		}
	}
	if (a)
		(*env)->ReleaseIntArrayElements(env, attrs_arr, a, JNI_ABORT);
	g_free(map);
	if (utf8_out)
		*utf8_out = utf8;
	else
		g_free(utf8);
	return list;
}

'''
anchor = 'JNIEXPORT jlong JNICALL Java_android_text_Layout_native_1constructor('
assert s.count(anchor) == 1
s = s.replace(anchor, helpers + anchor, 1)

def sub(old, new):
    global s
    assert s.count(old) == 1, old
    s = s.replace(old, new, 1)

# constructor: UTF-16 text
sub('''	const char *str = (*env)->GetStringUTFChars(env, text, NULL);
	pango_layout_set_text(layout, str, -1);
	(*env)->ReleaseStringUTFChars(env, text, str);
	pango_layout_set_width(layout, width == -1 ? -1 : width * PANGO_SCALE);''',
'''	tl_layout_set_text_utf16(env, layout, text); /* tl_text_layout */
	pango_layout_set_width(layout, width == -1 ? -1 : width * PANGO_SCALE);''')

# line start / end as UTF-16 offsets
sub('''	int byte_index = pango_layout_line_get_start_index(pango_line);
	return g_utf8_strlen(pango_layout_get_text(pango_layout), byte_index);''',
'''	int byte_index = pango_layout_line_get_start_index(pango_line);
	return tl_byte_to_u16(pango_layout_get_text(pango_layout), byte_index);''')
sub('''	int byte_index = pango_layout_line_get_start_index(pango_line) + pango_layout_line_get_length(pango_line);
	return g_utf8_strlen(pango_layout_get_text(pango_layout), byte_index);''',
'''	int byte_index = pango_layout_line_get_start_index(pango_line) + pango_layout_line_get_length(pango_line);
	return tl_byte_to_u16(pango_layout_get_text(pango_layout), byte_index);''')

# line for offset
sub('''	int line;
	pango_layout_index_to_line_x(pango_layout, offset, FALSE, &line, NULL);
	return line;''',
'''	int line;
	pango_layout_index_to_line_x(pango_layout, tl_u16_to_byte(pango_layout_get_text(pango_layout), offset), FALSE, &line, NULL);
	return line;''')

# primary / secondary horizontal
sub('''	PangoRectangle cursor_rect;
	pango_layout_get_cursor_pos(pango_layout, offset, &cursor_rect, NULL);''',
'''	PangoRectangle cursor_rect;
	pango_layout_get_cursor_pos(pango_layout, tl_u16_to_byte(pango_layout_get_text(pango_layout), offset), &cursor_rect, NULL);''')
sub('''	PangoRectangle cursor_rect;
	pango_layout_get_cursor_pos(pango_layout, offset, NULL, &cursor_rect);''',
'''	PangoRectangle cursor_rect;
	pango_layout_get_cursor_pos(pango_layout, tl_u16_to_byte(pango_layout_get_text(pango_layout), offset), NULL, &cursor_rect);''')

# offset for horizontal: byte index (+ trailing graphemes) -> UTF-16
sub('''	int index;
	pango_layout_line_x_to_index(pango_line, x * PANGO_SCALE, &index, NULL);
	return index;''',
'''	int index, trailing;
	pango_layout_line_x_to_index(pango_line, x * PANGO_SCALE, &index, &trailing);
	const char *text = pango_layout_get_text(pango_layout);
	while (trailing-- > 0 && text[index])
		index = g_utf8_next_char(text + index) - text; /* AOSP: the offset nearest to x */
	return tl_byte_to_u16(text, index);''')

# ellipsis count: the ellipsis run of the line covers the elided text
sub('''	PangoLayout *pango_layout = _PTR(layout);
	return pango_layout_is_ellipsized(pango_layout);''',
'''	PangoLayout *pango_layout = _PTR(layout);
	if (!pango_layout_is_ellipsized(pango_layout))
		return 0;
	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(pango_layout, line);
	const char *text = pango_layout_get_text(pango_layout);
	for (GSList *l = pango_line ? pango_line->runs : NULL; l; l = l->next) {
		PangoGlyphItem *run = l->data;
		if (run && (run->item->analysis.flags & PANGO_ANALYSIS_FLAG_IS_ELLIPSIS))
			return tl_byte_to_u16(text, run->item->offset + run->item->length) - tl_byte_to_u16(text, run->item->offset);
	}
	return 0;''')

s += r'''
/* ---- teslalinux: tl_text_layout natives ---- */
JNIEXPORT jint JNICALL Java_android_text_Layout_native_1get_1ellipsis_1start(JNIEnv *env, jobject object, jlong layout, jint line)
{
	PangoLayout *pango_layout = _PTR(layout);
	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(pango_layout, line);
	if (!pango_line || !pango_layout_is_ellipsized(pango_layout))
		return 0;
	const char *text = pango_layout_get_text(pango_layout);
	for (GSList *l = pango_line->runs; l; l = l->next) {
		PangoGlyphItem *run = l->data;
		if (run && (run->item->analysis.flags & PANGO_ANALYSIS_FLAG_IS_ELLIPSIS))
			return tl_byte_to_u16(text, run->item->offset) - tl_byte_to_u16(text, pango_line->start_index);
	}
	return 0;
}

JNIEXPORT void JNICALL Java_android_text_Layout_native_1set_1text_1attrs(JNIEnv *env, jobject object, jlong layout, jstring text, jintArray attrs, jobjectArray strings)
{
	PangoLayout *pango_layout = _PTR(layout);
	PangoAttrList *list = tl_text_attr_list(env, text, attrs, strings, NULL);
	pango_layout_set_attributes(pango_layout, list);
	pango_attr_list_unref(list);
}

JNIEXPORT void JNICALL Java_android_text_Layout_native_1set_1max_1lines(JNIEnv *env, jobject object, jlong layout, jint lines)
{
	pango_layout_set_height(PANGO_LAYOUT(_PTR(layout)), lines > 0 ? -lines : -1);
}

/* Layout.Alignment ordinal: NORMAL, OPPOSITE, CENTER, LEFT, RIGHT. Pango's LEFT/RIGHT follow each
 * paragraph's direction (auto-dir), which is AOSP's NORMAL/OPPOSITE. */
JNIEXPORT void JNICALL Java_android_text_Layout_native_1set_1alignment(JNIEnv *env, jobject object, jlong layout, jint align)
{
	PangoAlignment a = PANGO_ALIGN_LEFT;
	switch (align) {
		case 1: a = PANGO_ALIGN_RIGHT; break;
		case 2: a = PANGO_ALIGN_CENTER; break;
		case 4: a = PANGO_ALIGN_RIGHT; break;
		default: a = PANGO_ALIGN_LEFT; break;
	}
	pango_layout_set_alignment(PANGO_LAYOUT(_PTR(layout)), a);
}
'''
open(p,'w').write(s)
PY
fi
grep -c "tl_text_layout" "$L"
