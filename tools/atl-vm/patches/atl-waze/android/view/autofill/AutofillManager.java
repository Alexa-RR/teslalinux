/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.autofill;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The AutofillManager provides ways for apps and custom views to interact with the Autofill
 * Framework lifecycle.
 *
 * This device ships no autofill service (there is no system AutofillManagerService behind the
 * binder here), which is the same state as an Android device whose autofill feature is absent or
 * disabled: AOSP's AutofillManager checks {@code hasAutofillFeature()} / {@code isEnabled()} at
 * the top of every entry point and returns without doing anything. The public API below keeps
 * exactly that behaviour, so apps and Jetpack Compose (AutofillApi26Helper/AutofillApi27Helper)
 * can call every method and observe "autofill not available".
 */
public final class AutofillManager {
	private static final String TAG = "AutofillManager";

	/** Intent extra: desired autofill service component name on save. */
	public static final String EXTRA_ASSIST_STRUCTURE = "android.view.autofill.extra.ASSIST_STRUCTURE";
	public static final String EXTRA_AUTHENTICATION_RESULT = "android.view.autofill.extra.AUTHENTICATION_RESULT";
	public static final String EXTRA_AUTHENTICATION_RESULT_EPHEMERAL_DATASET = "android.view.autofill.extra.AUTHENTICATION_RESULT_EPHEMERAL_DATASET";
	public static final String EXTRA_CLIENT_STATE = "android.view.autofill.extra.CLIENT_STATE";

	/** Flag for {@link #requestAutofill(View)}: the request was triggered explicitly by the user. */
	public static final int FLAG_ADD_CLIENT_ENABLED = 0x1;
	public static final int FLAG_ADD_CLIENT_DEBUG = 0x2;
	public static final int FLAG_ADD_CLIENT_VERBOSE = 0x4;
	public static final int FLAG_ADD_CLIENT_ENABLED_FOR_AUGMENTED_AUTOFILL_ONLY = 0x8;

	public static final int STATE_UNKNOWN = 0;
	public static final int STATE_ACTIVE = 1;
	public static final int STATE_FINISHED = 2;
	public static final int STATE_SHOWING_SAVE_UI = 3;
	public static final int STATE_DISABLED_BY_SERVICE = 4;
	public static final int STATE_UNKNOWN_COMPAT_MODE = 5;
	public static final int STATE_UNKNOWN_FAILED = 6;

	private final Context mContext;
	private final Object mLock = new Object();
	private final List<AutofillCallback> mCallbacks = new ArrayList<>();

	/**
	 * Object running in the client context that's used by the autofill framework to query
	 * state of the application (e.g. its views). The framework calls back into the app through it;
	 * with no service here it is simply retained.
	 */
	public interface AutofillClient {
	}

	private AutofillClient mClient;

	public AutofillManager(Context context) {
		mContext = context;
	}

	/** @hide */
	public AutofillManager() {
		this(null);
	}

	/** @hide */
	public void setAutofillClient(AutofillClient client) {
		mClient = client;
	}

	/** @hide */
	public AutofillClient getClient() {
		return mClient;
	}

	/**
	 * Returns whether autofill is enabled for the current user: it is not, there is no autofill
	 * service on this device.
	 */
	public boolean isEnabled() {
		return false;
	}

	/** Returns whether the autofill feature is supported by the device: it is not. */
	public boolean isAutofillSupported() {
		return false;
	}

	/** Returns whether the user has an autofill service enabled: no. */
	public boolean hasEnabledAutofillServices() {
		return false;
	}

	/** The autofill service for the current user: none. */
	public ComponentName getAutofillServiceComponentName() {
		return null;
	}

	/** Compat mode is a hack for apps that predate the autofill APIs; this app is not in it. */
	public boolean isCompatibilityModeEnabled() {
		return false;
	}

	/** Returns an opaque id for the save flow, {@code null} when there is no session. */
	public String getUserDataId() {
		return null;
	}

	/** Whether the service supports field classification: it does not exist. */
	public boolean isFieldClassificationEnabled() {
		return false;
	}

	public String getDefaultFieldClassificationAlgorithm() {
		return null;
	}

