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

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.util.TypedValue;

/**
 * Contains methods to standard constants used in the UI for timeouts, sizes, and distances.
 *
 * teslalinux: AOSP's ViewConfiguration (android15). The per-context values come from the framework
 * resources (config_viewConfigurationTouchSlop and friends, ATL's framework-res carries AOSP's
 * config.xml) and are scaled by the display density as on a device; ATL returned unscaled constants
 * (touch slop 8 px on a 200 dpi screen instead of 10, fling limits in px rather than dp). A value
 * that cannot be read falls back to AOSP's config.xml default, scaled the same way.
 */
public class ViewConfiguration {
	private static final String TAG = "ViewConfiguration";

	/** Defines the width of the horizontal scrollbar and the height of the vertical scrollbar in dips */
	private static final int SCROLL_BAR_SIZE = 4;
	/** Duration of the fade when scrollbars fade away in milliseconds */
	private static final int SCROLL_BAR_FADE_DURATION = 250;
	/** Default delay before the scrollbars fade in milliseconds */
	private static final int SCROLL_BAR_DEFAULT_DELAY = 300;
	/** Defines the length of the fading edges in dips */
	private static final int FADING_EDGE_LENGTH = 12;
	/** Defines the duration in milliseconds of the pressed state in child components. */
	private static final int PRESSED_STATE_DURATION = 64;
	/** Defines the default duration in milliseconds before a press turns into a long press */
	public static final int DEFAULT_LONG_PRESS_TIMEOUT = 400;
	/** Defines the default duration in milliseconds between the first tap's up event and the second tap's down event for an interaction to be considered part of the same multi-press. */
	private static final int DEFAULT_MULTI_PRESS_TIMEOUT = 300;
	/** Defines the time between successive key repeats in milliseconds. */
	private static final int KEY_REPEAT_DELAY = 50;
	/** Defines the duration in milliseconds a user needs to hold down the appropriate button to bring up the global actions dialog (power off, lock screen, etc). */
	private static final int GLOBAL_ACTIONS_KEY_TIMEOUT = 500;
	/** Defines the duration in milliseconds a user needs to hold down the appropriate buttons (power + volume down) to trigger the screenshot chord. */
	private static final int SCREENSHOT_CHORD_KEY_TIMEOUT = 0;
	/** Defines the duration in milliseconds a user needs to hold down the appropriate button to bring up the accessibility shortcut for the first time */
	private static final int A11Y_SHORTCUT_KEY_TIMEOUT = 3000;
	/** Defines the duration in milliseconds a user needs to hold down the appropriate button to enable the accessibility shortcut once it's already been enabled. */
	private static final int A11Y_SHORTCUT_KEY_TIMEOUT_AFTER_CONFIRMATION = 1000;
	/** Defines the duration in milliseconds we will wait to see if a touch event is a tap or a scroll. */
	private static final int TAP_TIMEOUT = 100;
	/** Defines the duration in milliseconds we will wait to see if a touch event is a jump tap. */
	private static final int JUMP_TAP_TIMEOUT = 500;
	/** Defines the duration in milliseconds between the first tap's up event and the second tap's down event for an interaction to be considered a double-tap. */
	private static final int DOUBLE_TAP_TIMEOUT = 300;
	/** Defines the minimum duration in milliseconds between the first tap's up event and the second tap's down event for an interaction to be considered a double-tap. */
	private static final int DOUBLE_TAP_MIN_TIME = 40;
	/** Defines the maximum duration in milliseconds between a touch pad touch and release for a given touch to be considered a tap (click) as opposed to a hover movement gesture. */
	private static final int HOVER_TAP_TIMEOUT = 150;
	/** Defines the maximum distance in pixels that a touch pad touch can move before being released for it to be considered a tap (click) as opposed to a hover movement gesture. */
	private static final int HOVER_TAP_SLOP = 20;
	/** Defines the duration in milliseconds we want to display zoom controls in response to a user panning within an application. */
	private static final int ZOOM_CONTROLS_TIMEOUT = 3000;
	/** Inset in dips to look for touchable content when the user touches the edge of the screen */
	private static final int EDGE_SLOP = 12;
	/** Distance a touch can wander before we think the user is scrolling in dips. */
	private static final int TOUCH_SLOP = 8;
	/** Defines the minimum size of the touch target for a scrollbar in dips */
	private static final int MIN_SCROLLBAR_TOUCH_TARGET = 48;
	/** Distance the first touch can wander before we stop considering this event a double tap (in dips) */
	private static final int DOUBLE_TAP_TOUCH_SLOP = TOUCH_SLOP;
	/** Distance a touch can wander before we think the user is attempting a paged scroll (in dips) */
	private static final int PAGING_TOUCH_SLOP = TOUCH_SLOP * 2;
	/** Distance in dips between the first touch and second touch to still be considered a double tap */
	private static final int DOUBLE_TAP_SLOP = 100;
	/** Distance in dips a touch needs to be outside of a window's bounds for it to count as outside for purposes of dismissing the window. */
	private static final int WINDOW_TOUCH_SLOP = 16;
	/** Margin in dips around text line bounds where stylus handwriting gestures should be supported. */
	private static final int HANDWRITING_GESTURE_LINE_MARGIN = 16;
	/** Minimum velocity to initiate a fling, as measured in dips per second */
	private static final int MINIMUM_FLING_VELOCITY = 50;
	/** Maximum velocity to initiate a fling, as measured in dips per second */
	private static final int MAXIMUM_FLING_VELOCITY = 8000;
	/** Value used as a minimum fling velocity, when fling is not supported. */
	private static final int NO_FLING_MIN_VELOCITY = Integer.MAX_VALUE;
	/** Value used as a maximum fling velocity, when fling is not supported. */
	private static final int NO_FLING_MAX_VELOCITY = Integer.MIN_VALUE;
	/** @hide */
	public static final int NO_HAPTIC_SCROLL_TICK_INTERVAL = Integer.MAX_VALUE;
	/** Delay before dispatching a recurring accessibility event in milliseconds. */
	private static final long SEND_RECURRING_ACCESSIBILITY_EVENTS_INTERVAL_MILLIS = 100;
	/** The maximum size of View's drawing cache, expressed in bytes. */
	@Deprecated
	private static final int MAXIMUM_DRAWING_CACHE_SIZE = 480 * 800 * 4; // ARGB8888
	/** The coefficient of friction applied to flings/scrolls. */
	private static final float SCROLL_FRICTION = 0.015f;
	/** Max distance in dips to overscroll for edge effects */
	private static final int OVERSCROLL_DISTANCE = 0;
	/** Max distance in dips to overfling for edge effects */
	private static final int OVERFLING_DISTANCE = 6;
	/** Amount to scroll in response to a horizontal {@link MotionEvent#ACTION_SCROLL} event, in dips per axis value. */
	private static final float HORIZONTAL_SCROLL_FACTOR = 64;
	/** Amount to scroll in response to a vertical {@link MotionEvent#ACTION_SCROLL} event, in dips per axis value. */
	private static final float VERTICAL_SCROLL_FACTOR = 64;
	/** Default duration to hide an action mode for. */
	private static final long ACTION_MODE_HIDE_DURATION_DEFAULT = 2000;
	/** Defines the duration in milliseconds before an end of a long press causes a tooltip to be hidden. */
	private static final int LONG_PRESS_TOOLTIP_HIDE_TIMEOUT = 1500;
	/** Defines the duration in milliseconds before a hover event causes a tooltip to be shown. */
	private static final int HOVER_TOOLTIP_SHOW_TIMEOUT = 500;
	/** Defines the duration in milliseconds before mouse inactivity causes a tooltip to be hidden. (default variant to be used when {@link View#SYSTEM_UI_FLAG_LOW_PROFILE} is not set). */
	private static final int HOVER_TOOLTIP_HIDE_TIMEOUT = 15000;
	/** Defines the duration in milliseconds before mouse inactivity causes a tooltip to be hidden (short version to be used when {@link View#SYSTEM_UI_FLAG_LOW_PROFILE} is set). */
	private static final int HOVER_TOOLTIP_HIDE_SHORT_TIMEOUT = 3000;

