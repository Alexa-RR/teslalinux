#!/bin/sh
# teslalinux: ATL_DEBUG_NODE=<java class substring> captures the GSK render node a matching view's
# Java draw() produces (WrapperWidget snapshot): bounds and node type go to stderr, the first three
# nodes per widget are serialised to /tmp/atl-node-<ptr>-<n>.node (readable with gtk4-node-editor
# or as text). Off by default: one cached flag per wrapper. Run by build-atl.sh from ~/atl-src.
set -eu
W=src/api-impl-jni/widgets/WrapperWidget.c
if ! grep -q "tl_node_debug" "$W"; then
python3 - "$W" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	if (wrapper->draw_method) {
		JNIEnv *env = get_jni_env();
		_SET_LONG_FIELD(wrapper->canvas, "snapshot", _INTPTR(snapshot));
		(*env)->CallVoidMethod(env, wrapper->jobj, wrapper->draw_method, wrapper->canvas);
		if ((*env)->ExceptionCheck(env))
			(*env)->ExceptionDescribe(env);
	} else {'''
new='''	if (wrapper->draw_method) {
		JNIEnv *env = get_jni_env();
		if (tl_node_debug(env, wrapper)) {
			GtkSnapshot *sub = gtk_snapshot_new();
			_SET_LONG_FIELD(wrapper->canvas, "snapshot", _INTPTR(sub));
			(*env)->CallVoidMethod(env, wrapper->jobj, wrapper->draw_method, wrapper->canvas);
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionDescribe(env);
			GskRenderNode *node = gtk_snapshot_free_to_node(sub);
			tl_node_debug_report(wrapper, node);
			if (node) {
				gtk_snapshot_append_node(snapshot, node);
				gsk_render_node_unref(node);
			}
		} else {
			_SET_LONG_FIELD(wrapper->canvas, "snapshot", _INTPTR(snapshot));
			(*env)->CallVoidMethod(env, wrapper->jobj, wrapper->draw_method, wrapper->canvas);
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionDescribe(env);
		}
	} else {'''
assert s.count(old) == 1, s.count(old)
s=s.replace(old,new,1)
# plain layouts (no Java draw): capture the children's nodes the same way
old_children='''		GtkWidget *widget = &wrapper->parent_instance;
		GtkWidget *child = gtk_widget_get_first_child(widget);
		while (child) {
			gtk_widget_snapshot_child(widget, child, snapshot);
			child = gtk_widget_get_next_sibling(child);
		}
'''
new_children='''		GtkWidget *widget = &wrapper->parent_instance;
		GtkWidget *child = gtk_widget_get_first_child(widget);
		JNIEnv *env = get_jni_env();
		GtkSnapshot *target = snapshot;
		GtkSnapshot *sub = NULL;
		if (tl_node_debug(env, wrapper))
			target = sub = gtk_snapshot_new();
		while (child) {
			gtk_widget_snapshot_child(widget, child, target);
			child = gtk_widget_get_next_sibling(child);
		}
		if (sub) {
			GskRenderNode *node = gtk_snapshot_free_to_node(sub);
			tl_node_debug_report(wrapper, node);
			if (node) {
				gtk_snapshot_append_node(snapshot, node);
				gsk_render_node_unref(node);
			}
		}
'''
assert s.count(old_children) == 1, s.count(old_children)
s=s.replace(old_children,new_children,1)
helper='''
/* teslalinux: ATL_DEBUG_NODE support, see native-node-debug.sh */
static gboolean tl_node_debug(JNIEnv *env, WrapperWidget *wrapper)
{
	if ((*env)->ExceptionCheck(env))
		return FALSE;
	static const char *pattern = NULL;
	static int checked = 0;
	if (!checked) {
		pattern = getenv("ATL_DEBUG_NODE");
		checked = 1;
	}
	if (!pattern)
		return FALSE;
	gpointer cached = g_object_get_data(G_OBJECT(wrapper), "tl-node-debug");
	if (cached) {
		/* while /tmp/atl-capturenode exists every matching wrapper is captured again (tap.sh capture) */
		if (GPOINTER_TO_INT(cached) == 2 && g_file_test("/tmp/atl-capturenode", G_FILE_TEST_EXISTS))
			g_object_set_data(G_OBJECT(wrapper), "tl-node-debug-count", GINT_TO_POINTER(0));
		return GPOINTER_TO_INT(cached) == 2;
	}
	int match = 1;
	if (wrapper->jobj && !(*env)->IsSameObject(env, wrapper->jobj, NULL)) {
		jclass cls = (*env)->GetObjectClass(env, wrapper->jobj);
		jmethodID get_name = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, cls), "getName", "()Ljava/lang/String;");
		jstring name = (*env)->CallObjectMethod(env, cls, get_name);
		const char *cname = (*env)->GetStringUTFChars(env, name, NULL);
		if (strstr(cname, pattern))
			match = 2;
		(*env)->ReleaseStringUTFChars(env, name, cname);
	}
	g_object_set_data(G_OBJECT(wrapper), "tl-node-debug", GINT_TO_POINTER(match));
	return match == 2;
}

static void tl_node_debug_report(WrapperWidget *wrapper, GskRenderNode *node)
{
	int n = GPOINTER_TO_INT(g_object_get_data(G_OBJECT(wrapper), "tl-node-debug-count"));
	g_object_set_data(G_OBJECT(wrapper), "tl-node-debug-count", GINT_TO_POINTER(n + 1));
	if (!node) {
		fprintf(stderr, "[node] wrapper %p draw #%d produced no node%c", (void *)wrapper, n, 10);
		return;
	}
	graphene_rect_t b;
	gsk_render_node_get_bounds(node, &b);
	fprintf(stderr, "[node] wrapper %p draw #%d type=%d bounds=%.0f,%.0f %.0fx%.0f%c", (void *)wrapper, n, gsk_render_node_get_node_type(node), b.origin.x, b.origin.y, b.size.width, b.size.height, 10);
	if (n < 3) {
		char *path = g_strdup_printf("/tmp/atl-node-%p-%d.node", (void *)wrapper, n);
		GError *err = NULL;
		gsk_render_node_write_to_file(node, path, &err);
		if (err) {
			fprintf(stderr, "[node] could not write %s: %s%c", path, err->message, 10);
			g_error_free(err);
		}
		g_free(path);
	}
}
'''
key=chr(10)+"static void wrapper_widget_snapshot(GtkWidget *widget, GdkSnapshot *snapshot)"
assert s.count(key) == 1, s.count(key)
s=s.replace(key, helper+key, 1)
if "#include <string.h>" not in s:
    s="#include <string.h>\n#include <stdio.h>\n#include <stdlib.h>\n"+s
open(p,'w').write(s)
PY
fi
grep -c "tl_node_debug" "$W"
