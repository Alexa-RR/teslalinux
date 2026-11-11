#!/bin/sh
# teslalinux: View.draw(Canvas) draws a view that is not on screen, as AOSP does (a detached view
# drawn into a Bitmap: androidx View.drawToBitmap, map markers, view snapshots).
#
# ATL draws a view's background, content and children by snapshotting their GTK widgets with
# gtk_widget_snapshot_child(), which draws nothing for an unmapped widget. A view that is not in a
# window has no mapped widgets, so drawing it into a Bitmap gave a transparent bitmap: every map pin
# and route label Waze renders from an inflated layout (FrameLayout background, ImageView, TextView)
# came out empty. tl_snapshot_child() draws such a child the way GTK would draw it on screen: its
# transform and opacity, then its class's snapshot (plain containers: their children, recursively).
# GTK's allocation pass never runs for such a tree, so each view's widget is allocated to its
# Android size first, and the widget's CSS background/border (setBackgroundColor) is drawn as GTK
# draws it. It is used only when the parent is unmapped too, so a widget GTK hides inside a mapped
# tree stays hidden and drawing on screen is unchanged.
# Run by build-atl.sh from ~/atl-src (sorts after every script that edits these call sites). Idempotent.
set -eu
W=src/api-impl-jni/widgets/WrapperWidget.c
H=src/api-impl-jni/widgets/WrapperWidget.h
if ! grep -q "tl_snapshot_child" "$H"; then
python3 - "$W" "$H" src/api-impl-jni/views/android_view_View.c src/api-impl-jni/views/android_view_ViewGroup.c <<'PY'
import sys
w, h, v, vg = sys.argv[1:5]

def edit(path, pairs):
    s = open(path).read()
    for old, new in pairs:
        assert s.count(old) == 1, (path, old[:70], s.count(old))
        s = s.replace(old, new, 1)
    open(path, 'w').write(s)

s = open(h).read()
i = s.rindex("#endif")
s = s[:i] + '''/* native-zoffscreen-draw.sh: gtk_widget_snapshot_child() that also draws a view that is not on screen */
void tl_snapshot_child(GtkWidget *parent, GtkWidget *child, GtkSnapshot *snapshot);

''' + s[i:]
open(h, 'w').write(s)

