#!/bin/sh
# teslalinux: AOSP SurfaceView destroys its surface when the view leaves the window or the window is
# hidden (surfaceDestroyed callbacks) and creates it again when it comes back. ATL only had the
# realize -> surfaceCreated half; the GTK counterpart of losing the window is "unrealize". Run by
# build-atl.sh from ~/atl-src. Idempotent.
set -eu
S=src/api-impl-jni/widgets/android_view_SurfaceView.c
if ! grep -q "on_unrealize" "$S"; then
python3 - "$S" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''static void on_realize(GtkWidget *self, struct jni_callback_data *d)
{'''
new='''/* teslalinux: the widget lost its window -> the surface is gone (AOSP surfaceDestroyed) */
static void on_unrealize(GtkWidget *self, struct jni_callback_data *d)
{
	JNIEnv *env = get_jni_env();
	jmethodID surface_destroyed = _METHOD(handle_cache.surface_view.class, "surfaceDestroyed", "()V"); /* private: look it up on SurfaceView itself */
	(*env)->CallVoidMethod(env, d->this, surface_destroyed);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

static void on_realize(GtkWidget *self, struct jni_callback_data *d)
{'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
old2='''	g_signal_connect(dummy, "realize", G_CALLBACK(on_realize), callback_data);'''
new2='''	/* AOSP: the surface exists while the view is shown in a visible window: GTK map/unmap (tl_window_attach
	 * keeps stopped activities' views realized, so realize/unrealize no longer follow visibility) */
	g_signal_connect(dummy, "map", G_CALLBACK(on_realize), callback_data);
	g_signal_connect(dummy, "unmap", G_CALLBACK(on_unrealize), callback_data);'''
assert s.count(old2)==1, s.count(old2)
s=s.replace(old2,new2,1)
open(p,'w').write(s)
PY
fi
grep -c "on_unrealize" "$S"