	/** Configuration values for overriding {@link #hasPermanentMenuKey()} behavior. These constants must match the definition in res/values/config.xml. */
	private static final int HAS_PERMANENT_MENU_KEY_AUTODETECT = 0;
	private static final int HAS_PERMANENT_MENU_KEY_TRUE = 1;
	private static final int HAS_PERMANENT_MENU_KEY_FALSE = 2;

	/** The multiplication factor for inhibiting default gestures. */
	private static final float AMBIGUOUS_GESTURE_MULTIPLIER = 2f;

	/** The timeout value in milliseconds to adjust the selection span and actions for the selected text when TextClassifier has been initialized. */
	private static final int SMART_SELECTION_INITIALIZED_TIMEOUT_IN_MILLISECOND = 200;
	/** The timeout value in milliseconds to adjust the selection span and actions for the selected text when TextClassifier has not been initialized. */
	private static final int SMART_SELECTION_INITIALIZING_TIMEOUT_IN_MILLISECOND = 500;

	private final boolean mConstructedWithContext;
	private final int mEdgeSlop;
	private final int mFadingEdgeLength;
	private final int mMinimumFlingVelocity;
	private final int mMaximumFlingVelocity;
	private final int mMinimumRotaryEncoderFlingVelocity;
	private final int mMaximumRotaryEncoderFlingVelocity;
	private final int mRotaryEncoderHapticScrollFeedbackTickIntervalPixels;
	private final boolean mRotaryEncoderHapticScrollFeedbackEnabled;
	private final int mScrollbarSize;
	private final int mTouchSlop;
	private final int mHandwritingSlop;
	private final int mMinScalingSpan;
	private final int mHoverSlop;
	private final int mMinScrollbarTouchTarget;
	private final int mDoubleTapTouchSlop;
	private final int mPagingTouchSlop;
	private final int mDoubleTapSlop;
	private final int mWindowTouchSlop;
	private final int mHandwritingGestureLineMargin;
	private final float mAmbiguousGestureMultiplier;
	private final int mMaximumDrawingCacheSize;
	private final int mOverscrollDistance;
	private final int mOverflingDistance;
	private final boolean mFadingMarqueeEnabled;
	private final long mGlobalActionsKeyTimeout;
	private final float mVerticalScrollFactor;
	private final float mHorizontalScrollFactor;
	private final boolean mShowMenuShortcutsWhenKeyboardPresent;
	private final long mScreenshotChordKeyTimeout;
	private final int mSmartSelectionInitializedTimeout;
	private final int mSmartSelectionInitializingTimeout;
	private final boolean mPreferKeepClearForFocusEnabled;

