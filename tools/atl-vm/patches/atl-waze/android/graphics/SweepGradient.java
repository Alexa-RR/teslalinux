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

/** AOSP SweepGradient; drawn by android.atl.GskCanvas as a GSK conic gradient. */
public class SweepGradient extends Shader {
	private final float mCx;
	private final float mCy;
	private final int[] mColors;
	private final float[] mPositions;

	/**
	 * A Shader that draws a sweep gradient around a center point.
	 *
	 * @param cx       The x-coordinate of the center
	 * @param cy       The y-coordinate of the center
	 * @param colors   The sRGB colors to be distributed between around the center.
	 *                 There must be at least 2 colors in the array.
	 * @param positions May be NULL. The relative position of each corresponding color in the
	 *                 colors array, beginning with 0 and ending with 1.0. If the values are not
	 *                 monotonic, the drawing may produce unexpected results. If positions is
	 *                 NULL, then the colors are automatically spaced evenly.
	 */
	public SweepGradient(float cx, float cy, int[] colors, float[] positions) {
		tlCheckColors(colors.length, positions);
		mCx = cx;
		mCy = cy;
		mColors = colors.clone();
		mPositions = positions != null ? positions.clone() : null;
	}

	/** API 29: colours as ColorLongs. */
	public SweepGradient(float cx, float cy, long[] colors, float[] positions) {
		this(cx, cy, tlToArgb(colors), positions);
	}

	/** A Shader that draws a sweep gradient around a center point between two colours. */
	public SweepGradient(float cx, float cy, int color0, int color1) {
		this(cx, cy, new int[] { color0, color1 }, null);
	}

	/** API 29: the two colours as ColorLongs. */
	public SweepGradient(float cx, float cy, long color0, long color1) {
		this(cx, cy, new int[] { Color.toArgb(color0), Color.toArgb(color1) }, null);
	}

	public float tlCx() { return mCx; }
	public float tlCy() { return mCy; }
	public int[] tlColors() { return mColors; }
	public float[] tlPositions() { return mPositions; }
}
