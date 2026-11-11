/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.IntRange;
import android.annotation.NonNull;

/**
 * Implementation of {@code SegmentFinder} using grapheme clusters as the text segment. Whitespace
 * characters are included as segments.
 *
 * <p>For example, the text "a pot" would be divided into five text segments: "a", " ", "p", "o",
 * "t".
 *
 * @see <a href="https://unicode.org/reports/tr29/#Grapheme_Cluster_Boundaries">Unicode Text
 *     Segmentation - Grapheme Cluster Boundaries</a>
 */
public class GraphemeClusterSegmentFinder extends SegmentFinder {
    private final boolean[] mIsGraphemeBreak;

    /**
     * Constructs a GraphemeClusterSegmentFinder instance for the specified text which uses the
     * provided TextPaint to determine grapheme cluster boundaries.
     *
     * teslalinux: AOSP marks the breaks with minikin's GraphemeBreak (UAX #29 refined by which
     * clusters the font draws as one glyph); here ICU's character break iterator gives the UAX #29
     * extended grapheme cluster boundaries, which Pango also uses for cursor positions.
     *
     * @param text text to be segmented
     * @param textPaint TextPaint used to draw the text
     */
    public GraphemeClusterSegmentFinder(
            @NonNull CharSequence text, @NonNull TextPaint textPaint) {
        mIsGraphemeBreak = new boolean[text.length()];
        final android.icu.text.BreakIterator it = android.icu.text.BreakIterator.getCharacterInstance();
        it.setText(new CharSequenceCharacterIterator(text, 0, text.length()));
        for (int b = it.first(); b != android.icu.text.BreakIterator.DONE; b = it.next()) {
            if (b < mIsGraphemeBreak.length)
                mIsGraphemeBreak[b] = true;
        }
    }

    private int previousBoundary(@IntRange(from = 0) int offset) {
        if (offset <= 0) return DONE;
        do {
            --offset;
        } while (offset > 0 && !mIsGraphemeBreak[offset]);
        return offset;
    }

    private int nextBoundary(@IntRange(from = 0) int offset) {
        if (offset >= mIsGraphemeBreak.length) return DONE;
        do {
            ++offset;
        } while (offset < mIsGraphemeBreak.length && !mIsGraphemeBreak[offset]);
        return offset;
    }

    @Override
    public int previousStartBoundary(@IntRange(from = 0) int offset) {
        return previousBoundary(offset);
    }

    @Override
    public int previousEndBoundary(@IntRange(from = 0) int offset) {
        if (offset == 0) return DONE;
        int boundary = previousBoundary(offset);
        // Check that there is another cursor position before, otherwise this is not a valid
        // end boundary.
        if (boundary == DONE || previousBoundary(boundary) == DONE) {
            return DONE;
        }
        return boundary;
    }

    @Override
    public int nextStartBoundary(@IntRange(from = 0) int offset) {
        if (offset == mIsGraphemeBreak.length) return DONE;
        int boundary = nextBoundary(offset);
        // Check that there is another cursor position after, otherwise this is not a valid
        // start boundary.
        if (boundary == DONE || nextBoundary(boundary) == DONE) {
            return DONE;
        }
        return boundary;
    }

    @Override
    public int nextEndBoundary(@IntRange(from = 0) int offset) {
        return nextBoundary(offset);
    }
}
