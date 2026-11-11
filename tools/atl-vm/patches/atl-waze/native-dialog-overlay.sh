#!/bin/sh
# teslalinux: dialogs inside the main window (tl_dialog_overlay). AOSP shows a Dialog as a window
# layered over the activity with a dim behind it (FLAG_DIM_BEHIND, dimAmount 0.6 in the Material
# dialog themes); the activity stays visible. ATL created a second GtkWindow per dialog, which a
# kiosk compositor (cage) shows fullscreen over a blank page. The dialog is now a layer in the main
# window's GtkOverlay: a dim box filling the window with the dialog's decor centred, a touch on the
# dim cancels (Dialog.setCanceledOnTouchOutside), and keys go to the topmost dialog first.
# Replaces native-dialog-window.sh. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
D=src/api-impl-jni/app/android_app_Dialog.c
W=src/api-impl-jni/android_view_Window.c
A=src/api-impl-jni/app/android_app_AlertDialog.c
M=src/main-executable/main.c

if ! grep -q "tl_dialog_overlay" "$D"; then
cat > "$D" <<'EOF'
#include <gtk/gtk.h>
#include <jni.h>

#include "../defines.h"
#include "../util.h"

#include "../generated_headers/android_app_Dialog.h"

/* tl_dialog_overlay: AOSP dialogs are windows layered over the activity with a dim behind them
 * (FLAG_DIM_BEHIND, dimAmount 0.6 in the Material dialog themes). Under a kiosk compositor every
 * toplevel is fullscreen, so the dialog is a layer in the main window's GtkOverlay: a dim box that
 * fills the window with the dialog's decor centred in it. */
extern GtkWidget *tl_main_overlay;
static GList *tl_dialog_stack = NULL; /* layers in show order; the last one is on top */

