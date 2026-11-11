	/* ---- teslalinux (layout): AOSP window insets dispatch, fitsSystemWindows, requestApplyInsets ----
	 * Copyright (C) 2006 The Android Open Source Project, Apache License 2.0 (logic ported from
	 * frameworks/base core/java/android/view/View.java and ViewRootImpl.java, android14-release).
	 * The window's insets come from Window.tlGetWindowInsets: the car screen has no status bar,
	 * navigation bar, caption bar or display cutout, so every inset type is zero and not visible,
	 * which is what AOSP reports for a window without inset sources. The dispatch itself is AOSP's:
	 * the window root gets the insets when it is attached and whenever a view requests them again,
	 * fitsSystemWindows views take the system window insets as padding, listeners and
	 * onApplyWindowInsets overrides see every dispatch. */

	/** AOSP FITS_SYSTEM_WINDOWS (android:fitsSystemWindows) */
	private boolean tlFitsSystemWindows;
	/** AOSP OPTIONAL_FITS_SYSTEM_WINDOWS */
	private boolean tlOptionalFitsSystemWindows;
	/** AOSP PFLAG4_FRAMEWORK_OPTIONAL_FITS_SYSTEM_WINDOWS */
	private boolean tlFrameworkOptionalFitsSystemWindows;
	/** AOSP PFLAG3_APPLYING_INSETS / PFLAG3_FITTING_SYSTEM_WINDOWS */
	private boolean tlApplyingInsets;
	private boolean tlFittingSystemWindows;
	private View.OnApplyWindowInsetsListener tlOnApplyWindowInsetsListener;
	/** a dispatch from this window root is posted (ViewRootImpl.mApplyInsetsRequested) */
	private boolean tlApplyInsetsRequested;

	/**
	 * AOSP: whether the default fitSystemWindows/onApplyWindowInsets applies the system window
	 * insets to this view as padding.
	 */
	public void setFitsSystemWindows(boolean fitSystemWindows) {
		tlFitsSystemWindows = fitSystemWindows;
	}

	public boolean getFitsSystemWindows() {
		return tlFitsSystemWindows;
	}

	/** @hide AOSP */
	public boolean fitsSystemWindows() {
		return getFitsSystemWindows();
	}

	/** AOSP: the listener is called instead of onApplyWindowInsets */
	public void setOnApplyWindowInsetsListener(View.OnApplyWindowInsetsListener listener) {
		tlOnApplyWindowInsetsListener = listener;
	}

	/**
	 * AOSP: asks for a new dispatch of the window insets. The request travels to the window root
	 * (ViewRootImpl), which dispatches on its next traversal; here the root posts the dispatch, so
	 * the requests of one pass are served by a single dispatch.
	 */
	@Deprecated
	public void requestFitSystemWindows() {
		getRootView().tlScheduleApplyInsets();
	}

	public void requestApplyInsets() {
		requestFitSystemWindows();
	}

	/** AOSP ViewRootImpl.dispatchApplyInsets, scheduled for this window root */
	void tlScheduleApplyInsets() {
		if (tlApplyInsetsRequested)
			return;
		tlApplyInsetsRequested = true;
		post(new Runnable() {
			@Override
			public void run() {
				tlApplyInsetsRequested = false;
				// the root of a window: no parent view (a view moved under another root meanwhile
				// has that root dispatch to it)
				if (!(parent instanceof View) && isAttachedToWindow())
					dispatchApplyWindowInsets(getRootWindowInsets());
			}
		});
	}

	/** @hide AOSP: the framework applies the content insets policy of the window to this view */
	public void makeOptionalFitsSystemWindows() {
		tlOptionalFitsSystemWindows = true;
	}

	/** @hide AOSP */
	public void makeFrameworkOptionalFitsSystemWindows() {
		tlFrameworkOptionalFitsSystemWindows = true;
	}

	/** @hide AOSP */
	public boolean isFrameworkOptionalFitsSystemWindows() {
		return tlFrameworkOptionalFitsSystemWindows;
	}

	/** AOSP: the legacy insets entry point; it runs the newer dispatch unless called from it */
	@Deprecated
	protected boolean fitSystemWindows(Rect insets) {
		if (!tlApplyingInsets) {
			if (insets == null) {
				// Null insets by definition have already been consumed.
				return false;
			}
			// not dispatching the newer apply insets call: dispatch into it
			try {
				tlFittingSystemWindows = true;
				return dispatchApplyWindowInsets(new WindowInsets(insets)).isConsumed();
			} finally {
				tlFittingSystemWindows = false;
			}
		} else {
			// called from the newer apply insets path: the standard fallback behaviour
			return tlFitSystemWindowsInt(insets);
		}
	}

	private boolean tlFitSystemWindowsInt(Rect insets) {
		if (tlFitsSystemWindows) {
			Rect localInsets = new Rect();
			boolean res = computeFitSystemWindows(insets, localInsets);
			tlApplyInsets(localInsets);
			return res;
		}
		return false;
	}

	/** AOSP applyInsets/internalSetPadding: the insets become the view's padding (a layout only when it changes) */
	private void tlApplyInsets(Rect insets) {
		if (paddingLeft != insets.left || paddingTop != insets.top || paddingRight != insets.right || paddingBottom != insets.bottom)
			setPadding(insets.left, insets.top, insets.right, insets.bottom);
	}

	/** AOSP: the part of the insets this view applies, and whether the system window insets got consumed */
	protected boolean computeFitSystemWindows(Rect inoutInsets, Rect outLocalInsets) {
		WindowInsets innerInsets = computeSystemWindowInsets(new WindowInsets(inoutInsets), outLocalInsets);
		inoutInsets.set(innerInsets.getSystemWindowInsetsAsRect());
		return innerInsets.isSystemWindowInsetsConsumed();
	}

	/**
	 * AOSP: the insets this view applies (outLocalInsets) and what stays for its children. A view
	 * the framework fits optionally follows the window's content policy (Window
	 * setDecorFitsSystemWindows): fitting, it takes the system window insets; otherwise the app
	 * handles them itself.
	 */
	public WindowInsets computeSystemWindowInsets(WindowInsets in, Rect outLocalInsets) {
		final boolean isOptionalFitSystemWindows = tlOptionalFitsSystemWindows || tlFrameworkOptionalFitsSystemWindows;
		if (isOptionalFitSystemWindows && isAttachedToWindow()) {
			final Window window = native_get_window(widget);
			if (window == null || !window.tlDecorFitsSystemWindows()) {
				// The application wants to take care of fitting system window for the content.
				outLocalInsets.setEmpty();
				return in;
			}
			// AOSP ViewRootImpl sDefaultContentInsetsApplier
			final android.graphics.Insets insetsToApply = in.getSystemWindowInsets();
			outLocalInsets.set(insetsToApply.left, insetsToApply.top, insetsToApply.right, insetsToApply.bottom);
			return in.inset(insetsToApply).consumeSystemWindowInsets();
		} else {
			outLocalInsets.set(in.getSystemWindowInsetsAsRect());
			return in.consumeSystemWindowInsets().inset(outLocalInsets);
		}
	}

	/** AOSP: by default a fitsSystemWindows view takes the system window insets as its padding */
	public WindowInsets onApplyWindowInsets(WindowInsets insets) {
		if (tlFrameworkOptionalFitsSystemWindows && tlFitsSystemWindows) {
			Rect localInsets = new Rect();
			WindowInsets result = computeSystemWindowInsets(insets, localInsets);
			tlApplyInsets(localInsets);
			return result;
		}
		if (!tlFittingSystemWindows) {
			// not called from within a direct call to fitSystemWindows: call into it as a
			// fallback, in case this is a class that overrides it
			if (fitSystemWindows(insets.getSystemWindowInsetsAsRect()))
				return insets.consumeSystemWindowInsets();
		} else {
			if (tlFitSystemWindowsInt(insets.getSystemWindowInsetsAsRect()))
				return insets.consumeSystemWindowInsets();
		}
		return insets;
	}

	/** AOSP: the listener, or else onApplyWindowInsets, applies the insets to this view */
	public WindowInsets dispatchApplyWindowInsets(WindowInsets insets) {
		try {
			tlApplyingInsets = true;
			if (tlOnApplyWindowInsetsListener != null)
				return tlOnApplyWindowInsetsListener.onApplyWindowInsets(this, insets);
			return onApplyWindowInsets(insets);
		} finally {
			tlApplyingInsets = false;
		}
	}

	/** AOSP: the insets the window got (before any view consumed some), null when not attached */
	public WindowInsets getRootWindowInsets() {
		if (!isAttachedToWindow())
			return null;
		final Window window = native_get_window(widget);
		return window != null ? window.tlGetWindowInsets() : Window.tlWindowInsetsFor(getContext());
	}

	/** AOSP ViewGroup.cleanupLayoutState: the forced layout this view asked for is dropped */
	void tlCleanupLayoutState() {
		layoutRequested = false;
	}

	/* AOSP key shortcuts and trackball events, which Window.superDispatchKeyShortcutEvent and
	 * superDispatchTrackballEvent hand to the decor view (layout; View members ATL lacked) */

	/** AOSP: a shortcut key goes to the focused view's onKeyShortcut */
	public boolean dispatchKeyShortcutEvent(KeyEvent event) {
		return onKeyShortcut(event.getKeyCode(), event);
	}

	/** AOSP: a plain view handles no shortcut (TextView and lists override it) */
	public boolean onKeyShortcut(int keyCode, KeyEvent event) {
		return false;
	}

	/** AOSP: the trackball event goes to onTrackballEvent */
	public boolean dispatchTrackballEvent(MotionEvent event) {
		return onTrackballEvent(event);
	}

	/** AOSP: a plain view does not consume trackball motion (the car has no trackball either) */
	public boolean onTrackballEvent(MotionEvent event) {
		return false;
	}

	/* AOSP scrolling basics: overscroll mode, scroll offsets, canScroll*, overScrollBy, the reveal
	 * hint; ScrollView and HorizontalScrollView (AOSP ports) build on them (layout) */

	/** AOSP initial mode: OVER_SCROLL_IF_CONTENT_SCROLLS */
	private int tlOverScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS;
	/** AOSP PFLAG3_NO_REVEAL_ON_FOCUS, inverted: a focused view asks its scroll parent to show it */
	private boolean tlRevealOnFocusHint = true;

	/** AOSP: when the view shows an overscroll effect (edge glow / stretch) */
	public void setOverScrollMode(int overScrollMode) {
		if (overScrollMode != OVER_SCROLL_ALWAYS && overScrollMode != OVER_SCROLL_IF_CONTENT_SCROLLS && overScrollMode != OVER_SCROLL_NEVER)
			throw new IllegalArgumentException("Invalid overscroll mode " + overScrollMode);
		tlOverScrollMode = overScrollMode;
	}

	public int getOverScrollMode() {
		return tlOverScrollMode;
	}

	/** AOSP: the horizontal scroll position */
	protected int computeHorizontalScrollOffset() {
		return scrollX;
	}

	/** AOSP: whether the view can scroll left (negative direction) or right (positive) */
	public boolean canScrollHorizontally(int direction) {
		final int offset = computeHorizontalScrollOffset();
		final int range = computeHorizontalScrollRange() - computeHorizontalScrollExtent();
		if (range == 0)
			return false;
		if (direction < 0)
			return offset > 0;
		else
			return offset < range - 1;
	}

	/** AOSP: whether the view can scroll up (negative direction) or down (positive) */
	public boolean canScrollVertically(int direction) {
		final int offset = computeVerticalScrollOffset();
		final int range = computeVerticalScrollRange() - computeVerticalScrollExtent();
		if (range == 0)
			return false;
		if (direction < 0)
			return offset > 0;
		else
			return offset < range - 1;
	}

	/**
	 * AOSP: scrolls with overscroll, the mode and the ranges deciding how far past the content it
	 * may go; the result goes to onOverScrolled. True when the scroll was clamped.
	 */
	protected boolean overScrollBy(int deltaX, int deltaY, int scrollX, int scrollY, int scrollRangeX, int scrollRangeY, int maxOverScrollX, int maxOverScrollY, boolean isTouchEvent) {
		final int overScrollMode = tlOverScrollMode;
		final boolean canScrollHorizontal = computeHorizontalScrollRange() > computeHorizontalScrollExtent();
		final boolean canScrollVertical = computeVerticalScrollRange() > computeVerticalScrollExtent();
		final boolean overScrollHorizontal = overScrollMode == OVER_SCROLL_ALWAYS || (overScrollMode == OVER_SCROLL_IF_CONTENT_SCROLLS && canScrollHorizontal);
		final boolean overScrollVertical = overScrollMode == OVER_SCROLL_ALWAYS || (overScrollMode == OVER_SCROLL_IF_CONTENT_SCROLLS && canScrollVertical);

		int newScrollX = scrollX + deltaX;
		if (!overScrollHorizontal)
			maxOverScrollX = 0;
		int newScrollY = scrollY + deltaY;
		if (!overScrollVertical)
			maxOverScrollY = 0;

		// Clamp values if at the limits and record
		final int left = -maxOverScrollX;
		final int right = maxOverScrollX + scrollRangeX;
		final int top = -maxOverScrollY;
		final int bottom = maxOverScrollY + scrollRangeY;

		boolean clampedX = false;
		if (newScrollX > right) {
			newScrollX = right;
			clampedX = true;
		} else if (newScrollX < left) {
			newScrollX = left;
			clampedX = true;
		}
		boolean clampedY = false;
		if (newScrollY > bottom) {
			newScrollY = bottom;
			clampedY = true;
		} else if (newScrollY < top) {
			newScrollY = top;
			clampedY = true;
		}
		onOverScrolled(newScrollX, newScrollY, clampedX, clampedY);
		return clampedX || clampedY;
	}

	/** AOSP: the result of overScrollBy; a plain view does nothing with it (AOSP: intentionally empty) */
	protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
	}

	/** AOSP: whether this view, once focused, asks its scrolling ancestors to bring it on screen */
	public final void setRevealOnFocusHint(boolean revealOnFocus) {
		tlRevealOnFocusHint = revealOnFocus;
	}

	public final boolean getRevealOnFocusHint() {
		return tlRevealOnFocusHint;
	}