	private boolean sHasPermanentMenuKey;
	private boolean sHasPermanentMenuKeySet;

	static final SparseArray<ViewConfiguration> sConfigurations =
		new SparseArray<ViewConfiguration>(2);

	/**
	 * @deprecated Use {@link android.view.ViewConfiguration#get(android.content.Context)} instead.
	 */
	@Deprecated
	public ViewConfiguration() {
		mConstructedWithContext = false;
		mEdgeSlop = EDGE_SLOP;
		mFadingEdgeLength = FADING_EDGE_LENGTH;
		mMinimumFlingVelocity = MINIMUM_FLING_VELOCITY;
		mMaximumFlingVelocity = MAXIMUM_FLING_VELOCITY;
		mMinimumRotaryEncoderFlingVelocity = MINIMUM_FLING_VELOCITY;
		mMaximumRotaryEncoderFlingVelocity = MAXIMUM_FLING_VELOCITY;
		mRotaryEncoderHapticScrollFeedbackEnabled = false;
		mRotaryEncoderHapticScrollFeedbackTickIntervalPixels = NO_HAPTIC_SCROLL_TICK_INTERVAL;
		mScrollbarSize = SCROLL_BAR_SIZE;
		mTouchSlop = TOUCH_SLOP;
		mHandwritingSlop = 2; // config_viewConfigurationHandwritingSlop
		mHoverSlop = TOUCH_SLOP / 2;
		mMinScrollbarTouchTarget = MIN_SCROLLBAR_TOUCH_TARGET;
		mDoubleTapTouchSlop = DOUBLE_TAP_TOUCH_SLOP;
		mPagingTouchSlop = PAGING_TOUCH_SLOP;
		mDoubleTapSlop = DOUBLE_TAP_SLOP;
		mWindowTouchSlop = WINDOW_TOUCH_SLOP;
		mHandwritingGestureLineMargin = HANDWRITING_GESTURE_LINE_MARGIN;
		mAmbiguousGestureMultiplier = AMBIGUOUS_GESTURE_MULTIPLIER;
		//noinspection deprecation
		mMaximumDrawingCacheSize = MAXIMUM_DRAWING_CACHE_SIZE;
		mOverscrollDistance = OVERSCROLL_DISTANCE;
		mOverflingDistance = OVERFLING_DISTANCE;
		mFadingMarqueeEnabled = true;
		mGlobalActionsKeyTimeout = GLOBAL_ACTIONS_KEY_TIMEOUT;
		mHorizontalScrollFactor = HORIZONTAL_SCROLL_FACTOR;
		mVerticalScrollFactor = VERTICAL_SCROLL_FACTOR;
		mShowMenuShortcutsWhenKeyboardPresent = false;
		mScreenshotChordKeyTimeout = SCREENSHOT_CHORD_KEY_TIMEOUT;

		// Getter throws if mConstructedWithContext is false so doesn't matter what
		// this value is.
		mMinScalingSpan = 0;
		mSmartSelectionInitializedTimeout = SMART_SELECTION_INITIALIZED_TIMEOUT_IN_MILLISECOND;
		mSmartSelectionInitializingTimeout = SMART_SELECTION_INITIALIZING_TIMEOUT_IN_MILLISECOND;
		mPreferKeepClearForFocusEnabled = false;
	}

