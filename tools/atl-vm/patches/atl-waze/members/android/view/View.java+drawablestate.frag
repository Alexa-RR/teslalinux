	/* ---- teslalinux: AOSP drawable state (View.java) ----
	 * The view's state set (window_focused, selected, focused, enabled, pressed, activated, plus what
	 * subclasses add in onCreateDrawableState: checked, ...) is built from its real flags, cached, and
	 * rebuilt by refreshDrawableState(), which tells drawableStateChanged() so state-list drawables,
	 * colour state lists (TextView colours, tints) and the parent (addStatesFromChildren) follow it. */
	private int[] tlDrawableState;
	private boolean tlDrawableStateDirty = true;
	private boolean tlSelected;
	private boolean tlActivated;

	/** AOSP View.dispatchSetSelected: a leaf view has no children (ViewGroup passes the selection on) */
	protected void dispatchSetSelected(boolean selected) {
	}

	/** AOSP View.dispatchSetPressed: a leaf view has no children to pass the press to (ViewGroup overrides) */
	protected void dispatchSetPressed(boolean pressed) {
	}

	/** AOSP View.dispatchSetActivated: a leaf view has no children (ViewGroup passes it on) */
	protected void dispatchSetActivated(boolean activated) {
	}

	/** AOSP: the states this view would show, before subclasses add theirs */
	private int[] tlBaseDrawableState(int extraSpace) {
		int n = 0;
		final int[] s = new int[6 + extraSpace];
		if (hasWindowFocus())
			s[n++] = R.attr.state_window_focused;
		if (tlSelected)
			s[n++] = R.attr.state_selected;
		if (isFocused())
			s[n++] = R.attr.state_focused;
		if (isEnabled())
			s[n++] = R.attr.state_enabled;
		if (pressed)
			s[n++] = R.attr.state_pressed;
		if (tlActivated)
			s[n++] = R.attr.state_activated;
		final int[] out = new int[n + extraSpace];
		System.arraycopy(s, 0, out, 0, n);
		return out;
	}
