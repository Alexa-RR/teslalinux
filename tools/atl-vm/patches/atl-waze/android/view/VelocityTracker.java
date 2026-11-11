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

package android.view;

import android.util.ArrayMap;
import android.util.Pools.SynchronizedPool;
import java.util.Map;

/**
 * teslalinux: AOSP's VelocityTracker (frameworks/base core/java/android/view/VelocityTracker.java,
 * android15) with the native side (frameworks/native libs/input/VelocityTracker.cpp, android14) ported
 * to Java: per-axis strategies (LSQ2 for X/Y, impulse for AXIS_SCROLL by default), every pointer
 * tracked by id, the 40 ms "pointer stopped" rule, units and maximum velocity as AOSP applies them.
 * ATL's version took the straight line between the first and the last sample, ignored the pointer
 * id and the maximum velocity, and clear()/computeCurrentVelocity() did nothing.
 */
public final class VelocityTracker {
	private static final SynchronizedPool<VelocityTracker> sPool =
		new SynchronizedPool<VelocityTracker>(2);

	private static final int ACTIVE_POINTER_ID = -1;

	public static final int VELOCITY_TRACKER_STRATEGY_DEFAULT = -1;
	public static final int VELOCITY_TRACKER_STRATEGY_IMPULSE = 0;
	public static final int VELOCITY_TRACKER_STRATEGY_LSQ1 = 1;
	public static final int VELOCITY_TRACKER_STRATEGY_LSQ2 = 2;
	public static final int VELOCITY_TRACKER_STRATEGY_LSQ3 = 3;
	public static final int VELOCITY_TRACKER_STRATEGY_WLSQ2_DELTA = 4;
	public static final int VELOCITY_TRACKER_STRATEGY_WLSQ2_CENTRAL = 5;
	public static final int VELOCITY_TRACKER_STRATEGY_WLSQ2_RECENT = 6;
	public static final int VELOCITY_TRACKER_STRATEGY_INT1 = 7;
	public static final int VELOCITY_TRACKER_STRATEGY_INT2 = 8;
	public static final int VELOCITY_TRACKER_STRATEGY_LEGACY = 9;

	private static final Map<String, Integer> STRATEGIES = new ArrayMap<>();

	static {
		// Strategy string and IDs mapping lookup.
		STRATEGIES.put("impulse", VELOCITY_TRACKER_STRATEGY_IMPULSE);
		STRATEGIES.put("lsq1", VELOCITY_TRACKER_STRATEGY_LSQ1);
		STRATEGIES.put("lsq2", VELOCITY_TRACKER_STRATEGY_LSQ2);
		STRATEGIES.put("lsq3", VELOCITY_TRACKER_STRATEGY_LSQ3);
		STRATEGIES.put("wlsq2-delta", VELOCITY_TRACKER_STRATEGY_WLSQ2_DELTA);
		STRATEGIES.put("wlsq2-central", VELOCITY_TRACKER_STRATEGY_WLSQ2_CENTRAL);
		STRATEGIES.put("wlsq2-recent", VELOCITY_TRACKER_STRATEGY_WLSQ2_RECENT);
		STRATEGIES.put("int1", VELOCITY_TRACKER_STRATEGY_INT1);
		STRATEGIES.put("int2", VELOCITY_TRACKER_STRATEGY_INT2);
		STRATEGIES.put("legacy", VELOCITY_TRACKER_STRATEGY_LEGACY);
	}

	private static int toStrategyId(String strStrategy) {
		if (STRATEGIES.containsKey(strStrategy)) {
			return STRATEGIES.get(strStrategy);
		}
		return VELOCITY_TRACKER_STRATEGY_DEFAULT;
	}

	private final int mStrategy;
	private final Native mNative;

	/**
	 * Retrieve a new VelocityTracker object to watch the velocity of a motion. Be sure to call
	 * {@link #recycle} when done.
	 */
	static public VelocityTracker obtain() {
		VelocityTracker instance = sPool.acquire();
		return (instance != null) ? instance
					  : new VelocityTracker(VELOCITY_TRACKER_STRATEGY_DEFAULT);
	}

	/** @hide */
	@Deprecated
	public static VelocityTracker obtain(String strategy) {
		if (strategy == null) {
			return obtain();
		}
		return new VelocityTracker(toStrategyId(strategy));
	}

	/** @hide */
	public static VelocityTracker obtain(int strategy) {
		return new VelocityTracker(strategy);
	}

	public void recycle() {
		if (mStrategy == VELOCITY_TRACKER_STRATEGY_DEFAULT) {
			clear();
			sPool.release(this);
		}
	}

	/** @hide */
	public int getStrategyId() {
		return mStrategy;
	}

	private VelocityTracker(int strategy) {
		// AOSP lets the debug.velocitytracker.strategy property override the default; there is no
		// such property here, so the default stays the default.
		mStrategy = strategy;
		mNative = new Native(strategy);
	}

