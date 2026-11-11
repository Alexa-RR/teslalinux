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

package android.graphics.drawable;

import android.atl.GskCanvas;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Xfermode;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.LayoutDirection;
import android.util.TypedValue;
import android.view.Gravity;
import com.android.internal.R;
import java.io.IOException;
import java.io.InputStream;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * A Drawable that wraps a bitmap and can be tiled, stretched, or aligned (AOSP android13-release,
 * BitmapDrawable). ATL differences: bitmaps are decoded with BitmapFactory (ImageDecoder in AOSP);
 * tile modes are drawn as repeated/mirrored/clamped bitmap draws clipped to the bounds instead of
 * through a Skia BitmapShader; the paint alpha is applied as a GSK opacity group around the bitmap
 * draw on ATL's GSK canvas.
 */
public class BitmapDrawable extends Drawable {
	private static final int DEFAULT_PAINT_FLAGS = Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG;

	// Constants for {@link android.R.styleable#BitmapDrawable_tileMode}.
	private static final int TILE_MODE_UNDEFINED = -2;
	private static final int TILE_MODE_DISABLED = -1;
	private static final int TILE_MODE_CLAMP = 0;
	private static final int TILE_MODE_REPEAT = 1;
	private static final int TILE_MODE_MIRROR = 2;

	private final Rect mDstRect = new Rect(); // #updateDstRectAndInsetsIfDirty() sets this

	private BitmapState mBitmapState;
	private BlendModeColorFilter mBlendModeFilter;

	private int mTargetDensity = DisplayMetrics.DENSITY_DEFAULT;

	private boolean mDstRectAndInsetsDirty = true;
	private boolean mMutated;

	// These are scaled to match the target density.
	private int mBitmapWidth;
	private int mBitmapHeight;

	/** The DST_IN filter that applies the paint alpha on the GSK canvas (see draw). */
	private PorterDuffColorFilter mTlAlphaFilter;

	/** Optical insets due to gravity. */
	private Insets mOpticalInsets = Insets.NONE;

	/**
	 * Create an empty drawable, not dealing with density.
	 * @deprecated Use {@link #BitmapDrawable(android.content.res.Resources, android.graphics.Bitmap)}
	 * instead to specify a bitmap to draw with and ensure the correct density is set.
	 */
	@Deprecated
	public BitmapDrawable() {
		init(new BitmapState((Bitmap) null), null);
	}

	/**
	 * Create an empty drawable, setting initial target density based on
	 * the display metrics of the resources.
	 *
	 * @deprecated Use {@link #BitmapDrawable(android.content.res.Resources, android.graphics.Bitmap)}
	 * instead to specify a bitmap to draw with.
	 */
	@Deprecated
	public BitmapDrawable(Resources res) {
		init(new BitmapState((Bitmap) null), res);
	}

	/**
	 * Create drawable from a bitmap, not dealing with density.
	 * @deprecated Use {@link #BitmapDrawable(Resources, Bitmap)} to ensure
	 * that the drawable has correctly set its target density.
	 */
	@Deprecated
	public BitmapDrawable(Bitmap bitmap) {
		init(new BitmapState(bitmap), null);
	}

	/**
	 * Create drawable from a bitmap, setting initial target density based on
	 * the display metrics of the resources.
	 */
	public BitmapDrawable(Resources res, Bitmap bitmap) {
		init(new BitmapState(bitmap), res);
	}

	/**
	 * Create a drawable by opening a given file path and decoding the bitmap.
	 * @deprecated Use {@link #BitmapDrawable(Resources, String)} to ensure
	 * that the drawable has correctly set its target density.
	 */
	@Deprecated
	public BitmapDrawable(String filepath) {
		this(null, filepath);
	}

	/**
	 * Create a drawable by opening a given file path and decoding the bitmap.
	 */
	public BitmapDrawable(Resources res, String filepath) {
		Bitmap bitmap = null;
		try {
			bitmap = BitmapFactory.decodeFile(filepath);
		} catch (Exception e) {
			/* do nothing. This matches the behavior of BitmapFactory.decodeFile() */
		} finally {
			init(new BitmapState(bitmap), res);
			if (mBitmapState.mBitmap == null) {
				android.util.Log.w("BitmapDrawable", "BitmapDrawable cannot decode " + filepath);
			}
		}
	}

