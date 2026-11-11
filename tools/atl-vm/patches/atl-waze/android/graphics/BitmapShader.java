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

package android.graphics;

/**
 * Shader used to draw a bitmap as a texture. The bitmap can be repeated or mirrored by setting
 * the tiling mode. teslalinux: drawn by android.atl.GskCanvas with GSK texture and repeat nodes.
 */
public class BitmapShader extends Shader {
	/** The paint's filter flag decides (AOSP API 33). */
	public static final int FILTER_MODE_DEFAULT = 0;
	/** Nearest-neighbour sampling. */
	public static final int FILTER_MODE_NEAREST = 1;
	/** Bilinear sampling. */
	public static final int FILTER_MODE_LINEAR = 2;

	Bitmap bitmap;
	private final TileMode mTileX;
	private final TileMode mTileY;
	private int mFilterMode = FILTER_MODE_DEFAULT;
	private int mMaxAnisotropy;

	/**
	 * Call this to create a new shader that will draw with a bitmap.
	 *
	 * @param bitmap The bitmap to use inside the shader
	 * @param tileX The tiling mode for x to draw the bitmap in.
	 * @param tileY The tiling mode for y to draw the bitmap in.
	 */
	public BitmapShader(Bitmap bitmap, TileMode tileX, TileMode tileY) {
		if (bitmap == null) {
			throw new IllegalArgumentException("Bitmap must be non-null");
		}
		this.bitmap = bitmap;
		mTileX = tileX != null ? tileX : TileMode.CLAMP;
		mTileY = tileY != null ? tileY : TileMode.CLAMP;
	}

	/** API 33: the sampling filter (FILTER_MODE_DEFAULT, _NEAREST or _LINEAR). */
	public int getFilterMode() {
		return mFilterMode;
	}

	/** API 33: sets the sampling filter. */
	public void setFilterMode(int mode) {
		if (mode != FILTER_MODE_DEFAULT && mode != FILTER_MODE_NEAREST && mode != FILTER_MODE_LINEAR)
			throw new IllegalArgumentException("Invalid filter mode " + mode);
		mFilterMode = mode;
	}

	/**
	 * API 34: anisotropic filtering. GSK samples textures with nearest, linear or trilinear
	 * (mipmapped) filtering; any anisotropy above 0 selects linear sampling here.
	 */
	public void setMaxAnisotropy(int maxAnisotropy) {
		if (maxAnisotropy < 0)
			throw new IllegalArgumentException("maxAnisotropy must be > 0");
		mMaxAnisotropy = maxAnisotropy;
		if (maxAnisotropy > 0)
			mFilterMode = FILTER_MODE_LINEAR;
	}

	/** API 34. */
	public int getMaxAnisotropy() {
		return mMaxAnisotropy;
	}

	public Bitmap tlBitmap() { return bitmap; }
	public TileMode tlTileX() { return mTileX; }
	public TileMode tlTileY() { return mTileY; }
}
