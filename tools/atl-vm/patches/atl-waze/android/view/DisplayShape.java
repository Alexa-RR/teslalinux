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

package android.view;

import android.annotation.NonNull;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A class representing the shape of a display. It provides a {@link Path} of the display shape of
 * the display shape.
 *
 * {@link DisplayShape} is immutable.
 *
 * teslalinux: AOSP API 34 class. AOSP builds the path from the device's display-shape spec (an
 * SVG path in the config); a device without one gets the default shape, the display rectangle
 * (or its circle on a round screen), which is what the car screen is. The rotation, scale and
 * window offset are applied the AOSP way.
 */
public final class DisplayShape implements Parcelable {

    /* the Surface.ROTATION_* values (AOSP android.view.Surface) */
    private static final int ROTATION_0 = 0;
    private static final int ROTATION_90 = 1;
    private static final int ROTATION_180 = 2;
    private static final int ROTATION_270 = 3;

    /** @hide */
    public static final DisplayShape NONE = new DisplayShape(false, 0, 0, 1f, ROTATION_0, 0, 0, 1f);

    private final boolean mIsRound;
    private final int mDisplayWidth;
    private final int mDisplayHeight;
    private final float mPhysicalPixelDisplaySizeRatio;
    private final int mRotation;
    private final int mOffsetX;
    private final int mOffsetY;
    private final float mScale;

    private DisplayShape(boolean isRound, int displayWidth, int displayHeight,
            float physicalPixelDisplaySizeRatio, int rotation, int offsetX, int offsetY,
            float scale) {
        mIsRound = isRound;
        mDisplayWidth = displayWidth;
        mDisplayHeight = displayHeight;
        mPhysicalPixelDisplaySizeRatio = physicalPixelDisplaySizeRatio;
        mRotation = rotation;
        mOffsetX = offsetX;
        mOffsetY = offsetY;
        mScale = scale;
    }

    /**
     * @hide the default shape of a display without a shape spec: its rectangle, or the inscribed
     * circle on a round display
     */
    public static DisplayShape createDefaultDisplayShape(int displayWidth, int displayHeight,
            boolean isScreenRound) {
        return new DisplayShape(isScreenRound, displayWidth, displayHeight, 1f,
                ROTATION_0, 0, 0, 1f);
    }

    /**
     * @hide Returns a {@link DisplayShape} that has rotated to the given rotation.
     */
    public DisplayShape setRotation(int rotation) {
        return new DisplayShape(mIsRound, mDisplayWidth, mDisplayHeight,
                mPhysicalPixelDisplaySizeRatio, rotation, mOffsetX, mOffsetY, mScale);
    }

    /**
     * @hide Returns a {@link DisplayShape} that has the offset in the window's coordinates.
     */
    public DisplayShape setOffset(int offsetX, int offsetY) {
        return new DisplayShape(mIsRound, mDisplayWidth, mDisplayHeight,
                mPhysicalPixelDisplaySizeRatio, mRotation, offsetX, offsetY, mScale);
    }

    /**
     * @hide Returns a {@link DisplayShape} scaled by the given factor.
     */
    public DisplayShape setScale(float scale) {
        return new DisplayShape(mIsRound, mDisplayWidth, mDisplayHeight,
                mPhysicalPixelDisplaySizeRatio, mRotation, mOffsetX, mOffsetY, scale);
    }

    /**
     * Returns a {@link Path} of the display shape.
     *
     * @return a {@link Path} of the display shape.
     */
    @NonNull
    public Path getPath() {
        final Path path = new Path();
        if (mDisplayWidth <= 0 || mDisplayHeight <= 0) {
            return path;
        }
        final RectF bounds = new RectF(0, 0, mDisplayWidth, mDisplayHeight);
        if (mIsRound) {
            path.addOval(bounds, Path.Direction.CW);
        } else {
            path.addRect(bounds, Path.Direction.CW);
        }
        final Matrix matrix = new Matrix();
        if (mRotation != ROTATION_0) {
            // AOSP RotationUtils.transformPhysicalToLogicalCoordinates
            switch (mRotation) {
                case ROTATION_90:
                    matrix.setRotate(270);
                    matrix.postTranslate(0, mDisplayWidth);
                    break;
                case ROTATION_180:
                    matrix.setRotate(180);
                    matrix.postTranslate(mDisplayWidth, mDisplayHeight);
                    break;
                case ROTATION_270:
                    matrix.setRotate(90);
                    matrix.postTranslate(mDisplayHeight, 0);
                    break;
            }
        }
        if (mScale != 1f) {
            matrix.postScale(mScale, mScale);
        }
        if (mOffsetX != 0 || mOffsetY != 0) {
            matrix.postTranslate(mOffsetX, mOffsetY);
        }
        if (!matrix.isIdentity()) {
            path.transform(matrix);
        }
        return path;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof DisplayShape) {
            DisplayShape ds = (DisplayShape) o;
            return mIsRound == ds.mIsRound
                    && mDisplayWidth == ds.mDisplayWidth
                    && mDisplayHeight == ds.mDisplayHeight
                    && mPhysicalPixelDisplaySizeRatio == ds.mPhysicalPixelDisplaySizeRatio
                    && mRotation == ds.mRotation
                    && mOffsetX == ds.mOffsetX
                    && mOffsetY == ds.mOffsetY
                    && mScale == ds.mScale;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIsRound, mDisplayWidth, mDisplayHeight,
                mPhysicalPixelDisplaySizeRatio, mRotation, mOffsetX, mOffsetY, mScale);
    }

    @Override
    public String toString() {
        return "DisplayShape{"
                + (mIsRound ? "round" : "rect")
                + " displayWidth=" + mDisplayWidth
                + " displayHeight=" + mDisplayHeight
                + " physicalPixelDisplaySizeRatio=" + mPhysicalPixelDisplaySizeRatio
                + " rotation=" + mRotation
                + " offsetX=" + mOffsetX
                + " offsetY=" + mOffsetY
                + " scale=" + mScale + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mIsRound);
        dest.writeInt(mDisplayWidth);
        dest.writeInt(mDisplayHeight);
        dest.writeFloat(mPhysicalPixelDisplaySizeRatio);
        dest.writeInt(mRotation);
        dest.writeInt(mOffsetX);
        dest.writeInt(mOffsetY);
        dest.writeFloat(mScale);
    }

    public static final @NonNull Creator<DisplayShape> CREATOR = new Creator<DisplayShape>() {
        @Override
        public DisplayShape createFromParcel(Parcel in) {
            final boolean isRound = in.readBoolean();
            final int displayWidth = in.readInt();
            final int displayHeight = in.readInt();
            final float ratio = in.readFloat();
            final int rotation = in.readInt();
            final int offsetX = in.readInt();
            final int offsetY = in.readInt();
            final float scale = in.readFloat();
            return new DisplayShape(isRound, displayWidth, displayHeight, ratio, rotation,
                    offsetX, offsetY, scale);
        }

        @Override
        public DisplayShape[] newArray(int size) {
            return new DisplayShape[size];
        }
    };
}
