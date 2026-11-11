/*
 * Copyright (C) 2019 The Android Open Source Project
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

/**
 * A Canvas implementation that records view system drawing operations for deferred rendering
 * (AOSP API 29). Here the recording target is a GtkSnapshot: every draw call becomes a Gsk render
 * node, and {@link RenderNode#endRecording()} turns the snapshot into the node's display list.
 * Obtained from {@link RenderNode#beginRecording(int, int)}.
 */
public final class RecordingCanvas extends GskCanvas {
	/** Maximum bitmap size the recording can hold, as in AOSP (100 MB). */
	public static final int MAX_BITMAP_SIZE = 100 * 1024 * 1024;

	private final android.view.RenderNode mNode;
	private final int mWidth;
	private final int mHeight;

	RecordingCanvas(android.view.RenderNode node, long snapshot, int width, int height) {
		super(snapshot);
		// the recording's snapshot belongs to this canvas: transfer modes can compose with its content
		tlBaseComposable = true;
		mNode = node;
		mWidth = width;
		mHeight = height;
	}

	@Override
	public boolean isHardwareAccelerated() {
		return true;
	}

	@Override
	public void setBitmap(Bitmap bitmap) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isOpaque() {
		return false;
	}

	@Override
	public int getWidth() {
		return mWidth;
	}

	@Override
	public int getHeight() {
		return mHeight;
	}

	@Override
	public int getMaximumBitmapWidth() {
		return MAX_BITMAP_SIZE;
	}

	@Override
	public int getMaximumBitmapHeight() {
		return MAX_BITMAP_SIZE;
	}

	/** Z-ordered drawing of children by elevation (shadows): not reordered here. */
	@Override
	public void enableZ() {
	}

	@Override
	public void disableZ() {
	}

	/** Draws another node's display list: recorded as a stub that is patched when this node is drawn. */
	@Override
	public void drawRenderNode(android.view.RenderNode node) {
		mNode.atlRecordChild(node, snapshot);
	}
}
