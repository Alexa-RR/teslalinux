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

import android.atl.GskCanvas;

import java.io.IOException;
import java.io.InputStream;

/**
 * The NinePatch class permits drawing a bitmap in nine or more sections.
 * Essentially, it allows the creation of custom graphics that will scale the
 * way that you define, when content added within the image exceeds the normal
 * bounds of the graphic.
 *
 * teslalinux: AOSP keeps the bitmap and the native Res_png_9patch chunk and draws through Skia.
 * Here the decoded texture and the chunk live in ATL's NinePatchPaintable (a GdkPaintable that
 * stretches the odd segments to the requested size); this class owns a reference to it, reads
 * the chunk's padding, divs and colours from it and draws it into a GSK snapshot.
 */
public class NinePatch {
	/**
	 * Struct of inset information attached to a 9 patch bitmap.
	 *
	 * Present on a 9 patch bitmap if it optical insets were manually included,
	 * or if outline insets were automatically included by aapt.
	 */
	public static class InsetStruct {
		InsetStruct(int opticalLeft, int opticalTop, int opticalRight, int opticalBottom,
		            int outlineLeft, int outlineTop, int outlineRight, int outlineBottom,
		            float outlineRadius, int outlineAlpha, float decodeScale) {
			opticalRect = new Rect(opticalLeft, opticalTop, opticalRight, opticalBottom);
			opticalRect.scale(decodeScale);

			outlineRect = scaleInsets(outlineLeft, outlineTop,
			                          outlineRight, outlineBottom, decodeScale);

			this.outlineRadius = outlineRadius * decodeScale;
			this.outlineAlpha = outlineAlpha / 255.0f;
		}

		public final Rect opticalRect;
		public final Rect outlineRect;
		public final float outlineRadius;
		public final float outlineAlpha;

		/**
		 * Scales up the rect by the ratio, with intent to round-out the rect
		 */
		public static Rect scaleInsets(int left, int top, int right, int bottom, float scale) {
			if (scale == 1.0f) {
				return new Rect(left, top, right, bottom);
			}

			Rect result = new Rect();
			result.left = (int) Math.ceil(left * scale);
			result.top = (int) Math.ceil(top * scale);
			result.right = (int) Math.ceil(right * scale);
			result.bottom = (int) Math.ceil(bottom * scale);
			return result;
		}
	}

	private final Bitmap mBitmap;
	/** The native NinePatchPaintable (texture plus chunk); this object holds one reference. */
	private long mPaintable;
	private String mSrcName;
	private Paint mPaint;

	private final int mWidth;
	private final int mHeight;
	private final int mDensity;
	private final Rect mPadding = new Rect();
	private final int[] mXDivs;
	private final int[] mYDivs;
	private final int[] mColors;
	private int mHasAlpha = -1;
	/** the npLb/npOl insets at the source density (AOSP: Bitmap.getNinePatchInsets()), or null */
	private InsetStruct mInsets;

	/**
	 * Create a drawable projection from a bitmap to nine patches.
	 *
	 * @param bitmap The bitmap describing the patches.
	 * @param chunk The 9-patch data chunk describing how the underlying bitmap
	 *              is split apart and drawn.
	 */
	public NinePatch(Bitmap bitmap, byte[] chunk) {
		this(bitmap, chunk, null);
	}

	/**
	 * Create a drawable projection from a bitmap to nine patches.
	 *
	 * @param bitmap The bitmap describing the patches.
	 * @param chunk The 9-patch data chunk describing how the underlying
	 *              bitmap is split apart and drawn.
	 * @param srcName The name of the source for the bitmap. Might be null.
	 */
	public NinePatch(Bitmap bitmap, byte[] chunk, String srcName) {
		if (!isNinePatchChunk(chunk))
			throw new RuntimeException("The 9-patch chunk is not valid.");
		mBitmap = bitmap;
		mSrcName = srcName;
		mPaintable = nativeCreate(chunk, bitmap.getTexture());
		if (mPaintable == 0)
			throw new RuntimeException("The 9-patch chunk is not valid.");
		/* ATL bitmaps carry no density of their own (BitmapDrawable draws them pixel for pixel),
		 * so a nine-patch built on one is not density scaled, as DENSITY_NONE means in AOSP */
		mDensity = Bitmap.DENSITY_NONE;
		int[] info = new int[9];
		nativeGetInfo(mPaintable, info);
		mWidth = info[0];
		mHeight = info[1];
		mPadding.set(info[2], info[3], info[4], info[5]);
		mXDivs = new int[info[6]];
		mYDivs = new int[info[7]];
		mColors = new int[info[8]];
		nativeGetDivs(mPaintable, mXDivs, mYDivs, mColors);
	}

