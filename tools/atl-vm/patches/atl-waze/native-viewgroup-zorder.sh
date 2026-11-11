#!/bin/sh
# teslalinux: ViewGroup.native_restackChild (tl_restack_child), for children drawn in Z order.
# A group's children are drawn (native_drawChildren) and picked in the order of their wrapper
# widgets among the group widget's children; the Java side (ViewGroup+zorder) walks AOSP's ordered
# child list from the end and moves each wrapper right before the next one. GTK 4 moves a widget
# that already has this parent instead of re-parenting it, so nothing is unrealized. Idempotent.
set -eu
F=src/api-impl-jni/views/android_view_ViewGroup.c
if ! grep -q "tl_restack_child" "$F"; then
cat >> "$F" <<'EOF'

/* ---- teslalinux: tl_restack_child ---- */
JNIEXPORT void JNICALL Java_android_view_ViewGroup_native_1restackChild(JNIEnv *env, jobject this, jlong widget_ptr, jlong child_ptr, jlong next_ptr)
{
	GtkWidget *parent = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *child = gtk_widget_get_parent(GTK_WIDGET(_PTR(child_ptr)));
	GtkWidget *next = next_ptr ? gtk_widget_get_parent(GTK_WIDGET(_PTR(next_ptr))) : NULL;
	if (!child || gtk_widget_get_parent(child) != parent || child == next)
		return;
	if (next && gtk_widget_get_parent(next) != parent)
		return;
	if (gtk_widget_get_next_sibling(child) == next)
		return; /* already in place (next NULL: already the last) */
	gtk_widget_insert_before(child, parent, next);
	gtk_widget_queue_draw(parent);
}
EOF
fi
grep -c "tl_restack_child" "$F"
