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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Listener for apps to implement handling for insertion of content. Content may be both text and
 * non-text (plain/styled text, HTML, images, videos, audio files, etc). (AOSP.)
 */
public interface OnReceiveContentListener {
	/**
	 * Receive the given content.
	 *
	 * @return The portion of the passed-in content that was not accepted (may be all, some, or none
	 * of the passed-in content).
	 */
	@Nullable
	ContentInfo onReceiveContent(@NonNull View view, @NonNull ContentInfo payload);
}
