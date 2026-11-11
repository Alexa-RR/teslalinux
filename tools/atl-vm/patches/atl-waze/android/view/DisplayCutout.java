/*
 * Copyright 2017 The Android Open Source Project
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

package android.view;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Path;
import android.graphics.Rect;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Represents the area of the display that is not functional for displaying content.
 *
 * <p>{@code DisplayCutout} is immutable.
 *
 * teslalinux: AOSP android14-release DisplayCutout (safe insets, waterfall insets, the bounding
 * rects per side, insetting, the Builder). What is left out is how a device describes its own
 * cutout (the config_mainBuiltInDisplayCutout path spec and its parser): the car screen has none,
 * so Display.getCutout() and WindowInsets.getDisplayCutout() are null, as on a device without a
 * cutout. A cutout path given to the Builder is kept as given.
 */
public final class DisplayCutout {

    private static final Rect ZERO_RECT = new Rect();

    /**
     * An instance where {@link #isEmpty()} returns {@code true}.
     *
     * @hide
     */
    public static final DisplayCutout NO_CUTOUT = new DisplayCutout(
            ZERO_RECT, Insets.NONE, ZERO_RECT, ZERO_RECT, ZERO_RECT, ZERO_RECT, null,
            false /* copyArguments */);

    /**
     * The value for the left bounding rect position.
     * @hide
     */
    public static final int BOUNDS_POSITION_LEFT = 0;

    /**
     * The value for the top bounding rect position.
     * @hide
     */
    public static final int BOUNDS_POSITION_TOP = 1;

    /**
     * The value for the right bounding rect position.
     * @hide
     */
    public static final int BOUNDS_POSITION_RIGHT = 2;

    /**
     * The value for the bottom bounding rect position.
     * @hide
     */
    public static final int BOUNDS_POSITION_BOTTOM = 3;

    /**
     * The number of possible positions at which bounding rects can be placed.
     * @hide
     */
    public static final int BOUNDS_POSITION_LENGTH = 4;

