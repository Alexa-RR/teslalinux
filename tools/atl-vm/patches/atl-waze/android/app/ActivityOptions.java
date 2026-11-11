/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Pair;
import android.view.View;
import java.util.ArrayList;

/**
 * teslalinux: AOSP's ActivityOptions (android14-release): the public factories, setters, getters and
 * the Bundle form (the same keys as AOSP, so options survive toBundle()/fromBundle() and
 * Context.startActivity(Intent, Bundle)). What the launch does with them on this platform:
 * ANIM_CUSTOM plays the given enter/exit animations as the next activity switch
 * (Activity.tlSetPendingTransition, see Activity.overridePendingTransition); the other animation
 * types (scale-up, clip reveal, thumbnails, scene transitions, launch-behind) keep ATL's default
 * activity switch, as there is no window animation system or recents task to animate from.
 * There is one display and one task, so launch display, bounds and windowing mode are stored and
 * returned but cannot move the activity anywhere else.
 */
public class ActivityOptions {
	private static final String TAG = "ActivityOptions";

	public static final String EXTRA_USAGE_TIME_REPORT = "android.activity.usage_time";
	public static final String EXTRA_USAGE_TIME_REPORT_PACKAGES = "android.usage_time_packages";

	public static final int MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED = 0;
	public static final int MODE_BACKGROUND_ACTIVITY_START_ALLOWED = 1;
	public static final int MODE_BACKGROUND_ACTIVITY_START_DENIED = 2;
	public static final int MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS = 3;
	public static final int MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE = 4;

	/** @hide */ public static final String KEY_PACKAGE_NAME = "android:activity.packageName";
	/** @hide */ public static final String KEY_LAUNCH_BOUNDS = "android:activity.launchBounds";
	/** @hide */ public static final String KEY_ANIM_TYPE = "android:activity.animType";
	/** @hide */ public static final String KEY_ANIM_ENTER_RES_ID = "android:activity.animEnterRes";
	/** @hide */ public static final String KEY_ANIM_EXIT_RES_ID = "android:activity.animExitRes";
	/** @hide */ public static final String KEY_ANIM_IN_PLACE_RES_ID = "android:activity.animInPlaceRes";
	/** @hide */ public static final String KEY_ANIM_BACKGROUND_COLOR = "android:activity.backgroundColor";
	/** @hide */ public static final String KEY_ANIM_THUMBNAIL = "android:activity.animThumbnail";
	/** @hide */ public static final String KEY_ANIM_START_X = "android:activity.animStartX";
	/** @hide */ public static final String KEY_ANIM_START_Y = "android:activity.animStartY";
	/** @hide */ public static final String KEY_ANIM_WIDTH = "android:activity.animWidth";
	/** @hide */ public static final String KEY_ANIM_HEIGHT = "android:activity.animHeight";
	/** @hide */ public static final String KEY_SPLASH_SCREEN_THEME = "android.activity.splashScreenTheme";
	private static final String KEY_LOCK_TASK_MODE = "android:activity.lockTaskMode";
	private static final String KEY_SHARE_IDENTITY = "android:activity.shareIdentity";
	private static final String KEY_LAUNCH_DISPLAY_ID = "android.activity.launchDisplayId";
	private static final String KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode";
	private static final String KEY_LAUNCH_TASK_ID = "android.activity.launchTaskId";
	private static final String KEY_PENDING_INTENT_LAUNCH_FLAGS = "android.activity.pendingIntentLaunchFlags";
	private static final String KEY_TRANSITION_SHARED_ELEMENTS = "android:activity.sharedElementNames";
	private static final String KEY_TRANSITION_IS_RETURNING = "android:activity.transitionIsReturning";
	private static final String KEY_USAGE_TIME_REPORT = "android:activity.usageTimeReport";
	private static final String KEY_ROTATION_ANIMATION_HINT = "android:activity.rotationAnimationHint";
	private static final String KEY_SPLASH_SCREEN_STYLE = "android.activity.splashScreenStyle";
	private static final String KEY_LAUNCH_COOKIE = "android.activity.launchCookie";
	private static final String KEY_ALLOW_PASS_THROUGH_ON_TOUCH_OUTSIDE = "android.activity.allowPassThroughOnTouchOutside";
	/* ComponentOptions */
	private static final String KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED = "android.pendingIntent.backgroundActivityAllowed";
	private static final String KEY_PENDING_INTENT_CREATOR_BACKGROUND_ACTIVITY_START_MODE = "android.activity.pendingIntentCreatorBackgroundActivityStartMode";