	/**
	 * Create a drawable by decoding a bitmap from the given input stream.
	 * @deprecated Use {@link #BitmapDrawable(Resources, java.io.InputStream)} to ensure
	 * that the drawable has correctly set its target density.
	 */
	@Deprecated
	public BitmapDrawable(java.io.InputStream is) {
		this(null, is);
	}

	/**
	 * Create a drawable by decoding a bitmap from the given input stream.
	 */
	public BitmapDrawable(Resources res, java.io.InputStream is) {
		Bitmap bitmap = null;
		try {
			bitmap = BitmapFactory.decodeStream(is);
		} catch (Exception e) {
			/* do nothing. This matches the behavior of BitmapFactory.decodeStream() */
		} finally {
			init(new BitmapState(bitmap), res);
			if (mBitmapState.mBitmap == null) {
				android.util.Log.w("BitmapDrawable", "BitmapDrawable cannot decode " + is);
			}
		}
	}

	/**
	 * Returns the paint used to render this drawable.
	 */
	public final Paint getPaint() {
		return mBitmapState.mPaint;
	}

	/**
	 * Returns the bitmap used by this drawable to render. May be null.
	 */
	public final Bitmap getBitmap() {
		return mBitmapState.mBitmap;
	}

	private void computeBitmapSize() {
		final Bitmap bitmap = mBitmapState.mBitmap;
		if (bitmap != null) {
			mBitmapWidth = bitmap.getScaledWidth(mTargetDensity);
			mBitmapHeight = bitmap.getScaledHeight(mTargetDensity);
		} else {
			mBitmapWidth = mBitmapHeight = -1;
		}
		mDstRectAndInsetsDirty = true;
	}

	/**
	 * Switch to a new Bitmap object.
	 */
	public void setBitmap(Bitmap bitmap) {
		if (mBitmapState.mBitmap != bitmap) {
			mBitmapState.mBitmap = bitmap;
			computeBitmapSize();
			invalidateSelf();
		}
	}

	/**
	 * Set the density scale at which this drawable will be rendered. This
	 * method assumes the drawable will be rendered at the same density as the
	 * specified canvas. ATL canvases are GSK snapshots or bitmap canvases whose bitmaps carry no
	 * density, which AOSP reports as Bitmap.DENSITY_NONE (the default density is used then).
	 */
	public void setTargetDensity(Canvas canvas) {
		setTargetDensity(Bitmap.DENSITY_NONE);
	}

	/**
	 * Set the density scale at which this drawable will be rendered.
	 */
	public void setTargetDensity(DisplayMetrics metrics) {
		setTargetDensity(metrics.densityDpi);
	}

	/**
	 * Set the density at which this drawable will be rendered.
	 */
	public void setTargetDensity(int density) {
		if (mTargetDensity != density) {
			mTargetDensity = density == 0 ? DisplayMetrics.DENSITY_DEFAULT : density;
			if (mBitmapState.mBitmap != null) {
				computeBitmapSize();
			}
			invalidateSelf();
		}
	}

	/** Get the gravity used to position/stretch the bitmap within its bounds. */
	public int getGravity() {
		return mBitmapState.mGravity;
	}

	/** Set the gravity used to position/stretch the bitmap within its bounds. */
	public void setGravity(int gravity) {
		if (mBitmapState.mGravity != gravity) {
			mBitmapState.mGravity = gravity;
			mDstRectAndInsetsDirty = true;
			invalidateSelf();
		}
	}

	/** Enables or disables the mipmap hint for this drawable's bitmap. */
	public void setMipMap(boolean mipMap) {
		if (mBitmapState.mBitmap != null) {
			mBitmapState.mBitmap.setHasMipMap(mipMap);
			invalidateSelf();
		}
	}

