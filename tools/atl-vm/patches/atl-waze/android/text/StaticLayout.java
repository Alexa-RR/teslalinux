/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.text;

/**
 * StaticLayout is a Layout for text that will not be edited after it is laid out.
 *
 * ATL lays text out with a PangoLayout (see {@link Layout}); this class keeps AOSP's constructors
 * and the complete {@link Builder} API (every setter up to API 33) and hands the layout what Pango
 * can honour: width, ellipsizing (mode + width). Alignment and spacing go to {@link Layout}. The
 * remaining parameters (max lines, break strategy, hyphenation, indents, justification, fallback
 * line spacing, line break config) are recorded and reported by the getters so callers see
 * consistent state, but the Pango line breaker does not use them yet.
 */
public class StaticLayout extends Layout {
	private final int mMaxLines;
	private final int mEllipsizedWidth;
	private final TextUtils.TruncateAt mEllipsize;
	private final boolean mIncludePad;
	private boolean mFallbackLineSpacing;
	private int mBreakStrategy = BREAK_STRATEGY_SIMPLE;
	private int mHyphenationFrequency = HYPHENATION_FREQUENCY_NONE;
	private int mJustificationMode = JUSTIFICATION_MODE_NONE;
	private int[] mLeftIndents;
	private int[] mRightIndents;
	private TextDirectionHeuristic mTextDir;

	public StaticLayout(CharSequence source, int bufstart, int bufend,
	                    TextPaint paint, int outerwidth,
	                    Alignment align, TextDirectionHeuristic textDir,
	                    float spacingmult, float spacingadd,
	                    boolean includepad,
	                    TextUtils.TruncateAt ellipsize, int ellipsizedWidth, int maxLines) {
		super(slice(source, bufstart, bufend), paint, outerwidth, align == null ? Alignment.ALIGN_NORMAL : align, spacingmult, spacingadd);
		mTextDir = textDir;
		mIncludePad = includepad;
		mEllipsize = ellipsize;
		mEllipsizedWidth = ellipsize == null ? outerwidth : ellipsizedWidth;
		mMaxLines = maxLines;
		/* teslalinux (+text): AOSP ellipsizes the last of maxLines lines; Pango's default height (-1)
		 * would ellipsize every paragraph to one line, and ordinal()+1 was no PangoEllipsizeMode */
		if (ellipsize != null && maxLines > 0 && maxLines != Integer.MAX_VALUE) {
			native_set_ellipsize(layout, tlPangoEllipsize(ellipsize, maxLines), ellipsizedWidth);
			native_set_max_lines(layout, maxLines);
		}
	}

	public StaticLayout(CharSequence source, TextPaint paint, int outerwidth, Alignment align, float spacingmult, float spacingadd, boolean includepad) {
		this(source, 0, source.length(), paint, outerwidth, align, TextDirectionHeuristics.FIRSTSTRONG_LTR, spacingmult, spacingadd, includepad, null, outerwidth, Integer.MAX_VALUE);
	}

	public StaticLayout(CharSequence source, int start, int end, TextPaint paint, int outerwidth, Alignment align, float spacingmult, float spacingadd, boolean includepad, TextUtils.TruncateAt ellipsize, int ellipsizedWidth) {
		this(source, start, end, paint, outerwidth, align, TextDirectionHeuristics.FIRSTSTRONG_LTR, spacingmult, spacingadd, includepad, ellipsize, ellipsizedWidth, Integer.MAX_VALUE);
	}

	public StaticLayout(CharSequence source, int start, int end, TextPaint paint, int outerwidth, Alignment align, float spacingmult, float spacingadd, boolean includepad) {
		this(source, start, end, paint, outerwidth, align, TextDirectionHeuristics.FIRSTSTRONG_LTR, spacingmult, spacingadd, includepad, null, outerwidth, Integer.MAX_VALUE);
	}

	private static CharSequence slice(CharSequence source, int start, int end) {
		if (source == null)
			return "";
		if (start <= 0 && end >= source.length())
			return source;
		return source.subSequence(Math.max(0, start), Math.min(end, source.length()));
	}

	@Override
	public int getEllipsizedWidth() {
		return mEllipsizedWidth;
	}

	/** Maximum number of lines this layout was built with. */
	public int getMaxLines() {
		return mMaxLines;
	}

	private boolean mUseBoundsForWidth;

	/** AOSP (API 35): whether the builder asked for bounds-based line widths */
	public boolean getUseBoundsForWidth() {
		return mUseBoundsForWidth;
	}

	/** API 33 name. */
	public boolean isFallbackLineSpacingEnabled() {
		return mFallbackLineSpacing;
	}

	public boolean isFallbackLineSpacing() {
		return mFallbackLineSpacing;
	}

	public int getBreakStrategy() {
		return mBreakStrategy;
	}

	public int getHyphenationFrequency() {
		return mHyphenationFrequency;
	}

	public int getJustificationMode() {
		return mJustificationMode;
	}

	public TextDirectionHeuristic getTextDirectionHeuristic() {
		return mTextDir;
	}

