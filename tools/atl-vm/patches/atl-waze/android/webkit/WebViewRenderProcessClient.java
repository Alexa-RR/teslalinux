/*
 * Copyright 2019 The Android Open Source Project
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

package android.webkit;

/**
 * Used to receive callbacks on {@link WebView} renderer events.
 *
 * WebViewRenderProcessClient instances may be set or retrieved via {@link
 * WebView#setWebViewRenderProcessClient(WebViewRenderProcessClient)} and {@link
 * WebView#getWebViewRenderProcessClient()}.
 *
 * Instances may be attached to multiple WebViews, and thus a single renderer event may cause
 * a callback to be called multiple times with different WebView parameters.
 */
public abstract class WebViewRenderProcessClient {
	/**
	 * Called when the renderer currently associated with {@code view} becomes unresponsive as a
	 * result of a long running blocking task such as the execution of JavaScript.
	 *
	 * @param view The {@link WebView} for which unresponsiveness was detected.
	 * @param renderer The {@link WebViewRenderProcess} that has become unresponsive,
	 * or {@code null} if WebView is running in single process mode.
	 */
	public abstract void onRenderProcessUnresponsive(
	    WebView view, WebViewRenderProcess renderer);

	/**
	 * Called once when an unresponsive renderer currently associated with {@code view} becomes
	 * responsive.
	 *
	 * @param view The {@link WebView} for which responsiveness was detected.
	 * @param renderer The {@link WebViewRenderProcess} that has become responsive,
	 * or {@code null} if WebView is running in single process mode.
	 */
	public abstract void onRenderProcessResponsive(
	    WebView view, WebViewRenderProcess renderer);
}
