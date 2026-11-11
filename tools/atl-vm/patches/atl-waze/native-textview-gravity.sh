#!/bin/sh
# teslalinux: TextView.setGravity reaches the GTK label (tl_textview_gravity). ATL fixed the label
# at xalign/yalign 0 and never read android:gravity, so centred button labels drew left-aligned.
# The horizontal half follows AOSP's relative/absolute semantics: START/END map to GTK's xalign
# (which GTK mirrors for RTL text), LEFT/RIGHT are absolute and undo that mirroring.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
T=src/api-impl-jni/widgets/android_widget_TextView.c
if ! grep -q "tl_textview_gravity" "$T"; then
cat >> "$T" <<'EOF'

/* ---- teslalinux: text gravity (tl_textview_gravity) ---- */
JNIEXPORT void JNICALL Java_android_widget_TextView_native_1setGravity(JNIEnv *env, jobject this, jlong widget_ptr, jint gravity)
{
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *child = GTK_IS_BOX(widget) ? gtk_widget_get_first_child(widget) : widget;
	if (!child)
		return;
	int h = gravity & 0x7;                      /* Gravity.HORIZONTAL_GRAVITY_MASK: CENTER_HORIZONTAL 1, LEFT 3, RIGHT 5 */
	int relative = (gravity & 0x800000) != 0;   /* Gravity.RELATIVE_LAYOUT_DIRECTION: START/END */
	int v = gravity & 0x70;                     /* Gravity.VERTICAL_GRAVITY_MASK: CENTER_VERTICAL 0x10, TOP 0x30, BOTTOM 0x50 */
	gboolean rtl = gtk_widget_get_direction(child) == GTK_TEXT_DIR_RTL;
	float xalign;
	if (h == 1)
		xalign = 0.5f;
	else if (relative)
		xalign = (h == 5) ? 1.f : 0.f;          /* GTK mirrors xalign for RTL, like START/END */
	else
		xalign = ((h == 5) != rtl) ? 1.f : 0.f; /* absolute LEFT/RIGHT */
	float yalign = v == 0x10 ? 0.5f : (v == 0x50 ? 1.f : 0.f);
	GtkJustification justify = h == 1 ? GTK_JUSTIFY_CENTER : ((xalign == 1.f) != rtl ? GTK_JUSTIFY_RIGHT : GTK_JUSTIFY_LEFT);
	if (GTK_IS_LABEL(child)) {
		gtk_label_set_xalign(GTK_LABEL(child), xalign);
		gtk_label_set_yalign(GTK_LABEL(child), yalign);
		gtk_label_set_justify(GTK_LABEL(child), justify);
	} else if (GTK_IS_TEXT_VIEW(child)) {
		gtk_text_view_set_justification(GTK_TEXT_VIEW(child), justify);
	} else if (g_object_class_find_property(G_OBJECT_GET_CLASS(child), "xalign")) {
		g_object_set(child, "xalign", xalign, NULL); /* GtkText / GtkEntry */
	}
}
EOF
fi
grep -c "tl_textview_gravity" "$T"
