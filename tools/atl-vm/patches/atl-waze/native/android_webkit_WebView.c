/* teslalinux (tl_webkit): android.webkit.WebView on WebKitGTK 6.0.
 *
 * The Java WebView owns a GtkOverlay ("holder") as its widget. The WebKitWebView ("engine") is created
 * lazily, the first time the page is used (load, script, history), because apps routinely construct a
 * WebView they never use (Waze does so in FreeMapAppActivity.onCreate as a dark-mode workaround) and an
 * engine costs a web process and a network process. All engines share one persistent
 * WebKitNetworkSession under the app's data directory (app_webview, cache/WebView, as AOSP).
 *
 * Every WebKit signal is turned into one call of a package-private "internal*" method of
 * android.webkit.WebView, which implements the WebViewClient/WebChromeClient semantics in Java. Requests
 * that need an answer later (script dialogs, permission, file chooser, authentication) are ref'd here and
 * finished by the matching native*Finish call. The Java WebView reference kept on the engine is a weak
 * global reference, dropped in nativeDestroy. */
#include <gio/gunixinputstream.h>
#include <gtk/gtk.h>
#include <webkit/webkit.h>

#include <androidfw/androidfw_c_api.h>

#include "../defines.h"
#include "../util.h"

#include "../AssetInputStream.h"
#include "WrapperWidget.h"

#include "../generated_headers/android_view_View.h"
#include "../generated_headers/android_webkit_WebView.h"

static WebKitNetworkSession *tl_session;
static jclass tl_wv_class;
static struct {
	jmethodID loadChanged, loadFailed, tlsError, decideNavigation, decideResponse, createWindow, closeWindow,
	    titleChanged, progressChanged, scriptDialog, permissionRequest, fileChooser, authenticate,
	    processTerminated, responsiveChanged, evalResult, serveContent, uriChanged;
} tl_m;

static void tl_wv_ids(JNIEnv *env)
{
	if (tl_wv_class)
		return;
	tl_wv_class = _REF((*env)->FindClass(env, "android/webkit/WebView"));
	jclass c = tl_wv_class;
	tl_m.loadChanged = _METHOD(c, "internalLoadChanged", "(ILjava/lang/String;)V");
	tl_m.loadFailed = _METHOD(c, "internalLoadFailed", "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V");
	tl_m.tlsError = _METHOD(c, "internalTlsError", "(Ljava/lang/String;I)V");
	tl_m.decideNavigation = _METHOD(c, "internalDecideNavigation", "(Ljava/lang/String;Ljava/lang/String;ZZIZ)Z");
	tl_m.decideResponse = _METHOD(c, "internalDecideResponse", "(Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;ILjava/lang/String;ZZ[Ljava/lang/String;)Z");
	tl_m.createWindow = _METHOD(c, "internalCreateWindow", "(Ljava/lang/String;Z)J");
	tl_m.closeWindow = _METHOD(c, "internalCloseWindow", "()V");
	tl_m.titleChanged = _METHOD(c, "internalTitleChanged", "(Ljava/lang/String;)V");
	tl_m.progressChanged = _METHOD(c, "internalProgressChanged", "(I)V");
	tl_m.scriptDialog = _METHOD(c, "internalScriptDialog", "(JILjava/lang/String;Ljava/lang/String;)V");
	tl_m.permissionRequest = _METHOD(c, "internalPermissionRequest", "(JI)V");
	tl_m.fileChooser = _METHOD(c, "internalFileChooser", "(J[Ljava/lang/String;Z)V");
	tl_m.authenticate = _METHOD(c, "internalAuthenticate", "(JLjava/lang/String;Ljava/lang/String;Z)V");
	tl_m.processTerminated = _METHOD(c, "internalProcessTerminated", "(I)V");
	tl_m.responsiveChanged = _METHOD(c, "internalResponsiveChanged", "(Z)V");
	tl_m.evalResult = _METHOD(c, "internalEvalResult", "(ILjava/lang/String;)V");
	tl_m.serveContent = _METHOD(c, "internalServeContent", "(Ljava/lang/String;)[Ljava/lang/Object;");
	tl_m.uriChanged = _METHOD(c, "internalUriChanged", "(Ljava/lang/String;)V");
}

/* signal handlers run from the GLib main loop inside one never-returning JNI call: free their local
 * references with a frame each */
#define TL_LEAVE(env, value) ((*env)->PopLocalFrame(env, NULL), (value))

static jobject tl_jobj(WebKitWebView *wv)
{
	return wv ? g_object_get_data(G_OBJECT(wv), "tl-jobj") : NULL;
}

static void tl_check(JNIEnv *env)
{
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
	}
}

static jstring tl_jstr(JNIEnv *env, const char *s)
{
	return s ? (*env)->NewStringUTF(env, s) : NULL;
}

/* GetStringUTFChars hands out modified UTF-8; WebKit wants real UTF-8 (supplementary characters,
 * U+0000). Convert from UTF-16. Free with g_free. */
static char *tl_utf8(JNIEnv *env, jstring s)
{
	if (!s)
		return NULL;
	const jchar *chars = (*env)->GetStringChars(env, s, NULL);
	jsize len = (*env)->GetStringLength(env, s);
	char *out = g_utf16_to_utf8((const gunichar2 *)chars, len, NULL, NULL, NULL);
	(*env)->ReleaseStringChars(env, s, chars);
	return out ? out : g_strdup("");
}

static char **tl_strv(JNIEnv *env, jobjectArray arr)
{
	if (!arr)
		return NULL;
	jsize n = (*env)->GetArrayLength(env, arr);
	char **v = g_new0(char *, n + 1);
	for (jsize i = 0; i < n; i++) {
		jstring s = (*env)->GetObjectArrayElement(env, arr, i);
		v[i] = tl_utf8(env, s);
		if (!v[i])
			v[i] = g_strdup("");
		(*env)->DeleteLocalRef(env, s);
	}
	return v;
}

static jobjectArray tl_jstrv(JNIEnv *env, const char *const *v)
{
	jsize n = 0;
	while (v && v[n])
		n++;
	jobjectArray arr = (*env)->NewObjectArray(env, n, (*env)->FindClass(env, "java/lang/String"), NULL);
	for (jsize i = 0; i < n; i++) {
		jstring s = (*env)->NewStringUTF(env, v[i]);
		(*env)->SetObjectArrayElement(env, arr, i, s);
		(*env)->DeleteLocalRef(env, s);
	}
	return arr;
}

/* ---- custom URI schemes: android-asset (APK assets), content (ContentResolver) ---- */

