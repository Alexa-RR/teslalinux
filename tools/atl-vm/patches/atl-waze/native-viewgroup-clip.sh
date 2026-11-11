#!/bin/sh
# teslalinux: ViewGroup clipChildren and clipToPadding as AOSP draws them (tl_layout_clip; part of
# the layout work, named native-viewgroup-* so it sorts after native-node-debug.sh and
# native-surfaceview-frames.sh, whose output it anchors on).
#
# AOSP (HWUI): a child's RenderNode clips to the child's bounds only while its parent has
# clipChildren (ViewGroup.addViewInner / setClipChildren -> RenderNode.setClipToBounds), and
# ViewGroup.dispatchDraw clips the children to the padding box while clipToPadding is set and the
# group has padding. ATL clipped every wrapper to its own size, always, and never clipped to padding.
#
#  - wrapper_widget_snapshot clips to the wrapper's size unless the wrapper carries "tl-no-clip"
#    (set by ViewGroup.native_setChildClipsToBounds while the parent's clipChildren is false); such
#    a wrapper also gets GTK_OVERFLOW_VISIBLE, which _setOnTouchListener would otherwise force to
#    HIDDEN (GTK clips a HIDDEN widget's snapshot itself).
#  - a group wrapper carrying "tl-padding-clip" (ViewGroup.native_setPaddingClip, clipToPadding on)
#    clips the snapshot of its children widget to the padding box (left/top padding, size minus
#    the padding), in both drawing paths: the plain wrapper snapshot and View.dispatchDraw ->
#    native_drawChildren. The padding is read from the Java view at draw time, so every way the
#    padding changes (setPadding, fitsSystemWindows insets, XML) is honoured.
# Run by build-atl.sh from ~/atl-src. Idempotent per file.
set -eu
W=src/api-impl-jni/widgets/WrapperWidget.c
H=src/api-impl-jni/widgets/WrapperWidget.h
V=src/api-impl-jni/views/android_view_View.c
G=src/api-impl-jni/views/android_view_ViewGroup.c

if ! grep -q "tl_wrapper_push_padding_clip" "$H"; then
python3 - "$H" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = 'void _setOnTouchListener(JNIEnv *env, jobject this, GtkWidget *widget);\n'
new = old + '/* tl_layout_clip: pushes the clipToPadding clip of a group wrapper; TRUE when a clip was pushed */\ngboolean tl_wrapper_push_padding_clip(WrapperWidget *wrapper, GtkSnapshot *snapshot);\n'
assert s.count(old) == 1, s.count(old)
open(p, 'w').write(s.replace(old, new, 1))
PY
fi

if ! grep -q "tl_layout_clip" "$W"; then
python3 - "$W" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()

old = 'static void wrapper_widget_snapshot(GtkWidget *widget, GdkSnapshot *snapshot)\n{\n'
new = '''/* tl_layout_clip: AOSP ViewGroup.dispatchDraw clips the children to the padding box while
 * clipToPadding is set (FLAG_CLIP_TO_PADDING) and the group has padding (FLAG_PADDING_NOT_NULL) */
gboolean tl_wrapper_push_padding_clip(WrapperWidget *wrapper, GtkSnapshot *snapshot)
{
	if (!g_object_get_data(G_OBJECT(wrapper), "tl-padding-clip"))
		return FALSE;
	if (wrapper->real_width <= 0 || wrapper->real_height <= 0 || !wrapper->jobj)
		return FALSE;
	JNIEnv *env = get_jni_env();
	if ((*env)->IsSameObject(env, wrapper->jobj, NULL))
		return FALSE;
	static jfieldID f_left, f_top, f_right, f_bottom;
	if (!f_left) {
		f_left = (*env)->GetFieldID(env, handle_cache.view.class, "paddingLeft", "I");
		f_top = (*env)->GetFieldID(env, handle_cache.view.class, "paddingTop", "I");
		f_right = (*env)->GetFieldID(env, handle_cache.view.class, "paddingRight", "I");
		f_bottom = (*env)->GetFieldID(env, handle_cache.view.class, "paddingBottom", "I");
	}
	int left = (*env)->GetIntField(env, wrapper->jobj, f_left);
	int top = (*env)->GetIntField(env, wrapper->jobj, f_top);
	int right = (*env)->GetIntField(env, wrapper->jobj, f_right);
	int bottom = (*env)->GetIntField(env, wrapper->jobj, f_bottom);
	if (!left && !top && !right && !bottom)
		return FALSE;
	float width = wrapper->real_width - left - right;
	float height = wrapper->real_height - top - bottom;
	gtk_snapshot_push_clip(snapshot, &GRAPHENE_RECT_INIT(left, top, width > 0 ? width : 0, height > 0 ? height : 0));
	return TRUE;
}

static void wrapper_widget_snapshot(GtkWidget *widget, GdkSnapshot *snapshot)
{
'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)

old = '''	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	if (wrapper->real_height > 0 && wrapper->real_width > 0) {
		gtk_snapshot_push_clip(snapshot, &GRAPHENE_RECT_INIT(0, 0, wrapper->real_width, wrapper->real_height));
	}
