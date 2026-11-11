	/* teslalinux (layout): AOSP FrameLayout does not scroll, so its children show their pressed
	 * state at once (ViewGroup's default is true now, as in AOSP) */
	@Override
	public boolean shouldDelayChildPressedState() {
		return false;
	}
