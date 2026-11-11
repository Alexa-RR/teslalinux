/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.view.RoundedCorner.POSITION_BOTTOM_LEFT;
import static android.view.RoundedCorner.POSITION_BOTTOM_RIGHT;
import static android.view.RoundedCorner.POSITION_TOP_LEFT;
import static android.view.RoundedCorner.POSITION_TOP_RIGHT;

import android.graphics.Point;

import java.util.Arrays;

/**
 * A class to create and manage the rounded corners of a display (AOSP android.view.RoundedCorners,
 * hidden). teslalinux: the part WindowInsets uses; the resource loading for a device's corner
 * radii is not here because the car screen has square corners (Display.getRoundedCorner).
 *
 * @hide
 */
public class RoundedCorners {

    public static final RoundedCorners NO_ROUNDED_CORNERS = new RoundedCorners(
            new RoundedCorner(POSITION_TOP_LEFT), new RoundedCorner(POSITION_TOP_RIGHT),
            new RoundedCorner(POSITION_BOTTOM_RIGHT), new RoundedCorner(POSITION_BOTTOM_LEFT));

    static final int ROUNDED_CORNER_POSITION_LENGTH = 4;

    final RoundedCorner[] mRoundedCorners;

    public RoundedCorners(RoundedCorner[] roundedCorners) {
        mRoundedCorners = roundedCorners;
    }

    public RoundedCorners(RoundedCorner topLeft, RoundedCorner topRight, RoundedCorner bottomRight,
            RoundedCorner bottomLeft) {
        mRoundedCorners = new RoundedCorner[ROUNDED_CORNER_POSITION_LENGTH];
        mRoundedCorners[POSITION_TOP_LEFT] = topLeft;
        mRoundedCorners[POSITION_TOP_RIGHT] = topRight;
        mRoundedCorners[POSITION_BOTTOM_RIGHT] = bottomRight;
        mRoundedCorners[POSITION_BOTTOM_LEFT] = bottomLeft;
    }

    public RoundedCorners(RoundedCorners roundedCorners) {
        mRoundedCorners = new RoundedCorner[ROUNDED_CORNER_POSITION_LENGTH];
        for (int i = 0; i < ROUNDED_CORNER_POSITION_LENGTH; ++i) {
            mRoundedCorners[i] = new RoundedCorner(roundedCorners.mRoundedCorners[i]);
        }
    }

    /**
     * Insets the reference frame of the rounded corners.
     *
     * @return a copy of this instance which has been inset
     */
    public RoundedCorners inset(int insetLeft, int insetTop, int insetRight, int insetBottom) {
        final RoundedCorner[] roundedCorners = new RoundedCorner[ROUNDED_CORNER_POSITION_LENGTH];
        for (int i = 0; i < ROUNDED_CORNER_POSITION_LENGTH; i++) {
            roundedCorners[i] = insetRoundedCorner(i, insetLeft, insetTop, insetRight, insetBottom);
        }
        return new RoundedCorners(roundedCorners);
    }

    private RoundedCorner insetRoundedCorner(int position, int insetLeft, int insetTop,
            int insetRight, int insetBottom) {
        if (mRoundedCorners[position].isEmpty()) {
            return new RoundedCorner(position);
        }

        final int radius = mRoundedCorners[position].getRadius();
        final Point center = mRoundedCorners[position].getCenter();
        boolean hasRoundedCorner;
        switch (position) {
            case POSITION_TOP_LEFT:
                hasRoundedCorner = radius > insetTop && radius > insetLeft;
                break;
            case POSITION_TOP_RIGHT:
                hasRoundedCorner = radius > insetTop && radius > insetRight;
                break;
            case POSITION_BOTTOM_RIGHT:
                hasRoundedCorner = radius > insetBottom && radius > insetRight;
                break;
            case POSITION_BOTTOM_LEFT:
                hasRoundedCorner = radius > insetBottom && radius > insetLeft;
                break;
            default:
                throw new IllegalArgumentException(
                        "The position is not one of the RoundedCornerPosition =" + position);
        }
        return new RoundedCorner(
                position, radius,
                hasRoundedCorner ? center.x - insetLeft : 0,
                hasRoundedCorner ? center.y - insetTop : 0);
    }

    /**
     * Returns the {@link RoundedCorner} of the given position if there is one.
     *
     * @param position the position of the rounded corner on the display.
     * @return the rounded corner of the given position. Returns {@code null} if
     * {@link RoundedCorner#isEmpty()} is {@code true}.
     */
    public RoundedCorner getRoundedCorner(@RoundedCorner.Position int position) {
        return mRoundedCorners[position].isEmpty()
                ? null : new RoundedCorner(mRoundedCorners[position]);
    }

    /**
     * Sets the rounded corner of given position.
     *
     * @param position the position of this rounded corner
     * @param roundedCorner the rounded corner or null if there is none
     */
    public void setRoundedCorner(@RoundedCorner.Position int position,
            RoundedCorner roundedCorner) {
        mRoundedCorners[position] = roundedCorner == null
                ? new RoundedCorner(position) : roundedCorner;
    }

    /**
     * Returns an array of {@link RoundedCorner}s. Ordered by the position from the top-left
     * corner to the bottom-left corner clockwise.
     *
     * @return an array of rounded corners.
     */
    public RoundedCorner[] getAllRoundedCorners() {
        return mRoundedCorners;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof RoundedCorners) {
            RoundedCorners r = (RoundedCorners) o;
            return Arrays.deepEquals(mRoundedCorners, r.mRoundedCorners);
        }
        return false;
    }

    @Override
    public int hashCode() {
        int result = 0;
        for (RoundedCorner roundedCorner : mRoundedCorners) {
            result = result * 31 + roundedCorner.hashCode();
        }
        return result;
    }

    @Override
    public String toString() {
        return "RoundedCorners{" + Arrays.toString(mRoundedCorners) + "}";
    }
}
