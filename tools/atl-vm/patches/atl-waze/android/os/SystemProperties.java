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

package android.os;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Gives access to the system properties store (AOSP API).
 *
 * <p>teslalinux: ATL has no property service. The store lives in this process: the read-only
 * build properties ATL derives from the host, plus any given in the environment variable
 * ATL_SYSPROPS as "key=value" pairs separated by ';' (for example
 * {@code ATL_SYSPROPS="log.tag.Glide=DEBUG"}, the counterpart of {@code setprop}). set() follows
 * the property service's rules (ro.* is write-once, values are limited to PROP_VALUE_MAX).
 *
 * @hide
 */
public class SystemProperties {
	private static final String TAG = "SystemProperties";

	/** Android's PROP_VALUE_MAX - 1 */
	public static final int PROP_VALUE_MAX = 91;

	private static final HashMap<String, String> sProperties = new HashMap<>();
	private static final ArrayList<Runnable> sChangeCallbacks = new ArrayList<>();

	static {
		String SDK_INT_str = System.getProperty("Build.VERSION.SDK_INT");
		if (SDK_INT_str == null)
			SDK_INT_str = "" + Build.VERSION_CODES.GINGERBREAD;
		sProperties.put("ro.build.version.sdk", SDK_INT_str);
		sProperties.put("ro.product.brand", "google");
		sProperties.put("ro.build.tags", "release-keys");
		sProperties.put("ro.build.type", "user");
		String arch = System.getProperty("os.arch");
		if ("x86_64".equals(arch)) {
			sProperties.put("ro.product.cpu.abi", "x86_64");
			sProperties.put("ro.product.cpu.abi2", "x86");
			sProperties.put("ro.product.cpu.abilist", "x86_64,x86");
			sProperties.put("ro.product.cpu.abilist64", "x86_64");
			sProperties.put("ro.product.cpu.abilist32", "x86");
		} else if ("aarch64".equals(arch)) {
			sProperties.put("ro.product.cpu.abi", "arm64-v8a");
			sProperties.put("ro.product.cpu.abilist", "arm64-v8a");
			sProperties.put("ro.product.cpu.abilist64", "arm64-v8a");
			sProperties.put("ro.product.cpu.abilist32", "");
		}
		String env = System.getenv("ATL_SYSPROPS");
		if (env != null) {
			for (String pair : env.split(";")) {
				int eq = pair.indexOf('=');
				if (eq > 0)
					sProperties.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
			}
		}
	}

	private static String native_get(String key) {
		if (key == null)
			throw new NullPointerException("key");
		synchronized (sProperties) {
			return sProperties.get(key);
		}
	}

	/**
	 * Get the String value for the given {@code key}.
	 *
	 * @return an empty string if the {@code key} isn't found
	 */
	public static String get(String key) {
		String v = native_get(key);
		return v != null ? v : "";
	}

	/**
	 * Get the String value for the given {@code key}.
	 *
	 * @return if the {@code key} isn't found, return {@code def} if it isn't null, or an empty
	 * string otherwise
	 */
	public static String get(String key, String def) {
		String v = native_get(key);
		if (v != null && !v.isEmpty())
			return v;
		return def != null ? def : "";
	}

	/**
	 * Get the value for the given {@code key}, and return as an integer.
	 *
	 * @return the key parsed as an integer, or def if the key isn't found or cannot be parsed
	 */
	public static int getInt(String key, int def) {
		String v = native_get(key);
		if (v == null || v.isEmpty())
			return def;
		try {
			return Integer.decode(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	/**
	 * Get the value for the given {@code key}, and return as a long.
	 *
	 * @return the key parsed as a long, or def if the key isn't found or cannot be parsed
	 */
	public static long getLong(String key, long def) {
		String v = native_get(key);
		if (v == null || v.isEmpty())
			return def;
		try {
			return Long.decode(v.trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	/**
	 * Get the value for the given {@code key}, returned as a boolean. Values 'n', 'no', '0',
	 * 'false' or 'off' are considered false. Values 'y', 'yes', '1', 'true' or 'on' are considered
	 * true. (case sensitive). If the key does not exist, or has any other value, then the default
	 * result is returned.
	 */
	public static boolean getBoolean(String key, boolean def) {
		String v = native_get(key);
		if (v == null)
			return def;
		switch (v) {
			case "1": case "y": case "yes": case "on": case "true":
				return true;
			case "0": case "n": case "no": case "off": case "false":
				return false;
			default:
				return def;
		}
	}

	/**
	 * Set the value for the given {@code key} to {@code val}.
	 *
	 * @throws IllegalArgumentException if the {@code val} exceeds 91 characters
	 * @throws RuntimeException if the property cannot be set (a read-only one already set)
	 */
	public static void set(String key, String val) {
		if (val != null && !key.startsWith("ro.") && val.length() > PROP_VALUE_MAX) {
			throw new IllegalArgumentException("value of system property '" + key
			                                   + "' is longer than " + PROP_VALUE_MAX + " characters: " + val);
		}
		synchronized (sProperties) {
			if (key.startsWith("ro.") && sProperties.containsKey(key))
				throw new RuntimeException("failed to set system property (check logs for reason)");
			if (val == null || val.isEmpty())
				sProperties.remove(key);
			else
				sProperties.put(key, val);
		}
		callChangeCallbacks();
	}

	/**
	 * Add a callback that will be run whenever any system property changes.
	 */
	public static void addChangeCallback(Runnable callback) {
		synchronized (sChangeCallbacks) {
			sChangeCallbacks.add(callback);
		}
	}

	/**
	 * Remove the target callback.
	 */
	public static void removeChangeCallback(Runnable callback) {
		synchronized (sChangeCallbacks) {
			sChangeCallbacks.remove(callback);
		}
	}

	private static void callChangeCallbacks() {
		ArrayList<Runnable> callbacks;
		synchronized (sChangeCallbacks) {
			if (sChangeCallbacks.isEmpty())
				return;
			callbacks = new ArrayList<>(sChangeCallbacks);
		}
		for (Runnable r : callbacks) {
			try {
				r.run();
			} catch (Throwable t) {
				android.util.Log.wtf(TAG, "Exception in SystemProperties change callback", t);
			}
		}
	}

	/**
	 * Notifies listeners that a system property has changed.
	 */
	public static void reportSyspropChanged() {
		callChangeCallbacks();
	}
}
