/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Base64;
import android.util.Log;
import android.util.SparseArray;
import android.view.ViewGroup;
import android.widget.EditText;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A View that displays web pages.
 *
 * <p>teslalinux: the engine is WebKitGTK 6.0 (native side: api-impl-jni/widgets/android_webkit_WebView.c,
 * tl_webkit). The WebKitWebView is created the first time the page is used, so a WebView that is only
 * constructed (apps do that to warm up or to read the user agent) costs no web process. WebKit's
 * signals become the WebViewClient/WebChromeClient callbacks here, with Chromium WebView's ordering
 * and defaults. The JavaScript interface is synchronous like Android's: the injected bootstrap reaches
 * Java through a prompt() WebKit holds open (script-dialog) while the method runs on the JavaBridge
 * thread, which is immune to the page's Content-Security-Policy.
 *
 * <p>What WebKitGTK cannot give the embedder: interception of subresource loads (shouldInterceptRequest
 * is not called; WebKit has no UI-process hook for them), onLoadResource and subresource
 * onReceivedError/onReceivedHttpError, per-frame information for navigations (requests report
 * isForMainFrame true), console messages, and Safe Browsing.
 */
public class WebView extends ViewGroup {
	private static final String LOGTAG = "WebView";

	/** Broadcast Action: Indicates the data reduction proxy setting changed. @hide */
	public static final String SCHEME_TEL = "tel:";
	public static final String SCHEME_MAILTO = "mailto:";
	public static final String SCHEME_GEO = "geo:0,0?q=";

	public static final int RENDERER_PRIORITY_WAIVED = 0;
	public static final int RENDERER_PRIORITY_BOUND = 1;
	public static final int RENDERER_PRIORITY_IMPORTANT = 2;

	/* AOSP's asset and resource URLs; WebKit cannot serve file:, so they map to our scheme */
	private static final String ASSET_URL = "file:///android_asset/";
	private static final String ASSET_SCHEME_URL = "android-asset:///assets/";

	/* WebKitLoadEvent */
	private static final int LOAD_STARTED = 0, LOAD_REDIRECTED = 1, LOAD_COMMITTED = 2, LOAD_FINISHED = 3;
	/* WebKitNavigationType */
	private static final int NAV_LINK_CLICKED = 0, NAV_FORM_SUBMITTED = 1, NAV_BACK_FORWARD = 2, NAV_RELOAD = 3, NAV_FORM_RESUBMITTED = 4, NAV_OTHER = 5;
	/* WebKitScriptDialogType */
	private static final int DIALOG_ALERT = 0, DIALOG_CONFIRM = 1, DIALOG_PROMPT = 2, DIALOG_BEFORE_UNLOAD = 3;
	/* WebKitWebProcessTerminationReason */
	private static final int PROCESS_CRASHED = 0, PROCESS_EXCEEDED_MEMORY_LIMIT = 1, PROCESS_TERMINATED_BY_API = 2;

	/**
	 * Transportation object for returning WebView across thread boundaries.
	 */
	public class WebViewTransport {
		private WebView mWebview;

		/**
		 * Sets the WebView to the transportation object.
		 */
		public synchronized void setWebView(WebView webview) {
			mWebview = webview;
		}

		/**
		 * Gets the WebView object.
		 */
		public synchronized WebView getWebView() {
			return mWebview;
		}
	}

	/**
	 * Callback interface supplied to {@link #postVisualStateCallback} for receiving
	 * notifications about the visual state.
	 */
	public static abstract class VisualStateCallback {
		/**
		 * Invoked when the visual state is ready to be drawn in the next {@link #onDraw}.
		 *
		 * @param requestId The identifier passed to {@link #postVisualStateCallback} when this
		 *                  callback was posted.
		 */
		public abstract void onComplete(long requestId);
	}

	/* ---- process-wide state ---- */

	static volatile boolean sWebContentsDebuggingEnabled;
	private static boolean sSessionReady;
	private static String sDataDirectorySuffix;
	private static boolean sDisabled;
	private static HandlerThread sBridgeThread;
	private static Handler sBridgeHandler;
	private static Set<String> sSafeBrowsingAllowlist = new HashSet<String>();
	private static final List<WeakReference<WebView>> sLiveViews = new ArrayList<WeakReference<WebView>>();

	/* ---- per view ---- */

	private Looper mLooper;
	private WebSettings mSettings;
	private long mEngine;
	private boolean mDestroyed;
	private boolean mPaused;
	private boolean mRendererGone;
	private WebViewClient mWebViewClient;
	private WebChromeClient mWebChromeClient;
	private DownloadListener mDownloadListener;
	private WebViewRenderProcessClient mRenderProcessClient;
	private Executor mRenderProcessClientExecutor;
	private int mBackgroundColor;
	private boolean mBackgroundColorSet;
	private boolean mVerticalScrollbarOverlay;
	private boolean mHorizontalScrollbarOverlay;

	private final Map<String, Object> mJsInterfaces = new LinkedHashMap<String, Object>();
	private final Map<String, Object> mJsReturned = new HashMap<String, Object>();
	private int mNextReturnedId;
	private String mBridgeToken;

	private final SparseArray<ValueCallback<String>> mEvalCallbacks = new SparseArray<ValueCallback<String>>();
	private int mNextEvalId;

	private final SparseArray<WeakReference<WebMessagePort.Impl>> mPagePorts = new SparseArray<WeakReference<WebMessagePort.Impl>>();
	private int mNextPortId = 1;
	private WebMessagePort.PageEndpoint mPortEndpoint;

	/* back/forward list floor set by clearHistory: items before it and the forward items at that time
	 * are hidden (WebKitGTK cannot drop history entries); the pointers are ref'd so they stay unique */
	private long mHistoryFloor;
	private final Set<Long> mHiddenItems = new HashSet<Long>();
	private final Map<Long, Integer> mItemIds = new HashMap<Long, Integer>();
	private int mNextItemId = 1;

	/* navigation bookkeeping */
	private String mApiLoadUrl;        // URL of the last loadUrl-type call, until WebKit asks about it
	private boolean mLoading;          // between LOAD_STARTED and LOAD_FINISHED
	private boolean mCommitted;        // the current load has committed
	private boolean mReloading;
	private String mLastCommittedUrl;
	private long mLoadStartUs;

	public WebView(Context context) {
		this(context, null);
	}

	public WebView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public WebView(Context context, AttributeSet attrs, int defStyleAttr) {
		this(context, attrs, defStyleAttr, 0);
	}

