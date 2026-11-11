/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.text.LineBreakConfig;
import android.text.style.MetricAffectingSpan;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Objects;

/**
 * A text which has the character metrics data.
 *
 * A text object that contains the character metrics data and can be used to improve the performance
 * of text layout operations. When a PrecomputedText is created with a given {@link CharSequence},
 * it will measure the text metrics during the creation. This PrecomputedText instance can be set on
 * {@link android.widget.TextView} or {@link StaticLayout}. Since the text layout information will
 * be included in this instance, {@link android.widget.TextView} or {@link StaticLayout} will not
 * have to recalculate this information.
 *
 * Note that the {@link PrecomputedText} created from different parameters of the target {@link
 * android.widget.TextView} will be rejected internally and compute the text layout again with the
 * current {@link android.widget.TextView} parameters.
 *
 * <pre>
 * An example usage is:
 * <code>
 *  static void asyncSetText(TextView textView, final String longString, Executor bgExecutor) {
 *      // construct precompute related parameters using the TextView that we will set the text on.
 *      final PrecomputedText.Params params = textView.getTextMetricsParams();
 *      final Reference textViewRef = new WeakReference<>(textView);
 *      bgExecutor.submit(() -> {
 *          TextView textView = textViewRef.get();
 *          if (textView == null) return;
 *          final PrecomputedText precomputedText = PrecomputedText.create(longString, params);
 *          textView.post(() -> {
 *              TextView textView = textViewRef.get();
 *              if (textView == null) return;
 *              textView.setText(precomputedText);
 *          });
 *      });
 *  }
 * </code>
 * </pre>
 *
 * Note that the {@link PrecomputedText} created from different parameters of the target
 * {@link android.widget.TextView} will be rejected.
 *
 * Note that any {@link android.text.NoCopySpan} attached to the original text won't be passed to
 * PrecomputedText.
 */
public class PrecomputedText implements Spannable {
    private static final char LINE_FEED = '\n';

    /**
     * The information required for building {@link PrecomputedText}.
     *
     * Contains information required for precomputing text measurement metadata, so it can be done
     * in isolation of a {@link android.widget.TextView} or {@link StaticLayout}, when final layout
     * constraints are not known.
     */
    public static final class Params {
        // The TextPaint used for measurement.
        private final @NonNull TextPaint mPaint;

        // The requested text direction.
        private final @NonNull TextDirectionHeuristic mTextDir;

        // The break strategy for this measured text.
        private final int mBreakStrategy;

        // The hyphenation frequency for this measured text.
        private final int mHyphenationFrequency;

        // The line break configuration for calculating text wrapping.
        private final @NonNull LineBreakConfig mLineBreakConfig;

        /**
         * A builder for creating {@link Params}.
         */
        public static class Builder {
            // The TextPaint used for measurement.
            private final @NonNull TextPaint mPaint;

            // The requested text direction.
            private TextDirectionHeuristic mTextDir = TextDirectionHeuristics.FIRSTSTRONG_LTR;

            // The break strategy for this measured text.
            private int mBreakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY;

            // The hyphenation frequency for this measured text.
            private int mHyphenationFrequency =
                    Layout.HYPHENATION_FREQUENCY_NORMAL;

            // The line break configuration for calculating text wrapping.
            private @NonNull LineBreakConfig mLineBreakConfig = LineBreakConfig.NONE;

            /**
             * Builder constructor.
             *
             * @param paint the paint to be used for drawing
             */
            public Builder(@NonNull TextPaint paint) {
                mPaint = paint;
            }

            /**
             * Builder constructor from existing params.
             */
            public Builder(@NonNull Params params) {
                mPaint = params.mPaint;
                mTextDir = params.mTextDir;
                mBreakStrategy = params.mBreakStrategy;
                mHyphenationFrequency = params.mHyphenationFrequency;
                mLineBreakConfig = params.mLineBreakConfig;
            }

