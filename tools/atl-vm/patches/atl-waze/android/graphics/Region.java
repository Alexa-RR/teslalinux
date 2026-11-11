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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * AOSP Region (a set of integer rectangles with boolean operations), backed by a cairo region in
 * ATL (teslalinux, native/atl_region.c) instead of an SkRegion. Paths are rasterised with cairo.
 */
public class Region implements Parcelable {
	/** @hide */
	public long mNativeRegion;

	// the native values for these must match up with the enum in SkRegion.h
	public enum Op {
		DIFFERENCE(0),
		INTERSECT(1),
		UNION(2),
		XOR(3),
		REVERSE_DIFFERENCE(4),
		REPLACE(5);

		Op(int nativeInt) {
			this.nativeInt = nativeInt;
		}

		/** @hide */
		public final int nativeInt;
	}

	/** Create an empty region */
	public Region() {
		this(nativeConstructor());
	}

	/** Return a copy of the specified region */
	public Region(Region region) {
		this(nativeConstructor());
		nativeSetRegion(mNativeRegion, region.mNativeRegion);
	}

	/** Return a region set to the specified rectangle */
	public Region(Rect r) {
		mNativeRegion = nativeConstructor();
		nativeSetRect(mNativeRegion, r.left, r.top, r.right, r.bottom);
	}

	/** Return a region set to the specified rectangle */
	public Region(int left, int top, int right, int bottom) {
		mNativeRegion = nativeConstructor();
		nativeSetRect(mNativeRegion, left, top, right, bottom);
	}

	/** Set the region to the empty region */
	public void setEmpty() {
		nativeSetRect(mNativeRegion, 0, 0, 0, 0);
	}

	/** Set the region to the specified region. */
	public boolean set(Region region) {
		nativeSetRegion(mNativeRegion, region.mNativeRegion);
		return true;
	}

	/** Set the region to the specified rectangle */
	public boolean set(Rect r) {
		return nativeSetRect(mNativeRegion, r.left, r.top, r.right, r.bottom);
	}

	/** Set the region to the specified rectangle */
	public boolean set(int left, int top, int right, int bottom) {
		return nativeSetRect(mNativeRegion, left, top, right, bottom);
	}

	/**
	 * Set the region to the area described by the path and clip.
	 * Return true if the resulting region is non-empty. This produces a region
	 * that is identical to the pixels that would be drawn by the path
	 * (with no antialiasing).
	 */
	public boolean setPath(Path path, Region clip) {
		return nativeSetPath(mNativeRegion, path.getGskPath(), clip.mNativeRegion);
	}

	/** Return true if this region is empty */
	public native boolean isEmpty();

	/** Return true if the region contains a single rectangle */
	public native boolean isRect();

	/** Return true if the region contains more than one rectangle */
	public native boolean isComplex();

	/** Return a new Rect set to the bounds of the region. If the region is empty, the Rect will be set to [0, 0, 0, 0] */
	public Rect getBounds() {
		Rect r = new Rect();
		nativeGetBounds(mNativeRegion, r);
		return r;
	}

	/** Set the Rect to the bounds of the region. If the region is empty, the Rect will be set to [0, 0, 0, 0] */
	public boolean getBounds(Rect r) {
		if (r == null) {
			throw new NullPointerException();
		}
		return nativeGetBounds(mNativeRegion, r);
	}

	/** Return the boundary of the region as a new Path. If the region is empty, the path will also be empty. */
	public Path getBoundaryPath() {
		Path path = new Path();
		getBoundaryPath(path);
		return path;
	}

	/** Set the path to the boundary of the region (one rectangle per span). */
	public boolean getBoundaryPath(Path path) {
		path.rewind();
		int n = nativeGetRectCount(mNativeRegion);
		Rect r = new Rect();
		for (int i = 0; i < n; i++) {
			nativeGetRect(mNativeRegion, i, r);
			path.addRect(r.left, r.top, r.right, r.bottom, Path.Direction.CW);
		}
		return n > 0;
	}

	/** Return true if the region contains the specified point */
	public native boolean contains(int x, int y);

	/** Return true if the region is a single rectangle (not complex) and it contains the specified rectangle. */
	public boolean quickContains(Rect r) {
		return quickContains(r.left, r.top, r.right, r.bottom);
	}

	/** Return true if the region is a single rectangle (not complex) and it contains the specified rectangle. */
	public native boolean quickContains(int left, int top, int right, int bottom);

	/** Return true if the region is empty, or if the specified rectangle does not intersect the region. */
	public boolean quickReject(Rect r) {
		return quickReject(r.left, r.top, r.right, r.bottom);
	}

	/** Return true if the region is empty, or if the specified rectangle does not intersect the region. */
	public native boolean quickReject(int left, int top, int right, int bottom);

	/** Return true if the region is empty, or if the specified region does not intersect the region. */
	public native boolean quickReject(Region rgn);

