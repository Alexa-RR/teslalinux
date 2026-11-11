#!/bin/sh
# teslalinux: ATL_DEBUG_KEY=1 traces keyboard input: every key event the toplevel window receives
# (capture phase, with the window's focus widget) and every key event a WrapperWidget forwards to
# Java dispatchKeyEvent (with the view class and the result). Run by build-atl.sh from ~/atl-src.
set -eu
M=src/main-executable/main.c
W=src/api-impl-jni/widgets/WrapperWidget.c
if ! grep -q "tl_trace_window_key" "$M"; then
python3 - "$M" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	window = gtk_application_window_new(app);
'''
new='''	window = gtk_application_window_new(app);
	if (getenv("ATL_DEBUG_KEY")) {
		GtkEventController *keytrace = GTK_EVENT_CONTROLLER(gtk_event_controller_key_new());
		gtk_event_controller_set_propagation_phase(keytrace, GTK_PHASE_CAPTURE);
		g_signal_connect(keytrace, "key-pressed", G_CALLBACK(tl_trace_window_key), NULL);
		gtk_widget_add_controller(window, keytrace);
	}
'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)
helper = '''
/* teslalinux: ATL_DEBUG_KEY window-level key trace, see native-key-debug.sh */
static gboolean tl_trace_window_key(GtkEventControllerKey *controller, guint keyval, guint keycode, GdkModifierType state, gpointer user_data)
{
	GtkWidget *window = gtk_event_controller_get_widget(GTK_EVENT_CONTROLLER(controller));
	GtkWidget *focus = gtk_root_get_focus(GTK_ROOT(window));
	fprintf(stderr, "[key] window key-pressed keyval=0x%x (%s) keycode=%u state=0x%x focus=%p (%s)%c", keyval, gdk_keyval_name(keyval) ? gdk_keyval_name(keyval) : "?", keycode, state, (void *)focus, focus ? G_OBJECT_TYPE_NAME(focus) : "none", 10);
	return FALSE;
}
'''
idx = s.index(new)
end = s.rfind(chr(10) + "}" + chr(10), 0, idx) + 3
s = s[:end] + helper + s[end:]
if "#include <stdio.h>" not in s:
    s = "#include <stdio.h>" + chr(10) + s
open(p, 'w').write(s)
PY
fi
if ! grep -q "tl_key_debug" "$W"; then
python3 - "$W" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	gboolean ret = (*env)->CallBooleanMethod(env, wrapper->jobj, handle_cache.view.dispatchKeyEvent, key_event);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	return ret;
}
'''
new='''	gboolean ret = (*env)->CallBooleanMethod(env, wrapper->jobj, handle_cache.view.dispatchKeyEvent, key_event);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	tl_key_debug(env, wrapper, keyval, ret);
	return ret;
}
'''
assert s.count(old) == 2, s.count(old)
s = s.replace(old, new)
helper = '''
/* teslalinux: ATL_DEBUG_KEY trace of key events forwarded to Java, see native-key-debug.sh */
static void tl_key_debug(JNIEnv *env, WrapperWidget *wrapper, guint keyval, gboolean ret)
{
	if ((*env)->ExceptionCheck(env))
		return;
	static int enabled = -1;
	if (enabled < 0)
		enabled = getenv("ATL_DEBUG_KEY") != NULL;
	if (!enabled)
		return;
	const char *cname = "?";
	jstring name = NULL;
	if (wrapper->jobj && !(*env)->IsSameObject(env, wrapper->jobj, NULL)) {
		jclass cls = (*env)->GetObjectClass(env, wrapper->jobj);
		jmethodID get_name = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, cls), "getName", "()Ljava/lang/String;");
		name = (*env)->CallObjectMethod(env, cls, get_name);
		cname = (*env)->GetStringUTFChars(env, name, NULL);
	}
	fprintf(stderr, "[key] wrapper %p %s dispatchKeyEvent keyval=0x%x -> %d%c", (void *)wrapper, cname, keyval, ret, 10);
	if (name)
		(*env)->ReleaseStringUTFChars(env, name, cname);
}
'''
key = chr(10) + "static gboolean on_key_pressed("
assert s.count(key) == 1, s.count(key)
s = s.replace(key, helper + key, 1)
if "#include <stdlib.h>" not in s:
    s = "#include <stdlib.h>" + chr(10) + "#include <stdio.h>" + chr(10) + s
open(p, 'w').write(s)
PY
fi
grep -c "tl_trace_window_key" "$M"; grep -c "tl_key_debug" "$W"