static void on_dim_pressed(GtkGestureClick *gesture, int n_press, double x, double y, GtkWidget *layer)
{
	GtkWidget *content = gtk_widget_get_first_child(layer);
	graphene_rect_t bounds;
	if (content && gtk_widget_compute_bounds(content, layer, &bounds) && graphene_rect_contains_point(&bounds, &GRAPHENE_POINT_INIT(x, y)))
		return; /* inside the dialog: its own views handle the touch */
	gtk_gesture_set_state(GTK_GESTURE(gesture), GTK_EVENT_SEQUENCE_CLAIMED);
	JNIEnv *env = get_jni_env();
	jobject jobj = g_object_get_data(G_OBJECT(layer), "dialog");
	(*env)->CallVoidMethod(env, jobj, _METHOD(_CLASS(jobj), "tlTouchOutside", "()V"));
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

/* the dialog's decor (or ATL's AlertDialog content) becomes the centred child of the layer */
void tl_dialog_set_content(GtkWidget *layer, GtkWidget *content)
{
	GtkWidget *old;
	while ((old = gtk_widget_get_first_child(layer)))
		gtk_box_remove(GTK_BOX(layer), old);
	gtk_widget_set_halign(content, GTK_ALIGN_CENTER);
	gtk_widget_set_valign(content, GTK_ALIGN_CENTER);
	gtk_widget_set_hexpand(content, TRUE);
	gtk_widget_set_vexpand(content, TRUE);
	gtk_box_append(GTK_BOX(layer), content);
}

/* AOSP: the focused window gets the key, and while a dialog shows, that is the dialog */
jboolean tl_dialog_dispatch_key(JNIEnv *env, jobject key_event)
{
	if (!tl_dialog_stack)
		return JNI_FALSE;
	GtkWidget *layer = g_list_last(tl_dialog_stack)->data;
	jobject jobj = g_object_get_data(G_OBJECT(layer), "dialog");
	(*env)->CallBooleanMethod(env, jobj, _METHOD(_CLASS(jobj), "dispatchKeyEvent", "(Landroid/view/KeyEvent;)Z"), key_event);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	return JNI_TRUE;
}

JNIEXPORT jlong JNICALL Java_android_app_Dialog_nativeInit(JNIEnv *env, jobject this)
{
	GtkWidget *layer = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
	gtk_widget_add_css_class(layer, "tl-dialog-dim");
	gtk_widget_set_hexpand(layer, TRUE);
	gtk_widget_set_vexpand(layer, TRUE);
	gtk_widget_set_halign(layer, GTK_ALIGN_FILL);
	gtk_widget_set_valign(layer, GTK_ALIGN_FILL);
	gtk_widget_set_focusable(layer, TRUE);
	GtkGesture *click = gtk_gesture_click_new();
	gtk_gesture_single_set_button(GTK_GESTURE_SINGLE(click), 0);
	g_signal_connect(click, "pressed", G_CALLBACK(on_dim_pressed), layer);
	gtk_widget_add_controller(layer, GTK_EVENT_CONTROLLER(click));
	g_object_set_data(G_OBJECT(layer), "dialog", _REF(this));
	return _INTPTR(g_object_ref_sink(layer));
}

/* AOSP: the title is a view of the dialog's decor; ATL's AlertDialog content has a title label,
 * a custom content view (every Waze dialog) draws its own title */
JNIEXPORT void JNICALL Java_android_app_Dialog_nativeSetTitle(JNIEnv *env, jobject this, jlong ptr, jstring title)
{
	GtkWidget *layer = GTK_WIDGET(_PTR(ptr));
	const char *nativeTitle = (*env)->GetStringUTFChars(env, title, NULL);
	GtkWidget *label = g_object_get_data(G_OBJECT(layer), "title-label");
	if (label) {
		gtk_label_set_text(GTK_LABEL(label), nativeTitle);
		gtk_widget_set_visible(label, nativeTitle[0] != 0);
	}
	g_object_set_data_full(G_OBJECT(layer), "title", g_strdup(nativeTitle), g_free);
	(*env)->ReleaseStringUTFChars(env, title, nativeTitle);
}

JNIEXPORT void JNICALL Java_android_app_Dialog_nativeSetContentView(JNIEnv *env, jobject this, jlong ptr, jlong widget_ptr)
{
	GtkWidget *layer = GTK_WIDGET(_PTR(ptr));
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	tl_dialog_set_content(layer, gtk_widget_get_parent(widget));
}

JNIEXPORT void JNICALL Java_android_app_Dialog_nativeShow(JNIEnv *env, jobject this, jlong ptr)
{
	GtkWidget *layer = GTK_WIDGET(_PTR(ptr));
	if (!tl_main_overlay) {
		fprintf(stderr, "Dialog.nativeShow: no main overlay yet\n");
		return;
	}
	if (!gtk_widget_get_parent(layer)) {
		gtk_overlay_add_overlay(GTK_OVERLAY(tl_main_overlay), layer);
		gtk_overlay_set_measure_overlay(GTK_OVERLAY(tl_main_overlay), layer, FALSE);
		gtk_overlay_set_clip_overlay(GTK_OVERLAY(tl_main_overlay), layer, TRUE);
	}
	gtk_widget_set_visible(layer, TRUE);
	tl_dialog_stack = g_list_append(g_list_remove(tl_dialog_stack, layer), layer);
	gtk_widget_grab_focus(layer);
}

JNIEXPORT void JNICALL Java_android_app_Dialog_nativeClose(JNIEnv *env, jobject this, jlong ptr)
{
	GtkWidget *layer = GTK_WIDGET(_PTR(ptr));
	gtk_widget_set_visible(layer, FALSE);
	if (tl_main_overlay && gtk_widget_get_parent(layer) == tl_main_overlay)
		gtk_overlay_remove_overlay(GTK_OVERLAY(tl_main_overlay), layer);
	tl_dialog_stack = g_list_remove(tl_dialog_stack, layer);
}

JNIEXPORT jboolean JNICALL Java_android_app_Dialog_nativeIsShowing(JNIEnv *env, jobject this, jlong ptr)
{
	GtkWidget *layer = GTK_WIDGET(_PTR(ptr));
	return gtk_widget_get_parent(layer) != NULL && gtk_widget_get_visible(layer);
}
EOF
fi
grep -q "tl_dialog_overlay" "$D"

if ! grep -q "tl_dialog_overlay" "$W"; then
python3 - "$W" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''JNIEXPORT void JNICALL Java_android_view_Window_set_1widget_1as_1root(JNIEnv *env, jobject this, jlong window, jlong widget)
{
	GtkWindow *gtk_window = GTK_WINDOW(_PTR(window));
	GtkWidget *gtk_widget = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget)));
	if (gtk_widget != gtk_window_get_child(gtk_window)) {
		gtk_window_set_child(gtk_window, gtk_widget);
	}
}
'''
new='''/* tl_dialog_overlay: the main window's child is a GtkOverlay (activity content below, dialog layers above);
 * a dialog's root is its layer, whose content is the decor */
