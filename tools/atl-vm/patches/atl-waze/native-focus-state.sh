#!/bin/sh
# teslalinux: view focus state from the window's GTK focus (tl_focus_state). AOSP keeps one focused
# view per window; View.hasFocus is true when the view or a descendant is it, findFocus returns it,
# clearFocus gives it up. ATL answered hasFocus with false, findFocus with the view itself and made
# clearFocus a no-op, while the real focus lived in GTK (gtk_widget_grab_focus in requestFocus).
# These natives read and change that GTK focus: the focused view is the nearest WrapperWidget
# (with a Java view) at or above the root's focus widget, which also covers a GtkText inside an
# EditText. A deferred focus request (native-focus-on-map.sh, "tl-focus-pending") counts as the
# view's focus request and is dropped by clearFocus. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
V=src/api-impl-jni/views/android_view_View.c
if ! grep -q "tl_focus_state" "$V"; then
cat >> "$V" <<'EOF'

/* ---- teslalinux: view focus state from the window's GTK focus (tl_focus_state) ---- */

/* the widget that stands for the view in its parent: the WrapperWidget around the view's widget */
static GtkWidget *tl_focus_view_top(GtkWidget *widget)
{
	GtkWidget *wrapper = gtk_widget_get_parent(widget);
	return (wrapper && WRAPPER_IS_WIDGET(wrapper)) ? wrapper : widget;
}

/* the WrapperWidget of the view that owns the root's focus widget, or NULL */
static WrapperWidget *tl_focus_owner(GtkWidget *widget)
{
	GtkRoot *root = gtk_widget_get_root(widget);
	GtkWidget *focus = root ? gtk_root_get_focus(root) : NULL;
	for (GtkWidget *w = focus; w; w = gtk_widget_get_parent(w)) {
		if (WRAPPER_IS_WIDGET(w) && WRAPPER_WIDGET(w)->jobj)
			return WRAPPER_WIDGET(w);
	}
	return NULL;
}

JNIEXPORT jboolean JNICALL Java_android_view_View_nativeHasFocus(JNIEnv *env, jclass clazz, jlong widget_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkRoot *root = gtk_widget_get_root(widget);
	GtkWidget *focus = root ? gtk_root_get_focus(root) : NULL;
	if (!focus)
		return JNI_FALSE;
	GtkWidget *top = tl_focus_view_top(widget);
	return focus == top || gtk_widget_is_ancestor(focus, top);
}

JNIEXPORT jobject JNICALL Java_android_view_View_nativeFindFocus(JNIEnv *env, jclass clazz, jlong widget_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	WrapperWidget *owner = tl_focus_owner(widget);
	if (!owner)
		return NULL;
	GtkWidget *top = tl_focus_view_top(widget);
	if (GTK_WIDGET(owner) != top && !gtk_widget_is_ancestor(GTK_WIDGET(owner), top))
		return NULL;
	/* a weak reference: NULL once the view was collected */
	return (*env)->NewLocalRef(env, owner->jobj);
}

JNIEXPORT jboolean JNICALL Java_android_view_View_nativeClearFocus(JNIEnv *env, jclass clazz, jlong widget_ptr)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *top = tl_focus_view_top(widget);
	gboolean pending = g_object_get_data(G_OBJECT(widget), "tl-focus-pending") || g_object_get_data(G_OBJECT(top), "tl-focus-pending");
	g_object_set_data(G_OBJECT(widget), "tl-focus-pending", NULL);
	g_object_set_data(G_OBJECT(top), "tl-focus-pending", NULL);
	WrapperWidget *owner = tl_focus_owner(widget);
	if (owner && GTK_WIDGET(owner) == top) {
		gtk_root_set_focus(gtk_widget_get_root(widget), NULL);
		if (getenv("ATL_DEBUG_FOCUS"))
			fprintf(stderr, "[focus] clearFocus %s: window focus dropped\n", G_OBJECT_TYPE_NAME(widget));
		return JNI_TRUE;
	}
	return pending;
}
EOF
fi
grep -c "tl_focus_state" "$V"