	/** @hide */ public static final int ANIM_UNDEFINED = -1;
	/** @hide */ public static final int ANIM_NONE = 0;
	/** @hide */ public static final int ANIM_CUSTOM = 1;
	/** @hide */ public static final int ANIM_SCALE_UP = 2;
	/** @hide */ public static final int ANIM_THUMBNAIL_SCALE_UP = 3;
	/** @hide */ public static final int ANIM_THUMBNAIL_SCALE_DOWN = 4;
	/** @hide */ public static final int ANIM_SCENE_TRANSITION = 5;
	/** @hide */ public static final int ANIM_DEFAULT = 6;
	/** @hide */ public static final int ANIM_LAUNCH_TASK_BEHIND = 7;
	/** @hide */ public static final int ANIM_THUMBNAIL_ASPECT_SCALE_UP = 8;
	/** @hide */ public static final int ANIM_THUMBNAIL_ASPECT_SCALE_DOWN = 9;
	/** @hide */ public static final int ANIM_CUSTOM_IN_PLACE = 10;
	/** @hide */ public static final int ANIM_CLIP_REVEAL = 11;

	private static final int INVALID_DISPLAY = -1;

	private String mPackageName;
	private Rect mLaunchBounds;
	private int mAnimationType = ANIM_UNDEFINED;
	private int mCustomEnterResId;
	private int mCustomExitResId;
	private int mCustomInPlaceResId;
	private int mCustomBackgroundColor;
	private Bitmap mThumbnail;
	private int mStartX;
	private int mStartY;
	private int mWidth;
	private int mHeight;
	private ArrayList<String> mSharedElementNames;
	private boolean mIsReturning;
	private boolean mLockTaskMode;
	private boolean mShareIdentity;
	private int mLaunchDisplayId = INVALID_DISPLAY;
	private int mLaunchWindowingMode;
	private int mLaunchTaskId = -1;
	private int mPendingIntentLaunchFlags;
	private PendingIntent mUsageTimeReport;
	private int mRotationAnimationHint = -1;
	private int mSplashScreenThemeResId;
	private int mSplashScreenStyle;
	private boolean mAllowPassThroughOnTouchOutside;
	private int mPendingIntentBalMode = MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED;
	private int mPendingIntentCreatorBalMode = MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED;

	private ActivityOptions() {
	}

