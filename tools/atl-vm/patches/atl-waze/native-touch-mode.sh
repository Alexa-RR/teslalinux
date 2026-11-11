#!/bin/sh
# teslalinux: touch mode input hooks (tl_touch_mode). AOSP's ViewRootImpl sees every input event
# before the views: a pointer DOWN or SCROLL from any device enters touch mode
# (EarlyPostImeInputStage.processPointerEvent), a navigation or typing key leaves it
# (ViewPostImeInputStage -> checkForLeavingTouchModeAndConsume, which may consume the key).
# ATL had no touch mode at all (View.isInTouchMode returned false), so Compose's InputModeManager
# stayed in Keyboard mode and every clickable took focus (Focusability.SystemDefined): a
# FocusRequester on a clickable row focused the row instead of the text field inside it.
# Two capture-phase controllers on the main window call View.tlPointerDown and
# View.tlCheckForLeavingTouchModeAndConsume before the event reaches any widget. Dialogs are
# layers of this window, so their input passes here too, and so do the app's own windows
# (WindowManagerImpl layers): the pointer hook sends ACTION_OUTSIDE to the windows that watch for it,
# the key hook gives keys to a focusable app window first. Run by build-atl.sh from ~/atl-src.
# Idempotent per file.
set -eu
V=src/api-impl-jni/views/android_view_View.c
M=src/main-executable/main.c
if ! grep -q "tl_touch_mode" "$V"; then
cat >> "$V" <<'EOF'

/* ---- teslalinux: touch mode input hooks (tl_touch_mode), connected in main.c ---- */
int map_key_code(int key_code);
int map_meta_state(GdkModifierType state);

/* pointer DOWN (button press, touch begin) or SCROLL: AOSP enters touch mode, and every app window
 * watching outside touches whose frame does not contain the point gets ACTION_OUTSIDE (app windows
 * are layers of this window, WindowManagerImpl); the event goes on to the views */
gboolean tl_touch_mode_pointer_event(GtkEventControllerLegacy *controller, GdkEvent *event, gpointer data)
{
	GdkEventType type = gdk_event_get_event_type(event);
	if (type != GDK_BUTTON_PRESS && type != GDK_TOUCH_BEGIN && type != GDK_SCROLL)
		return FALSE;
	double x = 0, y = 0;
	gdk_event_get_position(event, &x, &y);
	JNIEnv *env = get_jni_env();
	static jmethodID pointer_down = NULL;
	if (!pointer_down)
		pointer_down = _STATIC_METHOD(handle_cache.view.class, "tlPointerDown", "(FF)V");
	(*env)->CallStaticVoidMethod(env, handle_cache.view.class, pointer_down, (jfloat)x, (jfloat)y);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	return FALSE;
}

/* a key, before any view: AOSP ViewRootImpl leaves touch mode on navigation and typing keys (and may
 * consume the key for that), and the key goes to the focused window, which is an app-added
 * focusable window above the activity when one exists (WindowManagerImpl); TRUE when consumed.
 * The host keyboard's Escape is this platform's BACK key, as in the window-level dispatch. */
static gboolean tl_touch_mode_key(guint keyval, GdkModifierType state, int action)
{
	JNIEnv *env = get_jni_env();
	static jmethodID check = NULL, to_window = NULL;
	static jclass wm = NULL;
	if (!check)
		check = _STATIC_METHOD(handle_cache.view.class, "tlCheckForLeavingTouchModeAndConsume", "(Landroid/view/KeyEvent;)Z");
	if (!wm) {
		wm = _REF((*env)->FindClass(env, "android/view/WindowManagerImpl"));
		to_window = _STATIC_METHOD(wm, "tlDispatchKeyToFocusedWindow", "(Landroid/view/KeyEvent;)Z");
	}
	int code = keyval == GDK_KEY_Escape ? 4 /* KEYCODE_BACK */ : map_key_code(keyval);
	jobject key_event = (*env)->NewObject(env, handle_cache.key_event.class, handle_cache.key_event.constructor,
	                                      (jlong)0, (jlong)0, action, code, 0, map_meta_state(state));
	_SET_INT_FIELD(key_event, "unicodeValue", gdk_keyval_to_unicode(keyval));
	gboolean consumed = FALSE;
	if (action == 0 /* ACTION_DOWN */)
		consumed = (*env)->CallStaticBooleanMethod(env, handle_cache.view.class, check, key_event);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		consumed = FALSE;
	}
	if (!consumed)
		consumed = (*env)->CallStaticBooleanMethod(env, wm, to_window, key_event);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		consumed = FALSE;
	}
	(*env)->DeleteLocalRef(env, key_event);
	return consumed;
}

gboolean tl_touch_mode_key_pressed(GtkEventControllerKey *controller, guint keyval, guint keycode, GdkModifierType state, gpointer data)
{
	return tl_touch_mode_key(keyval, state, 0 /* ACTION_DOWN */);
}

void tl_touch_mode_key_released(GtkEventControllerKey *controller, guint keyval, guint keycode, GdkModifierType state, gpointer data)
{
	tl_touch_mode_key(keyval, state, 1 /* ACTION_UP */);
}
EOF
fi
grep -q "tl_touch_mode_pointer_event" "$V"
if ! grep -q "tl_touch_mode" "$M"; then
python3 - "$M" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = '''	window = gtk_application_window_new(app);
'''
new = '''	window = gtk_application_window_new(app);
	{
		/* tl_touch_mode: input passes the touch-mode logic before any view (AOSP ViewRootImpl input stages) */
		gboolean tl_touch_mode_pointer_event(GtkEventControllerLegacy *controller, GdkEvent *event, gpointer data);
		gboolean tl_touch_mode_key_pressed(GtkEventControllerKey *controller, guint keyval, guint keycode, GdkModifierType state, gpointer data);
		void tl_touch_mode_key_released(GtkEventControllerKey *controller, guint keyval, guint keycode, GdkModifierType state, gpointer data);
		GtkEventController *tl_mode_pointer = gtk_event_controller_legacy_new();
		gtk_event_controller_set_propagation_phase(tl_mode_pointer, GTK_PHASE_CAPTURE);
		g_signal_connect(tl_mode_pointer, "event", G_CALLBACK(tl_touch_mode_pointer_event), NULL);
		gtk_widget_add_controller(window, tl_mode_pointer);
		GtkEventController *tl_mode_keys = gtk_event_controller_key_new();
		gtk_event_controller_set_propagation_phase(tl_mode_keys, GTK_PHASE_CAPTURE);
		g_signal_connect(tl_mode_keys, "key-pressed", G_CALLBACK(tl_touch_mode_key_pressed), NULL);
		g_signal_connect(tl_mode_keys, "key-released", G_CALLBACK(tl_touch_mode_key_released), NULL);
		gtk_widget_add_controller(window, tl_mode_keys);
	}
'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)
open(p, 'w').write(s)
PY
fi
grep -c "tl_touch_mode" "$M"