	/** Indicates whether the mipmap hint is enabled on this drawable's bitmap. */
	public boolean hasMipMap() {
		return mBitmapState.mBitmap != null && mBitmapState.mBitmap.hasMipMap();
	}

	/** Enables or disables anti-aliasing for this drawable. */
	public void setAntiAlias(boolean aa) {
		mBitmapState.mPaint.setAntiAlias(aa);
		invalidateSelf();
	}

	/** Indicates whether anti-aliasing is enabled for this drawable. */
	public boolean hasAntiAlias() {
		return mBitmapState.mPaint.isAntiAlias();
	}

	@Override
	public void setFilterBitmap(boolean filter) {
		mBitmapState.mPaint.setFilterBitmap(filter);
		invalidateSelf();
	}

	@Override
	public boolean isFilterBitmap() {
		return mBitmapState.mPaint.isFilterBitmap();
	}

	@Override
	public void setDither(boolean dither) {
		mBitmapState.mPaint.setDither(dither);
		invalidateSelf();
	}

	/** Indicates the repeat behavior of this drawable on the X axis. */
	public Shader.TileMode getTileModeX() {
		return mBitmapState.mTileModeX;
	}

	/** Indicates the repeat behavior of this drawable on the Y axis. */
	public Shader.TileMode getTileModeY() {
		return mBitmapState.mTileModeY;
	}

	/** Sets the repeat behavior of this drawable on the X axis. */
	public void setTileModeX(Shader.TileMode mode) {
		setTileModeXY(mode, mBitmapState.mTileModeY);
	}

	/** Sets the repeat behavior of this drawable on the Y axis. */
	public final void setTileModeY(Shader.TileMode mode) {
		setTileModeXY(mBitmapState.mTileModeX, mode);
	}

	/** Sets the repeat behavior of this drawable on both axis. */
	public void setTileModeXY(Shader.TileMode xmode, Shader.TileMode ymode) {
		final BitmapState state = mBitmapState;
		if (state.mTileModeX != xmode || state.mTileModeY != ymode) {
			state.mTileModeX = xmode;
			state.mTileModeY = ymode;
			mDstRectAndInsetsDirty = true;
			invalidateSelf();
		}
	}

	@Override
	public void setAutoMirrored(boolean mirrored) {
		if (mBitmapState.mAutoMirrored != mirrored) {
			mBitmapState.mAutoMirrored = mirrored;
			invalidateSelf();
		}
	}

	@Override
	public final boolean isAutoMirrored() {
		return mBitmapState.mAutoMirrored;
	}

	@Override
	public int getChangingConfigurations() {
		return super.getChangingConfigurations() | mBitmapState.getChangingConfigurations();
	}

	private boolean needMirroring() {
		return isAutoMirrored() && getLayoutDirection() == LayoutDirection.RTL;
	}

	@Override
	protected void onBoundsChange(Rect bounds) {
		mDstRectAndInsetsDirty = true;
	}

