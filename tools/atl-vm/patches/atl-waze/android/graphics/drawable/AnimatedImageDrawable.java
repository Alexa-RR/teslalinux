/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import com.android.internal.R;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * {@link Drawable} for drawing animated images (like GIF) (AOSP android13-release API).
 *
 * <p>AOSP decodes the frames lazily with Skia's SkAnimatedImage on the render thread. ATL decodes
 * the animation once into composited frames (GIF: LZW, disposal methods, transparency and the
 * NETSCAPE2.0 loop count, all in Java below) and plays them from draw(), scheduling the next frame
 * through the drawable callback like any other animated drawable. Animated WebP and HEIF are not
 * decoded as animations here (they decode as still images, which AOSP's &lt;animated-image&gt;
 * rejects the same way it rejects a still GIF).</p>
 *
 * <p>Created by ImageDecoder#decodeDrawable (on ATL: {@link #tlDecode}). A user needs to call
 * {@link #start} to start the animation.</p>
 *
 * <p>It can also be defined in XML using the <code>&lt;animated-image></code> element.</p>
 */
public class AnimatedImageDrawable extends Drawable implements Animatable2 {
	private int mIntrinsicWidth;
	private int mIntrinsicHeight;

	private boolean mStarting;

	private Handler mHandler;

	/** The decoded animation, shared by drawables made from the same data. */
	static final class Frames {
		final Bitmap[] mBitmaps;
		final int[] mDurations; // milliseconds
		final int mWidth, mHeight;
		final int mEncodedRepeatCount; // REPEAT_INFINITE or the repeat count in the data

		Frames(Bitmap[] bitmaps, int[] durations, int width, int height, int encodedRepeatCount) {
			mBitmaps = bitmaps;
			mDurations = durations;
			mWidth = width;
			mHeight = height;
			mEncodedRepeatCount = encodedRepeatCount;
		}
	}

	private class State {
		State(Frames frames) {
			mFrames = frames;
		}

		final Frames mFrames;

		int[] mThemeAttrs = null;
		boolean mAutoMirrored = false;
		int mRepeatCount = REPEAT_UNDEFINED;
	}

	private State mState;

	private Runnable mRunnable;

	private ColorFilter mColorFilter;

	/* playback state (AOSP keeps it in the native AnimatedImageDrawable) */
	private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
	private PorterDuffColorFilter mAlphaFilter;
	private int mAlpha = 255;
	private boolean mMirrored;
	private boolean mRunning;
	private int mFrame;
	private long mFrameStart;
	private int mRepeatsDone;
	private final Rect mDst = new Rect();

	/**
	 *  Pass this to {@link #setRepeatCount} to repeat infinitely.
	 *
	 *  <p>{@link Animatable2.AnimationCallback#onAnimationEnd} will never be
	 *  called unless there is an error.</p>
	 */
	public static final int REPEAT_INFINITE = -1;

	/** @removed
	 * @deprecated Replaced with REPEAT_INFINITE to match other APIs.
	 */
	@java.lang.Deprecated
	public static final int LOOP_INFINITE = REPEAT_INFINITE;

	private static final int REPEAT_UNDEFINED = -2;

	/**
	 *  Specify the number of times to repeat the animation.
	 *
	 *  <p>By default, the repeat count in the encoded data is respected. If set
	 *  to {@link #REPEAT_INFINITE}, the animation will repeat as long as it is
	 *  displayed. If the value is {@code 0}, the animation will play once.</p>
	 *
	 *  <p>If the animation is already playing, the iterations that have already
	 *  occurred count towards the new count. If the animation has already
	 *  repeated the appropriate number of times (or more), it will finish its
	 *  current iteration and then stop.</p>
	 */
	public void setRepeatCount(int repeatCount) {
		if (repeatCount < REPEAT_INFINITE) {
			throw new IllegalArgumentException("invalid value passed to setRepeatCount" + repeatCount);
		}
		if (mState.mRepeatCount != repeatCount) {
			mState.mRepeatCount = repeatCount;
		}
	}

	/** @removed
	 * @deprecated Replaced with setRepeatCount to match other APIs.
	 */
	@java.lang.Deprecated
	public void setLoopCount(int loopCount) {
		setRepeatCount(loopCount);
	}

	/**
	 *  Retrieve the number of times the animation will repeat.
	 */
	public int getRepeatCount() {
		if (mState.mFrames == null) {
			throw new IllegalStateException("called getRepeatCount on empty AnimatedImageDrawable");
		}
		if (mState.mRepeatCount == REPEAT_UNDEFINED) {
			mState.mRepeatCount = mState.mFrames.mEncodedRepeatCount;
		}
		return mState.mRepeatCount;
	}

	/** @removed
	 * @deprecated Replaced with getRepeatCount to match other APIs.
	 */
	@java.lang.Deprecated
	public int getLoopCount(int loopCount) {
		return getRepeatCount();
	}

	/**
	 * Create an empty AnimatedImageDrawable.
	 */
	public AnimatedImageDrawable() {
		mState = new State(null);
	}

	private AnimatedImageDrawable(Frames frames, int srcDensity, int dstDensity) {
		mState = new State(frames);
		mIntrinsicWidth = scaleFromDensity(frames.mWidth, srcDensity, dstDensity);
		mIntrinsicHeight = scaleFromDensity(frames.mHeight, srcDensity, dstDensity);
	}

	/* AOSP Bitmap.scaleFromDensity() */
	private static int scaleFromDensity(int size, int sdensity, int tdensity) {
		if (sdensity == Bitmap.DENSITY_NONE || tdensity == Bitmap.DENSITY_NONE || sdensity == tdensity) {
			return size;
		}
		return ((size * tdensity) + (sdensity >> 1)) / sdensity;
	}

	/**
	 * @hide ATL's ImageDecoder#decodeDrawable for animated sources: the drawable for the encoded
	 * image in the stream, or null when it is not an animation (one frame, or not a GIF).
	 */
	public static AnimatedImageDrawable tlDecode(InputStream is, int srcDensity, int dstDensity) throws IOException {
		final Frames frames = GifDecoder.decode(readAll(is));
		if (frames == null || frames.mBitmaps.length < 2) {
			return null;
		}
		return new AnimatedImageDrawable(frames, srcDensity, dstDensity);
	}

	private static byte[] readAll(InputStream is) throws IOException {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final byte[] buf = new byte[16384];
		int n;
		while ((n = is.read(buf)) > 0) {
			out.write(buf, 0, n);
		}
		return out.toByteArray();
	}

	@Override
	public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
	    throws XmlPullParserException, IOException {
		super.inflate(r, parser, attrs, theme);

		final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.AnimatedImageDrawable);
		updateStateFromTypedArray(a, mSrcDensityOverride);
	}

	private void updateStateFromTypedArray(TypedArray a, int srcDensityOverride)
	    throws XmlPullParserException {
		State oldState = mState;
		final Resources r = a.getResources();
		final int srcResId = a.getResourceId(R.styleable.AnimatedImageDrawable_src, 0);
		if (srcResId != 0) {
			// Follow the density handling in BitmapDrawable.
			final TypedValue value = new TypedValue();
			r.getValueForDensity(srcResId, srcDensityOverride, value, true);
			if (srcDensityOverride > 0 && value.density > 0 && value.density != TypedValue.DENSITY_NONE) {
				if (value.density == srcDensityOverride) {
					value.density = r.getDisplayMetrics().densityDpi;
				} else {
					value.density = (value.density * r.getDisplayMetrics().densityDpi) / srcDensityOverride;
				}
			}

			int density = Bitmap.DENSITY_NONE;
			if (value.density == TypedValue.DENSITY_DEFAULT) {
				density = DisplayMetrics.DENSITY_DEFAULT;
			} else if (value.density != TypedValue.DENSITY_NONE) {
				density = value.density;
			}

			AnimatedImageDrawable drawable = null;
			try (InputStream is = r.openRawResource(srcResId, value)) {
				drawable = tlDecode(is, density, r.getDisplayMetrics().densityDpi);
			} catch (IOException e) {
				throw new XmlPullParserException(a.getPositionDescription() + ": <animated-image> requires a valid 'src' attribute", null, e);
			}

			if (drawable == null) {
				throw new XmlPullParserException(a.getPositionDescription() + ": <animated-image> did not decode animated");
			}

			// This may have previously been set without a src if we were waiting for a theme.
			final int repeatCount = mState.mRepeatCount;
			// Transfer the state of other to this one. other will be discarded.
			mState = drawable.mState;
			drawable.mState = null;
			mIntrinsicWidth = drawable.mIntrinsicWidth;
			mIntrinsicHeight = drawable.mIntrinsicHeight;
			if (repeatCount != REPEAT_UNDEFINED) {
				this.setRepeatCount(repeatCount);
			}
		}

		mState.mThemeAttrs = a.extractThemeAttrs();
		if (mState.mFrames == null && (mState.mThemeAttrs == null || mState.mThemeAttrs[R.styleable.AnimatedImageDrawable_src] == 0)) {
			throw new XmlPullParserException(a.getPositionDescription() + ": <animated-image> requires a valid 'src' attribute");
		}

		mState.mAutoMirrored = a.getBoolean(R.styleable.AnimatedImageDrawable_autoMirrored, oldState.mAutoMirrored);

		int repeatCount = a.getInt(R.styleable.AnimatedImageDrawable_repeatCount, REPEAT_UNDEFINED);
		if (repeatCount != REPEAT_UNDEFINED) {
			this.setRepeatCount(repeatCount);
		}

		boolean autoStart = a.getBoolean(R.styleable.AnimatedImageDrawable_autoStart, false);
		if (autoStart && mState.mFrames != null) {
			this.start();
		}
	}

	@Override
	public int getIntrinsicWidth() {
		return mIntrinsicWidth;
	}

	@Override
	public int getIntrinsicHeight() {
		return mIntrinsicHeight;
	}

	@Override
	public void draw(Canvas canvas) {
		if (mState.mFrames == null) {
			throw new IllegalStateException("called draw on empty AnimatedImageDrawable");
		}

		if (mStarting) {
			mStarting = false;
			postOnAnimationStart();
		}

		final Frames frames = mState.mFrames;
		long nextUpdate = 0;
		if (mRunning) {
			final long now = SystemClock.uptimeMillis();
			while (now >= mFrameStart + frames.mDurations[mFrame]) {
				final int next = mFrame + 1;
				if (next < frames.mBitmaps.length) {
					mFrameStart += frames.mDurations[mFrame];
					mFrame = next;
					continue;
				}
				final int repeatCount = getRepeatCount();
				if (repeatCount != REPEAT_INFINITE && mRepeatsDone >= repeatCount) {
					// the last frame stays up and the animation is finished
					mRunning = false;
					nextUpdate = -1;
					break;
				}
				mRepeatsDone++;
				mFrameStart += frames.mDurations[mFrame];
				mFrame = 0;
				if (now - mFrameStart > 10000) {
					mFrameStart = now; // a long pause (not drawn): do not replay every frame
				}
			}
			if (mRunning) {
				nextUpdate = mFrameStart + frames.mDurations[mFrame] - now;
			}
		}

		final Rect bounds = getBounds();
		final Bitmap bitmap = frames.mBitmaps[mFrame];
		final boolean pushed = mAlpha < 255 && canvas instanceof GskCanvas && ((GskCanvas) canvas).tlPushColorFilter(alphaFilter());
		if (!(canvas instanceof GskCanvas)) {
			mPaint.setAlpha(mAlpha);
		}
		mPaint.setColorFilter(mColorFilter);
		mDst.set(bounds);
		if (mMirrored) {
			canvas.save();
			canvas.translate(bounds.left + bounds.right, 0);
			canvas.scale(-1.0f, 1.0f);
		}
		canvas.drawBitmap(bitmap, null, mDst, mPaint);
		if (mMirrored) {
			canvas.restore();
		}
		if (pushed) {
			((GskCanvas) canvas).tlPopGroup();
		}

		if (nextUpdate > 0) {
			if (mRunnable == null) {
				mRunnable = this::invalidateSelf;
			}
			scheduleSelf(mRunnable, nextUpdate + SystemClock.uptimeMillis());
		} else if (nextUpdate == -1) {
			postOnAnimationEnd();
		}
	}

	private PorterDuffColorFilter alphaFilter() {
		if (mAlphaFilter == null || mAlphaFilter.getColor() != mAlpha << 24) {
			mAlphaFilter = new PorterDuffColorFilter(mAlpha << 24, PorterDuff.Mode.DST_IN);
		}
		return mAlphaFilter;
	}

	@Override
	public void setAlpha(int alpha) {
		if (alpha < 0 || alpha > 255) {
			throw new IllegalArgumentException("Alpha must be between 0 and 255! provided " + alpha);
		}
		if (mState.mFrames == null) {
			throw new IllegalStateException("called setAlpha on empty AnimatedImageDrawable");
		}
		mAlpha = alpha;
		invalidateSelf();
	}

	@Override
	public int getAlpha() {
		if (mState.mFrames == null) {
			throw new IllegalStateException("called getAlpha on empty AnimatedImageDrawable");
		}
		return mAlpha;
	}

	@Override
	public void setColorFilter(ColorFilter colorFilter) {
		if (mState.mFrames == null) {
			throw new IllegalStateException("called setColorFilter on empty AnimatedImageDrawable");
		}
		if (colorFilter != mColorFilter) {
			mColorFilter = colorFilter;
			invalidateSelf();
		}
	}

	@Override
	public ColorFilter getColorFilter() {
		return mColorFilter;
	}

	@Override
	public int getOpacity() {
		return PixelFormat.TRANSLUCENT;
	}

	@Override
	public void setAutoMirrored(boolean mirrored) {
		if (mState.mAutoMirrored != mirrored) {
			mState.mAutoMirrored = mirrored;
			if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL && mState.mFrames != null) {
				mMirrored = mirrored;
				invalidateSelf();
			}
		}
	}

	@Override
	public boolean onLayoutDirectionChanged(int layoutDirection) {
		if (!mState.mAutoMirrored || mState.mFrames == null) {
			return false;
		}
		mMirrored = layoutDirection == View.LAYOUT_DIRECTION_RTL;
		return true;
	}

	@Override
	public final boolean isAutoMirrored() {
		return mState.mAutoMirrored;
	}

	// Animatable overrides
	/**
	 *  Return whether the animation is currently running.
	 */
	@Override
	public boolean isRunning() {
		if (mState.mFrames == null) {
			throw new IllegalStateException("called isRunning on empty AnimatedImageDrawable");
		}
		return mRunning;
	}

	/**
	 *  Start the animation.
	 *
	 *  <p>Does nothing if the animation is already running. If the animation is stopped,
	 *  this will reset it.</p>
	 */
	@Override
	public void start() {
		if (mState.mFrames == null) {
			throw new IllegalStateException("called start on empty AnimatedImageDrawable");
		}
		if (!mRunning) {
			mRunning = true;
			mFrame = 0;
			mRepeatsDone = 0;
			mFrameStart = SystemClock.uptimeMillis();
			mStarting = true;
			invalidateSelf();
		}
	}

	/**
	 *  Stop the animation.
	 *
	 *  <p>If the animation is stopped, it will continue to display the frame
	 *  it was displaying when stopped.</p>
	 */
	@Override
	public void stop() {
		if (mState.mFrames == null) {
			throw new IllegalStateException("called stop on empty AnimatedImageDrawable");
		}
		if (mRunning) {
			mRunning = false;
			if (mRunnable != null) {
				unscheduleSelf(mRunnable);
			}
			postOnAnimationEnd();
		}
	}

	// Animatable2 overrides
	private ArrayList<Animatable2.AnimationCallback> mAnimationCallbacks = null;

	@Override
	public void registerAnimationCallback(AnimationCallback callback) {
		if (callback == null) {
			return;
		}
		if (mAnimationCallbacks == null) {
			mAnimationCallbacks = new ArrayList<Animatable2.AnimationCallback>();
		}
		if (!mAnimationCallbacks.contains(callback)) {
			mAnimationCallbacks.add(callback);
		}
	}

	@Override
	public boolean unregisterAnimationCallback(AnimationCallback callback) {
		if (callback == null || mAnimationCallbacks == null || !mAnimationCallbacks.remove(callback)) {
			return false;
		}
		if (mAnimationCallbacks.isEmpty()) {
			clearAnimationCallbacks();
		}
		return true;
	}

	@Override
	public void clearAnimationCallbacks() {
		if (mAnimationCallbacks != null) {
			mAnimationCallbacks = null;
		}
	}

	private void postOnAnimationStart() {
		if (mAnimationCallbacks == null) {
			return;
		}
		getHandler().post(() -> {
			if (mAnimationCallbacks == null) {
				return;
			}
			for (Animatable2.AnimationCallback callback : new ArrayList<>(mAnimationCallbacks)) {
				callback.onAnimationStart(this);
			}
		});
	}

	private void postOnAnimationEnd() {
		if (mAnimationCallbacks == null) {
			return;
		}
		getHandler().post(() -> {
			if (mAnimationCallbacks == null) {
				return;
			}
			for (Animatable2.AnimationCallback callback : new ArrayList<>(mAnimationCallbacks)) {
				callback.onAnimationEnd(this);
			}
		});
	}

	private Handler getHandler() {
		if (mHandler == null) {
			mHandler = new Handler(Looper.getMainLooper());
		}
		return mHandler;
	}

	@Override
	protected void onBoundsChange(Rect bounds) {
		/* frames are drawn scaled to the bounds at draw time (AOSP scales the SkAnimatedImage) */
	}

	/**
	 * GIF87a/GIF89a decoder producing fully composited ARGB frames (what SkAnimatedImage shows):
	 * global/local colour tables, interlacing, the graphic control extension (delay, transparent
	 * index, disposal: keep, restore to background (transparent, as Skia does), restore to
	 * previous) and the NETSCAPE2.0/ANIMEXTS1.0 loop count.
	 */
	static final class GifDecoder {
		private final byte[] mData;
		private int mPos;

		private GifDecoder(byte[] data) {
			mData = data;
		}

		private int u8() {
			if (mPos >= mData.length)
				throw new IndexOutOfBoundsException("truncated GIF");
			return mData[mPos++] & 0xFF;
		}

		private int u16() {
			int lo = u8();
			return lo | (u8() << 8);
		}

		private int[] colorTable(int size) {
			int[] table = new int[size];
			for (int i = 0; i < size; i++)
				table[i] = 0xFF000000 | (u8() << 16) | (u8() << 8) | u8();
			return table;
		}

		private void skipSubBlocks() {
			int n;
			while ((n = u8()) != 0)
				mPos += n;
		}

		static Frames decode(byte[] data) {
			if (data.length < 13 || data[0] != 'G' || data[1] != 'I' || data[2] != 'F')
				return null;
			try {
				return new GifDecoder(data).run();
			} catch (RuntimeException e) {
				return null;
			}
		}

		private Frames run() {
			mPos = 6;
			final int width = u16(), height = u16();
			final int flags = u8();
			u8(); // background colour index: Skia treats the background as transparent
			u8(); // pixel aspect ratio
			if (width <= 0 || height <= 0)
				return null;
			int[] global = (flags & 0x80) != 0 ? colorTable(2 << (flags & 7)) : null;

			final ArrayList<Bitmap> bitmaps = new ArrayList<>();
			final ArrayList<Integer> durations = new ArrayList<>();
			int loopCount = 0; // no NETSCAPE block: the animation plays once
			int delay = 0, transparent = -1, disposal = 0;
			final int[] canvas = new int[width * height];
			int[] previous = null;

			loop:
			while (mPos < mData.length) {
				final int block = u8();
				switch (block) {
					case 0x21: { // extension
						final int label = u8();
						if (label == 0xF9) {
							final int len = u8();
							final int start = mPos;
							final int gflags = u8();
							delay = u16() * 10;
							final int index = u8();
							mPos = start + len;
							skipSubBlocks();
							disposal = (gflags >> 2) & 7;
							transparent = (gflags & 1) != 0 ? index : -1;
						} else if (label == 0xFF) {
							final int len = u8();
							final String app = new String(mData, mPos, Math.min(len, mData.length - mPos), java.nio.charset.StandardCharsets.ISO_8859_1);
							mPos += len;
							if (app.startsWith("NETSCAPE2.0") || app.startsWith("ANIMEXTS1.0")) {
								int n;
								while ((n = u8()) != 0) {
									if (n >= 3 && (mData[mPos] & 7) == 1) {
										final int count = (mData[mPos + 1] & 0xFF) | ((mData[mPos + 2] & 0xFF) << 8);
										loopCount = count == 0 ? REPEAT_INFINITE : count;
									}
									mPos += n;
								}
							} else {
								skipSubBlocks();
							}
						} else {
							skipSubBlocks();
						}
						break;
					}
					case 0x2C: { // image
						final int ix = u16(), iy = u16(), iw = u16(), ih = u16();
						final int iflags = u8();
						final int[] table = (iflags & 0x80) != 0 ? colorTable(2 << (iflags & 7)) : global;
						final boolean interlaced = (iflags & 0x40) != 0;
						final int minCodeSize = u8();
						final byte[] indices = lzw(minCodeSize, iw * ih);
						if (table == null)
							return null;
						if (disposal == 3)
							previous = canvas.clone();
						for (int row = 0; row < ih; row++) {
							final int y = iy + (interlaced ? interlacedRow(row, ih) : row);
							if (y < 0 || y >= height)
								continue;
							for (int col = 0; col < iw; col++) {
								final int x = ix + col;
								if (x < 0 || x >= width)
									continue;
								final int index = indices[row * iw + col] & 0xFF;
								if (index == transparent || index >= table.length)
									continue;
								canvas[y * width + x] = table[index];
							}
						}
						bitmaps.add(Bitmap.createBitmap(canvas.clone(), width, height, Bitmap.Config.ARGB_8888));
						/* browsers and Skia's GIF codec show frames of 0 or 10 ms for 100 ms */
						durations.add(delay <= 10 ? 100 : delay);
						if (disposal == 2) {
							for (int y = Math.max(0, iy); y < Math.min(height, iy + ih); y++)
								for (int x = Math.max(0, ix); x < Math.min(width, ix + iw); x++)
									canvas[y * width + x] = 0;
						} else if (disposal == 3 && previous != null) {
							System.arraycopy(previous, 0, canvas, 0, canvas.length);
						}
						delay = 0;
						transparent = -1;
						disposal = 0;
						break;
					}
					case 0x3B: // trailer
						break loop;
					default:
						break loop; // corrupt data: keep the frames decoded so far
				}
			}
			if (bitmaps.isEmpty())
				return null;
			final int[] d = new int[durations.size()];
			for (int i = 0; i < d.length; i++)
				d[i] = durations.get(i);
			return new Frames(bitmaps.toArray(new Bitmap[0]), d, width, height, loopCount);
		}

		/** the image row stored at position row of an interlaced image (passes 8/0, 8/4, 4/2, 2/1) */
		private static int interlacedRow(int row, int height) {
			final int pass1 = (height + 7) / 8;
			if (row < pass1)
				return row * 8;
			row -= pass1;
			final int pass2 = (height + 3) / 8;
			if (row < pass2)
				return row * 8 + 4;
			row -= pass2;
			final int pass3 = (height + 1) / 4;
			if (row < pass3)
				return row * 4 + 2;
			row -= pass3;
			return row * 2 + 1;
		}

		/** variable-length-code LZW decoding of the image data sub-blocks into colour indices */
		private byte[] lzw(int minCodeSize, int pixelCount) {
			final byte[] out = new byte[pixelCount];
			if (minCodeSize < 1 || minCodeSize > 11) {
				skipSubBlocks();
				return out;
			}
			final int clear = 1 << minCodeSize, end = clear + 1;
			final short[] prefix = new short[4096];
			final byte[] suffix = new byte[4096];
			final byte[] stack = new byte[4097];
			int codeSize = minCodeSize + 1, codeMask = (1 << codeSize) - 1, avail = clear + 2;
			int oldCode = -1, first = 0, outPos = 0;
			for (int i = 0; i < clear; i++)
				suffix[i] = (byte) i;
			int bits = 0, datum = 0, blockLeft = 0;
			boolean done = false, terminated = false;
			while (!done) {
				if (blockLeft == 0) {
					blockLeft = u8();
					if (blockLeft == 0) {
						terminated = true; // the block terminator ends the image data
						break;
					}
				}
				datum |= u8() << bits;
				bits += 8;
				blockLeft--;
				while (bits >= codeSize) {
					int code = datum & codeMask;
					datum >>= codeSize;
					bits -= codeSize;
					if (code == clear) {
						codeSize = minCodeSize + 1;
						codeMask = (1 << codeSize) - 1;
						avail = clear + 2;
						oldCode = -1;
						continue;
					}
					if (code == end) {
						done = true;
						break;
					}
					if (oldCode == -1) {
						if (code >= clear)
							continue;
						if (outPos < pixelCount)
							out[outPos++] = suffix[code];
						oldCode = code;
						first = code;
						continue;
					}
					final int inCode = code;
					int top = 0;
					if (code >= avail) {
						stack[top++] = (byte) first;
						code = oldCode;
					}
					while (code >= clear) {
						if (top >= stack.length - 1)
							break;
						stack[top++] = suffix[code];
						code = prefix[code];
					}
					first = suffix[code] & 0xFF;
					stack[top++] = (byte) first;
					if (avail < 4096) {
						prefix[avail] = (short) oldCode;
						suffix[avail] = (byte) first;
						avail++;
						if ((avail & codeMask) == 0 && avail < 4096) {
							codeSize++;
							codeMask = (1 << codeSize) - 1;
						}
					}
					oldCode = inCode;
					while (top > 0 && outPos < pixelCount)
						out[outPos++] = stack[--top];
					top = 0;
				}
			}
			if (!terminated) {
				// end code seen: skip the rest of this sub-block and the remaining ones
				mPos += blockLeft;
				skipSubBlocks();
			}
			return out;
		}
	}
}
