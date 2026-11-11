/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.content.Context;
import android.os.Build;

/**
 * Manages settings state for a WebView. When a WebView is first created, it
 * obtains a set of default settings. These default settings will be returned
 * from any getter call. A {@code WebSettings} object obtained from
 * {@link WebView#getSettings()} is tied to the life of the WebView. If a WebView has
 * been destroyed, any method call on {@code WebSettings} will throw an
 * {@link IllegalStateException}.
 *
 * <p>teslalinux: the defaults are AOSP's (Chromium's AwSettings). Each setting the engine knows is
 * pushed to the WebView's WebKitSettings as it changes (and all of them when the engine is created);
 * the WebView itself enforces the ones WebKitGTK has no switch for (file and content access,
 * network blocking, geolocation, multiple windows, cache mode for API loads). The comments on the
 * remaining ones say what WebKitGTK lacks.
 */
public class WebSettings {
	/**
	 * Enum for controlling the layout of html.
	 */
	public enum LayoutAlgorithm {
		/**
		 * NORMAL means no rendering changes. This is the recommended choice for maximum
		 * compatibility across different platforms and Android versions.
		 */
		NORMAL,
		/**
		 * SINGLE_COLUMN moves all content into one column that is the width of the
		 * view.
		 * @deprecated This algorithm is now obsolete.
		 */
		@Deprecated SINGLE_COLUMN,
		/**
		 * NARROW_COLUMNS makes all columns no wider than the screen if possible. Only use
		 * this for API levels prior to {@link android.os.Build.VERSION_CODES#KITKAT}.
		 * @deprecated This algorithm is now obsolete.
		 */
		@Deprecated NARROW_COLUMNS,
		/**
		 * TEXT_AUTOSIZING boosts font size of paragraphs based on heuristics to make
		 * the text readable when viewing a wide-viewport layout in the overview mode.
		 */
		TEXT_AUTOSIZING
	}

	/**
	 * Enum for specifying the text size.
	 * @deprecated Use {@link WebSettings#setTextZoom(int)} and {@link WebSettings#getTextZoom()} instead.
	 */
	@Deprecated
	public enum TextSize {
		SMALLEST(50),
		SMALLER(75),
		NORMAL(100),
		LARGER(150),
		LARGEST(200);
		TextSize(int size) {
			value = size;
		}
		int value;
	}

	/**
	 * Enum for specifying the WebView's desired density.
	 * @deprecated This class is obsolete.
	 */
	@Deprecated
	public enum ZoomDensity {
		FAR(150), // 240dpi
		MEDIUM(100), // 160dpi
		CLOSE(75); // 120dpi
		ZoomDensity(int size) {
			value = size;
		}
		public int getValue() {
			return value;
		}
		int value;
	}

	/**
	 * The plugin state.
	 * @deprecated Plugins are not supported in API level
	 *             {@link android.os.Build.VERSION_CODES#KITKAT} or later;
	 */
	@Deprecated
	public enum PluginState {
		ON,
		ON_DEMAND,
		OFF
	}

	/**
	 * @deprecated This class is obsolete.
	 */
	@Deprecated
	public enum RenderPriority {
		NORMAL,
		HIGH,
		LOW
	}

	/** Default cache usage mode. */
	public static final int LOAD_DEFAULT = -1;
	/** @deprecated This value is obsolete. */
	@Deprecated
	public static final int LOAD_NORMAL = 0;
	/** Use cached resources when they are available, even if they have expired. */
	public static final int LOAD_CACHE_ELSE_NETWORK = 1;
	/** Don't use the cache, load from the network. */
	public static final int LOAD_NO_CACHE = 2;
	/** Don't use the network, load from the cache. */
	public static final int LOAD_CACHE_ONLY = 3;

	/** Always allow a secure origin to load content from any other origin. */
	public static final int MIXED_CONTENT_ALWAYS_ALLOW = 0;
	/** Never allow a secure origin to load content from an insecure origin. */
	public static final int MIXED_CONTENT_NEVER_ALLOW = 1;
	/** The WebView will attempt to be compatible with the approach of a modern web browser. */
	public static final int MIXED_CONTENT_COMPATIBILITY_MODE = 2;

	/** Disable force dark. @deprecated */
	@Deprecated
	public static final int FORCE_DARK_OFF = 0;
	/** Enable force dark dependent on the state of the WebView parent view. @deprecated */
	@Deprecated
	public static final int FORCE_DARK_AUTO = 1;
	/** Unconditionally enable force dark. @deprecated */
	@Deprecated
	public static final int FORCE_DARK_ON = 2;

