#!/bin/sh
# teslalinux: clicks and long presses come from View.onTouchEvent (AOSP), not from GTK gestures.
# ATL fired performClick from a GtkGestureClick on the view's child widget, which never sees the
# release when the wrapper's own dispatchTouchEvent consumed the event at GTK's capture phase (every
# view with a custom dispatchTouchEvent, e.g. Waze's dialog buttons). Setting a click or long-click
# listener, or overriding performClick, now attaches the touch controller instead; the Java
# ACTION_UP/long-press handling performs the click. GtkButton-backed widgets (Button, ImageButton)
# keep their "clicked" signal: their wrapper consumes touches natively and never runs the Java path.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
V=src/api-impl-jni/views/android_view_View.c
W=src/api-impl-jni/widgets/WrapperWidget.c
if ! grep -q "tl_click_from_java" "$V"; then
python3 - "$V" "$W" <<'PY'
import sys
NL = chr(10)
p = sys.argv[1]; s = open(p).read()

def cut(s, old, new=''):
    assert s.count(old) == 1, (old[:60], s.count(old))
    return s.replace(old, new, 1)

s = cut(s, '''static bool action_up_already_handled = false;
''')
s = cut(s, '''	if ((gdk_event_get_event_type(event) == GDK_TOUCH_END || gdk_event_get_event_type(event) == GDK_BUTTON_RELEASE) && action_up_already_handled) {
		action_up_already_handled = false;
		return true;
	}

''')
s = cut(s, '''static void on_click(GtkGestureClick *gesture, int n_press, double x, double y, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	GtkWidget *widget = gtk_event_controller_get_widget(GTK_EVENT_CONTROLLER(gesture));
	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_widget_get_parent(widget));

	jboolean handled = (*env)->CallBooleanMethod(env, wrapper->jobj, handle_cache.view.performClick);

	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);

	if (handled) {
		gtk_gesture_set_state(GTK_GESTURE(gesture), GTK_EVENT_SEQUENCE_CLAIMED);
		// claiming fails if there is no last event, set a flag in that case
		GdkEvent *event = gtk_gesture_get_last_event(GTK_GESTURE(gesture), gtk_gesture_get_last_updated_sequence(GTK_GESTURE(gesture)));
		if (!event) {
			action_up_already_handled = true;
		}
	}
}

''')
s = cut(s, '''	GtkEventController *old_controller = g_object_get_data(G_OBJECT(widget), "on_click_listener");
	if (old_controller)
		return;

	GtkEventController *controller = GTK_EVENT_CONTROLLER(gtk_gesture_click_new());

	g_signal_connect(controller, "released", G_CALLBACK(on_click), NULL); // the release completes the click, I guess?
	gtk_widget_add_controller(widget, controller);
	g_object_set_data(G_OBJECT(widget), "on_click_listener", controller);
	widget_set_needs_allocation(widget);
''', '''	/* tl_click_from_java: AOSP performs the click from View.onTouchEvent on ACTION_UP */
	_setOnTouchListener(env, this, gtk_widget_get_parent(widget));
	widget_set_needs_allocation(widget);
''')
s = cut(s, '''static void on_long_click(GtkGestureLongPress *gesture, double x, double y, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	GtkWidget *widget = gtk_event_controller_get_widget(GTK_EVENT_CONTROLLER(gesture));
	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_widget_get_parent(widget));

	bool ret = (*env)->CallBooleanMethod(env, wrapper->jobj, handle_cache.view.performLongClick, x, y);

	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);

	if (ret)
		gtk_gesture_set_state(GTK_GESTURE(gesture), GTK_EVENT_SEQUENCE_CLAIMED);
}

static void on_long_click_update(GtkGesture *gesture, GdkEventSequence *sequence, gpointer user_data)
{
	GdkEvent *event = gtk_gesture_get_last_event(gesture, sequence);
	if (event == canceled_event) {
		gtk_event_controller_reset(GTK_EVENT_CONTROLLER(gesture));
	}
}

''')
s = cut(s, '''	GtkEventController *old_controller = g_object_get_data(G_OBJECT(widget), "on_long_click_listener");
	if (old_controller)
		return;

	GtkEventController *controller = GTK_EVENT_CONTROLLER(gtk_gesture_long_press_new());

	g_signal_connect(controller, "pressed", G_CALLBACK(on_long_click), NULL);
	g_signal_connect(controller, "update", G_CALLBACK(on_long_click_update), NULL);
	gtk_widget_add_controller(widget, controller);
	g_object_set_data(G_OBJECT(widget), "on_long_click_listener", controller);
''', '''	/* AOSP: the long press comes from View.onTouchEvent's pending check (tl_click_from_java) */
	_setOnTouchListener(env, this, gtk_widget_get_parent(widget));
''')
open(p, 'w').write(s)

p = sys.argv[2]; s = open(p).read()
if 'tl_click_from_java' in s:
    print('WrapperWidget.c already patched')
    sys.exit(0)
s = cut(s, '''static bool on_click(GtkGestureClick *gesture, int n_press, double x, double y, jobject this)
{
	JNIEnv *env = get_jni_env();

	bool ret = (*env)->CallBooleanMethod(env, this, handle_cache.view.performClick);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);

	return ret;
}

''')
s = cut(s, '''	jmethodID performClick_method = _METHOD(_CLASS(jobj), "performClick", "()Z");
	if (performClick_method != handle_cache.view.performClick) {
		GtkEventController *controller = GTK_EVENT_CONTROLLER(gtk_gesture_click_new());

		g_signal_connect(controller, "released", G_CALLBACK(on_click), wrapper->jobj);
		gtk_widget_add_controller(GTK_WIDGET(wrapper), controller);
		widget_set_needs_allocation(wrapper->child);
	}

''', '''	/* tl_click_from_java: a performClick override (CompoundButton & co.) needs the touch path */
	jmethodID performClick_method = _METHOD(_CLASS(jobj), "performClick", "()Z");

''')
s = cut(s, '''	if (ontouchevent_method != handle_cache.view.onTouchEvent || wrapper->custom_dispatch_touch) {
		_setOnTouchListener(env, jobj, GTK_WIDGET(wrapper));
	}
''', '''	if (ontouchevent_method != handle_cache.view.onTouchEvent || wrapper->custom_dispatch_touch
	    || performClick_method != handle_cache.view.performClick) {
		_setOnTouchListener(env, jobj, GTK_WIDGET(wrapper));
	}
''')
open(p, 'w').write(s)
print('click-from-java applied')
PY
fi
grep -c "tl_click_from_java" "$V" "$W"
