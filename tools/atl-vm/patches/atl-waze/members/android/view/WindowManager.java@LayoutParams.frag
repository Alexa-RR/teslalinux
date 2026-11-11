		/* ---- teslalinux: AOSP WindowManager.LayoutParams members ATL lacked (android14-release).
		 * The constants are copied from AOSP verbatim; the fields carry AOSP's defaults; copyFrom is
		 * AOSP's for every field this class has (WindowManagerImpl applies them to its window layers). */
		public static final int FIRST_APPLICATION_WINDOW = 1;
		public static final int TYPE_BASE_APPLICATION = 1;
		public static final int TYPE_APPLICATION = 2;
		public static final int TYPE_APPLICATION_STARTING = 3;
		public static final int TYPE_DRAWN_APPLICATION = 4;
		public static final int LAST_APPLICATION_WINDOW = 99;
		public static final int FIRST_SUB_WINDOW = 1000;
		public static final int TYPE_APPLICATION_PANEL = FIRST_SUB_WINDOW;
		public static final int TYPE_APPLICATION_MEDIA = FIRST_SUB_WINDOW + 1;
		public static final int TYPE_APPLICATION_SUB_PANEL = FIRST_SUB_WINDOW + 2;
		public static final int TYPE_APPLICATION_ATTACHED_DIALOG = FIRST_SUB_WINDOW + 3;
		public static final int TYPE_APPLICATION_MEDIA_OVERLAY = FIRST_SUB_WINDOW + 4;
		public static final int TYPE_APPLICATION_ABOVE_SUB_PANEL = FIRST_SUB_WINDOW + 5;
		public static final int LAST_SUB_WINDOW = 1999;
		public static final int FIRST_SYSTEM_WINDOW = 2000;
		public static final int TYPE_STATUS_BAR = FIRST_SYSTEM_WINDOW;
		public static final int TYPE_SEARCH_BAR = FIRST_SYSTEM_WINDOW+1;
		public static final int TYPE_PHONE = FIRST_SYSTEM_WINDOW+2;
		public static final int TYPE_SYSTEM_ALERT = FIRST_SYSTEM_WINDOW+3;
		public static final int TYPE_KEYGUARD = FIRST_SYSTEM_WINDOW+4;
		public static final int TYPE_TOAST = FIRST_SYSTEM_WINDOW+5;
		public static final int TYPE_SYSTEM_OVERLAY = FIRST_SYSTEM_WINDOW+6;
		public static final int TYPE_PRIORITY_PHONE = FIRST_SYSTEM_WINDOW+7;
		public static final int TYPE_SYSTEM_DIALOG = FIRST_SYSTEM_WINDOW+8;
		public static final int TYPE_KEYGUARD_DIALOG = FIRST_SYSTEM_WINDOW+9;
		public static final int TYPE_SYSTEM_ERROR = FIRST_SYSTEM_WINDOW+10;
		public static final int TYPE_INPUT_METHOD = FIRST_SYSTEM_WINDOW+11;
		public static final int TYPE_INPUT_METHOD_DIALOG = FIRST_SYSTEM_WINDOW+12;
		public static final int TYPE_WALLPAPER = FIRST_SYSTEM_WINDOW+13;
		public static final int TYPE_STATUS_BAR_PANEL = FIRST_SYSTEM_WINDOW+14;
		public static final int TYPE_SECURE_SYSTEM_OVERLAY = FIRST_SYSTEM_WINDOW+15;
		public static final int TYPE_DRAG = FIRST_SYSTEM_WINDOW+16;
		public static final int TYPE_STATUS_BAR_SUB_PANEL = FIRST_SYSTEM_WINDOW+17;
		public static final int TYPE_POINTER = FIRST_SYSTEM_WINDOW+18;
		public static final int TYPE_NAVIGATION_BAR = FIRST_SYSTEM_WINDOW+19;
		public static final int TYPE_VOLUME_OVERLAY = FIRST_SYSTEM_WINDOW+20;
		public static final int TYPE_BOOT_PROGRESS = FIRST_SYSTEM_WINDOW+21;
		public static final int TYPE_INPUT_CONSUMER = FIRST_SYSTEM_WINDOW+22;
		public static final int TYPE_NAVIGATION_BAR_PANEL = FIRST_SYSTEM_WINDOW+24;
		public static final int TYPE_DISPLAY_OVERLAY = FIRST_SYSTEM_WINDOW+26;
		public static final int TYPE_MAGNIFICATION_OVERLAY = FIRST_SYSTEM_WINDOW+27;
		public static final int TYPE_PRIVATE_PRESENTATION = FIRST_SYSTEM_WINDOW+30;
		public static final int TYPE_VOICE_INTERACTION = FIRST_SYSTEM_WINDOW+31;
		public static final int TYPE_ACCESSIBILITY_OVERLAY = FIRST_SYSTEM_WINDOW+32;
		public static final int TYPE_VOICE_INTERACTION_STARTING = FIRST_SYSTEM_WINDOW+33;
		public static final int TYPE_DOCK_DIVIDER = FIRST_SYSTEM_WINDOW+34;
		public static final int TYPE_QS_DIALOG = FIRST_SYSTEM_WINDOW+35;
		public static final int TYPE_SCREENSHOT = FIRST_SYSTEM_WINDOW + 36;
		public static final int TYPE_PRESENTATION = FIRST_SYSTEM_WINDOW + 37;
		public static final int TYPE_APPLICATION_OVERLAY = FIRST_SYSTEM_WINDOW + 38;
		public static final int TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY = FIRST_SYSTEM_WINDOW + 39;
		public static final int TYPE_NOTIFICATION_SHADE = FIRST_SYSTEM_WINDOW + 40;
		public static final int TYPE_STATUS_BAR_ADDITIONAL = FIRST_SYSTEM_WINDOW + 41;
		public static final int LAST_SYSTEM_WINDOW = 2999;
		public static final int INVALID_WINDOW_TYPE = -1;
		public static final int MEMORY_TYPE_NORMAL = 0;
		public static final int MEMORY_TYPE_HARDWARE = 1;
		public static final int MEMORY_TYPE_GPU = 2;
		public static final int MEMORY_TYPE_PUSH_BUFFERS = 3;
		public static final int PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED = 1 << 1;
		public static final int PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS = 1 << 2;
		public static final int PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY = 1 << 3;
		public static final int SYSTEM_FLAG_SHOW_FOR_ALL_USERS = 1 << 4;
		public static final int PRIVATE_FLAG_UNRESTRICTED_GESTURE_EXCLUSION = 1 << 5;
		public static final int PRIVATE_FLAG_NO_MOVE_ANIMATION = 1 << 6;
		public static final int PRIVATE_FLAG_COMPATIBLE_WINDOW = 1 << 7;
		public static final int PRIVATE_FLAG_SYSTEM_ERROR = 1 << 8;
		public static final int PRIVATE_FLAG_OPTIMIZE_MEASURE = 1 << 9;
		public static final int PRIVATE_FLAG_DISABLE_WALLPAPER_TOUCH_EVENTS = 1 << 10;
		public static final int PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR = 1 << 11;
		public static final int PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT = 1 << 12;
		public static final int PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY = 1 << 13;
		public static final int PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME = 1 << 14;
		public static final int PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS = 1 << 15;
		public static final int PRIVATE_FLAG_SUSTAINED_PERFORMANCE_MODE = 1 << 16;
		public static final int SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS = 1 << 19;
		public static final int PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY = 1 << 20;
		public static final int PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION = 1 << 21;
		public static final int PRIVATE_FLAG_NOT_MAGNIFIABLE = 1 << 22;
		public static final int PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION = 1 << 23;
		public static final int PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC = 1 << 24;
		public static final int PRIVATE_FLAG_USE_BLAST = 1 << 25;
		public static final int PRIVATE_FLAG_APPEARANCE_CONTROLLED = 1 << 26;
		public static final int PRIVATE_FLAG_BEHAVIOR_CONTROLLED = 1 << 27;
		public static final int PRIVATE_FLAG_FIT_INSETS_CONTROLLED = 1 << 28;
		public static final int PRIVATE_FLAG_TRUSTED_OVERLAY = 1 << 29;
		public static final int PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME = 1 << 30;
		public static final int PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP = 1 << 31;
		public static final int SOFT_INPUT_MASK_STATE = 0x0f;
		public static final int SOFT_INPUT_STATE_UNSPECIFIED = 0;
		public static final int SOFT_INPUT_STATE_UNCHANGED = 1;
		public static final int SOFT_INPUT_STATE_HIDDEN = 2;
		public static final int SOFT_INPUT_STATE_ALWAYS_HIDDEN = 3;
		public static final int SOFT_INPUT_STATE_VISIBLE = 4;
		public static final int SOFT_INPUT_STATE_ALWAYS_VISIBLE = 5;
		public static final int SOFT_INPUT_MASK_ADJUST = 0xf0;
		public static final int SOFT_INPUT_ADJUST_UNSPECIFIED = 0x00;
		public static final int SOFT_INPUT_ADJUST_RESIZE = 0x10;
		public static final int SOFT_INPUT_ADJUST_PAN = 0x20;
		public static final int SOFT_INPUT_ADJUST_NOTHING = 0x30;
		public static final int SOFT_INPUT_IS_FORWARD_NAVIGATION = 0x100;
		public static final int ROTATION_ANIMATION_UNSPECIFIED = -1;
		public static final int ROTATION_ANIMATION_ROTATE = 0;
		public static final int ROTATION_ANIMATION_CROSSFADE = 1;
		public static final int ROTATION_ANIMATION_JUMPCUT = 2;
		public static final int ROTATION_ANIMATION_SEAMLESS = 3;
		public static final int DISPLAY_FLAG_DISABLE_HDR_CONVERSION = 1 << 0;
		public static final int LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT = 0;
		public static final int LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES = 1;
		public static final int LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER = 2;
		public static final int LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3;
		public static final int INPUT_FEATURE_NO_INPUT_CHANNEL = 1 << 0;
		public static final int INPUT_FEATURE_DISABLE_USER_ACTIVITY = 1 << 1;
		public static final int INPUT_FEATURE_SPY = 1 << 2;
		public static final int LAYOUT_CHANGED = 1<<0;
		public static final int TYPE_CHANGED = 1<<1;
		public static final int FLAGS_CHANGED = 1<<2;
		public static final int FORMAT_CHANGED = 1<<3;
		public static final int ANIMATION_CHANGED = 1<<4;
		public static final int DIM_AMOUNT_CHANGED = 1<<5;
		public static final int TITLE_CHANGED = 1<<6;
		public static final int ALPHA_CHANGED = 1<<7;
		public static final int MEMORY_TYPE_CHANGED = 1<<8;
		public static final int SOFT_INPUT_MODE_CHANGED = 1<<9;
		public static final int SCREEN_ORIENTATION_CHANGED = 1<<10;
		public static final int SCREEN_BRIGHTNESS_CHANGED = 1<<11;
		public static final int ROTATION_ANIMATION_CHANGED = 1<<12;
		public static final int BUTTON_BRIGHTNESS_CHANGED = 1<<13;
		public static final int SYSTEM_UI_VISIBILITY_CHANGED = 1<<14;
		public static final int SYSTEM_UI_LISTENER_CHANGED = 1<<15;
		public static final int INPUT_FEATURES_CHANGED = 1<<16;
		public static final int PRIVATE_FLAGS_CHANGED = 1<<17;
		public static final int USER_ACTIVITY_TIMEOUT_CHANGED = 1<<18;
		public static final int TRANSLUCENT_FLAGS_CHANGED = 1<<19;
		public static final int SURFACE_INSETS_CHANGED = 1<<20;
		public static final int PREFERRED_REFRESH_RATE_CHANGED = 1 << 21;
		public static final int DISPLAY_FLAGS_CHANGED = 1 << 22;
		public static final int PREFERRED_DISPLAY_MODE_ID = 1 << 23;
		public static final int ACCESSIBILITY_ANCHOR_CHANGED = 1 << 24;
		public static final int ACCESSIBILITY_TITLE_CHANGED = 1 << 25;
		public static final int COLOR_MODE_CHANGED = 1 << 26;
		public static final int INSET_FLAGS_CHANGED = 1 << 27;
		public static final int MINIMAL_POST_PROCESSING_PREFERENCE_CHANGED = 1 << 28;
		public static final int BLUR_BEHIND_RADIUS_CHANGED = 1 << 29;
		public static final int PREFERRED_MIN_DISPLAY_REFRESH_RATE = 1 << 30;
		public static final int PREFERRED_MAX_DISPLAY_REFRESH_RATE = 1 << 31;

		public static final float BRIGHTNESS_OVERRIDE_NONE = -1.0f;
		public static final float BRIGHTNESS_OVERRIDE_OFF = 0.0f;
		public static final float BRIGHTNESS_OVERRIDE_FULL = 1.0f;
		public float horizontalWeight;
		public float verticalWeight;
		public int memoryType;
		public int privateFlags;
		public float horizontalMargin;
		public float verticalMargin;
		public final android.graphics.Rect surfaceInsets = new android.graphics.Rect();
		public boolean hasManualSurfaceInsets;
		public boolean receiveInsetsIgnoringZOrder;
		public boolean preservePreviousSurfaceInsets = true;
		public float buttonBrightness = BRIGHTNESS_OVERRIDE_NONE;
		public int rotationAnimation = ROTATION_ANIMATION_ROTATE;
		public int screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
		public float preferredMinDisplayRefreshRate;
		public float preferredMaxDisplayRefreshRate;
		public int systemUiVisibility;
		public int subtreeSystemUiVisibility;
		public boolean hasSystemUiListeners;
		public int inputFeatures;
		public long userActivityTimeout = -1;
		public CharSequence accessibilityTitle;
		public long accessibilityIdOfAnchor = android.view.accessibility.AccessibilityNodeInfo.UNDEFINED_NODE_ID;
		public long hideTimeoutMilliseconds = -1;
		public boolean preferMinimalPostProcessing;
		public int preferredDisplayModeId;
		public float preferredRefreshRate;
		public int blurBehindRadius;
		public float dimAmount = 1.0f;
		public int gravity;
		private CharSequence mTitle;
		private int mColorMode = 0; /* AOSP ActivityInfo.COLOR_MODE_DEFAULT */
		private int mFitInsetsTypes = WindowInsets.Type.systemBars();
		private int mFitInsetsSides = WindowInsets.Side.all();
		private boolean mFitInsetsIgnoringVisibility;

		public LayoutParams(int _type) {
			super(MATCH_PARENT, MATCH_PARENT);
			type = _type;
			format = android.graphics.PixelFormat.OPAQUE;
		}

		public LayoutParams(int _type, int _flags) {
			super(MATCH_PARENT, MATCH_PARENT);
			type = _type;
			flags = _flags;
			format = android.graphics.PixelFormat.OPAQUE;
		}

		public LayoutParams(int _type, int _flags, int _format) {
			super(MATCH_PARENT, MATCH_PARENT);
			type = _type;
			flags = _flags;
			format = _format;
		}

		public final CharSequence getTitle() {
			return mTitle != null ? mTitle : "";
		}

		/**
		 * AOSP (hidden): room around the surface for the view's shadow (its Z twice), never shrinking
		 * an inset that is already larger.
		 */
		public void setSurfaceInsets(View view, boolean manual, boolean preservePrevious) {
			final int surfaceInset = (int) Math.ceil(view.getZ() * 2);
			if (surfaceInset == 0) {
				surfaceInsets.set(0, 0, 0, 0);
			} else {
				surfaceInsets.set(Math.max(surfaceInset, surfaceInsets.left), Math.max(surfaceInset, surfaceInsets.top),
				                  Math.max(surfaceInset, surfaceInsets.right), Math.max(surfaceInset, surfaceInsets.bottom));
			}
			hasManualSurfaceInsets = manual;
			preservePreviousSurfaceInsets = preservePrevious;
		}

		public void setBlurBehindRadius(int r) { blurBehindRadius = r; }
		public int getBlurBehindRadius() { return blurBehindRadius; }
		public void setFitInsetsTypes(int types) { mFitInsetsTypes = types; privateFlags |= PRIVATE_FLAG_FIT_INSETS_CONTROLLED; }
		public void setFitInsetsSides(int sides) { mFitInsetsSides = sides; privateFlags |= PRIVATE_FLAG_FIT_INSETS_CONTROLLED; }
		public void setFitInsetsIgnoringVisibility(boolean ignore) { mFitInsetsIgnoringVisibility = ignore; privateFlags |= PRIVATE_FLAG_FIT_INSETS_CONTROLLED; }
		public int getFitInsetsTypes() { return mFitInsetsTypes; }
		public int getFitInsetsSides() { return mFitInsetsSides; }
		public boolean isFitInsetsIgnoringVisibility() { return mFitInsetsIgnoringVisibility; }
		public void setColorMode(int colorMode) { mColorMode = colorMode; }
		public int getColorMode() { return mColorMode; }

		/** AOSP: copies the attributes of o into this one; returns which groups of attributes changed. */
		public final int copyFrom(LayoutParams o) {
			int changes = 0;
			if (width != o.width) { width = o.width; changes |= LAYOUT_CHANGED; }
			if (height != o.height) { height = o.height; changes |= LAYOUT_CHANGED; }
			if (x != o.x) { x = o.x; changes |= LAYOUT_CHANGED; }
			if (y != o.y) { y = o.y; changes |= LAYOUT_CHANGED; }
			if (horizontalWeight != o.horizontalWeight) { horizontalWeight = o.horizontalWeight; changes |= LAYOUT_CHANGED; }
			if (verticalWeight != o.verticalWeight) { verticalWeight = o.verticalWeight; changes |= LAYOUT_CHANGED; }
			if (horizontalMargin != o.horizontalMargin) { horizontalMargin = o.horizontalMargin; changes |= LAYOUT_CHANGED; }
			if (verticalMargin != o.verticalMargin) { verticalMargin = o.verticalMargin; changes |= LAYOUT_CHANGED; }
			if (type != o.type) { type = o.type; changes |= TYPE_CHANGED; }
			if (flags != o.flags) {
				final int diff = flags ^ o.flags;
				if ((diff & (FLAG_TRANSLUCENT_STATUS | FLAG_TRANSLUCENT_NAVIGATION)) != 0)
					changes |= TRANSLUCENT_FLAGS_CHANGED;
				flags = o.flags;
				changes |= FLAGS_CHANGED;
			}
			if (privateFlags != o.privateFlags) { privateFlags = o.privateFlags; changes |= PRIVATE_FLAGS_CHANGED; }
			if (softInputMode != o.softInputMode) { softInputMode = o.softInputMode; changes |= SOFT_INPUT_MODE_CHANGED; }
			if (layoutInDisplayCutoutMode != o.layoutInDisplayCutoutMode) { layoutInDisplayCutoutMode = o.layoutInDisplayCutoutMode; changes |= LAYOUT_CHANGED; }
			if (gravity != o.gravity) { gravity = o.gravity; changes |= LAYOUT_CHANGED; }
			if (format != o.format) { format = o.format; changes |= FORMAT_CHANGED; }
			if (windowAnimations != o.windowAnimations) { windowAnimations = o.windowAnimations; changes |= ANIMATION_CHANGED; }
			if (token == null)
				token = o.token; /* only copied if the recipient has none */
			if (packageName == null)
				packageName = o.packageName; /* only copied if the recipient has none */
			if (!java.util.Objects.equals(mTitle, o.mTitle) && o.mTitle != null) { mTitle = o.mTitle; changes |= TITLE_CHANGED; }
			if (alpha != o.alpha) { alpha = o.alpha; changes |= ALPHA_CHANGED; }
			if (dimAmount != o.dimAmount) { dimAmount = o.dimAmount; changes |= DIM_AMOUNT_CHANGED; }
			if (screenBrightness != o.screenBrightness) { screenBrightness = o.screenBrightness; changes |= SCREEN_BRIGHTNESS_CHANGED; }
			if (buttonBrightness != o.buttonBrightness) { buttonBrightness = o.buttonBrightness; changes |= BUTTON_BRIGHTNESS_CHANGED; }
			if (rotationAnimation != o.rotationAnimation) { rotationAnimation = o.rotationAnimation; changes |= ROTATION_ANIMATION_CHANGED; }
			if (screenOrientation != o.screenOrientation) { screenOrientation = o.screenOrientation; changes |= SCREEN_ORIENTATION_CHANGED; }
			if (preferredRefreshRate != o.preferredRefreshRate) { preferredRefreshRate = o.preferredRefreshRate; changes |= PREFERRED_REFRESH_RATE_CHANGED; }
			if (preferredDisplayModeId != o.preferredDisplayModeId) { preferredDisplayModeId = o.preferredDisplayModeId; changes |= PREFERRED_DISPLAY_MODE_ID; }
			if (preferredMinDisplayRefreshRate != o.preferredMinDisplayRefreshRate) { preferredMinDisplayRefreshRate = o.preferredMinDisplayRefreshRate; changes |= PREFERRED_MIN_DISPLAY_REFRESH_RATE; }
			if (preferredMaxDisplayRefreshRate != o.preferredMaxDisplayRefreshRate) { preferredMaxDisplayRefreshRate = o.preferredMaxDisplayRefreshRate; changes |= PREFERRED_MAX_DISPLAY_REFRESH_RATE; }
			if (systemUiVisibility != o.systemUiVisibility || subtreeSystemUiVisibility != o.subtreeSystemUiVisibility) {
				systemUiVisibility = o.systemUiVisibility;
				subtreeSystemUiVisibility = o.subtreeSystemUiVisibility;
				changes |= SYSTEM_UI_VISIBILITY_CHANGED;
			}
			if (hasSystemUiListeners != o.hasSystemUiListeners) { hasSystemUiListeners = o.hasSystemUiListeners; changes |= SYSTEM_UI_LISTENER_CHANGED; }
			if (inputFeatures != o.inputFeatures) { inputFeatures = o.inputFeatures; changes |= INPUT_FEATURES_CHANGED; }
			if (userActivityTimeout != o.userActivityTimeout) { userActivityTimeout = o.userActivityTimeout; changes |= USER_ACTIVITY_TIMEOUT_CHANGED; }
			if (!surfaceInsets.equals(o.surfaceInsets)) { surfaceInsets.set(o.surfaceInsets); changes |= SURFACE_INSETS_CHANGED; }
			if (hasManualSurfaceInsets != o.hasManualSurfaceInsets) { hasManualSurfaceInsets = o.hasManualSurfaceInsets; changes |= SURFACE_INSETS_CHANGED; }
			if (receiveInsetsIgnoringZOrder != o.receiveInsetsIgnoringZOrder) { receiveInsetsIgnoringZOrder = o.receiveInsetsIgnoringZOrder; changes |= SURFACE_INSETS_CHANGED; }
			if (preservePreviousSurfaceInsets != o.preservePreviousSurfaceInsets) { preservePreviousSurfaceInsets = o.preservePreviousSurfaceInsets; changes |= SURFACE_INSETS_CHANGED; }
			if (accessibilityIdOfAnchor != o.accessibilityIdOfAnchor) { accessibilityIdOfAnchor = o.accessibilityIdOfAnchor; changes |= ACCESSIBILITY_ANCHOR_CHANGED; }
			if (!java.util.Objects.equals(accessibilityTitle, o.accessibilityTitle) && o.accessibilityTitle != null) { accessibilityTitle = o.accessibilityTitle; changes |= ACCESSIBILITY_TITLE_CHANGED; }
			if (mColorMode != o.mColorMode) { mColorMode = o.mColorMode; changes |= COLOR_MODE_CHANGED; }
			if (preferMinimalPostProcessing != o.preferMinimalPostProcessing) { preferMinimalPostProcessing = o.preferMinimalPostProcessing; changes |= MINIMAL_POST_PROCESSING_PREFERENCE_CHANGED; }
			if (blurBehindRadius != o.blurBehindRadius) { blurBehindRadius = o.blurBehindRadius; changes |= BLUR_BEHIND_RADIUS_CHANGED; }
			hideTimeoutMilliseconds = o.hideTimeoutMilliseconds; /* set at window creation only */
			if (mFitInsetsTypes != o.mFitInsetsTypes) { mFitInsetsTypes = o.mFitInsetsTypes; changes |= LAYOUT_CHANGED; }
			if (mFitInsetsSides != o.mFitInsetsSides) { mFitInsetsSides = o.mFitInsetsSides; changes |= LAYOUT_CHANGED; }
			if (mFitInsetsIgnoringVisibility != o.mFitInsetsIgnoringVisibility) { mFitInsetsIgnoringVisibility = o.mFitInsetsIgnoringVisibility; changes |= LAYOUT_CHANGED; }
			return changes;
		}