            /**
             * Set the line break strategy.
             *
             * The default value is {@link Layout#BREAK_STRATEGY_HIGH_QUALITY}.
             *
             * @param strategy the break strategy
             * @return this builder, useful for chaining
             * @see StaticLayout.Builder#setBreakStrategy
             * @see android.widget.TextView#setBreakStrategy
             */
            public Builder setBreakStrategy(int strategy) {
                mBreakStrategy = strategy;
                return this;
            }

            /**
             * Set the hyphenation frequency.
             *
             * The default value is {@link Layout#HYPHENATION_FREQUENCY_NORMAL}.
             *
             * @param frequency the hyphenation frequency
             * @return this builder, useful for chaining
             * @see StaticLayout.Builder#setHyphenationFrequency
             * @see android.widget.TextView#setHyphenationFrequency
             */
            public Builder setHyphenationFrequency(int frequency) {
                mHyphenationFrequency = frequency;
                return this;
            }

            /**
             * Set the text direction heuristic.
             *
             * The default value is {@link TextDirectionHeuristics#FIRSTSTRONG_LTR}.
             *
             * @param textDir the text direction heuristic for resolving bidi behavior
             * @return this builder, useful for chaining
             * @see StaticLayout.Builder#setTextDirection
             */
            public Builder setTextDirection(@NonNull TextDirectionHeuristic textDir) {
                mTextDir = textDir;
                return this;
            }

            /**
             * Set the line break config for the text wrapping.
             *
             * @param lineBreakConfig the newly line break configuration.
             * @return this builder, useful for chaining.
             * @see StaticLayout.Builder#setLineBreakConfig
             */
            public @NonNull Builder setLineBreakConfig(@NonNull LineBreakConfig lineBreakConfig) {
                mLineBreakConfig = lineBreakConfig;
                return this;
            }

            /**
             * Build the {@link Params}.
             *
             * @return the layout parameter
             */
            public @NonNull Params build() {
                return new Params(mPaint, mLineBreakConfig, mTextDir, mBreakStrategy,
                        mHyphenationFrequency);
            }
        }

        // This is public hidden for internal use.
        // For the external developers, use Builder instead.
        /** @hide */
        public Params(@NonNull TextPaint paint,
                @NonNull LineBreakConfig lineBreakConfig,
                @NonNull TextDirectionHeuristic textDir,
                int strategy,
                int frequency) {
            mPaint = paint;
            mTextDir = textDir;
            mBreakStrategy = strategy;
            mHyphenationFrequency = frequency;
            mLineBreakConfig = lineBreakConfig;
        }

        /**
         * Returns the {@link TextPaint} for this text.
         *
         * @return A {@link TextPaint}
         */
        public @NonNull TextPaint getTextPaint() {
            return mPaint;
        }

        /**
         * Returns the {@link TextDirectionHeuristic} for this text.
         *
         * @return A {@link TextDirectionHeuristic}
         */
        public @NonNull TextDirectionHeuristic getTextDirection() {
            return mTextDir;
        }

        /**
         * Returns the break strategy for this text.
         *
         * @return A line break strategy
         */
        public int getBreakStrategy() {
            return mBreakStrategy;
        }

        /**
         * Returns the hyphenation frequency for this text.
         *
         * @return A hyphenation frequency
         */
        public int getHyphenationFrequency() {
            return mHyphenationFrequency;
        }

        /**
         * Returns the {@link LineBreakConfig} for this text.
         *
         * @return the current line break configuration. The {@link LineBreakConfig} with default
         * values will be returned if no line break configuration is set.
         */
        public @NonNull LineBreakConfig getLineBreakConfig() {
            return mLineBreakConfig;
        }

        /** @hide */
        @IntDef(value = { UNUSABLE, NEED_RECOMPUTE, USABLE })
        @Retention(RetentionPolicy.SOURCE)
        public @interface CheckResultUsableResult {}

        /**
         * Constant for returning value of checkResultUsable indicating that given parameter is not
         * compatible.
         * @hide
         */
        public static final int UNUSABLE = 0;

