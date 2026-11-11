	/* AOSP View.TintInfo of the background (frameworks/base View.java): from android:backgroundTint /
	 * android:backgroundTintMode or setBackgroundTintList()/setBackgroundTintMode(), applied to every
	 * background drawable, mutated first so a drawable shared through its constant state keeps its own
	 * colours (Material and Waze buttons colour one ripple/shape resource this way). */
	private android.content.res.ColorStateList tlBgTintList;
	private android.graphics.PorterDuff.Mode tlBgTintMode;
	private boolean tlHasBgTintList;
	private boolean tlHasBgTintMode;

	/** AOSP View.applyBackgroundTint() */
	private void applyBackgroundTint() {
		final android.graphics.drawable.Drawable bg = background;
		if (bg == null || !(tlHasBgTintList || tlHasBgTintMode))
			return;
		final android.graphics.drawable.Drawable m = bg.mutate();
		if (tlHasBgTintList)
			m.setTintList(tlBgTintList);
		if (tlHasBgTintMode)
			m.setTintMode(tlBgTintMode);
		// the tint list resolves against the drawable state; a stateful background must know it
		if (m.isStateful())
			m.setState(getDrawableState());
		if (m != bg) {
			background = m;
			m.setCallback(this);
			native_setBackgroundDrawable(widget, m.paintable);
		}
		invalidate();
	}

	/** AOSP View.setBackgroundTintBlendMode() (API 29): the same tint, as a BlendMode */
	public void setBackgroundTintBlendMode(android.graphics.BlendMode blendMode) {
		setBackgroundTintMode(android.graphics.BlendMode.blendModeToPorterDuffMode(blendMode));
	}

	/** AOSP View.getBackgroundTintBlendMode() */
	public android.graphics.BlendMode getBackgroundTintBlendMode() {
		if (tlBgTintMode == null)
			return null;
		for (android.graphics.BlendMode b : android.graphics.BlendMode.values())
			if (android.graphics.BlendMode.blendModeToPorterDuffMode(b) == tlBgTintMode)
				return b;
		return null;
	}