	/** Used with {@link #setDisabledActionModeMenuItems}. No menu items should be disabled. */
	public static final int MENU_ITEM_NONE = 0;
	/** Disable menu item "Share". */
	public static final int MENU_ITEM_SHARE = 1 << 0;
	/** Disable menu item "Web Search". */
	public static final int MENU_ITEM_WEB_SEARCH = 1 << 1;
	/** Disable all the action mode menu items for text processing. */
	public static final int MENU_ITEM_PROCESS_TEXT = 1 << 2;

	private final WebView mView;
	private boolean mDestroyed;

	/* Chromium AwSettings defaults */
	private boolean mJavaScriptEnabled = false;
	private boolean mJavaScriptCanOpenWindowsAutomatically = false;
	private boolean mDomStorageEnabled = false;
	private boolean mDatabaseEnabled = false;
	private boolean mLoadsImagesAutomatically = true;
	private boolean mBlockNetworkImage = false;
	private boolean mBlockNetworkLoads;
	private boolean mAllowFileAccess;
	private boolean mAllowContentAccess = true;
	private boolean mAllowFileAccessFromFileURLs;
	private boolean mAllowUniversalAccessFromFileURLs;
	private boolean mMediaPlaybackRequiresUserGesture = true;
	private boolean mGeolocationEnabled = true;
	private boolean mSupportMultipleWindows = false;
	private boolean mSupportZoom = true;
	private boolean mBuiltInZoomControls = false;
	private boolean mDisplayZoomControls = true;
	private boolean mUseWideViewPort = false;
	private boolean mLoadWithOverviewMode = false;
	private boolean mSaveFormData = true;
	private boolean mSavePassword = false;
	private boolean mOffscreenPreRaster = false;
	private boolean mSafeBrowsingEnabled = true;
	private boolean mNeedInitialFocus = true;
	private int mCacheMode = LOAD_DEFAULT;
	private int mMixedContentMode = MIXED_CONTENT_NEVER_ALLOW;
	private int mTextZoom = 100;
	private int mDefaultFontSize = 16;
	private int mDefaultFixedFontSize = 13;
	private int mMinimumFontSize = 8;
	private int mMinimumLogicalFontSize = 8;
	private int mForceDark = FORCE_DARK_AUTO;
	private int mDisabledActionModeMenuItems = MENU_ITEM_NONE;
	private LayoutAlgorithm mLayoutAlgorithm = LayoutAlgorithm.NARROW_COLUMNS;
	private String mUserAgent;
	private String mDefaultTextEncoding = "UTF-8";
	private String mStandardFontFamily = "sans-serif";
	private String mFixedFontFamily = "monospace";
	private String mSansSerifFontFamily = "sans-serif";
	private String mSerifFontFamily = "serif";
	private String mCursiveFontFamily = "cursive";
	private String mFantasyFontFamily = "fantasy";
	private String mDatabasePath = "";
	private String mGeolocationDatabasePath = "";
	private boolean mAppCacheEnabled;
	private String mAppCachePath = "";
	private long mAppCacheMaxSize = Long.MAX_VALUE;
	private RenderPriority mRenderPriority = RenderPriority.NORMAL;
	private ZoomDensity mDefaultZoom = ZoomDensity.MEDIUM;
	private PluginState mPluginState = PluginState.OFF;

	WebSettings(WebView view, Context context) {
		mView = view;
		int target = context.getApplicationInfo().targetSdkVersion;
		// AwSettings: file access is on by default below R, and file-URL access below JELLY_BEAN
		mAllowFileAccess = target < Build.VERSION_CODES.R;
		mAllowFileAccessFromFileURLs = target < Build.VERSION_CODES.JELLY_BEAN;
		mAllowUniversalAccessFromFileURLs = target < Build.VERSION_CODES.JELLY_BEAN;
		mMixedContentMode = target < Build.VERSION_CODES.LOLLIPOP ? MIXED_CONTENT_ALWAYS_ALLOW : MIXED_CONTENT_NEVER_ALLOW;
		// network loads are blocked by default only when the app lacks the INTERNET permission
		mBlockNetworkLoads = context.checkPermission(android.Manifest.permission.INTERNET, android.os.Process.myPid(), android.os.Process.myUid()) != android.content.pm.PackageManager.PERMISSION_GRANTED;
	}

	private void check() {
		if (mDestroyed)
			throw new IllegalStateException("WebView had been destroyed");
	}

	void destroy() {
		mDestroyed = true;
	}