s = open(w).read()
s += '''
/*
 * native-zoffscreen-draw.sh: gtk_widget_snapshot_child(), and for a child of an unmapped parent (a
 * view tree that is not in a window, drawn into a Bitmap by View.draw) what GTK would draw if it were
 * mapped: the child's transform relative to the parent, its opacity, then its class's snapshot (a plain
 * container's children through this function again). Invisible children are skipped as GTK skips them.
 */
/* the child's transform as 2D GSK transforms (translate, rotate, skew, scale), which every GSK
 * renderer draws; a matrix3d transform node is one the cairo renderer cannot draw */
static void tl_offscreen_transform(GtkSnapshot *snapshot, const graphene_matrix_t *m)
{
	double sx, ky, kx, sy, tx, ty;
	if (!graphene_matrix_to_2d(m, &sx, &ky, &kx, &sy, &tx, &ty)) {
		gtk_snapshot_transform_matrix(snapshot, m);
		return;
	}
	gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(tx, ty));
	if (kx == 0 && ky == 0) {
		if (sx != 1 || sy != 1)
			gtk_snapshot_scale(snapshot, sx, sy);
		return;
	}
	const double a = sqrt(sx * sx + ky * ky);
	if (a == 0)
		return;
	const double theta = atan2(ky, sx);
	const double c = cos(theta), s = sin(theta);
	const double k = kx * c + sy * s;
	const double d = sy * c - kx * s;
	gtk_snapshot_rotate(snapshot, theta * 180.0 / G_PI);
	if (k != 0 && d != 0) {
		GskTransform *skew = gsk_transform_skew(NULL, atan(k / d) * 180.0 / G_PI, 0);
		gtk_snapshot_transform(snapshot, skew);
		gsk_transform_unref(skew);
	}
	gtk_snapshot_scale(snapshot, a, d);
}

void tl_snapshot_child(GtkWidget *parent, GtkWidget *child, GtkSnapshot *snapshot)
{
	if (gtk_widget_get_mapped(parent) || gtk_widget_get_mapped(child)) {
		gtk_widget_snapshot_child(parent, child, snapshot);
		return;
	}
	if (!gtk_widget_get_visible(child) || !gtk_widget_get_child_visible(child))
		return;
	const double opacity = gtk_widget_get_opacity(child);
	if (opacity <= 0)
		return;
	/* a view's widgets get their Android size in GTK's allocation pass, which never runs for a tree
	 * outside a window: allocate the view to its laid-out size (its children follow) */
	if (WRAPPER_IS_WIDGET(child)) {
		WrapperWidget *wrapper = WRAPPER_WIDGET(child);
		if (gtk_widget_get_width(child) != wrapper->real_width || gtk_widget_get_height(child) != wrapper->real_height) {
			graphene_point_t origin;
			if (!gtk_widget_compute_point(child, parent, &GRAPHENE_POINT_INIT(0, 0), &origin))
				origin = GRAPHENE_POINT_INIT(0, 0);
			gtk_widget_size_allocate(child, &(GtkAllocation){.x = origin.x, .y = origin.y, .width = wrapper->real_width, .height = wrapper->real_height}, -1);
		}
	}
	graphene_matrix_t transform;
	if (!gtk_widget_compute_transform(child, parent, &transform))
		return;
	static void (*base_snapshot)(GtkWidget *, GtkSnapshot *);
	if (!base_snapshot)
		base_snapshot = GTK_WIDGET_CLASS(g_type_class_peek(GTK_TYPE_WIDGET))->snapshot;
	gtk_snapshot_save(snapshot);
	tl_offscreen_transform(snapshot, &transform);
	if (opacity < 1)
		gtk_snapshot_push_opacity(snapshot, opacity);
	/* the widget's CSS background and border (View.setBackgroundColor is widget CSS), as GTK draws
	 * them before the class's snapshot */
	const int width = gtk_widget_get_width(child), height = gtk_widget_get_height(child);
	if (width > 0 && height > 0) {
		G_GNUC_BEGIN_IGNORE_DEPRECATIONS
		GtkStyleContext *style = gtk_widget_get_style_context(child);
		gtk_snapshot_render_background(snapshot, style, 0, 0, width, height);
		gtk_snapshot_render_frame(snapshot, style, 0, 0, width, height);
		G_GNUC_END_IGNORE_DEPRECATIONS
	}
	GtkWidgetClass *klass = GTK_WIDGET_GET_CLASS(child);
	if (klass->snapshot == base_snapshot) {
		for (GtkWidget *c = gtk_widget_get_first_child(child); c; c = gtk_widget_get_next_sibling(c))
			tl_snapshot_child(child, c, snapshot);
	} else if (klass->snapshot) {
		klass->snapshot(child, snapshot);
	}
	if (opacity < 1)
		gtk_snapshot_pop(snapshot);
	gtk_snapshot_restore(snapshot);
}
'''
open(w, 'w').write(s)
edit(w, [('\t\t\tgtk_widget_snapshot_child(widget, child, target);', '\t\t\ttl_snapshot_child(widget, child, target);')])
edit(v, [('\t\tgtk_widget_snapshot_child(&wrapper->parent_instance, wrapper->background, snapshot);',
          '\t\ttl_snapshot_child(&wrapper->parent_instance, wrapper->background, snapshot);'),
         ('\tgtk_widget_snapshot_child(&wrapper->parent_instance, wrapper->child, snapshot);\n}',
          '\ttl_snapshot_child(&wrapper->parent_instance, wrapper->child, snapshot);\n}')])
edit(vg, [('\tgtk_widget_snapshot_child(&wrapper->parent_instance, wrapper->child, snapshot);',
           '\ttl_snapshot_child(&wrapper->parent_instance, wrapper->child, snapshot);'),
          ('\tgtk_widget_snapshot_child(widget, child, snapshot);\n}',
           '\ttl_snapshot_child(widget, child, snapshot);\n}')])
PY
fi
grep -c "tl_snapshot_child" "$W" src/api-impl-jni/views/android_view_View.c src/api-impl-jni/views/android_view_ViewGroup.c
