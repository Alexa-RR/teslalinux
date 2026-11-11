/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.webkit;

import android.graphics.Bitmap;
import android.os.Message;
import android.view.KeyEvent;

/* teslalinux: AOSP's WebViewClient minus onReceivedSslError and onReceivedClientCertRequest, whose
 * parameter types (android.net.http.SslError, ClientCertRequest with java.security key material) are
 * not in this framework: TLS failures reach the app as onReceivedError(ERROR_FAILED_SSL_HANDSHAKE),
 * which is what AOSP does after the default onReceivedSslError cancels the load. */
public class WebViewClient {

	/**
	 * Give the host application a chance to take control when a URL is about to be loaded in the
	 * current WebView. If a WebViewClient is not provided, by default WebView will ask Activity
	 * Manager to choose the proper handler for the URL. If a WebViewClient is provided, returning
	 * {@code true} causes the current WebView to abort loading the URL, while returning
	 * {@code false} causes the WebView to continue loading the URL as usual.
	 *
	 * @deprecated Use {@link #shouldOverrideUrlLoading(WebView, WebResourceRequest)
	 *             shouldOverrideUrlLoading(WebView, WebResourceRequest)} instead.
	 */
	@Deprecated
	public boolean shouldOverrideUrlLoading(WebView view, String url) {
		return false;
	}

