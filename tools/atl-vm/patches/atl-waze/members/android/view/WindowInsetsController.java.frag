	/* teslalinux: API 30+ surface AndroidX touches, as defaults so InsetsController keeps compiling */
	int APPEARANCE_LOW_PROFILE_BARS = 1, APPEARANCE_LIGHT_STATUS_BARS = 1 << 3, APPEARANCE_LIGHT_NAVIGATION_BARS = 1 << 4, APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND = 1 << 7;
	int BEHAVIOR_SHOW_BARS_BY_TOUCH = 0, BEHAVIOR_DEFAULT = 1, BEHAVIOR_SHOW_BARS_BY_SWIPE = 1, BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2;
	interface OnControllableInsetsChangedListener { void onControllableInsetsChanged(WindowInsetsController controller, int typeMask); }
	default int getSystemBarsAppearance() { return 0; }
	default int getSystemBarsBehavior() { return BEHAVIOR_DEFAULT; }
	default void addOnControllableInsetsChangedListener(OnControllableInsetsChangedListener listener) {}
	default void removeOnControllableInsetsChangedListener(OnControllableInsetsChangedListener listener) {}
	default void setSystemBarsAppearance(int appearance, int mask, boolean animate) { setSystemBarsAppearance(appearance, mask); }
	default void controlWindowInsetsAnimation(int types, long durationMillis, android.view.animation.Interpolator interpolator, android.os.CancellationSignal cancellationSignal, Object listener) {}
	default void setCaptionBarBackgroundBlurRadius(int radius) {}