	/** Checks whether a given velocity-trackable MotionEvent axis is supported for velocity tracking. */
	public boolean isAxisSupported(int axis) {
		return axis == MotionEvent.AXIS_X || axis == MotionEvent.AXIS_Y || axis == MotionEvent.AXIS_SCROLL;
	}

	/** Reset the velocity tracker back to its initial state. */
	public void clear() {
		mNative.clear();
	}

	/** Add a user's movement to the tracker. */
	public void addMovement(MotionEvent event) {
		if (event == null) {
			throw new IllegalArgumentException("event must not be null");
		}
		mNative.addMovement(event);
	}

	public void computeCurrentVelocity(int units) {
		mNative.computeCurrentVelocity(units, Float.MAX_VALUE);
	}

	public void computeCurrentVelocity(int units, float maxVelocity) {
		mNative.computeCurrentVelocity(units, maxVelocity);
	}

	public float getXVelocity() {
		return getXVelocity(ACTIVE_POINTER_ID);
	}

	public float getYVelocity() {
		return getYVelocity(ACTIVE_POINTER_ID);
	}

	public float getXVelocity(int id) {
		return mNative.getComputedVelocity(MotionEvent.AXIS_X, id);
	}

	public float getYVelocity(int id) {
		return mNative.getComputedVelocity(MotionEvent.AXIS_Y, id);
	}

	/**
	 * The velocity of the axis and pointer as of the last {@link #computeCurrentVelocity} call (the
	 * JNI's VelocityTrackerState keeps what was computed), 0 when it has none.
	 */
	public float getAxisVelocity(int axis, int id) {
		return mNative.getComputedVelocity(axis, id);
	}

	public float getAxisVelocity(int axis) {
		return mNative.getComputedVelocity(axis, ACTIVE_POINTER_ID);
	}

	// ---------------------------------------------------------------------------------------------
	// frameworks/native libs/input/VelocityTracker.cpp (+ the JNI glue android_view_VelocityTracker.cpp)

	private static final long NANOS_PER_MS = 1000000L;
	/** Threshold for determining that a pointer has stopped moving. */
	private static final long ASSUME_POINTER_STOPPED_TIME = 40 * NANOS_PER_MS;
	private static final int MAX_POINTERS = 32;

	private static boolean isPlanarAxis(int axis) {
		return axis == MotionEvent.AXIS_X || axis == MotionEvent.AXIS_Y;
	}

	private static boolean isDifferentialAxis(int axis) {
		return axis == MotionEvent.AXIS_SCROLL;
	}

	private static abstract class Strategy {
		abstract void addMovement(long eventTime, int pointerId, float position);
		abstract void clearPointer(int pointerId);
		/** NaN when there is no estimate. */
		abstract float getVelocity(int pointerId);
	}

	/** The JNI side: VelocityTracker plus the computed velocities (VelocityTrackerState). */
	private static final class Native {
		private final int mOverrideStrategy;
		private long mLastEventTime;
		private int mCurrentPointerIdBits;
		private int mActivePointerId = -1; // -1: none
		private final android.util.SparseArray<Strategy> mConfiguredStrategies = new android.util.SparseArray<>();
		/** computed velocities: axis -> (pointer id -> velocity) */
		private final android.util.SparseArray<android.util.SparseArray<Float>> mComputed = new android.util.SparseArray<>();

		Native(int strategy) {
			mOverrideStrategy = strategy;
		}

		void clear() {
			mCurrentPointerIdBits = 0;
			mActivePointerId = -1;
			mConfiguredStrategies.clear();
		}

		void clearPointer(int pointerId) {
			mCurrentPointerIdBits &= ~(1 << pointerId);
			if (mActivePointerId == pointerId) {
				// The active pointer id is being removed. Mark it invalid and try to find a new one
				// from the remaining pointers.
				mActivePointerId = mCurrentPointerIdBits == 0 ? -1 : Integer.numberOfTrailingZeros(mCurrentPointerIdBits);
			}
			for (int i = 0; i < mConfiguredStrategies.size(); i++)
				mConfiguredStrategies.valueAt(i).clearPointer(pointerId);
		}

		private Strategy configureStrategy(int axis) {
			final boolean isDifferential = isDifferentialAxis(axis);
			Strategy s;
			if (mOverrideStrategy != VELOCITY_TRACKER_STRATEGY_DEFAULT) {
				s = createStrategy(mOverrideStrategy, isDifferential);
			} else {
				s = createStrategy(isPlanarAxis(axis) ? VELOCITY_TRACKER_STRATEGY_LSQ2 : VELOCITY_TRACKER_STRATEGY_IMPULSE, isDifferential);
			}
			mConfiguredStrategies.put(axis, s);
			return s;
		}

