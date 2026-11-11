#!/bin/sh
# teslalinux (vcore): View.setClipBounds and View.setClipToOutline clip the view's drawing, as AOSP's
# RenderNode clips to its clip bounds and to its outline (rect/round rect): the widget keeps both
# clips and pushes them around its whole snapshot (background, content, children). ATL's setters were
# empty: AndroidX transitions (ChangeClipBounds), Compose layers and rounded "clipToOutline" cards
# drew unclipped. The Java side (View.java+vcore.frag) calls nativeSetClipBounds/nativeSetOutlineClip.
# Run by build-atl.sh from ~/atl-src. Idempotent (grep-guarded per file).
set -eu
H=src/api-impl-jni/widgets/WrapperWidget.h
C=src/api-impl-jni/widgets/WrapperWidget.c
V=src/api-impl-jni/views/android_view_View.c

if ! grep -q "tl_clip_bounds_set" "$H"; then
python3 - "$H" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = '\tint hover_y;\n};'
assert s.count(old) == 1, s.count(old)
new = ('\tint hover_y;\n'
       '\tgboolean tl_clip_bounds_set; /* tl_view_clip: View.setClipBounds */\n'
       '\tgraphene_rect_t tl_clip_bounds;\n'
       '\tgboolean tl_outline_clip; /* tl_view_clip: View.setClipToOutline with a (round) rect outline */\n'
       '\tGskRoundedRect tl_outline;\n'
       '};')
s = s.replace(old, new, 1)
open(p, 'w').write(s)
PY
fi

if ! grep -q "tl_view_clip" "$C"; then
python3 - "$C" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old_push = ('\tWrapperWidget *wrapper = WRAPPER_WIDGET(widget);\n'
            '\tif (wrapper->real_height > 0 && wrapper->real_width > 0) {\n'
            '\t\tgtk_snapshot_push_clip(snapshot, &GRAPHENE_RECT_INIT(0, 0, wrapper->real_width, wrapper->real_height));\n'
            '\t}\n')
assert s.count(old_push) == 1, s.count(old_push)
new_push = old_push + ('\t/* tl_view_clip: the view\'s clip bounds and outline clip (AOSP RenderNode clipping) */\n'
                       '\tint tl_view_clips = 0;\n'
                       '\tif (wrapper->tl_clip_bounds_set) {\n'
                       '\t\tgtk_snapshot_push_clip(snapshot, &wrapper->tl_clip_bounds);\n'
                       '\t\ttl_view_clips++;\n'
                       '\t}\n'
                       '\tif (wrapper->tl_outline_clip) {\n'
                       '\t\tgtk_snapshot_push_rounded_clip(snapshot, &wrapper->tl_outline);\n'
                       '\t\ttl_view_clips++;\n'
                       '\t}\n')
s = s.replace(old_push, new_push, 1)
old_pop = ('\tif (wrapper->real_height > 0 && wrapper->real_width > 0) {\n'
           '\t\tgtk_snapshot_pop(snapshot);\n'
           '\t}\n'
           '\n'
           '\tsnapshot_in_progress--;\n')
assert s.count(old_pop) == 1, s.count(old_pop)
new_pop = ('\twhile (tl_view_clips-- > 0)\n'
           '\t\tgtk_snapshot_pop(snapshot);\n') + old_pop
s = s.replace(old_pop, new_pop, 1)
open(p, 'w').write(s)
PY
fi

if ! grep -q "Java_android_view_View_nativeSetClipBounds" "$V"; then
cat >> "$V" <<'EOF'

/* tl_view_clip (native-vcore-view-clip.sh): the clips the WrapperWidget pushes around its drawing */
static WrapperWidget *tl_clip_wrapper(jlong widget_ptr)
{
	if (!widget_ptr)
		return NULL;
	GtkWidget *parent = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget_ptr)));
	return (parent && WRAPPER_IS_WIDGET(parent)) ? WRAPPER_WIDGET(parent) : NULL;
}

JNIEXPORT void JNICALL Java_android_view_View_nativeSetClipBounds(JNIEnv *env, jclass clazz, jlong widget_ptr, jboolean set, jint left, jint top, jint right, jint bottom)
{
	WrapperWidget *wrapper = tl_clip_wrapper(widget_ptr);
	if (!wrapper)
		return;
	wrapper->tl_clip_bounds_set = set;
	graphene_rect_init(&wrapper->tl_clip_bounds, left, top, MAX(right - left, 0), MAX(bottom - top, 0));
	gtk_widget_queue_draw(GTK_WIDGET(wrapper));
}

JNIEXPORT void JNICALL Java_android_view_View_nativeSetOutlineClip(JNIEnv *env, jclass clazz, jlong widget_ptr, jboolean set, jint left, jint top, jint right, jint bottom, jfloat radius)
{
	WrapperWidget *wrapper = tl_clip_wrapper(widget_ptr);
	if (!wrapper)
		return;
	wrapper->tl_outline_clip = set;
	gsk_rounded_rect_init_from_rect(&wrapper->tl_outline, &GRAPHENE_RECT_INIT(left, top, MAX(right - left, 0), MAX(bottom - top, 0)), radius);
	gtk_widget_queue_draw(GTK_WIDGET(wrapper));
}
EOF
fi
grep -c "tl_view_clip" "$C" "$V"