        /**
         * Constant for returning value of checkResultUsable indicating that given parameter is not
         * compatible but partially usable for creating new PrecomputedText.
         * @hide
         */
        public static final int NEED_RECOMPUTE = 1;

        /**
         * Constant for returning value of checkResultUsable indicating that given parameter is
         * compatible.
         * @hide
         */
        public static final int USABLE = 2;

        /** @hide */
        public @CheckResultUsableResult int checkResultUsable(@NonNull TextPaint paint,
                @NonNull TextDirectionHeuristic textDir, int strategy,
                int frequency, @NonNull LineBreakConfig lbConfig) {
            if (mBreakStrategy == strategy && mHyphenationFrequency == frequency
                    && mLineBreakConfig.equals(lbConfig)
                    && tlSameMeasurement(mPaint, paint)) {
                return mTextDir == textDir ? USABLE : NEED_RECOMPUTE;
            } else {
                return UNUSABLE;
            }
        }

        /**
         * Check if the same text layout.
         *
         * @return true if this and the given param result in the same text layout
         */
        @Override
        public boolean equals(@Nullable Object o) {
            if (o == this) {
                return true;
            }
            if (o == null || !(o instanceof Params)) {
                return false;
            }
            Params param = (Params) o;
            return checkResultUsable(param.mPaint, param.mTextDir, param.mBreakStrategy,
                    param.mHyphenationFrequency, param.mLineBreakConfig) == Params.USABLE;
        }

        @Override
        public int hashCode() {
            // TODO: implement MinikinPaint::hashCode and use it to keep consistency with equals.
            int lineBreakStyle = (mLineBreakConfig != null)
                    ? mLineBreakConfig.getLineBreakStyle() : LineBreakConfig.LINE_BREAK_STYLE_NONE;
            return Objects.hash(mPaint.getTextSize(), mPaint.getTextScaleX(), mPaint.getTextSkewX(),
                    mPaint.getLetterSpacing(), mPaint.getFlags(),
                    mPaint.getTextLocales(), mPaint.getTypeface(),
                    mPaint.isElegantTextHeight(), mTextDir,
                    mBreakStrategy, mHyphenationFrequency, lineBreakStyle);
        }

        @Override
        public String toString() {
            int lineBreakStyle = (mLineBreakConfig != null)
                    ? mLineBreakConfig.getLineBreakStyle() : LineBreakConfig.LINE_BREAK_STYLE_NONE;
            int lineBreakWordStyle = (mLineBreakConfig != null)
                    ? mLineBreakConfig.getLineBreakWordStyle()
                            : LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE;
            return "{"
                + "textSize=" + mPaint.getTextSize()
                + ", textScaleX=" + mPaint.getTextScaleX()
                + ", textSkewX=" + mPaint.getTextSkewX()
                + ", letterSpacing=" + mPaint.getLetterSpacing()
                + ", textLocale=" + mPaint.getTextLocales()
                + ", typeface=" + mPaint.getTypeface()
                + ", variationSettings=" + mPaint.getFontVariationSettings()
                + ", elegantTextHeight=" + mPaint.isElegantTextHeight()
                + ", textDir=" + mTextDir
                + ", breakStrategy=" + mBreakStrategy
                + ", hyphenationFrequency=" + mHyphenationFrequency
                + ", lineBreakStyle=" + lineBreakStyle
                + ", lineBreakWordStyle=" + lineBreakWordStyle
                + "}";
        }
    };

    /** teslalinux: what AOSP Paint.equalsForTextMeasurement compares, as this Paint exposes it */
    private static boolean tlSameMeasurement(TextPaint a, TextPaint b) {
        return a.getTextSize() == b.getTextSize()
                && a.getTextScaleX() == b.getTextScaleX()
                && a.getTextSkewX() == b.getTextSkewX()
                && a.getLetterSpacing() == b.getLetterSpacing()
                && a.getFlags() == b.getFlags()
                && a.isElegantTextHeight() == b.isElegantTextHeight()
                && Objects.equals(a.getTypeface(), b.getTypeface())
                && Objects.equals(a.getTextLocales(), b.getTextLocales())
                && Objects.equals(a.getFontFeatureSettings(), b.getFontFeatureSettings());
    }