	/** pushes every engine setting to a freshly created engine */
	void applyAll() {
		applyBool("enable-javascript", mJavaScriptEnabled);
		applyBool("javascript-can-open-windows-automatically", mJavaScriptCanOpenWindowsAutomatically);
		applyBool("enable-html5-local-storage", mDomStorageEnabled);
		applyBool("enable-html5-database", mDatabaseEnabled);
		applyImages();
		applyBool("allow-file-access-from-file-urls", mAllowFileAccessFromFileURLs);
		applyBool("allow-universal-access-from-file-urls", mAllowUniversalAccessFromFileURLs);
		applyBool("media-playback-requires-user-gesture", mMediaPlaybackRequiresUserGesture);
		applyBool("enable-developer-extras", WebView.sWebContentsDebuggingEnabled);
		applyString("user-agent", getUserAgentString());
		applyString("default-charset", mDefaultTextEncoding);
		applyFonts();
		applyInt("default-font-size", mDefaultFontSize);
		applyInt("default-monospace-font-size", mDefaultFixedFontSize);
		applyInt("minimum-font-size", mMinimumFontSize);
		applyTextZoom();
	}

	private void applyBool(String name, boolean v) {
		long e = mView.engine();
		if (e != 0)
			WebView.nativeSetBoolSetting(e, name, v);
	}

	private void applyInt(String name, int v) {
		long e = mView.engine();
		if (e != 0)
			WebView.nativeSetIntSetting(e, name, v);
	}

	private void applyString(String name, String v) {
		long e = mView.engine();
		if (e != 0 && v != null)
			WebView.nativeSetStringSetting(e, name, v);
	}

	/* WebKitGTK has one switch for all images; blockNetworkImage turns image loading off as a whole,
	 * which also stops data: and file: images Chromium would still show. */
	private void applyImages() {
		applyBool("auto-load-images", mLoadsImagesAutomatically && !mBlockNetworkImage);
	}

	private void applyFonts() {
		applyString("default-font-family", mStandardFontFamily);
		applyString("monospace-font-family", mFixedFontFamily);
		applyString("sans-serif-font-family", mSansSerifFontFamily);
		applyString("serif-font-family", mSerifFontFamily);
		applyString("cursive-font-family", mCursiveFontFamily);
		applyString("fantasy-font-family", mFantasyFontFamily);
	}

	/* text zoom scales text only, as Chromium's text zoom does */
	private void applyTextZoom() {
		long e = mView.engine();
		if (e != 0) {
			WebView.nativeSetBoolSetting(e, "zoom-text-only", true);
			WebView.nativeSetZoom(e, mTextZoom / 100.0);
		}
	}

	/**
	 * Enables dumping the pages navigation cache to a text file. The default is {@code false}.
	 * @deprecated This method is now obsolete.
	 * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
	 */
	@Deprecated
	public void setNavDump(boolean enabled) {
	}

	/**
	 * Sets whether the WebView should support zooming using its on-screen zoom
	 * controls and gestures. The default is {@code true}.
	 *
	 * teslalinux: WebKitGTK has no switch for its pinch gesture; the value is kept for the getter
	 * and pages still control zooming with the viewport meta tag.
	 */
	public synchronized void setSupportZoom(boolean support) {
		check();
		mSupportZoom = support;
	}

	public synchronized boolean supportZoom() {
		check();
		return mSupportZoom;
	}

	/**
	 * Sets whether the WebView requires a user gesture to play media.
	 * The default is {@code true}.
	 */
	public synchronized void setMediaPlaybackRequiresUserGesture(boolean require) {
		check();
		mMediaPlaybackRequiresUserGesture = require;
		applyBool("media-playback-requires-user-gesture", require);
	}

	public synchronized boolean getMediaPlaybackRequiresUserGesture() {
		check();
		return mMediaPlaybackRequiresUserGesture;
	}

	/**
	 * Sets whether the WebView should use its built-in zoom mechanisms. The default is {@code false}.
	 * teslalinux: WebKitGTK has no zoom control widgets; pinch zoom is its own (kept for the getter).
	 */
	public synchronized void setBuiltInZoomControls(boolean enabled) {
		check();
		mBuiltInZoomControls = enabled;
	}

	public synchronized boolean getBuiltInZoomControls() {
		check();
		return mBuiltInZoomControls;
	}

	/**
	 * Sets whether the WebView should display on-screen zoom controls when using the built-in zoom
	 * mechanisms. The default is {@code true}. teslalinux: there are no on-screen zoom controls in
	 * WebKitGTK, as on Chromium WebView today (deprecated controls); the value is kept for the getter.
	 */
	public synchronized void setDisplayZoomControls(boolean enabled) {
		check();
		mDisplayZoomControls = enabled;
	}

	public synchronized boolean getDisplayZoomControls() {
		check();
		return mDisplayZoomControls;
	}

	/**
	 * Enables or disables file access within WebView. Note that this enables or disables file
	 * system access only. Assets and resources are still accessible using file:///android_asset and
	 * file:///android_res. The default is {@code true} for apps targeting below R, else {@code false}.
	 */
	public synchronized void setAllowFileAccess(boolean allow) {
		check();
		mAllowFileAccess = allow;
	}