	/** @hide AOSP: the options a Bundle made by toBundle() describes. */
	public ActivityOptions(Bundle opts) {
		mPackageName = opts.getString(KEY_PACKAGE_NAME);
		int[] bounds = opts.getIntArray(KEY_LAUNCH_BOUNDS);
		mLaunchBounds = bounds != null && bounds.length == 4 ? new Rect(bounds[0], bounds[1], bounds[2], bounds[3]) : null;
		mAnimationType = opts.getInt(KEY_ANIM_TYPE, ANIM_UNDEFINED);
		switch (mAnimationType) {
			case ANIM_CUSTOM:
				mCustomEnterResId = opts.getInt(KEY_ANIM_ENTER_RES_ID, 0);
				mCustomExitResId = opts.getInt(KEY_ANIM_EXIT_RES_ID, 0);
				mCustomBackgroundColor = opts.getInt(KEY_ANIM_BACKGROUND_COLOR, 0);
				break;
			case ANIM_CUSTOM_IN_PLACE:
				mCustomInPlaceResId = opts.getInt(KEY_ANIM_IN_PLACE_RES_ID, 0);
				break;
			case ANIM_SCALE_UP:
			case ANIM_CLIP_REVEAL:
			case ANIM_THUMBNAIL_SCALE_UP:
			case ANIM_THUMBNAIL_SCALE_DOWN:
			case ANIM_THUMBNAIL_ASPECT_SCALE_UP:
			case ANIM_THUMBNAIL_ASPECT_SCALE_DOWN:
				mStartX = opts.getInt(KEY_ANIM_START_X, 0);
				mStartY = opts.getInt(KEY_ANIM_START_Y, 0);
				mWidth = opts.getInt(KEY_ANIM_WIDTH, 0);
				mHeight = opts.getInt(KEY_ANIM_HEIGHT, 0);
				break;
			case ANIM_SCENE_TRANSITION:
				mIsReturning = opts.getBoolean(KEY_TRANSITION_IS_RETURNING, false);
				mSharedElementNames = opts.getStringArrayList(KEY_TRANSITION_SHARED_ELEMENTS);
				break;
		}
		mLockTaskMode = opts.getBoolean(KEY_LOCK_TASK_MODE, false);
		mShareIdentity = opts.getBoolean(KEY_SHARE_IDENTITY, false);
		mLaunchDisplayId = opts.getInt(KEY_LAUNCH_DISPLAY_ID, INVALID_DISPLAY);
		mLaunchWindowingMode = opts.getInt(KEY_LAUNCH_WINDOWING_MODE, 0);
		mLaunchTaskId = opts.getInt(KEY_LAUNCH_TASK_ID, -1);
		mPendingIntentLaunchFlags = opts.getInt(KEY_PENDING_INTENT_LAUNCH_FLAGS, 0);
		mUsageTimeReport = opts.getParcelable(KEY_USAGE_TIME_REPORT);
		mRotationAnimationHint = opts.getInt(KEY_ROTATION_ANIMATION_HINT, -1);
		mSplashScreenThemeResId = opts.getInt(KEY_SPLASH_SCREEN_THEME, 0);
		mSplashScreenStyle = opts.getInt(KEY_SPLASH_SCREEN_STYLE, 0);
		mAllowPassThroughOnTouchOutside = opts.getBoolean(KEY_ALLOW_PASS_THROUGH_ON_TOUCH_OUTSIDE, false);
		mPendingIntentBalMode = opts.getInt(KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED, MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED);
		mPendingIntentCreatorBalMode = opts.getInt(KEY_PENDING_INTENT_CREATOR_BACKGROUND_ACTIVITY_START_MODE, MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED);
	}

	/** @hide */
	public static ActivityOptions fromBundle(Bundle bOptions) {
		return bOptions != null ? new ActivityOptions(bOptions) : null;
	}

	public static ActivityOptions makeBasic() {
		return new ActivityOptions();
	}

	public static ActivityOptions makeCustomAnimation(Context context, int enterResId, int exitResId) {
		return makeCustomAnimation(context, enterResId, exitResId, 0);
	}

	public static ActivityOptions makeCustomAnimation(Context context, int enterResId, int exitResId, int backgroundColor) {
		ActivityOptions opts = new ActivityOptions();
		opts.mPackageName = context.getPackageName();
		opts.mAnimationType = ANIM_CUSTOM;
		opts.mCustomEnterResId = enterResId;
		opts.mCustomExitResId = exitResId;
		opts.mCustomBackgroundColor = backgroundColor;
		return opts;
	}

	/** @hide */
	public static ActivityOptions makeCustomInPlaceAnimation(Context context, int animId) {
		if (animId == 0) {
			throw new RuntimeException("You must specify a valid animation.");
		}
		ActivityOptions opts = new ActivityOptions();
		opts.mPackageName = context.getPackageName();
		opts.mAnimationType = ANIM_CUSTOM_IN_PLACE;
		opts.mCustomInPlaceResId = animId;
		return opts;
	}

