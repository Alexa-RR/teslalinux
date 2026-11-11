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

/** AOSP LinearGradient; drawn by android.atl.GskCanvas as a GSK linear gradient. */
public class LinearGradient extends Shader {
	private final float mX0, mY0, mX1, mY1;
	private final int[] mColors;
	private final float[] mPositions;
	private final TileMode mTileMode;

	/**
	 * Create a shader that draws a linear gradient along a line.
	 *
	 * @param colors    The sRGB colors to be distributed along the gradient line
	 * @param positions May be null. The relative positions [0..1] of each corresponding color in
	 *                  the colors array. If this is null, the the colors are distributed evenly
	 *                  along the gradient line.
	 * @param tile      The Shader tiling mode
	 */
	public LinearGradient(float x0, float y0, float x1, float y1, int[] colors, float[] positions, TileMode tile) {
		tlCheckColors(colors.length, positions);
		mX0 = x0;
		mY0 = y0;
		mX1 = x1;
		mY1 = y1;
		mColors = colors.clone();
		mPositions = positions != null ? positions.clone() : null;
		mTileMode = tile != null ? tile : TileMode.CLAMP;
	}

	/** API 29: colours as ColorLongs. */
	public LinearGradient(float x0, float y0, float x1, float y1, long[] colors, float[] positions, TileMode tile) {
		this(x0, y0, x1, y1, tlToArgb(colors), positions, tile);
	}

	/** Create a shader that draws a linear gradient along a line between two colours. */
	public LinearGradient(float x0, float y0, float x1, float y1, int color0, int color1, TileMode tile) {
		this(x0, y0, x1, y1, new int[] { color0, color1 }, null, tile);
	}

	/** API 29: the two colours as ColorLongs. */
	public LinearGradient(float x0, float y0, float x1, float y1, long color0, long color1, TileMode tile) {
		this(x0, y0, x1, y1, new int[] { Color.toArgb(color0), Color.toArgb(color1) }, null, tile);
	}

	public float tlX0() { return mX0; }
	public float tlY0() { return mY0; }
	public float tlX1() { return mX1; }
	public float tlY1() { return mY1; }
	public int[] tlColors() { return mColors; }
	public float[] tlPositions() { return mPositions; }
	public TileMode tlTileMode() { return mTileMode; }
}