	public synchronized boolean getAllowFileAccess() {
		check();
		return mAllowFileAccess;
	}

	/**
	 * Enables or disables content URL access within WebView. Content URL access allows WebView to
	 * load content from a content provider installed in the system. The default is enabled.
	 */
	public synchronized void setAllowContentAccess(boolean allow) {
		check();
		mAllowContentAccess = allow;
	}

	public synchronized boolean getAllowContentAccess() {
		check();
		return mAllowContentAccess;
	}

	/**
	 * Sets whether the WebView loads pages in overview mode, that is, zooms out the content to fit
	 * on screen by width. The default is {@code false}. teslalinux: WebKitGTK lays pages out at the
	 * view width and has no overview mode; the value is kept for the getter.
	 */
	public synchronized void setLoadWithOverviewMode(boolean overview) {
		check();
		mLoadWithOverviewMode = overview;
	}

	public synchronized boolean getLoadWithOverviewMode() {
		check();
		return mLoadWithOverviewMode;
	}

	/**
	 * Sets whether the WebView will enable smooth transition while panning or zooming.
	 * @deprecated This method is now obsolete, and will become a no-op in future.
	 */
	@Deprecated
	public void setEnableSmoothTransition(boolean enable) {
	}

	@Deprecated
	public boolean enableSmoothTransition() {
		return false;
	}

	/**
	 * Sets whether the WebView uses its background for over scroll background.
	 * @deprecated This method is now obsolete.
	 * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
	 */
	@Deprecated
	public void setUseWebViewBackgroundForOverscrollBackground(boolean view) {
	}

	/**
	 * Sets whether the WebView should save form data. In Android O, the platform has implemented a
	 * fully functional Autofill feature to store form data. Therefore, the Webview form data save
	 * feature is disabled. Note that the feature will continue to be supported on older versions of
	 * Android as before. (Chromium keeps the value only.)
	 * @deprecated In Android O and afterwards, this function does not have any effect.
	 */
	@Deprecated
	public synchronized void setSaveFormData(boolean save) {
		check();
		mSaveFormData = save;
	}

	@Deprecated
	public synchronized boolean getSaveFormData() {
		check();
		return mSaveFormData;
	}

	/**
	 * Sets whether the WebView should save passwords. The default is {@code true}.
	 * @deprecated Saving passwords in WebView will not be supported in future versions.
	 *             (Chromium ignores it.)
	 */
	@Deprecated
	public synchronized void setSavePassword(boolean save) {
		check();
		mSavePassword = save;
	}

	@Deprecated
	public synchronized boolean getSavePassword() {
		check();
		return mSavePassword;
	}

	/**
	 * Sets the text zoom of the page in percent. The default is 100.
	 */
	public synchronized void setTextZoom(int textZoom) {
		check();
		mTextZoom = textZoom;
		applyTextZoom();
	}

	public synchronized int getTextZoom() {
		check();
		return mTextZoom;
	}

	/**
	 * Sets policy for third party cookies.
	 * Developers should access this via {@link CookieManager#setShouldAcceptThirdPartyCookies}.
	 * @hide Internal API.
	 */
	public void setAcceptThirdPartyCookies(boolean accept) {
		CookieManager.getInstance().setAcceptThirdPartyCookies(mView, accept);
	}

	/**
	 * Gets policy for third party cookies.
	 * @hide Internal API
	 */
	public boolean getAcceptThirdPartyCookies() {
		return CookieManager.getInstance().acceptThirdPartyCookies(mView);
	}

	/**
	 * Sets the text size of the page. The default is {@link TextSize#NORMAL}.
	 * @deprecated Use {@link #setTextZoom} instead.
	 */
	@Deprecated
	public synchronized void setTextSize(TextSize t) {
		setTextZoom(t.value);
	}

	/**
	 * Gets the text size of the page. If the text size was previously specified
	 * in percent using {@link #setTextZoom}, this will return the closest
	 * matching {@link TextSize}.
	 * @deprecated Use {@link #getTextZoom} instead.
	 */
	@Deprecated
	public synchronized TextSize getTextSize() {
		TextSize closestSize = null;
		int smallestDelta = Integer.MAX_VALUE;
		int textSize = getTextZoom();
		for (TextSize size : TextSize.values()) {
			int delta = Math.abs(textSize - size.value);
			if (delta == 0) {
				return size;
			}
			if (delta < smallestDelta) {
				smallestDelta = delta;
				closestSize = size;
			}
		}
		return closestSize != null ? closestSize : TextSize.NORMAL;
	}

	/**
	 * Sets the default zoom density of the page.
	 * @deprecated This method is no longer supported, see the function documentation for
	 *             recommended alternatives (Chromium keeps the value only).
	 */
	@Deprecated
	public synchronized void setDefaultZoom(ZoomDensity zoom) {
		check();
		mDefaultZoom = zoom;
	}

