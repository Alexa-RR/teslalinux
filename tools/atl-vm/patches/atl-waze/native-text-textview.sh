#!/bin/sh
# teslalinux: AOSP TextView text rendering on the GTK label (tl_text_styled). ATL drew a TextView's
# text with a GtkLabel and only knew its size (in points, so XML sizes in px came out 4/3 too big),
# bold/italic through CSS classes, and Pango markup for Html. This adds:
#  - native_setStyledText: the text plus a Pango attribute list (tl_text_attr_list, native-text-layout.sh) built by TextView from its paint
#    (absolute pixel size, typeface family/weight/style, letter spacing, line spacing, underline and
#    strike-through paint flags) and from the text's spans (colours, sizes, styles, links, super-
#    and subscript, typefaces, any CharacterStyle the app defines). Span ranges are UTF-16 offsets,
#    mapped here onto the UTF-8 bytes Pango indexes; surrogate pairs are decoded (the JNI "modified
#    UTF-8" used before broke emoji). An EditText's GtkText gets the same attributes (no text).
#  - native_setLineConfig: singleLine / maxLines / ellipsize as GtkLabel wrap, lines and ellipsize
#    (wrap mode WORD_CHAR: AOSP breaks a word that does not fit a line on its own).
#  - native_getLinesHeight: height of the first N lines at a width (maxLines/minLines measuring).
#  - native_getLayout: a copy of the label's PangoLayout for TextView.getLayout().
#  - native_setCompoundPadding4: the label's margins keep it clear of the compound drawables.
#  - native_setJavaDraw: the Java draw path only for a TextView that draws compound drawables.
#  - text shadow, EditText hint colour, cursor visibility, max length and password visibility.
# WrapperWidget: TextView's own onDraw/onTouchEvent do not count as overrides (they only matter when
# a TextView has compound drawables or a movement method, and it then asks for the path itself), so
# plain TextViews keep GTK's direct snapshot and no touch controller.
# EditText: GtkText cursor/selection changes reach onSelectionChanged (AOSP Editor does that).
# Magnifier: a GtkWidgetPaintable of the source view, shown zoomed in a popup (tl_magnifier).
# Run by build-atl.sh from ~/atl-src. Idempotent per file.
set -eu
T=src/api-impl-jni/widgets/android_widget_TextView.c
W=src/api-impl-jni/widgets/WrapperWidget.c
E=src/api-impl-jni/widgets/android_widget_EditText.c

if ! grep -q "tl_text_styled" "$T"; then
python3 - "$T" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='	gtk_label_set_wrap(GTK_LABEL(label), TRUE);\n'
new='	gtk_label_set_wrap(GTK_LABEL(label), TRUE);\n	gtk_label_set_wrap_mode(GTK_LABEL(label), PANGO_WRAP_WORD_CHAR); /* tl_text_styled: AOSP breaks overlong words */\n'
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
open(p,'w').write(s)
PY
cat >> "$T" <<'EOF'

/* ---- teslalinux: AOSP text styling on the GTK label (tl_text_styled, native-text-textview.sh) ---- */
#include <string.h>

/* the widget that shows the text: the GtkLabel inside a TextView's box, or an EditText's GtkText */
static GtkWidget *tl_tv_target(GtkWidget *widget)
{
	if (!widget)
		return NULL;
	if (GTK_IS_BOX(widget)) {
		for (GtkWidget *c = gtk_widget_get_first_child(widget); c; c = gtk_widget_get_next_sibling(c)) {
			if (GTK_IS_LABEL(c))
				return c;
		}
		return NULL;
	}
	return widget;
}

/* the Pango attribute list builder lives in android_text_Layout.c (native-text-layout.sh) */
PangoAttrList *tl_text_attr_list(JNIEnv *env, jstring text, jintArray attrs_arr, jobjectArray strings, char **utf8_out);

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setStyledText(JNIEnv *env, jobject this, jlong widget_ptr, jstring text, jintArray attrs_arr, jobjectArray strings)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target)
		return;
	char *utf8 = NULL;
	PangoAttrList *list = tl_text_attr_list(env, text, attrs_arr, strings, &utf8);
	pango_attr_list_insert_before(list, pango_attr_font_features_new("tnum")); /* ATL's default */
	if (GTK_IS_LABEL(target)) {
		GtkLabel *label = GTK_LABEL(target);
		if (utf8)
			atl_safe_gtk_label_set_text(label, utf8); /* also turns markup off */
		gtk_label_set_attributes(label, list);
	} else if (GTK_IS_TEXT(target)) {
		gtk_text_set_attributes(GTK_TEXT(target), list);
	}
	pango_attr_list_unref(list);
	g_free(utf8);
}

