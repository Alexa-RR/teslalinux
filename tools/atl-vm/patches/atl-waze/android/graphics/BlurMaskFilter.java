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

/**
 * This takes a mask, and blurs its edge by the specified radius. Whether or not to include the
 * original mask, and whether the blur goes outside, inside, or straddles, the original mask's
 * border, is controlled by the Blur enum. teslalinux: drawn by android.atl.GskCanvas with GSK blur
 * and mask nodes.
 */
public class BlurMaskFilter extends MaskFilter {

	public enum Blur {
		/**
		 * Blur inside and outside the original border.
		 */
		NORMAL(0),

		/**
		 * Draw solid inside the border, blur outside.
		 */
		SOLID(1),

		/**
		 * Draw nothing inside the border, blur outside.
		 */
		OUTER(2),

		/**
		 * Blur inside the border, draw nothing outside.
		 */
		INNER(3);

		Blur(int value) {
			native_int = value;
		}
		final int native_int;
	}

	private final float mRadius;
	private final Blur mStyle;

	/**
	 * Create a blur maskfilter.
	 *
	 * @param radius The radius to extend the blur from the original mask. Must be > 0.
	 * @param style  The Blur to use
	 */
	public BlurMaskFilter(float radius, Blur style) {
		mRadius = radius;
		mStyle = style != null ? style : Blur.NORMAL;
	}

	/** Skia's blur sigma for the radius (SkBlurMask::ConvertRadiusToSigma). */
	public float tlSigma() {
		return mRadius > 0 ? 0.57735f * mRadius + 0.5f : 0f;
	}

	/** 0 NORMAL, 1 SOLID, 2 OUTER, 3 INNER. */
	public int tlStyle() {
		return mStyle.native_int;
	}
}