	/**
	 * Creates a new configuration for the specified visual {@link Context}. The configuration depends
	 * on various parameters of the {@link Context}, like the dimension of the display or the density
	 * of the display.
	 */
	private ViewConfiguration(Context context) {
		mConstructedWithContext = true;
		final Resources res = context.getResources();
		final DisplayMetrics metrics = res.getDisplayMetrics();
		final Configuration config = res.getConfiguration();
		final float density = metrics.density;
		final float sizeAndDensity;
		if (config.isLayoutSizeAtLeast(Configuration.SCREENLAYOUT_SIZE_XLARGE)) {
			sizeAndDensity = density * 1.5f;
		} else {
			sizeAndDensity = density;
		}

		mEdgeSlop = (int)(sizeAndDensity * EDGE_SLOP + 0.5f);
		mFadingEdgeLength = (int)(sizeAndDensity * FADING_EDGE_LENGTH + 0.5f);
		mScrollbarSize = dimen(res, com.android.internal.R.dimen.config_scrollbarSize, 4, metrics);
		mDoubleTapSlop = (int)(sizeAndDensity * DOUBLE_TAP_SLOP + 0.5f);
		mWindowTouchSlop = (int)(sizeAndDensity * WINDOW_TOUCH_SLOP + 0.5f);

		float multiplier = AMBIGUOUS_GESTURE_MULTIPLIER;
		try {
			final TypedValue multiplierValue = new TypedValue();
			res.getValue(com.android.internal.R.dimen.config_ambiguousGestureMultiplier, multiplierValue, true /*resolveRefs*/);
			multiplier = multiplierValue.getFloat();
		} catch (RuntimeException e) {
			// AOSP's config.xml default
		}
		mAmbiguousGestureMultiplier = Math.max(1.0f, multiplier);

		// Size of the screen in bytes, in ARGB_8888 format
		mMaximumDrawingCacheSize = 4 * metrics.widthPixels * metrics.heightPixels;

		mOverscrollDistance = (int)(sizeAndDensity * OVERSCROLL_DISTANCE + 0.5f);
		mOverflingDistance = (int)(sizeAndDensity * OVERFLING_DISTANCE + 0.5f);

		if (!sHasPermanentMenuKeySet) {
			final int configVal = integer(res, com.android.internal.R.integer.config_overrideHasPermanentMenuKey, HAS_PERMANENT_MENU_KEY_AUTODETECT);
			switch (configVal) {
				default:
				case HAS_PERMANENT_MENU_KEY_AUTODETECT: {
					// AOSP asks the window manager whether the display has a navigation bar and
					// assumes a menu key when it has none. This platform has neither a navigation
					// bar nor a menu key (the car page sends no KEYCODE_MENU), so the autodetected
					// answer is the absence of a permanent menu key: apps show their overflow menus.
					sHasPermanentMenuKey = false;
					sHasPermanentMenuKeySet = true;
				} break;

				case HAS_PERMANENT_MENU_KEY_TRUE:
					sHasPermanentMenuKey = true;
					sHasPermanentMenuKeySet = true;
					break;

				case HAS_PERMANENT_MENU_KEY_FALSE:
					sHasPermanentMenuKey = false;
					sHasPermanentMenuKeySet = true;
					break;
			}
		}

		mFadingMarqueeEnabled = bool(res, com.android.internal.R.bool.config_ui_enableFadingMarquee, false);
		mTouchSlop = dimen(res, com.android.internal.R.dimen.config_viewConfigurationTouchSlop, 8, metrics);
		mHandwritingSlop = dimen(res, com.android.internal.R.dimen.config_viewConfigurationHandwritingSlop, 2, metrics);
		mHoverSlop = dimen(res, com.android.internal.R.dimen.config_viewConfigurationHoverSlop, 4, metrics);
		mMinScrollbarTouchTarget = dimen(res, com.android.internal.R.dimen.config_minScrollbarTouchTarget, 48, metrics);
		mPagingTouchSlop = mTouchSlop * 2;

		mDoubleTapTouchSlop = mTouchSlop;

		mHandwritingGestureLineMargin = dimen(res, com.android.internal.R.dimen.config_viewConfigurationHandwritingGestureLineMargin, 16, metrics);

		mMinimumFlingVelocity = dimen(res, com.android.internal.R.dimen.config_viewMinFlingVelocity, 50, metrics);
		mMaximumFlingVelocity = dimen(res, com.android.internal.R.dimen.config_viewMaxFlingVelocity, 8000, metrics);

		int configMinRotaryEncoderFlingVelocity = dimen(res, com.android.internal.R.dimen.config_viewMinRotaryEncoderFlingVelocity, -1, metrics);
		int configMaxRotaryEncoderFlingVelocity = dimen(res, com.android.internal.R.dimen.config_viewMaxRotaryEncoderFlingVelocity, -1, metrics);
		if (configMinRotaryEncoderFlingVelocity < 0 || configMaxRotaryEncoderFlingVelocity < 0) {
			mMinimumRotaryEncoderFlingVelocity = NO_FLING_MIN_VELOCITY;
			mMaximumRotaryEncoderFlingVelocity = NO_FLING_MAX_VELOCITY;
		} else {
			mMinimumRotaryEncoderFlingVelocity = configMinRotaryEncoderFlingVelocity;
			mMaximumRotaryEncoderFlingVelocity = configMaxRotaryEncoderFlingVelocity;
		}
		// config_rotaryEncoderAxisScrollTickInterval / config_viewRotaryEncoderHapticScrollFedbackEnabled:
		// AOSP's defaults (no rotary encoder haptics); there is no rotary encoder on this platform.
		mRotaryEncoderHapticScrollFeedbackTickIntervalPixels = NO_HAPTIC_SCROLL_TICK_INTERVAL;
		mRotaryEncoderHapticScrollFeedbackEnabled = false;

		mGlobalActionsKeyTimeout = integer(res, com.android.internal.R.integer.config_globalActionsKeyTimeout, GLOBAL_ACTIONS_KEY_TIMEOUT);

		mHorizontalScrollFactor = dimen(res, com.android.internal.R.dimen.config_horizontalScrollFactor, 64, metrics);
		mVerticalScrollFactor = dimen(res, com.android.internal.R.dimen.config_verticalScrollFactor, 64, metrics);

		mShowMenuShortcutsWhenKeyboardPresent = bool(res, com.android.internal.R.bool.config_showMenuShortcutsWhenKeyboardPresent, false);

		// config_minScalingSpan is 27mm in AOSP's config.xml
		int minScalingSpan;
		try {
			minScalingSpan = res.getDimensionPixelSize(com.android.internal.R.dimen.config_minScalingSpan);
		} catch (RuntimeException e) {
			minScalingSpan = (int)(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 27, metrics) + 0.5f);
		}
		mMinScalingSpan = minScalingSpan;

