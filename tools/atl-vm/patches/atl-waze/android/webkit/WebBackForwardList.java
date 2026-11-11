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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * This class contains the back/forward list for a WebView.
 * WebView.copyBackForwardList() will return a copy of this class used to
 * inspect the entries in the list.
 */
public abstract class WebBackForwardList implements Cloneable, Serializable {
	/**
	 * Return the current history item. This method returns null if the list is
	 * empty.
	 */
	public abstract WebHistoryItem getCurrentItem();

	/**
	 * Get the index of the current history item. This index can be used to
	 * directly index into the array list.
	 * @return The current index from 0...n or -1 if the list is empty.
	 */
	public abstract int getCurrentIndex();

	/**
	 * Get the history item at the given index. The index range is from 0...n
	 * where 0 is the first item and n is the last item.
	 */
	public abstract WebHistoryItem getItemAtIndex(int index);

	/**
	 * Get the total size of the back/forward list.
	 */
	public abstract int getSize();

	/**
	 * Clone the entire object to be used in the UI thread by clients of
	 * WebView.
	 */
	protected abstract WebBackForwardList clone();

	/** teslalinux: a snapshot of the engine's back/forward list (WebView.copyBackForwardList). */
	static final class Snapshot extends WebBackForwardList {
		private final ArrayList<WebHistoryItem> mItems;
		private final int mCurrent;

		Snapshot(List<WebHistoryItem> items, int current) {
			mItems = new ArrayList<WebHistoryItem>(items);
			mCurrent = mItems.isEmpty() ? -1 : current;
		}

		@Override
		public synchronized WebHistoryItem getCurrentItem() {
			return getItemAtIndex(mCurrent);
		}

		@Override
		public synchronized int getCurrentIndex() {
			return mCurrent;
		}

		@Override
		public synchronized WebHistoryItem getItemAtIndex(int index) {
			if (index < 0 || index >= mItems.size())
				return null;
			return mItems.get(index);
		}

		@Override
		public synchronized int getSize() {
			return mItems.size();
		}

		@Override
		protected synchronized Snapshot clone() {
			return new Snapshot(mItems, mCurrent);
		}
	}
}