void tl_dialog_set_content(GtkWidget *layer, GtkWidget *content);

JNIEXPORT void JNICALL Java_android_view_Window_set_1widget_1as_1root(JNIEnv *env, jobject this, jlong window, jlong widget)
{
	GtkWidget *root = GTK_WIDGET(_PTR(window));
	GtkWidget *gtk_widget = gtk_widget_get_parent(GTK_WIDGET(_PTR(widget)));
	if (GTK_IS_WINDOW(root)) {
		GtkWidget *child = gtk_window_get_child(GTK_WINDOW(root));
		if (GTK_IS_OVERLAY(child)) {
			if (gtk_overlay_get_child(GTK_OVERLAY(child)) != gtk_widget)
				gtk_overlay_set_child(GTK_OVERLAY(child), gtk_widget);
		} else if (gtk_widget != child) {
			gtk_window_set_child(GTK_WINDOW(root), gtk_widget);
		}
	} else {
		tl_dialog_set_content(root, gtk_widget);
	}
}
'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
old2='''	GtkWindow *gtk_window = GTK_WINDOW(_PTR(window));
	const char *title = (*env)->GetStringUTFChars(env, title_jstr, NULL);
	gtk_window_set_title(gtk_window, title);
'''
new2='''	const char *title = (*env)->GetStringUTFChars(env, title_jstr, NULL);
	if (GTK_IS_WINDOW(_PTR(window))) /* a dialog layer has no window title; Dialog.setTitle draws it in the decor */
		gtk_window_set_title(GTK_WINDOW(_PTR(window)), title);
'''
assert s.count(old2)==1, s.count(old2)
s=s.replace(old2,new2,1)
old3='''	GtkWindow *gtk_window = GTK_WINDOW(_PTR(window));
	if (width > 0 && height > 0)
		gtk_window_set_default_size(gtk_window, width, height);
'''
new3='''	GtkWidget *root = GTK_WIDGET(_PTR(window));
	if (GTK_IS_WINDOW(root)) {
		if (width > 0 && height > 0)
			gtk_window_set_default_size(GTK_WINDOW(root), width, height);
	} else { /* a dialog layer: Window.setLayout sizes the decor (MATCH_PARENT/WRAP_CONTENT keep the decor's own measure) */
		GtkWidget *content = gtk_widget_get_first_child(root);
		if (content)
			gtk_widget_set_size_request(content, width > 0 ? width : -1, height > 0 ? height : -1);
	}
'''
assert s.count(old3)==1, s.count(old3)
s=s.replace(old3,new3,1)
old4='''	gtk_widget_add_css_class(GTK_WIDGET(_PTR(window)), "ATL-no-background");
'''
new4='''	/* tl_dialog_overlay: a dialog layer's background is AOSP's dim behind the dialog, not a window background */
	if (gtk_widget_has_css_class(GTK_WIDGET(_PTR(window)), "tl-dialog-dim"))
		return;
	gtk_widget_add_css_class(GTK_WIDGET(_PTR(window)), "ATL-no-background");
'''
assert s.count(old4)==1, s.count(old4)
s=s.replace(old4,new4,1)
open(p,'w').write(s)
PY
fi
grep -q "tl_dialog_overlay" "$W"

