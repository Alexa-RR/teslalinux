#!/bin/sh
# teslalinux: ATL_DEBUG_TOUCH=1 traces pointer/touch events through ATL's dispatch (which GTK event
# arrived, which View wrapper got it, in which phase, and what the Java side returned). Off by default;
# a static flag read once, so the cost when disabled is one branch per event.
set -eu
F=src/api-impl-jni/views/android_view_View.c
if ! grep -q "ATL_DEBUG_TOUCH" "$F"; then
python3 - "$F" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	jobject this = wrapper->jobj;
	bool is_synthetic = event == motion_event;
'''
new='''	jobject this = wrapper->jobj;
	bool is_synthetic = event == motion_event;
	static int debug_touch = -1;
	if (debug_touch < 0)
		debug_touch = getenv("ATL_DEBUG_TOUCH") != NULL;
	if (debug_touch) {
		jclass cls = (*env)->GetObjectClass(env, this);
		jstring name = (*env)->CallObjectMethod(env, cls, (*env)->GetMethodID(env, (*env)->GetObjectClass(env, cls), "getName", "()Ljava/lang/String;"));
		const char *cname = (*env)->GetStringUTFChars(env, name, NULL);
		fprintf(stderr, "[touch] %s phase=%s action=0x%x custom_dispatch=%d intercepting=%d synthetic=%d\\n", cname,
		        phase == GTK_PHASE_CAPTURE ? "capture" : phase == GTK_PHASE_BUBBLE ? "bubble" : "target",
		        action, wrapper->custom_dispatch_touch, wrapper->intercepting_touch, is_synthetic);
		(*env)->ReleaseStringUTFChars(env, name, cname);
	}
'''
assert old in s, "view_dispatch_motionevent prologue not found"
s=s.replace(old,new,1)
old2='''		ret = (*env)->CallBooleanMethod(env, this, handle_cache.view.dispatchTouchEvent, motion_event);
'''
new2='''		ret = (*env)->CallBooleanMethod(env, this, handle_cache.view.dispatchTouchEvent, motion_event);
		if (debug_touch)
			fprintf(stderr, "[touch]   custom dispatchTouchEvent returned %d%c", ret, 10);