		private static Strategy createStrategy(int strategy, boolean deltaValues) {
			switch (strategy) {
				case VELOCITY_TRACKER_STRATEGY_IMPULSE:
					return new ImpulseStrategy(deltaValues);
				case VELOCITY_TRACKER_STRATEGY_LSQ1:
					return new LeastSquaresStrategy(1, LeastSquaresStrategy.WEIGHTING_NONE);
				case VELOCITY_TRACKER_STRATEGY_LSQ3:
					return new LeastSquaresStrategy(3, LeastSquaresStrategy.WEIGHTING_NONE);
				case VELOCITY_TRACKER_STRATEGY_WLSQ2_DELTA:
					return new LeastSquaresStrategy(2, LeastSquaresStrategy.WEIGHTING_DELTA);
				case VELOCITY_TRACKER_STRATEGY_WLSQ2_CENTRAL:
					return new LeastSquaresStrategy(2, LeastSquaresStrategy.WEIGHTING_CENTRAL);
				case VELOCITY_TRACKER_STRATEGY_WLSQ2_RECENT:
					return new LeastSquaresStrategy(2, LeastSquaresStrategy.WEIGHTING_RECENT);
				case VELOCITY_TRACKER_STRATEGY_INT1:
					return new IntegratingStrategy(1);
				case VELOCITY_TRACKER_STRATEGY_INT2:
					return new IntegratingStrategy(2);
				case VELOCITY_TRACKER_STRATEGY_LEGACY:
					return new LegacyStrategy();
				case VELOCITY_TRACKER_STRATEGY_LSQ2:
				default:
					return new LeastSquaresStrategy(2, LeastSquaresStrategy.WEIGHTING_NONE);
			}
		}

		void addMovement(long eventTime, int pointerId, int axis, float position) {
			if ((mCurrentPointerIdBits & (1 << pointerId)) != 0 && eventTime - mLastEventTime > ASSUME_POINTER_STOPPED_TIME) {
				// We have not received any movements for too long. Assume that all pointers
				// have stopped.
				mConfiguredStrategies.clear();
			}
			mLastEventTime = eventTime;
			mCurrentPointerIdBits |= 1 << pointerId;
			if (mActivePointerId < 0) {
				// Let this be the new active pointer if no active pointer is currently set
				mActivePointerId = pointerId;
			}
			Strategy s = mConfiguredStrategies.get(axis);
			if (s == null)
				s = configureStrategy(axis);
			s.addMovement(eventTime, pointerId, position);
		}

		void addMovement(MotionEvent event) {
			final int actionMasked = event.getActionMasked();
			int[] axes;
			switch (actionMasked) {
				case MotionEvent.ACTION_DOWN:
				case MotionEvent.ACTION_HOVER_ENTER:
					// Clear all pointers on down before adding the new movement.
					clear();
					axes = new int[] {MotionEvent.AXIS_X, MotionEvent.AXIS_Y};
					break;
				case MotionEvent.ACTION_POINTER_DOWN: {
					// Start a new movement trace for a pointer that just went down.
					// We do this on down instead of on up because the client may want to query the
					// final velocity for a pointer that just went up.
					clearPointer(event.getPointerId(event.getActionIndex()));
					axes = new int[] {MotionEvent.AXIS_X, MotionEvent.AXIS_Y};
					break;
				}
				case MotionEvent.ACTION_MOVE:
				case MotionEvent.ACTION_HOVER_MOVE:
					axes = new int[] {MotionEvent.AXIS_X, MotionEvent.AXIS_Y};
					break;
				case MotionEvent.ACTION_POINTER_UP:
				case MotionEvent.ACTION_UP: {
					long delaySinceLastEvent = event.getEventTimeNanos() - mLastEventTime;
					if (delaySinceLastEvent > ASSUME_POINTER_STOPPED_TIME) {
						// We have not received any movements for too long. Assume that all pointers
						// have stopped.
						mConfiguredStrategies.remove(MotionEvent.AXIS_X);
						mConfiguredStrategies.remove(MotionEvent.AXIS_Y);
					}
					// These actions because they typically contain only stale data (positions
					// identical to the last MOVE): the velocity is computed from the earlier samples.
					return;
				}
				case MotionEvent.ACTION_SCROLL:
					axes = new int[] {MotionEvent.AXIS_SCROLL};
					break;
				default:
					// Ignore all other actions.
					return;
			}

			final int historySize = event.getHistorySize();
			final int pointerCount = event.getPointerCount();
			for (int h = 0; h <= historySize; h++) {
				final long eventTime = h == historySize ? event.getEventTimeNanos() : event.getHistoricalEventTimeNanos(h);
				for (int i = 0; i < pointerCount; i++) {
					final int pointerId = event.getPointerId(i);
					if (pointerId < 0 || pointerId >= MAX_POINTERS)
						continue;
					for (int axis : axes) {
						final float position = h == historySize ? event.getAxisValue(axis, i) : event.getHistoricalAxisValue(axis, i, h);
						addMovement(eventTime, pointerId, axis, position);
					}
				}
			}
		}

