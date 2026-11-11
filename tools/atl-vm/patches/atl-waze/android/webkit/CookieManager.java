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

import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Manages the cookies used by an application's {@link WebView} instances.
 * <p>
 * CookieManager represents cookies as strings in the same format as the
 * HTTP {@code Cookie} and {@code Set-Cookie} header fields (defined in
 * <a href="https://tools.ietf.org/html/draft-ietf-httpbis-rfc6265bis-03">RFC6265bis</a>).
 *
 * <p>teslalinux: backed by the cookie manager of the WebKitGTK network session all WebViews share,
 * persisted in app_webview/Cookies (SQLite). WebKitGTK's accept policy is global, so the third-party
 * setting of the WebView changed last applies to all of them.
 */
public abstract class CookieManager {
	/* WebKitCookieAcceptPolicy */
	static final int POLICY_ALWAYS = 0;
	static final int POLICY_NEVER = 1;
	static final int POLICY_NO_THIRD_PARTY = 2;

	/**
	 * @deprecated This class should not be constructed by applications, use {@link #getInstance}
	 * instead to fetch the singleton instance.
	 */
	@Deprecated
	public CookieManager() {}

	@Override
	protected Object clone() throws CloneNotSupportedException {
		throw new CloneNotSupportedException("doesn't implement Cloneable");
	}

	/**
	 * Gets the singleton CookieManager instance.
	 */
	public static CookieManager getInstance() {
		return Impl.INSTANCE;
	}

	/**
	 * Sets whether the application's {@link WebView} instances should send and
	 * accept cookies. By default this is set to {@code true} and the WebView accepts cookies.
	 */
	public abstract void setAcceptCookie(boolean accept);

	/**
	 * Gets whether the application's {@link WebView} instances send and accept cookies.
	 */
	public abstract boolean acceptCookie();

	/**
	 * Sets whether the {@link WebView} should allow third party cookies to be set.
	 * Apps that target {@link android.os.Build.VERSION_CODES#LOLLIPOP} or later default to
	 * disallowing third party cookies.
	 */
	public abstract void setAcceptThirdPartyCookies(WebView webview, boolean accept);

	/**
	 * Gets whether the {@link WebView} should allow third party cookies to be set.
	 */
	public abstract boolean acceptThirdPartyCookies(WebView webview);

	/**
	 * Sets a single cookie (key-value pair) for the given URL. Any existing cookie with the same
	 * host, path and name will be replaced with the new cookie. The cookie being set
	 * will be ignored if it is expired. To set multiple cookies, your application should invoke
	 * this method multiple times.
	 */
	public abstract void setCookie(String url, String value);

	/**
	 * Sets a single cookie (key-value pair) for the given URL. This method is asynchronous.
	 * If a {@link ValueCallback} is provided, {@link ValueCallback#onReceiveValue} will be called
	 * on the current thread's {@link android.os.Looper} once the operation is complete.
	 * The value provided to the callback indicates whether the cookie was set successfully.
	 */
	public abstract void setCookie(String url, String value, ValueCallback<Boolean> callback);

	/**
	 * Gets all the cookies for the given URL. This may return multiple key-value pairs if multiple
	 * cookies are associated with this URL, in which case each cookie will be delimited by {@code
	 * "; "} characters (semicolon followed by a space). Each key-value pair will be of the form
	 * {@code "key=value"}.
	 *
	 * @return value the cookies as a string, using the format of the 'Cookie'
	 *               HTTP request header
	 */
	public abstract String getCookie(String url);

	/**
	 * See {@link #getCookie(String)}.
	 * @hide Used by Browser and by WebViewProvider implementations.
	 */
	public abstract String getCookie(String url, boolean privateBrowsing);

	/**
	 * Removes all session cookies, which are cookies without an expiration date.
	 * @deprecated use {@link #removeSessionCookies(ValueCallback)} instead.
	 */
	@Deprecated
	public abstract void removeSessionCookie();

	/**
	 * Removes all session cookies, which are cookies without an expiration
	 * date.
	 */
	public abstract void removeSessionCookies(ValueCallback<Boolean> callback);

