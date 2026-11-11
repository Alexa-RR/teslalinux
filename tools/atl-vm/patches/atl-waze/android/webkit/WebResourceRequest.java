/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.net.Uri;
import java.util.Collections;
import java.util.Map;

/**
 * Encompasses parameters to the {@link WebViewClient#shouldInterceptRequest} and
 * {@link WebViewClient#shouldOverrideUrlLoading} methods.
 */
public interface WebResourceRequest {
	/**
	 * Gets the URL for which the resource request was made.
	 */
	Uri getUrl();

	/**
	 * Gets whether the request was made in order to fetch the main frame's document.
	 */
	boolean isForMainFrame();

	/**
	 * Gets whether the request was a result of a server-side redirect.
	 */
	boolean isRedirect();

	/**
	 * Gets whether a gesture (such as a click) was associated with the request.
	 */
	boolean hasGesture();

	/**
	 * Gets the method associated with the request, for example "GET".
	 */
	String getMethod();

	/**
	 * Gets the headers associated with the request. These are represented as a mapping of header
	 * name to header value.
	 */
	Map<String, String> getRequestHeaders();

	/** teslalinux: the request as the engine describes it to WebView. */
	static final class Impl implements WebResourceRequest {
		private final Uri mUrl;
		private final boolean mMainFrame, mRedirect, mGesture;
		private final String mMethod;
		private final Map<String, String> mHeaders;

		Impl(String url, boolean mainFrame, boolean redirect, boolean gesture, String method, Map<String, String> headers) {
			mUrl = Uri.parse(url != null ? url : "");
			mMainFrame = mainFrame;
			mRedirect = redirect;
			mGesture = gesture;
			mMethod = method != null ? method : "GET";
			mHeaders = headers != null ? Collections.unmodifiableMap(headers) : Collections.<String, String>emptyMap();
		}

		public Uri getUrl() { return mUrl; }
		public boolean isForMainFrame() { return mMainFrame; }
		public boolean isRedirect() { return mRedirect; }
		public boolean hasGesture() { return mGesture; }
		public String getMethod() { return mMethod; }
		public Map<String, String> getRequestHeaders() { return mHeaders; }
	}
}
