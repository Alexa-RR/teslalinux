#!/bin/sh
# teslalinux: where android.text.Layout's lines go, as AOSP places them.
#
# 1. An ellipsized line is drawn ellipsized on a custom canvas. Pango ellipsizes the last of maxLines
#    lines, but native_draw_custom_canvas (Compose's path: every line is a Canvas.drawText of the
#    line's UTF-16 range, native-text-lines.sh) drew the whole original range, so the line ran past
#    the layout width: Waze's Hebrew saved-place chip drew "השרון - סוקולוב" 143 px wide in a 125 px
#    box and the clip cut its first word. AOSP draws such a line from the Ellipsizer, the text with the
#    elided range replaced by the ellipsis (TextUtils.ELLIPSIS_NORMAL); the line here is drawn the
#    same way: the kept text before the gap, U+2026, the kept text after it.
# 2. Layout.Alignment.ALIGN_LEFT and ALIGN_RIGHT are absolute in AOSP (ALIGN_NORMAL/OPPOSITE follow the
#    paragraph direction). Pango's LEFT/RIGHT are swapped for a paragraph whose resolved direction is
#    opposite to the context's base direction (auto-dir), so ALIGN_LEFT on Hebrew text came out right
#    aligned. The absolute modes are now mapped through the paragraph direction: a flipped paragraph
#    gets the opposite Pango alignment, which Pango flips back to the asked side. Pango has a single
#    alignment per layout, so the first paragraph's direction decides (a multi-paragraph layout that
#    mixes base directions with an absolute alignment places the other paragraphs by that one).
# 3. getLineLeft/getLineRight are positions in the layout (AOSP: the line's alignment offset
#    included), from the layout iterator like the draw and getLineTop/Bottom use; Pango's per-line
#    extents are relative to the line's own start and were always 0-based.
# Run by build-atl.sh from ~/atl-src after native-text-layout.sh and native-text-lines.sh. Idempotent.
set -eu
F=src/api-impl-jni/text/android_text_Layout.c
if ! grep -q "tl_draw_ellipsis" "$F"; then
python3 - "$F" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()

# 1. ellipsized lines on a custom canvas
old = '''	jstring text_jstr = (*env)->NewString(env, text_u16 ? text_u16 : (const gunichar2 *)"", text_u16 ? text_u16_len : 0);
	g_free(text_u16);
'''
assert s.count(old) == 1, "draw_custom_canvas text"
s = s.replace(old, '''	jstring text_jstr = (*env)->NewString(env, text_u16 ? text_u16 : (const gunichar2 *)"", text_u16 ? text_u16_len : 0);
''', 1)
old = '''			(*env)->CallVoidMethod(env, canvas, handle_cache.canvas.drawText, text_jstr, start, end, x, y, paint);
			if ((*env)->ExceptionCheck(env)) {
				(*env)->ExceptionDescribe(env);
				break;
			}
		}
	} while (pango_layout_iter_next_line(pango_iter));
	(*env)->DeleteLocalRef(env, text_jstr);
'''
assert s.count(old) == 1, "draw_custom_canvas drawText"
new = '''			/* tl_draw_ellipsis: an ellipsized line is the kept text with the gap replaced by the
			 * ellipsis (AOSP draws the Ellipsizer's text); the ellipsis run covers the elided range */
			PangoGlyphItem *ell = NULL;
			for (GSList *l = pango_line->runs; l; l = l->next) {
				PangoGlyphItem *run = l->data;
				if (run && (run->item->analysis.flags & PANGO_ANALYSIS_FLAG_IS_ELLIPSIS)) {
					ell = run;
					break;
				}
			}
			if (ell && text_u16 && end <= text_u16_len) {
				jint es = CLAMP(tl_byte_to_u16(text, ell->item->offset), start, end);
				jint ee = CLAMP(tl_byte_to_u16(text, ell->item->offset + ell->item->length), es, end);
				jsize n = (es - start) + 1 + (end - ee);
				jchar *buf = g_new(jchar, n);
				memcpy(buf, text_u16 + start, (gsize)(es - start) * sizeof(jchar));
				buf[es - start] = 0x2026;
				memcpy(buf + (es - start) + 1, text_u16 + ee, (gsize)(end - ee) * sizeof(jchar));
				jstring line_jstr = (*env)->NewString(env, buf, n);
				g_free(buf);
				(*env)->CallVoidMethod(env, canvas, handle_cache.canvas.drawText, line_jstr, 0, n, x, y, paint);
				(*env)->DeleteLocalRef(env, line_jstr);
			} else {
				(*env)->CallVoidMethod(env, canvas, handle_cache.canvas.drawText, text_jstr, start, end, x, y, paint);
			}
			if ((*env)->ExceptionCheck(env)) {
				(*env)->ExceptionDescribe(env);
				break;
			}
		}
	} while (pango_layout_iter_next_line(pango_iter));
	pango_layout_iter_free(pango_iter);
	(*env)->DeleteLocalRef(env, text_jstr);
	g_free(text_u16);
'''
s = s.replace(old, new, 1)

