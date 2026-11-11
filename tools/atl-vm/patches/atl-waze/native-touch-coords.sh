#!/bin/sh
# teslalinux: a touch event forwarded by Java code (ViewGroup.dispatchTouchEvent -> native pick ->
# synthetic propagation) was delivered to every view on the way with the coordinates of the
# dispatching group, so a view that forwards again (a custom dispatchTouchEvent, Compose interop)
# picked with the wrong point and found nothing. AOSP transforms the event into each child's
# coordinate space (ViewGroup.dispatchTransformedTouchEvent); do the same here with GTK's widget
# transforms, restoring the location afterwards. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
V=src/api-impl-jni/views/android_view_ViewGroup.c
if ! grep -q "tl_relocate_event" "$V"; then
python3 - "$V" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	int action = _GET_INT_FIELD(motion_event, "action");
	return view_dispatch_motionevent(env, wrapper, phase, motion_event, motion_event, action);
}
'''
new='''	int action = _GET_INT_FIELD(motion_event, "action");
	/* tl_relocate_event: the event is in the coordinates of `toplevel`; the view expects its own */
	static jmethodID get_x, get_y, set_location;
	if (!set_location) {
		get_x = (*env)->GetMethodID(env, handle_cache.motion_event.class, "getX", "()F");
		get_y = (*env)->GetMethodID(env, handle_cache.motion_event.class, "getY", "()F");
		set_location = (*env)->GetMethodID(env, handle_cache.motion_event.class, "setLocation", "(FF)V");
	}
	jfloat orig_x = (*env)->CallFloatMethod(env, motion_event, get_x);
	jfloat orig_y = (*env)->CallFloatMethod(env, motion_event, get_y);
	graphene_point_t local;
	bool relocated = false;
	if (widget != toplevel && gtk_widget_compute_point(toplevel, widget, &GRAPHENE_POINT_INIT(orig_x, orig_y), &local)) {
		(*env)->CallVoidMethod(env, motion_event, set_location, local.x, local.y);
		relocated = true;
	}
	bool ret = view_dispatch_motionevent(env, wrapper, phase, motion_event, motion_event, action);
	if (relocated)
		(*env)->CallVoidMethod(env, motion_event, set_location, orig_x, orig_y);
	return ret;
}
'''
assert s.count(old) == 1, s.count(old)
s=s.replace(old,new,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_relocate_event" "$V"

# AOSP ViewGroup.dispatchTouchEvent: when no child takes the event, the group's own onInterceptTouchEvent /
# onTouchEvent run (super.dispatchTouchEvent). ATL skipped the dispatching group entirely when it had a
# custom dispatchTouchEvent (to avoid recursion); handle it as a plain view instead. Java learns whether
# the event is synthetic through View.tlNativeDispatchSynthetic (set before every custom dispatch).
VW=src/api-impl-jni/views/android_view_View.c
if ! grep -q "tl_force_plain_dispatch" "$VW"; then
python3 - "$VW" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	if (wrapper->custom_dispatch_touch) {
		ret = (*env)->CallBooleanMethod(env, this, handle_cache.view.dispatchTouchEvent, motion_event);
'''
new='''	if (wrapper->custom_dispatch_touch && !tl_force_plain_dispatch) {
		static jfieldID synthetic_field;
		if (!synthetic_field)
			synthetic_field = (*env)->GetStaticFieldID(env, (*env)->FindClass(env, "android/view/View"), "tlNativeDispatchSynthetic", "Z");
		(*env)->SetStaticBooleanField(env, (*env)->FindClass(env, "android/view/View"), synthetic_field, is_synthetic);
		ret = (*env)->CallBooleanMethod(env, this, handle_cache.view.dispatchTouchEvent, motion_event);
'''
assert s.count(old) == 1, s.count(old)
s=s.replace(old,new,1)
key=chr(10)+"bool view_dispatch_motionevent("
assert s.count(key) == 1, s.count(key)
s=s.replace(key, chr(10)+"bool tl_force_plain_dispatch = false; /* set by the synthetic propagation for the dispatching group itself */"+key, 1)
open(p,'w').write(s)
PY
fi
grep -c "tl_force_plain_dispatch" "$VW"
if ! grep -q "tl_force_plain_dispatch" "$V"; then
python3 - "$V" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	if (widget == toplevel && wrapper->custom_dispatch_touch) {
		// don't self propagate if the widget already had its chance to handle the event
		return false;
	}
'''
new='''	bool plain = false;
	if (widget == toplevel && wrapper->custom_dispatch_touch) {
		/* the group that is dispatching: its own onInterceptTouchEvent/onTouchEvent, never its
		 * dispatchTouchEvent again (AOSP super.dispatchTouchEvent after the children declined) */
		tl_force_plain_dispatch = true;
		plain = true;
	}
'''
assert s.count(old) == 1, s.count(old)
s=s.replace(old,new,1)
old2='''	bool ret = view_dispatch_motionevent(env, wrapper, phase, motion_event, motion_event, action);
	if (relocated)
		(*env)->CallVoidMethod(env, motion_event, set_location, orig_x, orig_y);
	return ret;
'''
new2='''	bool ret = view_dispatch_motionevent(env, wrapper, phase, motion_event, motion_event, action);
	if (plain)
		tl_force_plain_dispatch = false;
	if (relocated)
		(*env)->CallVoidMethod(env, motion_event, set_location, orig_x, orig_y);
	return ret;
'''
assert s.count(old2) == 1, s.count(old2)
s=s.replace(old2,new2,1)
key=chr(10)+"static bool dispatch_motionevent_if_JavaWidget("
assert s.count(key) == 1, s.count(key)
s=s.replace(key, chr(10)+"extern bool tl_force_plain_dispatch;"+key, 1)
open(p,'w').write(s)
PY
fi
grep -c "tl_force_plain_dispatch" "$V"
