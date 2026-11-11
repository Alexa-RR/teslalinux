#!/bin/sh
# teslalinux: GtkIMContext natives for the full android.view.inputmethod.InputMethodManager
# (session start/end, activation, cursor location, surrounding text push). Appended to ATL's
# android_view_inputmethod_InputMethodManager.c which keeps the IM context callbacks. Run by
# build-atl.sh from ~/atl-src. Idempotent.
set -eu
F=src/api-impl-jni/views/android_view_inputmethod_InputMethodManager.c
if ! grep -q "nativeStartInput" "$F"; then
cat >> "$F" <<'EOF'

/* ---- teslalinux: InputMethodManager session natives (native-imm.sh) ---- */

JNIEXPORT void JNICALL Java_android_view_inputmethod_InputMethodManager_nativeStartInput(JNIEnv *env, jclass clazz, jlong context_ptr, jlong widget_ptr, jobject new_connection, jint input_type)
{
	GtkIMContext *context = GTK_IM_CONTEXT(_PTR(context_ptr));
	if (connection) {
		_UNREF(connection);
		connection = NULL;
	}
	if (!widget_ptr || !new_connection) {
		gtk_im_context_focus_out(context);
		gtk_im_context_set_client_widget(context, NULL);
		return;
	}
	GtkWidget *widget = GTK_WIDGET(_PTR(widget_ptr));
	connection = _REF(new_connection);
	set_input_type(context, input_type);
	gtk_im_context_set_client_widget(context, widget);
	gtk_im_context_reset(context);
}

JNIEXPORT jboolean JNICALL Java_android_view_inputmethod_InputMethodManager_nativeActivate(JNIEnv *env, jclass clazz, jlong context_ptr)
{
	GtkIMContext *context = GTK_IM_CONTEXT(_PTR(context_ptr));
	if (!connection)
		return JNI_FALSE;
	gtk_im_context_focus_in(context);
	/* the Wayland text-input object needs a round trip after the client widget changes, so retry once */
	g_timeout_add(10, activate_osk, context);
	return gtk_im_context_activate_osk(context, NULL);
}

JNIEXPORT void JNICALL Java_android_view_inputmethod_InputMethodManager_nativeDeactivate(JNIEnv *env, jclass clazz, jlong context_ptr)
{
	GtkIMContext *context = GTK_IM_CONTEXT(_PTR(context_ptr));
	gtk_im_context_focus_out(context);
}

JNIEXPORT void JNICALL Java_android_view_inputmethod_InputMethodManager_nativeSetCursorLocation(JNIEnv *env, jclass clazz, jlong context_ptr, jint x, jint y, jint width, jint height)
{
	GtkIMContext *context = GTK_IM_CONTEXT(_PTR(context_ptr));
	GdkRectangle area = { x, y, width, height };
	gtk_im_context_set_cursor_location(context, &area);
}

JNIEXPORT void JNICALL Java_android_view_inputmethod_InputMethodManager_nativeUpdateSurrounding(JNIEnv *env, jclass clazz, jlong context_ptr)
{
	GtkIMContext *context = GTK_IM_CONTEXT(_PTR(context_ptr));
	if (connection)
		retrieve_surrounding_cb(context, NULL);
}
EOF
fi
grep -c "nativeStartInput" "$F"
