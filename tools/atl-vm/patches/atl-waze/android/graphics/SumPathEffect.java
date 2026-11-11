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

/** AOSP SumPathEffect: both effects applied to the original path, the results drawn together. */
public class SumPathEffect extends PathEffect {
	private final PathEffect mFirst;
	private final PathEffect mSecond;

	/**
	 * Construct a PathEffect whose effect is to apply two effects, in sequence.
	 * (e.g. first(path) + second(path))
	 */
	public SumPathEffect(PathEffect first, PathEffect second) {
		mFirst = first;
		mSecond = second;
	}

	@Override
	public Path tlApply(Path src) {
		Path a = mFirst != null ? mFirst.tlApply(src) : src;
		Path b = mSecond != null ? mSecond.tlApply(src) : src;
		Path dst = new Path(a);
		dst.addPath(b);
		return dst;
	}
}