static void asset_uri_scheme_request_cb(WebKitURISchemeRequest *request, gpointer user_data)
{
	const gchar *path = webkit_uri_scheme_request_get_path(request);
	JNIEnv *env = get_jni_env();
	(*env)->PushLocalFrame(env, 16);
	jobject jobj = tl_jobj(webkit_uri_scheme_request_get_web_view(request));
	struct Asset *asset = NULL;
	if (path && path[0] == '/' && jobj) {
		jobject asset_manager_obj = (*env)->CallObjectMethod(env, jobj, handle_cache.webview.internalGetAssetManager);
		tl_check(env);
		if (asset_manager_obj) {
			struct AssetManager *asset_manager = _PTR(_GET_LONG_FIELD(asset_manager_obj, "mObject"));
			asset = AssetManager_openNonAsset(asset_manager, path + 1, ACCESS_STREAMING);
		}
	}
	if (!asset) {
		GError *error = g_error_new(G_IO_ERROR, G_IO_ERROR_NOT_FOUND, "no asset %s", path ? path : "");
		webkit_uri_scheme_request_finish_error(request, error);
		g_error_free(error);
		(*env)->PopLocalFrame(env, NULL);
		return;
	}
	GInputStream *stream = asset_input_stream_new(asset);
	webkit_uri_scheme_request_finish(request, stream, Asset_getLength(asset), NULL);
	g_object_unref(stream);
	(*env)->PopLocalFrame(env, NULL);
}

/* content:// through WebView.internalServeContent (checks WebSettings.getAllowContentAccess and opens
 * the stream with the app's ContentResolver); it returns {byte[] data, String mimeType} or null. */
static void content_uri_scheme_request_cb(WebKitURISchemeRequest *request, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	tl_wv_ids(env);
	(*env)->PushLocalFrame(env, 16);
	jobject jobj = tl_jobj(webkit_uri_scheme_request_get_web_view(request));
	jobjectArray res = NULL;
	if (jobj) {
		jstring juri = tl_jstr(env, webkit_uri_scheme_request_get_uri(request));
		res = (*env)->CallObjectMethod(env, jobj, tl_m.serveContent, juri);
		tl_check(env);
	}
	if (!res) {
		GError *error = g_error_new(G_IO_ERROR, G_IO_ERROR_PERMISSION_DENIED, "content access denied or not found");
		webkit_uri_scheme_request_finish_error(request, error);
		g_error_free(error);
		(*env)->PopLocalFrame(env, NULL);
		return;
	}
	jbyteArray data = (*env)->GetObjectArrayElement(env, res, 0);
	jstring mime = (*env)->GetObjectArrayElement(env, res, 1);
	jsize len = (*env)->GetArrayLength(env, data);
	gpointer buf = g_malloc(len ? len : 1);
	(*env)->GetByteArrayRegion(env, data, 0, len, buf);
	GBytes *bytes = g_bytes_new_take(buf, len);
	GInputStream *stream = g_memory_input_stream_new_from_bytes(bytes);
	char *cmime = tl_utf8(env, mime);
	webkit_uri_scheme_request_finish(request, stream, len, cmime);
	g_free(cmime);
	g_object_unref(stream);
	g_bytes_unref(bytes);
	(*env)->PopLocalFrame(env, NULL);
}

/* ---- session ---- */

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeInitSession(JNIEnv *env, jclass klass, jstring data_dir, jstring cache_dir, jint cookie_policy)
{
	if (tl_session)
		return;
	char *d = tl_utf8(env, data_dir);
	char *c = tl_utf8(env, cache_dir);
	tl_session = webkit_network_session_new(d, c);
	WebKitCookieManager *cm = webkit_network_session_get_cookie_manager(tl_session);
	char *cookies = g_build_filename(d, "Cookies", NULL);
	webkit_cookie_manager_set_persistent_storage(cm, cookies, WEBKIT_COOKIE_PERSISTENT_STORAGE_SQLITE);
	webkit_cookie_manager_set_accept_policy(cm, cookie_policy);
	g_free(cookies);
	g_free(d);
	g_free(c);
}

/* ---- signal handlers ---- */

static void on_load_changed(WebKitWebView *wv, WebKitLoadEvent load_event, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return;
	(*env)->PushLocalFrame(env, 32);
	jstring uri = tl_jstr(env, webkit_web_view_get_uri(wv));
	(*env)->CallVoidMethod(env, jobj, tl_m.loadChanged, (jint)load_event, uri);
	tl_check(env);
	(*env)->PopLocalFrame(env, NULL);
}

static gboolean on_load_failed(WebKitWebView *wv, WebKitLoadEvent load_event, gchar *failing_uri, GError *error, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return FALSE;
	(*env)->PushLocalFrame(env, 32);
	(*env)->CallVoidMethod(env, jobj, tl_m.loadFailed, tl_jstr(env, failing_uri),
	                       tl_jstr(env, g_quark_to_string(error->domain)), (jint)error->code, tl_jstr(env, error->message));
	tl_check(env);
	/* Android WebView shows no engine error page of its own: the app decides (onReceivedError). */
	return TL_LEAVE(env, TRUE);
}

static gboolean on_load_failed_tls(WebKitWebView *wv, gchar *failing_uri, GTlsCertificate *certificate, GTlsCertificateFlags errors, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return FALSE;
	(*env)->PushLocalFrame(env, 32);
	(*env)->CallVoidMethod(env, jobj, tl_m.tlsError, tl_jstr(env, failing_uri), (jint)errors);
	tl_check(env);
	return TL_LEAVE(env, TRUE);
}

static void tl_headers_cb(const char *name, const char *value, gpointer user_data)
{
	GPtrArray *a = user_data;
	g_ptr_array_add(a, g_strdup(name));
	g_ptr_array_add(a, g_strdup(value));
}

