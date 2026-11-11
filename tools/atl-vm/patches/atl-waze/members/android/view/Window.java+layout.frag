	/* ---- teslalinux (layout): AOSP Window features, attributes, insets policy, frame metrics ----
	 * Copyright (C) 2006 The Android Open Source Project, Apache License 2.0 (logic ported from
	 * frameworks/base core/java/android/view/Window.java and PhoneWindow.java). */

	public static final int FEATURE_PROGRESS = 2;
	public static final int FEATURE_LEFT_ICON = 3;
	public static final int FEATURE_RIGHT_ICON = 4;
	public static final int FEATURE_INDETERMINATE_PROGRESS = 5;
	public static final int FEATURE_CONTEXT_MENU = 6;
	public static final int FEATURE_CUSTOM_TITLE = 7;
	public static final int FEATURE_ACTION_BAR = 8;
	public static final int FEATURE_ACTION_BAR_OVERLAY = 9;
	public static final int FEATURE_ACTION_MODE_OVERLAY = 10;
	public static final int FEATURE_SWIPE_TO_DISMISS = 11;
	public static final int FEATURE_CONTENT_TRANSITIONS = 12;
	public static final int FEATURE_ACTIVITY_TRANSITIONS = 13;
	/** @hide */
	public static final int FEATURE_MAX = FEATURE_ACTIVITY_TRANSITIONS;
	public static final int ID_ANDROID_CONTENT = android.R.id.content;
	/** AOSP PhoneWindow: the features a custom title can be combined with */
	private static final int CUSTOM_TITLE_COMPATIBLE_FEATURES = (1 << FEATURE_CUSTOM_TITLE) | (1 << FEATURE_CONTENT_TRANSITIONS) | (1 << FEATURE_ACTIVITY_TRANSITIONS) | (1 << FEATURE_ACTION_MODE_OVERLAY);

	/** AOSP Window.mFeatures / mLocalFeatures: from getDefaultFeatures, then requestFeature */
	private int tlFeatures = -1;
	private int tlLocalFeatures;
	/** AOSP PhoneWindow.mContentParentExplicitlySet */
	private boolean tlContentParentExplicitlySet;
	/** AOSP PhoneWindow.mDecorFitsSystemWindows (the content insets policy of the window) */
	private boolean tlDecorFitsSystemWindows = true;
	/** AOSP Window.mHaveDimAmount / mHaveWindowAnimations-style bookkeeping */
	private boolean tlHaveDimAmount;
	/** AOSP PhoneWindow.mBackgroundBlurRadius (the requested one) */
	private int tlBackgroundBlurRadius;
	private boolean tlStatusBarContrastEnforced = true;
	private boolean tlNavigationBarContrastEnforced = true;
	private final java.util.ArrayList<OnFrameMetricsAvailableListener> tlFrameMetricsListeners = new java.util.ArrayList<>();

	/** AOSP: the features every window of the app starts with (both configs are on for phones) */
	public static int getDefaultFeatures(Context context) {
		return (1 << FEATURE_OPTIONS_PANEL) | (1 << FEATURE_CONTEXT_MENU);
	}

	private void tlEnsureFeatures() {
		if (tlFeatures == -1) {
			tlFeatures = tlLocalFeatures = getDefaultFeatures(context);
		}
	}

	/**
	 * AOSP PhoneWindow.requestFeature: features are requested before the content is set; no title
	 * dominates the action bar, a custom title combines with few features.
	 */
	public boolean requestFeature(int featureId) {
		if (tlContentParentExplicitlySet) {
			throw new android.util.AndroidRuntimeException("requestFeature() must be called before adding content");
		}
		tlEnsureFeatures();
		final int features = getFeatures();
		final int newFeatures = features | (1 << featureId);
		if ((newFeatures & (1 << FEATURE_CUSTOM_TITLE)) != 0 && (newFeatures & ~CUSTOM_TITLE_COMPATIBLE_FEATURES) != 0) {
			// Another feature is enabled and the user is trying to enable the custom title feature
			// or custom title feature is enabled and the user is trying to enable another feature
			throw new android.util.AndroidRuntimeException("You cannot combine custom titles with other title features");
		}
		if ((features & (1 << FEATURE_NO_TITLE)) != 0 && featureId == FEATURE_ACTION_BAR) {
			return false; // Ignore. No title dominates.
		}
		if ((features & (1 << FEATURE_ACTION_BAR)) != 0 && featureId == FEATURE_NO_TITLE) {
			// Remove the action bar feature if we have no title. No title dominates.
			removeFeature(FEATURE_ACTION_BAR);
		}
		final int flag = 1 << featureId;
		tlFeatures |= flag;
		tlLocalFeatures |= flag;
		return (tlFeatures & flag) != 0;
	}

	/** AOSP */
	protected void removeFeature(int featureId) {
		tlEnsureFeatures();
		final int flag = 1 << featureId;
		tlFeatures &= ~flag;
		tlLocalFeatures &= ~flag;
	}

	/** AOSP: the enabled features (FEATURE_* bits) */
	protected final int getFeatures() {
		tlEnsureFeatures();
		return tlFeatures;
	}

	/** AOSP: the features requested on this window itself */
	protected final int getLocalFeatures() {
		tlEnsureFeatures();
		return tlLocalFeatures;
	}

	public boolean hasFeature(int feature) {
		return (getFeatures() & (1 << feature)) != 0;
	}

	/** @hide AOSP: the features any window may request (all of them) */
	public static int getAvailableFeatures() {
		return (1 << (FEATURE_MAX + 1)) - 1;
	}

	/** AOSP: the attributes changed; the callback (the activity or dialog) is told */
	protected void dispatchWindowAttributesChanged(WindowManager.LayoutParams attrs) {
		if (getCallback() != null)
			getCallback().onWindowAttributesChanged(attrs);
	}

	/** AOSP: how much the content behind the window is dimmed (with FLAG_DIM_BEHIND) */
	public void setDimAmount(float amount) {
		final WindowManager.LayoutParams attrs = getAttributes();
		attrs.dimAmount = amount;
		tlHaveDimAmount = true;
		dispatchWindowAttributesChanged(attrs);
	}

	/** @hide AOSP: back to the theme's dim amount */
	public void clearDimAmount() {
		tlHaveDimAmount = false;
	}

	/** @hide AOSP */
	public boolean tlHaveDimAmount() {
		return tlHaveDimAmount;
	}

	/** AOSP: the window's enter/exit animation style */
	public void setWindowAnimations(int resId) {
		final WindowManager.LayoutParams attrs = getAttributes();
		attrs.windowAnimations = resId;
		dispatchWindowAttributesChanged(attrs);
	}

	/** AOSP: where a window smaller than the screen sits */
	public void setGravity(int gravity) {
		final WindowManager.LayoutParams attrs = getAttributes();
		attrs.gravity = gravity;
		dispatchWindowAttributesChanged(attrs);
	}

	/**
	 * AOSP PhoneWindow: the blur radius behind the window's background. DecorView applies it only
	 * while cross-window blur is enabled, which it never is here (WindowManager
	 * isCrossWindowBlurEnabled: no compositor blur), so the requested radius is kept and the
	 * applied one stays 0, as on a device without blur support.
	 */
	public void setBackgroundBlurRadius(int blurRadius) {
		tlBackgroundBlurRadius = blurRadius;
	}

	/** @hide the requested background blur radius */
	public int tlGetBackgroundBlurRadius() {
		return tlBackgroundBlurRadius;
	}

	/**
	 * AOSP: whether the framework fits the content to the system bars (true) or the app handles
	 * the insets itself (false, edge to edge); the views see the change through a new insets
	 * dispatch.
	 */
	public void setDecorFitsSystemWindows(boolean decorFitsSystemWindows) {
		if (tlDecorFitsSystemWindows == decorFitsSystemWindows)
			return;
		tlDecorFitsSystemWindows = decorFitsSystemWindows;
		getDecorView().requestApplyInsets();
	}

	/** @hide the window's content insets policy (AOSP ViewRootImpl content insets listener set) */
	public boolean tlDecorFitsSystemWindows() {
		return tlDecorFitsSystemWindows;
	}

	/**
	 * @hide The insets of this window: the car screen has no status bar, navigation bar, caption
	 * bar, gesture area or cutout, so every type is zero and none is visible (AOSP InsetsState of
	 * a window without inset sources); the display shape is the screen's rectangle.
	 */
	public WindowInsets tlGetWindowInsets() {
		return tlWindowInsetsFor(context);
	}

	/** @hide the window insets of a root without a Window (popups, overlays) */
	public static WindowInsets tlWindowInsetsFor(Context context) {
		final android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
		final android.graphics.Insets[] typeInsets = new android.graphics.Insets[WindowInsets.Type.SIZE];
		final android.graphics.Insets[] typeMaxInsets = new android.graphics.Insets[WindowInsets.Type.SIZE];
		java.util.Arrays.fill(typeInsets, android.graphics.Insets.NONE);
		java.util.Arrays.fill(typeMaxInsets, android.graphics.Insets.NONE);
		return new WindowInsets(typeInsets, typeMaxInsets, new boolean[WindowInsets.Type.SIZE],
		                        (context.getResources().getConfiguration().screenLayout & 0x300) == 0x200 /* SCREENLAYOUT_ROUND_YES */, false, 0,
		                        DisplayCutout.NO_CUTOUT, RoundedCorners.NO_ROUNDED_CORNERS, new PrivacyIndicatorBounds(),
		                        DisplayShape.createDefaultDisplayShape(dm.widthPixels, dm.heightPixels, false),
		                        WindowInsets.Type.systemBars(), false);
	}

	/**
	 * AOSP: the listener gets the timing of every frame the window's HWUI renderer draws. This
	 * window is drawn by GTK, not by a ThreadedRenderer; AOSP keeps the listeners of such a window
	 * (a software-rendered one) registered without frames to report, which is the case here.
	 */
	public void addOnFrameMetricsAvailableListener(OnFrameMetricsAvailableListener listener, android.os.Handler handler) {
		final View decorView = getDecorView();
		if (decorView == null)
			throw new IllegalStateException("can't observe a Window without an attached view");
		if (listener == null)
			throw new NullPointerException("listener cannot be null");
		synchronized (tlFrameMetricsListeners) {
			tlFrameMetricsListeners.add(listener);
		}
	}

	public void removeOnFrameMetricsAvailableListener(OnFrameMetricsAvailableListener listener) {
		synchronized (tlFrameMetricsListeners) {
			if (!tlFrameMetricsListeners.remove(listener))
				throw new IllegalArgumentException("attempt to remove OnFrameMetricsAvailableListener that was never added");
		}
	}

	/**
	 * AOSP: whether the system draws a scrim behind a transparent status bar. There is no status
	 * bar on the car screen; the setting is kept for the app as AOSP keeps it.
	 */
	public void setStatusBarContrastEnforced(boolean ensureContrast) {
		tlStatusBarContrastEnforced = ensureContrast;
	}

	public boolean isStatusBarContrastEnforced() {
		return tlStatusBarContrastEnforced;
	}

	/** AOSP: as setStatusBarContrastEnforced, for the (absent) navigation bar */
	public void setNavigationBarContrastEnforced(boolean enforceContrast) {
		tlNavigationBarContrastEnforced = enforceContrast;
	}

	public boolean isNavigationBarContrastEnforced() {
		return tlNavigationBarContrastEnforced;
	}

	/** AOSP PhoneWindow.superDispatchKeyEvent: the decor view (and so the focused view) gets the key */
	public boolean superDispatchKeyEvent(KeyEvent event) {
		return decorView.dispatchKeyEvent(event);
	}

	/** AOSP PhoneWindow: the decor view gets the shortcut */
	public boolean superDispatchKeyShortcutEvent(KeyEvent event) {
		return decorView.dispatchKeyShortcutEvent(event);
	}

	/** AOSP PhoneWindow: the decor view dispatches the touch to the view tree */
	public boolean superDispatchTouchEvent(MotionEvent event) {
		return decorView.dispatchTouchEvent(event);
	}

	/** AOSP PhoneWindow */
	public boolean superDispatchTrackballEvent(MotionEvent event) {
		return decorView.dispatchTrackballEvent(event);
	}

	/** AOSP PhoneWindow */
	public boolean superDispatchGenericMotionEvent(MotionEvent event) {
		return decorView.dispatchGenericMotionEvent(event);
	}
