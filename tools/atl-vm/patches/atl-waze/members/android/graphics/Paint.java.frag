	/* teslalinux: Paint additions */
	public long getColorLong() { return Color.pack(getColor()); }
	public void setColor(long color) { setColor((int) (color >> 32)); }
	public long getColorLongCompat() { return getColorLong(); }

	/** Text locales (AOSP mLocales): never empty, defaults to the system locale list. */
	private android.os.LocaleList mLocales;

	/** Sets the text locale list; must not be empty (AOSP semantics). */
	public void setTextLocales(android.os.LocaleList locales) {
		if (locales == null || locales.isEmpty())
			throw new IllegalArgumentException("locales cannot be null or empty");
		mLocales = locales;
	}

	/** Sets a single text locale. */
	public void setTextLocale(Locale locale) {
		if (locale == null)
			throw new IllegalArgumentException("locale cannot be null");
		mLocales = new android.os.LocaleList(locale);
	}

	/** The text locale list (AOSP getTextLocales). */
	public android.os.LocaleList getTextLocales() {
		if (mLocales == null)
			mLocales = new android.os.LocaleList(Locale.getDefault());
		return mLocales;
	}

	/** Bounds of the text (API 29 CharSequence overload). */
	public void getTextBounds(CharSequence text, int start, int end, Rect bounds) {
		getTextBounds(text.toString(), start, end, bounds);
	}

	private String mFontFeatureSettings;

	/** CSS-style font feature settings, e.g. "smcp" (AOSP); kept for the layout engine to read. */
	public String getFontFeatureSettings() {
		return mFontFeatureSettings;
	}

	public void setFontFeatureSettings(String settings) {
		if (settings != null && settings.isEmpty())
			settings = null;
		mFontFeatureSettings = settings;
	}
	/** AOSP flag word (ANTI_ALIAS_FLAG, FILTER_BITMAP_FLAG, DITHER_FLAG, ...) and hinting mode. */
	private int mFlags;
	private int mHinting = HINTING_ON;
	/** Paint hinting modes (AOSP). */
	public static final int HINTING_OFF = 0x0;
	public static final int HINTING_ON = 0x1;

	/* teslalinux: text measurement state and natives (native-paint-metrics.sh) */
	private float mLetterSpacing = 0f;
	private float mTextScaleX = 1f;
	private static native void native_get_font_metrics(long paint, float[] out);
	private static native float native_measure_text(long paint, char[] text, int index, int count);
	private static native float native_get_text_advances(long paint, char[] text, int index, int count, float[] advances, int advancesIndex);
	private static native boolean native_has_glyph(long paint, String text);
	private static native void native_set_letter_spacing(long paint, float spacing);

	/** top, ascent, descent, bottom, leading of the current font in pixels (ascent and top negative). */
	private float[] tlFontMetrics() {
		float[] m = new float[5];
		native_get_font_metrics(paint, m);
		return m;
	}

	/** AOSP: the recommended line spacing, descent - ascent + leading. */
	public float getFontSpacing() {
		return getFontMetrics(null);
	}

	public int getTextWidths(char[] text, int index, int count, float[] widths) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((index | count) < 0 || index + count > text.length || count > widths.length)
			throw new ArrayIndexOutOfBoundsException();
		if (count == 0)
			return 0;
		native_get_text_advances(paint, text, index, count, widths, 0);
		if (mTextScaleX != 1f)
			for (int i = 0; i < count; i++)
				widths[i] *= mTextScaleX;
		return count;
	}

	public int getTextWidths(CharSequence text, int start, int end, float[] widths) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((start | end | (end - start) | (text.length() - end)) < 0)
			throw new IndexOutOfBoundsException();
		if (end - start > widths.length)
			throw new ArrayIndexOutOfBoundsException();
		if (end == start)
			return 0;
		char[] buf = new char[end - start];
		android.text.TextUtils.getChars(text, start, end, buf, 0);
		return getTextWidths(buf, 0, buf.length, widths);
	}

	public int getTextWidths(String text, float[] widths) {
		return getTextWidths(text, 0, text.length(), widths);
	}

	/**
	 * AOSP (hidden, used by the text layout code): advances of chars[index, index+count) measured in
	 * the context chars[contextIndex, contextIndex+contextCount); returns the total advance.
	 */
	public float getTextRunAdvances(char[] chars, int index, int count, int contextIndex, int contextCount, boolean isRtl, float[] advances, int advancesIndex) {
		if (chars == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((index | count | contextIndex | contextCount | advancesIndex | (index - contextIndex) | (contextCount - count)
				| ((contextIndex + contextCount) - (index + count)) | (chars.length - (contextIndex + contextCount))
				| (advances == null ? 0 : (advances.length - (advancesIndex + count)))) < 0)
			throw new IndexOutOfBoundsException();
		if (count == 0)
			return 0f;
		/* measure the whole context so shaping across the run boundary matches drawing, then take the slice */
		float[] all = new float[contextCount];
		native_get_text_advances(paint, chars, contextIndex, contextCount, all, 0);
		float total = 0f;
		for (int i = 0; i < count; i++) {
			float a = all[index - contextIndex + i] * mTextScaleX;
			if (advances != null)
				advances[advancesIndex + i] = a;
			total += a;
		}
		return total;
	}

	public float getTextRunAdvances(CharSequence text, int start, int end, int contextStart, int contextEnd, boolean isRtl, float[] advances, int advancesIndex) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((start | end | contextStart | contextEnd | (end - start) | (start - contextStart) | (contextEnd - end) | (text.length() - contextEnd)) < 0)
			throw new IndexOutOfBoundsException();
		char[] buf = new char[contextEnd - contextStart];
		android.text.TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
		return getTextRunAdvances(buf, start - contextStart, end - start, 0, contextEnd - contextStart, isRtl, advances, advancesIndex);
	}

	/** AOSP: the advance from start to offset within the run text[start, end), measured in its context. */
	public float getRunAdvance(char[] text, int start, int end, int contextStart, int contextEnd, boolean isRtl, int offset) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((contextStart | start | offset | end | contextEnd | start - contextStart | offset - start | end - offset
				| contextEnd - end | text.length - contextEnd) < 0)
			throw new IndexOutOfBoundsException();
		if (end == start)
			return 0f;
		float[] all = new float[contextEnd - contextStart];
		native_get_text_advances(paint, text, contextStart, contextEnd - contextStart, all, 0);
		float sum = 0f;
		for (int i = start; i < offset; i++)
			sum += all[i - contextStart];
		return sum * mTextScaleX;
	}

	public float getRunAdvance(CharSequence text, int start, int end, int contextStart, int contextEnd, boolean isRtl, int offset) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((contextStart | start | offset | end | contextEnd | start - contextStart | offset - start | end - offset
				| contextEnd - end | text.length() - contextEnd) < 0)
			throw new IndexOutOfBoundsException();
		if (end == start)
			return 0f;
		char[] buf = new char[contextEnd - contextStart];
		android.text.TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
		return getRunAdvance(buf, start - contextStart, end - contextStart, 0, contextEnd - contextStart, isRtl, offset - contextStart);
	}

	/** AOSP: the character offset whose position is closest to advance (cluster boundaries only). */
	public int getOffsetForAdvance(char[] text, int start, int end, int contextStart, int contextEnd, boolean isRtl, float advance) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		if ((contextStart | start | end | contextEnd | start - contextStart | end - start | contextEnd - end
				| text.length - contextEnd) < 0)
			throw new IndexOutOfBoundsException();
		float[] all = new float[contextEnd - contextStart];
		if (contextEnd > contextStart)
			native_get_text_advances(paint, text, contextStart, contextEnd - contextStart, all, 0);
		float x = 0f;
		int best = start;
		float bestDistance = Math.abs(advance);
		for (int i = start; i < end; i++) {
			float w = all[i - contextStart] * mTextScaleX;
			if (w == 0f && i > start)
				continue; /* not a cluster boundary */
			x += w;
			float distance = Math.abs(advance - x);
			if (distance <= bestDistance) {
				bestDistance = distance;
				best = i + 1;
			}
		}
		return best;
	}

	public int getOffsetForAdvance(CharSequence text, int start, int end, int contextStart, int contextEnd, boolean isRtl, float advance) {
		if (text == null)
			throw new IllegalArgumentException("text cannot be null");
		char[] buf = new char[contextEnd - contextStart];
		android.text.TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
		return getOffsetForAdvance(buf, start - contextStart, end - contextStart, 0, contextEnd - contextStart, isRtl, advance) + contextStart;
	}

	/* teslalinux: shadow layer and elegant text height state (AOSP Paint) */
	private float mShadowLayerRadius;
	private float mShadowLayerDx;
	private float mShadowLayerDy;
	private int mShadowLayerColor;
	private boolean mElegantTextHeight;

	public float getShadowLayerRadius() {
		return mShadowLayerRadius;
	}

	public float getShadowLayerDx() {
		return mShadowLayerDx;
	}

	public float getShadowLayerDy() {
		return mShadowLayerDy;
	}

	public int getShadowLayerColor() {
		return mShadowLayerColor;
	}

	public long getShadowLayerColorLong() {
		return Color.pack(mShadowLayerColor);
	}

	public void setShadowLayer(float radius, float dx, float dy, long color) {
		setShadowLayer(radius, dx, dy, Color.toArgb(color));
	}

	/** AOSP: whether tall scripts use their elegant (taller) variant fonts; Pango has no such variant switch, the flag is kept. */
	public boolean isElegantTextHeight() {
		return mElegantTextHeight;
	}

	public void setElegantTextHeight(boolean elegant) {
		mElegantTextHeight = elegant;
	}
	/* AOSP (hidden): compatibility scaling for apps that run in screen-compatibility mode (factor 1 here) */
	private boolean mHasCompatScaling;
	private float mCompatScaling = 1f, mInvCompatScaling = 1f;

	public void setCompatibilityScaling(float factor) {
		if (factor == 1.0f) {
			mHasCompatScaling = false;
			mCompatScaling = mInvCompatScaling = 1.0f;
		} else {
			mHasCompatScaling = true;
			mCompatScaling = factor;
			mInvCompatScaling = 1.0f / factor;
		}
	}
