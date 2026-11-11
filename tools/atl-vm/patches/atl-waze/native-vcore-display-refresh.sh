#!/bin/sh
# teslalinux (vcore): Display.getRefreshRate() is the refresh rate of the monitor that shows the app
# window, as AOSP reports the active display mode's rate; ATL returned 60 with a FIXME. GDK gives the
# rate in mHz (0 when the compositor announced none, the Java side then keeps 60 Hz, the Wayland
# default). Needs native-config.sh (tl_window_size and the extern window come from it), so this sorts
# after it. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
F=src/api-impl-jni/content/android_content_Context.c
if ! grep -q "Java_android_view_Display_nativeGetRefreshRate" "$F"; then
python3 - "$F" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
anchor = 'static void tl_window_size(int *width, int *height)\n'
assert s.count(anchor) == 1, s.count(anchor)
fn = '''/* tl_display_refresh (native-vcore-display-refresh.sh): the refresh rate of the monitor that shows
 * the app window (the first monitor before the window is realized), in Hz; 0 when unknown */
JNIEXPORT jfloat JNICALL Java_android_view_Display_nativeGetRefreshRate(JNIEnv *env, jclass clazz)
{
	GdkDisplay *display = gdk_display_get_default();
	if (!display)
		return 0;
	GdkMonitor *monitor = NULL;
	if (window && gtk_widget_get_realized(GTK_WIDGET(window))) {
		GdkSurface *surface = gtk_native_get_surface(GTK_NATIVE(window));
		if (surface)
			monitor = gdk_display_get_monitor_at_surface(display, surface);
	}
	int mhz = 0;
	if (monitor) {
		mhz = gdk_monitor_get_refresh_rate(monitor);
	} else {
		GListModel *monitors = gdk_display_get_monitors(display);
		if (g_list_model_get_n_items(monitors) > 0) {
			GdkMonitor *first = g_list_model_get_item(monitors, 0);
			mhz = gdk_monitor_get_refresh_rate(first);
			g_object_unref(first);
		}
	}
	return mhz > 0 ? mhz / 1000.0f : 0;
}

'''
s = s.replace(anchor, fn + anchor, 1)
open(p, 'w').write(s)
PY
fi
grep -c "Java_android_view_Display_nativeGetRefreshRate" "$F"
