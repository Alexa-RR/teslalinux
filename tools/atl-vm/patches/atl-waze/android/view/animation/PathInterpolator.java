/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.view.animation;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.InflateException;

import com.android.internal.R;

/**
 * An interpolator that can traverse a Path that extends from Point (0, 0) to (1, 1).
 *
 * teslalinux: AOSP flattens the Path with Path.approximate() (a native Skia call) into (fraction,
 * x, y) samples and interpolates linearly between them. ATL's Path is a GskPath that cannot be
 * sampled from Java, so the quadratic and cubic Bezier forms are sampled directly here at the same
 * precision (1/500 steps, matching AOSP's PRECISION of 0.002); the arbitrary-Path constructors are
 * not supported until Path.approximate exists.
 */
public class PathInterpolator extends BaseInterpolator {
	// This governs how accurate the approximation of the Path is.
	private static final float PRECISION = 0.002f;
	private static final int SAMPLES = 500;

	private float[] mX; // x coordinates in the line
	private float[] mY; // y coordinates in the line

	/** Create an interpolator for an arbitrary Path. */
	public PathInterpolator(Path path) {
		throw new UnsupportedOperationException("PathInterpolator(Path): Path.approximate is not available in this runtime; use the control-point constructors");
	}

	/** Create an interpolator for a quadratic Bezier curve. */
	public PathInterpolator(float controlX, float controlY) {
		initQuad(controlX, controlY);
	}

	/** Create an interpolator for a cubic Bezier curve. */
	public PathInterpolator(float controlX1, float controlY1, float controlX2, float controlY2) {
		initCubic(controlX1, controlY1, controlX2, controlY2);
	}

	public PathInterpolator(Context context, AttributeSet attrs) {
		this(context.getResources(), context.getTheme(), attrs);
	}

	/** @hide */
	public PathInterpolator(Resources res, Theme theme, AttributeSet attrs) {
		TypedArray a;
		if (theme != null) {
			a = theme.obtainStyledAttributes(attrs, R.styleable.PathInterpolator, 0, 0);
		} else {
			a = res.obtainAttributes(attrs, R.styleable.PathInterpolator);
		}
		parseInterpolatorFromTypeArray(a);
		setChangingConfiguration(a.getChangingConfigurations());
		a.recycle();
	}

	private void parseInterpolatorFromTypeArray(TypedArray a) {
		// If there is pathData defined in the xml file, then the controls points
		// will be all coming from pathData.
		if (a.hasValue(R.styleable.PathInterpolator_pathData)) {
			String pathData = a.getString(R.styleable.PathInterpolator_pathData);
			throw new InflateException("pathInterpolator with pathData is not supported in this runtime (no Path.approximate): " + pathData);
		} else {
			if (!a.hasValue(R.styleable.PathInterpolator_controlX1)) {
				throw new InflateException("pathInterpolator requires the controlX1 attribute");
			} else if (!a.hasValue(R.styleable.PathInterpolator_controlY1)) {
				throw new InflateException("pathInterpolator requires the controlY1 attribute");
			}
			float x1 = a.getFloat(R.styleable.PathInterpolator_controlX1, 0);
			float y1 = a.getFloat(R.styleable.PathInterpolator_controlY1, 0);

			boolean hasX2 = a.hasValue(R.styleable.PathInterpolator_controlX2);
			boolean hasY2 = a.hasValue(R.styleable.PathInterpolator_controlY2);

			if (hasX2 != hasY2) {
				throw new InflateException("pathInterpolator requires both controlX2 and controlY2 for cubic Beziers.");
			}

			if (!hasX2) {
				initQuad(x1, y1);
			} else {
				float x2 = a.getFloat(R.styleable.PathInterpolator_controlX2, 0);
				float y2 = a.getFloat(R.styleable.PathInterpolator_controlY2, 0);
				initCubic(x1, y1, x2, y2);
			}
		}
	}

	private void initQuad(float controlX, float controlY) {
		float[] pts = new float[(SAMPLES + 1) * 3];
		for (int i = 0; i <= SAMPLES; i++) {
			float t = (float) i / SAMPLES;
			float mt = 1 - t;
			pts[i * 3] = t;
			pts[i * 3 + 1] = 2 * mt * t * controlX + t * t;
			pts[i * 3 + 2] = 2 * mt * t * controlY + t * t;
		}
		initPoints(pts);
	}

	private void initCubic(float x1, float y1, float x2, float y2) {
		float[] pts = new float[(SAMPLES + 1) * 3];
		for (int i = 0; i <= SAMPLES; i++) {
			float t = (float) i / SAMPLES;
			float mt = 1 - t;
			pts[i * 3] = t;
			pts[i * 3 + 1] = 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t * t * t;
			pts[i * 3 + 2] = 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t * t * t;
		}
		initPoints(pts);
	}

	/** Same validation and layout as AOSP initPath() applied to (fraction, x, y) triples. */
	private void initPoints(float[] pointComponents) {
		int numPoints = pointComponents.length / 3;
		if (pointComponents[1] != 0 || pointComponents[2] != 0 || pointComponents[pointComponents.length - 2] != 1 || pointComponents[pointComponents.length - 1] != 1) {
			throw new IllegalArgumentException("The Path must start at (0,0) and end at (1,1)");
		}

		mX = new float[numPoints];
		mY = new float[numPoints];
		float prevX = 0;
		float prevFraction = 0;
		int componentIndex = 0;
		for (int i = 0; i < numPoints; i++) {
			float fraction = pointComponents[componentIndex++];
			float x = pointComponents[componentIndex++];
			float y = pointComponents[componentIndex++];
			if (fraction == prevFraction && x != prevX) {
				throw new IllegalArgumentException("The Path cannot have discontinuity in the X axis.");
			}
			if (x < prevX) {
				throw new IllegalArgumentException("The Path cannot loop back on itself.");
			}
			mX[i] = x;
			mY[i] = y;
			prevX = x;
			prevFraction = fraction;
		}
	}

	/** Using the line segment defined by the path, find the x,y coordinate that corresponds to the given time. */
	@Override
	public float getInterpolation(float t) {
		if (t <= 0) {
			return 0;
		} else if (t >= 1) {
			return 1;
		}
		// Do a binary search for the correct x to interpolate between.
		int startIndex = 0;
		int endIndex = mX.length - 1;

		while (endIndex - startIndex > 1) {
			int midIndex = (startIndex + endIndex) / 2;
			if (t < mX[midIndex]) {
				endIndex = midIndex;
			} else {
				startIndex = midIndex;
			}
		}

		float xRange = mX[endIndex] - mX[startIndex];
		if (xRange == 0) {
			return mY[startIndex];
		}

		float tInRange = t - mX[startIndex];
		float fraction = tInRange / xRange;

		float startY = mY[startIndex];
		float endY = mY[endIndex];
		return startY + (fraction * (endY - startY));
	}
}
