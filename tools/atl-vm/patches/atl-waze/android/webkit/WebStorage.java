/*
 * Copyright (C) 2009 The Android Open Source Project
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

/**
 * This class is used to manage the JavaScript storage APIs provided by the
 * {@link WebView}. It manages the Application Cache API, the Web SQL Database
 * API and the HTML5 Web Storage API.
 *
 * <p>teslalinux: storage lives in the WebKitGTK network session all WebViews share.
 */
public class WebStorage {

	/**
	 * Encapsulates a callback function which is used to provide a new quota
	 * for a JavaScript storage API.
	 * @deprecated This class is obsolete and no longer used.
	 */
	@Deprecated
	public interface QuotaUpdater {
		/**
		 * Provides a new quota, specified in bytes.
		 */
		public void updateQuota(long newQuota);
	};

	/* WebView.nativeClearWebsiteData kind: session and local storage, IndexedDB, service worker
	 * registrations and the DOM cache (WebKitGTK has removed Web SQL and the Application Cache) */
	static final int STORAGE_DATA = 0;

	/**
	 * Clears all storage currently being used by the JavaScript storage APIs.
	 * This includes the Application Cache, Web SQL Database and the HTML5 Web
	 * Storage APIs.
	 */
	public void deleteAllData() {
		CookieManager.runOnMain(new Runnable() {
			public void run() {
				WebView.ensureSession(null);
				WebView.nativeClearWebsiteData(STORAGE_DATA, -1);
			}
		});
	}

	/**
	 * Gets the singleton instance of this class.
	 */
	public static WebStorage getInstance() {
		synchronized (WebStorage.class) {
			if (sInstance == null)
				sInstance = new WebStorage();
			return sInstance;
		}
	}

	private static WebStorage sInstance;

	/**
	 * This class should not be instantiated directly, applications must only use
	 * {@link #getInstance()} to obtain the instance.
	 */
	public WebStorage() {}
}
