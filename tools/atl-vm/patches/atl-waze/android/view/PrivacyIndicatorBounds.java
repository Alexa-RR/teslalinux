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

import android.graphics.Rect;

import java.util.Arrays;
import java.util.Objects;

/**
 * The bounds of the system privacy indicator (camera/microphone dot) for each display rotation
 * (AOSP android.view.PrivacyIndicatorBounds, hidden). teslalinux: no SystemUI status bar draws an
 * indicator over the car screen, so windows carry the empty bounds, which report null.
 *
 * @hide
 */
public class PrivacyIndicatorBounds {

    private final Rect[] mStaticBounds;
    private final int mRotation;

    public PrivacyIndicatorBounds() {
        mStaticBounds = new Rect[4];
        mRotation = 0;
    }

    public PrivacyIndicatorBounds(Rect[] staticBounds, int rotation) {
        mStaticBounds = staticBounds;
        mRotation = rotation;
    }

    /** Returns a new instance with the bounds of the given rotation updated. */
    public PrivacyIndicatorBounds updateStaticBounds(Rect[] staticPositions) {
        return new PrivacyIndicatorBounds(staticPositions, mRotation);
    }

    /** Returns a new instance with the bounds of one rotation updated. */
    public PrivacyIndicatorBounds updateBoundsForRotation(Rect bounds, int rotation) {
        if (rotation >= mStaticBounds.length || rotation < 0) {
            return this;
        }
        Rect[] newBounds = Arrays.copyOf(mStaticBounds, mStaticBounds.length);
        newBounds[rotation] = bounds;
        return updateStaticBounds(newBounds);
    }

    /** Returns a new instance whose bounds are moved into the frame inset by the given amounts. */
    public PrivacyIndicatorBounds inset(int insetLeft, int insetTop, int insetRight,
            int insetBottom) {
        if (insetLeft == 0 && insetTop == 0 && insetRight == 0 && insetBottom == 0) {
            return this;
        }
        Rect[] insetStaticBounds = new Rect[mStaticBounds.length];
        for (int i = 0; i < mStaticBounds.length; i++) {
            insetStaticBounds[i] = insetRect(mStaticBounds[i], insetLeft, insetTop, insetRight,
                    insetBottom);
        }
        return updateStaticBounds(insetStaticBounds);
    }

    private static Rect insetRect(Rect orig, int insetLeft, int insetTop, int insetRight,
            int insetBottom) {
        if (orig == null) {
            return null;
        }
        int left = Math.max(0, orig.left - insetLeft);
        int top = Math.max(0, orig.top - insetTop);
        int right = Math.max(left, orig.right - insetRight);
        int bottom = Math.max(top, orig.bottom - insetBottom);
        return new Rect(left, top, right, bottom);
    }

    /** Returns a new instance for the given display rotation. */
    public PrivacyIndicatorBounds rotate(int rotation) {
        if (rotation == 0 /* Surface.ROTATION_0 */) {
            return this;
        }
        return new PrivacyIndicatorBounds(mStaticBounds, rotation);
    }

    /** Returns a new instance scaled by the given factor. */
    public PrivacyIndicatorBounds scale(float scale) {
        if (scale == 1f) {
            return this;
        }
        Rect[] scaledStaticPos = new Rect[mStaticBounds.length];
        for (int i = 0; i < mStaticBounds.length; i++) {
            scaledStaticPos[i] = scaleRect(mStaticBounds[i], scale);
        }
        return new PrivacyIndicatorBounds(scaledStaticPos, mRotation);
    }

    private static Rect scaleRect(Rect orig, float scale) {
        if (orig == null) {
            return null;
        }
        Rect newRect = new Rect(orig);
        newRect.scale(scale);
        return newRect;
    }

    /** The bounds for the current rotation, or null when none were loaded. */
    public Rect getStaticPrivacyIndicatorBounds() {
        return mStaticBounds[mRotation];
    }

    @Override
    public String toString() {
        return "PrivacyIndicatorBounds {static bounds=" + getStaticPrivacyIndicatorBounds()
                + " rotation=" + mRotation + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PrivacyIndicatorBounds that = (PrivacyIndicatorBounds) o;
        return Arrays.equals(mStaticBounds, that.mStaticBounds) && mRotation == that.mRotation;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mRotation);
        result = 31 * result + Arrays.hashCode(mStaticBounds);
        return result;
    }
}
