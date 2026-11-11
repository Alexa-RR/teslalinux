	/* ---- teslalinux: global layout from the frame clock (native-vto-frame-layout.sh) ----
	 * All activity windows share one GtkWindow and frame clock. AOSP dispatches global layout from
	 * the owning ViewRootImpl's traversal, so an observer whose window lost its decor (the activity
	 * was destroyed and its root removed) never hears of layouts again; the frame callback lands here
	 * and keeps that rule. */
	private Window tlWindow;

	/** called by the native frame callback (paint phase, after layout) */
	void tlDispatchFrameGlobalLayout() {
		final Window w = tlWindow;
		if (w != null) {
			final View decor = w.getDecorView();
			if (decor == null || !decor.isAttachedToWindow())
				return;
		}
		dispatchOnGlobalLayout();
	}
