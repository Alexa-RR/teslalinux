/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * Manages Service Workers used by WebView.
 *
 * <p>teslalinux: WebKitGTK runs service workers inside its own processes and gives the embedder no
 * hook for their fetches or settings. The controller keeps the client and settings for the app (one
 * instance, as AOSP); they do not reach the engine, whose service workers follow WebKit's defaults
 * (network allowed, normal caching, no file or content access).
 */
public abstract class ServiceWorkerController {

	/**
	 * Returns the default ServiceWorkerController instance. At present there is
	 * only one ServiceWorkerController instance for all WebView instances,
	 * however this restriction may be relaxed in the future.
	 */
	public static ServiceWorkerController getInstance() {
		return Impl.INSTANCE;
	}

	/**
	 * Gets the settings for all service workers.
	 */
	public abstract ServiceWorkerWebSettings getServiceWorkerWebSettings();

	/**
	 * Sets the client to capture service worker related callbacks.
	 *
	 * A {@link ServiceWorkerClient} should be set before any service workers are
	 * active, e.g. a safe place is before any WebView instances are created or
	 * pages loaded.
	 */
	public abstract void setServiceWorkerClient(ServiceWorkerClient client);

	public ServiceWorkerController() {}

	static final class Impl extends ServiceWorkerController {
		static final Impl INSTANCE = new Impl();
		private final ServiceWorkerWebSettings mSettings = new ServiceWorkerWebSettings.Impl();
		private ServiceWorkerClient mClient;

		@Override
		public ServiceWorkerWebSettings getServiceWorkerWebSettings() {
			return mSettings;
		}

		@Override
		public synchronized void setServiceWorkerClient(ServiceWorkerClient client) {
			mClient = client;
		}

		synchronized ServiceWorkerClient getClient() {
			return mClient;
		}
	}
}
