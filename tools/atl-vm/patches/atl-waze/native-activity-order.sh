#!/bin/sh
# teslalinux: AOSP activity transition order. Starting B from A: A.onPause, B.onCreate/onStart/
# onResume, A loses window focus, A.onStop (ATL stopped A completely before creating B, which apps
# read as "the app went to the background"). Finishing/switching back follows the same rule: the old
# activity is paused, the new one started and resumed, then the old one stopped. Run by
# build-atl.sh from ~/atl-src. Idempotent.
set -eu
A=src/api-impl-jni/app/android_app_Activity.c
if ! grep -q "activity_pause(JNIEnv" "$A"; then
python3 - "$A" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
NL=chr(10)
old_unfocus='''static void activity_unfocus(JNIEnv *env, jobject activity)
{
	if (!_GET_BOOL_FIELD(activity, "paused")) {
		(*env)->CallVoidMethod(env, activity, handle_cache.activity.onPause);
		if ((*env)->ExceptionCheck(env))
			(*env)->ExceptionDescribe(env);
	}

	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onStop);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);

	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onWindowFocusChanged, false);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}
'''
new_unfocus='''/* teslalinux: the transition is split into AOSP's steps (native-activity-order.sh) */
static void activity_pause(JNIEnv *env, jobject activity)
{
	if (!_GET_BOOL_FIELD(activity, "paused")) {
		(*env)->CallVoidMethod(env, activity, handle_cache.activity.onPause);
		if ((*env)->ExceptionCheck(env))
			(*env)->ExceptionDescribe(env);
	}
}

static void activity_window_focus(JNIEnv *env, jobject activity, jboolean focused)
{
	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onWindowFocusChanged, focused);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

static void activity_stop(JNIEnv *env, jobject activity)
{
	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onStop);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

/* the activity stays current (a start that was abandoned): back to resumed */
static void activity_resume_only(JNIEnv *env, jobject activity)
{
	if (_GET_BOOL_FIELD(activity, "finishing") || !_GET_BOOL_FIELD(activity, "paused"))
		return;
	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onResume);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	(*env)->CallVoidMethod(env, activity, handle_cache.activity.onPostResume);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

static void activity_unfocus(JNIEnv *env, jobject activity)
{
	activity_pause(env, activity);
	activity_stop(env, activity);
	activity_window_focus(env, activity, false);
}
'''
assert s.count(old_unfocus)==1, s.count(old_unfocus)
s=s.replace(old_unfocus,new_unfocus,1)

old_update='''	if (activity_current != activity_new) {
		if (activity_current)
			activity_unfocus(env, activity_current);
		activity_current = NULL;

		if (activity_new)
			activity_focus(env, activity_new);
		if (activity_new && _GET_BOOL_FIELD(activity_new, "finishing"))
			return;

		activity_current = activity_new;
	}
'''
new_update='''	if (activity_current != activity_new) {
		jobject activity_old = activity_current;
		if (activity_old) {
			activity_pause(env, activity_old);
			activity_window_focus(env, activity_old, false);
		}
		activity_current = NULL;

		if (activity_new)
			activity_focus(env, activity_new);
		if (activity_old)
			activity_stop(env, activity_old);
		if (activity_new && _GET_BOOL_FIELD(activity_new, "finishing"))
			return;

		activity_current = activity_new;
	}
'''
assert s.count(old_update)==1, s.count(old_update)
s=s.replace(old_update,new_update,1)

old_start='''void activity_start(JNIEnv *env, jobject activity_object)
{
	if (activity_current)
		activity_unfocus(env, activity_current);
	activity_current = NULL;
	/* -- run the activity's onCreate -- */
	(*env)->CallVoidMethod(env, activity_object, handle_cache.activity.onCreate, NULL);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);

	if (_GET_BOOL_FIELD(activity_object, "finishing")) { // finish() was called before the activity was created
		return;
	}

	(*env)->CallVoidMethod(env, activity_object, handle_cache.activity.onPostCreate, NULL);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);

	activity_backlog = g_list_prepend(activity_backlog, _REF(activity_object));

	activity_update_current(env);
}
'''
new_start='''void activity_start(JNIEnv *env, jobject activity_object)
{
	/* AOSP: the previous activity is paused first; it is stopped only once the new one is resumed */
	jobject activity_previous = activity_current;
	if (activity_previous)
		activity_pause(env, activity_previous);
	activity_current = NULL;
	/* -- run the activity's onCreate -- */
	(*env)->CallVoidMethod(env, activity_object, handle_cache.activity.onCreate, NULL);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);

	if (_GET_BOOL_FIELD(activity_object, "finishing")) { // finish() was called before the activity was created
		activity_current = activity_previous;
		if (activity_previous)
			activity_resume_only(env, activity_previous);
		return;
	}

	(*env)->CallVoidMethod(env, activity_object, handle_cache.activity.onPostCreate, NULL);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);

	activity_backlog = g_list_prepend(activity_backlog, _REF(activity_object));

	if (activity_previous)
		activity_window_focus(env, activity_previous, false);
	activity_update_current(env);
	if (activity_previous && activity_previous != activity_current)
		activity_stop(env, activity_previous);
}
'''
assert s.count(old_start)==1, s.count(old_start)
s=s.replace(old_start,new_start,1)
open(p,'w').write(s)
PY
fi
grep -c "activity_pause(JNIEnv" "$A"