	/**
	 * Builder for static layouts (AOSP API). The builder is not thread safe; obtain one with
	 * {@link #obtain}, set what differs from the defaults, then {@link #build}.
	 */
	public static final class Builder {
		private CharSequence mText;
		private int mStart;
		private int mEnd;
		private TextPaint mPaint;
		private int mWidth;
		private Alignment mAlignment = Alignment.ALIGN_NORMAL;
		private TextDirectionHeuristic mTextDir = TextDirectionHeuristics.FIRSTSTRONG_LTR;
		private float mSpacingMult = 1.0f;
		private float mSpacingAdd = 0.0f;
		private boolean mIncludePad = true;
		private boolean mFallbackLineSpacing = false;
		private int mEllipsizedWidth;
		private TextUtils.TruncateAt mEllipsize = null;
		private int mMaxLines = Integer.MAX_VALUE;
		private int mBreakStrategy = BREAK_STRATEGY_SIMPLE;
		private int mHyphenationFrequency = HYPHENATION_FREQUENCY_NONE;
		private int[] mLeftIndents;
		private int[] mRightIndents;
		private int mJustificationMode = JUSTIFICATION_MODE_NONE;
		private Object mLineBreakConfig;

		private Builder() {
		}

		/**
		 * Obtain a builder for constructing StaticLayout objects.
		 *
		 * @param source The text to be laid out, optionally with spans
		 * @param start The index of the start of the text
		 * @param end The index + 1 of the end of the text
		 * @param paint The base paint used for layout
		 * @param width The width in pixels
		 */
		public static Builder obtain(CharSequence source, int start, int end, TextPaint paint, int width) {
			Builder b = new Builder();
			b.mText = source;
			b.mStart = start;
			b.mEnd = end;
			b.mPaint = paint;
			b.mWidth = width;
			b.mEllipsizedWidth = width;
			return b;
		}

		public Builder setText(CharSequence source) {
			return setText(source, 0, source.length());
		}

		public Builder setText(CharSequence source, int start, int end) {
			mText = source;
			mStart = start;
			mEnd = end;
			return this;
		}

		public Builder setPaint(TextPaint paint) {
			mPaint = paint;
			return this;
		}

		public Builder setWidth(int width) {
			mWidth = width;
			if (mEllipsize == null)
				mEllipsizedWidth = width;
			return this;
		}

		public Builder setAlignment(Alignment alignment) {
			mAlignment = alignment;
			return this;
		}

		public Builder setTextDirection(TextDirectionHeuristic textDir) {
			mTextDir = textDir;
			return this;
		}

		public Builder setLineSpacing(float spacingAdd, float spacingMult) {
			mSpacingAdd = spacingAdd;
			mSpacingMult = spacingMult;
			return this;
		}

		public Builder setIncludePad(boolean includePad) {
			mIncludePad = includePad;
			return this;
		}

		/** API 28: whether line spacing follows the fallback fonts' metrics. */
		public Builder setUseLineSpacingFromFallbacks(boolean useLineSpacingFromFallbacks) {
			mFallbackLineSpacing = useLineSpacingFromFallbacks;
			return this;
		}

		public Builder setEllipsizedWidth(int ellipsizedWidth) {
			mEllipsizedWidth = ellipsizedWidth;
			return this;
		}

		public Builder setEllipsize(TextUtils.TruncateAt ellipsize) {
			mEllipsize = ellipsize;
			return this;
		}

		public Builder setMaxLines(int maxLines) {
			mMaxLines = maxLines;
			return this;
		}

		public Builder setBreakStrategy(int breakStrategy) {
			mBreakStrategy = breakStrategy;
			return this;
		}

		public Builder setHyphenationFrequency(int hyphenationFrequency) {
			mHyphenationFrequency = hyphenationFrequency;
			return this;
		}

		public Builder setIndents(int[] leftIndents, int[] rightIndents) {
			mLeftIndents = leftIndents;
			mRightIndents = rightIndents;
			return this;
		}

		public Builder setJustificationMode(int justificationMode) {
			mJustificationMode = justificationMode;
			return this;
		}

		/** API 33: line break style/word style configuration (android.graphics.text.LineBreakConfig). */
		public Builder setLineBreakConfig(Object lineBreakConfig) {
			mLineBreakConfig = lineBreakConfig;
			return this;
		}

		/** AOSP (API 35): size lines by the glyph bounds instead of the advances. Pango lays the
		 * text out by advances either way; the choice is kept and reported by the layout. */
		public Builder setUseBoundsForWidth(boolean useBoundsForWidth) {
			mUseBoundsForWidth = useBoundsForWidth;
			return this;
		}

		private boolean mUseBoundsForWidth;

		public StaticLayout build() {
			StaticLayout result = new StaticLayout(mText, mStart, mEnd, mPaint, mWidth, mAlignment, mTextDir,
			                                       mSpacingMult, mSpacingAdd, mIncludePad, mEllipsize, mEllipsizedWidth, mMaxLines);
			result.mFallbackLineSpacing = mFallbackLineSpacing;
			result.mBreakStrategy = mBreakStrategy;
			result.mHyphenationFrequency = mHyphenationFrequency;
			result.mJustificationMode = mJustificationMode;
			result.mLeftIndents = mLeftIndents;
			result.mRightIndents = mRightIndents;
			result.mUseBoundsForWidth = mUseBoundsForWidth;
			return result;
		}
	}
}
