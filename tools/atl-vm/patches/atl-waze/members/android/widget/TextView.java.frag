
	/* teslalinux: AOSP text attributes that have no Pango counterpart are kept and reported */
	private boolean mAllCaps;
	private int mLineBreakStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_STYLE_NONE;
	private int mLineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE;
	private boolean mFallbackLineSpacing = true;

	/** AOSP (API 33): the line-break strictness for CJK text; Pango uses the Unicode line breaking rules only. */
	public void setLineBreakStyle(int lineBreakStyle) {
		mLineBreakStyle = lineBreakStyle;
	}

	public int getLineBreakStyle() {
		return mLineBreakStyle;
	}

	public void setLineBreakWordStyle(int lineBreakWordStyle) {
		mLineBreakWordStyle = lineBreakWordStyle;
	}

	public int getLineBreakWordStyle() {
		return mLineBreakWordStyle;
	}

	/** AOSP (API 28): whether fallback fonts may raise the line height; Pango always sizes lines by the fonts used. */
	public void setFallbackLineSpacing(boolean enabled) {
		mFallbackLineSpacing = enabled;
	}

	public boolean isFallbackLineSpacing() {
		return mFallbackLineSpacing;
	}

	public void setElegantTextHeight(boolean elegant) {
		getPaint().setElegantTextHeight(elegant);
	}

	public boolean isElegantTextHeight() {
		return getPaint().isElegantTextHeight();
	}
	/* AOSP: selection highlight colour (default holo blue, 40% alpha) */
	private int mHighlightColor = 0x6633B5E5;

	public int getHighlightColor() {
		return mHighlightColor;
	}

	private int mCurHintTextColor = 0xFF808080;

	/** AOSP: the hint colour for the current state. */
	public int getCurrentHintTextColor() {
		return mCurHintTextColor;
	}
	/** AOSP: the gravity of the text within the view; a missing half defaults to START / TOP. */
	@Override
	public void setGravity(int gravity) {
		if ((gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == 0) {
			gravity |= Gravity.START;
		}
		if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == 0) {
			gravity |= Gravity.TOP;
		}
		boolean newLayout = (gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) != (this.gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
		if (gravity != this.gravity) {
			invalidate();
		}
		this.gravity = gravity;
		if (widget != 0) {
			native_setGravity(widget, gravity);
		}
		if (newLayout) {
			requestLayout();
		}
	}

	private native void native_setGravity(long widget, int gravity);
	/* teslalinux: compound padding (a CompoundButton's button, a Switch's track) keeps the GTK label clear of the drawable */
	private int tlCompoundLeft, tlCompoundRight;

	private native void native_setCompoundPadding(long widget, int left, int right);

	protected void tlUpdateCompoundPadding() {
		int l = getCompoundPaddingLeft();
		int r = getCompoundPaddingRight();
		if (l != tlCompoundLeft || r != tlCompoundRight) {
			tlCompoundLeft = l;
			tlCompoundRight = r;
			native_setCompoundPadding(widget, l, r);
		}
	}
	/** AOSP: the locale of the text paint. */
	public java.util.Locale getTextLocale() {
		return getPaint().getTextLocale();
	}
	/** AOSP (hidden): the horizontal space a subclass draws its own drawable in (CompoundButton's button). */
	public int getHorizontalOffsetForDrawables() {
		return 0;
	}
	/** AOSP TextView.onMeasure counts the compound padding; the GTK label carries it as margins, so apply it before the native measure. */
	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		tlUpdateCompoundPadding();
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
	}
	/* AOSP EditorInfo state of this editor (set by setInputType/setImeOptions or the XML attributes) */
	protected int tlInputType = android.text.InputType.TYPE_CLASS_TEXT;
	protected int tlImeOptions = android.view.inputmethod.EditorInfo.IME_NULL;