	@Override
	public void draw(Canvas canvas) {
		final Bitmap bitmap = mBitmapState.mBitmap;
		if (bitmap == null) {
			return;
		}

		final BitmapState state = mBitmapState;
		final Paint paint = state.mPaint;

		int alpha = paint.getAlpha();
		if (state.mBaseAlpha != 1.0f) {
			alpha = (int) (alpha * state.mBaseAlpha + 0.5f);
		}
		if (alpha <= 0) {
			return;
		}

		final boolean clearColorFilter;
		if (mBlendModeFilter != null && paint.getColorFilter() == null) {
			paint.setColorFilter(mBlendModeFilter);
			clearColorFilter = true;
		} else {
			clearColorFilter = false;
		}

		/* Skia modulates the bitmap by the paint alpha; on the GSK canvas that is a DST_IN colour
		 * group with the alpha as its colour (every pixel keeps its colour, its alpha is scaled). The
		 * paint goes to the bitmap draw opaque, so the alpha is applied exactly once. */
		final int restoreAlpha = paint.getAlpha();
		boolean alphaPushed = false;
		if (canvas instanceof GskCanvas) {
			if (alpha < 255) {
				if (mTlAlphaFilter == null || mTlAlphaFilter.getColor() != alpha << 24)
					mTlAlphaFilter = new PorterDuffColorFilter(alpha << 24, PorterDuff.Mode.DST_IN);
				alphaPushed = ((GskCanvas) canvas).tlPushColorFilter(mTlAlphaFilter);
			}
			if (restoreAlpha != 255) {
				paint.setAlpha(255);
			}
		} else if (alpha != restoreAlpha) {
			paint.setAlpha(alpha);
		}

		updateDstRectAndInsetsIfDirty();
		final boolean needMirroring = needMirroring();
		if (state.mTileModeX == null && state.mTileModeY == null) {
			if (needMirroring) {
				canvas.save();
				// Mirror the bitmap
				canvas.translate(mDstRect.right - mDstRect.left, 0);
				canvas.scale(-1.0f, 1.0f);
			}

			canvas.drawBitmap(bitmap, null, mDstRect, paint);

			if (needMirroring) {
				canvas.restore();
			}
		} else {
			drawTiled(canvas, bitmap, paint, needMirroring);
		}

		if (alphaPushed) {
			((GskCanvas) canvas).tlPopGroup();
		}
		if (paint.getAlpha() != restoreAlpha) {
			paint.setAlpha(restoreAlpha);
		}
		if (clearColorFilter) {
			paint.setColorFilter(null);
		}
	}

	/**
	 * Fills mDstRect the way a BitmapShader with the state's tile modes does (a null mode is CLAMP,
	 * as AOSP builds the shader): the bitmap at its density-scaled size is anchored at the top-left
	 * of the bounds; REPEAT repeats it, MIRROR repeats it flipped on every other tile, CLAMP extends
	 * its edge pixels.
	 */
	private void drawTiled(Canvas canvas, Bitmap bitmap, Paint paint, boolean needMirroring) {
		final Shader.TileMode tmx = mBitmapState.mTileModeX == null ? Shader.TileMode.CLAMP : mBitmapState.mTileModeX;
		final Shader.TileMode tmy = mBitmapState.mTileModeY == null ? Shader.TileMode.CLAMP : mBitmapState.mTileModeY;
		final Rect dst = mDstRect;
		final int tw = mBitmapWidth, th = mBitmapHeight;
		final int bw = bitmap.getWidth(), bh = bitmap.getHeight();
		if (tw <= 0 || th <= 0 || bw <= 0 || bh <= 0 || dst.isEmpty()) {
			return;
		}

		canvas.save();
		canvas.clipRect(dst);
		if (needMirroring) {
			canvas.translate(dst.right + dst.left, 0);
			canvas.scale(-1.0f, 1.0f);
		}
		final int width = dst.width(), height = dst.height();
		final int cols = tmx == Shader.TileMode.CLAMP ? 1 : (width + tw - 1) / tw;
		final int rows = tmy == Shader.TileMode.CLAMP ? 1 : (height + th - 1) / th;
		final Rect src = new Rect();
		final Rect tile = new Rect();
		for (int row = 0; row < rows; row++) {
			final int top = dst.top + row * th;
			final boolean flipY = tmy == Shader.TileMode.MIRROR && (row & 1) == 1;
			for (int col = 0; col < cols; col++) {
				final int left = dst.left + col * tw;
				final boolean flipX = tmx == Shader.TileMode.MIRROR && (col & 1) == 1;
				tile.set(left, top, left + tw, top + th);
				drawTile(canvas, bitmap, null, tile, paint, flipX, flipY);
			}
		}
		// CLAMP: the last column/row of pixels stretched over the rest of the bounds
		final boolean clampX = tmx == Shader.TileMode.CLAMP && width > tw;
		final boolean clampY = tmy == Shader.TileMode.CLAMP && height > th;
		if (clampX) {
			for (int row = 0; row < rows; row++) {
				final int top = dst.top + row * th;
				final boolean flipY = tmy == Shader.TileMode.MIRROR && (row & 1) == 1;
				src.set(bw - 1, 0, bw, bh);
				tile.set(dst.left + tw, top, dst.right, top + th);
				drawTile(canvas, bitmap, src, tile, paint, false, flipY);
			}
		}
		if (clampY) {
			for (int col = 0; col < cols; col++) {
				final int left = dst.left + col * tw;
				final boolean flipX = tmx == Shader.TileMode.MIRROR && (col & 1) == 1;
				src.set(0, bh - 1, bw, bh);
				tile.set(left, dst.top + th, left + tw, dst.bottom);
				drawTile(canvas, bitmap, src, tile, paint, flipX, false);
			}
		}
		if (clampX && clampY) {
			src.set(bw - 1, bh - 1, bw, bh);
			tile.set(dst.left + tw, dst.top + th, dst.right, dst.bottom);
			canvas.drawBitmap(bitmap, src, tile, paint);
		}
		canvas.restore();
	}

