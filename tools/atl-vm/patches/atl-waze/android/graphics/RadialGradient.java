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
 * AOSP RadialGradient; drawn by android.atl.GskCanvas as a GSK radial gradient.
 *
 * The API 31 two-point conical form is exact when both circles share a centre (GSK's radial
 * gradient has a start radius); GSK has no two-point conical gradient, so with different centres
 * the gradient is drawn around the end centre.
 */
public class RadialGradient extends Shader {
	private final float mCenterX, mCenterY, mRadius;
	private final float mStartRadius;
	private final int[] mColors;
	private final float[] mPositions;
	private final TileMode mTileMode;

	/**
	 * Create a shader that draws a radial gradient given the center and radius.
	 *
	 * @param centerX  The x-coordinate of the center of the radius
	 * @param centerY  The y-coordinate of the center of the radius
	 * @param radius   Must be positive. The radius of the circle for this gradient.
	 * @param colors   The sRGB colors to be distributed between the center and edge of the circle
	 * @param stops    May be <code>null</code>. Valid values are between <code>0.0f</code> and
	 *                 <code>1.0f</code>. The relative position of each corresponding color in
	 *                 the colors array. If <code>null</code>, colors are distributed evenly
	 *                 between the center and edge of the circle.
	 * @param tileMode The Shader tiling mode
	 */
	public RadialGradient(float centerX, float centerY, float radius, int[] colors, float[] stops, TileMode tileMode) {
		this(centerX, centerY, 0f, radius, colors, stops, tileMode);
	}

	/** API 29: colours as ColorLongs. */
	public RadialGradient(float centerX, float centerY, float radius, long[] colors, float[] stops, TileMode tileMode) {
		this(centerX, centerY, radius, tlToArgb(colors), stops, tileMode);
	}

	/** Create a shader that draws a radial gradient from the centre colour to the edge colour. */
	public RadialGradient(float centerX, float centerY, float radius, int centerColor, int edgeColor, TileMode tileMode) {
		this(centerX, centerY, radius, new int[] { centerColor, edgeColor }, null, tileMode);
	}

	/** API 29: the two colours as ColorLongs. */
	public RadialGradient(float centerX, float centerY, float radius, long centerColor, long edgeColor, TileMode tileMode) {
		this(centerX, centerY, radius, new int[] { Color.toArgb(centerColor), Color.toArgb(edgeColor) }, null, tileMode);
	}

	/** API 31: a two-point conical gradient (see the class comment for the different-centre case). */
	public RadialGradient(float startX, float startY, float startRadius, float endX, float endY, float endRadius,
	                      long[] colors, float[] stops, TileMode tileMode) {
		this(endX, endY, startRadius, endRadius, tlToArgb(colors), stops, tileMode);
		if (startRadius < 0)
			throw new IllegalArgumentException("starting radius must be >= 0");
	}

	private RadialGradient(float centerX, float centerY, float startRadius, float radius, int[] colors, float[] stops, TileMode tileMode) {
		if (radius <= 0) {
			throw new IllegalArgumentException("radius must be > 0");
		}
		tlCheckColors(colors.length, stops);
		mCenterX = centerX;
		mCenterY = centerY;
		mStartRadius = Math.min(startRadius, radius);
		mRadius = radius;
		mColors = colors.clone();
		mPositions = stops != null ? stops.clone() : null;
		mTileMode = tileMode != null ? tileMode : TileMode.CLAMP;
	}

	public float tlCenterX() { return mCenterX; }
	public float tlCenterY() { return mCenterY; }
	public float tlRadius() { return mRadius; }
	public int[] tlColors() { return mColors; }
	public TileMode tlTileMode() { return mTileMode; }

	/** Stop positions relative to the radius (the start radius folded in), or null for even spacing. */
	public float[] tlPositions() {
		if (mStartRadius <= 0)
			return mPositions;
		float f = mStartRadius / mRadius;
		float[] p = new float[mColors.length];
		for (int i = 0; i < p.length; i++) {
			float s = mPositions != null ? mPositions[i] : (p.length > 1 ? (float) i / (p.length - 1) : 0f);
			p[i] = f + (1 - f) * s;
		}
		return p;
	}
}
