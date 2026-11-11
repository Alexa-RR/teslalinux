#!/bin/sh
# teslalinux: surfaceDestroyed after the replacing window has drawn (tl_surface_destroy_order).
# AOSP keeps the covered activity's window until the new activity's window is drawn, so the new
# SurfaceView's surfaceCreated comes before the old one's surfaceDestroyed (which follows onStop).
# ATL's GtkStack page switch unmapped the old decor during the new activity's onCreate and
# delivered surfaceDestroyed at once: Waze's native map saw a moment with no surface at all and
# crashed on the next eglCreateWindowSurface (trip overview). The destroy now runs from a
# G_PRIORITY_DEFAULT_IDLE source, below the HIGH_IDLE+20 that delivers surfaceCreated, and a map
# before it runs cancels it (the surface simply stays). Sorts after native-surfaceview-destroy.sh,
# whose on_unrealize it replaces. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
S=src/api-impl-jni/widgets/android_view_SurfaceView.c
if ! grep -q "tl_surface_destroy_order" "$S"; then
python3 - "$S" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old_struct='''struct jni_callback_data {
	JavaVM *jvm;
	jobject this;
	jclass this_class;
	gint resize_width;
	gint resize_height;
};
'''
new_struct='''struct jni_callback_data {
	JavaVM *jvm;
	jobject this;
	jclass this_class;
	gint resize_width;
	gint resize_height;
	guint destroy_source; /* tl_surface_destroy_order: pending surfaceDestroyed */
};
'''
assert s.count(old_struct)==1, s.count(old_struct)
s=s.replace(old_struct,new_struct,1)
old='''static void on_unrealize(GtkWidget *self, struct jni_callback_data *d)
{
	JNIEnv *env = get_jni_env();
	jmethodID surface_destroyed = _METHOD(handle_cache.surface_view.class, "surfaceDestroyed", "()V"); /* private: look it up on SurfaceView itself */
	(*env)->CallVoidMethod(env, d->this, surface_destroyed);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

static void on_realize(GtkWidget *self, struct jni_callback_data *d)
{
	g_idle_add_full(G_PRIORITY_HIGH_IDLE + 20, G_SOURCE_FUNC(on_realize_delayed), d, NULL);
}
'''
new='''/* tl_surface_destroy_order: AOSP destroys the surface once the window is hidden, after the window
 * that replaced it has drawn (its surfaceCreated runs first, from the HIGH_IDLE+20 source) */
static gboolean on_unrealize_delayed(struct jni_callback_data *d)
{
	d->destroy_source = 0;
	JNIEnv *env = get_jni_env();
	jmethodID surface_destroyed = _METHOD(handle_cache.surface_view.class, "surfaceDestroyed", "()V"); /* private: look it up on SurfaceView itself */
	(*env)->CallVoidMethod(env, d->this, surface_destroyed);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	return G_SOURCE_REMOVE;
}

static void on_unrealize(GtkWidget *self, struct jni_callback_data *d)
{
	if (!d->destroy_source)
		d->destroy_source = g_idle_add_full(G_PRIORITY_DEFAULT_IDLE, G_SOURCE_FUNC(on_unrealize_delayed), d, NULL);
}

static void on_realize(GtkWidget *self, struct jni_callback_data *d)
{
	if (d->destroy_source) {
		/* shown again before the surface went: it stays, no destroyed/created pair */
		g_source_remove(d->destroy_source);
		d->destroy_source = 0;
		return;
	}
	g_idle_add_full(G_PRIORITY_HIGH_IDLE + 20, G_SOURCE_FUNC(on_realize_delayed), d, NULL);
}
'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
old_init='''	callback_data->this_class = _REF((*env)->FindClass(env, "android/view/SurfaceView"));
'''
new_init='''	callback_data->this_class = _REF((*env)->FindClass(env, "android/view/SurfaceView"));
	callback_data->destroy_source = 0;
'''
assert s.count(old_init)==1, s.count(old_init)
s=s.replace(old_init,new_init,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_surface_destroy_order" "$S"