		/** NaN when there is no velocity for this axis and pointer. */
		float getVelocity(int axis, int pointerId) {
			Strategy s = mConfiguredStrategies.get(axis);
			if (s == null)
				return Float.NaN;
			if (pointerId == ACTIVE_POINTER_ID)
				pointerId = mActivePointerId;
			if (pointerId < 0)
				return Float.NaN;
			return s.getVelocity(pointerId);
		}

		/** VelocityTracker::getComputedVelocity, stored as the JNI's VelocityTrackerState does. */
		void computeCurrentVelocity(int units, float maxVelocity) {
			mComputed.clear();
			for (int a = 0; a < mConfiguredStrategies.size(); a++) {
				final int axis = mConfiguredStrategies.keyAt(a);
				android.util.SparseArray<Float> perPointer = new android.util.SparseArray<>();
				int bits = mCurrentPointerIdBits;
				while (bits != 0) {
					final int id = Integer.numberOfTrailingZeros(bits);
					bits &= ~(1 << id);
					float velocity = getVelocity(axis, id);
					if (Float.isNaN(velocity))
						continue;
					// Velocity is in pixels per millisecond * units, clamped to +-maxVelocity.
					float adjusted = velocity * units / 1000;
					adjusted = Math.max(-maxVelocity, Math.min(maxVelocity, adjusted));
					perPointer.put(id, adjusted);
				}
				mComputed.put(axis, perPointer);
			}
		}

		/** The velocity computed by the last computeCurrentVelocity, 0 when there is none. */
		float getComputedVelocity(int axis, int id) {
			if (id == ACTIVE_POINTER_ID)
				id = mActivePointerId;
			android.util.SparseArray<Float> perPointer = mComputed.get(axis);
			if (perPointer == null)
				return 0;
			Float v = perPointer.get(id);
			return v == null ? 0 : v;
		}
	}

	// --- least squares ---------------------------------------------------------------------------

	private static final class Movement {
		long eventTime;
		float position;
	}

	/**
	 * Velocity tracker algorithm based on least-squares linear regression (LSQ1/2/3, WLSQ2).
	 */
	private static final class LeastSquaresStrategy extends Strategy {
		static final int WEIGHTING_NONE = 0, WEIGHTING_DELTA = 1, WEIGHTING_CENTRAL = 2, WEIGHTING_RECENT = 3;
		// Sample horizon: we don't use samples older than this.
		private static final long HORIZON = 100 * NANOS_PER_MS;
		// Number of samples to keep.
		private static final int HISTORY_SIZE = 20;

		private final int mDegree;
		private final int mWeighting;
		private final android.util.SparseArray<Movement[]> mMovements = new android.util.SparseArray<>();
		private final android.util.SparseIntArray mIndex = new android.util.SparseIntArray();

		LeastSquaresStrategy(int degree, int weighting) {
			mDegree = degree;
			mWeighting = weighting;
		}

		@Override
		void clearPointer(int pointerId) {
			mIndex.delete(pointerId);
			mMovements.remove(pointerId);
		}

		@Override
		void addMovement(long eventTime, int pointerId, float position) {
			Movement[] movements = mMovements.get(pointerId);
			boolean inserted = false;
			if (movements == null) {
				movements = new Movement[HISTORY_SIZE];
				for (int i = 0; i < HISTORY_SIZE; i++)
					movements[i] = new Movement();
				mMovements.put(pointerId, movements);
				mIndex.put(pointerId, 0);
				inserted = true;
			}
			int index = mIndex.get(pointerId, 0);
			if (!inserted && movements[index].eventTime != eventTime) {
				// When ACTION_POINTER_DOWN happens, we will first receive ACTION_MOVE with the coordinates
				// of the existing pointers, and then ACTION_POINTER_DOWN with the coordinates that include
				// the new pointer. If the eventtimes for both events are identical, just update the data
				// for this time (i.e. pretend that the second ACTION_POINTER_DOWN event is the only one).
				index++;
			}
			if (index == HISTORY_SIZE) {
				index = 0;
			}
			mIndex.put(pointerId, index);
			Movement movement = movements[index];
			movement.eventTime = eventTime;
			movement.position = position;
		}

