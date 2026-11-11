#!/bin/sh
# teslalinux: a View that draws itself and was invalidated is redrawn by the next parent recording
# that includes it, as in AOSP, where the invalidated flag is checked while the parent draws.
#
# wrapper_widget_queue_draw flags a WrapperWidget with a Java draw method from an idle callback (the
# call may come from inside a snapshot). When the parent re-recorded before that idle ran (Compose's
# AndroidViewHolder re-records its layer on onDescendantInvalidated), gtk_widget_snapshot_child
# reused the widget's cached node and the View's draw()/dispatchDraw() did not run. For an
# AndroidComposeView that is fatal: the layer that asked for the redraw stays in dirtyLayers, marked
# dirty, and ignores every later invalidate, so the view froze. Waze's map (a SurfaceView inside the
# AndroidComposeView of a ViewFactoryHolder) then stayed on its first frames, the loading grid,
# intermittently after a start.
# The widget is now also marked "tl-frame-pending" at once, the mark SurfaceView frames use: when a
# parent records its children (ViewGroup.native_drawChildren/native_drawChild,
# tl_prepare_child_snapshot) the path from a marked widget up to the child being recorded is flagged,
# so the View's own snapshot runs; the widget's snapshot clears the mark. Run by build-atl.sh from
# ~/atl-src. Idempotent.
set -eu
W=src/api-impl-jni/widgets/WrapperWidget.c
if ! grep -q "tl_pending_draw" "$W"; then
python3 - "$W" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = '''	if (wrapper->draw_method) {
		/* schedule the call to gtk_widget_queue_draw for a future event loop pass in case we're currently inside the snapshot */
'''
assert s.count(old) == 1, "wrapper_widget_queue_draw"
new = '''	if (wrapper->draw_method) {
		/* tl_pending_draw: a parent recording before the idle below finds the mark and snapshots this widget */
		g_object_set_data(G_OBJECT(wrapper), "tl-frame-pending", GINT_TO_POINTER(1));
		/* schedule the call to gtk_widget_queue_draw for a future event loop pass in case we're currently inside the snapshot */
'''
s = s.replace(old, new, 1)
old = '''static void wrapper_widget_snapshot(GtkWidget *widget, GdkSnapshot *snapshot)
{
	snapshot_in_progress++;

	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
'''
assert s.count(old) == 1, "wrapper_widget_snapshot head"
new = '''static void wrapper_widget_snapshot(GtkWidget *widget, GdkSnapshot *snapshot)
{
	snapshot_in_progress++;

	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	g_object_set_data(G_OBJECT(wrapper), "tl-frame-pending", NULL); /* tl_pending_draw: drawn now */
'''
s = s.replace(old, new, 1)
open(p, "w").write(s)
PY
fi
grep -c "tl_pending_draw" "$W"
