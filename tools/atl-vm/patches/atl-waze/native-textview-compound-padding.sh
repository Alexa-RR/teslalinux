#!/bin/sh
# teslalinux: TextView compound padding (tl_textview_compound). The AOSP CompoundButton and Switch
# ports draw their button/track drawables inside getCompoundPaddingLeft()/Right(); the GTK label
# that renders the text gets the same space as a margin so text and drawable do not overlap.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
T=src/api-impl-jni/widgets/android_widget_TextView.c
if ! grep -q "tl_textview_compound" "$T"; then
cat >> "$T" <<'EOF'

/* ---- teslalinux: compound padding (tl_textview_compound) ---- */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setCompoundPadding(JNIEnv *env, jobject this, jlong widget_ptr, jint left, jint right)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *child = GTK_IS_BOX(widget) ? gtk_widget_get_first_child(widget) : widget;
	if (!child)
		return;
	gboolean rtl = gtk_widget_get_direction(child) == GTK_TEXT_DIR_RTL;
	gtk_widget_set_margin_start(child, rtl ? right : left);
	gtk_widget_set_margin_end(child, rtl ? left : right);
}
EOF
fi
grep -c "tl_textview_compound" "$T"