static gboolean on_decide_policy(WebKitWebView *wv, WebKitPolicyDecision *decision, WebKitPolicyDecisionType type, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return FALSE;
	(*env)->PushLocalFrame(env, 32);
	if (type == WEBKIT_POLICY_DECISION_TYPE_NAVIGATION_ACTION) {
		WebKitNavigationAction *action = webkit_navigation_policy_decision_get_navigation_action(WEBKIT_NAVIGATION_POLICY_DECISION(decision));
		WebKitURIRequest *req = webkit_navigation_action_get_request(action);
		jboolean ignore = (*env)->CallBooleanMethod(env, jobj, tl_m.decideNavigation,
		                                            tl_jstr(env, webkit_uri_request_get_uri(req)),
		                                            tl_jstr(env, webkit_uri_request_get_http_method(req)),
		                                            (jboolean)webkit_navigation_action_is_redirect(action),
		                                            (jboolean)webkit_navigation_action_is_user_gesture(action),
		                                            (jint)webkit_navigation_action_get_navigation_type(action),
		                                            JNI_FALSE);
		tl_check(env);
		if (ignore) {
			webkit_policy_decision_ignore(decision);
			return TL_LEAVE(env, TRUE);
		}
		return TL_LEAVE(env, FALSE);
	}
	if (type == WEBKIT_POLICY_DECISION_TYPE_RESPONSE) {
		WebKitResponsePolicyDecision *rd = WEBKIT_RESPONSE_POLICY_DECISION(decision);
		WebKitURIResponse *resp = webkit_response_policy_decision_get_response(rd);
		SoupMessageHeaders *headers = webkit_uri_response_get_http_headers(resp);
		GPtrArray *hv = g_ptr_array_new_with_free_func(g_free);
		const char *disposition = NULL;
		if (headers) {
			soup_message_headers_foreach(headers, tl_headers_cb, hv);
			disposition = soup_message_headers_get_one(headers, "Content-Disposition");
		}
		g_ptr_array_add(hv, NULL);
		guint status = webkit_uri_response_get_status_code(resp);
		jobjectArray jheaders = tl_jstrv(env, (const char *const *)hv->pdata);
		jboolean ignore = (*env)->CallBooleanMethod(env, jobj, tl_m.decideResponse,
		                                            tl_jstr(env, webkit_uri_response_get_uri(resp)),
		                                            tl_jstr(env, webkit_uri_response_get_mime_type(resp)),
		                                            (jlong)webkit_uri_response_get_content_length(resp),
		                                            tl_jstr(env, disposition), (jint)status,
		                                            tl_jstr(env, status ? soup_status_get_phrase(status) : NULL),
		                                            (jboolean)webkit_response_policy_decision_is_main_frame_main_resource(rd),
		                                            (jboolean)webkit_response_policy_decision_is_mime_type_supported(rd),
		                                            jheaders);
		tl_check(env);
		g_ptr_array_free(hv, TRUE);
		if (ignore) {
			webkit_policy_decision_ignore(decision);
			return TL_LEAVE(env, TRUE);
		}
		return TL_LEAVE(env, FALSE);
	}
	/* NEW_WINDOW_ACTION: allow, so that "create" decides (WebChromeClient.onCreateWindow or same view). */
	return TL_LEAVE(env, FALSE);
}

static GtkWidget *on_create(WebKitWebView *wv, WebKitNavigationAction *action, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return NULL;
	(*env)->PushLocalFrame(env, 32);
	WebKitURIRequest *req = webkit_navigation_action_get_request(action);
	jlong child = (*env)->CallLongMethod(env, jobj, tl_m.createWindow, tl_jstr(env, webkit_uri_request_get_uri(req)),
	                                     (jboolean)webkit_navigation_action_is_user_gesture(action));
	tl_check(env);
	return TL_LEAVE(env, child ? GTK_WIDGET(_PTR(child)) : NULL);
}

static void on_close(WebKitWebView *wv, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return;
	(*env)->PushLocalFrame(env, 32);
	(*env)->CallVoidMethod(env, jobj, tl_m.closeWindow);
	tl_check(env);
	(*env)->PopLocalFrame(env, NULL);
}

static void on_notify_title(WebKitWebView *wv, GParamSpec *pspec, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return;
	(*env)->PushLocalFrame(env, 32);
	(*env)->CallVoidMethod(env, jobj, tl_m.titleChanged, tl_jstr(env, webkit_web_view_get_title(wv)));
	tl_check(env);
	(*env)->PopLocalFrame(env, NULL);
}

static void on_notify_uri(WebKitWebView *wv, GParamSpec *pspec, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return;
	(*env)->PushLocalFrame(env, 32);
	(*env)->CallVoidMethod(env, jobj, tl_m.uriChanged, tl_jstr(env, webkit_web_view_get_uri(wv)));
	tl_check(env);
	(*env)->PopLocalFrame(env, NULL);
}

static void on_notify_progress(WebKitWebView *wv, GParamSpec *pspec, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return;
	(*env)->PushLocalFrame(env, 32);
	(*env)->CallVoidMethod(env, jobj, tl_m.progressChanged, (jint)(webkit_web_view_get_estimated_load_progress(wv) * 100.0 + 0.5));
	tl_check(env);
	(*env)->PopLocalFrame(env, NULL);
}

static void on_notify_responsive(WebKitWebView *wv, GParamSpec *pspec, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return;
	(*env)->PushLocalFrame(env, 32);
	(*env)->CallVoidMethod(env, jobj, tl_m.responsiveChanged, (jboolean)webkit_web_view_get_is_web_process_responsive(wv));
	tl_check(env);
	(*env)->PopLocalFrame(env, NULL);
}

static void on_web_process_terminated(WebKitWebView *wv, WebKitWebProcessTerminationReason reason, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return;
	(*env)->PushLocalFrame(env, 32);
	(*env)->CallVoidMethod(env, jobj, tl_m.processTerminated, (jint)reason);
	tl_check(env);
	(*env)->PopLocalFrame(env, NULL);
}