		private float weightFor(Movement[] movements, int index, int newestIndex, long newestTime) {
			switch (mWeighting) {
				case WEIGHTING_DELTA: {
					// Weight points based on how much time elapsed between them and the next
					// point so that points that "cover" a shorter time span are weighed less.
					//   delta  0ms: 0.5
					//   delta 10ms: 1.0
					if (index == newestIndex) {
						return 1.0f;
					}
					int nextIndex = (index + 1) % HISTORY_SIZE;
					float deltaMillis = (movements[nextIndex].eventTime - movements[index].eventTime) * 0.000001f;
					if (deltaMillis < 0) {
						return 0.5f;
					}
					if (deltaMillis < 10) {
						return 0.5f + deltaMillis * 0.05f;
					}
					return 1.0f;
				}
				case WEIGHTING_CENTRAL: {
					// Weight points based on their age, weighing very recent and very old points less.
					//   age  0ms: 0.5
					//   age 10ms: 1.0
					//   age 50ms: 1.0
					//   age 60ms: 0.5
					float ageMillis = (newestTime - movements[index].eventTime) * 0.000001f;
					if (ageMillis < 0) {
						return 0.5f;
					}
					if (ageMillis < 10) {
						return 0.5f + ageMillis * 0.05f;
					}
					if (ageMillis < 50) {
						return 1.0f;
					}
					if (ageMillis < 60) {
						return 0.5f + (60 - ageMillis) * 0.05f;
					}
					return 0.5f;
				}
				case WEIGHTING_RECENT: {
					// Weight points based on their age, weighing older points less.
					//   age   0ms: 1.0
					//   age  50ms: 1.0
					//   age 100ms: 0.5
					float ageMillis = (newestTime - movements[index].eventTime) * 0.000001f;
					if (ageMillis < 50) {
						return 1.0f;
					}
					if (ageMillis < 100) {
						return 0.5f + (100 - ageMillis) * 0.01f;
					}
					return 0.5f;
				}
				case WEIGHTING_NONE:
				default:
					return 1.0f;
			}
		}

		@Override
		float getVelocity(int pointerId) {
			Movement[] movements = mMovements.get(pointerId);
			if (movements == null)
				return Float.NaN; // no data
			// Iterate over movement samples in reverse time order and collect samples.
			float[] positions = new float[HISTORY_SIZE];
			float[] w = new float[HISTORY_SIZE];
			float[] time = new float[HISTORY_SIZE];
			int m = 0;
			final int newestIndex = mIndex.get(pointerId);
			int index = newestIndex;
			final Movement newestMovement = movements[newestIndex];
			do {
				final Movement movement = movements[index];
				if (movement.eventTime == 0 && index != 0) {
					// All entries of a fresh history are zero-initialised: reaching one means there
					// is no older data.
					break;
				}
				final long age = newestMovement.eventTime - movement.eventTime;
				if (age > HORIZON) {
					break;
				}
				positions[m] = movement.position;
				w[m] = weightFor(movements, index, newestIndex, newestMovement.eventTime);
				time[m] = -age * 0.000000001f;
				m++;
				index = (index == 0 ? HISTORY_SIZE : index) - 1;
			} while (m < HISTORY_SIZE);

			if (m == 0) {
				return Float.NaN; // no data
			}

			// Calculate a least squares polynomial fit.
			int degree = mDegree;
			if (degree > m - 1) {
				degree = m - 1;
			}
			if (degree <= 0) {
				// Degree 0 estimate: position only, no velocity.
				return Float.NaN;
			}
			if (degree == 2 && mWeighting == WEIGHTING_NONE) {
				// Optimize unweighted, quadratic polynomial fit
				float[] coeff = solveUnweightedLeastSquaresDeg2(time, positions, m);
				if (coeff != null) {
					return coeff[1];
				}
				return Float.NaN;
			}
			// General case for an Nth degree polynomial fit
			float[] outB = new float[degree + 1];
			if (solveLeastSquares(time, positions, w, m, degree + 1, outB)) {
				return outB[1];
			}
			return Float.NaN;
		}