	public static ActivityOptions makeScaleUpAnimation(View source, int startX, int startY, int width, int height) {
		ActivityOptions opts = new ActivityOptions();
		opts.mPackageName = source.getContext().getPackageName();
		opts.mAnimationType = ANIM_SCALE_UP;
		int[] pts = new int[2];
		source.getLocationOnScreen(pts);
		opts.mStartX = pts[0] + startX;
		opts.mStartY = pts[1] + startY;
		opts.mWidth = width;
		opts.mHeight = height;
		return opts;
	}

	public static ActivityOptions makeClipRevealAnimation(View source, int startX, int startY, int width, int height) {
		ActivityOptions opts = new ActivityOptions();
		opts.mAnimationType = ANIM_CLIP_REVEAL;
		int[] pts = new int[2];
		source.getLocationOnScreen(pts);
		opts.mStartX = pts[0] + startX;
		opts.mStartY = pts[1] + startY;
		opts.mWidth = width;
		opts.mHeight = height;
		return opts;
	}

	public static ActivityOptions makeThumbnailScaleUpAnimation(View source, Bitmap thumbnail, int startX, int startY) {
		ActivityOptions opts = new ActivityOptions();
		opts.mPackageName = source.getContext().getPackageName();
		opts.mAnimationType = ANIM_THUMBNAIL_SCALE_UP;
		opts.mThumbnail = thumbnail;
		int[] pts = new int[2];
		source.getLocationOnScreen(pts);
		opts.mStartX = pts[0] + startX;
		opts.mStartY = pts[1] + startY;
		return opts;
	}

	/**
	 * AOSP starts a scene transition (shared element) animation; ATL has no activity
	 * scene-transition coordinator, so the options carry the shared element names and the launch
	 * uses the default activity switch.
	 */
	public static ActivityOptions makeSceneTransitionAnimation(Activity activity, View sharedElement, String sharedElementName) {
		return makeSceneTransitionAnimation(activity, Pair.create(sharedElement, sharedElementName));
	}

	@SafeVarargs
	public static ActivityOptions makeSceneTransitionAnimation(Activity activity, Pair<View, String>... sharedElements) {
		ActivityOptions opts = new ActivityOptions();
		opts.mAnimationType = ANIM_SCENE_TRANSITION;
		opts.mSharedElementNames = new ArrayList<>();
		if (sharedElements != null) {
			for (Pair<View, String> p : sharedElements) {
				if (p.second == null)
					throw new IllegalArgumentException("Shared element name must not be null");
				opts.mSharedElementNames.add(p.second);
			}
		}
		return opts;
	}

	public static ActivityOptions makeTaskLaunchBehind() {
		final ActivityOptions opts = new ActivityOptions();
		opts.mAnimationType = ANIM_LAUNCH_TASK_BEHIND;
		return opts;
	}

	public boolean getLockTaskMode() {
		return mLockTaskMode;
	}

	public ActivityOptions setLockTaskEnabled(boolean lockTaskMode) {
		mLockTaskMode = lockTaskMode;
		return this;
	}

	public ActivityOptions setLaunchBounds(Rect screenSpacePixelRect) {
		mLaunchBounds = screenSpacePixelRect != null ? new Rect(screenSpacePixelRect) : null;
		return this;
	}

	public Rect getLaunchBounds() {
		return mLaunchBounds;
	}

	public int getLaunchDisplayId() {
		return mLaunchDisplayId;
	}

	public ActivityOptions setLaunchDisplayId(int launchDisplayId) {
		mLaunchDisplayId = launchDisplayId;
		return this;
	}

	/** @hide */
	public int getLaunchWindowingMode() {
		return mLaunchWindowingMode;
	}

	/** @hide */
	public void setLaunchWindowingMode(int windowingMode) {
		mLaunchWindowingMode = windowingMode;
	}