# 2. absolute alignment
old = '''	PangoAlignment a = PANGO_ALIGN_LEFT;
	switch (align) {
		case 1: a = PANGO_ALIGN_RIGHT; break;
		case 2: a = PANGO_ALIGN_CENTER; break;
		case 4: a = PANGO_ALIGN_RIGHT; break;
		default: a = PANGO_ALIGN_LEFT; break;
	}
	pango_layout_set_alignment(PANGO_LAYOUT(_PTR(layout)), a);
'''
assert s.count(old) == 1, "native_set_alignment"
new = '''	PangoLayout *pango_layout = PANGO_LAYOUT(_PTR(layout));
	/* tl_align_absolute: Pango swaps LEFT/RIGHT for a paragraph whose direction is opposite to the
	 * context's base direction (auto-dir); AOSP's ALIGN_LEFT/RIGHT are absolute, so they are given to
	 * Pango through the (first) paragraph's resolved direction */
	gboolean flipped = FALSE;
	if ((align == 3 || align == 4) && pango_layout_get_auto_dir(pango_layout)) {
		PangoLayoutLine *first = pango_layout_get_line_readonly(pango_layout, 0);
		int line_dir = first ? tl_dir_simple((PangoDirection)first->resolved_dir) : 0;
		int base_dir = tl_dir_simple(pango_context_get_base_dir(pango_layout_get_context(pango_layout)));
		flipped = line_dir != 0 && line_dir == -base_dir;
	}
	PangoAlignment a = PANGO_ALIGN_LEFT;
	switch (align) {
		case 1: a = PANGO_ALIGN_RIGHT; break;
		case 2: a = PANGO_ALIGN_CENTER; break;
		case 3: a = flipped ? PANGO_ALIGN_RIGHT : PANGO_ALIGN_LEFT; break;
		case 4: a = flipped ? PANGO_ALIGN_LEFT : PANGO_ALIGN_RIGHT; break;
		default: a = PANGO_ALIGN_LEFT; break;
	}
	pango_layout_set_alignment(pango_layout, a);
'''
s = s.replace(old, new, 1)
old = '''/* Layout.Alignment ordinal: NORMAL, OPPOSITE, CENTER, LEFT, RIGHT.'''
assert s.count(old) == 1, "alignment comment"
s = s.replace(old, '''/* pango_direction_simple (Pango keeps it private): 1 left to right, -1 right to left, 0 neutral */
static int tl_dir_simple(PangoDirection d)
{
	switch (d) {
		case PANGO_DIRECTION_LTR:
		case PANGO_DIRECTION_WEAK_LTR:
		case PANGO_DIRECTION_TTB_RTL:
			return 1;
		case PANGO_DIRECTION_RTL:
		case PANGO_DIRECTION_WEAK_RTL:
		case PANGO_DIRECTION_TTB_LTR:
			return -1;
		default:
			return 0;
	}
}

''' + old, 1)

# 3. line left/right in layout coordinates
old = '''	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(PANGO_LAYOUT(_PTR(layout)), line);
	PangoRectangle logical_rect;
	pango_layout_line_get_extents(pango_line, &logical_rect, NULL);
	return logical_rect.x / PANGO_SCALE;
'''
assert s.count(old) == 1, "get_line_left"
s = s.replace(old, '''	PangoRectangle logical_rect; /* tl_line_left_layout: in the layout, alignment included */
	tl_line_layout_extents(PANGO_LAYOUT(_PTR(layout)), line, &logical_rect);
	return logical_rect.x / PANGO_SCALE;
''', 1)
old = '''	PangoLayoutLine *pango_line = pango_layout_get_line_readonly(PANGO_LAYOUT(_PTR(layout)), line);
	PangoRectangle logical_rect;
	pango_layout_line_get_extents(pango_line, &logical_rect, NULL);
	return (logical_rect.x + logical_rect.width) / PANGO_SCALE;
'''
assert s.count(old) == 1, "get_line_right"
s = s.replace(old, '''	PangoRectangle logical_rect; /* tl_line_left_layout: in the layout, alignment included */
	tl_line_layout_extents(PANGO_LAYOUT(_PTR(layout)), line, &logical_rect);
	return (logical_rect.x + logical_rect.width) / PANGO_SCALE;
''', 1)
open(p, "w").write(s)
PY
fi
grep -c "tl_draw_ellipsis\|tl_align_absolute\|tl_line_left_layout" "$F"