	private static void drawTile(Canvas canvas, Bitmap bitmap, Rect src, Rect tile, Paint paint, boolean flipX, boolean flipY) {
		if (!flipX && !flipY) {
			canvas.drawBitmap(bitmap, src, tile, paint);
			return;
		}
		canvas.save();
		canvas.translate(flipX ? tile.left + tile.right : 0, flipY ? tile.top + tile.bottom : 0);
		canvas.scale(flipX ? -1.0f : 1.0f, flipY ? -1.0f : 1.0f);
		canvas.drawBitmap(bitmap, src, tile, paint);
		canvas.restore();
	}

	private void updateDstRectAndInsetsIfDirty() {
		if (mDstRectAndInsetsDirty) {
			if (mBitmapState.mTileModeX == null && mBitmapState.mTileModeY == null) {
				final Rect bounds = getBounds();
				final int layoutDirection = getLayoutDirection();
				Gravity.apply(mBitmapState.mGravity, mBitmapWidth, mBitmapHeight,
				              bounds, mDstRect, layoutDirection);

				final int left = mDstRect.left - bounds.left;
				final int top = mDstRect.top - bounds.top;
				final int right = bounds.right - mDstRect.right;
				final int bottom = bounds.bottom - mDstRect.bottom;
				mOpticalInsets = Insets.of(left, top, right, bottom);
			} else {
				copyBounds(mDstRect);
				mOpticalInsets = Insets.NONE;
			}
		}
		mDstRectAndInsetsDirty = false;
	}

	@Override
	public Insets getOpticalInsets() {
		updateDstRectAndInsetsIfDirty();
		return mOpticalInsets;
	}

	@Override
	public void getOutline(Outline outline) {
		updateDstRectAndInsetsIfDirty();
		outline.setRect(mDstRect);

		// Only opaque Bitmaps can report a non-0 alpha,
		// since only they are guaranteed to fill their bounds
		boolean opaqueOverShape = mBitmapState.mBitmap != null && !mBitmapState.mBitmap.hasAlpha();
		outline.setAlpha(opaqueOverShape ? getAlpha() / 255.0f : 0.0f);
	}

	@Override
	public void setAlpha(int alpha) {
		final int oldAlpha = mBitmapState.mPaint.getAlpha();
		if (alpha != oldAlpha) {
			mBitmapState.mPaint.setAlpha(alpha);
			invalidateSelf();
		}
	}

	@Override
	public int getAlpha() {
		return mBitmapState.mPaint.getAlpha();
	}

	@Override
	public void setColorFilter(ColorFilter colorFilter) {
		mBitmapState.mPaint.setColorFilter(colorFilter);
		invalidateSelf();
	}

	@Override
	public ColorFilter getColorFilter() {
		return mBitmapState.mPaint.getColorFilter();
	}

	@Override
	public void setTintList(ColorStateList tint) {
		final BitmapState state = mBitmapState;
		if (state.mTint != tint) {
			state.mTint = tint;
			mBlendModeFilter = updateBlendModeFilter(mBlendModeFilter, tint, mBitmapState.mBlendMode);
			invalidateSelf();
		}
	}