	public WebView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
		if (sDisabled)
			throw new IllegalStateException("WebView.disableWebView() was called: WebView is disabled");
		if (context == null)
			throw new IllegalArgumentException("Invalid context argument");
		mLooper = Looper.myLooper();
		if (mLooper == null)
			throw new RuntimeException("WebView cannot be initialized on a thread that has no Looper.");
		mSettings = new WebSettings(this, context);
		CookieSyncManager.setGetInstanceIsAllowed();
		synchronized (sLiveViews) {
			sLiveViews.add(new WeakReference<WebView>(this));
		}
	}

	/**
	 * @deprecated Private browsing is no longer supported directly via
	 * WebView and will be removed in a future release.
	 */
	@Deprecated
	public WebView(Context context, AttributeSet attrs, int defStyleAttr, boolean privateBrowsing) {
		this(context, attrs, defStyleAttr, 0);
	}

	/* ---- thread and engine ---- */

	private void checkThread() {
		if (mLooper != null && Looper.myLooper() != mLooper) {
			Throwable throwable = new Throwable(
			    "A WebView method was called on thread '" + Thread.currentThread().getName() + "'. " +
			    "All WebView methods must be called on the same thread. " +
			    "(Expected Looper " + mLooper + " called on " + Looper.myLooper() +
			    ", FYI main Looper is " + Looper.getMainLooper() + ")");
			Log.w(LOGTAG, Log.getStackTraceString(throwable));
			if (getContext().getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.JELLY_BEAN_MR2)
				throw new RuntimeException(throwable);
		}
	}

	long engine() {
		return mEngine;
	}

	/** the process-wide WebKit network session: app_webview[_suffix] and cache/WebView[_suffix] */
	static synchronized void ensureSession(Context context) {
		if (sSessionReady)
			return;
		Context ctx = context != null ? context.getApplicationContext() : null;
		if (ctx == null)
			ctx = context != null ? context : android.app.ActivityThread.currentApplication();
		String suffix = sDataDirectorySuffix != null ? "_" + sDataDirectorySuffix : "";
		File data = ctx.getDir("webview" + suffix, Context.MODE_PRIVATE);
		File cache = new File(ctx.getCacheDir(), "WebView" + suffix);
		cache.mkdirs();
		nativeInitSession(data.getAbsolutePath(), cache.getAbsolutePath(), ((CookieManager.Impl)CookieManager.getInstance()).policy());
		GeolocationPermissions.getInstance().setDataDir(data);
		sSessionReady = true;
	}

	private boolean ensureEngine() {
		if (mDestroyed)
			return false;
		if (mEngine != 0)
			return true;
		ensureSession(getContext());
		mEngine = nativeCreateEngine(widget, 0);
		mRendererGone = false;
		mSettings.applyAll();
		if (mBackgroundColorSet)
			nativeSetBackgroundColor(mEngine, mBackgroundColor);
		nativeSetUserScript(mEngine, buildBootstrap());
		return true;
	}

	private static TracingController.Recorder tracer() {
		return TracingController.Recorder.INSTANCE;
	}

	private static Map<String, Object> args(Object... kv) {
		Map<String, Object> m = new HashMap<String, Object>();
		for (int i = 0; i + 1 < kv.length; i += 2)
			m.put((String)kv[i], kv[i + 1]);
		return m;
	}

	/* ---- settings and clients ---- */

	/**
	 * Gets the WebSettings object used to control the settings for this WebView.
	 */
	public WebSettings getSettings() {
		checkThread();
		return mSettings;
	}

	public void setWebViewClient(WebViewClient client) {
		checkThread();
		mWebViewClient = client;
	}

	/**
	 * Gets the WebViewClient. If not set, the default WebViewClient is returned.
	 */
	public WebViewClient getWebViewClient() {
		checkThread();
		if (mWebViewClient == null)
			mWebViewClient = new WebViewClient();
		return mWebViewClient;
	}

	private WebViewClient client() {
		return mWebViewClient;
	}

	public void setWebChromeClient(WebChromeClient client) {
		checkThread();
		mWebChromeClient = client;
	}

	/**
	 * Gets the chrome handler, or {@code null} if none was set.
	 */
	public WebChromeClient getWebChromeClient() {
		checkThread();
		return mWebChromeClient;
	}

	public void setDownloadListener(DownloadListener listener) {
		checkThread();
		mDownloadListener = listener;
	}

	/**
	 * Sets the renderer client object associated with this WebView. The callbacks run on
	 * {@code executor}.
	 */
	public void setWebViewRenderProcessClient(Executor executor, WebViewRenderProcessClient webViewRenderProcessClient) {
		if (executor == null)
			throw new IllegalArgumentException("executor must not be null");
		mRenderProcessClient = webViewRenderProcessClient;
		mRenderProcessClientExecutor = executor;
	}

	/**
	 * Sets the renderer client object associated with this WebView. The callbacks run on the
	 * WebView's UI thread.
	 */
	public void setWebViewRenderProcessClient(WebViewRenderProcessClient webViewRenderProcessClient) {
		mRenderProcessClient = webViewRenderProcessClient;
		mRenderProcessClientExecutor = null;
	}

	public WebViewRenderProcessClient getWebViewRenderProcessClient() {
		return mRenderProcessClient;
	}

	/**
	 * Gets a handle to the WebView renderer process associated with this WebView, or {@code null}
	 * if the renderer has not been started yet or has gone away.
	 */
	public WebViewRenderProcess getWebViewRenderProcess() {
		checkThread();
		if (mEngine == 0 || mRendererGone)
			return null;
		return new WebViewRenderProcess.Impl(this);
	}

	boolean terminateRenderProcess() {
		if (mEngine == 0 || mRendererGone)
			return false;
		nativeTerminateWebProcess(mEngine);
		return true;
	}

	@Override
	public void setBackgroundColor(int color) {
		super.setBackgroundColor(color);
		mBackgroundColor = color;
		mBackgroundColorSet = true;
		if (mEngine != 0)
			nativeSetBackgroundColor(mEngine, color);
	}

	/**
	 * Specifies whether the vertical scrollbar has overlay style.
	 * @deprecated This method has no effect (WebKitGTK scrollbars are always overlay scrollbars).
	 */
	@Deprecated
	public void setVerticalScrollbarOverlay(boolean overlay) {
		mVerticalScrollbarOverlay = overlay;
	}

	@Deprecated
	public boolean overlayVerticalScrollbar() {
		return true;
	}

	@Deprecated
	public void setHorizontalScrollbarOverlay(boolean overlay) {
		mHorizontalScrollbarOverlay = overlay;
	}

	@Deprecated
	public boolean overlayHorizontalScrollbar() {
		return true;
	}

	/* ---- loading ---- */

	private static String toEngineUrl(String url) {
		if (url != null && url.startsWith(ASSET_URL))
			return ASSET_SCHEME_URL + url.substring(ASSET_URL.length());
		return url;
	}

	private static String toAppUrl(String url) {
		if (url != null && url.startsWith(ASSET_SCHEME_URL))
			return ASSET_URL + url.substring(ASSET_SCHEME_URL.length());
		return url;
	}

	public void loadUrl(String url) {
		loadUrl(url, null);
	}

	/**
	 * Loads the given URL with additional HTTP headers, specified as a map from
	 * name to value. Note that if this map contains any of the headers that are
	 * set by default by this WebView, such as those controlling caching, accept
	 * types or the User-Agent, their values may be overridden by this WebView's
	 * defaults.
	 */
	public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
		checkThread();
		if (url == null || !ensureEngine())
			return;
		if (url.startsWith("javascript:")) {
			// executes in the current page, as Chromium does; the result is discarded
			nativeEvaluateJavascript(mEngine, Uri.decode(url.substring("javascript:".length())), -1, false);
			return;
		}
		ArrayList<String> headers = new ArrayList<String>();
		if (additionalHttpHeaders != null) {
			for (Map.Entry<String, String> e : additionalHttpHeaders.entrySet()) {
				if (e.getKey() == null || e.getValue() == null)
					continue;
				headers.add(e.getKey());
				headers.add(e.getValue());
			}
		}
		if (mSettings.getCacheMode() == WebSettings.LOAD_NO_CACHE) {
			headers.add("Cache-Control");
			headers.add("no-cache");
			headers.add("Pragma");
			headers.add("no-cache");
		}
		String engineUrl = toEngineUrl(url);
		mApiLoadUrl = engineUrl;
		nativeLoadUri(mEngine, engineUrl, headers.toArray(new String[0]));
	}

	/**
	 * Loads the URL with postData using "POST" method into this WebView.
	 * teslalinux: WebKitGTK's load API cannot carry a request body, so the POST is made by a form
	 * the engine submits (the same request a browser sends: application/x-www-form-urlencoded body).
	 */
	public void postUrl(String url, byte[] postData) {
		checkThread();
		if (!URLUtil.isNetworkUrl(url)) {
			loadUrl(url);
			return;
		}
		if (!ensureEngine())
			return;
		StringBuilder html = new StringBuilder("<!DOCTYPE html><html><body><form id=\"f\" method=\"post\" action=\"");
		html.append(escapeHtml(url)).append("\">");
		String body = postData != null ? new String(postData, StandardCharsets.ISO_8859_1) : "";
		for (String pair : body.split("&")) {
			if (pair.isEmpty())
				continue;
			int eq = pair.indexOf('=');
			String k = eq >= 0 ? pair.substring(0, eq) : pair;
			String v = eq >= 0 ? pair.substring(eq + 1) : "";
			html.append("<input type=\"hidden\" name=\"").append(escapeHtml(formDecode(k)))
			    .append("\" value=\"").append(escapeHtml(formDecode(v))).append("\">");
		}
		html.append("</form><script>document.getElementById('f').submit();</script></body></html>");
		mApiLoadUrl = url;
		byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8);
		nativeLoadData(mEngine, bytes, "text/html", "UTF-8", "about:blank");
	}

	private static String formDecode(String s) {
		try {
			return java.net.URLDecoder.decode(s, "UTF-8");
		} catch (Exception e) {
			return s;
		}
	}

	private static String escapeHtml(String s) {
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '&': b.append("&amp;"); break;
				case '"': b.append("&quot;"); break;
				case '<': b.append("&lt;"); break;
				case '>': b.append("&gt;"); break;
				default: b.append(c);
			}
		}
		return b.toString();
	}

	/**
	 * Loads the given data into this WebView using a 'data' scheme URL.
	 */
	public void loadData(String data, String mimeType, String encoding) {
		checkThread();
		if (data == null || !ensureEngine())
			return;
		StringBuilder url = new StringBuilder("data:");
		url.append(mimeType != null ? mimeType : "text/html");
		if ("base64".equals(encoding))
			url.append(";base64");
		url.append(',').append(data);
		mApiLoadUrl = url.toString();
		nativeLoadUri(mEngine, mApiLoadUrl, null);
	}

	/**
	 * Loads the given data into this WebView, using baseUrl as the base URL for
	 * the content. The base URL is used both to resolve relative URLs and when
	 * applying JavaScript's same origin policy. The historyUrl is used for the
	 * history entry.
	 *
	 * teslalinux: WebKit lists the entry under the base URL; a different historyUrl is not kept.
	 */
	public void loadDataWithBaseURL(String baseUrl, String data, String mimeType, String encoding, String historyUrl) {
		checkThread();
		if (data == null || !ensureEngine())
			return;
		if (baseUrl == null || baseUrl.isEmpty())
			baseUrl = "about:blank";
		if (baseUrl.startsWith("data:")) {
			// Chromium loads it as a data URL when the base is one
			loadData(data, mimeType, encoding);
			return;
		}
		if (mimeType == null || mimeType.isEmpty())
			mimeType = "text/html";
		if (mimeType.contains(";"))
			mimeType = mimeType.substring(0, mimeType.indexOf(';')).trim();
		byte[] bytes;
		String charset;
		if ("base64".equals(encoding)) {
			bytes = Base64.decode(data, Base64.DEFAULT);
			charset = null;
		} else {
			charset = "UTF-8";
			if (encoding != null) {
				try {
					charset = Charset.forName(encoding).name();
				} catch (RuntimeException e) {
					charset = "UTF-8";
				}
			}
			// WebKit cannot serve file:, so asset references go through the android-asset scheme
			bytes = data.replace(ASSET_URL, ASSET_SCHEME_URL).getBytes(Charset.forName(charset));
		}
		String base = toEngineUrl(baseUrl);
		mApiLoadUrl = base;
		nativeLoadData(mEngine, bytes, mimeType, charset, base);
	}

	public void stopLoading() {
		checkThread();
		if (mEngine != 0)
			nativeStopLoading(mEngine);
	}

	public void reload() {
		checkThread();
		if (mEngine == 0)
			return;
		mReloading = true;
		nativeReload(mEngine, mSettings.getCacheMode() == WebSettings.LOAD_NO_CACHE);
	}

	/* ---- history ---- */

	private long[] visibleHistory(int[] currentOut) {
		currentOut[0] = -1;
		if (mEngine == 0)
			return new long[0];
		long[] raw = nativeHistoryItems(mEngine);
		int cur = (int)raw[0];
		int start = 0;
		if (mHistoryFloor != 0) {
			for (int i = 1; i < raw.length; i++) {
				if (raw[i] == mHistoryFloor) {
					start = i - 1;
					break;
				}
			}
			if (cur >= 0 && cur < start)
				start = cur; // the page went back past the floor itself (history.back())
		}
		ArrayList<Long> items = new ArrayList<Long>();
		for (int i = start; i < raw.length - 1; i++) {
			long item = raw[i + 1];
			if (i != cur && mHiddenItems.contains(item))
				continue;
			if (i == cur)
				currentOut[0] = items.size();
			items.add(item);
		}
		long[] out = new long[items.size()];
		for (int i = 0; i < out.length; i++)
			out[i] = items.get(i);
		return out;
	}

	private int itemId(long item) {
		Integer id = mItemIds.get(item);
		if (id == null) {
			id = mNextItemId++;
			mItemIds.put(item, id);
		}
		return id;
	}

	/**
	 * Gets the WebBackForwardList for this WebView. This contains the
	 * back/forward list for use in querying each item in the history stack.
	 * This is a copy of the private WebBackForwardList so it contains only a
	 * snapshot of the current state.
	 */
	public WebBackForwardList copyBackForwardList() {
		checkThread();
		int[] cur = new int[1];
		long[] items = visibleHistory(cur);
		ArrayList<WebHistoryItem> list = new ArrayList<WebHistoryItem>();
		for (long item : items) {
			list.add(new WebHistoryItem.Entry(itemId(item), toAppUrl(nativeItemString(item, 0)),
			                                  toAppUrl(nativeItemString(item, 1)), nativeItemString(item, 2)));
		}
		return new WebBackForwardList.Snapshot(list, cur[0]);
	}

	public boolean canGoBack() {
		return canGoBackOrForward(-1);
	}

	public void goBack() {
		goBackOrForward(-1);
	}

	public boolean canGoForward() {
		return canGoBackOrForward(1);
	}

	public void goForward() {
		goBackOrForward(1);
	}

	/**
	 * Gets whether the page can go back or forward the given number of steps.
	 */
	public boolean canGoBackOrForward(int steps) {
		checkThread();
		int[] cur = new int[1];
		long[] items = visibleHistory(cur);
		int target = cur[0] + steps;
		return cur[0] >= 0 && target >= 0 && target < items.length;
	}

	/**
	 * Goes to the history item that is the number of steps away from the current item.
	 * Steps is negative if backward and positive if forward.
	 */
	public void goBackOrForward(int steps) {
		checkThread();
		int[] cur = new int[1];
		long[] items = visibleHistory(cur);
		int target = cur[0] + steps;
		if (cur[0] < 0 || target < 0 || target >= items.length || steps == 0)
			return;
		nativeGoToItem(mEngine, items[target]);
	}

	private void releaseHistoryRefs() {
		if (mHistoryFloor != 0)
			nativeItemRef(mHistoryFloor, false);
		mHistoryFloor = 0;
		for (long item : mHiddenItems)
			nativeItemRef(item, false);
		mHiddenItems.clear();
	}

	/**
	 * Tells this WebView to clear its internal back/forward list.
	 */
	public void clearHistory() {
		checkThread();
		if (mEngine == 0)
			return;
		long[] raw = nativeHistoryItems(mEngine);
		int cur = (int)raw[0];
		releaseHistoryRefs();
		if (cur < 0)
			return;
		mHistoryFloor = raw[cur + 1];
		nativeItemRef(mHistoryFloor, true);
		for (int i = cur + 1; i < raw.length - 1; i++) {
			long item = raw[i + 1];
			if (mHiddenItems.add(item))
				nativeItemRef(item, true);
		}
	}

	private static final String STATE_KEY = "WEBVIEW_CHROMIUM_STATE";

	/**
	 * Saves the state of this WebView used in
	 * {@link android.app.Activity#onSaveInstanceState}. Please note that this
	 * method no longer stores the display data for this WebView.
	 *
	 * @return the same copy of the back/forward list used to save the state, {@code null} if the
	 *         method fails.
	 */
	public WebBackForwardList saveState(Bundle outState) {
		checkThread();
		if (outState == null)
			throw new IllegalArgumentException("outState is null");
		if (mEngine == 0)
			return null;
		WebBackForwardList list = copyBackForwardList();
		if (list.getSize() == 0)
			return null;
		byte[] state = nativeSaveState(mEngine);
		if (state == null)
			return null;
		outState.putByteArray(STATE_KEY, state);
		return list;
	}

	/**
	 * Restores the state of this WebView from the given Bundle. This method is
	 * intended for use in {@link android.app.Activity#onRestoreInstanceState}
	 * and should be called to restore the state of this WebView. If
	 * it is called after this WebView has had a chance to build state (load
	 * pages, create a back/forward list, etc.) there may be undesirable
	 * side-effects.
	 *
	 * @return the restored back/forward list or {@code null} if restoreState failed
	 */
	public WebBackForwardList restoreState(Bundle inState) {
		checkThread();
		if (inState == null)
			return null;
		byte[] state = inState.getByteArray(STATE_KEY);
		if (state == null || !ensureEngine())
			return null;
		releaseHistoryRefs();
		if (!nativeRestoreState(mEngine, state))
			return null;
		return copyBackForwardList();
	}

	public String getUrl() {
		checkThread();
		return mEngine != 0 ? toAppUrl(nativeGetUri(mEngine)) : null;
	}

	/**
	 * Gets the original URL for the current page. This is not always the same
	 * as the URL passed to WebViewClient.onPageStarted because although the load for
	 * that URL has begun, the current page may not have changed. Also, there
	 * may have been redirects resulting in a different URL to that originally
	 * requested.
	 */
	public String getOriginalUrl() {
		checkThread();
		WebHistoryItem item = copyBackForwardList().getCurrentItem();
		return item != null ? item.getOriginalUrl() : null;
	}

	public String getTitle() {
		checkThread();
		return mEngine != 0 ? nativeGetTitle(mEngine) : null;
	}

	/**
	 * Gets the progress for the current page.
	 * @return the progress for the current page between 0 and 100
	 */
	public int getProgress() {
		checkThread();
		return mEngine != 0 && mLoading ? nativeGetProgress(mEngine) : 100;
	}

	/* ---- JavaScript ---- */

	/**
	 * Asynchronously evaluates JavaScript in the context of the currently displayed page.
	 * If non-null, {@code resultCallback} will be invoked with any result returned from that
	 * execution. This method must be called on the UI thread and the callback will
	 * be made on the UI thread.
	 */
	public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {
		checkThread();
		if (script == null || !ensureEngine())
			return;
		int id = -1;
		if (resultCallback != null) {
			id = mNextEvalId++;
			mEvalCallbacks.put(id, resultCallback);
		}
		if (tracer().isTracing())
			tracer().record("android_webview", "WebView.evaluateJavascript", TracingController.Recorder.nowUs(), -1, null);
		nativeEvaluateJavascript(mEngine, script, id, false);
	}

	/**
	 * Posts a {@link VisualStateCallback}, which will be called when
	 * the current state of the WebView is ready to be drawn.
	 */
	public void postVisualStateCallback(final long requestId, final VisualStateCallback callback) {
		checkThread();
		if (callback == null || !ensureEngine())
			return;
		// two animation frames: the frame with the DOM state as it is now has been produced
		runAfterNextFrame(new Runnable() {
			public void run() {
				callback.onComplete(requestId);
			}
		});
	}

	private void runAfterNextFrame(final Runnable r) {
		int id = mNextEvalId++;
		mEvalCallbacks.put(id, new ValueCallback<String>() {
			public void onReceiveValue(String value) {
				r.run();
			}
		});
		nativeEvaluateJavascript(mEngine, "await new Promise(function(r){requestAnimationFrame(function(){requestAnimationFrame(r);});}); return 0;", id, true);
	}

	/**
	 * Injects the supplied Java object into this WebView. The object is
	 * injected into all frames of the web page, including all the iframes,
	 * using the supplied name. This allows the Java object's methods to be
	 * accessed from JavaScript. For applications targeted to API
	 * level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
	 * and above, only public methods that are annotated with
	 * {@link android.webkit.JavascriptInterface} can be accessed from JavaScript.
	 * Note that injected objects will not appear in JavaScript until the page is next
	 * (re)loaded. JavaScript interacts with Java object on a private, background
	 * thread of this WebView.
	 */
	public void addJavascriptInterface(Object object, String name) {
		checkThread();
		if (object == null || name == null)
			return;
		mJsInterfaces.put(name, object);
		if (mEngine != 0)
			nativeSetUserScript(mEngine, buildBootstrap());
	}

	/**
	 * Removes a previously injected Java object from this WebView. Note that
	 * the removal will not be reflected in JavaScript until the page is next
	 * (re)loaded.
	 */
	public void removeJavascriptInterface(String name) {
		checkThread();
		if (mJsInterfaces.remove(name) != null && mEngine != 0)
			nativeSetUserScript(mEngine, buildBootstrap());
	}

	private boolean exposesAllMethods() {
		return getContext().getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.JELLY_BEAN_MR1;
	}

	private List<String> exposedMethodNames(Object object) {
		Set<String> names = new java.util.TreeSet<String>();
		boolean all = exposesAllMethods();
		for (Method m : object.getClass().getMethods()) {
			if (m.getDeclaringClass() == Object.class && m.getName().equals("getClass"))
				continue;
			if (all || m.isAnnotationPresent(JavascriptInterface.class))
				names.add(m.getName());
		}
		return new ArrayList<String>(names);
	}

	private String bridgeToken() {
		if (mBridgeToken == null) {
			byte[] r = new byte[12];
			new SecureRandom().nextBytes(r);
			StringBuilder b = new StringBuilder("__atl_bridge_");
			for (byte x : r)
				b.append(String.format("%02x", x & 0xff));
			mBridgeToken = b.toString();
		}
		return mBridgeToken;
	}

	/* The page side of the JavaScript interface and of transferred message ports. Every call is
	 * prompt(token, request): WebKit asks us (script-dialog) and holds the page until we answer. */
	private String buildBootstrap() {
		JSONObject defs = new JSONObject();
		try {
			for (Map.Entry<String, Object> e : mJsInterfaces.entrySet())
				defs.put(e.getKey(), new JSONArray(exposedMethodNames(e.getValue())));
		} catch (JSONException e) {
		}
		String token = JSONObject.quote(bridgeToken());
		return "(function(){var W=window,T=" + token + ",P=W.prompt,J=JSON;if(Object.prototype.hasOwnProperty.call(W,T))return;" +
		    "function A(v){var t=typeof v;if(v===undefined||t==='function'||t==='symbol')return{t:'u'};if(v===null)return{t:'n'};" +
		    "if(t==='number')return{t:'d',v:String(v)};if(t==='boolean')return{t:'b',v:v};if(t==='string')return{t:'s',v:v};" +
		    "if(Array.isArray(v)||ArrayBuffer.isView(v)){var a=[];for(var i=0;i<v.length;i++){var x=v[i];a.push(typeof x==='object'&&x!==null?{t:'o'}:A(x));}return{t:'a',v:a};}return{t:'o'};}" +
		    "function C(o,m,args){var a=[];for(var i=0;i<args.length;i++)a.push(A(args[i]));var r=P.call(W,T,J.stringify({o:o,m:m,a:a}));" +
		    "if(r===null||r===undefined)throw new Error('Java bridge method can\\'t be invoked');var x=J.parse(r);if(x.e!==undefined)throw new Error(x.e);" +
		    "if(x.o!==undefined)return O(x.o,x.m);return x.v;}" +
		    "function O(id,ms){var b={};ms.forEach(function(m){b[m]=function(){return C(id,m,arguments);};});return b;}" +
		    "var D=" + defs.toString() + ";Object.keys(D).forEach(function(n){var b=O(n,D[n]);try{Object.defineProperty(W,n,{value:b,configurable:true,writable:true});}catch(e){W[n]=b;}});" +
		    "var ports={},neg=0;function L(id,p){ports[id]=p;p.onmessage=function(e){var ids=[];if(e.ports)for(var i=0;i<e.ports.length;i++){neg--;L(neg,e.ports[i]);ids.push(neg);}" +
		    "var d=e.data;C(T,'msg',[id,typeof d==='string'?d:J.stringify(d),ids]);};}" +
		    "function M(id){var c=new MessageChannel();L(id,c.port1);return c.port2;}" +
		    "var R={post:function(id,d,ids){var p=ports[id];if(p)p.postMessage(d,ids.map(M));}," +
		    "close:function(id){var p=ports[id];if(p){p.close();delete ports[id];}}," +
		    "dispatch:function(d,o,ids){if(o!=='*'&&W.location.origin!==o)return;W.dispatchEvent(new MessageEvent('message',{data:d,origin:'',ports:ids.map(M)}));}};" +
		    "Object.defineProperty(W,T,{value:R});})();";
	}

	private static synchronized Handler bridgeHandler() {
		if (sBridgeHandler == null) {
			sBridgeThread = new HandlerThread("JavaBridge");
			sBridgeThread.start();
			sBridgeHandler = new Handler(sBridgeThread.getLooper());
		}
		return sBridgeHandler;
	}

	/* a call from the page: runs on the JavaBridge thread, the answer goes back on the UI thread */
	private void bridgeCall(final long dialog, final String payload) {
		final Handler ui = new Handler(mLooper);
		bridgeHandler().post(new Runnable() {
			public void run() {
				long start = TracingController.Recorder.nowUs();
				final String result = invokeBridge(payload);
				if (tracer().isTracing())
					tracer().record("android_webview", "JavaBridge.invoke", start, TracingController.Recorder.nowUs() - start, null);
				ui.post(new Runnable() {
					public void run() {
						nativeScriptDialogClose(dialog, true, result);
					}
				});
			}
		});
	}

	private String invokeBridge(String payload) {
		try {
			JSONObject req = new JSONObject(payload);
			String objName = req.getString("o");
			String methodName = req.getString("m");
			JSONArray args = req.getJSONArray("a");
			if (objName.equals(mBridgeToken)) {
				if (methodName.equals("msg"))
					portMessageFromPage(args);
				return "{}";
			}
			Object target;
			synchronized (mJsReturned) {
				target = objName.startsWith("#") ? mJsReturned.get(objName) : null;
			}
			if (target == null) {
				final Object[] holder = new Object[1];
				final String n = objName;
				// the interface map is UI-thread state
				runOnUiAndWait(new Runnable() {
					public void run() {
						holder[0] = mJsInterfaces.get(n);
					}
				});
				target = holder[0];
			}
			if (target == null)
				return error("Method not found");
			boolean all = exposesAllMethods();
			Method method = null;
			for (Method m : target.getClass().getMethods()) {
				if (m.getName().equals(methodName) && m.getParameterTypes().length == args.length() &&
				    (all || m.isAnnotationPresent(JavascriptInterface.class)) &&
				    !(m.getDeclaringClass() == Object.class && m.getName().equals("getClass"))) {
					method = m;
					break;
				}
			}
			if (method == null)
				return error("Method not found");
			Class<?>[] types = method.getParameterTypes();
			Object[] values = new Object[types.length];
			for (int i = 0; i < types.length; i++)
				values[i] = fromJs(args.getJSONObject(i), types[i]);
			Object ret;
			try {
				ret = method.invoke(target, values);
			} catch (InvocationTargetException e) {
				Log.e(LOGTAG, "Java exception in JavaScript interface method " + methodName, e.getCause());
				return error("Java exception was raised during method invocation");
			}
			return toJs(ret, method.getReturnType());
		} catch (Exception e) {
			return error("Java bridge call failed: " + e);
		}
	}

	private static String error(String message) {
		try {
			return new JSONObject().put("e", message).toString();
		} catch (JSONException e) {
			return "{\"e\":\"error\"}";
		}
	}

	private void runOnUiAndWait(final Runnable r) {
		if (Looper.myLooper() == mLooper) {
			r.run();
			return;
		}
		final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
		new Handler(mLooper).post(new Runnable() {
			public void run() {
				try {
					r.run();
				} finally {
					done.countDown();
				}
			}
		});
		try {
			done.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/* JavaScript value -> Java parameter, following Chromium's JavaBridge coercions */
	private static Object fromJs(JSONObject v, Class<?> type) throws JSONException {
		String t = v.getString("t");
		if (type.isArray()) {
			if (!t.equals("a"))
				return null;
			JSONArray a = v.getJSONArray("v");
			Class<?> ct = type.getComponentType();
			if (!ct.isPrimitive() && ct != String.class)
				return null;
			Object arr = Array.newInstance(ct, a.length());
			for (int i = 0; i < a.length(); i++)
				Array.set(arr, i, fromJs(a.getJSONObject(i), ct));
			return arr;
		}
		double num = t.equals("d") ? Double.parseDouble(v.getString("v")) : 0;
		if (type == String.class) {
			switch (t) {
				case "s": return v.getString("v");
				case "n": return null;
				case "b": return String.valueOf(v.getBoolean("v"));
				case "d": return num == Math.rint(num) && !Double.isInfinite(num) && Math.abs(num) < 1e15 ? String.valueOf((long)num) : String.valueOf(num);
				default: return "undefined";
			}
		}
		if (type == boolean.class)
			return t.equals("b") && v.getBoolean("v");
		if (type == double.class)
			return num;
		if (type == float.class)
			return (float)num;
		if (type == long.class)
			return (long)num;
		if (type == int.class)
			return (int)(long)num;
		if (type == short.class)
			return (short)(long)num;
		if (type == byte.class)
			return (byte)(long)num;
		if (type == char.class)
			return (char)(long)num;
		/* Object and boxed types: strings, numbers and booleans as their Java boxes */
		switch (t) {
			case "s": return type.isAssignableFrom(String.class) ? v.getString("v") : null;
			case "d": return type.isAssignableFrom(Double.class) ? (Object)num : null;
			case "b": return type.isAssignableFrom(Boolean.class) ? (Object)v.getBoolean("v") : null;
			default: return null;
		}
	}

	/* Java result -> the page: primitives and strings by value, other objects as new bridge objects */
	private String toJs(Object ret, Class<?> type) throws JSONException {
		JSONObject r = new JSONObject();
		if (type == void.class || ret == null)
			return ret == null && type != void.class ? "{\"v\":null}" : "{}";
		if (ret instanceof String || ret instanceof Boolean || ret instanceof Character) {
			r.put("v", ret instanceof Character ? (Object)(int)(Character)ret : ret);
			return r.toString();
		}
		if (ret instanceof Number) {
			double d = ((Number)ret).doubleValue();
			if (Double.isNaN(d) || Double.isInfinite(d))
				return "{}";
			r.put("v", ret);
			return r.toString();
		}
		String id;
		synchronized (mJsReturned) {
			id = "#" + (mNextReturnedId++);
			mJsReturned.put(id, ret);
		}
		r.put("o", id);
		r.put("m", new JSONArray(exposedMethodNames(ret)));
		return r.toString();
	}

	/* ---- HTML5 message channels ---- */

	/**
	 * Creates a message channel to communicate with JS and returns the message
	 * ports that represent the endpoints of this message channel.
	 */
	public WebMessagePort[] createWebMessageChannel() {
		checkThread();
		return WebMessagePort.Impl.createPair();
	}

	/**
	 * Post a message to main frame. The embedded application can restrict the
	 * messages to a certain target origin.
	 *
	 * @param targetOrigin the target origin. Use {@code Uri.parse("*")} or {@code Uri.EMPTY} to
	 *                     match any origin.
	 */
	public void postWebMessage(WebMessage message, Uri targetOrigin) {
		checkThread();
		if (message == null || targetOrigin == null)
			throw new IllegalArgumentException("message and targetOrigin must not be null");
		if (!ensureEngine())
			return;
		String origin = "*";
		String o = targetOrigin.toString();
		if (!o.isEmpty() && !o.equals("*")) {
			if (targetOrigin.getScheme() == null || targetOrigin.getHost() == null)
				return;
			origin = targetOrigin.getScheme() + "://" + targetOrigin.getHost() + (targetOrigin.getPort() != -1 ? ":" + targetOrigin.getPort() : "");
		}
		JSONArray ids = transferPorts(message.getPorts());
		String js = "(function(){var R=window[" + JSONObject.quote(bridgeToken()) + "];if(R)R.dispatch(" +
		            JSONObject.quote(message.getData() != null ? message.getData() : "") + "," + JSONObject.quote(origin) + "," + ids + ");})()";
		nativeEvaluateJavascript(mEngine, js, -1, false);
	}

	/* hands Java ports to the page: each becomes a page MessagePort entangled with the old peer */
	private JSONArray transferPorts(WebMessagePort[] ports) {
		JSONArray ids = new JSONArray();
		if (ports == null)
			return ids;
		for (WebMessagePort p : ports) {
			if (!(p instanceof WebMessagePort.Impl))
				throw new IllegalArgumentException("Unknown WebMessagePort implementation");
			int id = mNextPortId++;
			WebMessagePort.Impl peer = ((WebMessagePort.Impl)p).transferToPage(portEndpoint(), id);
			if (peer == null)
				throw new IllegalStateException("Port is already closed or transferred");
			mPagePorts.put(id, new WeakReference<WebMessagePort.Impl>(peer));
			ids.put(id);
		}
		return ids;
	}

	private WebMessagePort.PageEndpoint portEndpoint() {
		if (mPortEndpoint == null) {
			mPortEndpoint = new WebMessagePort.PageEndpoint() {
				public void postToPage(final int pageId, final String data, final WebMessagePort[] ports) {
					runOnUiAndWait(new Runnable() {
						public void run() {
							if (mEngine == 0)
								return;
							JSONArray ids = transferPorts(ports);
							nativeEvaluateJavascript(mEngine, "(function(){var R=window[" + JSONObject.quote(bridgeToken()) + "];if(R)R.post(" + pageId + "," +
							                                      JSONObject.quote(data != null ? data : "") + "," + ids + ");})()", -1, false);
						}
					});
				}

				public void closePagePort(final int pageId) {
					new Handler(mLooper).post(new Runnable() {
						public void run() {
							mPagePorts.remove(pageId);
							if (mEngine != 0)
								nativeEvaluateJavascript(mEngine, "(function(){var R=window[" + JSONObject.quote(bridgeToken()) + "];if(R)R.close(" + pageId + ");})()", -1, false);
						}
					});
				}
			};
		}
		return mPortEndpoint;
	}

	/* bridge thread: [id, data, [ids of ports the page sent along]] */
	private void portMessageFromPage(JSONArray args) throws JSONException {
		final int id = (int)Double.parseDouble(args.getJSONObject(0).getString("v"));
		JSONObject d = args.getJSONObject(1);
		final String data = d.getString("t").equals("s") ? d.getString("v") : null;
		JSONArray idList = args.getJSONObject(2).getJSONArray("v");
		final int[] newIds = new int[idList.length()];
		for (int i = 0; i < newIds.length; i++)
			newIds[i] = (int)Double.parseDouble(idList.getJSONObject(i).getString("v"));
		new Handler(mLooper).post(new Runnable() {
			public void run() {
				WeakReference<WebMessagePort.Impl> ref = mPagePorts.get(id);
				WebMessagePort.Impl target = ref != null ? ref.get() : null;
				if (target == null)
					return;
				WebMessagePort[] ports = null;
				if (newIds.length > 0) {
					ports = new WebMessagePort[newIds.length];
					for (int i = 0; i < newIds.length; i++) {
						WebMessagePort.Impl p = WebMessagePort.Impl.forPage(portEndpoint(), newIds[i]);
						mPagePorts.put(newIds[i], new WeakReference<WebMessagePort.Impl>(p));
						ports[i] = p;
					}
				}
				target.deliver(new WebMessage(data, ports));
			}
		});
	}

	/* ---- misc API ---- */

	public void destroy() {
		checkThread();
		if (mDestroyed)
			return;
		mDestroyed = true;
		if (mEngine != 0) {
			releaseHistoryRefs();
			nativeDestroy(widget, mEngine);
			mEngine = 0;
		}
		mSettings.destroy();
		mEvalCallbacks.clear();
		synchronized (mJsReturned) {
			mJsReturned.clear();
		}
		synchronized (sLiveViews) {
			for (int i = sLiveViews.size() - 1; i >= 0; i--) {
				WebView v = sLiveViews.get(i).get();
				if (v == null || v == this)
					sLiveViews.remove(i);
			}
		}
	}

	/**
	 * Does a best-effort attempt to pause any processing that can be paused safely, such as
	 * animations and geolocation. Note that this call does not pause JavaScript.
	 * teslalinux: WebKitGTK throttles animations of views that are not mapped by itself; the paused
	 * state is kept for isPaused.
	 */
	public void onPause() {
		checkThread();
		mPaused = true;
	}

	public void onResume() {
		checkThread();
		mPaused = false;
	}

	/** @hide */
	public boolean isPaused() {
		return mPaused;
	}

	/**
	 * Clears the resource cache. Note that the cache is per-application, so this will clear the
	 * cache for all WebViews used.
	 */
	public void clearCache(boolean includeDiskFiles) {
		checkThread();
		ensureSession(getContext());
		nativeClearWebsiteData(includeDiskFiles ? 2 : 1, -1);
	}

	/**
	 * Enables debugging of web contents (HTML / CSS / JavaScript) loaded into any WebViews of this
	 * application. teslalinux: turns on WebKit's developer extras (Web Inspector) in every WebView.
	 */
	public static void setWebContentsDebuggingEnabled(boolean enabled) {
		sWebContentsDebuggingEnabled = enabled;
		synchronized (sLiveViews) {
			for (WeakReference<WebView> r : sLiveViews) {
				final WebView v = r.get();
				if (v == null)
					continue;
				new Handler(v.mLooper).post(new Runnable() {
					public void run() {
						if (v.mEngine != 0)
							nativeSetBoolSetting(v.mEngine, "enable-developer-extras", sWebContentsDebuggingEnabled);
					}
				});
			}
		}
	}

	/**
	 * Define the directory used to store WebView data for the current process. The provided suffix
	 * will be used when constructing data and cache directory paths. If this API is not called, no
	 * suffix will be used. Each directory can be used by only one process in the application.
	 *
	 * @throws IllegalStateException if WebView has already been initialized in the current process.
	 * @throws IllegalArgumentException if the suffix contains a path separator.
	 */
	public static void setDataDirectorySuffix(String suffix) {
		synchronized (WebView.class) {
			if (sSessionReady)
				throw new IllegalStateException("Can't set data directory suffix: WebView already initialized");
			if (suffix.indexOf(File.separatorChar) >= 0)
				throw new IllegalArgumentException("Suffix " + suffix + " contains a path separator");
			sDataDirectorySuffix = suffix;
		}
	}

	/**
	 * Indicate that the current process does not intend to use WebView, and
	 * that an exception should be thrown if a WebView is created or any other
	 * methods in the android.webkit package are used.
	 */
	public static void disableWebView() {
		synchronized (WebView.class) {
			if (sSessionReady)
				throw new IllegalStateException("Can't disable WebView: WebView already initialized");
			sDisabled = true;
		}
	}

	/**
	 * If WebView has already been loaded into the current process this method will return the
	 * package that was used to load it. Otherwise, the package that would be used if the WebView
	 * was loaded right now will be returned; this does not cause WebView to be loaded, so this
	 * information may become outdated at any time.
	 *
	 * teslalinux: there is no WebView package; the engine is the system's WebKitGTK library, not an
	 * APK, so this returns null, AOSP's answer when no WebView package is available.
	 */
	public static PackageInfo getCurrentWebViewPackage() {
		return null;
	}

	/**
	 * Returns the {@link ClassLoader} used to load internal WebView classes.
	 * This method is meant for use by the WebView Support Library, there is no reason to use this
	 * method otherwise.
	 */
	public static ClassLoader getWebViewClassLoader() {
		return WebView.class.getClassLoader();
	}

	/**
	 * Returns the {@link Looper} corresponding to the thread on which WebView calls must be made.
	 */
	public Looper getWebViewLooper() {
		return mLooper;
	}

	/**
	 * Starts Safe Browsing initialization.
	 * teslalinux: this platform has no Safe Browsing service, so initialization fails and the
	 * callback receives {@code false}, as AOSP reports on a device where Safe Browsing is unsupported.
	 */
	public static void startSafeBrowsing(Context context, final ValueCallback<Boolean> callback) {
		if (callback == null)
			return;
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			public void run() {
				callback.onReceiveValue(false);
			}
		});
	}

	private static final Pattern HOST_RULE = Pattern.compile("^\\.?[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$");
	private static final Pattern IPV4_RULE = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
	private static final Pattern IPV6_RULE = Pattern.compile("^\\[[0-9A-Fa-f:.]+\\]$");

	/**
	 * Configures a set of hosts (domain names/IP addresses) that are exempt from SafeBrowsing
	 * checks. The callback is invoked with {@code true} if the allowlist is successfully set,
	 * {@code false} if any hosts are malformed. (There is no Safe Browsing here; the list is
	 * validated and kept, as AOSP keeps it.)
	 */
	public static void setSafeBrowsingAllowlist(Set<String> hosts, final ValueCallback<Boolean> callback) {
		boolean ok = true;
		for (String h : hosts) {
			if (h == null || !(HOST_RULE.matcher(h).matches() || IPV4_RULE.matcher(h).matches() || IPV6_RULE.matcher(h).matches())) {
				ok = false;
				break;
			}
		}
		if (ok) {
			synchronized (WebView.class) {
				sSafeBrowsingAllowlist = new HashSet<String>(hosts);
			}
		}
		if (callback != null) {
			final boolean result = ok;
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				public void run() {
					callback.onReceiveValue(result);
				}
			});
		}
	}

	/**
	 * @deprecated use {@link #setSafeBrowsingAllowlist(Set, ValueCallback)} instead.
	 */
	@Deprecated
	public static void setSafeBrowsingWhitelist(List<String> hosts, ValueCallback<Boolean> callback) {
		setSafeBrowsingAllowlist(new HashSet<String>(hosts), callback);
	}

	/**
	 * Returns a URL pointing to the privacy policy for Safe Browsing reporting.
	 * (Chromium WebView's fixed value.)
	 */
	public static Uri getSafeBrowsingPrivacyPolicyUrl() {
		return Uri.parse("https://www.google.com/chrome/browser/privacy/");
	}

	// to be used by native code
	AssetManager internalGetAssetManager() {
		return getContext().getResources().getAssets();
	}

	// directly accessed by androidx WebViewGlueCommunicator to get the support library glue's
	// ClassLoader: there is no Chromium glue, so androidx reports its features as unsupported
	private static Object getFactory() {
		return new Object();
	}

	/* ---- engine callbacks (UI thread, from the native signal handlers) ---- */

	private WebResourceRequest request(String url, boolean redirect, boolean gesture, String method) {
		return new WebResourceRequest.Impl(toAppUrl(url), true, redirect, gesture, method, null);
	}

	void internalLoadChanged(int event, String uri) {
		uri = toAppUrl(uri);
		WebViewClient c = client();
		switch (event) {
			case LOAD_STARTED:
				mLoading = true;
				mCommitted = false;
				mLoadStartUs = TracingController.Recorder.nowUs();
				if (tracer().isTracing())
					tracer().record("navigation", "Navigation.Start", mLoadStartUs, -1, args("url", uri));
				break;
			case LOAD_COMMITTED: {
				mCommitted = true;
				boolean reload = mReloading || (uri != null && uri.equals(mLastCommittedUrl) && mApiLoadUrl == null);
				mReloading = false;
				mLastCommittedUrl = uri;
				if (tracer().isTracing())
					tracer().record("navigation", "Navigation.Commit", TracingController.Recorder.nowUs(), -1, args("url", uri));
				if (c != null) {
					// Chromium reports a page as started when its navigation commits
					c.onPageStarted(this, uri, null);
					c.doUpdateVisitedHistory(this, uri, reload);
				}
				final String committed = uri;
				runAfterNextFrame(new Runnable() {
					public void run() {
						WebViewClient cc = client();
						if (cc != null && !mDestroyed)
							cc.onPageCommitVisible(WebView.this, committed);
					}
				});
				break;
			}
			case LOAD_FINISHED:
				mLoading = false;
				if (tracer().isTracing()) {
					long now = TracingController.Recorder.nowUs();
					tracer().record("navigation", "Navigation", mLoadStartUs, now - mLoadStartUs, args("url", uri));
				}
				if (mWebChromeClient != null)
					mWebChromeClient.onProgressChanged(this, 100);
				if (c != null)
					c.onPageFinished(this, uri);
				break;
		}
	}

	void internalUriChanged(String uri) {
		// same-document navigations (fragment, pushState) change the URI without a load
		if (!mLoading && uri != null) {
			uri = toAppUrl(uri);
			mLastCommittedUrl = uri;
			WebViewClient c = client();
			if (c != null)
				c.doUpdateVisitedHistory(this, uri, false);
		}
	}

	private static final int CHROMIUM_UNKNOWN = WebViewClient.ERROR_UNKNOWN;

	void internalLoadFailed(String uri, String domain, int code, String message) {
		int error;
		String description;
		if ("WebKitNetworkError".equals(domain)) {
			switch (code) {
				case 302: return; // cancelled: a new load replaced this one, or stopLoading
				case 301: error = WebViewClient.ERROR_UNSUPPORTED_SCHEME; description = "net::ERR_UNKNOWN_URL_SCHEME"; break;
				case 303: error = WebViewClient.ERROR_FILE_NOT_FOUND; description = "net::ERR_FILE_NOT_FOUND"; break;
				case 300: error = WebViewClient.ERROR_CONNECT; description = "net::ERR_CONNECTION_FAILED"; break;
				default: error = CHROMIUM_UNKNOWN; description = "net::ERR_FAILED"; break;
			}
		} else if ("WebKitPolicyError".equals(domain)) {
			switch (code) {
				case 100: // the response became a download (DownloadListener)
				case 102: // the navigation was taken over by shouldOverrideUrlLoading or a policy
					return;
				case 101: error = WebViewClient.ERROR_UNSUPPORTED_SCHEME; description = "net::ERR_UNKNOWN_URL_SCHEME"; break;
				case 103: error = CHROMIUM_UNKNOWN; description = "net::ERR_UNSAFE_PORT"; break;
				default: error = CHROMIUM_UNKNOWN; description = "net::ERR_BLOCKED_BY_CLIENT"; break;
			}
		} else if ("WebKitDownloadError".equals(domain)) {
			return;
		} else if ("g-resolver-error-quark".equals(domain)) {
			error = WebViewClient.ERROR_HOST_LOOKUP;
			description = "net::ERR_NAME_NOT_RESOLVED";
		} else if ("g-tls-error-quark".equals(domain)) {
			error = WebViewClient.ERROR_FAILED_SSL_HANDSHAKE;
			description = "net::ERR_SSL_PROTOCOL_ERROR";
		} else if ("g-io-error-quark".equals(domain)) {
			switch (code) {
				case 19: return; // G_IO_ERROR_CANCELLED
				case 24: error = WebViewClient.ERROR_TIMEOUT; description = "net::ERR_TIMED_OUT"; break;
				case 39: error = WebViewClient.ERROR_CONNECT; description = "net::ERR_CONNECTION_REFUSED"; break;
				case 37: case 38: error = WebViewClient.ERROR_CONNECT; description = "net::ERR_ADDRESS_UNREACHABLE"; break;
				case 1: error = WebViewClient.ERROR_FILE_NOT_FOUND; description = "net::ERR_FILE_NOT_FOUND"; break;
				case 14: error = CHROMIUM_UNKNOWN; description = "net::ERR_ACCESS_DENIED"; break;
				default: error = WebViewClient.ERROR_IO; description = "net::ERR_FAILED"; break;
			}
		} else if (domain != null && domain.startsWith("soup")) {
			if (code == 2 || code == 3) {
				error = WebViewClient.ERROR_REDIRECT_LOOP;
				description = "net::ERR_TOO_MANY_REDIRECTS";
			} else {
				error = WebViewClient.ERROR_IO;
				description = "net::ERR_INVALID_RESPONSE";
			}
		} else {
			error = CHROMIUM_UNKNOWN;
			description = message != null ? message : "net::ERR_FAILED";
		}
		reportMainFrameError(uri, error, description);
	}

	private void reportMainFrameError(String uri, int error, String description) {
		uri = toAppUrl(uri);
		if (tracer().isTracing())
			tracer().record("navigation", "Navigation.Error", TracingController.Recorder.nowUs(), -1, args("url", uri, "error", description));
		WebViewClient c = client();
		if (c == null)
			return;
		c.onReceivedError(this, new WebResourceRequest.Impl(uri, true, false, false, "GET", null),
		                  new WebResourceError.Impl(error, description));
		if (!mCommitted) {
			// Chromium commits an error page for a failed main-frame load, so the app sees it start
			mCommitted = true;
			c.onPageStarted(this, uri, null);
		}
	}

	void internalTlsError(String uri, int flags) {
		/* GTlsCertificateFlags -> the matching net::ERR_CERT_* */
		String description;
		if ((flags & 0x02) != 0)
			description = "net::ERR_CERT_COMMON_NAME_INVALID";
		else if ((flags & 0x04) != 0 || (flags & 0x08) != 0)
			description = "net::ERR_CERT_DATE_INVALID";
		else if ((flags & 0x10) != 0)
			description = "net::ERR_CERT_REVOKED";
		else if ((flags & 0x01) != 0)
			description = "net::ERR_CERT_AUTHORITY_INVALID";
		else
			description = "net::ERR_CERT_INVALID";
		reportMainFrameError(uri, WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, description);
	}

	/* returns true to ignore the navigation */
	boolean internalDecideNavigation(String uri, String method, boolean redirect, boolean gesture, int navType, boolean newWindow) {
		if (uri == null)
			return false;
		String appUrl = toAppUrl(uri);
		if (uri.startsWith("file:") && !mSettings.getAllowFileAccess()) {
			reportMainFrameError(uri, CHROMIUM_UNKNOWN, "net::ERR_ACCESS_DENIED");
			return true;
		}
		if ((uri.startsWith("http:") || uri.startsWith("https:")) && mSettings.getBlockNetworkLoads()) {
			reportMainFrameError(uri, CHROMIUM_UNKNOWN, "net::ERR_CACHE_MISS");
			return true;
		}
		if (uri.equals(mApiLoadUrl) && !redirect) {
			// loads started through the API are not offered to shouldOverrideUrlLoading
			mApiLoadUrl = null;
			return false;
		}
		if (navType == NAV_BACK_FORWARD || navType == NAV_RELOAD || navType == NAV_FORM_RESUBMITTED)
			return false;
		if (method != null && method.equalsIgnoreCase("POST"))
			return false;
		if (uri.startsWith("javascript:") || uri.startsWith("about:"))
			return false;
		return shouldOverride(appUrl, redirect, gesture);
	}

	private boolean shouldOverride(String url, boolean redirect, boolean gesture) {
		WebViewClient c = client();
		WebResourceRequest req = new WebResourceRequest.Impl(url, true, redirect, gesture, "GET", null);
		if (c != null)
			return c.shouldOverrideUrlLoading(this, req);
		/* No client: AOSP lets the system choose a handler for the URL (Chromium's NullWebViewClient),
		 * and loads it in the WebView if no other app takes it. */
		Intent intent;
		try {
			intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
		} catch (Exception e) {
			return false;
		}
		intent.addCategory(Intent.CATEGORY_BROWSABLE);
		intent.setComponent(null);
		intent.putExtra("com.android.browser.application_id", getContext().getPackageName());
		try {
			getContext().startActivity(intent);
			return true;
		} catch (ActivityNotFoundException e) {
			return false;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/* returns true to ignore the response (it became a download) */
	boolean internalDecideResponse(String uri, String mime, long length, String disposition, int status, String reason,
	                               boolean mainFrame, boolean mimeSupported, String[] headers) {
		String appUrl = toAppUrl(uri);
		if (status >= 400) {
			WebViewClient c = client();
			if (c != null) {
				Map<String, String> h = new HashMap<String, String>();
				for (int i = 0; headers != null && i + 1 < headers.length; i += 2)
					h.put(headers[i], headers[i + 1]);
				WebResourceResponse resp;
				try {
					resp = new WebResourceResponse(mime, null, status, reason != null && !reason.trim().isEmpty() ? reason : "Error", h, null);
				} catch (IllegalArgumentException e) {
					resp = new WebResourceResponse(mime, null, null);
					resp.setResponseHeaders(h);
				}
				c.onReceivedHttpError(this, new WebResourceRequest.Impl(appUrl, mainFrame, false, false, "GET", null), resp);
			}
		}
		boolean attachment = disposition != null && disposition.trim().toLowerCase(java.util.Locale.ROOT).startsWith("attachment");
		if (!mimeSupported || attachment) {
			if (mDownloadListener != null)
				mDownloadListener.onDownloadStart(appUrl, mSettings.getUserAgentString(), disposition, mime, length);
			return true;
		}
		return false;
	}

	long internalCreateWindow(final String uri, boolean gesture) {
		if (!mSettings.supportMultipleWindows()) {
			// without multiple windows the target loads in this view, as a navigation of the page
			if (uri != null && !uri.isEmpty() && !uri.equals("about:blank") && !shouldOverride(toAppUrl(uri), false, gesture)) {
				new Handler(mLooper).post(new Runnable() {
					public void run() {
						if (mEngine != 0) {
							mApiLoadUrl = uri;
							nativeLoadUri(mEngine, uri, null);
						}
					}
				});
			}
			return 0;
		}
		WebChromeClient chrome = mWebChromeClient;
		if (chrome == null)
			return 0;
		final WebViewTransport transport = new WebViewTransport();
		Handler handler = new Handler(mLooper) {
			@Override
			public void handleMessage(Message msg) {
				WebView child = ((WebViewTransport)msg.obj).getWebView();
				if (child == null)
					return;
				if (child == WebView.this)
					throw new IllegalArgumentException("Parent WebView cannot host its own popup window. Please use WebSettings.setSupportMultipleWindows(false)");
				if (child.copyBackForwardList().getSize() > 0)
					throw new IllegalArgumentException("New WebView for popup window must not have been previously navigated.");
				// the popup's content is loaded into the app's new WebView (WebKit's own new window,
				// with its window.opener link, is not created)
				if (uri != null && !uri.isEmpty() && !uri.equals("about:blank"))
					child.loadUrl(toAppUrl(uri));
			}
		};
		Message msg = handler.obtainMessage(0, transport);
		chrome.onCreateWindow(this, false, gesture, msg);
		return 0;
	}

	void internalCloseWindow() {
		if (mWebChromeClient != null)
			mWebChromeClient.onCloseWindow(this);
	}

	void internalTitleChanged(String title) {
		if (mWebChromeClient != null)
			mWebChromeClient.onReceivedTitle(this, title != null && !title.isEmpty() ? title : getUrl());
	}

	void internalProgressChanged(int progress) {
		if (mWebChromeClient != null && mLoading)
			mWebChromeClient.onProgressChanged(this, progress);
	}

	void internalScriptDialog(final long dialog, int type, String message, String defaultText) {
		if (type == DIALOG_PROMPT && mBridgeToken != null && mBridgeToken.equals(message)) {
			bridgeCall(dialog, defaultText);
			return;
		}
		final boolean[] done = new boolean[1];
		JsResult.ResultReceiver receiver = new JsResult.ResultReceiver() {
			public void onJsResultComplete(final JsResult result) {
				Runnable finish = new Runnable() {
					public void run() {
						if (done[0])
							return;
						done[0] = true;
						String text = result instanceof JsPromptResult ? ((JsPromptResult)result).getStringResult() : null;
						nativeScriptDialogClose(dialog, result.getResult(), result.getResult() && text == null && result instanceof JsPromptResult ? "" : text);
					}
				};
				if (Looper.myLooper() == mLooper)
					finish.run();
				else
					new Handler(mLooper).post(finish);
			}
		};
		String url = getUrl();
		WebChromeClient chrome = mWebChromeClient;
		if (chrome == null) {
			// Chromium suppresses dialogs without a WebChromeClient: alert returns, confirm and
			// prompt are cancelled, leaving the page (beforeunload) proceeds
			nativeScriptDialogClose(dialog, type == DIALOG_BEFORE_UNLOAD, null);
			return;
		}
		boolean handled;
		JsResult result;
		switch (type) {
			case DIALOG_ALERT:
				result = new JsResult(receiver);
				handled = chrome.onJsAlert(this, url, message, result);
				break;
			case DIALOG_CONFIRM:
				result = new JsResult(receiver);
				handled = chrome.onJsConfirm(this, url, message, result);
				break;
			case DIALOG_PROMPT:
				result = new JsPromptResult(receiver);
				handled = chrome.onJsPrompt(this, url, message, defaultText, (JsPromptResult)result);
				break;
			default:
				result = new JsResult(receiver);
				handled = chrome.onJsBeforeUnload(this, url, message, result);
				break;
		}
		if (!handled)
			showDefaultJsDialog(type, url, message, defaultText, result);
	}

	/* AOSP's JsDialogHelper: the dialog Chromium shows when the app does not handle one */
	private void showDefaultJsDialog(int type, String url, String message, String defaultValue, final JsResult result) {
		String title;
		Uri u = url != null ? Uri.parse(url) : null;
		if (u != null && u.getScheme() != null && u.getHost() != null && !"file".equals(u.getScheme()))
			title = "The page at \"" + u.getScheme() + "://" + u.getHost() + "\" says:";
		else
			title = "JavaScript";
		AlertDialog.Builder b = new AlertDialog.Builder(getContext());
		b.setTitle(type == DIALOG_BEFORE_UNLOAD ? "Confirm Navigation" : title);
		b.setMessage(type == DIALOG_BEFORE_UNLOAD ? message + "\n\nAre you sure you want to navigate away from this page?" : message);
		final EditText edit = type == DIALOG_PROMPT ? new EditText(getContext()) : null;
		if (edit != null) {
			edit.setText(defaultValue != null ? defaultValue : "");
			b.setView(edit);
		}
		b.setPositiveButton(type == DIALOG_BEFORE_UNLOAD ? "Leave this Page" : "OK", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				if (result instanceof JsPromptResult)
					((JsPromptResult)result).confirm(edit != null ? edit.getText().toString() : "");
				else
					result.confirm();
			}
		});
		if (type != DIALOG_ALERT) {
			b.setNegativeButton(type == DIALOG_BEFORE_UNLOAD ? "Stay on this Page" : "Cancel", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					result.cancel();
				}
			});
		}
		b.setOnCancelListener(new DialogInterface.OnCancelListener() {
			public void onCancel(DialogInterface dialog) {
				result.cancel();
			}
		});
		b.show();
	}

	/* kinds from the native side: 1 geolocation, 2 camera, 3 microphone, 4 both, 5 protected media */
	void internalPermissionRequest(final long request, int kind) {
		final String origin = originOf(getUrl());
		final boolean[] done = new boolean[1];
		final Runnable deny = new Runnable() {
			public void run() {
				if (!done[0]) {
					done[0] = true;
					nativePermissionFinish(request, false);
				}
			}
		};
		WebChromeClient chrome = mWebChromeClient;
		if (kind == 1) {
			if (!mSettings.getGeolocationEnabled() || origin == null) {
				deny.run();
				return;
			}
			Boolean retained = GeolocationPermissions.getInstance().retained(origin);
			if (retained != null) {
				done[0] = true;
				nativePermissionFinish(request, retained);
				return;
			}
			if (chrome == null) {
				deny.run();
				return;
			}
			chrome.onGeolocationPermissionsShowPrompt(origin, new GeolocationPermissions.Callback() {
				public void invoke(String o, final boolean allow, boolean retain) {
					if (retain)
						GeolocationPermissions.getInstance().retain(o != null ? o : origin, allow);
					new Handler(mLooper).post(new Runnable() {
						public void run() {
							if (!done[0]) {
								done[0] = true;
								nativePermissionFinish(request, allow);
							}
						}
					});
				}
			});
			return;
		}
		final String[] resources;
		switch (kind) {
			case 2: resources = new String[] {PermissionRequest.RESOURCE_VIDEO_CAPTURE}; break;
			case 3: resources = new String[] {PermissionRequest.RESOURCE_AUDIO_CAPTURE}; break;
			case 4: resources = new String[] {PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE}; break;
			case 5: resources = new String[] {PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID}; break;
			default:
				// a permission Android WebView has no API for (notifications, pointer lock, ...): denied
				deny.run();
				return;
		}
		if (chrome == null || origin == null) {
			deny.run();
			return;
		}
		chrome.onPermissionRequest(new PermissionRequest() {
			public Uri getOrigin() {
				return Uri.parse(origin + "/");
			}

			public String[] getResources() {
				return resources.clone();
			}

			public void grant(String[] granted) {
				final boolean all = granted != null && Arrays.asList(granted).containsAll(Arrays.asList(resources));
				new Handler(mLooper).post(new Runnable() {
					public void run() {
						if (!done[0]) {
							done[0] = true;
							nativePermissionFinish(request, all);
						}
					}
				});
			}

			public void deny() {
				new Handler(mLooper).post(deny);
			}
		});
	}

	private static String originOf(String url) {
		if (url == null)
			return null;
		Uri u = Uri.parse(url);
		if (u.getScheme() == null || u.getHost() == null)
			return null;
		return u.getScheme() + "://" + u.getHost() + (u.getPort() != -1 ? ":" + u.getPort() : "");
	}

	void internalFileChooser(final long request, String[] mimeTypes, boolean multiple) {
		final boolean[] done = new boolean[1];
		WebChromeClient chrome = mWebChromeClient;
		ValueCallback<Uri[]> callback = new ValueCallback<Uri[]>() {
			public void onReceiveValue(final Uri[] uris) {
				final String[] paths = uris != null ? localPaths(uris) : null;
				new Handler(mLooper).post(new Runnable() {
					public void run() {
						if (done[0])
							throw new IllegalStateException("Duplicate showFileChooser result");
						done[0] = true;
						nativeFileChooserFinish(request, paths);
					}
				});
			}
		};
		boolean handled = chrome != null && chrome.onShowFileChooser(this, callback, new WebChromeClient.FileChooserParamsImpl(mimeTypes, multiple));
		if (!handled && !done[0]) {
			done[0] = true;
			nativeFileChooserFinish(request, null);
		}
	}

	/* WebKit uploads local files: content: URIs are copied into cache/WebView/uploads */
	private String[] localPaths(Uri[] uris) {
		ArrayList<String> out = new ArrayList<String>();
		for (Uri u : uris) {
			if (u == null)
				continue;
			if ("file".equals(u.getScheme()) && u.getPath() != null) {
				out.add(u.getPath());
				continue;
			}
			try (InputStream in = getContext().getContentResolver().openInputStream(u)) {
				if (in == null)
					continue;
				File dir = new File(new File(getContext().getCacheDir(), "WebView"), "uploads");
				dir.mkdirs();
				String name = u.getLastPathSegment();
				if (name == null || name.isEmpty() || name.contains("/"))
					name = "upload";
				File f = new File(dir, System.nanoTime() + "-" + name);
				try (FileOutputStream os = new FileOutputStream(f)) {
					byte[] buf = new byte[65536];
					int n;
					while ((n = in.read(buf)) > 0)
						os.write(buf, 0, n);
				}
				out.add(f.getAbsolutePath());
			} catch (IOException | RuntimeException e) {
				Log.w(LOGTAG, "cannot read " + u + " for upload: " + e);
			}
		}
		return out.toArray(new String[0]);
	}

	static void finishAuth(final long request, final String user, final String password) {
		if (Looper.myLooper() == Looper.getMainLooper())
			nativeAuthFinish(request, user, password);
		else {
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				public void run() {
					nativeAuthFinish(request, user, password);
				}
			});
		}
	}

	void internalAuthenticate(long request, String host, String realm, boolean retry) {
		WebViewClient c = client();
		HttpAuthHandler handler = new HttpAuthHandler.Impl(request, retry);
		if (c != null)
			c.onReceivedHttpAuthRequest(this, handler, host, realm);
		else
			handler.cancel();
	}

	void internalProcessTerminated(int reason) {
		mRendererGone = true;
		boolean crashed = reason == PROCESS_CRASHED;
		if (tracer().isTracing())
			tracer().record("android_webview", "RenderProcessGone", TracingController.Recorder.nowUs(), -1, args("crashed", crashed));
		WebViewClient c = client();
		boolean handled = c != null && c.onRenderProcessGone(this, new RenderProcessGoneDetail.Impl(crashed, RENDERER_PRIORITY_IMPORTANT));
		if (!handled) {
			// as AOSP: an unhandled renderer exit takes the app down
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				public void run() {
					throw new RuntimeException("Render process's crash wasn't handled by all associated webviews, triggering application crash.");
				}
			});
		}
	}

	void internalResponsiveChanged(final boolean responsive) {
		final WebViewRenderProcessClient rc = mRenderProcessClient;
		if (rc == null)
			return;
		final WebViewRenderProcess process = getWebViewRenderProcess();
		Runnable r = new Runnable() {
			public void run() {
				if (responsive)
					rc.onRenderProcessResponsive(WebView.this, process);
				else
					rc.onRenderProcessUnresponsive(WebView.this, process);
			}
		};
		if (mRenderProcessClientExecutor != null)
			mRenderProcessClientExecutor.execute(r);
		else
			r.run();
	}

	void internalEvalResult(int id, String json) {
		ValueCallback<String> cb = mEvalCallbacks.get(id);
		mEvalCallbacks.remove(id);
		if (cb != null)
			cb.onReceiveValue(json);
	}

	/* content:// for the engine: {byte[] data, String mimeType}, or null when access is not allowed */
	Object[] internalServeContent(String uri) {
		if (!mSettings.getAllowContentAccess())
			return null;
		Uri u = Uri.parse(uri);
		try (InputStream in = getContext().getContentResolver().openInputStream(u)) {
			if (in == null)
				return null;
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0)
				bos.write(buf, 0, n);
			String mime = getContext().getContentResolver().getType(u);
			return new Object[] {bos.toByteArray(), mime != null ? mime : "application/octet-stream"};
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/* ---- natives (api-impl-jni/widgets/android_webkit_WebView.c) ---- */

	@Override
	protected native long native_constructor(Context context, AttributeSet attrs);
	private native long nativeCreateEngine(long holder, long related);
	private native void nativeEvaluateJavascript(long engine, String script, int id, boolean asyncFunction);
	static native void nativeInitSession(String dataDir, String cacheDir, int cookiePolicy);
	static native void nativeDestroy(long holder, long engine);
	static native void nativeLoadUri(long engine, String uri, String[] headers);
	static native void nativeLoadData(long engine, byte[] data, String mimeType, String encoding, String baseUri);
	static native void nativeStopLoading(long engine);
	static native void nativeReload(long engine, boolean bypassCache);
	static native String nativeGetUri(long engine);
	static native String nativeGetTitle(long engine);
	static native int nativeGetProgress(long engine);
	static native long[] nativeHistoryItems(long engine);
	static native String nativeItemString(long item, int what);
	static native void nativeItemRef(long item, boolean ref);
	static native void nativeGoToItem(long engine, long item);
	static native byte[] nativeSaveState(long engine);
	static native boolean nativeRestoreState(long engine, byte[] state);
	static native void nativeSetUserScript(long engine, String script);
	static native boolean nativeSetBoolSetting(long engine, String name, boolean value);
	static native boolean nativeSetIntSetting(long engine, String name, int value);
	static native boolean nativeSetStringSetting(long engine, String name, String value);
	static native void nativeSetZoom(long engine, double zoom);
	static native void nativeSetBackgroundColor(long engine, int argb);
	static native void nativeTerminateWebProcess(long engine);
	static native void nativeClearWebsiteData(int kind, int id);
	static native void nativeScriptDialogClose(long dialog, boolean confirmed, String text);
	static native void nativePermissionFinish(long request, boolean allow);
	static native void nativeFileChooserFinish(long request, String[] paths);
	static native void nativeAuthFinish(long request, String user, String password);
}
