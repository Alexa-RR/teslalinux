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
 * AOSP DiscretePathEffect (Skia's SkDiscretePathEffect): each contour is chopped into segments of
 * about segmentLength whose points are moved randomly by up to deviation along the normal. The
 * random sequence is Skia's linear congruential generator seeded from the contour length, so a
 * path jitters the same way on every draw.
 */
public class DiscretePathEffect extends PathEffect {
	private final float mSegmentLength;
	private final float mDeviation;

	/**
	 * Chop the path into lines of segmentLength, randomly deviating from the original path by
	 * deviation.
	 */
	public DiscretePathEffect(float segmentLength, float deviation) {
		mSegmentLength = segmentLength;
		mDeviation = deviation;
	}

	@Override
	public Path tlApply(Path src) {
		if (mSegmentLength <= 0 || mDeviation <= 0)
			return src;
		Path dst = new Path();
		dst.setFillType(src.getFillType());
		PathMeasure pm = new PathMeasure(src, false);
		float[] pos = new float[2], tan = new float[2];
		do {
			float len = pm.getLength();
			int n = (int) (len / mSegmentLength);
			if (n < 1) {
				pm.getSegment(0, len, dst, true);
				continue;
			}
			int seed = Math.round(len);
			float step = len / n;
			boolean closed = pm.isClosed();
			for (int i = 0; i <= n; i++) {
				if (!pm.getPosTan(i * step, pos, tan))
					break;
				seed = seed * 1664525 + 1013904223;
				// Skia: a deviation in [-dev, dev], ends of an open contour stay put
				float r = ((seed >>> 16) / 65535f) * 2f - 1f;
				float off = (!closed && (i == 0 || i == n)) ? 0f : r * mDeviation;
				float x = pos[0] - tan[1] * off, y = pos[1] + tan[0] * off;
				if (i == 0)
					dst.moveTo(x, y);
				else if (closed && i == n)
					dst.close();
				else
					dst.lineTo(x, y);
			}
		} while (pm.nextContour());
		return dst;
	}
}