	/** @hide */
	public int getLaunchTaskId() {
		return mLaunchTaskId;
	}

	/** @hide */
	public void setLaunchTaskId(int taskId) {
		mLaunchTaskId = taskId;
	}

	public int getPendingIntentLaunchFlags() {
		return mPendingIntentLaunchFlags;
	}

	public ActivityOptions setPendingIntentLaunchFlags(int flags) {
		mPendingIntentLaunchFlags = flags;
		return this;
	}

	public boolean isShareIdentityEnabled() {
		return mShareIdentity;
	}

	public ActivityOptions setShareIdentityEnabled(boolean shareIdentity) {
		mShareIdentity = shareIdentity;
		return this;
	}

	public int getRotationAnimationHint() {
		return mRotationAnimationHint;
	}

	public void setRotationAnimationHint(int hint) {
		mRotationAnimationHint = hint;
	}

	public int getSplashScreenStyle() {
		return mSplashScreenStyle;
	}

	public ActivityOptions setSplashScreenStyle(int style) {
		mSplashScreenStyle = style;
		return this;
	}

	/** @hide */
	public int getSplashScreenThemeResId() {
		return mSplashScreenThemeResId;
	}

	public ActivityOptions setAllowPassThroughOnTouchOutside(boolean allowPassThrough) {
		mAllowPassThroughOnTouchOutside = allowPassThrough;
		return this;
	}

	/** @hide */
	public boolean isAllowPassThroughOnTouchOutside() {
		return mAllowPassThroughOnTouchOutside;
	}

	/** @deprecated use setPendingIntentBackgroundActivityStartMode */
	@Deprecated
	public void setPendingIntentBackgroundActivityLaunchAllowed(boolean allowed) {
		mPendingIntentBalMode = allowed ? MODE_BACKGROUND_ACTIVITY_START_ALLOWED : MODE_BACKGROUND_ACTIVITY_START_DENIED;
	}

	/** @deprecated use getPendingIntentBackgroundActivityStartMode */
	@Deprecated
	public boolean isPendingIntentBackgroundActivityLaunchAllowed() {
		return mPendingIntentBalMode != MODE_BACKGROUND_ACTIVITY_START_DENIED;
	}

	public ActivityOptions setPendingIntentBackgroundActivityStartMode(int state) {
		mPendingIntentBalMode = state;
		return this;
	}

	public int getPendingIntentBackgroundActivityStartMode() {
		return mPendingIntentBalMode;
	}

	public ActivityOptions setPendingIntentCreatorBackgroundActivityStartMode(int mode) {
		mPendingIntentCreatorBalMode = mode;
		return this;
	}

	public int getPendingIntentCreatorBackgroundActivityStartMode() {
		return mPendingIntentCreatorBalMode;
	}

	/**
	 * AOSP: the system sends the PendingIntent with the time the user spent in the launched
	 * activities (EXTRA_USAGE_TIME_REPORT); see Activity.tlReportUsageTime.
	 */
	public void requestUsageTimeReport(PendingIntent receiver) {
		mUsageTimeReport = receiver;
	}

	/** @hide */
	public PendingIntent getUsageTimeReport() {
		return mUsageTimeReport;
	}

	/** @hide */
	public String getPackageName() {
		return mPackageName;
	}

	/** @hide */
	public int getAnimationType() {
		return mAnimationType;
	}

	/** @hide */
	public int getCustomEnterResId() {
		return mCustomEnterResId;
	}

	/** @hide */
	public int getCustomExitResId() {
		return mCustomExitResId;
	}

	/** @hide */
	public int getCustomInPlaceResId() {
		return mCustomInPlaceResId;
	}

	/** @hide */
	public int getCustomBackgroundColor() {
		return mCustomBackgroundColor;
	}

	/** @hide */
	public Bitmap getThumbnail() {
		return mThumbnail;
	}

	/** @hide */
	public int getStartX() {
		return mStartX;
	}

