#!/bin/sh
# teslalinux: GL context for an unrooted SurfaceView (tl_surface_for_widget, part of the dialog
# overlay). A SurfaceView inside a dialog layer that is not in the window yet has no GTK native;
# egl.c derived its GdkGLContext from the widget's native surface and dereferenced NULL. The context
# belongs to the main toplevel's surface, where the layer will be composited. Sorts after
# native-egl-backbuffer.sh on purpose. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
E=src/libandroid/egl.c
if ! grep -q "tl_surface_for_widget" "$E"; then
python3 - "$E" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='gtk_native_get_surface(gtk_widget_get_native(GTK_WIDGET(atl_surface->surface_view_widget)))'
assert s.count(old)==2, s.count(old)
s=s.replace(old,'tl_surface_for_widget(GTK_WIDGET(atl_surface->surface_view_widget))')
anchor='G_DECLARE_FINAL_TYPE(ATLSurface, atl_surface, ATL, SURFACE, GObject);\n'
assert s.count(anchor)==1, s.count(anchor)
helper='''G_DECLARE_FINAL_TYPE(ATLSurface, atl_surface, ATL, SURFACE, GObject);

/* tl_surface_for_widget (tl_dialog_overlay): a SurfaceView inside a dialog layer that is not in the
 * window yet has no native; its GL context belongs to the main toplevel's surface, where the layer
 * will be composited */
static GdkSurface *tl_surface_for_widget(GtkWidget *widget)
{
	GtkNative *native = gtk_widget_get_native(widget);
	if (native)
		return gtk_native_get_surface(native);
	GList *tops = gtk_window_list_toplevels();
	GdkSurface *surface = NULL;
	for (GList *l = tops; l && !surface; l = l->next)
		surface = gtk_native_get_surface(GTK_NATIVE(l->data));
	g_list_free(tops);
	return surface;
}
'''
s=s.replace(anchor,helper,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_surface_for_widget" "$E"