	public List<String> getAvailableFieldClassificationAlgorithms() {
		return new ArrayList<>();
	}

	/** AOSP: ids handed out here are app-generated (above LAST_APP_AUTOFILL_ID the views use). */
	private static int sNextId = 1;

	/** Returns the next unique autofill id for a view (app views use View.getAutofillId()). */
	public AutofillId getNextAutofillId() {
		synchronized (AutofillManager.class) {
			return new AutofillId(sNextId++);
		}
	}

	/** Explicitly requests a new autofill context for the view: nothing happens without a service. */
	public void requestAutofill(View view) {
	}

	/** Explicitly requests a new autofill context for a virtual view. */
	public void requestAutofill(View view, int virtualId, Rect absBounds) {
	}

	/** Called when a view that supports autofill is entered (focused). */
	public void notifyViewEntered(View view) {
	}

	/** @hide */
	public void notifyViewEntered(View view, int flags) {
	}

	/** Called when a view that supports autofill is exited (focus lost). */
	public void notifyViewExited(View view) {
	}

	/** Called when a virtual view that supports autofill is entered. */
	public void notifyViewEntered(View view, int virtualId, Rect absBounds) {
	}

	/** @hide */
	public void notifyViewEntered(View view, int virtualId, Rect absBounds, int flags) {
	}

	/** Called when a virtual view that supports autofill is exited. */
	public void notifyViewExited(View view, int virtualId) {
	}

	/** Called when the visibility of a view that supports autofill changed (API 27). */
	public void notifyViewVisibilityChanged(View view, boolean isVisible) {
	}

	/** Called when the visibility of a virtual view that supports autofill changed (API 27). */
	public void notifyViewVisibilityChanged(View view, int virtualId, boolean isVisible) {
	}

	/** Called to indicate the value of an autofillable view changed. */
	public void notifyValueChanged(View view) {
	}

	/** Called to indicate the value of an autofillable virtual view changed. */
	public void notifyValueChanged(View view, int virtualId, AutofillValue value) {
	}

	/** Called to indicate a View is clicked (e.g. the autofill-able part of a WebView). */
	public void notifyViewClicked(View view) {
	}

	/** Called to indicate a virtual view is clicked. */
	public void notifyViewClicked(View view, int virtualId) {
	}

	/** Called when the autofill session should be committed (e.g. form submitted). */
	public void commit() {
	}

	/** Called when the autofill session should be cancelled. */
	public void cancel() {
	}

	/** Disables autofill for all activities of the app: it already is. */
	public void disableAutofillServices() {
	}

	/** Registers a callback for autofill UI events; none will fire without a service. */
	public void registerCallback(AutofillCallback callback) {
		if (callback == null)
			return;
		synchronized (mLock) {
			if (!mCallbacks.contains(callback))
				mCallbacks.add(callback);
		}
	}

	public void unregisterCallback(AutofillCallback callback) {
		if (callback == null)
			return;
		synchronized (mLock) {
			mCallbacks.remove(callback);
		}
	}

	/** Sets user data used for field classification: no service to receive it. */
	public void setUserData(Object userData) {
	}

	/** @hide */
	public void onActivityFinishing() {
	}

	/** @hide */
	public void onInvisibleForAutofill(boolean isExpiredResponse) {
	}

	/** @hide */
	public void onVisibleForAutofill() {
	}

	/**
	 * Callback for autofill related events: the autofill UI showing/hiding for a view, or a
	 * dataset being unavailable. Nothing here ever fires, as on a device without the feature.
	 */
	public abstract static class AutofillCallback {
		/** The autofill input UI associated with the view was shown. */
		public static final int EVENT_INPUT_SHOWN = 1;
		/** The autofill input UI associated with the view was hidden. */
		public static final int EVENT_INPUT_HIDDEN = 2;
		/** The autofill input UI associated with the view isn't shown because autofill is not available. */
		public static final int EVENT_INPUT_UNAVAILABLE = 3;

		public void onAutofillEvent(View view, int event) {
		}

		public void onAutofillEvent(View view, int virtualId, int event) {
		}
	}
}