    /** @hide */
    @IntDef(prefix = { "BOUNDS_POSITION_" }, value = {
            BOUNDS_POSITION_LEFT,
            BOUNDS_POSITION_TOP,
            BOUNDS_POSITION_RIGHT,
            BOUNDS_POSITION_BOTTOM
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BoundsPosition {}

    private static class Bounds {
        private final Rect[] mRects;

        private Bounds(Rect left, Rect top, Rect right, Rect bottom, boolean copyArguments) {
            mRects = new Rect[BOUNDS_POSITION_LENGTH];
            mRects[BOUNDS_POSITION_LEFT] = getCopyOrRef(left, copyArguments);
            mRects[BOUNDS_POSITION_TOP] = getCopyOrRef(top, copyArguments);
            mRects[BOUNDS_POSITION_RIGHT] = getCopyOrRef(right, copyArguments);
            mRects[BOUNDS_POSITION_BOTTOM] = getCopyOrRef(bottom, copyArguments);
        }

        private Bounds(Rect[] rects, boolean copyArguments) {
            if (rects.length != BOUNDS_POSITION_LENGTH) {
                throw new IllegalArgumentException(
                        "rects must have exactly 4 elements: rects=" + Arrays.toString(rects));
            }
            if (copyArguments) {
                mRects = new Rect[BOUNDS_POSITION_LENGTH];
                for (int i = 0; i < BOUNDS_POSITION_LENGTH; ++i) {
                    mRects[i] = new Rect(rects[i]);
                }
            } else {
                for (Rect rect : rects) {
                    if (rect == null) {
                        throw new IllegalArgumentException(
                                "rects must have non-null elements: rects="
                                        + Arrays.toString(rects));
                    }
                }
                mRects = rects;
            }
        }

        private boolean isEmpty() {
            for (Rect rect : mRects) {
                if (!rect.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        private Rect getRect(@BoundsPosition int pos) {
            return new Rect(mRects[pos]);
        }

        private Rect[] getRects() {
            Rect[] rects = new Rect[BOUNDS_POSITION_LENGTH];
            for (int i = 0; i < BOUNDS_POSITION_LENGTH; ++i) {
                rects[i] = new Rect(mRects[i]);
            }
            return rects;
        }

        @Override
        public int hashCode() {
            int result = 0;
            for (Rect rect : mRects) {
                result = result * 48271 + rect.hashCode();
            }
            return result;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (o == this) {
                return true;
            }
            if (o instanceof Bounds) {
                Bounds b = (Bounds) o;
                return Arrays.deepEquals(mRects, b.mRects);
            }
            return false;
        }

        @Override
        public String toString() {
            return "Bounds=" + Arrays.toString(mRects);
        }
    }

    private final Rect mSafeInsets;
    @NonNull
    private final Insets mWaterfallInsets;
    private final Bounds mBounds;
    /** the outline of the cutout areas in display coordinates, null when unknown */
    @Nullable
    private final Path mCutoutPath;

    /**
     * Creates a DisplayCutout instance.
     *
     * <p>Note that this is only useful for tests. For production code, developers should always
     * use a {@link DisplayCutout} obtained from the system.</p>
     *
     * @param safeInsets the insets from each edge which avoid the display cutout as returned by
     *                   {@link #getSafeInsetTop()} etc.
     * @param boundLeft the left bounding rect of the display cutout in pixels. If null is passed,
     *                  it's treated as an empty rectangle (0,0)-(0,0).
     * @param boundTop the top bounding rect of the display cutout in pixels.  If null is passed,
     *                  it's treated as an empty rectangle (0,0)-(0,0).
     * @param boundRight the right bounding rect of the display cutout in pixels.  If null is
     *                  passed, it's treated as an empty rectangle (0,0)-(0,0).
     * @param boundBottom the bottom bounding rect of the display cutout in pixels.  If null is
     *                   passed, it's treated as an empty rectangle (0,0)-(0,0).
     */
    public DisplayCutout(@NonNull Insets safeInsets, @Nullable Rect boundLeft,
            @Nullable Rect boundTop, @Nullable Rect boundRight, @Nullable Rect boundBottom) {
        this(safeInsets.toRect(), Insets.NONE, boundLeft, boundTop, boundRight, boundBottom, null,
                true);
    }

    /**
     * Creates a DisplayCutout instance.
     *
     * @param waterfallInsets the insets for the curved areas in waterfall display.
     * @see #DisplayCutout(Insets, Rect, Rect, Rect, Rect)
     */
    public DisplayCutout(@NonNull Insets safeInsets, @Nullable Rect boundLeft,
            @Nullable Rect boundTop, @Nullable Rect boundRight, @Nullable Rect boundBottom,
            @NonNull Insets waterfallInsets) {
        this(safeInsets.toRect(), waterfallInsets, boundLeft, boundTop, boundRight, boundBottom,
                null, true);
    }

    /**
     * Creates a DisplayCutout instance.
     *
     * @param safeInsets the insets from each edge which avoid the display cutout as returned by
     *                   {@link #getSafeInsetTop()} etc.
     * @param boundingRects the bounding rects of the display cutouts as returned by
     *               {@link #getBoundingRects()} ()}.
     * @deprecated Use {@link DisplayCutout#DisplayCutout(Insets, Rect, Rect, Rect, Rect)} instead.
     */
    @Deprecated
    public DisplayCutout(@Nullable Rect safeInsets, @Nullable List<Rect> boundingRects) {
        this(safeInsets, Insets.NONE, extractBoundsFromList(safeInsets, boundingRects), null,
                true /* copyArguments */);
    }

    private DisplayCutout(Rect safeInsets, Insets waterfallInsets, Rect boundLeft, Rect boundTop,
            Rect boundRight, Rect boundBottom, Path cutoutPath, boolean copyArguments) {
        mSafeInsets = getCopyOrRef(safeInsets, copyArguments);
        mWaterfallInsets = waterfallInsets == null ? Insets.NONE : waterfallInsets;
        mBounds = new Bounds(boundLeft, boundTop, boundRight, boundBottom, copyArguments);
        mCutoutPath = cutoutPath;
    }

    private DisplayCutout(Rect safeInsets, Insets waterfallInsets, Rect[] bounds,
            Path cutoutPath, boolean copyArguments) {
        mSafeInsets = getCopyOrRef(safeInsets, copyArguments);
        mWaterfallInsets = waterfallInsets == null ? Insets.NONE : waterfallInsets;
        mBounds = new Bounds(bounds, copyArguments);
        mCutoutPath = cutoutPath;
    }

    private DisplayCutout(Rect safeInsets, Insets waterfallInsets, Bounds bounds,
            Path cutoutPath) {
        mSafeInsets = safeInsets;
        mWaterfallInsets = waterfallInsets == null ? Insets.NONE : waterfallInsets;
        mBounds = bounds;
        mCutoutPath = cutoutPath;
    }

    private static Rect getCopyOrRef(Rect r, boolean copyArguments) {
        if (r == null) {
            return ZERO_RECT;
        } else if (copyArguments) {
            return new Rect(r);
        } else {
            return r;
        }
    }

    /**
     * Returns the insets representing the curved areas of a waterfall display.
     *
     * @return the insets for the curved areas of a waterfall display in pixels or {@code
     * Insets.NONE} if there are no curved areas or they don't overlap with the window.
     */
    public @NonNull Insets getWaterfallInsets() {
        return mWaterfallInsets;
    }

    /**
     * Find the position of the bounding rect, and create an array of Rect whose index represents
     * the position (= BoundsPosition).
     *
     * @hide
     */
    public static Rect[] extractBoundsFromList(Rect safeInsets, List<Rect> boundingRects) {
        Rect[] sortedBounds = new Rect[BOUNDS_POSITION_LENGTH];
        for (int i = 0; i < sortedBounds.length; ++i) {
            sortedBounds[i] = ZERO_RECT;
        }
        if (safeInsets != null && boundingRects != null) {
            // There is at most one non-functional area per short edge of the device, but none
            // on the long edges, so either a) safeInsets.top and safeInsets.bottom is 0, or
            // b) safeInsets.left and safeInset.right is 0.
            final boolean topBottomInset = safeInsets.top > 0 || safeInsets.bottom > 0;
            for (Rect bound : boundingRects) {
                if (topBottomInset) {
                    if (bound.top == 0) {
                        sortedBounds[BOUNDS_POSITION_TOP] = bound;
                    } else {
                        sortedBounds[BOUNDS_POSITION_BOTTOM] = bound;
                    }
                } else {
                    if (bound.left == 0) {
                        sortedBounds[BOUNDS_POSITION_LEFT] = bound;
                    } else {
                        sortedBounds[BOUNDS_POSITION_RIGHT] = bound;
                    }
                }
            }
        }
        return sortedBounds;
    }

    /**
     * Returns true if there is no cutout, i.e. the bounds are empty.
     *
     * @hide
     */
    public boolean isBoundsEmpty() {
        return mBounds.isEmpty();
    }

    /**
     * Returns true if the safe insets are empty (and therefore the current view does not
     * overlap with the cutout or cutout area).
     *
     * @hide
     */
    public boolean isEmpty() {
        return mSafeInsets.equals(ZERO_RECT);
    }

    /** Returns the inset from the top which avoids the display cutout in pixels. */
    public int getSafeInsetTop() {
        return mSafeInsets.top;
    }

    /** Returns the inset from the bottom which avoids the display cutout in pixels. */
    public int getSafeInsetBottom() {
        return mSafeInsets.bottom;
    }

    /** Returns the inset from the left which avoids the display cutout in pixels. */
    public int getSafeInsetLeft() {
        return mSafeInsets.left;
    }

    /** Returns the inset from the right which avoids the display cutout in pixels. */
    public int getSafeInsetRight() {
        return mSafeInsets.right;
    }

    /**
     * Returns the safe insets in a rect in pixel units.
     *
     * @return a rect which is set to the safe insets.
     * @hide
     */
    public Rect getSafeInsets() {
        return new Rect(mSafeInsets);
    }

    /**
     * Returns a list of {@code Rect}s, each of which is the bounding rectangle for a non-functional
     * area on the display.
     *
     * @return a list of bounding {@code Rect}s, one for each display cutout area. No empty Rect is
     * returned.
     */
    @NonNull
    public List<Rect> getBoundingRects() {
        List<Rect> result = new ArrayList<>();
        for (Rect bound : getBoundingRectsAll()) {
            if (!bound.isEmpty()) {
                result.add(new Rect(bound));
            }
        }
        return result;
    }

    /**
     * Returns an array of the bounding rects, indexed by BoundsPosition; ZERO_RECT where there
     * is no cutout.
     *
     * @hide
     */
    public Rect[] getBoundingRectsAll() {
        return mBounds.getRects();
    }

    /** Returns the bounding rectangle of the cutout on the left of the screen, or an empty one. */
    public @NonNull Rect getBoundingRectLeft() {
        return mBounds.getRect(BOUNDS_POSITION_LEFT);
    }

    /** Returns the bounding rectangle of the cutout on the top of the screen, or an empty one. */
    public @NonNull Rect getBoundingRectTop() {
        return mBounds.getRect(BOUNDS_POSITION_TOP);
    }

    /** Returns the bounding rectangle of the cutout on the right of the screen, or an empty one. */
    public @NonNull Rect getBoundingRectRight() {
        return mBounds.getRect(BOUNDS_POSITION_RIGHT);
    }

    /** Returns the bounding rectangle of the cutout on the bottom of the screen, or an empty one. */
    public @NonNull Rect getBoundingRectBottom() {
        return mBounds.getRect(BOUNDS_POSITION_BOTTOM);
    }

    /**
     * Returns a {@link Path} that contains the cutout paths of all sides on the display.
     *
     * @return a {@link Path} contains all the cutout paths based on display coordinate. Returns
     * null if there is no cutout on the display.
     */
    public @Nullable Path getCutoutPath() {
        if (mCutoutPath == null || mCutoutPath.isEmpty()) {
            return null;
        }
        return new Path(mCutoutPath);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 48271 * result + mSafeInsets.hashCode();
        result = 48271 * result + mBounds.hashCode();
        result = 48271 * result + mWaterfallInsets.hashCode();
        result = 48271 * result + (mCutoutPath == null ? 0 : 1);
        return result;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof DisplayCutout) {
            DisplayCutout c = (DisplayCutout) o;
            return mSafeInsets.equals(c.mSafeInsets) && mBounds.equals(c.mBounds)
                    && mWaterfallInsets.equals(c.mWaterfallInsets)
                    && mCutoutPath == c.mCutoutPath;
        }
        return false;
    }

    @Override
    public String toString() {
        return "DisplayCutout{insets=" + mSafeInsets
                + " waterfall=" + mWaterfallInsets
                + " boundingRect={" + mBounds + "}"
                + " cutoutPath=" + (mCutoutPath != null)
                + "}";
    }

    /**
     * Insets the reference frame of the cutout in the given directions.
     *
     * @return a copy of this instance which has been inset
     * @hide
     */
    public DisplayCutout inset(int insetLeft, int insetTop, int insetRight, int insetBottom) {
        if (insetLeft == 0 && insetTop == 0 && insetRight == 0 && insetBottom == 0
                || (isBoundsEmpty() && mWaterfallInsets.equals(Insets.NONE))) {
            return this;
        }

        Rect safeInsets = insetInsets(insetLeft, insetTop, insetRight, insetBottom,
                new Rect(mSafeInsets));

        // If we are not cutting off part of the cutout by insetting it on bottom/right, and we also
        // don't move it around, we can avoid the allocation and copy of the instance.
        if (insetLeft == 0 && insetTop == 0 && mSafeInsets.equals(safeInsets)) {
            return this;
        }

        Rect waterfallInsets = insetInsets(insetLeft, insetTop, insetRight, insetBottom,
                mWaterfallInsets.toRect());

        Rect[] bounds = mBounds.getRects();
        for (int i = 0; i < bounds.length; ++i) {
            if (!bounds[i].equals(ZERO_RECT)) {
                bounds[i].offset(-insetLeft, -insetTop);
            }
        }

        return new DisplayCutout(safeInsets, Insets.of(waterfallInsets), bounds,
                mCutoutPath, false /* copyArguments */);
    }

    private Rect insetInsets(int insetLeft, int insetTop, int insetRight, int insetBottom,
            Rect insets) {
        // Note: it's not really well defined what happens when the inset is negative, because we
        // don't know if the safe inset needs to expand in general.
        if (insetTop > 0 || insets.top > 0) {
            insets.top = atLeastZero(insets.top - insetTop);
        }
        if (insetBottom > 0 || insets.bottom > 0) {
            insets.bottom = atLeastZero(insets.bottom - insetBottom);
        }
        if (insetLeft > 0 || insets.left > 0) {
            insets.left = atLeastZero(insets.left - insetLeft);
        }
        if (insetRight > 0 || insets.right > 0) {
            insets.right = atLeastZero(insets.right - insetRight);
        }
        return insets;
    }

    /**
     * Returns a copy of this instance with the safe insets replaced with the parameter.
     *
     * @hide
     */
    public DisplayCutout replaceSafeInsets(Rect safeInsets) {
        return new DisplayCutout(new Rect(safeInsets), mWaterfallInsets, mBounds, mCutoutPath);
    }

    private static int atLeastZero(int value) {
        return value < 0 ? 0 : value;
    }

    /**
     * Creates an instance from a bounding rect.
     *
     * @hide
     */
    public static DisplayCutout fromBoundingRect(
            int left, int top, int right, int bottom, @BoundsPosition int pos) {
        Rect[] bounds = new Rect[BOUNDS_POSITION_LENGTH];
        for (int i = 0; i < BOUNDS_POSITION_LENGTH; ++i) {
            bounds[i] = (pos == i) ? new Rect(left, top, right, bottom) : new Rect();
        }
        return new DisplayCutout(ZERO_RECT, Insets.NONE, bounds, null, false /* copyArguments */);
    }

    /**
     * Creates an instance from bounds.
     *
     * @hide
     */
    public static DisplayCutout fromBounds(Rect[] bounds) {
        return new DisplayCutout(ZERO_RECT, Insets.NONE, bounds, null, false /* copyArguments */);
    }

    /**
     * Compute the insets derived from a cutout. This is usually used to populate the safe-insets
     * of the cutout via {@link #replaceSafeInsets}.
     * @hide
     */
    public static Rect computeSafeInsets(int displayW, int displayH, DisplayCutout cutout) {
        if (displayW == displayH) {
            throw new UnsupportedOperationException("not implemented: display=" + displayW + "x"
                    + displayH + " cutout=" + cutout);
        }

        int leftInset = Math.max(cutout.getWaterfallInsets().left, findCutoutInsetForSide(
                displayW, displayH, cutout.getBoundingRectLeft(), Gravity.LEFT));
        int topInset = Math.max(cutout.getWaterfallInsets().top, findCutoutInsetForSide(
                displayW, displayH, cutout.getBoundingRectTop(), Gravity.TOP));
        int rightInset = Math.max(cutout.getWaterfallInsets().right, findCutoutInsetForSide(
                displayW, displayH, cutout.getBoundingRectRight(), Gravity.RIGHT));
        int bottomInset = Math.max(cutout.getWaterfallInsets().bottom, findCutoutInsetForSide(
                displayW, displayH, cutout.getBoundingRectBottom(), Gravity.BOTTOM));

        return new Rect(leftInset, topInset, rightInset, bottomInset);
    }

    private static int findCutoutInsetForSide(int displayW, int displayH, Rect boundingRect,
            int gravity) {
        if (boundingRect.isEmpty()) {
            return 0;
        }

        int inset = 0;
        switch (gravity) {
            case Gravity.TOP:
                return Math.max(inset, boundingRect.bottom);
            case Gravity.BOTTOM:
                return Math.max(inset, displayH - boundingRect.top);
            case Gravity.LEFT:
                return Math.max(inset, boundingRect.right);
            case Gravity.RIGHT:
                return Math.max(inset, displayW - boundingRect.left);
            default:
                throw new IllegalArgumentException("unknown gravity: " + gravity);
        }
    }

    /**
     * A Builder class to construct a DisplayCutout instance.
     *
     * <p>Note that this is only for tests purpose. For production code, developers should always
     * use a {@link DisplayCutout} obtained from the system.</p>
     */
    public static final class Builder {
        private Insets mSafeInsets = Insets.NONE;
        private Insets mWaterfallInsets = Insets.NONE;
        private Path mCutoutPath;
        private final Rect mBoundingRectLeft = new Rect();
        private final Rect mBoundingRectTop = new Rect();
        private final Rect mBoundingRectRight = new Rect();
        private final Rect mBoundingRectBottom = new Rect();

        /**
         * Begin building a DisplayCutout.
         */
        public Builder() {
        }

        /**
         * Construct a new {@link DisplayCutout} with the set parameters.
         */
        @NonNull
        public DisplayCutout build() {
            return new DisplayCutout(mSafeInsets.toRect(), mWaterfallInsets, mBoundingRectLeft,
                    mBoundingRectTop, mBoundingRectRight, mBoundingRectBottom,
                    mCutoutPath != null ? new Path(mCutoutPath) : null, false);
        }

        /**
         * Set the safe insets. If not set, the default value is {@link Insets#NONE}.
         */
        @NonNull
        public Builder setSafeInsets(@NonNull Insets safeInsets) {
            mSafeInsets = safeInsets;
            return this;
        }

        /**
         * Set the waterfall insets of the DisplayCutout. If not set, the default value is
         * {@link Insets#NONE}
         */
        @NonNull
        public Builder setWaterfallInsets(@NonNull Insets waterfallInsets) {
            mWaterfallInsets = waterfallInsets;
            return this;
        }

        /**
         * Set a bounding rectangle for a non-functional area on the display which is located on
         * the left of the screen. If not set, the default value is an empty rectangle.
         */
        @NonNull
        public Builder setBoundingRectLeft(@NonNull Rect boundingRectLeft) {
            mBoundingRectLeft.set(boundingRectLeft);
            return this;
        }

        /**
         * Set a bounding rectangle for a non-functional area on the display which is located on
         * the top of the screen. If not set, the default value is an empty rectangle.
         */
        @NonNull
        public Builder setBoundingRectTop(@NonNull Rect boundingRectTop) {
            mBoundingRectTop.set(boundingRectTop);
            return this;
        }

        /**
         * Set a bounding rectangle for a non-functional area on the display which is located on
         * the right of the screen. If not set, the default value is an empty rectangle.
         */
        @NonNull
        public Builder setBoundingRectRight(@NonNull Rect boundingRectRight) {
            mBoundingRectRight.set(boundingRectRight);
            return this;
        }

        /**
         * Set a bounding rectangle for a non-functional area on the display which is located on
         * the bottom of the screen. If not set, the default value is an empty rectangle.
         */
        @NonNull
        public Builder setBoundingRectBottom(@NonNull Rect boundingRectBottom) {
            mBoundingRectBottom.set(boundingRectBottom);
            return this;
        }

        /**
         * Set the cutout {@link Path}.
         *
         * Note that not support creating/testing left or right cutout with a given Path.
         */
        @NonNull
        public Builder setCutoutPath(@NonNull Path cutoutPath) {
            mCutoutPath = cutoutPath;
            return this;
        }
    }
}
