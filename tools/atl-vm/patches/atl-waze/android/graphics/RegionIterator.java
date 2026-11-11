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

package android.graphics;

/** AOSP RegionIterator: walks the rectangles of a snapshot of a region (teslalinux: over Region.getRect). */
public class RegionIterator {
	private final Region mRegion;
	private int mIndex;

	/** Construct an iterator for all of the rectangles in a region. This effectively makes a private copy of the region. */
	public RegionIterator(Region region) {
		mRegion = new Region(region);
	}

	/** Return the next rectangle in the region. If there are no more rectangles this returns false and r is unchanged. */
	public final boolean next(Rect r) {
		if (r == null) {
			throw new NullPointerException("The Rect must be provided");
		}
		if (mIndex >= mRegion.getRectCount())
			return false;
		return mRegion.getRect(mIndex++, r);
	}
}