		mScreenshotChordKeyTimeout = integer(res, com.android.internal.R.integer.config_screenshotChordKeyTimeout, SCREENSHOT_CHORD_KEY_TIMEOUT);

		mSmartSelectionInitializedTimeout = integer(res, com.android.internal.R.integer.config_smartSelectionInitializedTimeoutMillis, SMART_SELECTION_INITIALIZED_TIMEOUT_IN_MILLISECOND);
		mSmartSelectionInitializingTimeout = integer(res, com.android.internal.R.integer.config_smartSelectionInitializingTimeoutMillis, SMART_SELECTION_INITIALIZING_TIMEOUT_IN_MILLISECOND);
		mPreferKeepClearForFocusEnabled = bool(res, com.android.internal.R.bool.config_preferKeepClearForFocus, false);
	}

	/** Resources.getDimensionPixelSize of a framework dimension, else AOSP's default in dp converted the same way. */
	private static int dimen(Resources res, int id, int defaultDp, DisplayMetrics metrics) {
		try {
			return res.getDimensionPixelSize(id);
		} catch (RuntimeException e) {
			final float f = defaultDp * metrics.density;
			final int px = (int)(f >= 0 ? f + 0.5f : f - 0.5f);
			if (px != 0)
				return px;
			return f == 0 ? 0 : (f > 0 ? 1 : -1);
		}
	}

	private static int integer(Resources res, int id, int def) {
		try {
			return res.getInteger(id);
		} catch (RuntimeException e) {
			return def;
		}
	}

	private static boolean bool(Resources res, int id, boolean def) {
		try {
			return res.getBoolean(id);
		} catch (RuntimeException e) {
			return def;
		}
	}

	/**
	 * Returns a configuration for the specified visual {@link Context}. The configuration depends on
	 * various parameters of the {@link Context}, like the dimension of the display or the density of
	 * the display.
	 */
	public static ViewConfiguration get(Context context) {
		final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
		final int density = (int)(100.0f * metrics.density);

		ViewConfiguration configuration = sConfigurations.get(density);
		if (configuration == null) {
			configuration = new ViewConfiguration(context);
			sConfigurations.put(density, configuration);
		}

		return configuration;
	}

	/** @hide */
	public static void resetCacheForTesting() {
		sConfigurations.clear();
	}

	/** @hide */
	public static void setInstanceForTesting(Context context, ViewConfiguration instance) {
		final DisplayMetrics metrics = context.getResources().getDisplayMetrics();
		final int density = (int)(100.0f * metrics.density);
		sConfigurations.put(density, instance);
	}

	@Deprecated
	public static int getScrollBarSize() {
		return SCROLL_BAR_SIZE;
	}

	public int getScaledScrollBarSize() {
		return mScrollbarSize;
	}

	public int getScaledMinScrollbarTouchTarget() {
		return mMinScrollbarTouchTarget;
	}

	public static int getScrollBarFadeDuration() {
		return SCROLL_BAR_FADE_DURATION;
	}

	public static int getScrollDefaultDelay() {
		return SCROLL_BAR_DEFAULT_DELAY;
	}

	@Deprecated
	public static int getFadingEdgeLength() {
		return FADING_EDGE_LENGTH;
	}

	public int getScaledFadingEdgeLength() {
		return mFadingEdgeLength;
	}

	public static int getPressedStateDuration() {
		return PRESSED_STATE_DURATION;
	}

	/**
	 * AOSP reads Settings.Secure.LONG_PRESS_TIMEOUT through the core settings, which hold the
	 * default unless the user changed it in accessibility settings. This platform has no such
	 * setting, so the value is AOSP's default.
	 */
	public static int getLongPressTimeout() {
		return DEFAULT_LONG_PRESS_TIMEOUT;
	}

	/** As {@link #getLongPressTimeout}: Settings.Secure.MULTI_PRESS_TIMEOUT's default. */
	public static int getMultiPressTimeout() {
		return DEFAULT_MULTI_PRESS_TIMEOUT;
	}

	public static int getKeyRepeatTimeout() {
		return getLongPressTimeout();
	}

	public static int getKeyRepeatDelay() {
		return KEY_REPEAT_DELAY;
	}

	public static int getTapTimeout() {
		return TAP_TIMEOUT;
	}

	public static int getJumpTapTimeout() {
		return JUMP_TAP_TIMEOUT;
	}

	public static int getDoubleTapTimeout() {
		return DOUBLE_TAP_TIMEOUT;
	}

	/** @hide */
	public static int getDoubleTapMinTime() {
		return DOUBLE_TAP_MIN_TIME;
	}

	/** @hide */
	public static int getHoverTapTimeout() {
		return HOVER_TAP_TIMEOUT;
	}

	/** @hide */
	public static int getHoverTapSlop() {
		return HOVER_TAP_SLOP;
	}

	@Deprecated
	public static int getEdgeSlop() {
		return EDGE_SLOP;
	}

	public int getScaledEdgeSlop() {
		return mEdgeSlop;
	}

	@Deprecated
	public static int getTouchSlop() {
		return TOUCH_SLOP;
	}

	public int getScaledTouchSlop() {
		return mTouchSlop;
	}

	public int getScaledHandwritingSlop() {
		return mHandwritingSlop;
	}

	public int getScaledHoverSlop() {
		return mHoverSlop;
	}

	/** @hide */
	public int getScaledDoubleTapTouchSlop() {
		return mDoubleTapTouchSlop;
	}

	public int getScaledPagingTouchSlop() {
		return mPagingTouchSlop;
	}

	@Deprecated
	public static int getDoubleTapSlop() {
		return DOUBLE_TAP_SLOP;
	}

	public int getScaledDoubleTapSlop() {
		return mDoubleTapSlop;
	}

	public int getScaledHandwritingGestureLineMargin() {
		return mHandwritingGestureLineMargin;
	}

	public static long getSendRecurringAccessibilityEventsInterval() {
		return SEND_RECURRING_ACCESSIBILITY_EVENTS_INTERVAL_MILLIS;
	}

	@Deprecated
	public static int getWindowTouchSlop() {
		return WINDOW_TOUCH_SLOP;
	}

	public int getScaledWindowTouchSlop() {
		return mWindowTouchSlop;
	}

	@Deprecated
	public static int getMinimumFlingVelocity() {
		return MINIMUM_FLING_VELOCITY;
	}

	public int getScaledMinimumFlingVelocity() {
		return mMinimumFlingVelocity;
	}

	@Deprecated
	public static int getMaximumFlingVelocity() {
		return MAXIMUM_FLING_VELOCITY;
	}

	public int getScaledMaximumFlingVelocity() {
		return mMaximumFlingVelocity;
	}

	/** @hide */
	public int getScaledScrollFactor() {
		return (int)mVerticalScrollFactor;
	}

	public float getScaledHorizontalScrollFactor() {
		return mHorizontalScrollFactor;
	}

	public float getScaledVerticalScrollFactor() {
		return mVerticalScrollFactor;
	}

	@Deprecated
	public static int getMaximumDrawingCacheSize() {
		//noinspection deprecation
		return MAXIMUM_DRAWING_CACHE_SIZE;
	}

	public int getScaledMaximumDrawingCacheSize() {
		return mMaximumDrawingCacheSize;
	}

	public int getScaledOverscrollDistance() {
		return mOverscrollDistance;
	}

	public int getScaledOverflingDistance() {
		return mOverflingDistance;
	}

	public static long getZoomControlsTimeout() {
		return ZOOM_CONTROLS_TIMEOUT;
	}

	@Deprecated
	public static long getGlobalActionKeyTimeout() {
		return GLOBAL_ACTIONS_KEY_TIMEOUT;
	}

	/** @hide */
	public long getDeviceGlobalActionKeyTimeout() {
		return mGlobalActionsKeyTimeout;
	}

	/** @hide */
	public long getScreenshotChordKeyTimeout() {
		return mScreenshotChordKeyTimeout;
	}

	/** @hide */
	public long getAccessibilityShortcutKeyTimeout() {
		return A11Y_SHORTCUT_KEY_TIMEOUT;
	}

	/** @hide */
	public long getAccessibilityShortcutKeyTimeoutAfterConfirmation() {
		return A11Y_SHORTCUT_KEY_TIMEOUT_AFTER_CONFIRMATION;
	}

	public static float getScrollFriction() {
		return SCROLL_FRICTION;
	}

	public static long getDefaultActionModeHideDuration() {
		return ACTION_MODE_HIDE_DURATION_DEFAULT;
	}

	@Deprecated
	public static float getAmbiguousGestureMultiplier() {
		return AMBIGUOUS_GESTURE_MULTIPLIER;
	}

	public float getScaledAmbiguousGestureMultiplier() {
		return mAmbiguousGestureMultiplier;
	}

	/**
	 * Report if the device has a permanent menu key available to the user.
	 */
	public boolean hasPermanentMenuKey() {
		return sHasPermanentMenuKey;
	}

	/**
	 * Minimum absolute value of velocity to initiate a fling for a motion generated by an
	 * {@link InputDevice} with an id of {@code inputDeviceId}, from an input {@code source} and on
	 * a given motion event {@code axis}.
	 */
	public int getScaledMinimumFlingVelocity(int inputDeviceId, int axis, int source) {
		if (!isInputDeviceInfoValid(inputDeviceId, axis, source))
			return NO_FLING_MIN_VELOCITY;

		if (source == InputDevice.SOURCE_ROTARY_ENCODER)
			return mMinimumRotaryEncoderFlingVelocity;

		return mMinimumFlingVelocity;
	}

	/**
	 * Maximum absolute value of velocity to initiate a fling for a motion generated by an
	 * {@link InputDevice} with an id of {@code inputDeviceId}, from an input {@code source} and on
	 * a given motion event {@code axis}.
	 */
	public int getScaledMaximumFlingVelocity(int inputDeviceId, int axis, int source) {
		if (!isInputDeviceInfoValid(inputDeviceId, axis, source))
			return NO_FLING_MAX_VELOCITY;

		if (source == InputDevice.SOURCE_ROTARY_ENCODER)
			return mMaximumRotaryEncoderFlingVelocity;

		return mMaximumFlingVelocity;
	}

	/**
	 * Checks if any kind of scroll haptic feedback is enabled for a motion generated by a specific
	 * input device configuration and motion axis.
	 */
	public boolean isHapticScrollFeedbackEnabled(int inputDeviceId, int axis, int source) {
		if (!isInputDeviceInfoValid(inputDeviceId, axis, source))
			return false;

		if (source == InputDevice.SOURCE_ROTARY_ENCODER && axis == MotionEvent.AXIS_SCROLL) {
			return mRotaryEncoderHapticScrollFeedbackEnabled;
		}

		return false;
	}

	/**
	 * Provides the minimum scroll interval (in pixels) between consecutive scroll tick haptics for
	 * motions generated by a specific input device configuration and motion axis.
	 */
	public int getHapticScrollFeedbackTickInterval(int inputDeviceId, int axis, int source) {
		if (!mRotaryEncoderHapticScrollFeedbackEnabled) {
			return NO_HAPTIC_SCROLL_TICK_INTERVAL;
		}

		if (!isInputDeviceInfoValid(inputDeviceId, axis, source)) {
			return NO_HAPTIC_SCROLL_TICK_INTERVAL;
		}

		if (source == InputDevice.SOURCE_ROTARY_ENCODER && axis == MotionEvent.AXIS_SCROLL) {
			return mRotaryEncoderHapticScrollFeedbackTickIntervalPixels;
		}

		return NO_HAPTIC_SCROLL_TICK_INTERVAL;
	}

	/** @hide AOSP: off unless config_viewBasedRotaryEncoderHapticsEnabled and its flag; no rotary encoder here. */
	public boolean isViewBasedRotaryEncoderHapticScrollFeedbackEnabled() {
		return false;
	}

	private static boolean isInputDeviceInfoValid(int id, int axis, int source) {
		InputDevice device = InputDevice.getDevice(id);
		return device != null && device.getMotionRange(axis, source) != null;
	}

	public boolean shouldShowMenuShortcutsWhenKeyboardPresent() {
		return mShowMenuShortcutsWhenKeyboardPresent;
	}

	public int getScaledMinimumScalingSpan() {
		if (!mConstructedWithContext) {
			throw new IllegalStateException("Min scaling span cannot be determined when this "
							+ "method is called on a ViewConfiguration that was instantiated using a "
							+ "constructor with no Context parameter");
		}
		return mMinScalingSpan;
	}

	/** @hide */
	public boolean isFadingMarqueeEnabled() {
		return mFadingMarqueeEnabled;
	}

	/** @hide */
	public int getSmartSelectionInitializedTimeout() {
		return mSmartSelectionInitializedTimeout;
	}

	/** @hide */
	public int getSmartSelectionInitializingTimeout() {
		return mSmartSelectionInitializingTimeout;
	}

	/** @hide */
	public boolean isPreferKeepClearForFocusEnabled() {
		return mPreferKeepClearForFocusEnabled;
	}

	/** @hide */
	public static int getLongPressTooltipHideTimeout() {
		return LONG_PRESS_TOOLTIP_HIDE_TIMEOUT;
	}

	/** @hide */
	public static int getHoverTooltipShowTimeout() {
		return HOVER_TOOLTIP_SHOW_TIMEOUT;
	}

	/** @hide */
	public static int getHoverTooltipHideTimeout() {
		return HOVER_TOOLTIP_HIDE_TIMEOUT;
	}

	/** @hide */
	public static int getHoverTooltipHideShortTimeout() {
		return HOVER_TOOLTIP_HIDE_SHORT_TIMEOUT;
	}
}
