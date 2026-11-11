#!/bin/sh
# teslalinux: synthetic touch dispatch reaches every view, not only JavaWidget ones (tl_view_widget_dispatch).
# A view nested inside another view's custom dispatch (Compose interop: AndroidViewsHandler ->
# ViewFactoryHolder -> view) gets its MotionEvents from atl_propagate_synthetic_motionevent, which
# called into Java only for JavaWidget widgets. A view's own widget is the child of its WrapperWidget,
# but it is a JavaWidget only for some classes: a SurfaceView's is a GtkGraphicsOffload (Waze's map
# view, so the map never saw a touch: no pan, no pinch, no long press), a TextView's a label, an
# ImageView's a picture. AOSP dispatches to every View in the hierarchy, so the test becomes "the
# widget is the child of a WrapperWidget that carries a Java view". Runs after native-touch-coords.sh
# (tl_relocate_event). Idempotent.
set -eu
F=src/api-impl-jni/views/android_view_ViewGroup.c
if ! grep -q "tl_view_widget_dispatch" "$F"; then
python3 - "$F" <<'EOF'
import sys
f = sys.argv[1]
s = open(f).read()
old = """	if (!JAVA_IS_WIDGET(widget))
		return false;
	JNIEnv *env = get_jni_env();

	WrapperWidget *wrapper = WRAPPER_WIDGET(gtk_widget_get_parent(widget));"""
new = """	/* tl_view_widget_dispatch: any view's own widget (its WrapperWidget's child), not only JavaWidget:
	 * a SurfaceView's widget is a GtkGraphicsOffload, a TextView's a label */
	GtkWidget *view_parent = gtk_widget_get_parent(widget);
	if (!view_parent || !WRAPPER_IS_WIDGET(view_parent) || WRAPPER_WIDGET(view_parent)->child != widget
	    || !WRAPPER_WIDGET(view_parent)->jobj)
		return false;
	JNIEnv *env = get_jni_env();

	WrapperWidget *wrapper = WRAPPER_WIDGET(view_parent);
	if (getenv("ATL_DEBUG_TOUCH")) {
		static jmethodID pc, gx, gy, ga;
		if (!pc) {
			pc = (*env)->GetMethodID(env, handle_cache.motion_event.class, "getPointerCount", "()I");
			gx = (*env)->GetMethodID(env, handle_cache.motion_event.class, "getX", "(I)F");
			gy = (*env)->GetMethodID(env, handle_cache.motion_event.class, "getY", "(I)F");
			ga = (*env)->GetMethodID(env, handle_cache.motion_event.class, "getAction", "()I");
		}
		jint n = (*env)->CallIntMethod(env, motion_event, pc);
		jint act = (*env)->CallIntMethod(env, motion_event, ga);
		fprintf(stderr, "[touch]   view widget %s phase=%d action=0x%x pointers=%d", G_OBJECT_TYPE_NAME(widget), phase, act, n);
		for (jint i = 0; i < n && i < 4; i++)
			fprintf(stderr, " p%d=%.0f,%.0f", i, (*env)->CallFloatMethod(env, motion_event, gx, i), (*env)->CallFloatMethod(env, motion_event, gy, i));
		fprintf(stderr, "\\n");
	}"""
assert s.count(old) == 1, "dispatch_motionevent_if_JavaWidget anchor"
s = s.replace(old, new)
open(f, "w").write(s)
EOF
fi
grep -c "tl_view_widget_dispatch" "$F"
