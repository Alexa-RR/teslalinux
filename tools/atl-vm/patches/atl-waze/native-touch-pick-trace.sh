#!/bin/sh
# teslalinux: ATL_DEBUG_TOUCH detail for the synthetic pick (tl_pick_trace): the picked widget's
# Java class and allocation, and for each of its children why GTK did not pick it (targetable,
# sensitive, mapped, the point in the child's coordinates and whether the child contains it).
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
V=src/api-impl-jni/views/android_view_ViewGroup.c
if ! grep -q "tl_pick_trace" "$V"; then
python3 - "$V" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
anchor='JNIEXPORT jboolean JNICALL Java_android_view_ViewGroup_native_1dispatchTouchEvent('
assert s.count(anchor)==1
helper='''/* tl_pick_trace: why the pick stopped where it did (ATL_DEBUG_TOUCH) */
static const char *tl_pick_java_class(GtkWidget *w)
{
	static char buf[128];
	GtkWidget *wrapper = WRAPPER_IS_WIDGET(w) ? w : gtk_widget_get_parent(w);
	if (!wrapper || !WRAPPER_IS_WIDGET(wrapper) || !WRAPPER_WIDGET(wrapper)->jobj)
		return "-";
	JNIEnv *env = get_jni_env();
	jclass cls = (*env)->GetObjectClass(env, WRAPPER_WIDGET(wrapper)->jobj);
	jmethodID get_name = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, cls), "getName", "()Ljava/lang/String;");
	jstring name = (*env)->CallObjectMethod(env, cls, get_name);
	const char *str = (*env)->GetStringUTFChars(env, name, NULL);
	g_strlcpy(buf, str, sizeof(buf));
	(*env)->ReleaseStringUTFChars(env, name, str);
	return buf;
}
static void tl_pick_trace(GtkWidget *picked, double x, double y, GtkWidget *from)
{
	graphene_point_t p;
	if (!gtk_widget_compute_point(from, picked, &GRAPHENE_POINT_INIT(x, y), &p))
		p = GRAPHENE_POINT_INIT(-1, -1);
	fprintf(stderr, "[touch]   picked %s java=%s alloc=%dx%d local=%.0f,%.0f contains=%d children:%c", G_OBJECT_TYPE_NAME(picked), tl_pick_java_class(picked),
	        gtk_widget_get_width(picked), gtk_widget_get_height(picked), p.x, p.y, gtk_widget_contains(picked, p.x, p.y), 10);
	for (GtkWidget *c = gtk_widget_get_first_child(picked); c; c = gtk_widget_get_next_sibling(c)) {
		graphene_point_t cp;
		gboolean ok = gtk_widget_compute_point(picked, c, &p, &cp);
		fprintf(stderr, "[touch]     child %s java=%s alloc=%dx%d target=%d sensitive=%d mapped=%d visible=%d local=%.0f,%.0f contains=%d%c", G_OBJECT_TYPE_NAME(c), tl_pick_java_class(c),
		        gtk_widget_get_width(c), gtk_widget_get_height(c), gtk_widget_get_can_target(c), gtk_widget_is_sensitive(c), gtk_widget_get_mapped(c), gtk_widget_get_visible(c),
		        ok ? cp.x : -1.0, ok ? cp.y : -1.0, ok ? gtk_widget_contains(c, cp.x, cp.y) : -1, 10);
	}
}

'''
s=s.replace(anchor, helper+anchor, 1)
old='''	if (!picked_child)
		return false;

	return atl_propagate_synthetic_motionevent(picked_child, motion_event, widget);'''
new='''	if (!picked_child)
		return false;
	if (getenv("ATL_DEBUG_TOUCH"))
		tl_pick_trace(picked_child, x, y, widget);

	return atl_propagate_synthetic_motionevent(picked_child, motion_event, widget);'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_pick_trace" "$V"
