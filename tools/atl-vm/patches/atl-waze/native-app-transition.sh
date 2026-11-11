#!/bin/sh
# teslalinux (app): activity transitions (tl_activity_transition). AOSP plays the animations given by
# Activity.overridePendingTransition / ActivityOptions.makeCustomAnimation when the next activity
# switch happens. ATL shows each activity's decor as a page of the main window's GtkStack
# (tl_window_attach), so the switch can animate natively: Activity.nativeSetPageTransition sets the
# stack's transition type and duration right before the page change (Activity.onStart) and resets it
# to none afterwards. Java (Activity.tlPageTransitionFor) maps the anim resources to a
# GtkStackTransitionType. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
A=src/api-impl-jni/app/android_app_Activity.c
if ! grep -q "tl_activity_transition" "$A"; then
cat >> "$A" <<'EOF'

/* tl_activity_transition: the transition of the next page change of the activity stack */
JNIEXPORT void JNICALL Java_android_app_Activity_nativeSetPageTransition(JNIEnv *env, jclass klass, jlong native_window, jint type, jint duration_ms)
{
	GtkWidget *root = native_window ? GTK_WIDGET(_PTR(native_window)) : NULL;
	if (!root || !GTK_IS_WINDOW(root))
		return;
	GtkWidget *child = gtk_window_get_child(GTK_WINDOW(root));
	if (!child || !GTK_IS_OVERLAY(child))
		return;
	GtkWidget *stack = gtk_overlay_get_child(GTK_OVERLAY(child));
	if (!stack || !GTK_IS_STACK(stack))
		return;
	gtk_stack_set_transition_type(GTK_STACK(stack), (GtkStackTransitionType)type);
	gtk_stack_set_transition_duration(GTK_STACK(stack), duration_ms > 0 ? (guint)duration_ms : 0);
}
EOF
fi
grep -c "tl_activity_transition" "$A"
