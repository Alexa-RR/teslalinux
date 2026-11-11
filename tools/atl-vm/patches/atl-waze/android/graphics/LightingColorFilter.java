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

package android.graphics;

/**
 * A color filter that can be used to simulate simple lighting effects. A LightingColorFilter is
 * defined by two parameters, one used to multiply the source color (called colorMultiply) and one
 * used to add to the source color (called colorAdd). The alpha channel is left untouched by this
 * color filter. Given a source color RGB, the resulting R'G'B' color is computed thusly:
 * R' = R * colorMultiply.R + colorAdd.R (likewise for G and B), each clamped to [0..255].
 * teslalinux: applied as the equivalent GSK colour matrix.
 */
public class LightingColorFilter extends ColorFilter {
	private int mMul;
	private int mAdd;

	/**
	 * Create a colorfilter that multiplies the RGB channels by one color, and then adds a second
	 * color. The alpha components of the mul and add arguments are ignored.
	 */
	public LightingColorFilter(int mul, int add) {
		mMul = mul;
		mAdd = add;
	}

	/** Returns the RGB color used to multiply the source color when the color filter is applied. */
	public int getColorMultiply() {
		return mMul;
	}

	/** @hide (AOSP) */
	public void setColorMultiply(int mul) {
		mMul = mul;
	}

	/** Returns the RGB color that will be added to the source color when the color filter is applied. */
	public int getColorAdd() {
		return mAdd;
	}

	/** @hide (AOSP) */
	public void setColorAdd(int add) {
		mAdd = add;
	}

	/** The equivalent 4x5 colour matrix (offsets in 0..255), read by Paint. */
	float[] tlArray() {
		return new float[] {
			Color.red(mMul) / 255f, 0, 0, 0, Color.red(mAdd),
			0, Color.green(mMul) / 255f, 0, 0, Color.green(mAdd),
			0, 0, Color.blue(mMul) / 255f, 0, Color.blue(mAdd),
			0, 0, 0, 1, 0,
		};
	}
}