'''
new = '''	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	/* tl_layout_clip: the view clips to its bounds unless its parent has clipChildren off */
	const gboolean tl_clip_bounds = wrapper->real_height > 0 && wrapper->real_width > 0 && !g_object_get_data(G_OBJECT(wrapper), "tl-no-clip");
	if (tl_clip_bounds) {
		gtk_snapshot_push_clip(snapshot, &GRAPHENE_RECT_INIT(0, 0, wrapper->real_width, wrapper->real_height));
	}
'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)

old = '''	if (wrapper->real_height > 0 && wrapper->real_width > 0) {
		gtk_snapshot_pop(snapshot);
	}
'''
new = '''	if (tl_clip_bounds) {
		gtk_snapshot_pop(snapshot);
	}
'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)

old = '''			gtk_widget_snapshot_child(widget, child, target);
			child = gtk_widget_get_next_sibling(child);
'''
new = '''			/* tl_layout_clip: the children widget of a group clips to the padding box */
			const gboolean tl_padding_clip = child == wrapper->child && tl_wrapper_push_padding_clip(wrapper, target);
			gtk_widget_snapshot_child(widget, child, target);
			if (tl_padding_clip)
				gtk_snapshot_pop(target);
			child = gtk_widget_get_next_sibling(child);
'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)
open(p, 'w').write(s)
PY
fi

if ! grep -q "tl_layout_clip" "$V"; then
python3 - "$V" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '''	wrapper->needs_allocation = true;
	gtk_widget_set_overflow(GTK_WIDGET(wrapper), GTK_OVERFLOW_HIDDEN);
'''
new = '''	wrapper->needs_allocation = true;
	/* tl_layout_clip: a child of a group with clipChildren off may draw outside its bounds */
	gtk_widget_set_overflow(GTK_WIDGET(wrapper), g_object_get_data(G_OBJECT(wrapper), "tl-no-clip") ? GTK_OVERFLOW_VISIBLE : GTK_OVERFLOW_HIDDEN);
'''
assert s.count(old) == 1, s.count(old)
open(p, 'w').write(s.replace(old, new, 1))
PY
fi

if ! grep -q "tl_layout_clip" "$G"; then
python3 - "$G" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
old = '''	tl_prepare_child_snapshot(wrapper->child, wrapper->child, 0);
	gtk_widget_snapshot_child(&wrapper->parent_instance, wrapper->child, snapshot);
}
'''
new = '''	tl_prepare_child_snapshot(wrapper->child, wrapper->child, 0);
	/* tl_layout_clip: AOSP dispatchDraw clips the children to the padding box (clipToPadding) */
	const gboolean tl_padding_clip = tl_wrapper_push_padding_clip(wrapper, snapshot);
	gtk_widget_snapshot_child(&wrapper->parent_instance, wrapper->child, snapshot);
	if (tl_padding_clip)
		gtk_snapshot_pop(snapshot);
}
'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)
s += '''
/* ---- teslalinux: tl_layout_clip ---- */
/* AOSP RenderNode.setClipToBounds(parent clipChildren): whether the child's drawing (its own and its
 * descendants') is clipped to the child's bounds */
JNIEXPORT void JNICALL Java_android_view_ViewGroup_native_1setChildClipsToBounds(JNIEnv *env, jobject this, jlong child_ptr, jboolean clip)
{
	GtkWidget *wrapper = gtk_widget_get_parent(GTK_WIDGET(_PTR(child_ptr)));
	if (!wrapper || !WRAPPER_IS_WIDGET(wrapper))
		return;
	gboolean was_clipped = !g_object_get_data(G_OBJECT(wrapper), "tl-no-clip");
	if (was_clipped == !!clip)
		return;
	g_object_set_data(G_OBJECT(wrapper), "tl-no-clip", clip ? NULL : GINT_TO_POINTER(1));
	if (!clip)
		gtk_widget_set_overflow(wrapper, GTK_OVERFLOW_VISIBLE);
	else if (g_object_get_data(G_OBJECT(wrapper), "on_touch_listener"))
		gtk_widget_set_overflow(wrapper, GTK_OVERFLOW_HIDDEN); /* what _setOnTouchListener sets */
	gtk_widget_queue_draw(wrapper);
}

/* AOSP FLAG_CLIP_TO_PADDING: the group's children are clipped to its padding box when drawn */
JNIEXPORT void JNICALL Java_android_view_ViewGroup_native_1setPaddingClip(JNIEnv *env, jobject this, jlong widget_ptr, jboolean enabled)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *wrapper = gtk_widget_get_parent(widget);
	if (!wrapper || !WRAPPER_IS_WIDGET(wrapper))
		return;
	g_object_set_data(G_OBJECT(wrapper), "tl-padding-clip", enabled ? GINT_TO_POINTER(1) : NULL);
	gtk_widget_queue_draw(widget);
	gtk_widget_queue_draw(wrapper);
}
'''
open(p, 'w').write(s)
PY
fi
grep -c "tl_layout_clip" "$W" "$V" "$G"
