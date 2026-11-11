/*
 * teslalinux debugging aid: dump the GTK widget tree a View lives in (android.atl.ViewDump), with
 * the real allocations, visibility, mapping and child order GTK renders with, and the Java class of
 * each WrapperWidget. Only reachable through ATL_DEBUG_DUMPVIEWS.
 */
#include <stdio.h>
#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"
#include "../widgets/WrapperWidget.h"

static void dump_widget(JNIEnv *env, GtkWidget *w, int depth, FILE *out)
{
	graphene_rect_t bounds;
	gboolean have_bounds = gtk_widget_compute_bounds(w, gtk_widget_get_parent(w) ? gtk_widget_get_parent(w) : w, &bounds);
	const char *java = "";
	char buf[256] = "";
	if (WRAPPER_IS_WIDGET(w) && WRAPPER_WIDGET(w)->jobj && !(*env)->IsSameObject(env, WRAPPER_WIDGET(w)->jobj, NULL)) {
		jclass cls = (*env)->GetObjectClass(env, WRAPPER_WIDGET(w)->jobj);
		jmethodID getName = (*env)->GetMethodID(env, (*env)->FindClass(env, "java/lang/Class"), "getName", "()Ljava/lang/String;");
		jstring name = (*env)->CallObjectMethod(env, cls, getName);
		const char *s = (*env)->GetStringUTFChars(env, name, NULL);
		snprintf(buf, sizeof(buf), " java=%s", s);
		(*env)->ReleaseStringUTFChars(env, name, s);
		java = buf;
	}
	fprintf(out, "%*s%s %p%s vis=%d childvis=%d mapped=%d opacity=%.2f alloc=%dx%d", depth * 2, "", G_OBJECT_TYPE_NAME(w), (void *)w, java,
	        gtk_widget_get_visible(w), gtk_widget_get_child_visible(w), gtk_widget_get_mapped(w), gtk_widget_get_opacity(w),
	        gtk_widget_get_width(w), gtk_widget_get_height(w));
	if (have_bounds)
		fprintf(out, " at %.0f,%.0f %.0fx%.0f", bounds.origin.x, bounds.origin.y, bounds.size.width, bounds.size.height);
	fputc(10, out);
	for (GtkWidget *c = gtk_widget_get_first_child(w); c; c = gtk_widget_get_next_sibling(c))
		dump_widget(env, c, depth + 1, out);
}

JNIEXPORT void JNICALL Java_android_atl_ViewDump_nativeDumpGtk(JNIEnv *env, jclass cls, jlong widget_ptr)
{
	GtkWidget *w = GTK_WIDGET(_PTR(widget_ptr));
	if (!w)
		return;
	GtkRoot *root = gtk_widget_get_root(w);
	GtkWidget *top = root ? GTK_WIDGET(root) : w;
	/* every toplevel first: a kiosk compositor shows the newest one on top of the app window */
	GListModel *toplevels = gtk_window_get_toplevels();
	guint n_top = g_list_model_get_n_items(toplevels);
	fprintf(stderr, "=== GTK toplevels: %u ===%c", n_top, 10);
	for (guint i = 0; i < n_top; i++) {
		GtkWindow *w = g_list_model_get_item(toplevels, i);
		const char *title = gtk_window_get_title(w);
		fprintf(stderr, "  %s %p title=%s visible=%d mapped=%d realized=%d transient_for=%p size=%dx%d%c", G_OBJECT_TYPE_NAME(w), (void *)w,
		        title ? title : "(none)", gtk_widget_get_visible(GTK_WIDGET(w)), gtk_widget_get_mapped(GTK_WIDGET(w)),
		        gtk_widget_get_realized(GTK_WIDGET(w)), (void *)gtk_window_get_transient_for(w),
		        gtk_widget_get_width(GTK_WIDGET(w)), gtk_widget_get_height(GTK_WIDGET(w)), 10);
		g_object_unref(w);
	}
	/* every toplevel's tree (dialogs and popups are their own windows) */
	for (guint i = 0; i < n_top; i++) {
		GtkWindow *tw = g_list_model_get_item(toplevels, i);
		fprintf(stderr, "=== GTK widget tree from %s %p ===%c", G_OBJECT_TYPE_NAME(tw), (void *)tw, 10);
		dump_widget(env, GTK_WIDGET(tw), 0, stderr);
		g_object_unref(tw);
	}
	if (!GTK_IS_WINDOW(top)) {
		fprintf(stderr, "=== GTK widget tree from %s (unrooted) ===%c", G_OBJECT_TYPE_NAME(top), 10);
		dump_widget(env, top, 0, stderr);
	}
	fprintf(stderr, "=== end GTK tree ===%c", 10);
}
