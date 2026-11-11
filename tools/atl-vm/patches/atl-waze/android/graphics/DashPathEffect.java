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

/** AOSP DashPathEffect: alternating on/off intervals along each contour, offset by the phase. */
public class DashPathEffect extends PathEffect {
	private final float[] mIntervals;
	private final float mPhase;

	/**
	 * The intervals array must contain an even number of entries (>=2), with the even indices
	 * specifying the "on" intervals, and the odd indices specifying the "off" intervals.
	 * phase is an offset into the intervals array (mod the sum of all of the intervals).
	 */
	public DashPathEffect(float intervals[], float phase) {
		if (intervals.length < 2) {
			throw new ArrayIndexOutOfBoundsException();
		}
		mIntervals = intervals.clone();
		mPhase = phase;
	}

	public float[] tlIntervals() {
		return mIntervals;
	}

	public float tlPhase() {
		return mPhase;
	}

	@Override
	public Path tlApply(Path src) {
		int n = mIntervals.length & ~1; // Skia ignores an odd last interval
		float total = 0;
		for (int i = 0; i < n; i++) {
			if (mIntervals[i] < 0)
				return src; // Skia: invalid intervals leave the path unchanged
			total += mIntervals[i];
		}
		if (total <= 0 || n == 0)
			return src;
		float phase = mPhase % total;
		if (phase < 0)
			phase += total;
		Path dst = new Path();
		dst.setFillType(src.getFillType());
		PathMeasure pm = new PathMeasure(src, false);
		float[] pos = new float[2];
		do {
			float len = pm.getLength();
			if (len <= 0)
				continue;
			int idx = 0;
			float p = phase;
			while (p >= mIntervals[idx]) {
				p -= mIntervals[idx];
				idx = (idx + 1) % n;
			}
			float rem = mIntervals[idx] - p;
			float d = 0;
			int guard = 0;
			while (d < len && guard++ < 1000000) {
				float seg = Math.min(rem, len - d);
				if ((idx & 1) == 0) {
					if (seg > 0) {
						pm.getSegment(d, d + seg, dst, true);
					} else if (pm.getPosTan(d, pos, null)) {
						// a zero-length dash: a degenerate segment, so round or square caps draw a dot
						dst.moveTo(pos[0], pos[1]);
						dst.lineTo(pos[0], pos[1]);
					}
				}
				d += seg;
				idx = (idx + 1) % n;
				rem = mIntervals[idx];
			}
		} while (pm.nextContour());
		return dst;
	}
}
