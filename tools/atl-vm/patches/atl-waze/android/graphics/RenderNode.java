/*
 * Copyright (C) 2014 The Android Open Source Project
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

import java.util.concurrent.atomic.AtomicLong;

/**
 * RenderNode is used to build hardware accelerated rendering hierarchies (AOSP public API 29).
 *
 * On ATL a node's display list is a Gsk render node recorded through {@link RecordingCanvas};
 * the hidden android.view.RenderNode this class extends owns that node, applies the transform
 * (scale, rotation, translation, pivot), clips to bounds and patches child nodes in when the node
 * is drawn. AOSP keeps position (left/top) apart from translation and reports getMatrix() without
 * the position; the superclass folds position into its translation, so this class keeps both sets
 * of values and feeds the superclass their sum.
 */
public final class RenderNode extends android.view.RenderNode {
	private static final AtomicLong sNextId = new AtomicLong(1);

	private final String mName;
	private final long mUniqueId = sNextId.getAndIncrement();
	private RecordingCanvas mCurrentRecordingCanvas;

	private int mLeft, mTop, mRight, mBottom;
	private float mTranslationX, mTranslationY, mTranslationZ;
	private float mRotationZ;
	private boolean mPivotExplicitlySet;
	private boolean mClipRectSet;
	private Rect mClipRect;
	private boolean mUseCompositingLayer;
	private Paint mLayerPaint;
	private int mSpotShadowColor = Color.BLACK;
	private int mAmbientShadowColor = Color.BLACK;
	private boolean mProjectBackwards, mProjectionReceiver;
	private boolean mForceDarkAllowed = true;
	private Matrix mStaticMatrix, mAnimationMatrix;
	private RenderEffect mRenderEffect;
	private int mUsageHint;

	public static final int USAGE_BACKGROUND = 1;

	/** Creates a new RenderNode that can be used to record batches of drawing operations. */
	public RenderNode(String name) {
		mName = name;
	}

	// ---- recording -----------------------------------------------------------------------------

	public RecordingCanvas beginRecording(int width, int height) {
		if (mCurrentRecordingCanvas != null)
			throw new IllegalStateException("Recording currently in progress - missing #endRecording() call?");
		mCurrentRecordingCanvas = new RecordingCanvas(this, atlCreateSnapshot(width, height), width, height);
		return mCurrentRecordingCanvas;
	}

	public RecordingCanvas beginRecording() {
		return beginRecording(getWidth(), getHeight());
	}

	public void endRecording() {
		if (mCurrentRecordingCanvas == null)
			throw new IllegalStateException("No recording in progress, forgot to call #beginRecording()?");
		RecordingCanvas canvas = mCurrentRecordingCanvas;
		mCurrentRecordingCanvas = null;
		canvas.tlCloseRecording();
		long snapshot = canvas.snapshot;
		canvas.snapshot = 0;
		atlEndRecording(snapshot);
	}

	@Deprecated
	public RecordingCanvas start(int width, int height) {
		return beginRecording(width, height);
	}

	@Deprecated
	public void end(RecordingCanvas canvas) {
		if (canvas != mCurrentRecordingCanvas)
			throw new IllegalArgumentException("Wrong canvas");
		endRecording();
	}

	public boolean hasDisplayList() {
		return isValid();
	}

	// ---- matrix ---------------------------------------------------------------------------------

	public boolean hasIdentityMatrix() {
		return mTranslationX == 0 && mTranslationY == 0 && mRotationZ == 0 && getRotationX() == 0 && getRotationY() == 0
		    && getScaleX() == 1 && getScaleY() == 1;
	}

	/** The transform of this node: translation, rotation and scale about the pivot; position excluded (AOSP). */
	@Override
	public void getMatrix(Matrix outMatrix) {
		outMatrix.reset();
		outMatrix.setTranslate(mTranslationX, mTranslationY);
		outMatrix.preRotate(mRotationZ, getPivotX(), getPivotY());
		outMatrix.preScale(getScaleX(), getScaleY(), getPivotX(), getPivotY());
	}

