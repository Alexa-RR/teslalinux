/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

/**
 * teslalinux: AOSP's EdgeEffect (frameworks/base core/java/android/widget/EdgeEffect.java), the
 * overscroll glow of scrolling containers, with the API 31 distance methods.
 *
 * Android 12+ defaults to TYPE_STRETCH, which is not drawn by EdgeEffect at all but by HWUI's
 * RenderNode stretch effect; ATL's GSK renderer has no such effect, so this EdgeEffect always
 * behaves as AOSP does with {@code android:edgeEffectType="glow"} (TYPE_GLOW): it draws the glow
 * itself in {@link #draw(Canvas)}, and reports its pull distance from the glow values exactly as
 * AOSP's glow mode does. ATL's EdgeEffect used to extend View; AOSP's extends Object.
 */
public class EdgeEffect {

	/** The default blend mode used by EdgeEffect. */
	public static final BlendMode DEFAULT_BLEND_MODE = BlendMode.SRC_ATOP;

	/** AOSP API 31: the stretch effect (rendered by HWUI on Android; not available here, see above). */
	public static final int TYPE_STRETCH = 1;
	/** AOSP API 31: the glow effect, the one this implementation draws. */
	public static final int TYPE_GLOW = 0;

	// Time it will take the effect to fully recede in ms
	private static final int RECEDE_TIME = 600;

	// Time it will take before a pulled glow begins receding in ms
	private static final int PULL_TIME = 167;

	// Time it will take in ms for a pulled glow to decay to partial strength before release
	private static final int PULL_DECAY_TIME = 2000;

	private static final float MAX_ALPHA = 0.15f;
	private static final float GLOW_ALPHA_START = .09f;

	private static final float MAX_GLOW_SCALE = 2.f;

	private static final float PULL_GLOW_BEGIN = 0.f;

	// Minimum velocity that will be absorbed
	private static final int MIN_VELOCITY = 100;
	// Maximum velocity, clamps at this value
	private static final int MAX_VELOCITY = 10000;

	private static final float EPSILON = 0.001f;

	private static final double ANGLE = Math.PI / 6;
	private static final float SIN = (float) Math.sin(ANGLE);
	private static final float COS = (float) Math.cos(ANGLE);
	private static final float RADIUS_FACTOR = 0.6f;

	private float mGlowAlpha;
	private float mGlowScaleY;

	private float mGlowAlphaStart;
	private float mGlowAlphaFinish;
	private float mGlowScaleYStart;
	private float mGlowScaleYFinish;

	private long mStartTime;
	private float mDuration;

	private final Interpolator mInterpolator = new DecelerateInterpolator();

	private static final int STATE_IDLE = 0;
	private static final int STATE_PULL = 1;
	private static final int STATE_ABSORB = 2;
	private static final int STATE_RECEDE = 3;
	private static final int STATE_PULL_DECAY = 4;

	private static final float PULL_DISTANCE_ALPHA_GLOW_FACTOR = 0.8f;

	private static final int VELOCITY_GLOW_FACTOR = 6;

	private int mState = STATE_IDLE;

	private float mPullDistance;

	private final Rect mBounds = new Rect();
	private final Paint mPaint = new Paint();
	private float mRadius;
	private float mBaseGlowScale;
	private float mDisplacement = 0.5f;
	private float mTargetDisplacement = 0.5f;

	/** The current pull distance, as a fraction of the size (API 31 getDistance()). */
	private float mDistance;

	/**
	 * Construct a new EdgeEffect with a theme appropriate for the provided context.
	 * @param context Context used to provide theming and resource information for the EdgeEffect
	 */
	public EdgeEffect(Context context) {
		this(context, null);
	}

