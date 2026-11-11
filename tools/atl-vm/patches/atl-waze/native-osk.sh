#!/bin/sh
# teslalinux: built-in on-screen keyboard (tl_osk). AOSP's InputMethodManager shows the system
# input method's window above the activity when a text field asks for soft input; ATL only
# forwards the request to the Wayland compositor's text-input protocol, and a kiosk compositor
# (cage) has no keyboard to show. The keyboard lives in the main window's GtkOverlay (bottom
# layer, no dim) and drives the served InputConnection through InputMethodManager's static
# tlOsk* methods: commitText for keys, deleteSurroundingText for backspace, performEditorAction
# or an ENTER key event for the action key (what the AOSP LatinIME does). Pages: Latin, symbols,
# Hebrew. ATL_OSK=none disables it (a desktop with its own OSK). Appended to the InputMethodManager
# natives. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
I=src/api-impl-jni/views/android_view_inputmethod_InputMethodManager.c
if ! grep -q "tl_osk" "$I"; then
cat >> "$I" <<'EOF'

/* ---- teslalinux: built-in on-screen keyboard (tl_osk) ---- */
extern GtkWidget *tl_main_overlay;

static GtkWidget *tl_osk = NULL;        /* the keyboard layer (vertical box of rows) */
static int tl_osk_page = 0;             /* 0 Latin, 1 symbols, 2 Hebrew */
static int tl_osk_shift = 0;
static int tl_osk_ime_options = 0;
static jclass tl_osk_imm_class = NULL;
static jmethodID tl_osk_text, tl_osk_backspace, tl_osk_action;

#define TL_OSK_BACKSPACE "\xe2\x8c\xab" /* ⌫ */
#define TL_OSK_SHIFT     "\xe2\x87\xa7" /* ⇧ */
#define TL_OSK_ENTER     "\xe2\x8f\x8e" /* ⏎ */
#define TL_OSK_GLOBE     "\xf0\x9f\x8c\x90" /* 🌐 */
#define TL_OSK_SPACE     "space"

/* each row is a space separated list of keys; special keys are the names above, "?123" and "ABC" */
static const char *tl_osk_rows_latin[] = {
	"q w e r t y u i o p",
	"a s d f g h j k l",
	TL_OSK_SHIFT " z x c v b n m " TL_OSK_BACKSPACE,
	"?123 , " TL_OSK_GLOBE " " TL_OSK_SPACE " . " TL_OSK_ENTER,
	NULL
};
static const char *tl_osk_rows_symbols[] = {
	"1 2 3 4 5 6 7 8 9 0",
	"@ # $ % & - + ( ) /",
	"* \" ' : ; ! ? _ " TL_OSK_BACKSPACE,
	"ABC , " TL_OSK_GLOBE " " TL_OSK_SPACE " . " TL_OSK_ENTER,
	NULL
};
static const char *tl_osk_rows_hebrew[] = {
	"\xd7\xa7 \xd7\xa8 \xd7\x90 \xd7\x98 \xd7\x95 \xd7\x9f \xd7\x9d \xd7\xa4 '",
	"\xd7\xa9 \xd7\x93 \xd7\x92 \xd7\x9b \xd7\xa2 \xd7\x99 \xd7\x97 \xd7\x9c \xd7\x9a \xd7\xa3",
	"\xd7\x96 \xd7\xa1 \xd7\x91 \xd7\x94 \xd7\xa0 \xd7\x9e \xd7\xa6 \xd7\xaa \xd7\xa5 " TL_OSK_BACKSPACE,
	"?123 , " TL_OSK_GLOBE " " TL_OSK_SPACE " . " TL_OSK_ENTER,
	NULL
};

static void tl_osk_rebuild(void);

static void tl_osk_call_text(const char *text)
{
	JNIEnv *env = get_jni_env();
	jstring s = (*env)->NewStringUTF(env, text);
	(*env)->CallStaticVoidMethod(env, tl_osk_imm_class, tl_osk_text, s);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	(*env)->DeleteLocalRef(env, s);
}