		/**
		 * Solves a linear least squares problem to obtain a N degree polynomial that fits
		 * the specified input data as nearly as possible (QR decomposition by Gram-Schmidt).
		 */
		private static boolean solveLeastSquares(float[] x, float[] y, float[] w, int m, int n, float[] outB) {
			// Expand the X vector to a matrix A, pre-multiplied by the weights.
			float[][] a = new float[n][m]; // column-major order
			for (int h = 0; h < m; h++) {
				a[0][h] = w[h];
				for (int i = 1; i < n; i++) {
					a[i][h] = a[i - 1][h] * x[h];
				}
			}

			// Apply the Gram-Schmidt process to A to obtain its QR decomposition.
			float[][] q = new float[n][m]; // orthonormal basis, column-major order
			float[][] r = new float[n][n]; // upper triangular matrix, row-major order
			for (int j = 0; j < n; j++) {
				for (int h = 0; h < m; h++) {
					q[j][h] = a[j][h];
				}
				for (int i = 0; i < j; i++) {
					float dot = vectorDot(q[j], q[i], m);
					for (int h = 0; h < m; h++) {
						q[j][h] -= dot * q[i][h];
					}
				}

				float norm = vectorNorm(q[j], m);
				if (norm < 0.000001f) {
					// vectors are linearly dependent or zero so no solution
					return false;
				}

				float invNorm = 1.0f / norm;
				for (int h = 0; h < m; h++) {
					q[j][h] *= invNorm;
				}
				for (int i = 0; i < n; i++) {
					r[j][i] = i < j ? 0 : vectorDot(q[j], a[i], m);
				}
			}

			// Solve R B = Qt W Y to find B. This is easy because R is upper triangular.
			// We just work from bottom-right to top-left calculating B's coefficients.
			float[] wy = new float[m];
			for (int h = 0; h < m; h++) {
				wy[h] = y[h] * w[h];
			}
			for (int i = n; i != 0;) {
				i--;
				outB[i] = vectorDot(q[i], wy, m);
				for (int j = n - 1; j > i; j--) {
					outB[i] -= r[i][j] * outB[j];
				}
				outB[i] /= r[i][i];
			}
			return true;
		}

		/**
		 * Optimized unweighted second-order least squares fit. About 2x speed improvement compared to
		 * the default implementation. Returns {c, b, a} for y = a*x^2 + b*x + c, or null.
		 */
		private static float[] solveUnweightedLeastSquaresDeg2(float[] x, float[] y, int count) {
			// Solving y = a*x^2 + b*x + c
			float sxi = 0, sxiyi = 0, syi = 0, sxi2 = 0, sxi3 = 0, sxi2yi = 0, sxi4 = 0;
			for (int i = 0; i < count; i++) {
				float xi = x[i];
				float yi = y[i];
				float xi2 = xi * xi;
				float xi3 = xi2 * xi;
				float xi4 = xi3 * xi;
				float xiyi = xi * yi;
				float xi2yi = xi2 * yi;

				sxi += xi;
				sxi2 += xi2;
				sxiyi += xiyi;
				sxi2yi += xi2yi;
				syi += yi;
				sxi3 += xi3;
				sxi4 += xi4;
			}

			float Sxx = sxi2 - sxi * sxi / count;
			float Sxy = sxiyi - sxi * syi / count;
			float Sxx2 = sxi3 - sxi * sxi2 / count;
			float Sx2y = sxi2yi - sxi2 * syi / count;
			float Sx2x2 = sxi4 - sxi2 * sxi2 / count;

			float denominator = Sxx * Sx2x2 - Sxx2 * Sxx2;
			if (denominator == 0) {
				return null;
			}
			// Compute a
			float numerator = Sx2y * Sxx - Sxy * Sxx2;
			float a = numerator / denominator;

			// Compute b
			numerator = Sxy * Sx2x2 - Sx2y * Sxx2;
			float b = numerator / denominator;

			// Compute c
			float c = syi / count - b * sxi / count - a * sxi2 / count;

			return new float[] {c, b, a};
		}

		private static float vectorDot(float[] a, float[] b, int m) {
			float r = 0;
			for (int i = 0; i < m; i++) {
				r += a[i] * b[i];
			}
			return r;
		}

		private static float vectorNorm(float[] a, int m) {
			float r = 0;
			for (int i = 0; i < m; i++) {
				float t = a[i];
				r += t * t;
			}
			return (float)Math.sqrt(r);
		}
	}

	// --- integrating -----------------------------------------------------------------------------

	/**
	 * Velocity tracker algorithm that uses an IIR filter (INT1, INT2).
	 */
	private static final class IntegratingStrategy extends Strategy {
		// Minimum time difference between consecutive samples (ns).
		private static final long MIN_TIME_DELTA = 2 * NANOS_PER_MS;
		// Filter time constant (s).
		private static final float FILTER_TIME_CONSTANT = 0.010f;

		private static final class State {
			long updateTime;
			int degree;
			float pos, vel, accel;
		}

		private final int mDegree;
		private final android.util.SparseArray<State> mPointerState = new android.util.SparseArray<>();

		IntegratingStrategy(int degree) {
			mDegree = degree;
		}

		@Override
		void clearPointer(int pointerId) {
			mPointerState.remove(pointerId);
		}

