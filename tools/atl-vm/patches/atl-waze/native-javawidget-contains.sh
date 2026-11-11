#!/bin/sh
# teslalinux: hit testing of a view's own widget (tl_javawidget_contains). AOSP's ViewGroup
# dispatches a touch to the child whose laid-out bounds contain the point, deepest first, and the
# group itself gets onTouchEvent when no child takes it. ATL's WrapperWidget re-allocates the
# JavaWidget of a view group with a zero size whenever the Java layout has nothing new (the "x,y
# only" allocation), so GTK records the widget as 0x0 and gtk_widget_pick never picks it: a nested
# Compose root (AndroidView interop inside a fragment) got no touches, since the synthetic dispatch
# only delivers to JavaWidgets. The JavaWidget of a view whose class handles touches itself
# (overrides onTouchEvent or dispatchTouchEvent) answers contains() with the wrapper's real size,
# the size Java laid out. Plain containers stay transparent to the pick (GTK picks one deepest
# widget, so a full-screen container must not shadow its siblings the way AOSP's child-by-child
# dispatch never would). Run by build-atl.sh from ~/atl-src. Idempotent per file.
set -eu
H=src/api-impl-jni/widgets/WrapperWidget.h
C=src/api-impl-jni/widgets/WrapperWidget.c
V=src/api-impl-jni/views/android_view_View.c
if ! grep -q "tl_touch_target" "$H"; then
python3 - "$H" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='	gboolean custom_dispatch_touch;\n'
new='	gboolean custom_dispatch_touch;\n	gboolean tl_touch_target; /* tl_javawidget_contains: class overrides onTouchEvent/dispatchTouchEvent */\n'
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new,1))
PY
fi
if ! grep -q "tl_touch_target" "$C"; then
python3 - "$C" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	wrapper->custom_dispatch_touch = (dispatchtouchevent_method != handle_cache.view.dispatchTouchEvent && dispatchtouchevent_method != handle_cache.view_group.dispatchTouchEvent);
'''
new='''	wrapper->custom_dispatch_touch = (dispatchtouchevent_method != handle_cache.view.dispatchTouchEvent && dispatchtouchevent_method != handle_cache.view_group.dispatchTouchEvent);
	wrapper->tl_touch_target = ontouchevent_method != handle_cache.view.onTouchEvent || wrapper->custom_dispatch_touch; /* tl_javawidget_contains */
'''
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new,1))
PY
fi
if ! grep -q "tl_javawidget_contains" "$V"; then
python3 - "$V" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''static void java_widget_init(JavaWidget *java_widget) {}
static void java_widget_class_init(JavaWidgetClass *class)
{
	G_OBJECT_CLASS(class)->dispose = java_widget_dispose;
}
'''
new='''static void java_widget_init(JavaWidget *java_widget) {}
/* tl_javawidget_contains: a touch-handling view's laid-out bounds, not the lazy GTK allocation */
static gboolean java_widget_contains(GtkWidget *widget, double x, double y)
{
	GtkWidget *parent = gtk_widget_get_parent(widget);
	int width = gtk_widget_get_width(widget);
	int height = gtk_widget_get_height(widget);
	if (parent && WRAPPER_IS_WIDGET(parent) && WRAPPER_WIDGET(parent)->tl_touch_target) {
		WrapperWidget *wrapper = WRAPPER_WIDGET(parent);
		if (wrapper->real_width > width)
			width = wrapper->real_width;
		if (wrapper->real_height > height)
			height = wrapper->real_height;
	}
	return x >= 0 && y >= 0 && x < width && y < height;
}
static void java_widget_class_init(JavaWidgetClass *class)
{
	G_OBJECT_CLASS(class)->dispose = java_widget_dispose;
	GTK_WIDGET_CLASS(class)->contains = java_widget_contains;
}
'''
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new,1))
PY
fi
grep -c "tl_javawidget_contains" "$V"