'''
assert s.count(old2) == 1, "custom dispatch call not found once"
s=s.replace(old2,new2,1)
old2='''	int action;
	GdkEventType event_type = gdk_event_get_event_type(event);
	switch (event_type) {
'''
new2='''	int action;
	GdkEventType event_type = gdk_event_get_event_type(event);
	if (getenv("ATL_DEBUG_TOUCH")) {
		double ex = 0, ey = 0;
		gdk_event_get_position(event, &ex, &ey);
		fprintf(stderr, "[touch] gdk event type=%d at %.0f,%.0f on %s\\n", event_type, ex, ey,
		        G_OBJECT_TYPE_NAME(gtk_event_controller_get_widget(GTK_EVENT_CONTROLLER(event_controller))));
	}
	switch (event_type) {
'''
assert old2 in s, "on_pointer_event switch not found"
s=s.replace(old2,new2,1)
old3='''	GtkEventController *controller = GTK_EVENT_CONTROLLER(gtk_event_controller_legacy_new());
	gtk_event_controller_set_propagation_phase(controller, GTK_PHASE_BUBBLE);
'''
new3='''	if (getenv("ATL_DEBUG_TOUCH")) {
		jclass cls = (*env)->GetObjectClass(env, this);
		jstring name = (*env)->CallObjectMethod(env, cls, (*env)->GetMethodID(env, (*env)->GetObjectClass(env, cls), "getName", "()Ljava/lang/String;"));
		const char *cname = (*env)->GetStringUTFChars(env, name, NULL);
		fprintf(stderr, "[touch] attaching touch controller to %s (widget %p)\\n", cname, widget);
		(*env)->ReleaseStringUTFChars(env, name, cname);
	}
	GtkEventController *controller = GTK_EVENT_CONTROLLER(gtk_event_controller_legacy_new());
	gtk_event_controller_set_propagation_phase(controller, GTK_PHASE_BUBBLE);
'''
assert old3 in s, "_setOnTouchListener controller creation not found"
s=s.replace(old3,new3,1)
open(p,'w').write(s)
PY
fi
grep -c "ATL_DEBUG_TOUCH" "$F"

# window-level capture-phase trace: every pointer event the toplevel receives, before any widget
M=src/main-executable/main.c
if ! grep -q "ATL_DEBUG_TOUCH" "$M"; then
python3 - "$M" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	window = gtk_application_window_new(app);
'''
new='''	window = gtk_application_window_new(app);
	if (getenv("ATL_DEBUG_TOUCH")) {
		GtkEventController *trace = GTK_EVENT_CONTROLLER(gtk_event_controller_legacy_new());
		gtk_event_controller_set_propagation_phase(trace, GTK_PHASE_CAPTURE);
		g_signal_connect(trace, "event", G_CALLBACK(tl_trace_window_event), NULL);
		gtk_widget_add_controller(window, trace);
	}
'''
assert old in s, "window creation not found"
s=s.replace(old,new,1)
helper='''
/* teslalinux: ATL_DEBUG_TOUCH window-level event trace (capture phase, never consumes) */
static gboolean tl_trace_window_event(GtkEventControllerLegacy *controller, GdkEvent *event, gpointer user_data)
{
	GdkEventType t = gdk_event_get_event_type(event);
	if (t != GDK_MOTION_NOTIFY || getenv("ATL_DEBUG_TOUCH_MOTION")) {
		double x = 0, y = 0;
		gdk_event_get_position(event, &x, &y);
		GtkWidget *w = gtk_widget_pick(GTK_WIDGET(gtk_event_controller_get_widget(GTK_EVENT_CONTROLLER(controller))), x, y, GTK_PICK_DEFAULT);
		fprintf(stderr, "[touch] window event type=%d at %.0f,%.0f picks %s\\n", t, x, y, w ? G_OBJECT_TYPE_NAME(w) : "(none)");
	}
	return FALSE;
}
'''
# place the helper before the first function that uses it: right before "static void open("
idx=s.index("static void open(")
s=s[:idx]+helper+"\n"+s[idx:]
open(p,'w').write(s)
PY
fi
grep -c "ATL_DEBUG_TOUCH" "$M"

# Java-forwarded events (ViewGroup.dispatchTouchEvent -> native pick + synthetic propagation)
VG=src/api-impl-jni/views/android_view_ViewGroup.c
if ! grep -q "ATL_DEBUG_TOUCH" "$VG"; then
python3 - "$VG" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	GtkWidget *picked_child = gtk_widget_pick(widget, x, y, GTK_PICK_DEFAULT);

	return atl_propagate_synthetic_motionevent(picked_child, motion_event, widget);'''
new='''	GtkWidget *picked_child = gtk_widget_pick(widget, x, y, GTK_PICK_DEFAULT);
	if (getenv("ATL_DEBUG_TOUCH"))
		fprintf(stderr, "[touch] native_dispatchTouchEvent on %s (%dx%d) at %.0f,%.0f picks %s%c", G_OBJECT_TYPE_NAME(widget), gtk_widget_get_width(widget), gtk_widget_get_height(widget), x, y, picked_child ? G_OBJECT_TYPE_NAME(picked_child) : "nothing", 10);
	if (!picked_child)
		return false;

	return atl_propagate_synthetic_motionevent(picked_child, motion_event, widget);'''
assert s.count(old) == 1, s.count(old)
s=s.replace(old,new,1)
if "#include <stdio.h>" not in s:
    s="#include <stdio.h>\n#include <stdlib.h>\n"+s
open(p,'w').write(s)
PY
fi
grep -c "ATL_DEBUG_TOUCH" "$VG"
