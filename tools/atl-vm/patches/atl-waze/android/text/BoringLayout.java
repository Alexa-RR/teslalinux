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

import android.graphics.Paint;
import android.text.style.ParagraphStyle;

/**
 * A BoringLayout is a very simple Layout implementation for text that fits on a single line and is
 * all left-to-right characters (AOSP API). Layout itself is done by the Pango layout of
 * {@link Layout}; this class carries the AOSP constructors, the {@link #isBoring} checks (the same
 * character classes AOSP treats as "not boring") and the {@link Metrics}.
 */
public class BoringLayout extends Layout {
	private final boolean mIncludePad;
	private final boolean mFallbackLineSpacing;
	private final int mEllipsizedWidth;
	private final TextUtils.TruncateAt mEllipsize;

	/** Metrics of a boring piece of text: the font metrics plus its width. */
	public static class Metrics extends Paint.FontMetricsInt {
		public int width;

		@Override
		public String toString() {
			return super.toString() + " width=" + width;
		}

		private void reset() {
			top = bottom = ascent = descent = width = leading = 0;
		}
	}

	public static BoringLayout make(CharSequence source, TextPaint paint, int outerWidth, Alignment align,
	                                float spacingMult, float spacingAdd, Metrics metrics, boolean includePad) {
		return new BoringLayout(source, paint, outerWidth, align, spacingMult, spacingAdd, metrics, includePad);
	}

	public static BoringLayout make(CharSequence source, TextPaint paint, int outerWidth, Alignment align,
	                                float spacingMult, float spacingAdd, Metrics metrics, boolean includePad,
	                                TextUtils.TruncateAt ellipsize, int ellipsizedWidth) {
		return new BoringLayout(source, paint, outerWidth, align, spacingMult, spacingAdd, metrics, includePad, ellipsize, ellipsizedWidth);
	}

	/** API 33: with the fallback line spacing flag. */
	public static BoringLayout make(CharSequence source, TextPaint paint, int outerWidth, Alignment align,
	                                Metrics metrics, boolean includePad, TextUtils.TruncateAt ellipsize,
	                                int ellipsizedWidth, boolean useFallbackLineSpacing) {
		return new BoringLayout(source, paint, outerWidth, align, 1f, 0f, metrics, includePad, ellipsize, ellipsizedWidth, useFallbackLineSpacing);
	}

	/** Layouts are immutable here (the Pango layout is rebuilt), so replaceOrMake makes a new one. */
	public BoringLayout replaceOrMake(CharSequence source, TextPaint paint, int outerWidth, Alignment align,
	                                  float spacingMult, float spacingAdd, Metrics metrics, boolean includePad) {
		return make(source, paint, outerWidth, align, spacingMult, spacingAdd, metrics, includePad);
	}

	public BoringLayout replaceOrMake(CharSequence source, TextPaint paint, int outerWidth, Alignment align,
	                                  float spacingMult, float spacingAdd, Metrics metrics, boolean includePad,
	                                  TextUtils.TruncateAt ellipsize, int ellipsizedWidth) {
		return make(source, paint, outerWidth, align, spacingMult, spacingAdd, metrics, includePad, ellipsize, ellipsizedWidth);
	}

	public BoringLayout replaceOrMake(CharSequence source, TextPaint paint, int outerWidth, Alignment align,
	                                  Metrics metrics, boolean includePad, TextUtils.TruncateAt ellipsize,
	                                  int ellipsizedWidth, boolean useFallbackLineSpacing) {
		return make(source, paint, outerWidth, align, metrics, includePad, ellipsize, ellipsizedWidth, useFallbackLineSpacing);
	}

	public BoringLayout(CharSequence source, TextPaint paint, int outerwidth, Alignment align,
	                    float spacingMult, float spacingAdd, Metrics metrics, boolean includePad) {
		this(source, paint, outerwidth, align, spacingMult, spacingAdd, metrics, includePad, null, outerwidth, false);
	}

