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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * AOSP Movie (deprecated in API 28 in favour of AnimatedImageDrawable): an animated image (GIF)
 * whose frame is chosen with setTime and drawn with draw. teslalinux: decoded by gdk-pixbuf's
 * animation loader (native/atl_gfx_canvas.c).
 */
@Deprecated
public class Movie {
	private long mNativeMovie;
	private final int mWidth, mHeight, mDuration;
	private int mTime;
	private Bitmap mFrame;
	private int mFrameTime = -1;

	private Movie(long nativeMovie) {
		mNativeMovie = nativeMovie;
		int[] info = native_info(nativeMovie);
		mWidth = info[0];
		mHeight = info[1];
		mDuration = info[2];
	}

	public int width() {
		return mWidth;
	}

	public int height() {
		return mHeight;
	}

	/** GIF frames may have transparent pixels: a movie is not opaque. */
	public boolean isOpaque() {
		return false;
	}

	/** The total length of one loop in milliseconds (0 for a still image). */
	public int duration() {
		return mDuration;
	}

	/** Selects the frame shown at relativeMilliseconds into the loop; true if the frame changed. */
	public boolean setTime(int relativeMilliseconds) {
		int t = mDuration > 0 ? Math.floorMod(relativeMilliseconds, mDuration) : 0;
		boolean changed = t != mTime;
		mTime = t;
		return changed;
	}

	public void draw(Canvas canvas, float x, float y, Paint paint) {
		if (mFrameTime != mTime || mFrame == null) {
			mFrame = new Bitmap(native_frame(mNativeMovie, mTime));
			mFrameTime = mTime;
		}
		canvas.drawBitmap(mFrame, x, y, paint);
	}

	public void draw(Canvas canvas, float x, float y) {
		draw(canvas, x, y, null);
	}

	/** null if the stream is not an image gdk-pixbuf can decode. */
	public static Movie decodeStream(InputStream is) {
		if (is == null)
			return null;
		try {
			byte[] data = BitmapFactory.tlReadAll(is);
			return decodeByteArray(data, 0, data.length);
		} catch (IOException e) {
			return null;
		}
	}

	public static Movie decodeByteArray(byte[] data, int offset, int length) {
		if (data == null)
			throw new NullPointerException();
		if (offset < 0 || length < 0 || offset + length > data.length)
			throw new ArrayIndexOutOfBoundsException();
		long movie = native_decode(data, offset, length);
		return movie == 0 ? null : new Movie(movie);
	}

	public static Movie decodeFile(String pathName) {
		try (InputStream is = new FileInputStream(pathName)) {
			return decodeStream(is);
		} catch (IOException e) {
			return null;
		}
	}

	@SuppressWarnings("deprecation")
	@Override
	protected void finalize() throws Throwable {
		try {
			native_destroy(mNativeMovie);
			mNativeMovie = 0;
		} finally {
			super.finalize();
		}
	}

	private static native long native_decode(byte[] data, int offset, int length);
	/** {width, height, duration ms} */
	private static native int[] native_info(long movie);
	/** a new GdkTexture of the frame at the time (owned by the caller) */
	private static native long native_frame(long movie, int timeMs);
	private static native void native_destroy(long movie);
}
