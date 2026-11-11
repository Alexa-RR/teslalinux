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

package android.view.animation;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;

/**
 * Abstraction for an Animation that can be applied to Views, Surfaces, or other objects.
 *
 * teslalinux: AOSP's public API, attribute parsing, state machine (started / ended / repeat
 * handling), {@link Description} parsing and size resolution. As on Android, a view animation is
 * sampled on every frame through {@link #getTransformation(long, Transformation)}: the View it is
 * attached to (View.startAnimation / setAnimation) drives it from Choreographer frames and applies
 * the Transformation's matrix and alpha (View.java+transform.frag).
 */
public abstract class Animation implements Cloneable {
	/** Repeat the animation indefinitely. */
	public static final int INFINITE = -1;
	/** When the animation reaches the end and the repeat count is INFINTE_REPEAT or a positive value, the animation restarts from the beginning. */
	public static final int RESTART = 1;
	/** When the animation reaches the end and the repeat count is INFINTE_REPEAT or a positive value, the animation plays backward (and then forward again). */
	public static final int REVERSE = 2;
	/** Can be used as the start time to indicate the start time should be the current time when {@link #getTransformation(long, Transformation)} is invoked for the first animation frame. */
	public static final int START_ON_FIRST_FRAME = -1;

	/** The specified dimension is an absolute number of pixels. */
	public static final int ABSOLUTE = 0;
	/** The specified dimension holds a float and should be multiplied by the height or width of the object being animated. */
	public static final int RELATIVE_TO_SELF = 1;
	/** The specified dimension holds a float and should be multiplied by the height or width of the parent of the object being animated. */
	public static final int RELATIVE_TO_PARENT = 2;

	/** Requests that the content being animated be kept in its current Z order. */
	public static final int ZORDER_NORMAL = 0;
	/** Requests that the content being animated be forced on top of all other content for the duration of the animation. */
	public static final int ZORDER_TOP = 1;
	/** Requests that the content being animated be forced under all other content for the duration of the animation. */
	public static final int ZORDER_BOTTOM = -1;

	/** Set by {@link #getTransformation(long, Transformation)} when the animation ends. */
	boolean mEnded = false;
	/** Set by {@link #getTransformation(long, Transformation)} when the animation starts. */
	boolean mStarted = false;
	/** Set by {@link #getTransformation(long, Transformation)} when the animation repeats in REVERSE mode. */
	boolean mCycleFlip = false;
	/** This value must be set to true by {@link #initialize(int, int, int, int)}. */
	boolean mInitialized = false;
	/** Indicates whether the animation transformation should be applied before the animation starts. */
	boolean mFillBefore = true;
	/** Indicates whether the animation transformation should be applied after the animation ends. */
	boolean mFillAfter = false;
	/** Indicates whether fillBefore should be taken into account. */
	boolean mFillEnabled = false;
	/** The time in milliseconds at which the animation must start; */
	long mStartTime = -1;
	/** The delay in milliseconds after which the animation must start. */
	long mStartOffset;
	/** The duration of one animation cycle in milliseconds. */
	long mDuration;
	/** The number of times the animation must repeat. By default, an animation repeats indefinitely. */
	int mRepeatCount = 0;
	/** Indicates how many times the animation was repeated. */
	int mRepeated = 0;
	/** The behavior of the animation when it repeats. */
	int mRepeatMode = RESTART;
	/** The interpolator used by the animation to smooth the movement. */
	Interpolator mInterpolator;
	/** The animation listener to be notified when the animation starts, ends or repeats. */
	private AnimationListener mListener;
	/** Desired Z order mode during animation. */
	private int mZAdjustment;
	/** Desired background color behind animation. */
	private int mBackgroundColor;
	/** scalefactor to apply to pivot points, etc. during animation. */
	private float mScaleFactor = 1f;
	/** Don't animate the wallpaper. */
	private boolean mDetachWallpaper = false;
	private boolean mShowWallpaper;
	private boolean mHasRoundedCorners;
	private boolean mMore = true;
	private boolean mOneMoreTime = true;


