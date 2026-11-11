#!/bin/sh
# teslalinux: window focus reaches the views (tl_window_focus). AOSP's ViewRootImpl dispatches
# windowFocusChanged to the decor view tree (View.onWindowFocusChanged, hasWindowFocus) before the
# activity's callback; ATL only called Activity.onWindowFocusChanged. Compose keeps the window focus
# state for its focus/keyboard logic. The native calls go through Activity.tlWindowFocusChanged,
# which dispatches to the decor and then calls onWindowFocusChanged. Run by build-atl.sh from
# ~/atl-src. Idempotent.
set -eu
A=src/api-impl-jni/app/android_app_Activity.c
if ! grep -q "tl_window_focus" "$A"; then
python3 - "$A" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
n=s.count('handle_cache.activity.onWindowFocusChanged')
assert n>=2, n
s=s.replace('handle_cache.activity.onWindowFocusChanged', 'tl_window_focus_method(env)')
anchor='static void activity_close(JNIEnv *env, jobject activity)\n'
assert s.count(anchor)==1, s.count(anchor)
helper='''/* tl_window_focus: Activity.tlWindowFocusChanged dispatches to the decor views, then onWindowFocusChanged */
static jmethodID tl_window_focus_method(JNIEnv *env)
{
	static jmethodID method = NULL;
	if (!method)
		method = _METHOD(handle_cache.activity.class, "tlWindowFocusChanged", "(Z)V");
	return method;
}

'''
s=s.replace(anchor, helper+anchor, 1)
open(p,'w').write(s)
PY
fi
grep -c "tl_window_focus" "$A"
