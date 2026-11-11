/*
 * Copyright (C) 2023 The Android Open Source Project
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

/**
 * AOSP Gainmap (API 34): the gain map image and the parameters that turn an SDR base image into
 * HDR. teslalinux: the parameters are kept exactly as AOSP keeps them; the Tesla screen is an SDR
 * display, where Android also draws only the base image.
 */
public final class Gainmap {
	private Bitmap mGainmapContents;
	private final float[] mRatioMin = { 1f, 1f, 1f };
	private final float[] mRatioMax = { 2f, 2f, 2f };
	private final float[] mGamma = { 1f, 1f, 1f };
	private final float[] mEpsilonSdr = { 0f, 0f, 0f };
	private final float[] mEpsilonHdr = { 0f, 0f, 0f };
	private float mDisplayRatioForFullHdr = 2f;
	private float mMinDisplayRatioForHdrTransition = 1f;

	/** Creates a gainmap from the given contents with default (no-op) parameters. */
	public Gainmap(Bitmap gainmapContents) {
		setGainmapContents(gainmapContents);
	}

	/** API 35: a copy of gainmap's parameters with other contents. */
	public Gainmap(Gainmap gainmap, Bitmap gainmapContents) {
		this(gainmapContents);
		System.arraycopy(gainmap.mRatioMin, 0, mRatioMin, 0, 3);
		System.arraycopy(gainmap.mRatioMax, 0, mRatioMax, 0, 3);
		System.arraycopy(gainmap.mGamma, 0, mGamma, 0, 3);
		System.arraycopy(gainmap.mEpsilonSdr, 0, mEpsilonSdr, 0, 3);
		System.arraycopy(gainmap.mEpsilonHdr, 0, mEpsilonHdr, 0, 3);
		mDisplayRatioForFullHdr = gainmap.mDisplayRatioForFullHdr;
		mMinDisplayRatioForHdrTransition = gainmap.mMinDisplayRatioForHdrTransition;
	}

	public Bitmap getGainmapContents() {
		return mGainmapContents;
	}

	public void setGainmapContents(Bitmap bitmap) {
		if (bitmap == null)
			throw new NullPointerException("gainmapContents");
		if (bitmap.isRecycled())
			throw new IllegalArgumentException("Bitmap is recycled");
		mGainmapContents = bitmap;
	}

	public void setRatioMin(float r, float g, float b) {
		set(mRatioMin, r, g, b);
	}

	public float[] getRatioMin() {
		return mRatioMin.clone();
	}

	public void setRatioMax(float r, float g, float b) {
		set(mRatioMax, r, g, b);
	}

	public float[] getRatioMax() {
		return mRatioMax.clone();
	}

	public void setGamma(float r, float g, float b) {
		set(mGamma, r, g, b);
	}

	public float[] getGamma() {
		return mGamma.clone();
	}

	public void setEpsilonSdr(float r, float g, float b) {
		set(mEpsilonSdr, r, g, b);
	}

	public float[] getEpsilonSdr() {
		return mEpsilonSdr.clone();
	}

	public void setEpsilonHdr(float r, float g, float b) {
		set(mEpsilonHdr, r, g, b);
	}

	public float[] getEpsilonHdr() {
		return mEpsilonHdr.clone();
	}

	/** The HDR/SDR ratio at which the gain map applies fully; must be >= 1. */
	public void setDisplayRatioForFullHdr(float max) {
		if (!Float.isFinite(max) || max < 1f)
			throw new IllegalArgumentException("setDisplayRatioForFullHdr must be >= 1.0f, got = " + max);
		mDisplayRatioForFullHdr = max;
	}

	public float getDisplayRatioForFullHdr() {
		return mDisplayRatioForFullHdr;
	}

	/** The HDR/SDR ratio at which the gain map starts to apply; must be >= 1. */
	public void setMinDisplayRatioForHdrTransition(float min) {
		if (!Float.isFinite(min) || min < 1f)
			throw new IllegalArgumentException("setMinDisplayRatioForHdrTransition must be >= 1.0f, got = " + min);
		mMinDisplayRatioForHdrTransition = min;
	}

	public float getMinDisplayRatioForHdrTransition() {
		return mMinDisplayRatioForHdrTransition;
	}

	private static void set(float[] dst, float r, float g, float b) {
		dst[0] = r;
		dst[1] = g;
		dst[2] = b;
	}
}
