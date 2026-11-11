	/* teslalinux: WindowManager additions (default methods: WindowManagerImpl keeps compiling) */
	default WindowMetrics getCurrentWindowMetrics() {
		Display d = getDefaultDisplay();
		// AOSP WindowManagerImpl: the window bounds and the insets the window would get (layout:
		// no inset source on the car screen, see Window.tlWindowInsetsFor)
		android.content.Context app = android.app.ActivityThread.currentApplication();
		return new WindowMetrics(new android.graphics.Rect(0, 0, d.getWidth(), d.getHeight()),
		                         app != null ? Window.tlWindowInsetsFor(app) : new WindowInsets.Builder().build());
	}
	default WindowMetrics getMaximumWindowMetrics() { return getCurrentWindowMetrics(); }
	default boolean isCrossWindowBlurEnabled() { return false; }
	default void addCrossWindowBlurEnabledListener(java.util.function.Consumer<Boolean> listener) {}
	default void addCrossWindowBlurEnabledListener(java.util.concurrent.Executor executor, java.util.function.Consumer<Boolean> listener) {}
	default void removeCrossWindowBlurEnabledListener(java.util.function.Consumer<Boolean> listener) {}

	/** Thrown when a window token is invalid (AOSP). */
	public static class BadTokenException extends RuntimeException {
		public BadTokenException() {}
		public BadTokenException(String name) { super(name); }
	}

	/** Thrown when the display for a window is invalid (AOSP). */
	public static class InvalidDisplayException extends RuntimeException {
		public InvalidDisplayException() {}
		public InvalidDisplayException(String name) { super(name); }
	}