	/**
	 * Construct a new EdgeEffect with a theme appropriate for the provided context.
	 * @param context Context used to provide theming and resource information for the EdgeEffect
	 * @param attrs The attributes of the XML tag that is inflating the view
	 */
	public EdgeEffect(Context context, AttributeSet attrs) {
		int themeColor = 0xff666666;
		if (context != null) {
			final TypedArray a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.EdgeEffect);
			themeColor = a.getColor(com.android.internal.R.styleable.EdgeEffect_colorEdgeEffect, 0xff666666);
			a.recycle();
		}
		mPaint.setAntiAlias(true);
		mPaint.setColor((themeColor & 0xffffff) | 0x33000000);
		mPaint.setStyle(Paint.Style.FILL);
		mPaint.setBlendMode(DEFAULT_BLEND_MODE);
	}

	/**
	 * Set the size of this edge effect in pixels.
	 *
	 * @param width Effect width in pixels
	 * @param height Effect height in pixels
	 */
	public void setSize(int width, int height) {
		final float r = width * RADIUS_FACTOR / SIN;
		final float y = COS * r;
		final float h = r - y;
		final float or = height * RADIUS_FACTOR / SIN;
		final float oy = COS * or;
		final float oh = or - oy;

		mRadius = r;
		mBaseGlowScale = h > 0 ? Math.min(oh / h, 1.f) : 1.f;

		mBounds.set(mBounds.left, mBounds.top, width, (int) Math.min(height, h));
	}

	/**
	 * Reports if this EdgeEffect's animation is finished. If this method returns false
	 * after a call to {@link #draw(Canvas)} the host widget should schedule another
	 * drawing pass to continue the animation.
	 *
	 * @return true if animation is finished, false if drawing should continue on the next frame.
	 */
	public boolean isFinished() {
		return mState == STATE_IDLE;
	}

	/**
	 * Immediately finish the current animation.
	 * After this call {@link #isFinished()} will return true.
	 */
	public void finish() {
		mState = STATE_IDLE;
		mDistance = 0;
	}

	/**
	 * A view should call this when content is pulled away from an edge by the user.
	 * This will update the state of the current visual effect and its associated animation.
	 * The host view should always {@link android.view.View#invalidate()} after this
	 * and draw the results accordingly.
	 *
	 * @param deltaDistance Change in distance since the last call. Values may be 0 (no change) to
	 *                      1.f (full length of the view) or negative values to express change
	 *                      back toward the edge reached to initiate the effect.
	 */
	public void onPull(float deltaDistance) {
		onPull(deltaDistance, 0.5f);
	}

	/**
	 * A view should call this when content is pulled away from an edge by the user.
	 *
	 * @param deltaDistance Change in distance since the last call.
	 * @param displacement The displacement from the starting side of the effect of the point
	 *                     initiating the pull. In the case of touch this is the finger position.
	 *                     Values may be from 0-1.
	 */
	public void onPull(float deltaDistance, float displacement) {
		final long now = AnimationUtils.currentAnimationTimeMillis();
		mTargetDisplacement = displacement;
		if (mState == STATE_PULL_DECAY && now - mStartTime < mDuration) {
			return;
		}
		if (mState != STATE_PULL) {
			mGlowScaleY = Math.max(PULL_GLOW_BEGIN, mGlowScaleY);
		}
		mState = STATE_PULL;

		mStartTime = now;
		mDuration = PULL_TIME;

		mPullDistance += deltaDistance;
		mDistance = Math.max(0f, mPullDistance);

		final float absdd = Math.abs(deltaDistance);
		mGlowAlpha = mGlowAlphaStart = Math.min(MAX_ALPHA,
				mGlowAlpha + (absdd * PULL_DISTANCE_ALPHA_GLOW_FACTOR));

		if (mPullDistance == 0) {
			mGlowScaleY = mGlowScaleYStart = 0;
		} else {
			final float scale = (float) (Math.max(0, 1 - 1 /
					Math.sqrt(Math.abs(mPullDistance) * mBounds.height()) - 0.3d) / 0.7d);

			mGlowScaleY = mGlowScaleYStart = scale;
		}

		mGlowAlphaFinish = mGlowAlpha;
		mGlowScaleYFinish = mGlowScaleY;
	}

	/**
	 * A view should call this when content is pulled away from an edge by the user (API 31).
	 * Unlike {@link #onPull(float, float)}, the distance can not go below 0: a pull back toward
	 * the edge is consumed only up to the current distance.
	 *
	 * @return The amount of <code>deltaDistance</code> that was consumed, a number between
	 * 0 and <code>deltaDistance</code>.
	 */
	public float onPullDistance(float deltaDistance, float displacement) {
		float finalDistance = Math.max(0f, deltaDistance + mDistance);
		float delta = finalDistance - mDistance;
		if (delta == 0f && mDistance == 0f) {
			return 0f; // No pull, don't do anything.
		}

		if (mState != STATE_PULL && mState != STATE_PULL_DECAY) {
			// Catch the edge glow in the middle of an animation.
			mPullDistance = mDistance;
			mState = STATE_PULL;
		}
		onPull(delta, displacement);
		return delta;
	}

	/**
	 * Returns the pull distance needed to be released to remove the showing effect (API 31).
	 * It is determined by the {@link #onPull(float, float)} <code>deltaDistance</code> and
	 * any animating values, including from {@link #onAbsorb(int)} and {@link #onRelease()}.
	 */
	public float getDistance() {
		return mDistance;
	}

	/**
	 * Call when the object is released after being pulled.
	 * This will begin the "decay" phase of the effect. After calling this method
	 * the host view should {@link android.view.View#invalidate()} and thereby
	 * draw the results accordingly.
	 */
	public void onRelease() {
		mPullDistance = 0;

		if (mState != STATE_PULL && mState != STATE_PULL_DECAY) {
			return;
		}

		mState = STATE_RECEDE;
		mGlowAlphaStart = mGlowAlpha;
		mGlowScaleYStart = mGlowScaleY;

		mGlowAlphaFinish = 0.f;
		mGlowScaleYFinish = 0.f;

		mStartTime = AnimationUtils.currentAnimationTimeMillis();
		mDuration = RECEDE_TIME;
	}

	/**
	 * Call when the effect absorbs an impact at the given velocity.
	 * Used when a fling reaches the scroll boundary.
	 *
	 * @param velocity Velocity at impact in pixels per second.
	 */
	public void onAbsorb(int velocity) {
		mState = STATE_ABSORB;
		velocity = Math.min(Math.max(MIN_VELOCITY, Math.abs(velocity)), MAX_VELOCITY);

		mStartTime = AnimationUtils.currentAnimationTimeMillis();
		mDuration = 0.15f + (velocity * 0.02f);

		// The glow depends more on the velocity, and therefore starts out
		// nearly invisible.
		mGlowAlphaStart = GLOW_ALPHA_START;
		mGlowScaleYStart = Math.max(mGlowScaleY, 0.f);

		// Growth for the size of the glow should be quadratic to properly
		// respond to a user's scrolling speed. The faster the scrolling speed, the more
		// intense the effect should be for both the size and the saturation.
		mGlowScaleYFinish = Math.min(0.025f + (velocity * (velocity / 100) * 0.00015f) / 2, 1.f);
		// Alpha should change for the glow as well as size.
		mGlowAlphaFinish = Math.max(
				mGlowAlphaStart, Math.min(velocity * VELOCITY_GLOW_FACTOR * .00001f, MAX_ALPHA));
		mTargetDisplacement = 0.5f;
	}

	/**
	 * Set the color of this edge effect in argb.
	 *
	 * @param color Color in argb
	 */
	public void setColor(int color) {
		mPaint.setColor(color);
	}

	/**
	 * Set or clear the blend mode. A blend mode defines how source pixels
	 * (generated by a drawing command) are composited with the destination pixels
	 * (content of the render target).
	 */
	public void setBlendMode(BlendMode blendmode) {
		mPaint.setBlendMode(blendmode);
	}

	/**
	 * Return the color of this edge effect in argb.
	 * @return The color of this edge effect in argb
	 */
	public int getColor() {
		return mPaint.getColor();
	}

	/**
	 * Returns the blend mode. A blend mode defines how source pixels
	 * (generated by a drawing command) are composited with the destination pixels
	 * (content of the render target).
	 */
	public BlendMode getBlendMode() {
		return mPaint.getBlendMode();
	}

	/**
	 * Draw into the provided canvas. Assumes that the canvas has been rotated
	 * accordingly and the size has been set. The effect will be drawn the full
	 * width of X=0 to X=width, beginning from Y=0 and extending to some factor <
	 * 1.f of height.
	 *
	 * @param canvas Canvas to draw into
	 * @return true if drawing should continue beyond this frame to continue the
	 *         animation
	 */
	public boolean draw(Canvas canvas) {
		update();

		final int count = canvas.save();

		final float centerX = mBounds.centerX();
		final float centerY = mBounds.height() - mRadius;

		canvas.scale(1.f, Math.min(mGlowScaleY, 1.f) * mBaseGlowScale, centerX, 0);

		final float displacement = Math.max(0, Math.min(mDisplacement, 1.f)) - 0.5f;
		float translateX = mBounds.width() * displacement / 2;

		canvas.clipRect(mBounds);
		canvas.translate(translateX, 0);
		mPaint.setAlpha((int) (0xff * mGlowAlpha));
		canvas.drawCircle(centerX, centerY, mRadius, mPaint);
		canvas.restoreToCount(count);

		boolean oneLastFrame = false;
		if (mState == STATE_RECEDE && mGlowScaleY == 0) {
			mState = STATE_IDLE;
			oneLastFrame = true;
		}

		return mState != STATE_IDLE || oneLastFrame;
	}

	/**
	 * Return the maximum height that the edge effect will be drawn at given the original
	 * {@link #setSize(int, int) input size}.
	 * @return The maximum height of the edge effect
	 */
	public int getMaxHeight() {
		return (int) (mBounds.height() * MAX_GLOW_SCALE + 0.5f);
	}

	private void update() {
		final long time = AnimationUtils.currentAnimationTimeMillis();
		final float t = mDuration > 0 ? Math.min((time - mStartTime) / mDuration, 1.f) : 1.f;

		final float interp = mInterpolator.getInterpolation(t);

		mGlowAlpha = mGlowAlphaStart + (mGlowAlphaFinish - mGlowAlphaStart) * interp;
		mGlowScaleY = mGlowScaleYStart + (mGlowScaleYFinish - mGlowScaleYStart) * interp;
		if (mState != STATE_PULL) {
			mDistance = calculateDistanceFromGlowValues(mGlowScaleY, mGlowAlpha);
		}
		mDisplacement = (mDisplacement + mTargetDisplacement) / 2;

		if (t >= 1.f - EPSILON) {
			switch (mState) {
				case STATE_ABSORB:
					mState = STATE_RECEDE;
					mStartTime = AnimationUtils.currentAnimationTimeMillis();
					mDuration = RECEDE_TIME;

					mGlowAlphaStart = mGlowAlpha;
					mGlowScaleYStart = mGlowScaleY;

					// After absorb, the glow should fade to nothing.
					mGlowAlphaFinish = 0.f;
					mGlowScaleYFinish = 0.f;
					break;
				case STATE_PULL:
					mState = STATE_PULL_DECAY;
					mStartTime = AnimationUtils.currentAnimationTimeMillis();
					mDuration = PULL_DECAY_TIME;

					mGlowAlphaStart = mGlowAlpha;
					mGlowScaleYStart = mGlowScaleY;

					// After pull, the glow should fade to nothing.
					mGlowAlphaFinish = 0.f;
					mGlowScaleYFinish = 0.f;
					break;
				case STATE_PULL_DECAY:
					mState = STATE_RECEDE;
					break;
				case STATE_RECEDE:
					mState = STATE_IDLE;
					mDistance = 0;
					break;
			}
		}
	}

	/**
	 * The pull distance that produces the given glow (AOSP glow mode): the inverse of the scale
	 * formula in {@link #onPull(float, float)} while the glow has a height, else from the alpha.
	 */
	private float calculateDistanceFromGlowValues(float scale, float alpha) {
		if (scale >= 1f) {
			// It should asymptotically approach 1, but not reach there.
			// Here, we're just choosing a value that is large.
			return 1f;
		}
		if (scale > 0f) {
			float v = 1f / 0.7f / (scale - 1f);
			return mBounds.height() > 0 ? v * v / mBounds.height() : 0f;
		}
		return alpha / PULL_DISTANCE_ALPHA_GLOW_FACTOR;
	}
}
