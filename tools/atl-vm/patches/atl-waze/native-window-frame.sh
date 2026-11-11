#!/bin/sh
# teslalinux: View.getWindowVisibleDisplayFrame reports what is really visible (tl_window_frame).
# AOSP: the window's display frame minus what covers it (system bars, the IME while it shows). ATL
# returned the main window's default size (the -w/-h launch size, e.g. 1088x832) even when the
# window was fullscreen at another size, so PopupWindow and ListPopupWindow sized and placed popups
# for a screen that does not exist. Now: the main window's allocated size (the default size only
# before the first allocation), minus the built-in on-screen keyboard (native-osk.sh, the "tl-osk"
# layer of the main overlay) while it is shown, which is where AOSP's IME inset goes. There are no
# system bars. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
V=src/api-impl-jni/views/android_view_View.c
if ! grep -q "tl_window_frame" "$V"; then
python3 - "$V" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = '''JNIEXPORT void JNICALL Java_android_view_View_getWindowVisibleDisplayFrame(JNIEnv *env, jobject this, jobject rect)
{
	int width;
	int height;
	gtk_window_get_default_size(window, &width, &height);
	_SET_INT_FIELD(rect, "left", 0);
'''
new = '''/* tl_window_frame: the height of the on-screen keyboard layer while it shows (0 otherwise) */
static int tl_window_frame_ime_height(void)
{
	extern GtkWidget *tl_main_overlay;
	if (!tl_main_overlay)
		return 0;
	for (GtkWidget *c = gtk_widget_get_first_child(tl_main_overlay); c; c = gtk_widget_get_next_sibling(c)) {
		if (!gtk_widget_has_css_class(c, "tl-osk") || !gtk_widget_get_visible(c))
			continue;
		int h = gtk_widget_get_height(c);
		if (h <= 0) { /* shown but not allocated yet: its requested height */
			int min = 0, nat = 0;
			gtk_widget_measure(c, GTK_ORIENTATION_VERTICAL, gtk_widget_get_width(tl_main_overlay), &min, &nat, NULL, NULL);
			h = nat;
		}
		return h;
	}
	return 0;
}

JNIEXPORT void JNICALL Java_android_view_View_getWindowVisibleDisplayFrame(JNIEnv *env, jobject this, jobject rect)
{
	int width;
	int height;
	/* tl_window_frame: the window as allocated (fullscreen, kiosk), not its default size */
	width = gtk_widget_get_width(GTK_WIDGET(window));
	height = gtk_widget_get_height(GTK_WIDGET(window));
	if (width <= 0 || height <= 0)
		gtk_window_get_default_size(window, &width, &height);
	height -= tl_window_frame_ime_height();
	if (height < 0)
		height = 0;
	_SET_INT_FIELD(rect, "left", 0);
'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)
open(p, 'w').write(s)
PY
fi
grep -c "tl_window_frame" "$V"