/* ellipsize is a PangoEllipsizeMode; TextView passes NONE unless the lines are limited, as AOSP ellipsizes only then */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setLineConfig(JNIEnv *env, jobject this, jlong widget_ptr, jint max_lines, jint ellipsize, jboolean single_line)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target || !GTK_IS_LABEL(target))
		return;
	GtkLabel *label = GTK_LABEL(target);
	gtk_label_set_wrap(label, !single_line);
	gtk_label_set_wrap_mode(label, PANGO_WRAP_WORD_CHAR);
	gtk_label_set_ellipsize(label, (PangoEllipsizeMode)CLAMP(ellipsize, PANGO_ELLIPSIZE_NONE, PANGO_ELLIPSIZE_END));
	gtk_label_set_lines(label, (ellipsize != PANGO_ELLIPSIZE_NONE && !single_line && max_lines > 0) ? max_lines : -1);
}

/* the height of the first `lines` lines of the label's text laid out `width` px wide (unlimited
 * lines, no ellipsis); lines past the end of the text count as high as the last one */
JNIEXPORT jint JNICALL Java_android_widget_TextView_native_1getLinesHeight(JNIEnv *env, jobject this, jlong widget_ptr, jint width, jint lines)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target || !GTK_IS_LABEL(target) || lines <= 0)
		return -1;
	GtkLabel *label = GTK_LABEL(target);
	PangoLayout *layout = pango_layout_copy(gtk_label_get_layout(label));
	pango_layout_set_ellipsize(layout, PANGO_ELLIPSIZE_NONE);
	pango_layout_set_height(layout, -1);
	pango_layout_set_width(layout, (width > 0 && gtk_label_get_wrap(label)) ? width * PANGO_SCALE : -1);
	PangoLayoutIter *iter = pango_layout_get_iter(layout);
	int n = 0, bottom = 0, last = 0;
	do {
		PangoRectangle r;
		pango_layout_iter_get_line_extents(iter, NULL, &r);
		bottom = r.y + r.height;
		last = r.height;
		n++;
	} while (n < lines && pango_layout_iter_next_line(iter));
	pango_layout_iter_free(iter);
	if (n < lines)
		bottom += (lines - n) * last;
	g_object_unref(layout);
	return PANGO_PIXELS_CEIL(bottom);
}

/* a new PangoLayout with what the widget shows, at its allocated width (owned by android.text.Layout) */
JNIEXPORT jlong JNICALL Java_android_widget_TextView_native_1getLayout(JNIEnv *env, jobject this, jlong widget_ptr)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target)
		return 0;
	PangoLayout *layout = NULL;
	if (GTK_IS_LABEL(target)) {
		GtkLabel *label = GTK_LABEL(target);
		layout = pango_layout_copy(gtk_label_get_layout(label));
		int w = gtk_widget_get_width(target);
		if (w > 0 && (gtk_label_get_wrap(label) || gtk_label_get_ellipsize(label) != PANGO_ELLIPSIZE_NONE))
			pango_layout_set_width(layout, w * PANGO_SCALE);
	} else if (GTK_IS_TEXT(target)) {
		layout = gtk_widget_create_pango_layout(target, gtk_editable_get_text(GTK_EDITABLE(target)));
		PangoAttrList *attrs = gtk_text_get_attributes(GTK_TEXT(target));
		if (attrs)
			pango_layout_set_attributes(layout, attrs);
	}
	return _INTPTR(layout);
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setCompoundPadding4(JNIEnv *env, jobject this, jlong widget_ptr, jint left, jint top, jint right, jint bottom)
{
	GtkWidget *child = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!child)
		return;
	gboolean rtl = gtk_widget_get_direction(child) == GTK_TEXT_DIR_RTL;
	gtk_widget_set_margin_start(child, MAX(0, rtl ? right : left));
	gtk_widget_set_margin_end(child, MAX(0, rtl ? left : right));
	gtk_widget_set_margin_top(child, MAX(0, top));
	gtk_widget_set_margin_bottom(child, MAX(0, bottom));
}