	public void getInverseMatrix(Matrix outMatrix) {
		Matrix m = new Matrix();
		getMatrix(m);
		if (!m.invert(outMatrix))
			outMatrix.reset();
	}

	// ---- layers ---------------------------------------------------------------------------------

	@Override
	public boolean setLayerType(int layerType) {
		return super.setLayerType(layerType);
	}

	@Override
	public boolean setLayerPaint(Paint paint) {
		boolean changed = mLayerPaint != paint;
		mLayerPaint = paint;
		super.setLayerPaint(paint);
		return changed;
	}

	public boolean setUseCompositingLayer(boolean forceToLayer, Paint paint) {
		boolean changed = mUseCompositingLayer != forceToLayer || mLayerPaint != paint;
		mUseCompositingLayer = forceToLayer;
		mLayerPaint = paint;
		tlSetForceLayer(forceToLayer, paint);
		return changed;
	}

	public boolean getUseCompositingLayer() {
		return mUseCompositingLayer;
	}

	// ---- clipping / outline ---------------------------------------------------------------------

	public boolean setClipRect(Rect rect) {
		boolean changed = (rect == null) != (mClipRect == null) || (rect != null && !rect.equals(mClipRect));
		mClipRect = rect == null ? null : new Rect(rect);
		mClipRectSet = rect != null;
		return changed;
	}

	public boolean setProjectBackwards(boolean shouldProject) {
		boolean changed = mProjectBackwards != shouldProject;
		mProjectBackwards = shouldProject;
		return changed;
	}

	public boolean setProjectionReceiver(boolean shouldRecieve) {
		boolean changed = mProjectionReceiver != shouldRecieve;
		mProjectionReceiver = shouldRecieve;
		return changed;
	}

	public boolean clearStretch() {
		return false;
	}

	public boolean stretch(float vecX, float vecY, float maxStretchAmountX, float maxStretchAmountY) {
		return false;
	}

	public boolean hasShadow() {
		return getElevation() > 0 || mTranslationZ > 0;
	}

	public boolean setSpotShadowColor(int color) {
		boolean changed = mSpotShadowColor != color;
		mSpotShadowColor = color;
		return changed;
	}

	public int getSpotShadowColor() {
		return mSpotShadowColor;
	}

	public boolean setAmbientShadowColor(int color) {
		boolean changed = mAmbientShadowColor != color;
		mAmbientShadowColor = color;
		return changed;
	}

	public int getAmbientShadowColor() {
		return mAmbientShadowColor;
	}

	public boolean setRevealClip(boolean shouldClip, float x, float y, float radius) {
		return false;
	}

	public boolean setStaticMatrix(Matrix matrix) {
		mStaticMatrix = matrix;
		return true;
	}

	public boolean setAnimationMatrix(Matrix matrix) {
		mAnimationMatrix = matrix;
		return true;
	}

	public Matrix getAnimationMatrix() {
		return mAnimationMatrix;
	}

	public boolean setRenderEffect(RenderEffect renderEffect) {
		boolean changed = mRenderEffect != renderEffect;
		mRenderEffect = renderEffect;
		return changed;
	}

	public void setUsageHint(int usageHint) {
		mUsageHint = usageHint;
	}

	// ---- transforms -----------------------------------------------------------------------------

	public boolean setTranslationX(float translationX) {
		boolean changed = mTranslationX != translationX;
		mTranslationX = translationX;
		super.setTranslationX(mLeft + translationX);
		return changed;
	}

	public float getTranslationX() {
		return mTranslationX;
	}

	public boolean setTranslationY(float translationY) {
		boolean changed = mTranslationY != translationY;
		mTranslationY = translationY;
		super.setTranslationY(mTop + translationY);
		return changed;
	}

	public float getTranslationY() {
		return mTranslationY;
	}

