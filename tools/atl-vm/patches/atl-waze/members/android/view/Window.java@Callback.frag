		/* teslalinux: AOSP Window.Callback members ATL lacked; defaults so ATL's own implementers keep compiling. */
		default boolean dispatchKeyEvent(KeyEvent event) { return false; }
		default boolean dispatchKeyShortcutEvent(KeyEvent event) { return false; }
		default boolean dispatchTouchEvent(MotionEvent event) { return false; }
		default boolean dispatchTrackballEvent(MotionEvent event) { return false; }
		default boolean dispatchGenericMotionEvent(MotionEvent event) { return false; }
		default void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {}
		default void onAttachedToWindow() {}
		default void onDetachedFromWindow() {}
		default void onWindowFocusChanged(boolean hasFocus) {}
		default boolean onSearchRequested() { return false; }
		default ActionMode onWindowStartingActionMode(ActionMode.Callback callback) { return null; }
		default ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) { return null; }
		default void onActionModeStarted(ActionMode mode) {}
		default void onActionModeFinished(ActionMode mode) {}
		default void onPointerCaptureChanged(boolean hasCapture) {}
