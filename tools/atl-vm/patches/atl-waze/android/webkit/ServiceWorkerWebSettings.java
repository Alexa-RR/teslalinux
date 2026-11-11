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
 * Manages settings state for all Service Workers. These settings are not tied to
 * the lifetime of any WebView because service workers can outlive WebView instances.
 * The settings are similar to {@link WebSettings} but only settings relevant to
 * Service Workers are supported.
 */
public abstract class ServiceWorkerWebSettings {

	/**
	 * Overrides the way the cache is used, see {@link WebSettings#setCacheMode}.
	 */
	public abstract void setCacheMode(int mode);

	/**
	 * Gets the current setting for overriding the cache mode.
	 */
	public abstract int getCacheMode();

	/**
	 * Enables or disables content URL access from Service Workers, see
	 * {@link WebSettings#setAllowContentAccess}.
	 */
	public abstract void setAllowContentAccess(boolean allow);

	/**
	 * Gets whether Service Workers support content URL access.
	 */
	public abstract boolean getAllowContentAccess();

	/**
	 * Enables or disables file access within Service Workers, see
	 * {@link WebSettings#setAllowFileAccess}.
	 */
	public abstract void setAllowFileAccess(boolean allow);

	/**
	 * Gets whether Service Workers support file access.
	 */
	public abstract boolean getAllowFileAccess();

	/**
	 * Sets whether the Service Workers should not load resources from the network,
	 * see {@link WebSettings#setBlockNetworkLoads}.
	 *
	 * @throws SecurityException if the app does not have the INTERNET permission and
	 *         tries to unblock network loads.
	 */
	public abstract void setBlockNetworkLoads(boolean flag);

	/**
	 * Gets whether Service Workers are prohibited from loading any resources from the network.
	 */
	public abstract boolean getBlockNetworkLoads();

	/** teslalinux: the one settings object, with Chromium's AwServiceWorkerSettings defaults. */
	static final class Impl extends ServiceWorkerWebSettings {
		private int mCacheMode = WebSettings.LOAD_DEFAULT;
		private boolean mAllowContentAccess = true;
		private boolean mAllowFileAccess = true;
		private Boolean mBlockNetworkLoads;

		private static boolean hasInternetPermission() {
			android.content.Context c = android.app.ActivityThread.currentApplication();
			return c == null || c.checkPermission(android.Manifest.permission.INTERNET, android.os.Process.myPid(), android.os.Process.myUid()) == android.content.pm.PackageManager.PERMISSION_GRANTED;
		}

		@Override
		public synchronized void setCacheMode(int mode) {
			if (mode < WebSettings.LOAD_DEFAULT || mode > WebSettings.LOAD_CACHE_ONLY)
				throw new IllegalArgumentException("Invalid cache mode " + mode);
			mCacheMode = mode;
		}

		@Override
		public synchronized int getCacheMode() {
			return mCacheMode;
		}

		@Override
		public synchronized void setAllowContentAccess(boolean allow) {
			mAllowContentAccess = allow;
		}

		@Override
		public synchronized boolean getAllowContentAccess() {
			return mAllowContentAccess;
		}

		@Override
		public synchronized void setAllowFileAccess(boolean allow) {
			mAllowFileAccess = allow;
		}

		@Override
		public synchronized boolean getAllowFileAccess() {
			return mAllowFileAccess;
		}

		@Override
		public synchronized void setBlockNetworkLoads(boolean flag) {
			if (!flag && !hasInternetPermission())
				throw new SecurityException("Permission denied - application missing INTERNET permission");
			mBlockNetworkLoads = flag;
		}

		@Override
		public synchronized boolean getBlockNetworkLoads() {
			if (mBlockNetworkLoads == null)
				mBlockNetworkLoads = !hasInternetPermission();
			return mBlockNetworkLoads;
		}
	}
}