    /*
     * teslalinux: AOSP measures every paragraph with minikin (MeasuredParagraph) when the text is
     * created. Text here is laid out and drawn by Pango from the text and the paint, so the
     * precomputed part is the paragraph split; widths, bounds and metrics are measured by the
     * paint (Pango) when asked, with the same results as the layouts that draw the text.
     */

    // The original text.
    private final @NonNull SpannableString mText;
    private final @NonNull Params mParams;
    // Exclusive paragraph ends.
    private final @NonNull int[] mParagraphEnds;

    public static PrecomputedText create(@NonNull CharSequence text, @NonNull Params params) {
        Preconditions.checkNotNull(text);
        Preconditions.checkNotNull(params);
        if (text instanceof PrecomputedText) {
            final PrecomputedText hint = (PrecomputedText) text;
            if (hint.getParams().equals(params))
                return hint;
        }
        final ArrayList<Integer> ends = new ArrayList<Integer>();
        final int len = text.length();
        for (int paraStart = 0, paraEnd; paraStart < len; paraStart = paraEnd) {
            paraEnd = TextUtils.indexOf(text, LINE_FEED, paraStart, len);
            if (paraEnd < 0)
                paraEnd = len;
            else
                paraEnd++; // Includes LINE_FEED(U+000A) to the prev paragraph.
            ends.add(paraEnd);
        }
        final int[] out = new int[ends.size()];
        for (int i = 0; i < out.length; i++)
            out[i] = ends.get(i);
        return new PrecomputedText(text, params, out);
    }

    private PrecomputedText(@NonNull CharSequence text, @NonNull Params params, @NonNull int[] paragraphEnds) {
        // AOSP: the copy leaves NoCopySpans behind (SpannableString(text, ignoreNoCopySpan))
        mText = new SpannableString(text.toString());
        if (text instanceof Spanned) {
            final Spanned sp = (Spanned) text;
            for (Object span : sp.getSpans(0, sp.length(), Object.class)) {
                if (!(span instanceof NoCopySpan))
                    mText.setSpan(span, sp.getSpanStart(span), sp.getSpanEnd(span), sp.getSpanFlags(span));
            }
        }
        mParams = params;
        mParagraphEnds = paragraphEnds;
    }

    public @NonNull CharSequence getText() {
        return mText;
    }

    /** @hide */
    public @IntRange(from = 0) int getStart() {
        return 0;
    }

    /** @hide */
    public @IntRange(from = 0) int getEnd() {
        return mText.length();
    }

    public @NonNull Params getParams() {
        return mParams;
    }

    public @IntRange(from = 0) int getParagraphCount() {
        return mParagraphEnds.length;
    }

    public @IntRange(from = 0) int getParagraphStart(@IntRange(from = 0) int paraIndex) {
        Preconditions.checkArgumentInRange(paraIndex, 0, getParagraphCount(), "paraIndex");
        return paraIndex == 0 ? 0 : getParagraphEnd(paraIndex - 1);
    }

    public @IntRange(from = 0) int getParagraphEnd(@IntRange(from = 0) int paraIndex) {
        Preconditions.checkArgumentInRange(paraIndex, 0, getParagraphCount(), "paraIndex");
        return mParagraphEnds[paraIndex];
    }

    /** @hide */
    public @Params.CheckResultUsableResult int checkResultUsable(@IntRange(from = 0) int start,
            @IntRange(from = 0) int end, @NonNull TextDirectionHeuristic textDir,
            @NonNull TextPaint paint, int strategy,
            int frequency, @NonNull LineBreakConfig lbConfig) {
        if (0 != start || mText.length() != end)
            return Params.UNUSABLE;
        return mParams.checkResultUsable(paint, textDir, strategy, frequency, lbConfig);
    }

