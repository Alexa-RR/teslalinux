#!/bin/sh
# teslalinux: surfaceDestroyed is synchronous when the view leaves its window (tl_surface_unroot).
# AOSP's SurfaceView.onDetachedFromWindow destroys the surface at once, before the activity's
# onDestroy; native-surfaceview-destroy2-order.sh defers a hide's surfaceDestroyed behind the
# replacing window's surfaceCreated, which must not apply to a finishing activity (its map view
# then got surfaceDestroyed after onDestroy and the native map crashed on the next surface).
# The "unroot" of the surface widget and its destruction flush a pending destroy. Sorts after
# native-surfaceview-destroy2-order.sh, whose code it anchors on. Run by build-atl.sh from
# ~/atl-src. Idempotent.
set -eu
S=src/api-impl-jni/widgets/android_view_SurfaceView.c
if ! grep -q "tl_surface_unroot" "$S"; then
python3 - "$S" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''static void on_realize(GtkWidget *self, struct jni_callback_data *d)
{
	if (d->destroy_source) {'''
new='''/* tl_surface_unroot: AOSP onDetachedFromWindow destroys the surface synchronously */
static void on_unroot(GtkWidget *self, struct jni_callback_data *d)
{
	if (d->destroy_source) {
		g_source_remove(d->destroy_source);
		d->destroy_source = 0;
		on_unrealize_delayed(d);
	}
}

static void on_realize(GtkWidget *self, struct jni_callback_data *d)
{
	if (d->destroy_source) {'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
old2='''	g_signal_connect(dummy, "unmap", G_CALLBACK(on_unrealize), callback_data);'''
new2='''	g_signal_connect(dummy, "unmap", G_CALLBACK(on_unrealize), callback_data);
	/* GTK4 has no "unroot" signal; a widget leaving a realized hierarchy is unrealized */
	g_signal_connect(dummy, "unrealize", G_CALLBACK(on_unroot), callback_data); /* tl_surface_unroot */'''
assert s.count(old2)==1, s.count(old2)
s=s.replace(old2,new2,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_surface_unroot" "$S"
