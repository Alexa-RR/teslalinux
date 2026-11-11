#!/bin/sh
# teslalinux: requestFocus before the first map (tl_focus_on_map). AOSP's View.requestFocus succeeds
# as soon as the view is attached, visible and focusable (focus is view state; the window applies
# it). ATL turned it into gtk_widget_grab_focus, which only works on a mapped widget, so a focus
# requested right after attach (Compose's search screen focuses its text field in its first
# frame, now before GTK maps the new view since attach follows root/unroot) was lost and no soft
# input was requested. The grab is deferred to the widget's "map". Run by build-atl.sh from
# ~/atl-src. Idempotent.
set -eu
V=src/api-impl-jni/views/android_view_View.c
if ! grep -q "tl_focus_on_map" "$V"; then
python3 - "$V" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''JNIEXPORT void JNICALL Java_android_view_View_nativeRequestFocus(JNIEnv *env, jobject this, jlong widget_ptr, jint direction)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *wrapper = gtk_widget_get_parent(widget);
	if (gtk_widget_get_focusable(widget))
		gtk_widget_grab_focus(widget);
	else
		gtk_widget_grab_focus(wrapper);
}
'''
new='''/* tl_focus_on_map: a focus requested before the widget is mapped is applied when it maps (AOSP
 * keeps the focus request as view state and the window applies it) */
static void tl_focus_on_map(GtkWidget *widget, gpointer data)
{
	if (g_object_get_data(G_OBJECT(widget), "tl-focus-pending")) {
		g_object_set_data(G_OBJECT(widget), "tl-focus-pending", NULL);
		gboolean ok = gtk_widget_grab_focus(widget);
		if (getenv("ATL_DEBUG_FOCUS"))
			fprintf(stderr, "[focus] deferred grab on map %s -> %d has_focus=%d\\n", G_OBJECT_TYPE_NAME(widget), ok, gtk_widget_has_focus(widget));
	}
	g_signal_handlers_disconnect_by_func(widget, tl_focus_on_map, data);
}

JNIEXPORT void JNICALL Java_android_view_View_nativeRequestFocus(JNIEnv *env, jobject this, jlong widget_ptr, jint direction)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *wrapper = gtk_widget_get_parent(widget);
	GtkWidget *target = gtk_widget_get_focusable(widget) ? widget : wrapper;
	if (gtk_widget_get_mapped(target)) {
		gboolean ok = gtk_widget_grab_focus(target);
		if (getenv("ATL_DEBUG_FOCUS"))
			fprintf(stderr, "[focus] grab %s -> %d has_focus=%d\\n", G_OBJECT_TYPE_NAME(target), ok, gtk_widget_has_focus(target));
	} else {
		if (getenv("ATL_DEBUG_FOCUS"))
			fprintf(stderr, "[focus] grab deferred to map: %s\\n", G_OBJECT_TYPE_NAME(target));
		g_object_set_data(G_OBJECT(target), "tl-focus-pending", GINT_TO_POINTER(1));
		g_signal_handlers_disconnect_by_func(target, tl_focus_on_map, NULL);
		g_signal_connect(target, "map", G_CALLBACK(tl_focus_on_map), NULL);
	}
}
'''
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new,1))
PY
fi
grep -c "tl_focus_on_map" "$V"