	public boolean setTranslationZ(float translationZ) {
		boolean changed = mTranslationZ != translationZ;
		mTranslationZ = translationZ;
		return changed;
	}

	public float getTranslationZ() {
		return mTranslationZ;
	}

	public boolean setRotationZ(float rotation) {
		boolean changed = mRotationZ != rotation;
		mRotationZ = rotation;
		super.setRotation(rotation);
		return changed;
	}

	public float getRotationZ() {
		return mRotationZ;
	}

	@Override
	public boolean setPivotX(float pivotX) {
		mPivotExplicitlySet = true;
		return super.setPivotX(pivotX);
	}

	@Override
	public boolean setPivotY(float pivotY) {
		mPivotExplicitlySet = true;
		return super.setPivotY(pivotY);
	}

	public boolean isPivotExplicitlySet() {
		return mPivotExplicitlySet;
	}

	/** Back to the default pivot, the centre of the node. */
	public boolean resetPivot() {
		mPivotExplicitlySet = false;
		boolean changed = super.setPivotX(getWidth() / 2f);
		changed |= super.setPivotY(getHeight() / 2f);
		return changed;
	}

	// ---- position -------------------------------------------------------------------------------

	public boolean setLeft(int left) {
		return setLeftTopRightBottom(left, mTop, mRight, mBottom);
	}

	public boolean setTop(int top) {
		return setLeftTopRightBottom(mLeft, top, mRight, mBottom);
	}

	public boolean setRight(int right) {
		return setLeftTopRightBottom(mLeft, mTop, right, mBottom);
	}

	public boolean setBottom(int bottom) {
		return setLeftTopRightBottom(mLeft, mTop, mRight, bottom);
	}

	public int getLeft() {
		return mLeft;
	}

	public int getTop() {
		return mTop;
	}

	public int getRight() {
		return mRight;
	}

	public int getBottom() {
		return mBottom;
	}

	public int getWidth() {
		return mRight - mLeft;
	}

	public int getHeight() {
		return mBottom - mTop;
	}

	@Override
	public boolean setLeftTopRightBottom(int left, int top, int right, int bottom) {
		boolean changed = mLeft != left || mTop != top || mRight != right || mBottom != bottom;
		mLeft = left;
		mTop = top;
		mRight = right;
		mBottom = bottom;
		super.setLeftTopRightBottom(0, 0, right - left, bottom - top);
		super.setTranslationX(mLeft + mTranslationX);
		super.setTranslationY(mTop + mTranslationY);
		if (!mPivotExplicitlySet) {
			super.setPivotX((right - left) / 2f);
			super.setPivotY((bottom - top) / 2f);
		}
		return changed;
	}

	public boolean setPosition(int left, int top, int right, int bottom) {
		return setLeftTopRightBottom(left, top, right, bottom);
	}

	public boolean setPosition(Rect position) {
		return setLeftTopRightBottom(position.left, position.top, position.right, position.bottom);
	}

	@Override
	public boolean offsetLeftAndRight(int offset) {
		return setLeftTopRightBottom(mLeft + offset, mTop, mRight + offset, mBottom);
	}

	@Override
	public boolean offsetTopAndBottom(int offset) {
		return setLeftTopRightBottom(mLeft, mTop + offset, mRight, mBottom + offset);
	}

	// ---- misc -----------------------------------------------------------------------------------

	public long getUniqueId() {
		return mUniqueId;
	}

	public void output() {
	}

	public long computeApproximateMemoryUsage() {
		return 0;
	}

	public long computeApproximateMemoryAllocated() {
		return 0;
	}

	public boolean setForceDarkAllowed(boolean allow) {
		boolean changed = mForceDarkAllowed != allow;
		mForceDarkAllowed = allow;
		return changed;
	}

	public boolean isForceDarkAllowed() {
		return mForceDarkAllowed;
	}

	@Override
	public String toString() {
		return "RenderNode(" + mName + ", id=" + mUniqueId + ")";
	}
}