	@Override
	public void setTintBlendMode(BlendMode blendMode) {
		final BitmapState state = mBitmapState;
		if (blendMode == null) {
			blendMode = DEFAULT_BLEND_MODE;
		}
		if (state.mBlendMode != blendMode) {
			state.mBlendMode = blendMode;
			mBlendModeFilter = updateBlendModeFilter(mBlendModeFilter, mBitmapState.mTint, blendMode);
			invalidateSelf();
		}
	}

	/** AOSP (hidden, UnsupportedAppUsage): the tint list. */
	private ColorStateList getTint() {
		return mBitmapState.mTint;
	}

	/** AOSP (hidden, UnsupportedAppUsage): the tint mode. */
	private PorterDuff.Mode getTintMode() {
		return BlendMode.blendModeToPorterDuffMode(mBitmapState.mBlendMode);
	}

	/**
	 * @hide Candidate for future API inclusion
	 */
	@Override
	public void setXfermode(Xfermode xfermode) {
		mBitmapState.mPaint.setXfermode(xfermode);
		invalidateSelf();
	}

	/**
	 * A mutable BitmapDrawable still shares its Bitmap with any other Drawable
	 * that comes from the same resource.
	 *
	 * @return This drawable.
	 */
	@Override
	public Drawable mutate() {
		if (!mMutated && super.mutate() == this) {
			mBitmapState = new BitmapState(mBitmapState);
			mMutated = true;
		}
		return this;
	}

	/**
	 * @hide
	 */
	@Override
	public void clearMutated() {
		super.clearMutated();
		mMutated = false;
	}

	@Override
	protected boolean onStateChange(int[] stateSet) {
		final BitmapState state = mBitmapState;
		if (state.mTint != null && state.mBlendMode != null) {
			mBlendModeFilter = updateBlendModeFilter(mBlendModeFilter, state.mTint, state.mBlendMode);
			return true;
		}
		return false;
	}

	@Override
	public boolean isStateful() {
		return (mBitmapState.mTint != null && mBitmapState.mTint.isStateful()) || super.isStateful();
	}

	@Override
	public boolean hasFocusStateSpecified() {
		return mBitmapState.mTint != null && mBitmapState.mTint.hasFocusStateSpecified();
	}

	@Override
	public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
	    throws XmlPullParserException, IOException {
		super.inflate(r, parser, attrs, theme);

		final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.BitmapDrawable);
		updateStateFromTypedArray(a, mSrcDensityOverride);
		verifyRequiredAttributes(a);
		a.recycle();