	@Deprecated
	public synchronized ZoomDensity getDefaultZoom() {
		check();
		return mDefaultZoom;
	}

	/**
	 * Enables using light touches to make a selection and activate mouseovers.
	 * @deprecated From {@link android.os.Build.VERSION_CODES#JELLY_BEAN} this
	 *             setting is obsolete and has no effect.
	 */
	@Deprecated
	public void setLightTouchEnabled(boolean enabled) {
	}

	@Deprecated
	public boolean getLightTouchEnabled() {
		return false;
	}

	/**
	 * Sets whether the WebView should enable support for the &quot;viewport&quot;
	 * HTML meta tag or should use a wide viewport. The default is {@code false}.
	 * teslalinux: WebKitGTK always lays the page out at the view width (desktop behaviour, as
	 * Chromium with this off); the value is kept for the getter.
	 */
	public synchronized void setUseWideViewPort(boolean use) {
		check();
		mUseWideViewPort = use;
	}

	public synchronized boolean getUseWideViewPort() {
		check();
		return mUseWideViewPort;
	}

	/**
	 * Sets whether the WebView whether supports multiple windows. If set to
	 * true, {@link WebChromeClient#onCreateWindow} must be implemented by the
	 * host application. The default is {@code false}.
	 */
	public synchronized void setSupportMultipleWindows(boolean support) {
		check();
		mSupportMultipleWindows = support;
	}

	public synchronized boolean supportMultipleWindows() {
		check();
		return mSupportMultipleWindows;
	}

	/**
	 * Sets the underlying layout algorithm. The default is {@link LayoutAlgorithm#NARROW_COLUMNS}.
	 * teslalinux: WebKitGTK has no text autosizing or column narrowing (Chromium treats NARROW_COLUMNS
	 * as NORMAL too); the value is kept for the getter.
	 */
	public synchronized void setLayoutAlgorithm(LayoutAlgorithm l) {
		check();
		mLayoutAlgorithm = l;
	}

	public synchronized LayoutAlgorithm getLayoutAlgorithm() {
		check();
		return mLayoutAlgorithm;
	}

	public synchronized void setStandardFontFamily(String font) {
		check();
		mStandardFontFamily = font;
		applyString("default-font-family", font);
	}

	public synchronized String getStandardFontFamily() {
		check();
		return mStandardFontFamily;
	}

	public synchronized void setFixedFontFamily(String font) {
		check();
		mFixedFontFamily = font;
		applyString("monospace-font-family", font);
	}

	public synchronized String getFixedFontFamily() {
		check();
		return mFixedFontFamily;
	}

	public synchronized void setSansSerifFontFamily(String font) {
		check();
		mSansSerifFontFamily = font;
		applyString("sans-serif-font-family", font);
	}

	public synchronized String getSansSerifFontFamily() {
		check();
		return mSansSerifFontFamily;
	}

	public synchronized void setSerifFontFamily(String font) {
		check();
		mSerifFontFamily = font;
		applyString("serif-font-family", font);
	}

	public synchronized String getSerifFontFamily() {
		check();
		return mSerifFontFamily;
	}

	public synchronized void setCursiveFontFamily(String font) {
		check();
		mCursiveFontFamily = font;
		applyString("cursive-font-family", font);
	}

	public synchronized String getCursiveFontFamily() {
		check();
		return mCursiveFontFamily;
	}

	public synchronized void setFantasyFontFamily(String font) {
		check();
		mFantasyFontFamily = font;
		applyString("fantasy-font-family", font);
	}

	public synchronized String getFantasyFontFamily() {
		check();
		return mFantasyFontFamily;
	}

	private static int clampFontSize(int size) {
		return Math.max(1, Math.min(72, size));
	}

	/**
	 * Sets the minimum font size. The default is 8.
	 */
	public synchronized void setMinimumFontSize(int size) {
		check();
		mMinimumFontSize = clampFontSize(size);
		applyInt("minimum-font-size", mMinimumFontSize);
	}

	public synchronized int getMinimumFontSize() {
		check();
		return mMinimumFontSize;
	}

	/**
	 * Sets the minimum logical font size. The default is 8.
	 * teslalinux: WebKitGTK has only the absolute minimum (setMinimumFontSize); the logical one,
	 * which Chromium applies to zoomed text, is kept for the getter.
	 */
	public synchronized void setMinimumLogicalFontSize(int size) {
		check();
		mMinimumLogicalFontSize = clampFontSize(size);
	}

	public synchronized int getMinimumLogicalFontSize() {
		check();
		return mMinimumLogicalFontSize;
	}

	/**
	 * Sets the default font size. The default is 16.
	 */
	public synchronized void setDefaultFontSize(int size) {
		check();
		mDefaultFontSize = clampFontSize(size);
		applyInt("default-font-size", mDefaultFontSize);
	}