	/** Translate the region by [dx, dy]. If the region is empty, do nothing. */
	public void translate(int dx, int dy) {
		translate(dx, dy, null);
	}

	/** Set the dst region to the result of translating this region by [dx, dy]. If this region is empty, then dst will be set to empty. */
	public native void translate(int dx, int dy, Region dst);

	/** Scale the region by the given scale amount. @hide */
	public void scale(float scale) {
		scale(scale, null);
	}

	/** Set the dst region to the result of scaling this region by the given scale amount. @hide */
	public native void scale(float scale, Region dst);

	public final boolean union(Rect r) {
		return op(r, Op.UNION);
	}

	/** Perform the specified Op on this region and the specified rect. Return true if the result of the op is not empty. */
	public boolean op(Rect r, Op op) {
		return nativeOp(mNativeRegion, r.left, r.top, r.right, r.bottom, op.nativeInt);
	}

	/** Perform the specified Op on this region and the specified rect. Return true if the result of the op is not empty. */
	public boolean op(int left, int top, int right, int bottom, Op op) {
		return nativeOp(mNativeRegion, left, top, right, bottom, op.nativeInt);
	}

	/** Perform the specified Op on this region and the specified region. Return true if the result of the op is not empty. */
	public boolean op(Region region, Op op) {
		return op(this, region, op);
	}

	/** Set this region to the result of performing the Op on the specified rect and region. Return true if the result is not empty. */
	public boolean op(Rect rect, Region region, Op op) {
		return nativeOp(mNativeRegion, rect, region.mNativeRegion, op.nativeInt);
	}

	/** Set this region to the result of performing the Op on the specified regions. Return true if the result is not empty. */
	public boolean op(Region region1, Region region2, Op op) {
		return nativeOp(mNativeRegion, region1.mNativeRegion, region2.mNativeRegion, op.nativeInt);
	}

	/** @hide the number of rectangles the region consists of (RegionIterator) */
	public int getRectCount() {
		return nativeGetRectCount(mNativeRegion);
	}

	/** @hide the i-th rectangle of the region (RegionIterator) */
	public boolean getRect(int index, Rect r) {
		return nativeGetRect(mNativeRegion, index, r);
	}

	@Override
	public String toString() {
		return nativeToString(mNativeRegion);
	}

	public static final Parcelable.Creator<Region> CREATOR = new Parcelable.Creator<Region>() {
		/** Rebuild a Region previously stored with writeToParcel(). */
		@Override
		public Region createFromParcel(Parcel p) {
			Region region = new Region();
			int n = p.readInt();
			for (int i = 0; i < n; i++) {
				int left = p.readInt(), top = p.readInt(), right = p.readInt(), bottom = p.readInt();
				region.op(left, top, right, bottom, Op.UNION);
			}
			return region;
		}

		@Override
		public Region[] newArray(int size) {
			return new Region[size];
		}
	};

	@Override
	public int describeContents() {
		return 0;
	}

	/** Write the region and its rectangles to the parcel (a count and four ints per rectangle). */
	@Override
	public void writeToParcel(Parcel p, int flags) {
		int n = nativeGetRectCount(mNativeRegion);
		p.writeInt(n);
		Rect r = new Rect();
		for (int i = 0; i < n; i++) {
			nativeGetRect(mNativeRegion, i, r);
			p.writeInt(r.left);
			p.writeInt(r.top);
			p.writeInt(r.right);
			p.writeInt(r.bottom);
		}
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null || !(obj instanceof Region)) {
			return false;
		}
		Region peer = (Region) obj;
		return nativeEquals(mNativeRegion, peer.mNativeRegion);
	}

	@Override
	public int hashCode() {
		return toString().hashCode();
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			nativeDestructor(mNativeRegion);
			mNativeRegion = 0;
		} finally {
			super.finalize();
		}
	}

	Region(long ni) {
		if (ni == 0) {
			throw new RuntimeException();
		}
		mNativeRegion = ni;
	}

	/** @hide */
	public final long ni() {
		return mNativeRegion;
	}

	private static native boolean nativeEquals(long native_r1, long native_r2);
	private static native long nativeConstructor();
	private static native void nativeDestructor(long native_region);
	private static native void nativeSetRegion(long native_dst, long native_src);
	private static native boolean nativeSetRect(long native_dst, int left, int top, int right, int bottom);
	private static native boolean nativeSetPath(long native_dst, long native_path, long native_clip);
	private static native boolean nativeGetBounds(long native_region, Rect rect);
	private static native boolean nativeOp(long native_dst, int left, int top, int right, int bottom, int op);
	private static native boolean nativeOp(long native_dst, Rect rect, long native_region, int op);
	private static native boolean nativeOp(long native_dst, long native_region1, long native_region2, int op);
	private static native int nativeGetRectCount(long native_region);
	private static native boolean nativeGetRect(long native_region, int index, Rect rect);
	private static native String nativeToString(long native_region);
}
