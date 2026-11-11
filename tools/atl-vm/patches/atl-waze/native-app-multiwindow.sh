#!/bin/sh
# teslalinux (app): Activity.isInMultiWindowMode (tl_multiwindow). AOSP: true while the activity's
# task is in split-screen or a freeform window, false when it fills the display. ATL answered
# "not maximized", so a fullscreen kiosk window (cage / tacomp give the toplevel the whole output,
# fullscreen or simply sized to it) counted as multi-window. Now: a window that is maximized,
# fullscreen, or as large as its monitor fills the display. Run by build-atl.sh from ~/atl-src.
# Idempotent.
set -eu
A=src/api-impl-jni/app/android_app_Activity.c
if ! grep -q "tl_multiwindow" "$A"; then
python3 - "$A" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = 'JNIEXPORT jboolean JNICALL Java_android_app_Activity_isInMultiWindowMode(JNIEnv *env, jobject this)\n{\n\treturn !gtk_window_is_maximized(window);\n}'
assert s.count(old) == 1, s.count(old)
new = '''/* tl_multiwindow: the activity fills the display unless its window is a smaller, freeform one */
JNIEXPORT jboolean JNICALL Java_android_app_Activity_isInMultiWindowMode(JNIEnv *env, jobject this)
{
	if (!window || gtk_window_is_maximized(window) || gtk_window_is_fullscreen(window))
		return JNI_FALSE;
	GdkSurface *surface = gtk_native_get_surface(GTK_NATIVE(window));
	if (!surface)
		return JNI_FALSE;
	GdkMonitor *monitor = gdk_display_get_monitor_at_surface(gdk_surface_get_display(surface), surface);
	if (!monitor)
		return JNI_FALSE;
	GdkRectangle geometry;
	gdk_monitor_get_geometry(monitor, &geometry);
	int width = gdk_surface_get_width(surface);
	int height = gdk_surface_get_height(surface);
	return (width < geometry.width || height < geometry.height) ? JNI_TRUE : JNI_FALSE;
}'''
s = s.replace(old, new)
open(p, 'w').write(s)
PY
fi
grep -c "tl_multiwindow" "$A"