VW=src/api-impl-jni/views/android_view_View.c
if ! grep -q "tl_dialog_overlay" "$VW"; then
python3 - "$VW" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWindow *window = GTK_WINDOW(gtk_widget_get_native(widget));
	if (fullscreen) {
'''
new='''	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	/* tl_dialog_overlay: a dialog's decor lives in a layer of the main window; its system UI flags
	 * apply to the dialog window in AOSP and must not change the activity window */
	for (GtkWidget *p = widget; p; p = gtk_widget_get_parent(p)) {
		if (gtk_widget_has_css_class(p, "tl-dialog-dim"))
			return;
	}
	if (!gtk_widget_get_native(widget) || !GTK_IS_WINDOW(gtk_widget_get_native(widget)))
		return;
	GtkWindow *window = GTK_WINDOW(gtk_widget_get_native(widget));
	if (fullscreen) {
'''
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new,1))
PY
fi
grep -q "tl_dialog_overlay" "$VW"

if ! grep -q "tl_dialog_overlay" "$A"; then
python3 - "$A" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
assert s.count('	GtkWindow *dialog = GTK_WINDOW(_PTR(ptr));\n')==3, s.count('	GtkWindow *dialog = GTK_WINDOW(_PTR(ptr));\n')
s=s.replace('	GtkWindow *dialog = GTK_WINDOW(_PTR(ptr));\n','	GObject *dialog = G_OBJECT(_PTR(ptr)); /* tl_dialog_overlay: the dialog layer */\n')
old='''	GtkBox *content_area = GTK_BOX(gtk_box_new(GTK_ORIENTATION_VERTICAL, 1));
'''
new='''	GtkBox *content_area = GTK_BOX(gtk_box_new(GTK_ORIENTATION_VERTICAL, 1));
	/* AOSP: the dialog's title view, shown once Dialog.setTitle gives it text */
	GtkWidget *title = gtk_label_new(NULL);
	gtk_widget_add_css_class(title, "title-3");
	gtk_widget_set_visible(title, FALSE);
	gtk_box_append(content_area, title);
	g_object_set_data(G_OBJECT(dialog), "title-label", title);
	const char *pending_title = g_object_get_data(G_OBJECT(dialog), "title");
	if (pending_title && pending_title[0]) {
		gtk_label_set_text(GTK_LABEL(title), pending_title);
		gtk_widget_set_visible(title, TRUE);
	}
'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
old2='	gtk_window_set_child(dialog, GTK_WIDGET(content_area));\n'
new2='	tl_dialog_set_content(GTK_WIDGET(dialog), GTK_WIDGET(content_area));\n'
assert s.count(old2)==1, s.count(old2)
s=s.replace(old2,new2,1)
old3='#include "../generated_headers/android_app_AlertDialog.h"\n'
new3='#include "../generated_headers/android_app_AlertDialog.h"\n\nvoid tl_dialog_set_content(GtkWidget *layer, GtkWidget *content);\n'
assert s.count(old3)==1, s.count(old3)
s=s.replace(old3,new3,1)
open(p,'w').write(s)
PY
fi
grep -q "tl_dialog_overlay" "$A"

if ! grep -q "tl_dialog_overlay" "$M"; then
python3 - "$M" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='GtkWidget *window;\n'
new='GtkWidget *window;\nGtkWidget *tl_main_overlay; /* tl_dialog_overlay: activity content below, dialog layers above */\n'
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
old2='\twindow = gtk_application_window_new(app);\n'
new2='''\twindow = gtk_application_window_new(app);
	tl_main_overlay = gtk_overlay_new();
	gtk_window_set_child(GTK_WINDOW(window), tl_main_overlay);
'''
assert s.count(old2)==1, s.count(old2)
s=s.replace(old2,new2,1)
old3='''	gtk_style_context_add_provider_for_display(gdk_display_get_default(), GTK_STYLE_PROVIDER(cssProvider), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
'''
new3='''	gtk_style_context_add_provider_for_display(gdk_display_get_default(), GTK_STYLE_PROVIDER(cssProvider), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
	/* tl_dialog_overlay: the dim behind a dialog (AOSP dimAmount 0.6) */
	GtkCssProvider *tl_css = gtk_css_provider_new();
	gtk_css_provider_load_from_string(tl_css, "box.tl-dialog-dim { background-color: rgba(0, 0, 0, 0.6); background-image: none; }");
	gtk_style_context_add_provider_for_display(gdk_display_get_default(), GTK_STYLE_PROVIDER(tl_css), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
'''
assert s.count(old3)==1, s.count(old3)
s=s.replace(old3,new3,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_dialog_overlay" "$M"

# a view inside a dialog layer that is not in the window yet has no GTK root/native
if ! grep -q "tl_dialog_overlay_window" "$VW"; then
python3 - "$VW" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	return g_object_get_data(G_OBJECT(gtk_widget_get_root(widget)), "jobject");
'''
new='''	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	/* tl_dialog_overlay_window: the nearest ancestor carrying a Window (a dialog's layer before the
	 * main window, which carries the activity's) */
	for (GtkWidget *w = widget; w; w = gtk_widget_get_parent(w)) {
		jobject jobj = g_object_get_data(G_OBJECT(w), "jobject");
		if (jobj)
			return jobj;
	}
	return NULL;
'''
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new,1))
PY
fi
grep -q "tl_dialog_overlay_window" "$VW"

