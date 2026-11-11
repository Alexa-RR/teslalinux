#!/bin/sh
# teslalinux: a SurfaceView's content lives in a GTK texture that parents capture when they record
# their children (ViewGroup.drawChild -> gtk_widget_snapshot_child). Android's compositor shows a
# new SurfaceView buffer without any view invalidation; here the equivalent is to invalidate the
# Java SurfaceView when a new frame texture arrives, so the parent chain (AOSP invalidateChild ->
# onDescendantInvalidated; Compose's AndroidViewHolder re-records its layer on that) picks the new
# texture up. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
W=src/api-impl-jni/widgets/android_view_SurfaceView.c
if ! grep -q "tl_invalidate_java_view" "$W"; then
python3 - "$W" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	gtk_widget_queue_draw(GTK_WIDGET(surface_view_widget));
}
'''
new='''	gtk_widget_queue_draw(GTK_WIDGET(surface_view_widget));
	tl_invalidate_java_view(GTK_WIDGET(surface_view_widget));
}
'''
assert s.count(old) == 1, "set_texture tail not found exactly once"
s=s.replace(old,new,1)
helper='''
/* teslalinux: SurfaceViewWidget sits inside a GtkGraphicsOffload inside the WrapperWidget that owns
 * the Java view; call View.invalidate() on it so recorded parents (Compose layers) re-record. */
static void tl_invalidate_java_view(GtkWidget *surface_view_widget)
{
	static jmethodID invalidate_method = NULL;
	GtkWidget *offload = gtk_widget_get_parent(surface_view_widget);
	GtkWidget *wrapper = offload ? gtk_widget_get_parent(offload) : NULL;
	static int calls = 0;
	int trace = getenv("ATL_DEBUG_SURFACE") && (calls++ % 100) == 0;
	if (!wrapper || !WRAPPER_IS_WIDGET(wrapper)) {
		if (trace)
			fprintf(stderr, "[surface] invalidate_java_view #%d: no wrapper widget (offload %p, parent %p %s)%c", calls, (void *)offload, (void *)wrapper, wrapper ? G_OBJECT_TYPE_NAME(wrapper) : "-", 10);
		return;
	}
	jobject jobj = WRAPPER_WIDGET(wrapper)->jobj;
	if (!jobj) {
		if (trace)
			fprintf(stderr, "[surface] invalidate_java_view #%d: wrapper has no Java object%c", calls, 10);
		return;
	}
	if (trace)
		fprintf(stderr, "[surface] invalidate_java_view #%d: calling View.invalidate()%c", calls, 10);
	JNIEnv *env = get_jni_env();
	if ((*env)->IsSameObject(env, jobj, NULL))
		return;
	if (!invalidate_method) {
		jclass view_class = (*env)->FindClass(env, "android/view/View");
		invalidate_method = (*env)->GetMethodID(env, view_class, "invalidate", "()V");
	}
	(*env)->CallVoidMethod(env, jobj, invalidate_method);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

void surface_view_widget_set_texture('''
key=chr(10)+"void surface_view_widget_set_texture("
assert s.count(key) == 1, s.count(key)
s=s.replace(key, helper, 1)
open(p,'w').write(s)
PY
fi
grep -c "tl_invalidate_java_view" "$W"
grep -n "WRAPPER_IS_WIDGET\|define WRAPPER_WIDGET\|G_DECLARE_FINAL_TYPE(WrapperWidget" src/api-impl-jni/widgets/WrapperWidget.h | head -3
