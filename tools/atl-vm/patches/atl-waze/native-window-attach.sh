#!/bin/sh
# teslalinux: AOSP window attachment (tl_window_attach). In AOSP a view is attached to its window
# from the moment it is added to a window's hierarchy until it is removed, independent of its
# visibility, and a stopped activity keeps its window (hidden). ATL called onAttachedToWindow on GTK
# "map" and onDetachedFromWindow on "unmap" (every GONE view detached), and replaced the window's
# child with the new activity's decor, which unparented the stopped activity's views: its
# ComposeViews disposed their compositions (DisposeOnDetachedFromWindow) and Waze's map was gone
# after returning from a second activity.
# - WrapperWidget attaches/detaches in the GtkWidget root/unroot vfuncs (in a window hierarchy).
# - View.isAttachedToWindow is "has a root" (still true inside onDetachedFromWindow, as in AOSP).
# - Activity decors live in a GtkStack under the main overlay; the current activity's decor is the
#   visible page, stopped ones stay parented (hidden); Activity.onDestroy removes its page.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
W=src/api-impl-jni/widgets/WrapperWidget.c
V=src/api-impl-jni/views/android_view_View.c
M=src/main-executable/main.c
N=src/api-impl-jni/android_view_Window.c

if ! grep -q "tl_window_attach" "$W"; then
python3 - "$W" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	g_signal_connect(wrapper, "map", G_CALLBACK(map_cb), handle_cache.view.onAttachedToWindow);
	g_signal_connect(wrapper, "unmap", G_CALLBACK(unmap_cb), handle_cache.view.onDetachedFromWindow);
'''
new='''	/* tl_window_attach: attach/detach follow the root/unroot vfuncs (in a window), not map/unmap */
	if (gtk_widget_get_root(GTK_WIDGET(wrapper)))
		map_cb(wrapper, handle_cache.view.onAttachedToWindow);
'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
old2='''	widget_class->snapshot = wrapper_widget_snapshot;
'''
new2='''	widget_class->snapshot = wrapper_widget_snapshot;
	widget_class->root = wrapper_widget_root;     /* tl_window_attach */
	widget_class->unroot = wrapper_widget_unroot;
'''
assert s.count(old2)==1, s.count(old2)
s=s.replace(old2,new2,1)
old3='''static void wrapper_widget_class_init(WrapperWidgetClass *class)
{'''
new3='''/* tl_window_attach: AOSP dispatchAttachedToWindow/dispatchDetachedFromWindow happen when the view
 * enters or leaves a window's hierarchy; GTK's root/unroot are exactly that */
static void map_cb(WrapperWidget *wrapper, jmethodID method);
static void unmap_cb(WrapperWidget *wrapper, jmethodID method);
static gboolean wrapper_has_jobject(WrapperWidget *wrapper)
{
	if (!wrapper->jobj)
		return FALSE;
	JNIEnv *env = get_jni_env();
	return !(*env)->IsSameObject(env, wrapper->jobj, NULL);
}
static void wrapper_widget_root(GtkWidget *widget)
{
	GTK_WIDGET_CLASS(wrapper_widget_parent_class)->root(widget);
	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	if (wrapper_has_jobject(wrapper))
		map_cb(wrapper, handle_cache.view.onAttachedToWindow);
}
static void wrapper_widget_unroot(GtkWidget *widget)
{
	WrapperWidget *wrapper = WRAPPER_WIDGET(widget);
	if (wrapper_has_jobject(wrapper))
		unmap_cb(wrapper, handle_cache.view.onDetachedFromWindow);
	GTK_WIDGET_CLASS(wrapper_widget_parent_class)->unroot(widget);
}

static void wrapper_widget_class_init(WrapperWidgetClass *class)
{'''
assert s.count(old3)==1, s.count(old3)
s=s.replace(old3,new3,1)
open(p,'w').write(s)
PY
fi
grep -q "tl_window_attach" "$W"

if ! grep -q "tl_window_attach" "$V"; then
python3 - "$V" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	return currently_unmapping == widget || gtk_widget_get_mapped(widget);
'''
new='''	/* tl_window_attach: attached while in a window's hierarchy (and still during onDetachedFromWindow) */
	return currently_unmapping == widget || gtk_widget_get_root(widget) != NULL;
'''
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new,1))
PY
fi
grep -q "tl_window_attach" "$V"

if ! grep -q "tl_window_attach" "$M"; then
python3 - "$M" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	tl_main_overlay = gtk_overlay_new();
	gtk_window_set_child(GTK_WINDOW(window), tl_main_overlay);
'''
new='''	tl_main_overlay = gtk_overlay_new();
	gtk_window_set_child(GTK_WINDOW(window), tl_main_overlay);
	/* tl_window_attach: activity decors are pages of a stack; stopped activities keep their (hidden) window */
	tl_activity_stack = gtk_stack_new();
	gtk_stack_set_hhomogeneous(GTK_STACK(tl_activity_stack), FALSE);
	gtk_stack_set_vhomogeneous(GTK_STACK(tl_activity_stack), FALSE);
	gtk_overlay_set_child(GTK_OVERLAY(tl_main_overlay), tl_activity_stack);
'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
old2='GtkWidget *tl_main_overlay;'
new2='GtkWidget *tl_main_overlay;\nGtkWidget *tl_activity_stack; /* tl_window_attach */'
assert s.count(old2)==1, s.count(old2)
s=s.replace(old2,new2,1)
open(p,'w').write(s)
PY
fi
grep -q "tl_window_attach" "$M"

if ! grep -q "tl_window_attach" "$N"; then
python3 - "$N" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''		if (GTK_IS_OVERLAY(child)) {
			if (gtk_overlay_get_child(GTK_OVERLAY(child)) != gtk_widget)
				gtk_overlay_set_child(GTK_OVERLAY(child), gtk_widget);
		} else if (gtk_widget != child) {
'''
new='''		if (GTK_IS_OVERLAY(child)) {
			GtkWidget *stack = gtk_overlay_get_child(GTK_OVERLAY(child));
			if (GTK_IS_STACK(stack)) { /* tl_window_attach: the decor is a page; the current activity's page is shown */
				if (gtk_widget_get_parent(gtk_widget) != stack)
					gtk_stack_add_child(GTK_STACK(stack), gtk_widget);
				gtk_stack_set_visible_child(GTK_STACK(stack), gtk_widget);
			} else if (stack != gtk_widget) {
				gtk_overlay_set_child(GTK_OVERLAY(child), gtk_widget);
			}
		} else if (gtk_widget != child) {
'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
s+='''
/* tl_window_attach: a destroyed activity's decor leaves the window (AOSP: its window is removed) */
JNIEXPORT void JNICALL Java_android_view_Window_tlRemoveRoot(JNIEnv *env, jobject this, jlong window, jlong widget)
{
	GtkWidget *gtk_widget = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget)));
	GtkWidget *parent = gtk_widget ? gtk_widget_get_parent(gtk_widget) : NULL;
	if (parent && GTK_IS_STACK(parent))
		gtk_stack_remove(GTK_STACK(parent), gtk_widget);
}
'''
open(p,'w').write(s)
PY
fi
grep -c "tl_window_attach" "$N"