	/**
	 * Removes all cookies.
	 * @deprecated Use {@link #removeAllCookies(ValueCallback)} instead.
	 */
	@Deprecated
	public abstract void removeAllCookie();

	/**
	 * Removes all cookies.
	 */
	public abstract void removeAllCookies(ValueCallback<Boolean> callback);

	/**
	 * Gets whether there are stored cookies.
	 */
	public abstract boolean hasCookies();

	/**
	 * See {@link #hasCookies()}.
	 * @hide Used by Browser and WebViewProvider implementations.
	 */
	public abstract boolean hasCookies(boolean privateBrowsing);

	/**
	 * Removes all expired cookies.
	 * @deprecated The WebView handles removing expired cookies automatically.
	 */
	@Deprecated
	public abstract void removeExpiredCookie();

	/**
	 * Ensures all cookies currently accessible through the getCookie API are
	 * written to persistent storage.
	 */
	public abstract void flush();

	/**
	 * Gets whether the application's {@link WebView} instances send and accept
	 * cookies for file scheme URLs.
	 * @deprecated This setting is not secure.
	 */
	@Deprecated
	public static boolean allowFileSchemeCookies() {
		return getInstance().allowFileSchemeCookiesImpl();
	}

	/** @hide */
	protected abstract boolean allowFileSchemeCookiesImpl();

	/**
	 * Sets whether the application's {@link WebView} instances should send and accept cookies for
	 * file scheme URLs.
	 * @deprecated This setting is not secure.
	 */
	@Deprecated
	public static void setAcceptFileSchemeCookies(boolean accept) {
		getInstance().setAcceptFileSchemeCookiesImpl(accept);
	}

	/** @hide */
	protected abstract void setAcceptFileSchemeCookiesImpl(boolean accept);

	/* ---- teslalinux: the WebKitGTK-backed implementation ---- */

	private static final class Pending {
		final ValueCallback<Boolean> callback;
		final Handler handler;

		Pending(ValueCallback<Boolean> callback, Handler handler) {
			this.callback = callback;
			this.handler = handler;
		}
	}

	private static final SparseArray<Pending> sPending = new SparseArray<Pending>();
	private static int sNextId;

	/** registers a completion for an async native operation; the callback runs on the caller's
	 * looper (the main looper for threads without one) */
	static int register(ValueCallback<Boolean> callback) {
		if (callback == null)
			return -1;
		Looper l = Looper.myLooper();
		Handler h = new Handler(l != null ? l : Looper.getMainLooper());
		synchronized (sPending) {
			int id = sNextId++;
			sPending.put(id, new Pending(callback, h));
			return id;
		}
	}

	/* called by the native side on the main thread when an async operation completes */
	static void internalDone(int id, final boolean ok) {
		final Pending p;
		synchronized (sPending) {
			p = sPending.get(id);
			sPending.remove(id);
		}
		if (p != null) {
			p.handler.post(new Runnable() {
				public void run() {
					p.callback.onReceiveValue(ok);
				}
			});
		}
	}

