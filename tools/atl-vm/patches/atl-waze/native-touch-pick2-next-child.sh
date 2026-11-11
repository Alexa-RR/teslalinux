#!/bin/sh
# teslalinux: AOSP ViewGroup.dispatchTouchEvent tries the children under the point one after the
# other (topmost first) until one takes the touch (tl_next_child). ATL's synthetic dispatch asked
# GTK for the single topmost widget and gave up when its chain declined, so a transparent overlay
# (an empty Compose root above the search results sheet) swallowed every tap meant for the view
# underneath. A declined pick is hidden from picking and the pick is repeated; the widgets are
# restored afterwards. Sorts after native-touch-pick-trace.sh, whose code it anchors on. Run by
# build-atl.sh from ~/atl-src. Idempotent.
set -eu
V=src/api-impl-jni/views/android_view_ViewGroup.c
if ! grep -q "tl_next_child" "$V"; then
python3 - "$V" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	GtkWidget *picked_child = gtk_widget_pick(widget, x, y, GTK_PICK_DEFAULT);
	if (getenv("ATL_DEBUG_TOUCH"))
		fprintf(stderr, "[touch] native_dispatchTouchEvent on %s (%dx%d) at %.0f,%.0f picks %s%c", G_OBJECT_TYPE_NAME(widget), gtk_widget_get_width(widget), gtk_widget_get_height(widget), x, y, picked_child ? G_OBJECT_TYPE_NAME(picked_child) : "nothing", 10);
	if (!picked_child)
		return false;
	if (getenv("ATL_DEBUG_TOUCH"))
		tl_pick_trace(picked_child, x, y, widget);

	return atl_propagate_synthetic_motionevent(picked_child, motion_event, widget);
'''
new='''	/* tl_next_child: AOSP tries the children under the point one after the other, topmost first */
	GtkWidget *hidden[8];
	gboolean hidden_target[8];
	int hidden_n = 0;
	bool handled = false;
	for (int attempt = 0; attempt < 8; attempt++) {
		GtkWidget *picked_child = gtk_widget_pick(widget, x, y, GTK_PICK_DEFAULT);
		if (getenv("ATL_DEBUG_TOUCH"))
			fprintf(stderr, "[touch] native_dispatchTouchEvent on %s (%dx%d) at %.0f,%.0f picks %s (attempt %d)%c", G_OBJECT_TYPE_NAME(widget), gtk_widget_get_width(widget), gtk_widget_get_height(widget), x, y, picked_child ? G_OBJECT_TYPE_NAME(picked_child) : "nothing", attempt, 10);
		if (!picked_child || picked_child == widget)
			break;
		if (getenv("ATL_DEBUG_TOUCH"))
			tl_pick_trace(picked_child, x, y, widget);
		handled = atl_propagate_synthetic_motionevent(picked_child, motion_event, widget);
		if (handled)
			break;
		/* this child declined: hide it from the pick and look for the next one under the point */
		hidden[hidden_n] = picked_child;
		hidden_target[hidden_n] = gtk_widget_get_can_target(picked_child);
		hidden_n++;
		gtk_widget_set_can_target(picked_child, FALSE);
	}
	for (int i = 0; i < hidden_n; i++)
		gtk_widget_set_can_target(hidden[i], hidden_target[i]);
	return handled;
'''
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new,1))
PY
fi
grep -c "tl_next_child" "$V"
