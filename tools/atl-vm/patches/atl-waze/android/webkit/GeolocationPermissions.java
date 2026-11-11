/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * This class is used to manage permissions for the WebView's Geolocation
 * JavaScript API.
 *
 * Geolocation permissions are applied to an origin, which consists of the
 * host, scheme and port of a URI. In order for web content to use the
 * Geolocation API, permission must be granted for that content's origin.
 *
 * This class stores Geolocation permissions. An origin's permission state can
 * be either allowed or denied. This class uses Strings to represent
 * an origin.
 *
 * When an origin attempts to use the Geolocation API, but no permission state
 * is currently set for that origin,
 * {@link WebChromeClient#onGeolocationPermissionsShowPrompt(String,GeolocationPermissions.Callback) WebChromeClient.onGeolocationPermissionsShowPrompt()}
 * is called. This allows the permission state to be set for that origin.
 *
 * The methods of this class can be used to modify and interrogate the stored
 * Geolocation permissions at any time.
 *
 * <p>teslalinux: retained decisions persist in app_webview/GeolocationPermissions.properties.
 */
public class GeolocationPermissions {
	/**
	 * A callback interface used by the host application to set the Geolocation
	 * permission state for an origin.
	 */
	public interface Callback {
		/**
		 * Sets the Geolocation permission state for the supplied origin.
		 *
		 * @param origin the origin for which permissions are set
		 * @param allow whether or not the origin should be allowed to use the
		 *              Geolocation API
		 * @param retain whether the permission should be retained beyond the
		 *               lifetime of a page currently being displayed by a
		 *               WebView
		 */
		public void invoke(String origin, boolean allow, boolean retain);
	};

	private static GeolocationPermissions sInstance;
	private final Properties mRetained = new Properties();
	private File mFile;

	/**
	 * Gets the singleton instance of this class.
	 */
	public static synchronized GeolocationPermissions getInstance() {
		if (sInstance == null)
			sInstance = new GeolocationPermissions();
		return sInstance;
	}

	/** teslalinux: the WebView data directory is known once the engine session exists */
	synchronized void setDataDir(File dir) {
		if (mFile != null)
			return;
		mFile = new File(dir, "GeolocationPermissions.properties");
		Properties p = new Properties();
		try (FileInputStream in = new FileInputStream(mFile)) {
			p.load(in);
			for (String k : p.stringPropertyNames())
				if (!mRetained.containsKey(k))
					mRetained.setProperty(k, p.getProperty(k));
		} catch (IOException e) {
			// no stored decisions yet
		}
	}

	private void save() {
		if (mFile == null)
			return;
		try (FileOutputStream out = new FileOutputStream(mFile)) {
			mRetained.store(out, null);
		} catch (IOException e) {
		}
	}

	private static String key(String origin) {
		if (origin == null)
			return null;
		android.net.Uri u = android.net.Uri.parse(origin);
		if (u.getScheme() == null || u.getHost() == null)
			return null;
		return u.getScheme() + "://" + u.getHost() + (u.getPort() != -1 ? ":" + u.getPort() : "");
	}

	/** teslalinux: the retained decision for origin, or null if none */
	synchronized Boolean retained(String origin) {
		String k = key(origin);
		String v = k != null ? mRetained.getProperty(k) : null;
		return v == null ? null : Boolean.valueOf(v);
	}

	synchronized void retain(String origin, boolean allow) {
		String k = key(origin);
		if (k == null)
			return;
		mRetained.setProperty(k, Boolean.toString(allow));
		save();
	}

	private static <T> void reply(final ValueCallback<T> callback, final T value) {
		if (callback == null)
			return;
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			public void run() {
				callback.onReceiveValue(value);
			}
		});
	}

	/**
	 * Gets the set of origins for which Geolocation permissions are stored.
	 */
	public void getOrigins(ValueCallback<Set<String>> callback) {
		Set<String> origins;
		synchronized (this) {
			origins = new HashSet<String>(mRetained.stringPropertyNames());
		}
		reply(callback, origins);
	}

	/**
	 * Gets the Geolocation permission state for the specified origin.
	 */
	public void getAllowed(String origin, ValueCallback<Boolean> callback) {
		Boolean v = retained(origin);
		reply(callback, v != null && v);
	}

	/**
	 * Clears the Geolocation permission state for the specified origin.
	 */
	public synchronized void clear(String origin) {
		String k = key(origin);
		if (k != null && mRetained.remove(k) != null)
			save();
	}

	/**
	 * Allows the specified origin to use the Geolocation API.
	 */
	public void allow(String origin) {
		retain(origin, true);
	}

	/**
	 * Clears the Geolocation permission state for all origins.
	 */
	public synchronized void clearAll() {
		mRetained.clear();
		save();
	}

	/**
	 * This class should not be instantiated directly, applications must only use
	 * {@link #getInstance()} to obtain the instance.
	 */
	public GeolocationPermissions() {}
}
