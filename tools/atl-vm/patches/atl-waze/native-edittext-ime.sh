#!/bin/sh
# teslalinux: EditText as an input method target (tl_edittext_ime). AOSP's TextView creates an
# EditableInputConnection for the input method, starts input when it gains focus and asks for the
# soft input on touch. ATL's EditText is a GtkText with no connection to InputMethodManager, so the
# on-screen keyboard could not type into it. Natives: text insertion/deletion at the cursor,
# selection, the "activate" action, and a GTK focus hook that calls EditText.tlGtkFocusChanged.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
E=src/api-impl-jni/widgets/android_widget_EditText.c
if ! grep -q "tl_edittext_ime" "$E"; then
python3 - "$E" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	GtkWidget *gtk_text = gtk_text_new();
	wrapper_widget_set_child(WRAPPER_WIDGET(wrapper), gtk_text);
	return _INTPTR(gtk_text);
'''
new='''	GtkWidget *gtk_text = gtk_text_new();
	wrapper_widget_set_child(WRAPPER_WIDGET(wrapper), gtk_text);
	g_signal_connect(gtk_text, "notify::has-focus", G_CALLBACK(tl_edittext_focus_cb), NULL); /* tl_edittext_ime */
	return _INTPTR(gtk_text);
'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
anchor='JNIEXPORT jlong JNICALL Java_android_widget_EditText_native_1constructor'
assert s.count(anchor)==1
helper='''/* ---- teslalinux: EditText as an input method target (tl_edittext_ime) ---- */
static void tl_edittext_focus_cb(GtkWidget *gtk_text, GParamSpec *pspec, gpointer data)
{
	GtkWidget *wrapper = gtk_widget_get_parent(gtk_text);
	if (!wrapper || !WRAPPER_IS_WIDGET(wrapper))
		return;
	WrapperWidget *w = WRAPPER_WIDGET(wrapper);
	JNIEnv *env = get_jni_env();
	if (!w->jobj || (*env)->IsSameObject(env, w->jobj, NULL))
		return;
	jmethodID m = _METHOD(_CLASS(w->jobj), "tlGtkFocusChanged", "(Z)V");
	(*env)->CallVoidMethod(env, w->jobj, m, (jboolean)gtk_widget_has_focus(gtk_text));
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

'''
s=s.replace(anchor, helper+anchor, 1)
s+='''
/* positions are GtkEditable character offsets */
JNIEXPORT void JNICALL Java_android_widget_EditText_native_1insertText(JNIEnv *env, jobject this, jlong widget_ptr, jstring text_jstr)
{
	GtkEditable *editable = GTK_EDITABLE(_PTR(widget_ptr));
	const char *text = (*env)->GetStringUTFChars(env, text_jstr, NULL);
	int start, end;
	if (gtk_editable_get_selection_bounds(editable, &start, &end))
		gtk_editable_delete_text(editable, start, end);
	int pos = gtk_editable_get_position(editable);
	gtk_editable_insert_text(editable, text, -1, &pos);
	gtk_editable_set_position(editable, pos);
	(*env)->ReleaseStringUTFChars(env, text_jstr, text);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1deleteRange(JNIEnv *env, jobject this, jlong widget_ptr, jint start, jint end)
{
	GtkEditable *editable = GTK_EDITABLE(_PTR(widget_ptr));
	if (start < 0)
		start = 0;
	if (end > start)
		gtk_editable_delete_text(editable, start, end);
	gtk_editable_set_position(editable, start);
}

JNIEXPORT jint JNICALL Java_android_widget_EditText_native_1getCursor(JNIEnv *env, jobject this, jlong widget_ptr)
{
	return gtk_editable_get_position(GTK_EDITABLE(_PTR(widget_ptr)));
}

/* out: selection start and end (equal when there is none) */
JNIEXPORT void JNICALL Java_android_widget_EditText_native_1getSelection(JNIEnv *env, jobject this, jlong widget_ptr, jintArray out_array)
{
	GtkEditable *editable = GTK_EDITABLE(_PTR(widget_ptr));
	jint out[2];
	int start, end;
	if (gtk_editable_get_selection_bounds(editable, &start, &end)) {
		out[0] = start;
		out[1] = end;
	} else {
		out[0] = out[1] = gtk_editable_get_position(editable);
	}
	(*env)->SetIntArrayRegion(env, out_array, 0, 2, out);
}

JNIEXPORT void JNICALL Java_android_widget_EditText_native_1setSelection(JNIEnv *env, jobject this, jlong widget_ptr, jint start, jint end)
{
	GtkEditable *editable = GTK_EDITABLE(_PTR(widget_ptr));
	if (start == end)
		gtk_editable_set_position(editable, start);
	else
		gtk_editable_select_region(editable, start, end);
}

/* the editor action: GtkText's "activate" (what Enter does), which reaches OnEditorActionListener */
JNIEXPORT void JNICALL Java_android_widget_EditText_native_1activate(JNIEnv *env, jobject this, jlong widget_ptr)
{
	g_signal_emit_by_name(_PTR(widget_ptr), "activate");
}

JNIEXPORT jint JNICALL Java_android_widget_EditText_native_1getLength(JNIEnv *env, jobject this, jlong widget_ptr)
{
	return gtk_entry_buffer_get_length(gtk_text_get_buffer(GTK_TEXT(_PTR(widget_ptr))));
}
'''
open(p,'w').write(s)
PY
fi
grep -c "tl_edittext_ime" "$E"