	public synchronized int getDefaultFontSize() {
		check();
		return mDefaultFontSize;
	}

	/**
	 * Sets the default fixed font size. The default is 16 in AOSP's docs, 13 in Chromium.
	 */
	public synchronized void setDefaultFixedFontSize(int size) {
		check();
		mDefaultFixedFontSize = clampFontSize(size);
		applyInt("default-monospace-font-size", mDefaultFixedFontSize);
	}

	public synchronized int getDefaultFixedFontSize() {
		check();
		return mDefaultFixedFontSize;
	}

	/**
	 * Sets whether the WebView should load image resources. The default is {@code true}.
	 */
	public synchronized void setLoadsImagesAutomatically(boolean flag) {
		check();
		mLoadsImagesAutomatically = flag;
		applyImages();
	}

	public synchronized boolean getLoadsImagesAutomatically() {
		check();
		return mLoadsImagesAutomatically;
	}

	/**
	 * Sets whether the WebView should not load image resources from the network. The default is
	 * {@code false}.
	 */
	public synchronized void setBlockNetworkImage(boolean flag) {
		check();
		mBlockNetworkImage = flag;
		applyImages();
	}

	public synchronized boolean getBlockNetworkImage() {
		check();
		return mBlockNetworkImage;
	}

	/**
	 * Sets whether the WebView should not load resources from the network. The default value is
	 * {@code false} if the app has the INTERNET permission, otherwise it is {@code true}.
	 *
	 * teslalinux: WebView refuses network navigations (any frame) while this is set; WebKitGTK gives
	 * the embedder no hook for subresource loads of an already loaded page.
	 *
	 * @throws SecurityException if the app does not have the INTERNET permission and tries to
	 *         unblock network loads.
	 */
	public synchronized void setBlockNetworkLoads(boolean flag) {
		check();
		if (!flag && mView.getContext().checkPermission(android.Manifest.permission.INTERNET, android.os.Process.myPid(), android.os.Process.myUid()) != android.content.pm.PackageManager.PERMISSION_GRANTED)
			throw new SecurityException("Permission denied - application missing INTERNET permission");
		mBlockNetworkLoads = flag;
	}

	public synchronized boolean getBlockNetworkLoads() {
		check();
		return mBlockNetworkLoads;
	}

	/**
	 * Tells WebView to enable JavaScript execution. The default is {@code false}.
	 */
	public synchronized void setJavaScriptEnabled(boolean flag) {
		check();
		mJavaScriptEnabled = flag;
		applyBool("enable-javascript", flag);
	}

	public synchronized boolean getJavaScriptEnabled() {
		check();
		return mJavaScriptEnabled;
	}

	/**
	 * Sets whether cross-origin requests in the context of a file scheme URL should be allowed to
	 * access content from any origin.
	 * @deprecated This setting is not secure.
	 */
	@Deprecated
	public synchronized void setAllowUniversalAccessFromFileURLs(boolean flag) {
		check();
		mAllowUniversalAccessFromFileURLs = flag;
		applyBool("allow-universal-access-from-file-urls", flag);
	}

	public synchronized boolean getAllowUniversalAccessFromFileURLs() {
		check();
		return mAllowUniversalAccessFromFileURLs;
	}

	/**
	 * Sets whether cross-origin requests in the context of a file scheme URL should be allowed to
	 * access content from other file scheme URLs.
	 * @deprecated This setting is not secure.
	 */
	@Deprecated
	public synchronized void setAllowFileAccessFromFileURLs(boolean flag) {
		check();
		mAllowFileAccessFromFileURLs = flag;
		applyBool("allow-file-access-from-file-urls", flag);
	}

	public synchronized boolean getAllowFileAccessFromFileURLs() {
		check();
		return mAllowFileAccessFromFileURLs;
	}

	/**
	 * Sets whether the WebView should enable plugins.
	 * @deprecated Plugins are not supported (Chromium keeps the value only).
	 */
	@Deprecated
	public synchronized void setPluginState(PluginState state) {
		check();
		mPluginState = state;
	}

	@Deprecated
	public synchronized PluginState getPluginState() {
		check();
		return mPluginState;
	}

	/**
	 * Sets the path to where database storage API databases should be saved.
	 * @deprecated Database paths are managed by the implementation and calling this method
	 *             will have no effect.
	 */
	@Deprecated
	public synchronized void setDatabasePath(String databasePath) {
		check();
		if (databasePath != null && mDatabasePath.isEmpty())
			mDatabasePath = databasePath;
	}

	@Deprecated
	public synchronized String getDatabasePath() {
		check();
		return mDatabasePath;
	}