	/**
	 * Give the host application a chance to take control when a URL is about to be loaded in the
	 * current WebView.
	 *
	 * <p class="note"><b>Note:</b> This method is not called for POST requests.
	 *
	 * <p class="note"><b>Note:</b> This method may be called for subframes and with non-HTTP(S)
	 * schemes; calling {@link WebView#loadUrl(String)} with such a URL will fail.
	 */
	public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
		return shouldOverrideUrlLoading(view, request.getUrl().toString());
	}

	/**
	 * Notify the host application that a page has started loading. This method
	 * is called once for each main frame load so a page with iframes or
	 * framesets will call onPageStarted one time for the main frame.
	 */
	public void onPageStarted(WebView view, String url, Bitmap favicon) {
	}

	/**
	 * Notify the host application that a page has finished loading. This method
	 * is called only for main frame.
	 */
	public void onPageFinished(WebView view, String url) {
	}

	/**
	 * Notify the host application that the WebView will load the resource
	 * specified by the given url.
	 */
	public void onLoadResource(WebView view, String url) {
	}

	/**
	 * Notify the host application that {@link android.webkit.WebView} content left over from
	 * previous page navigations will no longer be drawn.
	 */
	public void onPageCommitVisible(WebView view, String url) {
	}

	/**
	 * Notify the host application of a resource request and allow the
	 * application to return the data.
	 *
	 * @deprecated Use {@link #shouldInterceptRequest(WebView, WebResourceRequest)
	 *             shouldInterceptRequest(WebView, WebResourceRequest)} instead.
	 */
	@Deprecated
	public WebResourceResponse shouldInterceptRequest(WebView view,
	                                                  String url) {
		return null;
	}

	/**
	 * Notify the host application of a resource request and allow the
	 * application to return the data.  If the return value is {@code null}, the WebView
	 * will continue to load the resource as usual.  Otherwise, the
	 * return response and data will be used.
	 */
	public WebResourceResponse shouldInterceptRequest(WebView view,
	                                                  WebResourceRequest request) {
		return shouldInterceptRequest(view, request.getUrl().toString());
	}

	/**
	 * Notify the host application that there have been an excessive number of
	 * HTTP redirects. As the host application if it would like to continue
	 * trying to load the resource. The default behavior is to send the cancel
	 * message.
	 *
	 * @deprecated This method is no longer called. When the WebView encounters
	 *             a redirect loop, it will cancel the load.
	 */
	@Deprecated
	public void onTooManyRedirects(WebView view, Message cancelMsg,
	                               Message continueMsg) {
		cancelMsg.sendToTarget();
	}

	// These ints must match up to the hidden values in EventHandler.
	/** Generic error */
	public static final int ERROR_UNKNOWN = -1;
	/** Server or proxy hostname lookup failed */
	public static final int ERROR_HOST_LOOKUP = -2;
	/** Unsupported authentication scheme (not basic or digest) */
	public static final int ERROR_UNSUPPORTED_AUTH_SCHEME = -3;
	/** User authentication failed on server */
	public static final int ERROR_AUTHENTICATION = -4;
	/** User authentication failed on proxy */
	public static final int ERROR_PROXY_AUTHENTICATION = -5;
	/** Failed to connect to the server */
	public static final int ERROR_CONNECT = -6;
	/** Failed to read or write to the server */
	public static final int ERROR_IO = -7;
	/** Connection timed out */
	public static final int ERROR_TIMEOUT = -8;
	/** Too many redirects */
	public static final int ERROR_REDIRECT_LOOP = -9;
	/** Unsupported URI scheme */
	public static final int ERROR_UNSUPPORTED_SCHEME = -10;
	/** Failed to perform SSL handshake */
	public static final int ERROR_FAILED_SSL_HANDSHAKE = -11;
	/** Malformed URL */
	public static final int ERROR_BAD_URL = -12;
	/** Generic file error */
	public static final int ERROR_FILE = -13;
	/** File not found */
	public static final int ERROR_FILE_NOT_FOUND = -14;
	/** Too many requests during this load */
	public static final int ERROR_TOO_MANY_REQUESTS = -15;
	/** Resource load was canceled by Safe Browsing */
	public static final int ERROR_UNSAFE_RESOURCE = -16;

	/** The user's safe browsing hit was a malware hit. */
	public static final int SAFE_BROWSING_THREAT_UNKNOWN = 0;
	public static final int SAFE_BROWSING_THREAT_MALWARE = 1;
	public static final int SAFE_BROWSING_THREAT_PHISHING = 2;
	public static final int SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE = 3;
	public static final int SAFE_BROWSING_THREAT_BILLING = 4;

	/**
	 * Report an error to the host application. These errors are unrecoverable
	 * (i.e. the main resource is unavailable). The {@code errorCode} parameter
	 * corresponds to one of the {@code ERROR_*} constants.
	 *
	 * @deprecated Use {@link #onReceivedError(WebView, WebResourceRequest, WebResourceError)
	 *             onReceivedError(WebView, WebResourceRequest, WebResourceError)} instead.
	 */
	@Deprecated
	public void onReceivedError(WebView view, int errorCode,
	                            String description, String failingUrl) {
	}

	/**
	 * Report web resource loading error to the host application. These errors usually indicate
	 * inability to connect to the server. Note that unlike the deprecated version of the callback,
	 * the new version will be called for any resource (iframe, image, etc.), not just for the main
	 * page. Thus, it is recommended to perform minimum required work in this callback.
	 */
	public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
		if (request.isForMainFrame()) {
			onReceivedError(view,
			                error.getErrorCode(), error.getDescription().toString(),
			                request.getUrl().toString());
		}
	}

	/**
	 * Notify the host application that an HTTP error has been received from the server while
	 * loading a resource.  HTTP errors have status codes &gt;= 400.  This callback will be called
	 * for any resource (iframe, image, etc.), not just for the main page.
	 */
	public void onReceivedHttpError(
	    WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
	}

	/**
	 * As the host application if the browser should resend data as the
	 * requested page was a result of a POST. The default is to not resend the
	 * data.
	 */
	public void onFormResubmission(WebView view, Message dontResend,
	                               Message resend) {
		dontResend.sendToTarget();
	}

	/**
	 * Notify the host application to update its visited links database.
	 */
	public void doUpdateVisitedHistory(WebView view, String url,
	                                   boolean isReload) {
	}

	/**
	 * Notifies the host application that the WebView received an HTTP
	 * authentication request. The host application can use the supplied
	 * {@link HttpAuthHandler} to set the WebView's response to the request.
	 * The default behavior is to cancel the request.
	 */
	public void onReceivedHttpAuthRequest(WebView view,
	                                      HttpAuthHandler handler, String host, String realm) {
		handler.cancel();
	}

	/**
	 * Give the host application a chance to handle the key event synchronously.
	 * e.g. menu shortcut key events need to be filtered this way. If return
	 * true, WebView will not handle the key event. If return {@code false}, WebView
	 * will always handle the key event, so none of the super in the view chain
	 * will see the key event. The default behavior returns {@code false}.
	 */
	public boolean shouldOverrideKeyEvent(WebView view, KeyEvent event) {
		return false;
	}

	/**
	 * Notify the host application that a key was not handled by the WebView.
	 * Except system keys, WebView always consumes the keys in the normal flow
	 * or if {@link #shouldOverrideKeyEvent} returns {@code true}. This is called asynchronously
	 * from where the key is dispatched. It gives the host application a chance
	 * to handle the unhandled key events.
	 */
	public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
		onUnhandledInputEventInternal(view, event);
	}

	private void onUnhandledInputEventInternal(WebView view, android.view.InputEvent event) {
		// AOSP hands it to ViewRootImpl.dispatchUnhandledInputEvent, which ends in the window
		// callback's key handling: the Activity's onKeyDown/onKeyUp, as for a key no view consumed
		if (!(event instanceof KeyEvent))
			return;
		android.content.Context ctx = view.getContext();
		while (ctx instanceof android.content.ContextWrapper && !(ctx instanceof android.app.Activity))
			ctx = ((android.content.ContextWrapper)ctx).getBaseContext();
		if (!(ctx instanceof android.app.Activity))
			return;
		android.app.Activity activity = (android.app.Activity)ctx;
		KeyEvent key = (KeyEvent)event;
		if (key.getAction() == KeyEvent.ACTION_DOWN)
			activity.onKeyDown(key.getKeyCode(), key);
		else if (key.getAction() == KeyEvent.ACTION_UP)
			activity.onKeyUp(key.getKeyCode(), key);
	}

	/**
	 * Notify the host application that the scale applied to the WebView has
	 * changed.
	 */
	public void onScaleChanged(WebView view, float oldScale, float newScale) {
	}

	/**
	 * Notify the host application that a request to automatically log in the
	 * user has been processed.
	 */
	public void onReceivedLoginRequest(WebView view, String realm,
	                                   String account, String args) {
	}

	/**
	 * Notify host application that the given WebView's render process has exited.
	 *
	 * Multiple WebView instances may be associated with a single render process;
	 * onRenderProcessGone will be called for each WebView that was affected.
	 * The application's implementation of this callback should only attempt to
	 * clean up the specific WebView given as a parameter, and should not assume
	 * that other WebView instances are affected.
	 *
	 * The given WebView can't be used, and should be removed from the view hierarchy,
	 * all references to it should be cleaned up, e.g any references in the Activity
	 * or other classes saved using {@link android.view.View#findViewById} and similar calls, etc.
	 *
	 * @return {@code true} if the host application handled the situation that process has
	 *         exited, otherwise, application will crash if render process crashed,
	 *         or be killed if render process was killed by the system.
	 */
	public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
		return false;
	}

	/**
	 * Notify the host application that a loading URL has been flagged by Safe Browsing.
	 *
	 * The application must invoke the callback to indicate the preferred response. The default
	 * behavior is to show an interstitial to the user, with the reporting checkbox visible.
	 */
	public void onSafeBrowsingHit(WebView view, WebResourceRequest request,
	                              int threatType, SafeBrowsingResponse callback) {
		callback.showInterstitial(/* allowReporting */ true);
	}
}
