/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Shader.TileMode;

import java.util.Objects;

/**
 * Intermediate rendering step used to render drawing commands with a corresponding visual effect.
 *
 * teslalinux: AOSP builds these on top of Skia image filters through a native handle. ATL renders
 * views with GSK, which has no image-filter node for an arbitrary effect, so a RenderEffect here is
 * the description of the effect (kind, parameters, chained inputs) exactly as the app composed it.
 * View.setRenderEffect keeps it on the view; applying it when drawing is a GSK-side task (see
 * TODO-real.md). Parameter validation matches AOSP.
 */
public final class RenderEffect {
	public static final int KIND_OFFSET = 1;
	public static final int KIND_BLUR = 2;
	public static final int KIND_BITMAP = 3;
	public static final int KIND_COLOR_FILTER = 4;
	public static final int KIND_BLEND_MODE = 5;
	public static final int KIND_CHAIN = 6;
	public static final int KIND_SHADER = 7;
	public static final int KIND_RUNTIME_SHADER = 8;

	private final int mKind;
	private final float[] mParams;
	private final Object[] mObjects;
	private final RenderEffect mInput;
	private final RenderEffect mSecondInput;

	private RenderEffect(int kind, float[] params, Object[] objects, RenderEffect input, RenderEffect secondInput) {
		mKind = kind;
		mParams = params;
		mObjects = objects;
		mInput = input;
		mSecondInput = secondInput;
	}

	/** Create a {@link RenderEffect} instance that will offset the drawing content by the provided x and y offset. */
	@NonNull
	public static RenderEffect createOffsetEffect(float offsetX, float offsetY) {
		return new RenderEffect(KIND_OFFSET, new float[] { offsetX, offsetY }, null, null, null);
	}

	/** Create a {@link RenderEffect} instance with the provided x and y offset, applied to the given input. */
	@NonNull
	public static RenderEffect createOffsetEffect(float offsetX, float offsetY, @NonNull RenderEffect input) {
		return new RenderEffect(KIND_OFFSET, new float[] { offsetX, offsetY }, null, Objects.requireNonNull(input), null);
	}

	/** Create a {@link RenderEffect} that blurs the contents of the optional input RenderEffect with the specified radius along the x and y axis. */
	@NonNull
	public static RenderEffect createBlurEffect(float radiusX, float radiusY, @NonNull RenderEffect inputEffect, @NonNull TileMode edgeTreatment) {
		return new RenderEffect(KIND_BLUR, new float[] { radiusX, radiusY, edgeTreatment.ordinal() }, new Object[] { edgeTreatment }, Objects.requireNonNull(inputEffect), null);
	}

	/** Create a {@link RenderEffect} that blurs the contents with the specified radius along the x and y axis. */
	@NonNull
	public static RenderEffect createBlurEffect(float radiusX, float radiusY, @NonNull TileMode edgeTreatment) {
		return new RenderEffect(KIND_BLUR, new float[] { radiusX, radiusY, edgeTreatment.ordinal() }, new Object[] { edgeTreatment }, null, null);
	}

	/** Create a {@link RenderEffect} that renders the contents of the input {@link Bitmap}. */
	@NonNull
	public static RenderEffect createBitmapEffect(@NonNull Bitmap bitmap) {
		int width = bitmap.getWidth();
		int height = bitmap.getHeight();
		return createBitmapEffect(bitmap, null, new Rect(0, 0, width, height));
	}

	/** Create a {@link RenderEffect} that renders the contents of the input {@link Bitmap} into the given destination rectangle. */
	@NonNull
	public static RenderEffect createBitmapEffect(@NonNull Bitmap bitmap, @Nullable Rect src, @NonNull Rect dst) {
		Objects.requireNonNull(bitmap);
		Objects.requireNonNull(dst);
		return new RenderEffect(KIND_BITMAP, null, new Object[] { bitmap, src, dst }, null, null);
	}

	/** Create a {@link RenderEffect} that applies the color filter to the provided RenderEffect. */
	@NonNull
	public static RenderEffect createColorFilterEffect(@NonNull ColorFilter colorFilter, @NonNull RenderEffect renderEffect) {
		return new RenderEffect(KIND_COLOR_FILTER, null, new Object[] { Objects.requireNonNull(colorFilter) }, Objects.requireNonNull(renderEffect), null);
	}

	/** Create a {@link RenderEffect} that applies the color filter to the contents. */
	@NonNull
	public static RenderEffect createColorFilterEffect(@NonNull ColorFilter colorFilter) {
		return new RenderEffect(KIND_COLOR_FILTER, null, new Object[] { Objects.requireNonNull(colorFilter) }, null, null);
	}

	/** Create a {@link RenderEffect} that is a composition of 2 other {@link RenderEffect} instances combined by the specified {@link BlendMode}. */
	@NonNull
	public static RenderEffect createBlendModeEffect(@NonNull RenderEffect dst, @NonNull RenderEffect src, @NonNull BlendMode blendMode) {
		return new RenderEffect(KIND_BLEND_MODE, null, new Object[] { Objects.requireNonNull(blendMode) }, Objects.requireNonNull(dst), Objects.requireNonNull(src));
	}

	/** Create a {@link RenderEffect} that composes 'inner' with 'outer', such that the results of 'inner' are treated as the source bitmap passed to 'outer'. */
	@NonNull
	public static RenderEffect createChainEffect(@NonNull RenderEffect outer, @NonNull RenderEffect inner) {
		return new RenderEffect(KIND_CHAIN, null, null, Objects.requireNonNull(outer), Objects.requireNonNull(inner));
	}

	/** Create a {@link RenderEffect} that renders the contents of the input {@link Shader}. */
	@NonNull
	public static RenderEffect createShaderEffect(@NonNull Shader shader) {
		return new RenderEffect(KIND_SHADER, null, new Object[] { Objects.requireNonNull(shader) }, null, null);
	}

	/** Create a {@link RenderEffect} that executes the provided {@link RuntimeShader} and passes the contents of the {@link android.graphics.RenderNode} as an input. */
	@NonNull
	public static RenderEffect createRuntimeShaderEffect(@NonNull Shader shader, @NonNull String uniformShaderName) {
		return new RenderEffect(KIND_RUNTIME_SHADER, null, new Object[] { Objects.requireNonNull(shader), Objects.requireNonNull(uniformShaderName) }, null, null);
	}

	/** @hide the kind of effect (KIND_*) */
	public int getKind() {
		return mKind;
	}

	/** @hide numeric parameters of the effect (offsets, blur radii and edge mode) */
	@Nullable
	public float[] getParams() {
		return mParams == null ? null : mParams.clone();
	}

	/** @hide object parameters of the effect (bitmap/rects, color filter, blend mode, shader) */
	@Nullable
	public Object[] getObjects() {
		return mObjects == null ? null : mObjects.clone();
	}

	/** @hide the input effect, or the outer/dst effect for chain and blend effects */
	@Nullable
	public RenderEffect getInput() {
		return mInput;
	}

	/** @hide the inner/src effect for chain and blend effects */
	@Nullable
	public RenderEffect getSecondInput() {
		return mSecondInput;
	}

	/** @hide */
	public long getNativeInstance() {
		return 0;
	}

	@Override
	public String toString() {
		return "RenderEffect{kind=" + mKind + ", params=" + java.util.Arrays.toString(mParams) + ", input=" + mInput + ", secondInput=" + mSecondInput + "}";
	}
}