		@Override
		void addMovement(long eventTime, int pointerId, float position) {
			State state = mPointerState.get(pointerId);
			if (state == null) {
				state = new State();
				mPointerState.put(pointerId, state);
				// initState
				state.updateTime = eventTime;
				state.degree = 0;
				state.pos = position;
				state.accel = 0;
				state.vel = 0;
				return;
			}
			// updateState
			if (eventTime <= state.updateTime + MIN_TIME_DELTA) {
				return;
			}
			float dt = (eventTime - state.updateTime) * 0.000000001f;
			state.updateTime = eventTime;

			float vel = (position - state.pos) / dt;
			if (state.degree == 0) {
				state.vel = vel;
				state.degree = 1;
			} else {
				float alpha = dt / (FILTER_TIME_CONSTANT + dt);
				if (mDegree == 1) {
					state.vel += (vel - state.vel) * alpha;
				} else {
					float accel = (vel - state.vel) / dt;
					if (state.degree == 1) {
						state.accel = accel;
						state.degree = 2;
					} else {
						state.accel += (accel - state.accel) * alpha;
					}
					state.vel += (state.accel * dt) * alpha;
				}
			}
			state.pos = position;
		}

		@Override
		float getVelocity(int pointerId) {
			State state = mPointerState.get(pointerId);
			if (state == null)
				return Float.NaN;
			return state.vel;
		}
	}

	// --- legacy ----------------------------------------------------------------------------------

	/**
	 * Velocity tracker strategy used prior to ICS.
	 */
	private static final class LegacyStrategy extends Strategy {
		// Oldest sample to consider when calculating the velocity.
		private static final long HORIZON = 200 * NANOS_PER_MS;
		// Number of samples to keep.
		private static final int HISTORY_SIZE = 20;
		// The minimum duration between samples when estimating velocity.
		private static final long MIN_DURATION = 10 * NANOS_PER_MS;

		private final android.util.SparseArray<Movement[]> mMovements = new android.util.SparseArray<>();
		private final android.util.SparseIntArray mIndex = new android.util.SparseIntArray();
		private final android.util.SparseIntArray mCount = new android.util.SparseIntArray();

		@Override
		void clearPointer(int pointerId) {
			mMovements.remove(pointerId);
			mIndex.delete(pointerId);
			mCount.delete(pointerId);
		}

		@Override
		void addMovement(long eventTime, int pointerId, float position) {
			Movement[] movements = mMovements.get(pointerId);
			int index;
			if (movements == null) {
				movements = new Movement[HISTORY_SIZE];
				for (int i = 0; i < HISTORY_SIZE; i++)
					movements[i] = new Movement();
				mMovements.put(pointerId, movements);
				index = 0;
			} else {
				index = (mIndex.get(pointerId) + 1) % HISTORY_SIZE;
			}
			mIndex.put(pointerId, index);
			mCount.put(pointerId, Math.min(HISTORY_SIZE, mCount.get(pointerId) + 1));
			movements[index].eventTime = eventTime;
			movements[index].position = position;
		}

		@Override
		float getVelocity(int pointerId) {
			Movement[] movements = mMovements.get(pointerId);
			if (movements == null)
				return Float.NaN;
			final int newestIndex = mIndex.get(pointerId);
			final int available = mCount.get(pointerId);
			final Movement newestMovement = movements[newestIndex];

			// Find the oldest sample that contains the pointer and that is not older than HORIZON.
			long minTime = newestMovement.eventTime - HORIZON;
			int oldestIndex = newestIndex;
			int numTouches = 1;
			do {
				if (numTouches >= available)
					break;
				int nextOldestIndex = (oldestIndex == 0 ? HISTORY_SIZE : oldestIndex) - 1;
				final Movement nextOldestMovement = movements[nextOldestIndex];
				if (nextOldestMovement.eventTime < minTime) {
					break;
				}
				oldestIndex = nextOldestIndex;
			} while (++numTouches < HISTORY_SIZE);

			// Calculate an exponentially weighted moving average of the velocity estimate
			// at different points in time measured relative to the oldest sample.
			// This is essentially an IIR filter. Newer samples are weighted more heavily
			// than older samples. Samples at equal time points are weighted more or less
			// equally.
			//
			// One tricky problem is that the sample data may be poorly conditioned.
			// Sometimes samples arrive very close together in time which can cause us to
			// overestimate the velocity at that time point. Most samples might be measured
			// 16ms apart but some consecutive samples could be only 0.5sm apart because
			// the hardware or driver reports them irregularly or in bursts.
			float accumV = 0;
			int index = oldestIndex;
			int samplesUsed = 0;
			final Movement oldestMovement = movements[oldestIndex];
			float oldestPosition = oldestMovement.position;
			long lastDuration = 0;

			while (numTouches-- > 1) {
				if (++index == HISTORY_SIZE) {
					index = 0;
				}
				final Movement movement = movements[index];
				long duration = movement.eventTime - oldestMovement.eventTime;

				// If the duration between samples is small, we may significantly overestimate
				// the velocity. Consequently, we impose a minimum duration constraint on the
				// samples that we include in the calculation.
				if (duration >= MIN_DURATION) {
					float position = movement.position;
					float scale = 1000000000.0f / duration; // one over time delta in seconds
					float v = (position - oldestPosition) * scale;
					accumV = (accumV * lastDuration + v * duration) / (duration + lastDuration);
					lastDuration = duration;
					samplesUsed += 1;
				}
			}
			return samplesUsed > 0 ? accumV : 0;
		}
	}

