/*
 * Copyright (C) 2022 The Android Open Source Project
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

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * teslalinux: AOSP's PathIterator (API 34) over the operations of the GskPath behind a Path,
 * captured when the iterator is created (native/atl_gfx_path.c).
 *
 * Points per verb, as AOSP: MOVE 1, LINE 2 (start, end), QUAD 3, CONIC 3 plus the weight in the
 * seventh float, CUBIC 4, CLOSE 2 (the last point and the contour start), DONE 0.
 */
public class PathIterator implements Iterator<PathIterator.Segment> {

	public static final int VERB_MOVE = 0;
	public static final int VERB_LINE = 1;
	public static final int VERB_QUAD = 2;
	public static final int VERB_CONIC = 3;
	public static final int VERB_CUBIC = 4;
	public static final int VERB_CLOSE = 5;
	public static final int VERB_DONE = 6;

	private final Path mPath;
	private final float[] mOps;
	private int mIndex;
	private final float[] mPointsArray = new float[8];

	PathIterator(Path path) {
		mPath = path;
		mOps = path.tlOps();
	}

	private static int verbOf(int gskOp) {
		switch (gskOp) {
			case Path.OP_MOVE: return VERB_MOVE;
			case Path.OP_CLOSE: return VERB_CLOSE;
			case Path.OP_LINE: return VERB_LINE;
			case Path.OP_QUAD: return VERB_QUAD;
			case Path.OP_CUBIC: return VERB_CUBIC;
			case Path.OP_CONIC: return VERB_CONIC;
			default: return VERB_DONE;
		}
	}

	private static int pointCount(int verb) {
		switch (verb) {
			case VERB_MOVE: return 1;
			case VERB_LINE: return 2;
			case VERB_QUAD: return 3;
			case VERB_CONIC: return 3;
			case VERB_CUBIC: return 4;
			case VERB_CLOSE: return 2;
			default: return 0;
		}
	}

	/**
	 * Returns the next verb in this iterator's Path, and fills entries in the array with the
	 * points, if any, for that verb. The array must hold at least 8 floats past offset.
	 */
	public int next(float[] points, int offset) {
		if (points.length < offset + 8) {
			throw new ArrayIndexOutOfBoundsException("points array must be able to hold at least 8 entries");
		}
		if (mIndex * Path.OP_SIZE >= mOps.length)
			return VERB_DONE;
		int o = mIndex++ * Path.OP_SIZE;
		int verb = verbOf((int) mOps[o]);
		int n = pointCount(verb);
		System.arraycopy(mOps, o + 2, points, offset, n * 2);
		if (verb == VERB_CONIC)
			points[offset + 6] = mOps[o + 1];
		return verb;
	}

	/** Returns true if the there are more elements in this iterator to be returned. */
	@Override
	public boolean hasNext() {
		return mIndex * Path.OP_SIZE < mOps.length;
	}

	/** Returns the next verb in the iteration, or VERB_DONE, without advancing. */
	public int peek() {
		if (mIndex * Path.OP_SIZE >= mOps.length)
			return VERB_DONE;
		return verbOf((int) mOps[mIndex * Path.OP_SIZE]);
	}

	/** Returns the next Segment, with its verb, points and conic weight. */
	@Override
	public Segment next() {
		if (!hasNext())
			throw new NoSuchElementException();
		int verb = next(mPointsArray, 0);
		float conicWeight = verb == VERB_CONIC ? mPointsArray[6] : 0f;
		float[] pts = new float[pointCount(verb) * 2];
		System.arraycopy(mPointsArray, 0, pts, 0, pts.length);
		return new Segment(verb, pts, conicWeight);
	}

	/** One segment of a path: its verb, points and (for conics) weight. */
	public static class Segment {
		private final int mVerb;
		private final float[] mPoints;
		private final float mConicWeight;

		public int getVerb() {
			return mVerb;
		}

		public float[] getPoints() {
			return mPoints;
		}

		public float getConicWeight() {
			return mConicWeight;
		}

		Segment(int verb, float[] points, float conicWeight) {
			mVerb = verb;
			mPoints = points;
			mConicWeight = conicWeight;
		}
	}
}
