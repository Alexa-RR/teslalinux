#!/bin/sh
# teslalinux: window-level key dispatch (tl_window_keys). AOSP delivers every key event through
# Activity.dispatchKeyEvent (views first, then the activity's KeyEvent.Callback, where BACK ends in
# onBackPressed). ATL only gave keys to focused widgets whose class overrides onKeyDown; nothing
# reached the activity. A bubble-phase key controller on the toplevel window (after the focused
# widget's controllers) forwards the event to the current activity. Key layout of this platform:
# the host keyboard's Escape is the BACK key (what Generic.kl does for a device's back key).
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
W=src/api-impl-jni/widgets/WrapperWidget.c
A=src/api-impl-jni/app/android_app_Activity.c
M=src/main-executable/main.c
if ! grep -q "tl_window_keys" "$W"; then
	sed -i 's/^static int map_key_code(int key_code)$/int map_key_code(int key_code) \/* tl_window_keys: shared with the window-level dispatch *\//' "$W"
	sed -i 's/^static int map_meta_state(GdkModifierType state)$/int map_meta_state(GdkModifierType state)/' "$W"
fi
grep -q "^int map_key_code" "$W"
grep -q "^int map_meta_state" "$W"
if ! grep -q "tl_window_keys" "$A"; then
cat >> "$A" <<'EOF'

/* ---- teslalinux: window-level key dispatch (tl_window_keys) ---- */
int map_key_code(int key_code);
int map_meta_state(GdkModifierType state);
#define TL_KEYCODE_BACK 4
#define TL_ACTION_DOWN 0
#define TL_ACTION_UP 1

gboolean current_activity_dispatch_key(guint keyval, GdkModifierType state, int action)
{
	if (!activity_current)
		return FALSE;
	JNIEnv *env = get_jni_env();
	int code = map_key_code(keyval);
	if (keyval == GDK_KEY_Escape)
		code = TL_KEYCODE_BACK;
	jobject key_event = (*env)->NewObject(env, handle_cache.key_event.class, handle_cache.key_event.constructor,
	                                      (jlong)0, (jlong)0, action, code, 0, map_meta_state(state));
	_SET_INT_FIELD(key_event, "unicodeValue", gdk_keyval_to_unicode(keyval));
	/* tl_edittext_ime: the on-screen keyboard consumes BACK while it shows (AOSP: the IME window) */
	static gboolean tl_osk_back_down = FALSE;
	if (code == TL_KEYCODE_BACK) {
		if (action == TL_ACTION_DOWN) {
			jclass imm = (*env)->FindClass(env, "android/view/inputmethod/InputMethodManager");
			jmethodID handle = (*env)->GetStaticMethodID(env, imm, "tlOskHandleBack", "()Z");
			tl_osk_back_down = (*env)->CallStaticBooleanMethod(env, imm, handle);
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionDescribe(env);
			if (tl_osk_back_down) {
				(*env)->DeleteLocalRef(env, key_event);
				return TRUE;
			}
		} else if (tl_osk_back_down) {
			tl_osk_back_down = FALSE;
			(*env)->DeleteLocalRef(env, key_event);
			return TRUE;
		}
	}
	/* tl_dialog_overlay: a showing dialog is the focused window and gets the key instead of the activity */
	jboolean tl_dialog_dispatch_key(JNIEnv *env, jobject key_event);
	if (tl_dialog_dispatch_key(env, key_event)) {
		(*env)->DeleteLocalRef(env, key_event);
		return TRUE;
	}
	jmethodID dispatch = _METHOD(_CLASS(activity_current), "dispatchKeyEvent", "(Landroid/view/KeyEvent;)Z");
	gboolean ret = (*env)->CallBooleanMethod(env, activity_current, dispatch, key_event);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	(*env)->DeleteLocalRef(env, key_event);
	return ret;
}
EOF
fi
if ! grep -q "tl_window_keys" "$M"; then
python3 - "$M" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='#include <unistd.h>\n'
new='''#include <unistd.h>

/* tl_window_keys: keys the focused view did not consume reach the current activity (AOSP Activity.dispatchKeyEvent) */
gboolean current_activity_dispatch_key(guint keyval, GdkModifierType state, int action);
static gboolean tl_window_key_pressed(GtkEventControllerKey *controller, guint keyval, guint keycode, GdkModifierType state, gpointer data)
{
	return current_activity_dispatch_key(keyval, state, 0);
}
static gboolean tl_window_key_released(GtkEventControllerKey *controller, guint keyval, guint keycode, GdkModifierType state, gpointer data)
{
	return current_activity_dispatch_key(keyval, state, 1);
}
'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
old2='\twindow = gtk_application_window_new(app);\n'
new2='''\twindow = gtk_application_window_new(app);
	{
		GtkEventController *tl_keys = gtk_event_controller_key_new();
		gtk_event_controller_set_propagation_phase(tl_keys, GTK_PHASE_BUBBLE);
		g_signal_connect(tl_keys, "key-pressed", G_CALLBACK(tl_window_key_pressed), NULL);
		g_signal_connect(tl_keys, "key-released", G_CALLBACK(tl_window_key_released), NULL);
		gtk_widget_add_controller(window, tl_keys);
	}
'''
assert s.count(old2)==1, s.count(old2)
s=s.replace(old2,new2,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_window_keys" "$M"