		// Update local properties.
		updateLocalState(r);
	}

	/**
	 * Ensures all required attributes are set.
	 *
	 * @throws XmlPullParserException if any required attributes are missing
	 */
	private void verifyRequiredAttributes(TypedArray a) throws XmlPullParserException {
		// If we're not waiting on a theme, verify required attributes.
		final BitmapState state = mBitmapState;
		if (state.mBitmap == null && (state.mThemeAttrs == null || state.mThemeAttrs[R.styleable.BitmapDrawable_src] == 0)) {
			throw new XmlPullParserException(a.getPositionDescription() + ": <bitmap> requires a valid 'src' attribute");
		}
	}

	/**
	 * Updates the constant state from the values in the typed array.
	 */
	private void updateStateFromTypedArray(TypedArray a, int srcDensityOverride)
	    throws XmlPullParserException {
		final Resources r = a.getResources();
		final BitmapState state = mBitmapState;

		// Account for any configuration changes.
		state.mChangingConfigurations |= a.getChangingConfigurations();

		// Extract the theme attributes, if any.
		state.mThemeAttrs = a.extractThemeAttrs();

		state.mSrcDensityOverride = srcDensityOverride;

		state.mTargetDensity = Drawable.resolveDensity(r, 0);

		final int srcResId = a.getResourceId(R.styleable.BitmapDrawable_src, 0);
		if (srcResId != 0) {
			Bitmap bitmap = null;
			final TypedValue value = new TypedValue();
			try {
				r.getValue(srcResId, value, true);
				final String file = value.string != null ? value.string.toString() : null;
				if (file != null && file.endsWith(".xml")) {
					/* AOSP decodes the source with ImageDecoder, which rejects anything but an
					 * image file; a <bitmap> pointing at XML is the same error as a missing src */
					bitmap = null;
				} else {
					try (InputStream is = r.openRawResource(srcResId, value)) {
						bitmap = BitmapFactory.decodeStream(is);
					}
				}
			} catch (Exception e) {
				// Do nothing and pick up the error below.
			}

			if (bitmap == null) {
				throw new XmlPullParserException(a.getPositionDescription() + ": <bitmap> requires a valid 'src' attribute");
			}

			state.mBitmap = bitmap;
		}

		final boolean defMipMap = state.mBitmap != null ? state.mBitmap.hasMipMap() : false;
		setMipMap(a.getBoolean(R.styleable.BitmapDrawable_mipMap, defMipMap));

		state.mAutoMirrored = a.getBoolean(R.styleable.BitmapDrawable_autoMirrored, state.mAutoMirrored);
		state.mBaseAlpha = a.getFloat(R.styleable.BitmapDrawable_alpha, state.mBaseAlpha);

		final int tintMode = a.getInt(R.styleable.BitmapDrawable_tintMode, -1);
		if (tintMode != -1) {
			state.mBlendMode = Drawable.parseBlendMode(tintMode, BlendMode.SRC_IN);
		}

		final ColorStateList tint = a.getColorStateList(R.styleable.BitmapDrawable_tint);
		if (tint != null) {
			state.mTint = tint;
		}

		final Paint paint = mBitmapState.mPaint;
		paint.setAntiAlias(a.getBoolean(R.styleable.BitmapDrawable_antialias, paint.isAntiAlias()));
		paint.setFilterBitmap(a.getBoolean(R.styleable.BitmapDrawable_filter, paint.isFilterBitmap()));
		paint.setDither(a.getBoolean(R.styleable.BitmapDrawable_dither, paint.isDither()));

		setGravity(a.getInt(R.styleable.BitmapDrawable_gravity, state.mGravity));

		final int tileMode = a.getInt(R.styleable.BitmapDrawable_tileMode, TILE_MODE_UNDEFINED);
		if (tileMode != TILE_MODE_UNDEFINED) {
			final Shader.TileMode mode = parseTileMode(tileMode);
			setTileModeXY(mode, mode);
		}

		final int tileModeX = a.getInt(R.styleable.BitmapDrawable_tileModeX, TILE_MODE_UNDEFINED);
		if (tileModeX != TILE_MODE_UNDEFINED) {
			setTileModeX(parseTileMode(tileModeX));
		}

		final int tileModeY = a.getInt(R.styleable.BitmapDrawable_tileModeY, TILE_MODE_UNDEFINED);
		if (tileModeY != TILE_MODE_UNDEFINED) {
			setTileModeY(parseTileMode(tileModeY));
		}
	}

	@Override
	public void applyTheme(Theme t) {
		super.applyTheme(t);

		final BitmapState state = mBitmapState;
		if (state == null) {
			return;
		}

		if (state.mThemeAttrs != null) {
			final TypedArray a = t.resolveAttributes(state.mThemeAttrs, R.styleable.BitmapDrawable);
			try {
				updateStateFromTypedArray(a, state.mSrcDensityOverride);
			} catch (XmlPullParserException e) {
				rethrowAsRuntimeException(e);
			} finally {
				a.recycle();
			}
		}

		// Apply theme to contained color state list.
		if (state.mTint != null && state.mTint.canApplyTheme()) {
			state.mTint = state.mTint.obtainForTheme(t);
		}

		// Update local properties.
		updateLocalState(t.getResources());
	}

	private static Shader.TileMode parseTileMode(int tileMode) {
		switch (tileMode) {
			case TILE_MODE_CLAMP:
				return Shader.TileMode.CLAMP;
			case TILE_MODE_REPEAT:
				return Shader.TileMode.REPEAT;
			case TILE_MODE_MIRROR:
				return Shader.TileMode.MIRROR;
			default:
				return null;
		}
	}

	@Override
	public boolean canApplyTheme() {
		return mBitmapState != null && mBitmapState.canApplyTheme();
	}

	@Override
	public int getIntrinsicWidth() {
		return mBitmapWidth;
	}

	@Override
	public int getIntrinsicHeight() {
		return mBitmapHeight;
	}

	@Override
	public int getOpacity() {
		if (mBitmapState.mGravity != Gravity.FILL) {
			return PixelFormat.TRANSLUCENT;
		}

		final Bitmap bitmap = mBitmapState.mBitmap;
		return (bitmap == null || bitmap.hasAlpha() || mBitmapState.mPaint.getAlpha() < 255) ? PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
	}

	@Override
	public final ConstantState getConstantState() {
		mBitmapState.mChangingConfigurations |= getChangingConfigurations();
		return mBitmapState;
	}

	final static class BitmapState extends ConstantState {
		final Paint mPaint;

		// Values loaded during inflation.
		int[] mThemeAttrs = null;
		Bitmap mBitmap = null;
		ColorStateList mTint = null;
		BlendMode mBlendMode = DEFAULT_BLEND_MODE;

		int mGravity = Gravity.FILL;
		float mBaseAlpha = 1.0f;
		Shader.TileMode mTileModeX = null;
		Shader.TileMode mTileModeY = null;

		// The density to use when looking up the bitmap in Resources. A value of 0 means use
		// the system's density.
		int mSrcDensityOverride = 0;

		// The density at which to render the bitmap.
		int mTargetDensity = DisplayMetrics.DENSITY_DEFAULT;

		boolean mAutoMirrored = false;

		int mChangingConfigurations;

		BitmapState(Bitmap bitmap) {
			mBitmap = bitmap;
			mPaint = new Paint(DEFAULT_PAINT_FLAGS);
		}

		BitmapState(BitmapState bitmapState) {
			mBitmap = bitmapState.mBitmap;
			mTint = bitmapState.mTint;
			mBlendMode = bitmapState.mBlendMode;
			mThemeAttrs = bitmapState.mThemeAttrs;
			mChangingConfigurations = bitmapState.mChangingConfigurations;
			mGravity = bitmapState.mGravity;
			mTileModeX = bitmapState.mTileModeX;
			mTileModeY = bitmapState.mTileModeY;
			mSrcDensityOverride = bitmapState.mSrcDensityOverride;
			mTargetDensity = bitmapState.mTargetDensity;
			mBaseAlpha = bitmapState.mBaseAlpha;
			mPaint = new Paint(bitmapState.mPaint);
			mAutoMirrored = bitmapState.mAutoMirrored;
		}

		@Override
		public boolean canApplyTheme() {
			return mThemeAttrs != null || mTint != null && mTint.canApplyTheme();
		}

		@Override
		public Drawable newDrawable() {
			return new BitmapDrawable(this, null);
		}

		@Override
		public Drawable newDrawable(Resources res) {
			return new BitmapDrawable(this, res);
		}

		@Override
		public int getChangingConfigurations() {
			return mChangingConfigurations | (mTint != null ? mTint.getChangingConfigurations() : 0);
		}
	}

	private BitmapDrawable(BitmapState state, Resources res) {
		init(state, res);
	}

	/**
	 * The one helper to rule them all. This is called by all public & private
	 * constructors to set the state and initialize local properties.
	 */
	private void init(BitmapState state, Resources res) {
		mBitmapState = state;
		updateLocalState(res);

		if (mBitmapState != null && res != null) {
			mBitmapState.mTargetDensity = mTargetDensity;
		}
	}

	/**
	 * Initializes local dynamic properties from state. This should be called
	 * after significant state changes, e.g. from the One True Constructor and
	 * after inflating or applying a theme.
	 */
	private void updateLocalState(Resources res) {
		mTargetDensity = resolveDensity(res, mBitmapState.mTargetDensity);
		mBlendModeFilter = updateBlendModeFilter(mBlendModeFilter, mBitmapState.mTint, mBitmapState.mBlendMode);
		computeBitmapSize();
	}
}