	/**
	 * Sets the path where the Geolocation databases should be saved.
	 * @deprecated Geolocation database are managed by the implementation and calling this method
	 *             will have no effect.
	 */
	@Deprecated
	public synchronized void setGeolocationDatabasePath(String databasePath) {
		check();
		mGeolocationDatabasePath = databasePath;
	}

	/**
	 * Sets whether the Application Caches API should be enabled.
	 * @deprecated The Application Cache API is deprecated and this method will
	 *             become a no-op on all Android versions once support is
	 *             removed in Chromium (WebKitGTK has removed it too).
	 */
	@Deprecated
	public synchronized void setAppCacheEnabled(boolean flag) {
		check();
		mAppCacheEnabled = flag;
	}

	/**
	 * @deprecated See {@link #setAppCacheEnabled}.
	 */
	@Deprecated
	public synchronized void setAppCachePath(String appCachePath) {
		check();
		if (appCachePath != null && mAppCachePath.isEmpty())
			mAppCachePath = appCachePath;
	}

	/**
	 * @deprecated See {@link #setAppCacheEnabled}.
	 */
	@Deprecated
	public synchronized void setAppCacheMaxSize(long appCacheMaxSize) {
		check();
		mAppCacheMaxSize = appCacheMaxSize;
	}

	/**
	 * Sets whether the database storage API is enabled. The default value is false.
	 * @deprecated The Web SQL Database API is deprecated.
	 */
	@Deprecated
	public synchronized void setDatabaseEnabled(boolean flag) {
		check();
		mDatabaseEnabled = flag;
		applyBool("enable-html5-database", flag);
	}

	@Deprecated
	public synchronized boolean getDatabaseEnabled() {
		check();
		return mDatabaseEnabled;
	}

	/**
	 * Sets whether the DOM storage API is enabled. The default value is {@code false}.
	 */
	public synchronized void setDomStorageEnabled(boolean flag) {
		check();
		mDomStorageEnabled = flag;
		applyBool("enable-html5-local-storage", flag);
	}

	public synchronized boolean getDomStorageEnabled() {
		check();
		return mDomStorageEnabled;
	}

	/**
	 * Sets whether Geolocation is enabled. The default is {@code true}.
	 */
	public synchronized void setGeolocationEnabled(boolean flag) {
		check();
		mGeolocationEnabled = flag;
	}

	synchronized boolean getGeolocationEnabled() {
		return mGeolocationEnabled;
	}

	/**
	 * Tells JavaScript to open windows automatically. This applies to the
	 * JavaScript function {@code window.open()}. The default is {@code false}.
	 */
	public synchronized void setJavaScriptCanOpenWindowsAutomatically(boolean flag) {
		check();
		mJavaScriptCanOpenWindowsAutomatically = flag;
		applyBool("javascript-can-open-windows-automatically", flag);
	}

	public synchronized boolean getJavaScriptCanOpenWindowsAutomatically() {
		check();
		return mJavaScriptCanOpenWindowsAutomatically;
	}

	/**
	 * Sets the default text encoding name to use when decoding html pages.
	 * The default is "UTF-8".
	 */
	public synchronized void setDefaultTextEncodingName(String encoding) {
		check();
		if (encoding != null && !encoding.isEmpty()) {
			mDefaultTextEncoding = encoding;
			applyString("default-charset", encoding);
		}
	}

	public synchronized String getDefaultTextEncodingName() {
		check();
		return mDefaultTextEncoding;
	}

	/**
	 * Sets the WebView's user-agent string. If the string is {@code null} or empty,
	 * the system default value will be used.
	 */
	public synchronized void setUserAgentString(String ua) {
		check();
		mUserAgent = (ua == null || ua.isEmpty()) ? null : ua;
		applyString("user-agent", getUserAgentString());
	}

	public synchronized String getUserAgentString() {
		check();
		return mUserAgent != null ? mUserAgent : getDefaultUserAgent(mView.getContext());
	}

	/**
	 * Returns the default User-Agent used by a WebView.
	 * An instance of WebView could use a different User-Agent if a call
	 * is made to {@link WebSettings#setUserAgentString(String)}.
	 *
	 * teslalinux: the engine is WebKit, so the string names WebKit's (frozen) version the way
	 * WebKit-based mobile browsers do, together with the Android release and device as Android
	 * WebView reports them ("; wv" marks an embedded WebView).
	 */
	public static String getDefaultUserAgent(Context context) {
		StringBuilder sb = new StringBuilder("Mozilla/5.0 (Linux; Android ");
		sb.append(Build.VERSION.RELEASE);
		if (Build.MODEL != null && !Build.MODEL.isEmpty())
			sb.append("; ").append(Build.MODEL);
		if (Build.ID != null && !Build.ID.isEmpty())
			sb.append(" Build/").append(Build.ID);
		sb.append("; wv) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/4.0 Mobile Safari/605.1.15");
		return sb.toString();
	}

