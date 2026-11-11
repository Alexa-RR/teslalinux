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
 * A Picture records drawing calls (via the Canvas returned by beginRecording) and can then be drawn
 * onto a canvas (AOSP API). Skia records a display list; here the recording is rasterised into a
 * bitmap of the given size as it is drawn, which is what drawing the picture later plays back.
 */
public class Picture {
	private Bitmap mBitmap;
	private Canvas mRecordingCanvas;
	private int mWidth, mHeight;

	public Picture() {
	}

	/** Creates a Picture with the same contents as src. */
	public Picture(Picture src) {
		if (src != null && src.mBitmap != null) {
			mBitmap = Bitmap.createBitmap(src.mBitmap);
			mWidth = src.mWidth;
			mHeight = src.mHeight;
		}
	}

	/** Start recording: all drawing on the returned Canvas goes into this picture until endRecording. */
	public Canvas beginRecording(int width, int height) {
		if (mRecordingCanvas != null)
			throw new IllegalStateException("Picture is already recording, must call #endRecording()");
		mWidth = width;
		mHeight = height;
		mBitmap = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888);
		mRecordingCanvas = new Canvas(mBitmap);
		return mRecordingCanvas;
	}

	public void endRecording() {
		mRecordingCanvas = null;
	}

	public int getWidth() {
		return mWidth;
	}

	public int getHeight() {
		return mHeight;
	}

	/** Whether the picture holds drawing operations only a hardware canvas can play back: never here. */
	public boolean requiresHardwareAcceleration() {
		return false;
	}

	/** Draw this picture on the canvas. */
	public void draw(Canvas canvas) {
		if (mRecordingCanvas != null)
			endRecording();
		if (mBitmap != null)
			canvas.drawBitmap(mBitmap, 0, 0, null);
	}
}