	// --- impulse ---------------------------------------------------------------------------------

	/**
	 * Velocity tracker algorithm that uses an impulse-based kinetic energy model, as used by AOSP for
	 * AXIS_SCROLL (and by Compose for touch).
	 */
	private static final class ImpulseStrategy extends Strategy {
		// Sample horizon: we don't use samples older than this.
		private static final long HORIZON = 100 * NANOS_PER_MS;
		// Number of samples to keep.
		private static final int HISTORY_SIZE = 20;

		private final boolean mDeltaValues;
		private final android.util.SparseArray<Movement[]> mMovements = new android.util.SparseArray<>();
		private final android.util.SparseIntArray mIndex = new android.util.SparseIntArray();

		ImpulseStrategy(boolean deltaValues) {
			mDeltaValues = deltaValues;
		}

		@Override
		void clearPointer(int pointerId) {
			mIndex.delete(pointerId);
			mMovements.remove(pointerId);
		}

		@Override
		void addMovement(long eventTime, int pointerId, float position) {
			Movement[] movements = mMovements.get(pointerId);
			boolean inserted = false;
			if (movements == null) {
				movements = new Movement[HISTORY_SIZE];
				for (int i = 0; i < HISTORY_SIZE; i++)
					movements[i] = new Movement();
				mMovements.put(pointerId, movements);
				mIndex.put(pointerId, 0);
				inserted = true;
			}
			int index = mIndex.get(pointerId, 0);
			if (!inserted && movements[index].eventTime != eventTime) {
				index++;
			}
			if (index == HISTORY_SIZE) {
				index = 0;
			}
			mIndex.put(pointerId, index);
			movements[index].eventTime = eventTime;
			movements[index].position = position;
		}

		private static float kineticEnergyToVelocity(float work) {
			final float sqrt2 = 1.41421356237f;
			return (work < 0 ? -1.0f : 1.0f) * (float)Math.sqrt(Math.abs(work)) * sqrt2;
		}

		/**
		 * Calculate the total impulse provided to the screen and the resulting velocity: the
		 * kinetic energy of the "screen" is computed from the work done by each sample.
		 */
		private float calculateImpulseVelocity(long[] t, float[] x, int count) {
			// The input should be in reversed time order (most recent sample at index i=0)
			// t[i] is in nanoseconds, but due to FP arithmetic, convert to seconds inside this function
			final float SECONDS_PER_NANO = 1E-9f;
			if (count < 2) {
				return 0; // if 0 or 1 points, velocity is zero
			}
			if (count == 2) { // if 2 points, basic linear calculation
				if (t[1] == t[0]) {
					return 0;
				}
				float deltaX = mDeltaValues ? -x[0] : x[1] - x[0];
				return deltaX / (SECONDS_PER_NANO * (t[1] - t[0]));
			}
			// Guaranteed to have at least 3 points here
			// Note: t[0] is the newest sample, so the iteration runs from the oldest one.
			float work = 0;
			for (int i = count - 1; i > 0; i--) { // start with the oldest sample and go forward in time
				if (t[i] == t[i - 1]) {
					continue;
				}
				float vprev = kineticEnergyToVelocity(work); // v[i-1]
				float deltaX = mDeltaValues ? -x[i - 1] : x[i] - x[i - 1];
				float vcurr = deltaX / (SECONDS_PER_NANO * (t[i] - t[i - 1])); // v[i]
				work += (vcurr - vprev) * Math.abs(vcurr);
				if (i == count - 1) {
					work *= 0.5f; // initial condition, case 2) above
				}
			}
			return kineticEnergyToVelocity(work);
		}

		@Override
		float getVelocity(int pointerId) {
			Movement[] movements = mMovements.get(pointerId);
			if (movements == null)
				return Float.NaN; // no data
			// Iterate over movement samples in reverse time order and collect samples.
			float[] positions = new float[HISTORY_SIZE];
			long[] time = new long[HISTORY_SIZE];
			int m = 0; // number of points that will be used for fitting
			int index = mIndex.get(pointerId);
			final Movement newestMovement = movements[index];
			do {
				final Movement movement = movements[index];
				if (movement.eventTime == 0 && index != 0) {
					break;
				}
				final long age = newestMovement.eventTime - movement.eventTime;
				if (age > HORIZON) {
					break;
				}
				positions[m] = movement.position;
				time[m] = movement.eventTime;
				index = (index == 0 ? HISTORY_SIZE : index) - 1;
			} while (++m < HISTORY_SIZE);

			if (m == 0) {
				return Float.NaN; // no data
			}
			return calculateImpulseVelocity(time, positions, m);
		}
	}

}
