#!/bin/sh
# teslalinux: deliver every SurfaceView frame through GTK's snapshot caching.
#
# GTK only re-runs a widget's snapshot when the widget is flagged (gtk_widget_queue_draw); the flag
# propagation stops at the first ancestor that is already flagged, and an ancestor that Java draws
# through a recorded display list may stay flagged forever without ever being visited, so a new
# frame texture did not reliably reach the toplevel (no render queued) or the re-record of the
# Compose layer (cached child node reused). Two measures:
#  1. surface_view_widget_set_texture flags every ancestor up to the toplevel individually and marks
#     the widget "tl-frame-pending";
#  2. ViewGroup.native_drawChildren/native_drawChild flag the path from a pending SurfaceViewWidget up
#     to the child being snapshotted, so gtk_widget_snapshot_child runs the real snapshot functions.
# surface_view_widget_snapshot clears the mark. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
W=src/api-impl-jni/widgets/android_view_SurfaceView.c
# this anchors on the line native-surfaceview-invalidate.sh adds; run it first (it is idempotent)
sh "$(dirname "$0")/native-surfaceview-invalidate.sh" >/dev/null
if ! grep -q "tl_flag_ancestors" "$W"; then
python3 - "$W" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
key=chr(10)+"void surface_view_widget_set_texture("
assert s.count(key) == 1, s.count(key)
helper='''
/* teslalinux: flag the whole ancestor chain so the toplevel queues a render even when an
 * intermediate widget is already flagged (gtk_widget_queue_draw stops there). */
static void tl_flag_ancestors(GtkWidget *widget)
{
	for (GtkWidget *w = widget; w; w = gtk_widget_get_parent(w))
		gtk_widget_queue_draw(w);
}

void surface_view_widget_set_texture('''
s=s.replace(key, helper, 1)
old='''	gtk_widget_queue_draw(GTK_WIDGET(surface_view_widget));
	tl_invalidate_java_view(GTK_WIDGET(surface_view_widget));
'''
new='''	g_object_set_data(G_OBJECT(surface_view_widget), "tl-frame-pending", GINT_TO_POINTER(1));
	tl_flag_ancestors(GTK_WIDGET(surface_view_widget));
	tl_invalidate_java_view(GTK_WIDGET(surface_view_widget));
'''
assert s.count(old) == 1, s.count(old)
s=s.replace(old, new, 1)
old2='''	if (surface_view_widget->texture) {
		graphene_rect_t bounds = GRAPHENE_RECT_INIT(0, 0, gtk_widget_get_width(widget), gtk_widget_get_height(widget));
'''
new2='''	g_object_set_data(G_OBJECT(widget), "tl-frame-pending", NULL);
	if (surface_view_widget->texture) {
		graphene_rect_t bounds = GRAPHENE_RECT_INIT(0, 0, gtk_widget_get_width(widget), gtk_widget_get_height(widget));
'''
assert s.count(old2) == 1, s.count(old2)
s=s.replace(old2, new2, 1)
open(p,'w').write(s)
PY
fi
grep -c "tl_flag_ancestors\|tl-frame-pending" "$W"

V=src/api-impl-jni/views/android_view_ViewGroup.c
if ! grep -q "tl_prepare_child_snapshot" "$V"; then
python3 - "$V" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
key=chr(10)+"JNIEXPORT void JNICALL Java_android_view_ViewGroup_native_1drawChildren("
assert s.count(key) == 1, s.count(key)
helper='''
/* teslalinux: a SurfaceView below `child` has a frame nobody snapshotted yet: flag the path from it
 * up to `child` so the following gtk_widget_snapshot_child() re-runs the snapshot functions instead
 * of appending cached render nodes. Depth-limited walk; SurfaceView trees are shallow. */
static void tl_prepare_child_snapshot(GtkWidget *child, GtkWidget *w, int depth)
{
	if (depth > 8)
		return;
	if (g_object_get_data(G_OBJECT(w), "tl-frame-pending")) {
		for (GtkWidget *a = w; a; a = gtk_widget_get_parent(a)) {
			gtk_widget_queue_draw(a);
			if (a == child)
				break;
		}
		return;
	}
	for (GtkWidget *c = gtk_widget_get_first_child(w); c; c = gtk_widget_get_next_sibling(c))
		tl_prepare_child_snapshot(child, c, depth + 1);
}

JNIEXPORT void JNICALL Java_android_view_ViewGroup_native_1drawChildren('''
s=s.replace(key, helper, 1)
old='''	gtk_widget_snapshot_child(&wrapper->parent_instance, wrapper->child, snapshot);
}
'''
new='''	tl_prepare_child_snapshot(wrapper->child, wrapper->child, 0);
	gtk_widget_snapshot_child(&wrapper->parent_instance, wrapper->child, snapshot);
}
'''
assert s.count(old) == 1, s.count(old)
s=s.replace(old, new, 1)
old2='''	gtk_widget_queue_draw(child); // FIXME: why didn't compose UI invalidate the child?
	gtk_widget_snapshot_child(widget, child, snapshot);
'''
new2='''	gtk_widget_queue_draw(child); // FIXME: why didn't compose UI invalidate the child?
	tl_prepare_child_snapshot(child, child, 0);
	gtk_widget_snapshot_child(widget, child, snapshot);
'''
assert s.count(old2) == 1, s.count(old2)
s=s.replace(old2, new2, 1)
open(p,'w').write(s)
PY
fi
grep -c "tl_prepare_child_snapshot" "$V"