	/** runs on the main thread (where the GLib main loop and the session live) and waits for it */
	static void runOnMain(final Runnable r) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			r.run();
			return;
		}
		final CountDownLatch done = new CountDownLatch(1);
		new Handler(Looper.getMainLooper()).post(new Runnable() {
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

	static native void nativeSetAcceptPolicy(int policy);
	static native void nativeSetCookie(String url, String value, int id);
	static native String nativeGetCookie(String url);
	static native boolean nativeHasCookies();
	static native void nativeRemoveCookies(boolean sessionOnly, int id);

	static final class Impl extends CookieManager {
		static final Impl INSTANCE = new Impl();
		private boolean mAccept = true;
		private boolean mThirdParty;
		private final WeakHashMap<WebView, Boolean> mThirdPartyByView = new WeakHashMap<WebView, Boolean>();

		@SuppressWarnings("deprecation")
		private Impl() {}

		int policy() {
			return !mAccept ? POLICY_NEVER : mThirdParty ? POLICY_ALWAYS : POLICY_NO_THIRD_PARTY;
		}

		private void session() {
			WebView.ensureSession(null);
		}

		private void applyPolicy() {
			runOnMain(new Runnable() {
				public void run() {
					session();
					nativeSetAcceptPolicy(policy());
				}
			});
		}

		@Override
		public synchronized void setAcceptCookie(boolean accept) {
			mAccept = accept;
			applyPolicy();
		}

		@Override
		public synchronized boolean acceptCookie() {
			return mAccept;
		}

		@Override
		public synchronized void setAcceptThirdPartyCookies(WebView webview, boolean accept) {
			if (webview == null)
				throw new NullPointerException("webview");
			mThirdPartyByView.put(webview, accept);
			mThirdParty = accept;
			applyPolicy();
		}

		/* the default follows the app's target, as Chromium's AwSettings */
		@Override
		public synchronized boolean acceptThirdPartyCookies(WebView webview) {
			Boolean b = mThirdPartyByView.get(webview);
			if (b != null)
				return b;
			return webview != null && webview.getContext().getApplicationInfo().targetSdkVersion < android.os.Build.VERSION_CODES.LOLLIPOP;
		}

		@Override
		public void setCookie(String url, String value) {
			setCookie(url, value, null);
		}

		@Override
		public void setCookie(final String url, final String value, ValueCallback<Boolean> callback) {
			final int id = register(callback);
			if (url == null || value == null) {
				if (id >= 0)
					internalDone(id, false);
				return;
			}
			runOnMain(new Runnable() {
				public void run() {
					session();
					nativeSetCookie(fixupUrl(url), value, id);
				}
			});
		}

		/* Chromium accepts host-only URLs ("example.com") by assuming http */
		private static String fixupUrl(String url) {
			return url.contains("://") ? url : "http://" + url;
		}

		@Override
		public String getCookie(final String url) {
			if (url == null)
				return null;
			final String[] out = new String[1];
			runOnMain(new Runnable() {
				public void run() {
					session();
					out[0] = nativeGetCookie(fixupUrl(url));
				}
			});
			return out[0] == null || out[0].isEmpty() ? null : out[0];
		}

		@Override
		public String getCookie(String url, boolean privateBrowsing) {
			return getCookie(url);
		}

		@Override
		@Deprecated
		public void removeSessionCookie() {
			removeSessionCookies(null);
		}

		@Override
		public void removeSessionCookies(ValueCallback<Boolean> callback) {
			final int id = register(callback);
			runOnMain(new Runnable() {
				public void run() {
					session();
					nativeRemoveCookies(true, id);
				}
			});
		}

		@Override
		@Deprecated
		public void removeAllCookie() {
			removeAllCookies(null);
		}

		@Override
		public void removeAllCookies(ValueCallback<Boolean> callback) {
			final int id = register(callback);
			runOnMain(new Runnable() {
				public void run() {
					session();
					nativeRemoveCookies(false, id);
				}
			});
		}

		@Override
		public boolean hasCookies() {
			final boolean[] out = new boolean[1];
			runOnMain(new Runnable() {
				public void run() {
					session();
					out[0] = nativeHasCookies();
				}
			});
			return out[0];
		}

		@Override
		public boolean hasCookies(boolean privateBrowsing) {
			return hasCookies();
		}

		/* WebKit's cookie store drops expired cookies itself (as Chromium's does: its
		 * removeExpiredCookie is empty too) */
		@Override
		@Deprecated
		public void removeExpiredCookie() {
		}

		/* WebKitGTK's SQLite cookie storage writes each change as it happens, so every cookie the
		 * getCookie API sees is already persistent when flush is called */
		@Override
		public void flush() {
		}

		/* WebKitGTK does not keep cookies for file: URLs at all: the feature is absent, so file scheme
		 * cookies are reported as not accepted whatever was asked */
		@Override
		protected boolean allowFileSchemeCookiesImpl() {
			return false;
		}

		@Override
		protected void setAcceptFileSchemeCookiesImpl(boolean accept) {
		}
	}
}