	/** @hide */
	public int getStartY() {
		return mStartY;
	}

	/** @hide */
	public int getWidth() {
		return mWidth;
	}

	/** @hide */
	public int getHeight() {
		return mHeight;
	}

	/** @hide */
	public ArrayList<String> getSharedElementNames() {
		return mSharedElementNames;
	}

	/** @hide */
	public boolean isReturning() {
		return mIsReturning;
	}

	/** AOSP: takes over the other options' animation and a few launch flags. */
	public void update(ActivityOptions otherOptions) {
		if (otherOptions.mPackageName != null) {
			mPackageName = otherOptions.mPackageName;
		}
		mUsageTimeReport = otherOptions.mUsageTimeReport;
		mSharedElementNames = null;
		mIsReturning = false;
		mAnimationType = otherOptions.mAnimationType;
		switch (otherOptions.mAnimationType) {
			case ANIM_CUSTOM:
				mCustomEnterResId = otherOptions.mCustomEnterResId;
				mCustomExitResId = otherOptions.mCustomExitResId;
				mCustomBackgroundColor = otherOptions.mCustomBackgroundColor;
				mThumbnail = null;
				break;
			case ANIM_CUSTOM_IN_PLACE:
				mCustomInPlaceResId = otherOptions.mCustomInPlaceResId;
				break;
			case ANIM_SCALE_UP:
				mStartX = otherOptions.mStartX;
				mStartY = otherOptions.mStartY;
				mWidth = otherOptions.mWidth;
				mHeight = otherOptions.mHeight;
				break;
			case ANIM_THUMBNAIL_SCALE_UP:
			case ANIM_THUMBNAIL_SCALE_DOWN:
			case ANIM_THUMBNAIL_ASPECT_SCALE_UP:
			case ANIM_THUMBNAIL_ASPECT_SCALE_DOWN:
				mThumbnail = otherOptions.mThumbnail;
				mStartX = otherOptions.mStartX;
				mStartY = otherOptions.mStartY;
				mWidth = otherOptions.mWidth;
				mHeight = otherOptions.mHeight;
				break;
			case ANIM_SCENE_TRANSITION:
				mSharedElementNames = otherOptions.mSharedElementNames;
				mIsReturning = otherOptions.mIsReturning;
				mThumbnail = null;
				break;
		}
		mLockTaskMode = otherOptions.mLockTaskMode;
		mShareIdentity = otherOptions.mShareIdentity;
	}

