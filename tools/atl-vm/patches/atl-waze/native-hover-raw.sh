#!/bin/sh
# teslalinux: hover raw coordinates without a current event (tl_hover_raw). GTK synthesizes the
# enter/motion of a widget that appears under the pointer (a layout change, a new activity page)
# without a GdkEvent, so gtk_event_controller_get_current_event() is NULL there; ATL passed it to
# gdk_event_get_position (Gdk-CRITICAL) and sent uninitialised raw coordinates to the app. AOSP's
# MotionEvent raw coordinates are the pointer's screen position: the callback's widget coordinates
# converted to the toplevel. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
V=src/api-impl-jni/views/android_view_View.c
if ! grep -q "tl_hover_raw" "$V"; then
python3 - "$V" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
helper='''/* tl_hover_raw: the pointer's position in the toplevel (AOSP raw coordinates), from the current
 * event when there is one, else from the widget-relative position of the callback */
static void tl_hover_raw(GtkEventController *controller, double x, double y, double *raw_x, double *raw_y)
{
	GdkEvent *event = gtk_event_controller_get_current_event(controller);
	if (event) {
		gdk_event_get_position(event, raw_x, raw_y);
		return;
	}
	GtkWidget *widget = gtk_event_controller_get_widget(controller);
	GtkRoot *root = widget ? gtk_widget_get_root(widget) : NULL;
	graphene_point_t p;
	if (root && gtk_widget_compute_point(widget, GTK_WIDGET(root), &GRAPHENE_POINT_INIT(x, y), &p)) {
		*raw_x = p.x;
		*raw_y = p.y;
	} else {
		*raw_x = x;
		*raw_y = y;
	}
}

static void hover_enter_cb(GtkEventControllerMotion *controller, double x, double y, WrapperWidget *wrapper)
'''
old_enter='''static void hover_enter_cb(GtkEventControllerMotion *controller, double x, double y, WrapperWidget *wrapper)
{
	double raw_x;
	double raw_y;
	GdkEvent *event = gtk_event_controller_get_current_event(GTK_EVENT_CONTROLLER(controller));
	gdk_event_get_position(event, &raw_x, &raw_y);
'''
new_enter=helper+'''{
	double raw_x;
	double raw_y;
	tl_hover_raw(GTK_EVENT_CONTROLLER(controller), x, y, &raw_x, &raw_y);
'''
assert s.count(old_enter)==1, s.count(old_enter)
s=s.replace(old_enter,new_enter,1)
old_motion='''static void hover_motion_cb(GtkEventControllerMotion *controller, double x, double y, WrapperWidget *wrapper)
{
	double raw_x;
	double raw_y;
	GdkEvent *event = gtk_event_controller_get_current_event(GTK_EVENT_CONTROLLER(controller));
	gdk_event_get_position(event, &raw_x, &raw_y);
'''
new_motion='''static void hover_motion_cb(GtkEventControllerMotion *controller, double x, double y, WrapperWidget *wrapper)
{
	double raw_x;
	double raw_y;
	tl_hover_raw(GTK_EVENT_CONTROLLER(controller), x, y, &raw_x, &raw_y);
'''
assert s.count(old_motion)==1, s.count(old_motion)
s=s.replace(old_motion,new_motion,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_hover_raw" "$V"