    /** @hide */
    public int findParaIndex(@IntRange(from = 0) int pos) {
        for (int i = 0; i < mParagraphEnds.length; ++i) {
            if (pos < mParagraphEnds[i])
                return i;
        }
        throw new IndexOutOfBoundsException(
            "pos must be less than " + mParagraphEnds[mParagraphEnds.length - 1] + ", gave " + pos);
    }

    private void checkRange(int start, int end) {
        Preconditions.checkArgument(0 <= start && start <= mText.length(), "invalid start offset");
        Preconditions.checkArgument(0 <= end && end <= mText.length(), "invalid end offset");
        Preconditions.checkArgument(start <= end, "start offset can not be larger than end offset");
        final int paraIndex = findParaIndex(start);
        final int paraStart = getParagraphStart(paraIndex);
        final int paraEnd = getParagraphEnd(paraIndex);
        if (start < paraStart || paraEnd < end) {
            throw new IllegalArgumentException("Cannot measured across the paragraph:"
                + "para: (" + paraStart + ", " + paraEnd + "), "
                + "request: (" + start + ", " + end + ")");
        }
    }

    public @FloatRange(from = 0) float getWidth(@IntRange(from = 0) int start, @IntRange(from = 0) int end) {
        if (start == end)
            return 0;
        checkRange(start, end);
        return mParams.getTextPaint().measureText(mText, start, end);
    }

    public void getBounds(@IntRange(from = 0) int start, @IntRange(from = 0) int end, @NonNull Rect bounds) {
        Preconditions.checkNotNull(bounds);
        if (start == end) {
            bounds.set(0, 0, 0, 0);
            return;
        }
        checkRange(start, end);
        mParams.getTextPaint().getTextBounds(mText.toString(), start, end, bounds);
    }

    public void getFontMetricsInt(@IntRange(from = 0) int start, @IntRange(from = 0) int end,
            @NonNull Paint.FontMetricsInt outMetrics) {
        Objects.requireNonNull(outMetrics);
        if (start != end)
            checkRange(start, end);
        mParams.getTextPaint().getFontMetricsInt(outMetrics);
    }

    /** @hide */
    public float getCharWidthAt(@IntRange(from = 0) int offset) {
        Preconditions.checkArgument(0 <= offset && offset < mText.length(), "invalid offset");
        int end = offset + 1;
        if (Character.isHighSurrogate(mText.charAt(offset)) && end < mText.length() && Character.isLowSurrogate(mText.charAt(end)))
            end++;
        return mParams.getTextPaint().measureText(mText, offset, end);
    }

    /** @hide the paragraph table is all this object keeps besides the text */
    public int getMemoryUsage() {
        return mParagraphEnds.length * 4;
    }

    // Spannable overrides: MetricAffectingSpan may not change (AOSP)

    @Override
    public void setSpan(Object what, int start, int end, int flags) {
        if (what instanceof MetricAffectingSpan)
            throw new IllegalArgumentException("MetricAffectingSpan can not be set to PrecomputedText.");
        mText.setSpan(what, start, end, flags);
    }

    @Override
    public void removeSpan(Object what) {
        if (what instanceof MetricAffectingSpan)
            throw new IllegalArgumentException("MetricAffectingSpan can not be removed from PrecomputedText.");
        mText.removeSpan(what);
    }

    @Override
    public <T> T[] getSpans(int start, int end, Class<T> type) {
        return mText.getSpans(start, end, type);
    }

    @Override
    public int getSpanStart(Object tag) {
        return mText.getSpanStart(tag);
    }

    @Override
    public int getSpanEnd(Object tag) {
        return mText.getSpanEnd(tag);
    }

    @Override
    public int getSpanFlags(Object tag) {
        return mText.getSpanFlags(tag);
    }

    @Override
    public int nextSpanTransition(int start, int limit, Class type) {
        return mText.nextSpanTransition(start, limit, type);
    }

    @Override
    public int length() {
        return mText.length();
    }

    @Override
    public char charAt(int index) {
        return mText.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return PrecomputedText.create(mText.subSequence(start, end), mParams);
    }

    @Override
    public String toString() {
        return mText.toString();
    }
}
