/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.util.ArrayList;
import java.util.TreeMap;

/**
 * teslalinux: the OnBackInvokedDispatcher of an activity or dialog window, after AOSP's
 * WindowOnBackInvokedDispatcher. Callbacks are kept per priority (the highest priority, most recently
 * registered one is on top). As in AOSP, an application's callbacks are only accepted when
 * OnBackInvokedCallback is enabled for it; otherwise registration logs AOSP's warning and BACK keeps
 * going to onBackPressed(). Enabled means: the platform has the API (SDK 33+) and, as Android 16 does
 * by default, the app targets SDK 36+ on an SDK 36+ platform. ATL's manifest parser does not read
 * android:enableOnBackInvokedCallback, so the explicit manifest opt-in/opt-out is not seen.
 */
final class TlBackDispatcher implements OnBackInvokedDispatcher {
	private static final String TAG = "OnBackInvokedCallback";
	/** AOSP OnBackInvokedDispatcher.PRIORITY_SYSTEM (hidden): only the system may use negative priorities. */
	static final int PRIORITY_SYSTEM = -1;

	private final boolean mEnabled;
	/* priority -> callbacks in registration order */
	private final TreeMap<Integer, ArrayList<OnBackInvokedCallback>> mCallbacks = new TreeMap<>();

	TlBackDispatcher(Context context) {
		mEnabled = isOnBackInvokedCallbackEnabled(context);
	}

	static boolean isOnBackInvokedCallbackEnabled(Context context) {
		if (Build.VERSION.SDK_INT < 33)
			return false;
		ApplicationInfo info = context != null ? context.getApplicationInfo() : null;
		int target = info != null ? info.targetSdkVersion : 0;
		return Build.VERSION.SDK_INT >= 36 && target >= 36;
	}

	@Override
	public void registerOnBackInvokedCallback(int priority, OnBackInvokedCallback callback) {
		if (priority < 0) {
			throw new IllegalArgumentException("Application registered OnBackInvokedCallback cannot have negative priority. Priority: " + priority);
		}
		if (callback == null)
			throw new NullPointerException("callback");
		if (!mEnabled) {
			Log.w(TAG, "OnBackInvokedCallback is not enabled for the application.\nSet 'android:enableOnBackInvokedCallback=\"true\"' in the application manifest.");
			return;
		}
		synchronized (mCallbacks) {
			removeLocked(callback);
			ArrayList<OnBackInvokedCallback> list = mCallbacks.get(priority);
			if (list == null)
				mCallbacks.put(priority, list = new ArrayList<>());
			list.add(callback);
		}
	}

	@Override
	public void unregisterOnBackInvokedCallback(OnBackInvokedCallback callback) {
		synchronized (mCallbacks) {
			removeLocked(callback);
		}
	}

	private void removeLocked(OnBackInvokedCallback callback) {
		Integer empty = null;
		for (java.util.Map.Entry<Integer, ArrayList<OnBackInvokedCallback>> e : mCallbacks.entrySet()) {
			if (e.getValue().remove(callback) && e.getValue().isEmpty())
				empty = e.getKey();
		}
		if (empty != null)
			mCallbacks.remove(empty);
	}

	/** The callback BACK goes to: the most recently registered one of the highest priority, or null. */
	OnBackInvokedCallback getTopCallback() {
		synchronized (mCallbacks) {
			if (mCallbacks.isEmpty())
				return null;
			ArrayList<OnBackInvokedCallback> top = mCallbacks.lastEntry().getValue();
			return top.isEmpty() ? null : top.get(top.size() - 1);
		}
	}
}