/* Dialogs are answered asynchronously: ref here, nativeScriptDialogClose finishes them. */
static gboolean on_script_dialog(WebKitWebView *wv, WebKitScriptDialog *dialog, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return FALSE;
	(*env)->PushLocalFrame(env, 32);
	WebKitScriptDialogType type = webkit_script_dialog_get_dialog_type(dialog);
	const char *def = type == WEBKIT_SCRIPT_DIALOG_PROMPT ? webkit_script_dialog_prompt_get_default_text(dialog) : NULL;
	webkit_script_dialog_ref(dialog);
	(*env)->CallVoidMethod(env, jobj, tl_m.scriptDialog, _INTPTR(dialog), (jint)type,
	                       tl_jstr(env, webkit_script_dialog_get_message(dialog)), tl_jstr(env, def));
	tl_check(env);
	return TL_LEAVE(env, TRUE);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeScriptDialogClose(JNIEnv *env, jclass klass, jlong dialog_ptr, jboolean confirmed, jstring text)
{
	WebKitScriptDialog *dialog = _PTR(dialog_ptr);
	WebKitScriptDialogType type = webkit_script_dialog_get_dialog_type(dialog);
	if (type == WEBKIT_SCRIPT_DIALOG_CONFIRM || type == WEBKIT_SCRIPT_DIALOG_BEFORE_UNLOAD_CONFIRM)
		webkit_script_dialog_confirm_set_confirmed(dialog, confirmed);
	else if (type == WEBKIT_SCRIPT_DIALOG_PROMPT && confirmed) {
		char *t = tl_utf8(env, text);
		webkit_script_dialog_prompt_set_text(dialog, t ? t : "");
		g_free(t);
	}
	webkit_script_dialog_close(dialog);
	webkit_script_dialog_unref(dialog);
}

/* kinds: 1 geolocation, 2 camera, 3 microphone, 4 camera+microphone, 5 protected media id, 0 other */
static gboolean on_permission_request(WebKitWebView *wv, WebKitPermissionRequest *request, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return FALSE;
	(*env)->PushLocalFrame(env, 32);
	int kind = 0;
	if (WEBKIT_IS_GEOLOCATION_PERMISSION_REQUEST(request))
		kind = 1;
	else if (WEBKIT_IS_USER_MEDIA_PERMISSION_REQUEST(request)) {
		WebKitUserMediaPermissionRequest *um = WEBKIT_USER_MEDIA_PERMISSION_REQUEST(request);
		gboolean v = webkit_user_media_permission_is_for_video_device(um);
		gboolean a = webkit_user_media_permission_is_for_audio_device(um);
		kind = (v && a) ? 4 : v ? 2 : a ? 3 : 0;
	} else if (WEBKIT_IS_MEDIA_KEY_SYSTEM_PERMISSION_REQUEST(request))
		kind = 5;
	g_object_ref(request);
	(*env)->CallVoidMethod(env, jobj, tl_m.permissionRequest, _INTPTR(request), (jint)kind);
	tl_check(env);
	return TL_LEAVE(env, TRUE);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativePermissionFinish(JNIEnv *env, jclass klass, jlong request_ptr, jboolean allow)
{
	WebKitPermissionRequest *request = _PTR(request_ptr);
	if (allow)
		webkit_permission_request_allow(request);
	else
		webkit_permission_request_deny(request);
	g_object_unref(request);
}

static gboolean on_run_file_chooser(WebKitWebView *wv, WebKitFileChooserRequest *request, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return FALSE;
	(*env)->PushLocalFrame(env, 32);
	g_object_ref(request);
	(*env)->CallVoidMethod(env, jobj, tl_m.fileChooser, _INTPTR(request),
	                       tl_jstrv(env, webkit_file_chooser_request_get_mime_types(request)),
	                       (jboolean)webkit_file_chooser_request_get_select_multiple(request));
	tl_check(env);
	return TL_LEAVE(env, TRUE);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeFileChooserFinish(JNIEnv *env, jclass klass, jlong request_ptr, jobjectArray paths)
{
	WebKitFileChooserRequest *request = _PTR(request_ptr);
	if (paths && (*env)->GetArrayLength(env, paths) > 0) {
		char **v = tl_strv(env, paths);
		webkit_file_chooser_request_select_files(request, (const gchar *const *)v);
		g_strfreev(v);
	} else {
		webkit_file_chooser_request_cancel(request);
	}
	g_object_unref(request);
}

static gboolean on_authenticate(WebKitWebView *wv, WebKitAuthenticationRequest *request, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	jobject jobj = tl_jobj(wv);
	if (!jobj)
		return FALSE;
	(*env)->PushLocalFrame(env, 32);
	g_object_ref(request);
	(*env)->CallVoidMethod(env, jobj, tl_m.authenticate, _INTPTR(request),
	                       tl_jstr(env, webkit_authentication_request_get_host(request)),
	                       tl_jstr(env, webkit_authentication_request_get_realm(request)),
	                       (jboolean)webkit_authentication_request_is_retry(request));
	tl_check(env);
	return TL_LEAVE(env, TRUE);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeAuthFinish(JNIEnv *env, jclass klass, jlong request_ptr, jstring user, jstring password)
{
	WebKitAuthenticationRequest *request = _PTR(request_ptr);
	if (user) {
		char *u = tl_utf8(env, user);
		char *p = tl_utf8(env, password);
		WebKitCredential *cred = webkit_credential_new(u, p ? p : "", WEBKIT_CREDENTIAL_PERSISTENCE_FOR_SESSION);
		webkit_authentication_request_authenticate(request, cred);
		webkit_credential_free(cred);
		g_free(u);
		g_free(p);
	} else {
		webkit_authentication_request_cancel(request);
	}
	g_object_unref(request);
}

/* ---- construction ---- */

JNIEXPORT jlong JNICALL Java_android_webkit_WebView_native_1constructor(JNIEnv *env, jobject this, jobject context, jobject attrs)
{
	GtkWidget *wrapper = g_object_ref(wrapper_widget_new());
	GtkWidget *holder = gtk_overlay_new();
	gtk_widget_set_name(holder, "android.webkit.WebView");
	wrapper_widget_set_child(WRAPPER_WIDGET(wrapper), holder);
	wrapper_widget_set_jobject(WRAPPER_WIDGET(wrapper), env, this);
	return _INTPTR(holder);
}

static void tl_engine_setup(JNIEnv *env, jobject this, WebKitWebView *wv)
{
	static gboolean schemes_registered;
	WebKitWebContext *ctx = webkit_web_view_get_context(wv);
	if (!schemes_registered) {
		schemes_registered = TRUE;
		webkit_web_context_register_uri_scheme(ctx, "android-asset", asset_uri_scheme_request_cb, NULL, NULL);
		webkit_web_context_register_uri_scheme(ctx, "content", content_uri_scheme_request_cb, NULL, NULL);
		WebKitSecurityManager *sm = webkit_web_context_get_security_manager(ctx);
		webkit_security_manager_register_uri_scheme_as_local(sm, "android-asset");
		webkit_security_manager_register_uri_scheme_as_local(sm, "content");
	}
	g_object_set_data_full(G_OBJECT(wv), "tl-jobj", (*env)->NewWeakGlobalRef(env, this), NULL);
	g_signal_connect(wv, "load-changed", G_CALLBACK(on_load_changed), NULL);
	g_signal_connect(wv, "load-failed", G_CALLBACK(on_load_failed), NULL);
	g_signal_connect(wv, "load-failed-with-tls-errors", G_CALLBACK(on_load_failed_tls), NULL);
	g_signal_connect(wv, "decide-policy", G_CALLBACK(on_decide_policy), NULL);
	g_signal_connect(wv, "create", G_CALLBACK(on_create), NULL);
	g_signal_connect(wv, "close", G_CALLBACK(on_close), NULL);
	g_signal_connect(wv, "notify::title", G_CALLBACK(on_notify_title), NULL);
	g_signal_connect(wv, "notify::uri", G_CALLBACK(on_notify_uri), NULL);
	g_signal_connect(wv, "notify::estimated-load-progress", G_CALLBACK(on_notify_progress), NULL);
	g_signal_connect(wv, "notify::is-web-process-responsive", G_CALLBACK(on_notify_responsive), NULL);
	g_signal_connect(wv, "web-process-terminated", G_CALLBACK(on_web_process_terminated), NULL);
	g_signal_connect(wv, "script-dialog", G_CALLBACK(on_script_dialog), NULL);
	g_signal_connect(wv, "permission-request", G_CALLBACK(on_permission_request), NULL);
	g_signal_connect(wv, "run-file-chooser", G_CALLBACK(on_run_file_chooser), NULL);
	g_signal_connect(wv, "authenticate", G_CALLBACK(on_authenticate), NULL);
	gtk_widget_set_hexpand(GTK_WIDGET(wv), TRUE);
	gtk_widget_set_vexpand(GTK_WIDGET(wv), TRUE);
}

/* related: the engine of the WebView that opened this one (window.open), or 0. */
JNIEXPORT jlong JNICALL Java_android_webkit_WebView_nativeCreateEngine(JNIEnv *env, jobject this, jlong holder_ptr, jlong related_ptr)
{
	tl_wv_ids(env);
	WebKitWebView *wv;
	if (related_ptr)
		wv = g_object_new(WEBKIT_TYPE_WEB_VIEW, "related-view", WEBKIT_WEB_VIEW(_PTR(related_ptr)), NULL);
	else
		wv = g_object_new(WEBKIT_TYPE_WEB_VIEW, "network-session", tl_session, NULL);
	tl_engine_setup(env, this, wv);
	gtk_overlay_set_child(GTK_OVERLAY(_PTR(holder_ptr)), GTK_WIDGET(wv));
	return _INTPTR(wv);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeDestroy(JNIEnv *env, jclass klass, jlong holder_ptr, jlong engine_ptr)
{
	WebKitWebView *wv = _PTR(engine_ptr);
	jobject ref = g_object_get_data(G_OBJECT(wv), "tl-jobj");
	static const GCallback handlers[] = {
		G_CALLBACK(on_load_changed), G_CALLBACK(on_load_failed), G_CALLBACK(on_load_failed_tls), G_CALLBACK(on_decide_policy),
		G_CALLBACK(on_create), G_CALLBACK(on_close), G_CALLBACK(on_notify_title), G_CALLBACK(on_notify_uri),
		G_CALLBACK(on_notify_progress), G_CALLBACK(on_notify_responsive), G_CALLBACK(on_web_process_terminated),
		G_CALLBACK(on_script_dialog), G_CALLBACK(on_permission_request), G_CALLBACK(on_run_file_chooser),
		G_CALLBACK(on_authenticate),
	};
	for (size_t i = 0; i < ARRAY_SIZE(handlers); i++)
		g_signal_handlers_disconnect_matched(wv, G_SIGNAL_MATCH_FUNC, 0, 0, NULL, (gpointer)handlers[i], NULL);
	g_object_set_data(G_OBJECT(wv), "tl-jobj", NULL);
	if (ref)
		(*env)->DeleteWeakGlobalRef(env, ref);
	webkit_web_view_stop_loading(wv);
	gtk_overlay_set_child(GTK_OVERLAY(_PTR(holder_ptr)), NULL);
}

/* ---- loading ---- */

/* headers: name/value pairs, may be null */
JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeLoadUri(JNIEnv *env, jclass klass, jlong engine_ptr, jstring juri, jobjectArray jheaders)
{
	char *uri = tl_utf8(env, juri);
	WebKitURIRequest *req = webkit_uri_request_new(uri);
	char **h = tl_strv(env, jheaders);
	SoupMessageHeaders *headers = webkit_uri_request_get_http_headers(req);
	for (int i = 0; h && headers && h[i] && h[i + 1]; i += 2)
		soup_message_headers_replace(headers, h[i], h[i + 1]);
	webkit_web_view_load_request(WEBKIT_WEB_VIEW(_PTR(engine_ptr)), req);
	g_object_unref(req);
	g_strfreev(h);
	g_free(uri);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeLoadData(JNIEnv *env, jclass klass, jlong engine_ptr, jbyteArray data, jstring mime, jstring encoding, jstring base_uri)
{
	jsize len = (*env)->GetArrayLength(env, data);
	gpointer buf = g_malloc(len ? len : 1);
	(*env)->GetByteArrayRegion(env, data, 0, len, buf);
	GBytes *bytes = g_bytes_new_take(buf, len);
	char *m = tl_utf8(env, mime), *e = tl_utf8(env, encoding), *b = tl_utf8(env, base_uri);
	webkit_web_view_load_bytes(WEBKIT_WEB_VIEW(_PTR(engine_ptr)), bytes, m, e, b);
	g_bytes_unref(bytes);
	g_free(m);
	g_free(e);
	g_free(b);
}

/* loadDataWithBaseURL with a history URL: the page is listed in history (and getUrl) under content_uri */
JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeLoadAlternateHtml(JNIEnv *env, jclass klass, jlong engine_ptr, jstring html, jstring content_uri, jstring base_uri)
{
	char *h = tl_utf8(env, html), *c = tl_utf8(env, content_uri), *b = tl_utf8(env, base_uri);
	webkit_web_view_load_alternate_html(WEBKIT_WEB_VIEW(_PTR(engine_ptr)), h, c, b);
	g_free(h);
	g_free(c);
	g_free(b);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeStopLoading(JNIEnv *env, jclass klass, jlong engine_ptr)
{
	webkit_web_view_stop_loading(WEBKIT_WEB_VIEW(_PTR(engine_ptr)));
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeReload(JNIEnv *env, jclass klass, jlong engine_ptr, jboolean bypass_cache)
{
	if (bypass_cache)
		webkit_web_view_reload_bypass_cache(WEBKIT_WEB_VIEW(_PTR(engine_ptr)));
	else
		webkit_web_view_reload(WEBKIT_WEB_VIEW(_PTR(engine_ptr)));
}

JNIEXPORT jstring JNICALL Java_android_webkit_WebView_nativeGetUri(JNIEnv *env, jclass klass, jlong engine_ptr)
{
	return tl_jstr(env, webkit_web_view_get_uri(WEBKIT_WEB_VIEW(_PTR(engine_ptr))));
}

JNIEXPORT jstring JNICALL Java_android_webkit_WebView_nativeGetTitle(JNIEnv *env, jclass klass, jlong engine_ptr)
{
	return tl_jstr(env, webkit_web_view_get_title(WEBKIT_WEB_VIEW(_PTR(engine_ptr))));
}

JNIEXPORT jint JNICALL Java_android_webkit_WebView_nativeGetProgress(JNIEnv *env, jclass klass, jlong engine_ptr)
{
	return (jint)(webkit_web_view_get_estimated_load_progress(WEBKIT_WEB_VIEW(_PTR(engine_ptr))) * 100.0 + 0.5);
}

/* ---- back/forward list ---- */

/* returns {currentIndex, item0, item1, ...} oldest first; the item pointers stay valid while the list
 * holds them (nativeItemRef/nativeItemUnref keep one alive across navigations) */
JNIEXPORT jlongArray JNICALL Java_android_webkit_WebView_nativeHistoryItems(JNIEnv *env, jclass klass, jlong engine_ptr)
{
	WebKitBackForwardList *list = webkit_web_view_get_back_forward_list(WEBKIT_WEB_VIEW(_PTR(engine_ptr)));
	GArray *items = g_array_new(FALSE, FALSE, sizeof(jlong));
	int back = 0;
	for (int i = -1;; i--) {
		if (!webkit_back_forward_list_get_nth_item(list, i))
			break;
		back++;
	}
	jlong current = -1;
	for (int i = -back;; i++) {
		WebKitBackForwardListItem *item = webkit_back_forward_list_get_nth_item(list, i);
		if (!item)
			break;
		if (i == 0)
			current = items->len;
		jlong p = _INTPTR(item);
		g_array_append_val(items, p);
	}
	jlongArray arr = (*env)->NewLongArray(env, items->len + 1);
	(*env)->SetLongArrayRegion(env, arr, 0, 1, &current);
	if (items->len)
		(*env)->SetLongArrayRegion(env, arr, 1, items->len, (jlong *)items->data);
	g_array_free(items, TRUE);
	return arr;
}

/* what: 0 uri, 1 original uri, 2 title */
JNIEXPORT jstring JNICALL Java_android_webkit_WebView_nativeItemString(JNIEnv *env, jclass klass, jlong item_ptr, jint what)
{
	WebKitBackForwardListItem *item = _PTR(item_ptr);
	const char *s = what == 0 ? webkit_back_forward_list_item_get_uri(item) : what == 1 ? webkit_back_forward_list_item_get_original_uri(item) : webkit_back_forward_list_item_get_title(item);
	return tl_jstr(env, s);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeItemRef(JNIEnv *env, jclass klass, jlong item_ptr, jboolean ref)
{
	if (ref)
		g_object_ref(_PTR(item_ptr));
	else
		g_object_unref(_PTR(item_ptr));
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeGoToItem(JNIEnv *env, jclass klass, jlong engine_ptr, jlong item_ptr)
{
	webkit_web_view_go_to_back_forward_list_item(WEBKIT_WEB_VIEW(_PTR(engine_ptr)), _PTR(item_ptr));
}

JNIEXPORT jbyteArray JNICALL Java_android_webkit_WebView_nativeSaveState(JNIEnv *env, jclass klass, jlong engine_ptr)
{
	WebKitWebViewSessionState *state = webkit_web_view_get_session_state(WEBKIT_WEB_VIEW(_PTR(engine_ptr)));
	GBytes *bytes = webkit_web_view_session_state_serialize(state);
	gsize len;
	const void *data = g_bytes_get_data(bytes, &len);
	jbyteArray arr = (*env)->NewByteArray(env, len);
	(*env)->SetByteArrayRegion(env, arr, 0, len, data);
	g_bytes_unref(bytes);
	webkit_web_view_session_state_unref(state);
	return arr;
}

/* restores the list and loads its current item; false if the data is not a session state */
JNIEXPORT jboolean JNICALL Java_android_webkit_WebView_nativeRestoreState(JNIEnv *env, jclass klass, jlong engine_ptr, jbyteArray data)
{
	WebKitWebView *wv = WEBKIT_WEB_VIEW(_PTR(engine_ptr));
	jsize len = (*env)->GetArrayLength(env, data);
	gpointer buf = g_malloc(len ? len : 1);
	(*env)->GetByteArrayRegion(env, data, 0, len, buf);
	GBytes *bytes = g_bytes_new_take(buf, len);
	WebKitWebViewSessionState *state = webkit_web_view_session_state_new(bytes);
	g_bytes_unref(bytes);
	if (!state)
		return JNI_FALSE;
	webkit_web_view_restore_session_state(wv, state);
	webkit_web_view_session_state_unref(state);
	WebKitBackForwardListItem *cur = webkit_back_forward_list_get_current_item(webkit_web_view_get_back_forward_list(wv));
	if (cur)
		webkit_web_view_go_to_back_forward_list_item(wv, cur);
	return JNI_TRUE;
}

/* ---- JavaScript ---- */

struct tl_eval {
	jobject jobj; /* weak */
	int id;
	gboolean async_fn;
};

static void tl_eval_done(GObject *source, GAsyncResult *res, gpointer user_data)
{
	struct tl_eval *ev = user_data;
	JNIEnv *env = get_jni_env();
	GError *error = NULL;
	JSCValue *value = ev->async_fn ? webkit_web_view_call_async_javascript_function_finish(WEBKIT_WEB_VIEW(source), res, &error)
	                               : webkit_web_view_evaluate_javascript_finish(WEBKIT_WEB_VIEW(source), res, &error);
	char *json = NULL;
	if (value) {
		/* Android hands the result to the callback JSON-encoded; undefined and values JSON cannot
		 * represent (functions, symbols) become "null", like Chromium's WebView */
		if (!jsc_value_is_undefined(value))
			json = jsc_value_to_json(value, 0);
		g_object_unref(value);
	}
	if (error)
		g_error_free(error);
	if (ev->id >= 0 && !(*env)->IsSameObject(env, ev->jobj, NULL)) {
		(*env)->PushLocalFrame(env, 8);
		(*env)->CallVoidMethod(env, ev->jobj, tl_m.evalResult, (jint)ev->id, tl_jstr(env, json ? json : "null"));
		tl_check(env);
		(*env)->PopLocalFrame(env, NULL);
	}
	g_free(json);
	(*env)->DeleteWeakGlobalRef(env, ev->jobj);
	g_free(ev);
}

/* async_fn: script is the body of an async function (awaited), else a plain script. id < 0: no result */
JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeEvaluateJavascript(JNIEnv *env, jobject this, jlong engine_ptr, jstring script, jint id, jboolean async_fn)
{
	struct tl_eval *ev = g_new0(struct tl_eval, 1);
	ev->jobj = (*env)->NewWeakGlobalRef(env, this);
	ev->id = id;
	ev->async_fn = async_fn;
	char *s = tl_utf8(env, script);
	if (async_fn)
		webkit_web_view_call_async_javascript_function(WEBKIT_WEB_VIEW(_PTR(engine_ptr)), s, -1, NULL, NULL, NULL, NULL, tl_eval_done, ev);
	else
		webkit_web_view_evaluate_javascript(WEBKIT_WEB_VIEW(_PTR(engine_ptr)), s, -1, NULL, NULL, NULL, tl_eval_done, ev);
	g_free(s);
}

/* the JavaScript interface bootstrap (WebView.addJavascriptInterface); applies from the next page load,
 * as on Android */
JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeSetUserScript(JNIEnv *env, jclass klass, jlong engine_ptr, jstring script)
{
	WebKitUserContentManager *ucm = webkit_web_view_get_user_content_manager(WEBKIT_WEB_VIEW(_PTR(engine_ptr)));
	webkit_user_content_manager_remove_all_scripts(ucm);
	if (!script)
		return;
	char *s = tl_utf8(env, script);
	WebKitUserScript *us = webkit_user_script_new(s, WEBKIT_USER_CONTENT_INJECT_ALL_FRAMES, WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START, NULL, NULL);
	webkit_user_content_manager_add_script(ucm, us);
	webkit_user_script_unref(us);
	g_free(s);
}

/* ---- settings, zoom, colours, process ---- */

static GParamSpec *tl_setting(WebKitWebView *wv, const char *name, GType type)
{
	WebKitSettings *settings = webkit_web_view_get_settings(wv);
	GParamSpec *p = g_object_class_find_property(G_OBJECT_GET_CLASS(settings), name);
	return (p && G_TYPE_FUNDAMENTAL(p->value_type) == type) ? p : NULL;
}

/* false if this WebKitGTK has no such setting */
JNIEXPORT jboolean JNICALL Java_android_webkit_WebView_nativeSetBoolSetting(JNIEnv *env, jclass klass, jlong engine_ptr, jstring jname, jboolean value)
{
	WebKitWebView *wv = _PTR(engine_ptr);
	const char *name = _CSTRING(jname);
	gboolean ok = tl_setting(wv, name, G_TYPE_BOOLEAN) != NULL;
	if (ok)
		g_object_set(webkit_web_view_get_settings(wv), name, (gboolean)value, NULL);
	(*env)->ReleaseStringUTFChars(env, jname, name);
	return ok;
}

JNIEXPORT jboolean JNICALL Java_android_webkit_WebView_nativeSetIntSetting(JNIEnv *env, jclass klass, jlong engine_ptr, jstring jname, jint value)
{
	WebKitWebView *wv = _PTR(engine_ptr);
	const char *name = _CSTRING(jname);
	GParamSpec *p = tl_setting(wv, name, G_TYPE_UINT);
	if (!p)
		p = tl_setting(wv, name, G_TYPE_INT);
	if (!p)
		p = tl_setting(wv, name, G_TYPE_ENUM);
	if (p) {
		GValue v = G_VALUE_INIT;
		g_value_init(&v, p->value_type);
		if (G_TYPE_FUNDAMENTAL(p->value_type) == G_TYPE_UINT)
			g_value_set_uint(&v, value);
		else if (G_TYPE_FUNDAMENTAL(p->value_type) == G_TYPE_INT)
			g_value_set_int(&v, value);
		else
			g_value_set_enum(&v, value);
		g_object_set_property(G_OBJECT(webkit_web_view_get_settings(wv)), name, &v);
		g_value_unset(&v);
	}
	(*env)->ReleaseStringUTFChars(env, jname, name);
	return p != NULL;
}

JNIEXPORT jboolean JNICALL Java_android_webkit_WebView_nativeSetStringSetting(JNIEnv *env, jclass klass, jlong engine_ptr, jstring jname, jstring value)
{
	WebKitWebView *wv = _PTR(engine_ptr);
	const char *name = _CSTRING(jname);
	gboolean ok = tl_setting(wv, name, G_TYPE_STRING) != NULL;
	if (ok) {
		char *v = tl_utf8(env, value);
		g_object_set(webkit_web_view_get_settings(wv), name, v, NULL);
		g_free(v);
	}
	(*env)->ReleaseStringUTFChars(env, jname, name);
	return ok;
}

JNIEXPORT jstring JNICALL Java_android_webkit_WebView_nativeGetUserAgent(JNIEnv *env, jclass klass, jlong engine_ptr)
{
	return tl_jstr(env, webkit_settings_get_user_agent(webkit_web_view_get_settings(WEBKIT_WEB_VIEW(_PTR(engine_ptr)))));
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeSetZoom(JNIEnv *env, jclass klass, jlong engine_ptr, jdouble zoom)
{
	webkit_web_view_set_zoom_level(WEBKIT_WEB_VIEW(_PTR(engine_ptr)), zoom);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeSetBackgroundColor(JNIEnv *env, jclass klass, jlong engine_ptr, jint argb)
{
	GdkRGBA rgba = {
		.red = ((argb >> 16) & 0xff) / 255.0,
		.green = ((argb >> 8) & 0xff) / 255.0,
		.blue = (argb & 0xff) / 255.0,
		.alpha = ((guint32)argb >> 24) / 255.0,
	};
	webkit_web_view_set_background_color(WEBKIT_WEB_VIEW(_PTR(engine_ptr)), &rgba);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeTerminateWebProcess(JNIEnv *env, jclass klass, jlong engine_ptr)
{
	webkit_web_view_terminate_web_process(WEBKIT_WEB_VIEW(_PTR(engine_ptr)));
}

JNIEXPORT jboolean JNICALL Java_android_webkit_WebView_nativeIsResponsive(JNIEnv *env, jclass klass, jlong engine_ptr)
{
	return webkit_web_view_get_is_web_process_responsive(WEBKIT_WEB_VIEW(_PTR(engine_ptr)));
}

/* WebKitGTK's version, for WebView.getCurrentWebViewPackage-style queries and the user agent */
JNIEXPORT jstring JNICALL Java_android_webkit_WebView_nativeEngineVersion(JNIEnv *env, jclass klass)
{
	char buf[64];
	snprintf(buf, sizeof(buf), "%u.%u.%u", webkit_get_major_version(), webkit_get_minor_version(), webkit_get_micro_version());
	return _JSTRING(buf);
}

/* ---- website data (WebView.clearCache, WebStorage, CookieManager) ---- */

/* kind: 0 the JavaScript storage APIs (WebStorage.deleteAllData), 1 the memory cache, 2 memory and
 * disk cache (WebView.clearCache); id: CookieManager completion id or -1 */
static jclass tl_cookie_class;
static jmethodID tl_cookie_done;

static void tl_cookie_ids(JNIEnv *env)
{
	if (tl_cookie_class)
		return;
	tl_cookie_class = _REF((*env)->FindClass(env, "android/webkit/CookieManager"));
	tl_cookie_done = _STATIC_METHOD(tl_cookie_class, "internalDone", "(IZ)V");
}

static void tl_report_done(int id, gboolean ok)
{
	if (id < 0)
		return;
	JNIEnv *env = get_jni_env();
	(*env)->CallStaticVoidMethod(env, tl_cookie_class, tl_cookie_done, (jint)id, (jboolean)ok);
	tl_check(env);
}

static void tl_clear_done(GObject *source, GAsyncResult *res, gpointer user_data)
{
	int id = GPOINTER_TO_INT(user_data);
	gboolean ok = webkit_website_data_manager_clear_finish(WEBKIT_WEBSITE_DATA_MANAGER(source), res, NULL);
	tl_report_done(id, ok);
}

JNIEXPORT void JNICALL Java_android_webkit_WebView_nativeClearWebsiteData(JNIEnv *env, jclass klass, jint kind, jint id)
{
	tl_cookie_ids(env);
	WebKitWebsiteDataTypes types;
	if (kind == 0)
		types = WEBKIT_WEBSITE_DATA_SESSION_STORAGE | WEBKIT_WEBSITE_DATA_LOCAL_STORAGE | WEBKIT_WEBSITE_DATA_INDEXEDDB_DATABASES |
		        WEBKIT_WEBSITE_DATA_SERVICE_WORKER_REGISTRATIONS | WEBKIT_WEBSITE_DATA_DOM_CACHE;
	else if (kind == 1)
		types = WEBKIT_WEBSITE_DATA_MEMORY_CACHE;
	else
		types = WEBKIT_WEBSITE_DATA_MEMORY_CACHE | WEBKIT_WEBSITE_DATA_DISK_CACHE;
	WebKitWebsiteDataManager *dm = webkit_network_session_get_website_data_manager(tl_session);
	webkit_website_data_manager_clear(dm, types, 0, NULL, tl_clear_done, GINT_TO_POINTER(id));
}

/* ---- cookies (android.webkit.CookieManager natives) ---- */

JNIEXPORT void JNICALL Java_android_webkit_CookieManager_nativeSetAcceptPolicy(JNIEnv *env, jclass klass, jint policy)
{
	webkit_cookie_manager_set_accept_policy(webkit_network_session_get_cookie_manager(tl_session), policy);
}

static void tl_add_cookie_done(GObject *source, GAsyncResult *res, gpointer user_data)
{
	gboolean ok = webkit_cookie_manager_add_cookie_finish(WEBKIT_COOKIE_MANAGER(source), res, NULL);
	tl_report_done(GPOINTER_TO_INT(user_data), ok);
}

/* Set-Cookie syntax, resolved against url as a response from url would be */
JNIEXPORT void JNICALL Java_android_webkit_CookieManager_nativeSetCookie(JNIEnv *env, jclass klass, jstring jurl, jstring jvalue, jint id)
{
	tl_cookie_ids(env);
	char *url = tl_utf8(env, jurl), *value = tl_utf8(env, jvalue);
	GUri *origin = g_uri_parse(url, SOUP_HTTP_URI_FLAGS, NULL);
	SoupCookie *cookie = origin ? soup_cookie_parse(value, origin) : NULL;
	if (cookie) {
		webkit_cookie_manager_add_cookie(webkit_network_session_get_cookie_manager(tl_session), cookie, NULL, tl_add_cookie_done, GINT_TO_POINTER(id));
		soup_cookie_free(cookie);
	} else {
		tl_report_done(id, FALSE);
	}
	if (origin)
		g_uri_unref(origin);
	g_free(url);
	g_free(value);
}

struct tl_cookie_query {
	gboolean done;
	GList *cookies;
};

static void tl_get_cookies_done(GObject *source, GAsyncResult *res, gpointer user_data)
{
	struct tl_cookie_query *q = user_data;
	q->cookies = webkit_cookie_manager_get_cookies_finish(WEBKIT_COOKIE_MANAGER(source), res, NULL);
	q->done = TRUE;
}

static void tl_get_all_cookies_done(GObject *source, GAsyncResult *res, gpointer user_data)
{
	struct tl_cookie_query *q = user_data;
	q->cookies = webkit_cookie_manager_get_all_cookies_finish(WEBKIT_COOKIE_MANAGER(source), res, NULL);
	q->done = TRUE;
}

/* CookieManager.getCookie is synchronous on Android; the network process answers asynchronously, so
 * iterate the main context until it has (the Java side only calls this on the main thread, and posts
 * calls from other threads there). url NULL: all cookies (for hasCookies/removeSessionCookies). */
static GList *tl_query_cookies(JNIEnv *env, jstring jurl)
{
	struct tl_cookie_query q = { 0 };
	WebKitCookieManager *cm = webkit_network_session_get_cookie_manager(tl_session);
	if (jurl) {
		char *url = tl_utf8(env, jurl);
		webkit_cookie_manager_get_cookies(cm, url, NULL, tl_get_cookies_done, &q);
		g_free(url);
	} else {
		webkit_cookie_manager_get_all_cookies(cm, NULL, tl_get_all_cookies_done, &q);
	}
	while (!q.done)
		g_main_context_iteration(NULL, TRUE);
	return q.cookies;
}

JNIEXPORT jstring JNICALL Java_android_webkit_CookieManager_nativeGetCookie(JNIEnv *env, jclass klass, jstring jurl)
{
	GList *cookies = tl_query_cookies(env, jurl);
	if (!cookies)
		return NULL;
	GString *s = g_string_new(NULL);
	for (GList *l = cookies; l; l = l->next) {
		SoupCookie *c = l->data;
		if (s->len)
			g_string_append(s, "; ");
		g_string_append_printf(s, "%s=%s", soup_cookie_get_name(c), soup_cookie_get_value(c));
	}
	g_list_free_full(cookies, (GDestroyNotify)soup_cookie_free);
	jstring ret = tl_jstr(env, s->str);
	g_string_free(s, TRUE);
	return ret;
}

JNIEXPORT jboolean JNICALL Java_android_webkit_CookieManager_nativeHasCookies(JNIEnv *env, jclass klass)
{
	GList *cookies = tl_query_cookies(env, NULL);
	jboolean has = cookies != NULL;
	g_list_free_full(cookies, (GDestroyNotify)soup_cookie_free);
	return has;
}

static void tl_replace_done(GObject *source, GAsyncResult *res, gpointer user_data)
{
	gboolean ok = webkit_cookie_manager_replace_cookies_finish(WEBKIT_COOKIE_MANAGER(source), res, NULL);
	tl_report_done(GPOINTER_TO_INT(user_data), ok);
}

/* session_only: drop the cookies without an expiry date (removeSessionCookies); else all of them */
JNIEXPORT void JNICALL Java_android_webkit_CookieManager_nativeRemoveCookies(JNIEnv *env, jclass klass, jboolean session_only, jint id)
{
	tl_cookie_ids(env);
	if (!session_only) {
		WebKitWebsiteDataManager *dm = webkit_network_session_get_website_data_manager(tl_session);
		webkit_website_data_manager_clear(dm, WEBKIT_WEBSITE_DATA_COOKIES, 0, NULL, tl_clear_done, GINT_TO_POINTER(id));
		return;
	}
	GList *cookies = tl_query_cookies(env, NULL);
	GList *keep = NULL;
	for (GList *l = cookies; l; l = l->next) {
		SoupCookie *c = l->data;
		if (soup_cookie_get_expires(c))
			keep = g_list_prepend(keep, c);
	}
	webkit_cookie_manager_replace_cookies(webkit_network_session_get_cookie_manager(tl_session), keep, NULL, tl_replace_done, GINT_TO_POINTER(id));
	g_list_free(keep);
	g_list_free_full(cookies, (GDestroyNotify)soup_cookie_free);
}