	/** Creates a new animation with a duration of 0ms, the default interpolator, with fillBefore set to true and fillAfter set to false */
	public Animation() {
		ensureInterpolator();
	}

	/** Creates a new animation whose parameters come from the specified context and attributes set. */
	public Animation(Context context, AttributeSet attrs) {
		TypedArray a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.Animation);

		setDuration((long) a.getInt(com.android.internal.R.styleable.Animation_duration, 0));
		setStartOffset((long) a.getInt(com.android.internal.R.styleable.Animation_startOffset, 0));

		setFillEnabled(a.getBoolean(com.android.internal.R.styleable.Animation_fillEnabled, mFillEnabled));
		setFillBefore(a.getBoolean(com.android.internal.R.styleable.Animation_fillBefore, mFillBefore));
		setFillAfter(a.getBoolean(com.android.internal.R.styleable.Animation_fillAfter, mFillAfter));

		setRepeatCount(a.getInt(com.android.internal.R.styleable.Animation_repeatCount, mRepeatCount));
		setRepeatMode(a.getInt(com.android.internal.R.styleable.Animation_repeatMode, RESTART));

		setZAdjustment(a.getInt(com.android.internal.R.styleable.Animation_zAdjustment, ZORDER_NORMAL));

		setBackgroundColor(a.getInt(com.android.internal.R.styleable.Animation_background, 0));

		setDetachWallpaper(a.getBoolean(com.android.internal.R.styleable.Animation_detachWallpaper, false));

		final int resID = a.getResourceId(com.android.internal.R.styleable.Animation_interpolator, 0);

		a.recycle();

		if (resID > 0) {
			setInterpolator(context, resID);
		}