	/**
	 * Tells the WebView whether it needs to set a node to have focus when
	 * {@link WebView#requestFocus(int, android.graphics.Rect)} is called. The
	 * default value is {@code true}.
	 */
	public synchronized void setNeedInitialFocus(boolean flag) {
		check();
		mNeedInitialFocus = flag;
	}

	synchronized boolean getNeedInitialFocus() {
		return mNeedInitialFocus;
	}

	/**
	 * Sets the priority of the Render thread.
	 * @deprecated It is not recommended to adjust thread priorities, and this will
	 *             not be supported in future versions (Chromium ignores it).
	 */
	@Deprecated
	public synchronized void setRenderPriority(RenderPriority priority) {
		check();
		mRenderPriority = priority;
	}

	/**
	 * Overrides the way the cache is used. The way the cache is used is based
	 * on the navigation type. For a normal page load, the cache is checked
	 * and content is re-validated as needed. When navigating back, content is
	 * not revalidated, instead the content is just retrieved from the cache.
	 * This method allows the client to override this behavior by specifying
	 * one of {@link #LOAD_DEFAULT}, {@link #LOAD_CACHE_ELSE_NETWORK},
	 * {@link #LOAD_NO_CACHE} or {@link #LOAD_CACHE_ONLY}. The default value is {@link #LOAD_DEFAULT}.
	 *
	 * teslalinux: WebKitGTK has no per-view cache policy. WebView applies LOAD_NO_CACHE to the
	 * documents it loads (no-cache request headers, reload bypassing the cache); the cache-preferring
	 * modes leave WebKit's normal revalidation in place.
	 */
	public synchronized void setCacheMode(int mode) {
		check();
		if (mode < LOAD_DEFAULT || mode > LOAD_CACHE_ONLY)
			throw new IllegalArgumentException("Invalid cache mode " + mode);
		mCacheMode = mode;
	}

	public synchronized int getCacheMode() {
		check();
		return mCacheMode;
	}

	/**
	 * Configures the WebView's behavior when a secure origin attempts to load a resource from an
	 * insecure origin. teslalinux: WebKit blocks active mixed content and upgrades passive content
	 * itself, close to MIXED_CONTENT_COMPATIBILITY_MODE; it has no switch, so the value is kept for
	 * the getter.
	 */
	public synchronized void setMixedContentMode(int mode) {
		check();
		mMixedContentMode = mode;
	}

	public synchronized int getMixedContentMode() {
		check();
		return mMixedContentMode;
	}

	/**
	 * Sets whether this WebView should raster tiles when it is offscreen but attached to a window.
	 * teslalinux: a Chromium raster scheduling hint; WebKitGTK renders what is mapped, so the value
	 * only reports back (as on Chromium it changes no visible behaviour).
	 */
	public synchronized void setOffscreenPreRaster(boolean enabled) {
		check();
		mOffscreenPreRaster = enabled;
	}

	public synchronized boolean getOffscreenPreRaster() {
		check();
		return mOffscreenPreRaster;
	}

	/**
	 * Sets whether Safe Browsing is enabled. teslalinux: this platform has no Safe Browsing service
	 * (see WebView.startSafeBrowsing, which reports that); the preference is kept as AOSP keeps it
	 * when the provider cannot check URLs.
	 */
	public synchronized void setSafeBrowsingEnabled(boolean enabled) {
		check();
		mSafeBrowsingEnabled = enabled;
	}

	public synchronized boolean getSafeBrowsingEnabled() {
		check();
		return mSafeBrowsingEnabled;
	}

	/**
	 * Set the force dark mode for this WebView.
	 * @deprecated The "force dark" model previously implemented by WebView was complex
	 * and didn't interoperate well with current Web standards. Apps targeting T+ get no effect
	 * (as AOSP); WebKitGTK has no algorithmic darkening for older targets either, so pages follow
	 * their own prefers-color-scheme handling.
	 */
	@Deprecated
	public synchronized void setForceDark(int forceDark) {
		check();
		mForceDark = forceDark;
	}

	@Deprecated
	public synchronized int getForceDark() {
		check();
		return mForceDark;
	}

	/**
	 * Disables the action mode menu items according to {@code menuItems} flag.
	 * teslalinux: WebKitGTK text selection shows its own context menu without Share, Web Search or
	 * Process Text entries, so none of these items exists to be shown; the value is kept.
	 */
	public synchronized void setDisabledActionModeMenuItems(int menuItems) {
		check();
		mDisabledActionModeMenuItems = menuItems;
	}

	public synchronized int getDisabledActionModeMenuItems() {
		check();
		return mDisabledActionModeMenuItems;
	}
}