	public BoringLayout(CharSequence source, TextPaint paint, int outerwidth, Alignment align,
	                    float spacingMult, float spacingAdd, Metrics metrics, boolean includePad,
	                    TextUtils.TruncateAt ellipsize, int ellipsizedWidth) {
		this(source, paint, outerwidth, align, spacingMult, spacingAdd, metrics, includePad, ellipsize, ellipsizedWidth, false);
	}

	public BoringLayout(CharSequence source, TextPaint paint, int outerwidth, Alignment align,
	                    float spacingMult, float spacingAdd, Metrics metrics, boolean includePad,
	                    TextUtils.TruncateAt ellipsize, int ellipsizedWidth, boolean useFallbackLineSpacing) {
		super(source, paint, outerwidth, align, spacingMult, spacingAdd);
		mIncludePad = includePad;
		mFallbackLineSpacing = useFallbackLineSpacing;
		mEllipsize = ellipsize;
		mEllipsizedWidth = ellipsize == null ? outerwidth : ellipsizedWidth;
		if (ellipsize != null && ellipsize != TextUtils.TruncateAt.MARQUEE) {
			/* teslalinux (+text): one line, ellipsized the AOSP way (ordinal()+1 was no PangoEllipsizeMode) */
			native_set_ellipsize(layout, tlPangoEllipsize(ellipsize, 1), ellipsizedWidth);
			native_set_max_lines(layout, 1);
		}
	}

	/** Whether this layout was built with fallback line spacing (API 33). */
	public boolean isFallbackLineSpacingEnabled() {
		return mFallbackLineSpacing;
	}

	@Override
	public int getEllipsizedWidth() {
		return mEllipsizedWidth;
	}

	// ---- isBoring -----------------------------------------------------------------------------

	public static Metrics isBoring(CharSequence text, TextPaint paint) {
		return isBoring(text, paint, TextDirectionHeuristics.FIRSTSTRONG_LTR, null);
	}

	public static Metrics isBoring(CharSequence text, TextPaint paint, Metrics metrics) {
		return isBoring(text, paint, TextDirectionHeuristics.FIRSTSTRONG_LTR, metrics);
	}

	public static Metrics isBoring(CharSequence text, TextPaint paint, TextDirectionHeuristic textDir, Metrics metrics) {
		return isBoring(text, paint, textDir, false, metrics);
	}

	/**
	 * Returns null if not boring; the width, ascent, and descent in the provided Metrics object
	 * (or a new one if the provided one was null) if boring. Text is boring when it has no line
	 * breaks, tabs or characters from right-to-left or complex scripts and no paragraph spans.
	 */
	public static Metrics isBoring(CharSequence text, TextPaint paint, TextDirectionHeuristic textDir,
	                               boolean useFallbackLineSpacing, Metrics metrics) {
		final int textLength = text.length();
		if (hasAnyInterestingChars(text, textLength))
			return null;
		if (textDir != null && textDir.isRtl(text, 0, textLength))
			return null;
		if (text instanceof Spanned) {
			Spanned sp = (Spanned) text;
			Object[] styles = sp.getSpans(0, textLength, ParagraphStyle.class);
			if (styles.length > 0)
				return null;
		}
		Metrics fm = metrics;
		if (fm == null) {
			fm = new Metrics();
		} else {
			fm.reset();
		}
		Paint.FontMetricsInt pfm = paint.getFontMetricsInt();
		if (pfm != null) {
			fm.top = pfm.top;
			fm.ascent = pfm.ascent;
			fm.descent = pfm.descent;
			fm.bottom = pfm.bottom;
			fm.leading = pfm.leading;
		}
		fm.width = (int) Math.ceil(paint.measureText(text, 0, textLength));
		return fm;
	}

	/** AOSP's character classes that make text not boring (bidi, complex scripts, surrogates, controls). */
	private static boolean hasAnyInterestingChars(CharSequence text, int textLength) {
		for (int i = 0; i < textLength; i++) {
			final char c = text.charAt(i);
			if (c == '\n' || c == '\t' || c >= 0x0590)
				return true;
		}
		return false;
	}
}