/* the wrapper calls View.draw (and so TextView.onDraw, which draws the compound drawables) only
 * while the TextView asks for it; see the tl_textview_java_hooks part in WrapperWidget.c */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setJavaDraw(JNIEnv *env, jobject this, jlong widget_ptr, jboolean enable)
{
	GtkWidget *parent = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr)));
	if (!parent || !WRAPPER_IS_WIDGET(parent))
		return;
	WrapperWidget *wrapper = WRAPPER_WIDGET(parent);
	if (enable) {
		if (!wrapper->draw_method) {
			wrapper->draw_method = _METHOD(_CLASS(this), "draw", "(Landroid/graphics/Canvas;)V");
			if (!wrapper->canvas) {
				jclass canvas_class = (*env)->FindClass(env, "android/atl/GskCanvas");
				jmethodID canvas_constructor = _METHOD(canvas_class, "<init>", "(J)V");
				wrapper->canvas = _REF((*env)->NewObject(env, canvas_class, canvas_constructor, 0));
				(*env)->DeleteLocalRef(env, canvas_class);
			}
			g_object_set_data(G_OBJECT(wrapper), "tl-textview-java-draw", GINT_TO_POINTER(1));
		}
	} else if (g_object_get_data(G_OBJECT(wrapper), "tl-textview-java-draw")) {
		wrapper->draw_method = NULL;
		g_object_set_data(G_OBJECT(wrapper), "tl-textview-java-draw", NULL);
	}
	gtk_widget_queue_draw(parent);
}

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
/* a per-widget CSS provider under `key` (NULL css removes it) */
static void tl_tv_css(GtkWidget *w, const char *key, const char *css)
{
	GtkStyleContext *sc = gtk_widget_get_style_context(w);
	GtkCssProvider *old = g_object_get_data(G_OBJECT(w), key);
	if (old)
		gtk_style_context_remove_provider(sc, GTK_STYLE_PROVIDER(old));
	if (!css) {
		g_object_set_data(G_OBJECT(w), key, NULL);
		return;
	}
	GtkCssProvider *p = gtk_css_provider_new();
	gtk_css_provider_load_from_string(p, css);
	gtk_style_context_add_provider(sc, GTK_STYLE_PROVIDER(p), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
	g_object_set_data_full(G_OBJECT(w), key, p, g_object_unref);
}
#pragma GCC diagnostic pop

static char *tl_tv_rgba(jint color)
{
	char alpha[G_ASCII_DTOSTR_BUF_SIZE];
	g_ascii_formatd(alpha, sizeof(alpha), "%.3f", ((color >> 24) & 0xff) / 255.0);
	return g_strdup_printf("rgba(%d,%d,%d,%s)", (color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff, alpha);
}

/* AOSP Paint.setShadowLayer on the text: CSS text-shadow (radius 0 removes it) */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setShadow(JNIEnv *env, jobject this, jlong widget_ptr, jfloat radius, jfloat dx, jfloat dy, jint color)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target)
		return;
	if (radius <= 0) {
		tl_tv_css(target, "tl-text-shadow", NULL);
		return;
	}
	char x[G_ASCII_DTOSTR_BUF_SIZE], y[G_ASCII_DTOSTR_BUF_SIZE], r[G_ASCII_DTOSTR_BUF_SIZE];
	g_ascii_formatd(x, sizeof(x), "%.2f", dx);
	g_ascii_formatd(y, sizeof(y), "%.2f", dy);
	g_ascii_formatd(r, sizeof(r), "%.2f", radius);
	char *rgba = tl_tv_rgba(color);
	char *css = g_strdup_printf("* { text-shadow: %spx %spx %spx %s; }", x, y, r, rgba);
	tl_tv_css(target, "tl-text-shadow", css);
	g_free(css);
	g_free(rgba);
}

/* an EditText's hint: GtkText draws it with its placeholder label */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setHintColor(JNIEnv *env, jobject this, jlong widget_ptr, jint color)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target || !GTK_IS_TEXT(target))
		return;
	char *rgba = tl_tv_rgba(color);
	char *css = g_strdup_printf("* { color: %s; opacity: 1; }", rgba);
	for (GtkWidget *c = gtk_widget_get_first_child(target); c; c = gtk_widget_get_next_sibling(c)) {
		if (GTK_IS_LABEL(c))
			tl_tv_css(c, "tl-hint-color", css);
	}
	g_free(css);
	g_free(rgba);
}

JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setCursorVisible(JNIEnv *env, jobject this, jlong widget_ptr, jboolean visible)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target || !GTK_IS_TEXT(target))
		return;
	tl_tv_css(target, "tl-cursor-visible", visible ? NULL : "* { caret-color: transparent; }");
}

/* an InputFilter.LengthFilter on an EditText: GtkText refuses longer input itself (0: unlimited) */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setMaxLength(JNIEnv *env, jobject this, jlong widget_ptr, jint max)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target || !GTK_IS_TEXT(target))
		return;
	gtk_text_set_max_length(GTK_TEXT(target), CLAMP(max, 0, 65535));
}

/* PasswordTransformationMethod on an EditText: GtkText shows its invisible char */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setTextVisibility(JNIEnv *env, jobject this, jlong widget_ptr, jboolean visible)
{
	GtkWidget *target = tl_tv_target(GTK_WIDGET(_PTR(widget_ptr)));
	if (!target || !GTK_IS_TEXT(target))
		return;
	gtk_text_set_visibility(GTK_TEXT(target), visible);
	if (!visible)
		gtk_text_set_invisible_char(GTK_TEXT(target), 0x2022); /* AOSP PasswordTransformationMethod's DOT */
}
EOF
fi

