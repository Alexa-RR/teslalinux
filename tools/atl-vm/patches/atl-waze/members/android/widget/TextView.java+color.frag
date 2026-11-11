	/* ---- teslalinux: AOSP text and hint colours (TextView.java updateTextColors) ----
	 * The colour state lists resolve against the view's drawable state whenever it changes (pressed,
	 * selected, checked, enabled: a selected tab or segment shows its selected colour), and a plain
	 * TextView shows its hint in the hint colour while its text is empty, as AOSP draws it. */
	private ColorStateList tlTextColor;
	private int tlCurTextColor;
	private ColorStateList tlHintTextColor;
	private CharSequence tlHint;
	private boolean tlShowingHint;

	/** AOSP: the theme's primary text colour when no textColor was set (getCurrentTextColor before any setTextColor) */
	private int tlDefaultTextColor() {
		try {
			final TypedArray ta = getContext().obtainStyledAttributes(new int[] {android.R.attr.textColorPrimary});
			final ColorStateList c = ta.getColorStateList(0);
			ta.recycle();
			if (c != null)
				return c.getColorForState(getDrawableState(), c.getDefaultColor());
		} catch (Exception e) {
		}
		return 0xFF000000;
	}

	/** AOSP TextView.updateTextColors(): the current text and hint colours for this state */
	private void tlUpdateTextColors() {
		final int[] state = getDrawableState();
		boolean changed = false;
		if (tlTextColor != null) {
			final int c = tlTextColor.getColorForState(state, tlTextColor.getDefaultColor());
			if (c != tlCurTextColor) {
				tlCurTextColor = c;
				changed = true;
			}
		}
		if (tlHintTextColor != null) {
			final int c = tlHintTextColor.getColorForState(state, tlHintTextColor.getDefaultColor());
			if (c != mCurHintTextColor) {
				mCurHintTextColor = c;
				changed = true;
			}
		}
		if (changed)
			tlApplyNativeColor();
	}

	private void tlApplyNativeColor() {
		if (tlShowingHint)
			native_setTextColor(mCurHintTextColor);
		else if (tlTextColor != null)
			native_setTextColor(tlCurTextColor);
	}

	/** what the native label shows: the text, or (a plain TextView with empty text) the hint */
	private void tlShowTextOrHint() {
		final boolean hint = !(this instanceof EditText) && (text == null || text.length() == 0) && tlHint != null && tlHint.length() > 0;
		if (hint) {
			native_setText(tlHint.toString());
			if (tlHint instanceof android.text.Spanned)
				native_set_markup(1);
		} else {
			native_setText(text != null ? text.toString() : null);
		}
		if (hint != tlShowingHint) {
			tlShowingHint = hint;
			tlApplyNativeColor();
		}
	}

	@Override
	protected void drawableStateChanged() {
		super.drawableStateChanged();
		if ((tlTextColor != null && tlTextColor.isStateful()) || (tlHintTextColor != null && tlHintTextColor.isStateful()))
			tlUpdateTextColors();
	}
