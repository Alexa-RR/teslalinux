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

/** AOSP ComposePathEffect: the outer effect applied to the result of the inner one. */
public class ComposePathEffect extends PathEffect {
	private final PathEffect mOuter;
	private final PathEffect mInner;

	/**
	 * Construct a PathEffect whose effect is to apply first the inner effect and the the outer
	 * pathEffect (e.g. outer(inner(path))).
	 */
	public ComposePathEffect(PathEffect outerpe, PathEffect innerpe) {
		mOuter = outerpe;
		mInner = innerpe;
	}

	@Override
	public Path tlApply(Path src) {
		Path p = mInner != null ? mInner.tlApply(src) : src;
		return mOuter != null ? mOuter.tlApply(p) : p;
	}
}