	/** teslalinux: a nine-patch decoded by GDK from PNG bytes that carry an npTc chunk. */
	private NinePatch(long paintable, int density, String srcName) {
		mBitmap = null;
		mSrcName = srcName;
		mPaintable = paintable;
		mDensity = density;
		int[] info = new int[9];
		nativeGetInfo(mPaintable, info);
		mWidth = info[0];
		mHeight = info[1];
		mPadding.set(info[2], info[3], info[4], info[5]);
		mXDivs = new int[info[6]];
		mYDivs = new int[info[7]];
		mColors = new int[info[8]];
		nativeGetDivs(mPaintable, mXDivs, mYDivs, mColors);
	}

	/**
	 * teslalinux: decodes a nine-patch PNG from a stream the way AOSP's BitmapFactory does for a
	 * drawable resource (density is the resource's, DENSITY_NONE for no scaling). Returns null when
	 * the data is not a PNG with a 9-patch chunk.
	 */
	public static NinePatch tlDecode(InputStream is, int density, String srcName) {
		byte[] png;
		try {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(1024, is.available()));
			byte[] buf = new byte[16384];
			int n;
			while ((n = is.read(buf)) > 0)
				out.write(buf, 0, n);
			png = out.toByteArray();
		} catch (IOException e) {
			return null;
		}
		return tlDecode(png, density, srcName);
	}

	/** teslalinux: decodes a nine-patch from PNG bytes; see {@link #tlDecode(InputStream, int, String)}. */
	public static NinePatch tlDecode(byte[] png, int density, String srcName) {
		final int[] in = new int[11];
		long paintable = nativeCreateFromBytes(png, in);
		if (paintable == 0)
			return null;
		final NinePatch patch = new NinePatch(paintable, density, srcName);
		/* AOSP BitmapFactory: NinePatchPeeker.createNinePatchInsets(decodeScale); the texture is not
		 * scaled at decode here (NinePatchDrawable scales from the density), so the scale is 1 */
		if (in[0] != 0)
			patch.mInsets = new InsetStruct(in[1], in[2], in[3], in[4], in[5], in[6], in[7], in[8],
			                                Float.intBitsToFloat(in[9]), in[10], 1.0f);
		return patch;
	}

	/** teslalinux: decodes a nine-patch file (Drawable.createFromPath: no density scaling). */
	public static NinePatch tlDecodeFile(String path) {
		try {
			return tlDecode(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), Bitmap.DENSITY_NONE, path);
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * @hide
	 */
	public NinePatch(NinePatch patch) {
		mBitmap = patch.mBitmap;
		mSrcName = patch.mSrcName;
		if (patch.mPaint != null) {
			mPaint = new Paint(patch.mPaint);
		}
		mPaintable = patch.mPaintable;
		nativeRef(mPaintable);
		mWidth = patch.mWidth;
		mHeight = patch.mHeight;
		mDensity = patch.mDensity;
		mPadding.set(patch.mPadding);
		mXDivs = patch.mXDivs;
		mYDivs = patch.mYDivs;
		mColors = patch.mColors;
		mHasAlpha = patch.mHasAlpha;
		mInsets = patch.mInsets;
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			if (mPaintable != 0) {
				nativeUnref(mPaintable);
				mPaintable = 0;
			}
		} finally {
			super.finalize();
		}
	}

	/**
	 * Returns the name of this NinePatch object if one was specified
	 * when calling the constructor.
	 */
	public String getName() {
		return mSrcName;
	}

	/**
	 * Returns the paint used to draw this NinePatch. The paint can be null.
	 *
	 * @see #setPaint(Paint)
	 * @see #draw(Canvas, Rect)
	 * @see #draw(Canvas, RectF)
	 */
	public Paint getPaint() {
		return mPaint;
	}

	/**
	 * Sets the paint to use when drawing the NinePatch.
	 *
	 * @param p The paint that will be used to draw this NinePatch.
	 *
	 * @see #getPaint()
	 * @see #draw(Canvas, Rect)
	 * @see #draw(Canvas, RectF)
	 */
	public void setPaint(Paint p) {
		mPaint = p;
	}

	/**
	 * Returns the bitmap used to draw this NinePatch (null for a nine-patch decoded straight
	 * from a resource stream, whose texture lives in the native paintable).
	 */
	public Bitmap getBitmap() {
		return mBitmap;
	}

	/**
	 * Draws the NinePatch. This method will use the paint returned by {@link #getPaint()}.
	 *
	 * @param canvas A container for the current matrix and clip used to draw the NinePatch.
	 * @param location Where to draw the NinePatch.
	 */
	public void draw(Canvas canvas, RectF location) {
		draw(canvas, location.left, location.top, location.width(), location.height(), mPaint);
	}

	/**
	 * Draws the NinePatch. This method will use the paint returned by {@link #getPaint()}.
	 *
	 * @param canvas A container for the current matrix and clip used to draw the NinePatch.
	 * @param location Where to draw the NinePatch.
	 */
	public void draw(Canvas canvas, Rect location) {
		draw(canvas, location.left, location.top, location.width(), location.height(), mPaint);
	}

	/**
	 * Draws the NinePatch. This method will ignore the paint returned
	 * by {@link #getPaint()} and use the specified paint instead.
	 *
	 * @param canvas A container for the current matrix and clip used to draw the NinePatch.
	 * @param location Where to draw the NinePatch.
	 * @param paint The Paint to draw through.
	 */
	public void draw(Canvas canvas, Rect location, Paint paint) {
		draw(canvas, location.left, location.top, location.width(), location.height(), paint);
	}

	private void draw(Canvas canvas, float left, float top, float width, float height, Paint paint) {
		final GskCanvas gsk = canvas.tlGskCanvas(); /* a Canvas(Bitmap) draws through its GSK canvas too */
		if (gsk == null)
			return;
		ColorFilter filter = paint != null ? paint.getColorFilter() : null;
		boolean pushed = filter != null && gsk.tlPushColorFilter(filter);
		float alpha = paint != null ? paint.getAlpha() / 255f : 1f;
		nativeDraw(mPaintable, gsk.snapshot, left, top, width, height, alpha);
		if (pushed)
			gsk.tlPopGroup();
	}

	/**
	 * Return the underlying bitmap's density, as per
	 * {@link Bitmap#getDensity() Bitmap.getDensity()}.
	 */
	public int getDensity() {
		return mDensity;
	}

	/**
	 * Returns the intrinsic width of this NinePatch, as the bitmap's width.
	 */
	public int getWidth() {
		return mWidth;
	}

	/**
	 * Returns the intrinsic height of this NinePatch, as the bitmap's height.
	 */
	public int getHeight() {
		return mHeight;
	}

	/**
	 * Indicates whether this NinePatch contains transparent or translucent pixels.
	 * This is equivalent to calling <code>getBitmap().hasAlpha()</code> on this
	 * NinePatch.
	 */
	public final boolean hasAlpha() {
		if (mHasAlpha < 0)
			mHasAlpha = nativeHasAlpha(mPaintable) ? 1 : 0;
		return mHasAlpha == 1;
	}

	/**
	 * Returns a {@link Region} representing the parts of this NinePatch that are
	 * completely transparent.
	 *
	 * @param bounds The location and size of the NinePatch.
	 *
	 * @return null if the NinePatch has no transparent region to
	 * report, else a {@link Region} holding the parts of the specified bounds
	 * that are transparent.
	 *
	 * AOSP computes this in native code from the chunk's patch colours; the same walk over the
	 * patch grid is done here (fixed segments keep their size, stretchable ones share the rest).
	 */
	public final Region getTransparentRegion(Rect bounds) {
		if (bounds == null)
			throw new NullPointerException("bounds cannot be null");
		Region region = null;
		int segsX = mXDivs.length + 1, segsY = mYDivs.length + 1;
		float[] xs = segmentEdges(mXDivs, mWidth, bounds.left, bounds.width());
		float[] ys = segmentEdges(mYDivs, mHeight, bounds.top, bounds.height());
		int colorIndex = 0;
		for (int j = 0; j < segsY; j++) {
			for (int i = 0; i < segsX; i++) {
				if (colorIndex >= mColors.length)
					return region;
				int color = mColors[colorIndex++];
				if (color == TRANSPARENT_COLOR) {
					Rect r = new Rect(Math.round(xs[i]), Math.round(ys[j]), Math.round(xs[i + 1]), Math.round(ys[j + 1]));
					if (!r.isEmpty()) {
						if (region == null)
							region = new Region();
						region.op(r, Region.Op.UNION);
					}
				}
			}
		}
		return region;
	}

	/** Destination edges of the patch segments when the source size is stretched to dstSize. */
	private static float[] segmentEdges(int[] divs, int srcSize, int origin, int dstSize) {
		int segs = divs.length + 1;
		float[] edges = new float[segs + 1];
		int stretchy = 0;
		for (int i = 1; i < divs.length + 1; i += 2)
			stretchy += (i == divs.length ? srcSize : divs[i]) - divs[i - 1];
		float factor = stretchy > 0 ? (dstSize - (srcSize - stretchy)) / (float) stretchy : 1f;
		float pos = origin;
		edges[0] = pos;
		for (int i = 0; i < segs; i++) {
			int start = i == 0 ? 0 : divs[i - 1];
			int end = i == divs.length ? srcSize : divs[i];
			pos += (end - start) * ((i % 2 == 1) ? factor : 1f);
			edges[i + 1] = pos;
		}
		return edges;
	}

	/** Res_png_9patch colour of a patch that is fully transparent. */
	private static final int TRANSPARENT_COLOR = 0x00000000;

	/**
	 * Verifies that the specified byte array is a valid 9-patch data chunk.
	 *
	 * @param chunk A byte array representing a 9-patch data chunk.
	 *
	 * @return True if the specified byte array represents a 9-patch data chunk,
	 *         false otherwise.
	 */
	public static boolean isNinePatchChunk(byte[] chunk) {
		/* AOSP: at least a Res_png_9patch header (32 bytes) whose wasDeserialized is not -1 */
		return chunk != null && chunk.length >= 32 && chunk[0] != -1;
	}

	/** teslalinux: the chunk's padding (what BitmapFactory reports as outPadding for a nine-patch). */
	public Rect tlGetPadding() {
		return new Rect(mPadding);
	}

	/**
	 * teslalinux: the optical and outline insets of the PNG's npLb/npOl chunks at the source density
	 * (what AOSP's Bitmap.getNinePatchInsets() returns for the decoded bitmap), or null when it has none.
	 */
	public InsetStruct tlGetInsets() {
		return mInsets;
	}

	/** teslalinux: the native paintable, for drawables that let GTK paint it. */
	public long tlPaintable() {
		return mPaintable;
	}

	private static native long nativeCreate(byte[] chunk, long texture);
	private static native long nativeCreateFromBytes(byte[] png, int[] insets);
	private static native void nativeGetInfo(long paintable, int[] out);
	private static native void nativeGetDivs(long paintable, int[] xDivs, int[] yDivs, int[] colors);
	private static native void nativeDraw(long paintable, long snapshot, float x, float y, float width, float height, float alpha);
	private static native boolean nativeHasAlpha(long paintable);
	private static native void nativeRef(long paintable);
	private static native void nativeUnref(long paintable);
}
