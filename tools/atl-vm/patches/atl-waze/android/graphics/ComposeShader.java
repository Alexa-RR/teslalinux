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
 * A subclass of shader that returns the composition of two other shaders, combined by the
 * specified subclass of Xfermode (shaderA is the destination, shaderB the source).
 * teslalinux: android.atl.GskCanvas draws both shaders and composes them with GSK mask/blend nodes.
 */
public class ComposeShader extends Shader {
	private final Shader mShaderA;
	private final Shader mShaderB;
	private final int mPorterDuffMode;

	/** Create a new compose shader, given shaders A, B, and a combining mode. */
	public ComposeShader(Shader shaderA, Shader shaderB, Xfermode mode) {
		this(shaderA, shaderB, mode instanceof PorterDuffXfermode ? ((PorterDuffXfermode) mode).porterDuffMode : PorterDuff.Mode.SRC_OVER.nativeInt);
	}

	/** Create a new compose shader, given shaders A, B, and a combining PorterDuff mode. */
	public ComposeShader(Shader shaderA, Shader shaderB, PorterDuff.Mode mode) {
		this(shaderA, shaderB, mode.nativeInt);
	}

	/** API 29: Create a new compose shader, given shaders A, B, and a combining BlendMode. */
	public ComposeShader(Shader shaderA, Shader shaderB, BlendMode blendMode) {
		this(shaderA, shaderB, blendMode.getXfermode().porterDuffMode);
	}

	private ComposeShader(Shader shaderA, Shader shaderB, int nativeMode) {
		if (shaderA == null || shaderB == null) {
			throw new IllegalArgumentException("Shader parameters must not be null");
		}
		mShaderA = shaderA;
		mShaderB = shaderB;
		mPorterDuffMode = nativeMode;
	}

	public Shader tlShaderA() { return mShaderA; }
	public Shader tlShaderB() { return mShaderB; }
	/** The SkBlendMode value combining the two. */
	public int tlMode() { return mPorterDuffMode; }
}