	public Bundle toBundle() {
		Bundle b = new Bundle();
		if (mPendingIntentBalMode != MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED)
			b.putInt(KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED, mPendingIntentBalMode);
		if (mPendingIntentCreatorBalMode != MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED)
			b.putInt(KEY_PENDING_INTENT_CREATOR_BACKGROUND_ACTIVITY_START_MODE, mPendingIntentCreatorBalMode);
		if (mPackageName != null) {
			b.putString(KEY_PACKAGE_NAME, mPackageName);
		}
		if (mLaunchBounds != null) {
			/* AOSP parcels the Rect; ATL's Rect is not Parcelable, so its four edges go in */
			b.putIntArray(KEY_LAUNCH_BOUNDS, new int[] {mLaunchBounds.left, mLaunchBounds.top, mLaunchBounds.right, mLaunchBounds.bottom});
		}
		if (mAnimationType != ANIM_UNDEFINED) {
			b.putInt(KEY_ANIM_TYPE, mAnimationType);
		}
		if (mUsageTimeReport != null) {
			b.putParcelable(KEY_USAGE_TIME_REPORT, mUsageTimeReport);
		}
		switch (mAnimationType) {
			case ANIM_CUSTOM:
				b.putInt(KEY_ANIM_ENTER_RES_ID, mCustomEnterResId);
				b.putInt(KEY_ANIM_EXIT_RES_ID, mCustomExitResId);
				b.putInt(KEY_ANIM_BACKGROUND_COLOR, mCustomBackgroundColor);
				break;
			case ANIM_CUSTOM_IN_PLACE:
				b.putInt(KEY_ANIM_IN_PLACE_RES_ID, mCustomInPlaceResId);
				break;
			case ANIM_SCALE_UP:
			case ANIM_CLIP_REVEAL:
				b.putInt(KEY_ANIM_START_X, mStartX);
				b.putInt(KEY_ANIM_START_Y, mStartY);
				b.putInt(KEY_ANIM_WIDTH, mWidth);
				b.putInt(KEY_ANIM_HEIGHT, mHeight);
				break;
			case ANIM_THUMBNAIL_SCALE_UP:
			case ANIM_THUMBNAIL_SCALE_DOWN:
			case ANIM_THUMBNAIL_ASPECT_SCALE_UP:
			case ANIM_THUMBNAIL_ASPECT_SCALE_DOWN:
				/* AOSP passes the thumbnail as a HardwareBuffer for the system's window animation;
				 * ATL's Bitmap is not Parcelable and there is no window animation to use it */
				b.putInt(KEY_ANIM_START_X, mStartX);
				b.putInt(KEY_ANIM_START_Y, mStartY);
				b.putInt(KEY_ANIM_WIDTH, mWidth);
				b.putInt(KEY_ANIM_HEIGHT, mHeight);
				break;
			case ANIM_SCENE_TRANSITION:
				b.putBoolean(KEY_TRANSITION_IS_RETURNING, mIsReturning);
				b.putStringArrayList(KEY_TRANSITION_SHARED_ELEMENTS, mSharedElementNames);
				break;
		}
		if (mLockTaskMode) {
			b.putBoolean(KEY_LOCK_TASK_MODE, mLockTaskMode);
		}
		if (mShareIdentity) {
			b.putBoolean(KEY_SHARE_IDENTITY, mShareIdentity);
		}
		if (mLaunchDisplayId != INVALID_DISPLAY) {
			b.putInt(KEY_LAUNCH_DISPLAY_ID, mLaunchDisplayId);
		}
		if (mLaunchWindowingMode != 0) {
			b.putInt(KEY_LAUNCH_WINDOWING_MODE, mLaunchWindowingMode);
		}
		if (mLaunchTaskId != -1) {
			b.putInt(KEY_LAUNCH_TASK_ID, mLaunchTaskId);
		}
		if (mPendingIntentLaunchFlags != 0) {
			b.putInt(KEY_PENDING_INTENT_LAUNCH_FLAGS, mPendingIntentLaunchFlags);
		}
		if (mRotationAnimationHint != -1) {
			b.putInt(KEY_ROTATION_ANIMATION_HINT, mRotationAnimationHint);
		}
		if (mSplashScreenThemeResId != 0) {
			b.putInt(KEY_SPLASH_SCREEN_THEME, mSplashScreenThemeResId);
		}
		if (mSplashScreenStyle != 0) {
			b.putInt(KEY_SPLASH_SCREEN_STYLE, mSplashScreenStyle);
		}
		if (mAllowPassThroughOnTouchOutside) {
			b.putBoolean(KEY_ALLOW_PASS_THROUGH_ON_TOUCH_OUTSIDE, true);
		}
		return b;
	}

	/** @hide AOSP: options for the activity that was just launched with these, minus the one-shot animation. */
	public ActivityOptions forTargetActivity() {
		if (mAnimationType == ANIM_SCENE_TRANSITION) {
			final ActivityOptions result = new ActivityOptions();
			result.update(this);
			return result;
		}
		return null;
	}

	@Override
	public String toString() {
		return "ActivityOptions(" + hashCode() + "), mPackageName=" + mPackageName
		     + ", mAnimationType=" + mAnimationType + ", mStartX=" + mStartX + ", mStartY="
		     + mStartY + ", mWidth=" + mWidth + ", mHeight=" + mHeight + ", mLaunchDisplayId="
		     + mLaunchDisplayId;
	}
}