if ! grep -q "tl_magnifier" "$T"; then
cat >> "$T" <<'EOF2'

/* ---- teslalinux: android.widget.Magnifier shows a view's live rendering (tl_magnifier) ---- */
JNIEXPORT jlong JNICALL Java_android_widget_Magnifier_native_1widgetPaintable(JNIEnv *env, jclass clazz, jlong widget_ptr)
{
	GtkWidget *w = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *wrapper = gtk_widget_get_parent(w);
	return _INTPTR(gtk_widget_paintable_new((wrapper && WRAPPER_IS_WIDGET(wrapper)) ? wrapper : w));
}

JNIEXPORT void JNICALL Java_android_widget_Magnifier_native_1unref(JNIEnv *env, jclass clazz, jlong ptr)
{
	if (ptr)
		g_object_unref(_PTR(ptr));
}
EOF2
fi

if ! grep -q "tl_textview_java_hooks" "$W"; then
python3 - "$W" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='	jmethodID draw_method = _METHOD(_CLASS(jobj), "draw", "(Landroid/graphics/Canvas;)V");\n	if (on_draw_method != handle_cache.view.onDraw'
new='''	jmethodID draw_method = _METHOD(_CLASS(jobj), "draw", "(Landroid/graphics/Canvas;)V");
	/* tl_textview_java_hooks: TextView's own onDraw (compound drawables) and onTouchEvent (movement
	 * method) count as View's; a TextView asks for the Java path itself when it needs it
	 * (TextView.native_setJavaDraw, setClickable). Subclasses that override them again still count. */
	static jmethodID tl_tv_on_draw = NULL, tl_tv_on_touch = NULL;
	if (!tl_tv_on_draw) {
		jclass tv_class = (*env)->FindClass(env, "android/widget/TextView");
		tl_tv_on_draw = _METHOD(tv_class, "onDraw", "(Landroid/graphics/Canvas;)V");
		tl_tv_on_touch = _METHOD(tv_class, "onTouchEvent", "(Landroid/view/MotionEvent;)Z");
		(*env)->DeleteLocalRef(env, tv_class);
	}
	if (on_draw_method == tl_tv_on_draw)
		on_draw_method = handle_cache.view.onDraw;
	if (on_draw_method != handle_cache.view.onDraw'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
old2='	jmethodID ontouchevent_method = _METHOD(_CLASS(jobj), "onTouchEvent", "(Landroid/view/MotionEvent;)Z");\n'
new2=old2+'	if (ontouchevent_method == tl_tv_on_touch) /* tl_textview_java_hooks */\n		ontouchevent_method = handle_cache.view.onTouchEvent;\n'
assert s.count(old2)==1, s.count(old2)
s=s.replace(old2,new2,1)
open(p,'w').write(s)
PY
fi

if ! grep -q "tl_edittext_selection" "$E"; then
python3 - "$E" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
anchor='JNIEXPORT jlong JNICALL Java_android_widget_EditText_native_1constructor('
assert s.count(anchor)==1, s.count(anchor)
cb='''/* ---- teslalinux: GtkText cursor/selection changes reach TextView.onSelectionChanged (tl_edittext_selection) ---- */
static void tl_edittext_selection_cb(GtkWidget *gtk_text, GParamSpec *pspec, gpointer data)
{
	GtkWidget *wrapper = gtk_widget_get_parent(gtk_text);
	if (!wrapper || !WRAPPER_IS_WIDGET(wrapper))
		return;
	WrapperWidget *w = WRAPPER_WIDGET(wrapper);
	JNIEnv *env = get_jni_env();
	if (!w->jobj || (*env)->IsSameObject(env, w->jobj, NULL))
		return;
	jmethodID m = _METHOD(_CLASS(w->jobj), "tlGtkSelectionChanged", "()V");
	if (!m) {
		(*env)->ExceptionClear(env);
		return;
	}
	(*env)->CallVoidMethod(env, w->jobj, m);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

'''
s=s.replace(anchor, cb+anchor, 1)
old='	g_signal_connect(gtk_text, "notify::has-focus", G_CALLBACK(tl_edittext_focus_cb), NULL); /* tl_edittext_ime */\n'
assert s.count(old)==1, s.count(old)
new=old+'	g_signal_connect(gtk_text, "notify::cursor-position", G_CALLBACK(tl_edittext_selection_cb), NULL); /* tl_edittext_selection */\n	g_signal_connect(gtk_text, "notify::selection-bound", G_CALLBACK(tl_edittext_selection_cb), NULL);\n'
s=s.replace(old,new,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_text_styled" "$T"
grep -c "tl_magnifier" "$T"
grep -c "tl_textview_java_hooks" "$W"
grep -c "tl_edittext_selection" "$E"