		ensureInterpolator();
	}

	@Override
	protected Animation clone() throws CloneNotSupportedException {
		final Animation animation = (Animation) super.clone();
		return animation;
	}

	/** Reset the initialization state of this animation. */
	public void reset() {
		mInitialized = false;
		mCycleFlip = false;
		mRepeated = 0;
		mMore = true;
		mOneMoreTime = true;
		mStarted = mEnded = false;
	}

	/** Cancel the animation. Cancelling an animation invokes the animation listener, if set, to notify the end of the animation. */
	public void cancel() {
		if (mStarted && !mEnded) {
			fireAnimationEnd();
			mEnded = true;
		}
		// Make sure we move the animation to the end
		mStartTime = Long.MIN_VALUE;
		mMore = mOneMoreTime = false;
	}

	/** @hide */
	public void detach() {
		if (mStarted && !mEnded) {
			mEnded = true;
			fireAnimationEnd();
		}
	}

	/** Whether or not the animation has been initialized. */
	public boolean isInitialized() {
		return mInitialized;
	}

	/**
	 * Initialize this animation with the dimensions of the object being animated as well as the
	 * objects parents. Subclasses that need the parent dimensions must override this method.
	 */
	public void initialize(int width, int height, int parentWidth, int parentHeight) {
		reset();
		mInitialized = true;
	}

	/** Sets the acceleration curve for this animation. */
	public void setInterpolator(Context context, int resID) {
		setInterpolator(AnimationUtils.loadInterpolator(context, resID));
	}

	/** Sets the acceleration curve for this animation. Defaults to a linear interpolation. */
	public void setInterpolator(Interpolator i) {
		mInterpolator = i;
	}

	/** When this animation should start relative to the start time. */
	public void setStartOffset(long startOffset) {
		mStartOffset = startOffset;
	}

	/** How long this animation should last. The duration cannot be negative. */
	public void setDuration(long durationMillis) {
		if (durationMillis < 0) {
			throw new IllegalArgumentException("Animation duration cannot be negative");
		}
		mDuration = durationMillis;
	}

	/** Ensure that the duration that this animation will run is not longer than durationMillis. */
	public void restrictDuration(long durationMillis) {
		// If we start after the duration, then we just won't run.
		if (mStartOffset > durationMillis) {
			mStartOffset = durationMillis;
			mDuration = 0;
			mRepeatCount = 0;
			return;
		}

		long dur = mDuration + mStartOffset;
		if (dur > durationMillis) {
			mDuration = durationMillis - mStartOffset;
			dur = durationMillis;
		}
		// If the duration is 0 or less, then we won't run.
		if (mDuration <= 0) {
			mDuration = 0;
			mRepeatCount = 0;
			return;
		}
		// Reduce the number of repeats to keep below the maximum duration.
		if (mRepeatCount < 0 || mRepeatCount > durationMillis || (dur * (mRepeatCount + 1)) > durationMillis) {
			// Figure out how many times to do the animation.  Subtract 1 since
			// repeat count is the number of times to repeat so 0 runs once.
			mRepeatCount = (int) (durationMillis / dur) - 1;
			if (mRepeatCount < 0) {
				mRepeatCount = 0;
			}
		}
	}

	/** How much to scale the duration by. */
	public void scaleCurrentDuration(float scale) {
		mDuration = (long) (mDuration * scale);
		mStartOffset = (long) (mStartOffset * scale);
	}

	/** When this animation should start. */
	public void setStartTime(long startTimeMillis) {
		mStartTime = startTimeMillis;
		mStarted = mEnded = false;
		mCycleFlip = false;
		mRepeated = 0;
		mMore = true;
	}

	/**
	 * Convenience method to start the animation the first time {@link #getTransformation(long, Transformation)} is invoked.
	 * AOSP: only the start time changes; whoever samples the animation (the View it is attached to) runs it.
	 */
	public void start() {
		setStartTime(-1);
	}

	/** Convenience method to start the animation at the current time in milliseconds. */
	public void startNow() {
		setStartTime(AnimationUtils.currentAnimationTimeMillis());
	}

	/** Defines what this animation should do when it reaches the end. */
	public void setRepeatMode(int repeatMode) {
		mRepeatMode = repeatMode;
	}

	/** Sets how many times the animation should be repeated. */
	public void setRepeatCount(int repeatCount) {
		if (repeatCount < 0) {
			repeatCount = INFINITE;
		}
		mRepeatCount = repeatCount;
	}

	/** If fillEnabled is true, this animation will apply the value of fillBefore. */
	public boolean isFillEnabled() {
		return mFillEnabled;
	}

	public void setFillEnabled(boolean fillEnabled) {
		mFillEnabled = fillEnabled;
	}

	public void setFillBefore(boolean fillBefore) {
		mFillBefore = fillBefore;
	}

	public void setFillAfter(boolean fillAfter) {
		mFillAfter = fillAfter;
	}

	public void setZAdjustment(int zAdjustment) {
		mZAdjustment = zAdjustment;
	}

	public void setBackgroundColor(int bg) {
		mBackgroundColor = bg;
	}

	/** The scale factor is set by the call to getTransformation. */
	protected float getScaleFactor() {
		return mScaleFactor;
	}

	public void setDetachWallpaper(boolean detachWallpaper) {
		mDetachWallpaper = detachWallpaper;
	}

	/** @hide */
	public void setShowWallpaper(boolean showWallpaper) {
		mShowWallpaper = showWallpaper;
	}

	/** @hide */
	public void setHasRoundedCorners(boolean hasRoundedCorners) {
		mHasRoundedCorners = hasRoundedCorners;
	}

	public Interpolator getInterpolator() {
		return mInterpolator;
	}

	public long getStartTime() {
		return mStartTime;
	}

	public long getDuration() {
		return mDuration;
	}

	public long getStartOffset() {
		return mStartOffset;
	}

	public int getRepeatMode() {
		return mRepeatMode;
	}

	public int getRepeatCount() {
		return mRepeatCount;
	}

	public boolean getFillBefore() {
		return mFillBefore;
	}

	public boolean getFillAfter() {
		return mFillAfter;
	}

	public int getZAdjustment() {
		return mZAdjustment;
	}

	public int getBackgroundColor() {
		return mBackgroundColor;
	}

	public boolean getDetachWallpaper() {
		return mDetachWallpaper;
	}

	/** @hide */
	public boolean getShowWallpaper() {
		return mShowWallpaper;
	}

	/** @hide */
	public boolean hasRoundedCorners() {
		return mHasRoundedCorners;
	}

	/** Indicates whether or not this animation will affect the transformation matrix. */
	public boolean willChangeTransformationMatrix() {
		// assume we will change the matrix
		return true;
	}

	/** Indicates whether or not this animation will affect the bounds of the animated view. */
	public boolean willChangeBounds() {
		// assume we will change the bounds
		return true;
	}

	private boolean hasAnimationListener() {
		return mListener != null;
	}

	/** Binds an animation listener to this animation. */
	public void setAnimationListener(AnimationListener listener) {
		mListener = listener;
	}

	/** Gurantees that this animation has an interpolator. Will use a AccelerateDecelerateInterpolator is nothing else was specified. */
	protected void ensureInterpolator() {
		if (mInterpolator == null) {
			mInterpolator = new AccelerateDecelerateInterpolator();
		}
	}

	/** Compute a hint at how long the entire animation may last, in milliseconds. */
	public long computeDurationHint() {
		return (getStartOffset() + getDuration()) * (getRepeatCount() + 1);
	}

	/**
	 * Gets the transformation to apply at a specified point in time. Implementations of this
	 * method should always replace the specified Transformation or document they are doing
	 * otherwise.
	 *
	 * @return True if the animation is still running
	 */
	public boolean getTransformation(long currentTime, Transformation outTransformation) {
		if (mStartTime == -1) {
			mStartTime = currentTime;
		}

		final long startOffset = getStartOffset();
		final long duration = mDuration;
		float normalizedTime;
		if (duration != 0) {
			normalizedTime = ((float) (currentTime - (mStartTime + startOffset))) / (float) duration;
		} else {
			// time is a step-change with a zero duration
			normalizedTime = currentTime < mStartTime ? 0.0f : 1.0f;
		}

		final boolean expired = normalizedTime >= 1.0f || isCanceled();
		mMore = !expired;

		if (!mFillEnabled) normalizedTime = Math.max(Math.min(normalizedTime, 1.0f), 0.0f);

		if ((normalizedTime >= 0.0f || mFillBefore) && (normalizedTime <= 1.0f || mFillAfter)) {
			if (!mStarted) {
				fireAnimationStart();
				mStarted = true;
			}

			if (mFillEnabled) normalizedTime = Math.max(Math.min(normalizedTime, 1.0f), 0.0f);

			if (mCycleFlip) {
				normalizedTime = 1.0f - normalizedTime;
			}

			final float interpolatedTime = mInterpolator.getInterpolation(normalizedTime);
			applyTransformation(interpolatedTime, outTransformation);
		}

		if (expired) {
			if (mRepeatCount == mRepeated || isCanceled()) {
				if (!mEnded) {
					mEnded = true;
					fireAnimationEnd();
				}
			} else {
				if (mRepeatCount > 0) {
					mRepeated++;
				}

				if (mRepeatMode == REVERSE) {
					mCycleFlip = !mCycleFlip;
				}

				mStartTime = -1;
				mMore = true;

				fireAnimationRepeat();
			}
		}

		if (!mMore && mOneMoreTime) {
			mOneMoreTime = false;
			return true;
		}

		return mMore;
	}

	private boolean isCanceled() {
		return mStartTime == Long.MIN_VALUE;
	}

	void fireAnimationStart() {
		if (hasAnimationListener()) {
			mListener.onAnimationStart(this);
		}
	}

	void fireAnimationRepeat() {
		if (hasAnimationListener()) {
			mListener.onAnimationRepeat(this);
		}
	}

	void fireAnimationEnd() {
		if (hasAnimationListener()) {
			mListener.onAnimationEnd(this);
		}
	}

	/** Gets the transformation to apply at a specified point in time, with a scale factor for pivots. */
	public boolean getTransformation(long currentTime, Transformation outTransformation, float scale) {
		mScaleFactor = scale;
		return getTransformation(currentTime, outTransformation);
	}

	/** Indicates whether this animation has started or not. */
	public boolean hasStarted() {
		return mStarted;
	}

	/** Indicates whether this animation has ended or not. */
	public boolean hasEnded() {
		return mEnded;
	}

	/**
	 * Helper for getTransformation. Subclasses should implement this to apply their transforms
	 * given an interpolation value. Implementations of this method should always replace the
	 * specified Transformation or document they are doing otherwise.
	 */
	protected void applyTransformation(float interpolatedTime, Transformation t) {
	}

	/** Convert the information in the description of a size to an actual dimension */
	protected float resolveSize(int type, float value, int size, int parentSize) {
		switch (type) {
			case ABSOLUTE:
				return value;
			case RELATIVE_TO_SELF:
				return size * value;
			case RELATIVE_TO_PARENT:
				return parentSize * value;
			default:
				return value;
		}
	}

	/** @hide */
	public void getInvalidateRegion(int left, int top, int right, int bottom, android.graphics.RectF invalidate, Transformation transformation) {
		invalidate.set(left, top, right, bottom);
	}

	/** @hide */
	public void initializeInvalidateRegion(int left, int top, int right, int bottom) {
	}

	/** @hide AOSP: whether the animation extends the view beyond its bounds (ExtendAnimation). */
	public boolean hasExtension() {
		return false;
	}

	/** Return true if this animation changes the view's alpha property. */
	public boolean hasAlpha() {
		return false;
	}

	/** Utility class to parse a string description of a size. */
	protected static class Description {
		/** One of Animation.ABSOLUTE, Animation.RELATIVE_TO_SELF, or Animation.RELATIVE_TO_PARENT. */
		public int type;

		/** The absolute or relative dimension for this Description. */
		public float value;

		/** Size descriptions can appear inemallow pixels or percentages of self/parent. */
		static Description parseValue(TypedValue value, Context context) {
			Description d = new Description();
			if (value == null) {
				d.type = ABSOLUTE;
				d.value = 0;
			} else {
				if (value.type == TypedValue.TYPE_FRACTION) {
					d.type = (value.data & TypedValue.COMPLEX_UNIT_MASK) == TypedValue.COMPLEX_UNIT_FRACTION_PARENT ? RELATIVE_TO_PARENT : RELATIVE_TO_SELF;
					d.value = TypedValue.complexToFloat(value.data);
					return d;
				} else if (value.type == TypedValue.TYPE_FLOAT) {
					d.type = ABSOLUTE;
					d.value = value.getFloat();
					return d;
				} else if (value.type >= TypedValue.TYPE_FIRST_INT && value.type <= TypedValue.TYPE_LAST_INT) {
					d.type = ABSOLUTE;
					d.value = value.data;
					return d;
				} else if (value.type == TypedValue.TYPE_DIMENSION) {
					d.type = ABSOLUTE;
					d.value = TypedValue.complexToDimension(value.data, context.getResources().getDisplayMetrics());
					return d;
				}
			}

			d.type = ABSOLUTE;
			d.value = 0.0f;

			return d;
		}

		/** @hide */
		static Description parseValue(TypedValue value) {
			return parseValue(value, null);
		}
	}

	/** An animation listener receives notifications from an animation. */
	public static interface AnimationListener {
		void onAnimationStart(Animation animation);
		void onAnimationEnd(Animation animation);
		void onAnimationRepeat(Animation animation);
	}
}
