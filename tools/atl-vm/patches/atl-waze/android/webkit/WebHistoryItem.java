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

import android.graphics.Bitmap;

/**
 * A convenience class for accessing fields in an entry in the back/forward list
 * of a WebView. Each WebHistoryItem is a snapshot of the requested history
 * item.
 */
public abstract class WebHistoryItem implements Cloneable {
	/**
	 * Return an identifier for this history item.
	 * @deprecated This method is now obsolete.
	 */
	@Deprecated
	public abstract int getId();

	/**
	 * Return the url of this history item.
	 */
	public abstract String getUrl();

	/**
	 * Return the original url of this history item. This was the requested
	 * url, the final url may be different as there might have been
	 * redirects while loading the site.
	 */
	public abstract String getOriginalUrl();

	/**
	 * Return the document title of this history item.
	 */
	public abstract String getTitle();

	/**
	 * Return the favicon of this history item or {@code null} if no favicon was found.
	 */
	public abstract Bitmap getFavicon();

	/**
	 * Clone the history item for use by clients of WebView.
	 */
	protected abstract WebHistoryItem clone();

	/** teslalinux: one entry of the engine's back/forward list. */
	static final class Entry extends WebHistoryItem {
		private final int mId;
		private final String mUrl, mOriginalUrl, mTitle;

		Entry(int id, String url, String originalUrl, String title) {
			mId = id;
			mUrl = url;
			mOriginalUrl = originalUrl != null ? originalUrl : url;
			mTitle = title;
		}

		@Deprecated
		@Override
		public int getId() { return mId; }
		@Override
		public String getUrl() { return mUrl; }
		@Override
		public String getOriginalUrl() { return mOriginalUrl; }
		@Override
		public String getTitle() { return mTitle; }
		/* The WebKitGTK favicon database is not enabled for our network session, so no favicon is ever
		 * found: null, which is AOSP's answer when there is none. */
		@Override
		public Bitmap getFavicon() { return null; }
		@Override
		protected Entry clone() { return new Entry(mId, mUrl, mOriginalUrl, mTitle); }
	}
}
