	/* AOSP ImageView drawable tint, colour filter, image alpha and image state (frameworks/base
	 * ImageView.java): kept on the view and applied to every drawable it shows, mutated first so a
	 * drawable shared through its constant state keeps its own colours. Icon colours in apps come
	 * from these (app:tint, setImageTintList, setColorFilter). */
	private android.content.res.ColorStateList tlDrawableTintList;
	private android.graphics.PorterDuff.Mode tlDrawableTintMode;
	private boolean tlHasDrawableTint;
	private boolean tlHasDrawableTintMode;
	private android.graphics.ColorFilter tlColorFilter;
	private boolean tlHasColorFilter;
	private boolean tlColorMod;
	private int tlImageAlpha = 255;
	private int[] tlImageState;
	private boolean tlMergeImageState;

	/** AOSP applyImageTint() */
	private void tlApplyImageTint() {
		if (drawable == null || !(tlHasDrawableTint || tlHasDrawableTintMode))
			return;
		drawable = drawable.mutate();
		if (tlHasDrawableTint)
			drawable.setTintList(tlDrawableTintList);
		if (tlHasDrawableTintMode)
			drawable.setTintMode(tlDrawableTintMode);
		// the tint list resolves against the drawable state
		if (drawable.isStateful())
			drawable.setState(getDrawableState());
	}

	/** AOSP applyColorMod(): the colour filter and the image alpha */
	private void tlApplyColorMod() {
		if (drawable == null || !tlColorMod)
			return;
		drawable = drawable.mutate();
		if (tlHasColorFilter)
			drawable.setColorFilter(tlColorFilter);
		drawable.setAlpha(tlImageAlpha);
	}

	/** re-applies the view's tint/filter (they may mutate the drawable into a new object) and shows the result */
	private void tlRefreshImage() {
		final Drawable before = drawable;
		tlApplyImageTint();
		tlApplyColorMod();
		if (drawable != before) {
			if (before != null)
				before.setCallback(null);
			drawable.setCallback(this);
			native_setDrawable(widget, drawable.paintable);
		}
		invalidate();
	}

	public void setImageTintBlendMode(android.graphics.BlendMode blendMode) {
		setImageTintMode(android.graphics.BlendMode.blendModeToPorterDuffMode(blendMode));
	}

	public android.graphics.ColorFilter getColorFilter() {
		return tlColorFilter;
	}

	public int getImageAlpha() {
		return tlImageAlpha;
	}

	/** AOSP: the image state replaces (merge=false) or extends the view's drawable state */
	@Override
	public int[] onCreateDrawableState(int extraSpace) {
		if (tlImageState == null)
			return super.onCreateDrawableState(extraSpace);
		if (!tlMergeImageState)
			return tlImageState;
		return mergeDrawableStates(super.onCreateDrawableState(extraSpace + tlImageState.length), tlImageState);
	}

	/** AOSP: the image drawable is one of this view's own drawables (invalidateDrawable redraws for it) */
	@Override
	protected boolean verifyDrawable(Drawable dr) {
		return drawable == dr || super.verifyDrawable(dr);
	}

	/** AOSP: a stateful image follows the view's state (pressed, selected, checked, ...) */
	@Override
	protected void drawableStateChanged() {
		super.drawableStateChanged();
		final Drawable d = drawable;
		if (d != null && d.isStateful() && d.setState(getDrawableState()))
			invalidateDrawable(d);
	}
