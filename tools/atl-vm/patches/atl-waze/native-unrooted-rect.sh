#!/bin/sh
# teslalinux: View.getGlobalVisibleRect on a view that is not in a window. ATL asked GTK for the
# native surface of an unrooted widget, which failed two assertions per call and filled the log
# (tens of thousands of lines per run: Waze measures detached views constantly). AOSP reports such
# a view's rect relative to its outermost ancestor, so do the same. Run by build-atl.sh from
# ~/atl-src. Idempotent.
set -eu
V=src/api-impl-jni/views/android_view_View.c
if ! grep -q "tl_unrooted_rect" "$V"; then
python3 - "$V" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	gboolean ret;
	GtkWidget *window = GTK_WIDGET(gtk_widget_get_native(widget));
	gtk_native_get_surface_transform(GTK_NATIVE(window), &off_x, &off_y);
	ret = gtk_widget_compute_point(widget, window, &point_in, &point_out);
	if (!ret)
		return false;
	_SET_INT_FIELD(rect, "left", point_out.x + off_x);
'''
new='''	gboolean ret;
	GtkNative *native = gtk_widget_get_native(widget);
	GtkWidget *window;
	if (native) {
		window = GTK_WIDGET(native);
		gtk_native_get_surface_transform(native, &off_x, &off_y);
	} else {
		/* tl_unrooted_rect: AOSP reports a detached view's rect relative to its outermost ancestor */
		window = widget;
		while (gtk_widget_get_parent(window))
			window = gtk_widget_get_parent(window);
		off_x = off_y = 0;
	}
	ret = gtk_widget_compute_point(widget, window, &point_in, &point_out);
	if (!ret)
		return false;
	_SET_INT_FIELD(rect, "left", point_out.x + off_x);
'''
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new))
PY
fi
grep -c "tl_unrooted_rect" "$V"
