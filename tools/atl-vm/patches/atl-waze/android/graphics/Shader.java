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
 * Shader is the base class for objects that return horizontal spans of colors during drawing. A
 * subclass of Shader is installed in a Paint calling paint.setShader(shader). After that any
 * object (other than a bitmap) that is drawn with that paint will get its color(s) from the shader.
 *
 * teslalinux: the shaders are drawn by android.atl.GskCanvas as GSK gradient/texture nodes
 * (native/atl_gfx_canvas.c); a Shader only holds its parameters and the local matrix.
 */
public class Shader {

	/**
	 * @deprecated Use subclass constructors directly instead.
	 */
	@Deprecated
	public Shader() {}

	public enum TileMode {
		/**
		 * Replicate the edge color if the shader draws outside of its original bounds.
		 */
		CLAMP,
		/**
		 * Repeat the shader's image horizontally and vertically, alternating mirror images so
		 * that adjacent images always seam.
		 */
		MIRROR,
		/**
		 * Repeat the shader's image horizontally and vertically.
		 */
		REPEAT,
		/**
		 * Render the shader's image pixels only within its original bounds. If the shader draws
		 * outside of its original bounds, transparent black is drawn instead.
		 */
		DECAL,
	}

	private Matrix mLocalMatrix;

	/**
	 * Return true if the shader has a non-identity local matrix.
	 * @param localM Set to the local matrix of the shader, if the shader's matrix is non-null.
	 * @return true if the shader has a non-identity local matrix
	 */
	public boolean getLocalMatrix(Matrix localM) {
		if (mLocalMatrix != null) {
			localM.set(mLocalMatrix);
			return true;
		}
		return false;
	}

	/**
	 * Set the shader's local matrix. Passing null will reset the shader's matrix to identity.
	 *
	 * @param localM The shader's new local matrix, or null to specify identity
	 */
	public void setLocalMatrix(Matrix localM) {
		if (localM == null || localM.isIdentity()) {
			mLocalMatrix = null;
		} else {
			mLocalMatrix = new Matrix(localM);
		}
	}

	/** The local matrix, or null for identity (read by GskCanvas). */
	public Matrix tlLocalMatrix() {
		return mLocalMatrix;
	}

	protected void copyLocalMatrix(Shader dest) {
		dest.mLocalMatrix = mLocalMatrix != null ? new Matrix(mLocalMatrix) : null;
	}

	/** Colour longs (API 29) to colour ints for drawing (sRGB). */
	static int[] tlToArgb(long[] colors) {
		int[] out = new int[colors.length];
		for (int i = 0; i < colors.length; i++)
			out[i] = Color.toArgb(colors[i]);
		return out;
	}

	static void tlCheckColors(int colorCount, float[] positions) {
		if (colorCount < 2) {
			throw new IllegalArgumentException("needs >= 2 number of colors");
		}
		if (positions != null && colorCount != positions.length) {
			throw new IllegalArgumentException("color and position arrays must be of equal length");
		}
	}
}
