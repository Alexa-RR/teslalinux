/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.graphics.Insets;
import android.view.animation.Interpolator;

import java.util.List;

/**
 * Class representing an animation of a set of windows that cause insets (AOSP API 30). Nothing
 * animates the insets in this runtime (the compositor does not animate the keyboard or bars), so no
 * animation is ever started, but apps and Compose keep callbacks and inspect these objects.
 */
public final class WindowInsetsAnimation {
	private final int mTypeMask;
	private float mFraction;
	private final Interpolator mInterpolator;
	private final long mDurationMillis;
	private float mAlpha;

	public WindowInsetsAnimation(int typeMask, Interpolator interpolator, long durationMillis) {
		mTypeMask = typeMask;
		mInterpolator = interpolator;
		mDurationMillis = durationMillis;
	}

	/** The bitmask of {@link WindowInsets.Type}s this animation affects. */
	public int getTypeMask() {
		return mTypeMask;
	}

	/** The raw fractional progress of this animation between 0 and 1. */
	public float getFraction() {
		return mFraction;
	}

	/** The interpolated fractional progress of this animation between 0 and 1. */
	public float getInterpolatedFraction() {
		if (mInterpolator != null)
			return mInterpolator.getInterpolation(mFraction);
		return mFraction;
	}

	public Interpolator getInterpolator() {
		return mInterpolator;
	}

	/** The duration in milliseconds, or -1 when driven by the user. */
	public long getDurationMillis() {
		return mDurationMillis;
	}

	/** Set fraction of the progress if {@link WindowInsets.Type} animation is controlled by the app. */
	public void setFraction(float fraction) {
		mFraction = fraction;
	}

	/** Translucency of the windows that are animating. */
	public float getAlpha() {
		return mAlpha;
	}

	public void setAlpha(float alpha) {
		mAlpha = alpha;
	}

	/** The lower and upper bound of the insets over the animation. */
	public static final class Bounds {
		private final Insets mLowerBound;
		private final Insets mUpperBound;

		public Bounds(Insets lowerBound, Insets upperBound) {
			mLowerBound = lowerBound;
			mUpperBound = upperBound;
		}

		public Insets getLowerBound() {
			return mLowerBound;
		}

		public Insets getUpperBound() {
			return mUpperBound;
		}

		/** Insets both the lower and the upper bound by the given insets. */
		public Bounds inset(Insets insets) {
			return new Bounds(Insets.subtract(mLowerBound, insets), Insets.subtract(mUpperBound, insets));
		}

		@Override
		public String toString() {
			return "Bounds{lower=" + mLowerBound + " upper=" + mUpperBound + "}";
		}
	}

	/** Interface that allows the application to listen to animation events for windows that cause insets. */
	public abstract static class Callback {
		/** Dispatch the animation to this view's children; stop there. */
		public static final int DISPATCH_MODE_STOP = 0;
		/** Dispatch the animation to the children after this view. */
		public static final int DISPATCH_MODE_CONTINUE_ON_SUBTREE = 1;

		private final int mDispatchMode;

		public Callback(int dispatchMode) {
			mDispatchMode = dispatchMode;
		}

		public final int getDispatchMode() {
			return mDispatchMode;
		}

		/** Called when an insets animation is about to start and before the views have been laid out. */
		public void onPrepare(WindowInsetsAnimation animation) {
		}

		/** Called when an insets animation gets started; the bounds may be modified. */
		public Bounds onStart(WindowInsetsAnimation animation, Bounds bounds) {
			return bounds;
		}

		/** Called when the insets change as part of running an animation. */
		public abstract WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> runningAnimations);

		/** Called when an insets animation has ended. */
		public void onEnd(WindowInsetsAnimation animation) {
		}
	}
}
