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
 * A color filter that transforms colors through a 4x5 color matrix. This filter can be used to
 * change the saturation of pixels, convert from YUV to RGB, etc. teslalinux: applied as a GSK
 * colour-matrix node (Paint.setColorFilter, native/atl_gfx_canvas.c).
 */
public class ColorMatrixColorFilter extends ColorFilter {
	private final ColorMatrix mMatrix = new ColorMatrix();

	/**
	 * Create a color filter that transforms colors through a 4x5 color matrix.
	 *
	 * @param matrix 4x5 matrix used to transform colors. It is copied into the filter, so changes
	 *               made to the matrix after the filter is constructed will not be reflected in
	 *               the filter.
	 */
	public ColorMatrixColorFilter(ColorMatrix matrix) {
		mMatrix.set(matrix);
	}

	/**
	 * Create a color filter that transforms colors through a 4x5 color matrix.
	 *
	 * @param array Array of floats used to transform colors, treated as a 4x5 matrix. The first 20
	 *              entries of the array are copied into the filter.
	 */
	public ColorMatrixColorFilter(float[] array) {
		if (array.length < 20) {
			throw new ArrayIndexOutOfBoundsException();
		}
		mMatrix.set(array);
	}

	/**
	 * Copies the ColorMatrix from the filter into the passed ColorMatrix.
	 *
	 * @param colorMatrix Set to the current value of the filter's ColorMatrix.
	 */
	public void getColorMatrix(ColorMatrix colorMatrix) {
		colorMatrix.set(mMatrix);
	}

	/**
	 * @hide (AOSP): changes the filter's matrix; paints that use the filter pick the change up when
	 * the filter is set on them again, as AOSP's native filter does.
	 */
	public void setColorMatrix(ColorMatrix matrix) {
		if (matrix == null)
			mMatrix.reset();
		else
			mMatrix.set(matrix);
	}

	/** @hide (AOSP): changes the filter's matrix from a 20-entry array. */
	public void setColorMatrixArray(float[] array) {
		if (array == null) {
			mMatrix.reset();
			return;
		}
		if (array.length < 20)
			throw new IllegalArgumentException("array must have a minimum of 20 elements");
		mMatrix.set(array);
	}

	/** The 4x5 matrix, row-major (read by Paint). */
	float[] tlArray() {
		return mMatrix.getArray().clone();
	}
}