static void tl_osk_key_clicked(GtkButton *button, gpointer data)
{
	const char *key = g_object_get_data(G_OBJECT(button), "osk-key");
	JNIEnv *env = get_jni_env();
	if (getenv("ATL_DEBUG_IME"))
		fprintf(stderr, "[osk] key '%s' page=%d shift=%d\n", key, tl_osk_page, tl_osk_shift);
	if (!strcmp(key, TL_OSK_BACKSPACE)) {
		(*env)->CallStaticVoidMethod(env, tl_osk_imm_class, tl_osk_backspace);
	} else if (!strcmp(key, TL_OSK_ENTER)) {
		(*env)->CallStaticVoidMethod(env, tl_osk_imm_class, tl_osk_action);
	} else if (!strcmp(key, TL_OSK_SHIFT)) {
		tl_osk_shift = !tl_osk_shift;
		tl_osk_rebuild();
		return;
	} else if (!strcmp(key, TL_OSK_GLOBE)) {
		tl_osk_page = tl_osk_page == 2 ? 0 : 2;
		tl_osk_shift = 0;
		tl_osk_rebuild();
		return;
	} else if (!strcmp(key, "?123")) {
		tl_osk_page = 1;
		tl_osk_rebuild();
		return;
	} else if (!strcmp(key, "ABC")) {
		tl_osk_page = 0;
		tl_osk_shift = 0;
		tl_osk_rebuild();
		return;
	} else if (!strcmp(key, TL_OSK_SPACE)) {
		tl_osk_call_text(" ");
	} else {
		if (tl_osk_page == 0 && tl_osk_shift) {
			char *up = g_utf8_strup(key, -1);
			tl_osk_call_text(up);
			g_free(up);
			tl_osk_shift = 0; /* AOSP: a single shift applies to one key */
			tl_osk_rebuild();
		} else {
			tl_osk_call_text(key);
		}
	}
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

static GtkWidget *tl_osk_make_key(const char *key)
{
	const char *label = key;
	char *shown = NULL;
	if (!strcmp(key, TL_OSK_SPACE))
		label = " ";
	else if (!strcmp(key, TL_OSK_GLOBE))
		label = tl_osk_page == 2 ? "EN" : "\xd7\xa2\xd7\x91"; /* the language the key switches to */
	else if (!strcmp(key, TL_OSK_ENTER)) {
		int action = tl_osk_ime_options & 0xff; /* EditorInfo.IME_MASK_ACTION */
		if (action == 3) label = "Search";
		else if (action == 2) label = "Go";
		else if (action == 4) label = "Send";
		else if (action == 5) label = "Next";
		else if (action == 6) label = "Done";
	} else if (tl_osk_page == 0 && tl_osk_shift && g_utf8_strlen(key, -1) == 1) {
		shown = g_utf8_strup(key, -1);
		label = shown;
	}
	GtkWidget *b = gtk_button_new_with_label(label);
	g_free(shown);
	gtk_widget_set_hexpand(b, TRUE);
	gtk_widget_set_can_focus(b, FALSE); /* GTK focus stays on the text field's view */
	gtk_widget_set_focus_on_click(b, FALSE);
	gtk_widget_add_css_class(b, "tl-osk-key");
	if (!strcmp(key, TL_OSK_SPACE))
		gtk_widget_set_size_request(b, 480, -1);
	else if (!strcmp(key, TL_OSK_SHIFT) || !strcmp(key, TL_OSK_BACKSPACE) || !strcmp(key, TL_OSK_ENTER) || !strcmp(key, "?123") || !strcmp(key, "ABC"))
		gtk_widget_set_size_request(b, 150, -1);
	gboolean special = !strcmp(key, TL_OSK_SPACE) || !strcmp(key, "?123") || !strcmp(key, "ABC") || !strcmp(key, TL_OSK_SHIFT)
	                   || !strcmp(key, TL_OSK_BACKSPACE) || !strcmp(key, TL_OSK_ENTER) || !strcmp(key, TL_OSK_GLOBE);
	if (!special)
		gtk_widget_add_css_class(b, "tl-osk-letter");
	g_object_set_data_full(G_OBJECT(b), "osk-key", g_strdup(key), g_free);
	g_signal_connect(b, "clicked", G_CALLBACK(tl_osk_key_clicked), NULL);
	return b;
}

static void tl_osk_rebuild(void)
{
	if (!tl_osk)
		return;
	GtkWidget *old;
	while ((old = gtk_widget_get_first_child(tl_osk)))
		gtk_box_remove(GTK_BOX(tl_osk), old);
	const char **rows = tl_osk_page == 1 ? tl_osk_rows_symbols : tl_osk_page == 2 ? tl_osk_rows_hebrew : tl_osk_rows_latin;
	for (int r = 0; rows[r]; r++) {
		GtkWidget *row = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
		gtk_widget_set_halign(row, GTK_ALIGN_FILL);
		gtk_widget_set_margin_start(row, 6);
		gtk_widget_set_margin_end(row, 6);
		char **keys = g_strsplit(rows[r], " ", -1);
		for (int k = 0; keys[k]; k++) {
			if (!keys[k][0])
				continue;
			gtk_box_append(GTK_BOX(row), tl_osk_make_key(keys[k]));
		}
		g_strfreev(keys);
		gtk_box_append(GTK_BOX(tl_osk), row);
	}
}

static void tl_osk_create(JNIEnv *env)
{
	jclass cls = (*env)->FindClass(env, "android/view/inputmethod/InputMethodManager");
	tl_osk_imm_class = (*env)->NewGlobalRef(env, cls);
	tl_osk_text = (*env)->GetStaticMethodID(env, cls, "tlOskText", "(Ljava/lang/String;)V");
	tl_osk_backspace = (*env)->GetStaticMethodID(env, cls, "tlOskBackspace", "()V");
	tl_osk_action = (*env)->GetStaticMethodID(env, cls, "tlOskAction", "()V");
	GtkCssProvider *css = gtk_css_provider_new();
	gtk_css_provider_load_from_string(css,
		".tl-osk { background-color: #202124; padding: 6px 0 8px 0; }"
		".tl-osk button.tl-osk-key { font-size: 22px; min-height: 52px; min-width: 40px; padding: 4px; margin: 3px 0;"
		"  border-radius: 8px; background-image: none; background-color: #5f6368; color: #ffffff; border: none; box-shadow: none; }"
		".tl-osk button.tl-osk-letter { background-color: #3c4043; }"
		".tl-osk button.tl-osk-key:active { background-color: #8ab4f8; }");
	gtk_style_context_add_provider_for_display(gdk_display_get_default(), GTK_STYLE_PROVIDER(css), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
	tl_osk = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
	gtk_widget_add_css_class(tl_osk, "tl-osk");
	gtk_widget_set_halign(tl_osk, GTK_ALIGN_FILL);
	gtk_widget_set_valign(tl_osk, GTK_ALIGN_END);
	gtk_widget_set_hexpand(tl_osk, TRUE);
	gtk_widget_set_can_focus(tl_osk, FALSE);
	g_object_ref_sink(tl_osk);
	tl_osk_rebuild();
}

JNIEXPORT void JNICALL Java_android_view_inputmethod_InputMethodManager_nativeOskShow(JNIEnv *env, jclass clazz, jint ime_options, jint input_type)
{
	if (!tl_main_overlay)
		return;
	if (!tl_osk)
		tl_osk_create(env);
	int number = (input_type & 0xf) == 2 || (input_type & 0xf) == 3; /* TYPE_CLASS_NUMBER / PHONE */
	int page = number ? 1 : (tl_osk_page == 1 ? 0 : tl_osk_page);
	if (page != tl_osk_page || ime_options != tl_osk_ime_options) {
		tl_osk_page = page;
		tl_osk_ime_options = ime_options;
		tl_osk_rebuild();
	}
	if (!gtk_widget_get_parent(tl_osk)) {
		gtk_overlay_add_overlay(GTK_OVERLAY(tl_main_overlay), tl_osk);
		gtk_overlay_set_measure_overlay(GTK_OVERLAY(tl_main_overlay), tl_osk, FALSE);
	}
	gtk_widget_set_visible(tl_osk, TRUE);
}

JNIEXPORT void JNICALL Java_android_view_inputmethod_InputMethodManager_nativeOskHide(JNIEnv *env, jclass clazz)
{
	if (!tl_osk)
		return;
	gtk_widget_set_visible(tl_osk, FALSE);
	if (tl_main_overlay && gtk_widget_get_parent(tl_osk) == tl_main_overlay)
		gtk_overlay_remove_overlay(GTK_OVERLAY(tl_main_overlay), tl_osk);
}

JNIEXPORT jboolean JNICALL Java_android_view_inputmethod_InputMethodManager_nativeOskIsShown(JNIEnv *env, jclass clazz)
{
	return tl_osk && gtk_widget_get_parent(tl_osk) && gtk_widget_get_visible(tl_osk);
}
EOF
fi
grep -c "tl_osk" "$I"
