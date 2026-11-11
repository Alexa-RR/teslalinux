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

package android.view.accessibility;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * teslalinux: AOSP's AccessibilityManager as it behaves on a device with no accessibility service
 * installed. This platform has no accessibility services, no settings UI to enable one and no
 * accessibility timeouts set by the user, so:
 * - accessibility, touch exploration, high text contrast and audio description are off, and the
 *   service lists are empty;
 * - listeners are kept and called on their handler when a state changes (which, with no services,
 *   only happens if a state is ever changed here; the registration itself is AOSP's);
 * - sending or interrupting events while accessibility is off is the app's error, as on AOSP
 *   (IllegalStateException on the main thread, a logged error elsewhere);
 * - getRecommendedTimeoutMillis applies the user's interactive / non-interactive UI timeouts, which
 *   are 0 (unset) here, so the app's own timeout stands.
 */
public final class AccessibilityManager {
	private static final String LOG_TAG = "AccessibilityManager";

	/** UI content flags for getRecommendedTimeoutMillis */
	public static final int FLAG_CONTENT_ICONS = 1;
	public static final int FLAG_CONTENT_TEXT = 1 << 1;
	public static final int FLAG_CONTENT_CONTROLS = 1 << 2;

	public interface AccessibilityStateChangeListener {
		void onAccessibilityStateChanged(boolean enabled);
	}

	public interface TouchExplorationStateChangeListener {
		void onTouchExplorationStateChanged(boolean enabled);
	}

	public interface AccessibilityServicesStateChangeListener {
		void onAccessibilityServicesStateChanged(AccessibilityManager manager);
	}

	/** @hide */
	public interface HighTextContrastChangeListener {
		void onHighTextContrastStateChanged(boolean enabled);
	}

	public interface AudioDescriptionRequestedChangeListener {
		void onAudioDescriptionRequestedChanged(boolean enabled);
	}

	/** a listener and the handler (or executor) its callbacks run on */
	private static final class Registration<T> {
		final T listener;
		final Handler handler;
		final java.util.concurrent.Executor executor;

		Registration(T listener, Handler handler, java.util.concurrent.Executor executor) {
			this.listener = listener;
			this.handler = handler;
			this.executor = executor;
		}

		void post(Runnable r) {
			if (executor != null)
				executor.execute(r);
			else
				handler.post(r);
		}
	}

	private final Object mLock = new Object();
	private boolean mIsEnabled;
	private boolean mIsTouchExplorationEnabled;
	private boolean mIsHighTextContrastEnabled;
	private boolean mIsAudioDescriptionRequested;
	/** the user's "time to take action" accessibility settings; unset (0) on this platform */
	private int mInteractiveUiTimeout;
	private int mNonInteractiveUiTimeout;

	private final ArrayList<Registration<AccessibilityStateChangeListener>> mAccessibilityStateChangeListeners = new ArrayList<>();
	private final ArrayList<Registration<TouchExplorationStateChangeListener>> mTouchExplorationStateChangeListeners = new ArrayList<>();
	private final ArrayList<Registration<AccessibilityServicesStateChangeListener>> mServicesStateChangeListeners = new ArrayList<>();
	private final ArrayList<Registration<HighTextContrastChangeListener>> mHighTextContrastStateChangeListeners = new ArrayList<>();
	private final ArrayList<Registration<AudioDescriptionRequestedChangeListener>> mAudioDescriptionRequestedChangeListeners = new ArrayList<>();

	public AccessibilityManager() {
	}

	private static Handler mainHandler() {
		return new Handler(Looper.getMainLooper());
	}

	public boolean isEnabled() {
		synchronized (mLock) {
			return mIsEnabled;
		}
	}

	public boolean isTouchExplorationEnabled() {
		synchronized (mLock) {
			return mIsTouchExplorationEnabled;
		}
	}

	/** @hide */
	public boolean isHighTextContrastEnabled() {
		synchronized (mLock) {
			return mIsHighTextContrastEnabled;
		}
	}

	public boolean isAudioDescriptionRequested() {
		synchronized (mLock) {
			return mIsAudioDescriptionRequested;
		}
	}

	/** API 34: whether the current request comes from a tool marked isAccessibilityTool; no service sends requests here. */
	public boolean isRequestFromAccessibilityTool() {
		return false;
	}

	/** AOSP: sends an event to the enabled services; with accessibility off that is the caller's error. */
	public void sendAccessibilityEvent(AccessibilityEvent event) {
		if (!isEnabled()) {
			if (Looper.myLooper() == Looper.getMainLooper())
				throw new IllegalStateException("Accessibility off. Did you forget to check that?");
			Log.e(LOG_TAG, "AccessibilityEvent sent with accessibility disabled");
			return;
		}
		/* enabled only if a service existed; none can be installed here */
	}

	/** AOSP: asks the services to stop speaking; with accessibility off that is the caller's error. */
	public void interrupt() {
		if (!isEnabled()) {
			if (Looper.myLooper() == Looper.getMainLooper())
				throw new IllegalStateException("Accessibility off. Did you forget to check that?");
			Log.e(LOG_TAG, "Interrupt called with accessibility disabled");
		}
	}

	/** the installed accessibility services (AccessibilityServiceInfo); none are installed */
	public List getInstalledAccessibilityServiceList() {
		return new ArrayList<>();
	}

	/** the enabled services giving the requested feedback types; none are installed */
	public List getEnabledAccessibilityServiceList(int feedbackTypeFlags) {
		return new ArrayList<>();
	}

	/**
	 * AOSP: the timeout to use for UI that would otherwise disappear after originalTimeout ms, given
	 * what it contains: at least the user's interactive timeout for UI with controls, at least the
	 * non-interactive timeout for UI with icons or text.
	 */
	public int getRecommendedTimeoutMillis(int originalTimeout, int uiContentFlags) {
		boolean hasControls = (uiContentFlags & FLAG_CONTENT_CONTROLS) != 0;
		boolean hasIconsOrText = (uiContentFlags & FLAG_CONTENT_ICONS) != 0 || (uiContentFlags & FLAG_CONTENT_TEXT) != 0;
		int recommendedTimeout = originalTimeout;
		if (hasControls)
			recommendedTimeout = Math.max(recommendedTimeout, mInteractiveUiTimeout);
		if (hasIconsOrText)
			recommendedTimeout = Math.max(recommendedTimeout, mNonInteractiveUiTimeout);
		return recommendedTimeout;
	}

	/* ------------------------------------------------------------------ listeners */

	private static <T> boolean add(ArrayList<Registration<T>> list, T listener, Handler handler, java.util.concurrent.Executor executor) {
		synchronized (list) {
			for (Registration<T> r : list) {
				if (r.listener == listener)
					return true;
			}
			list.add(new Registration<>(listener, handler, executor));
		}
		return true;
	}

	private static <T> boolean remove(ArrayList<Registration<T>> list, T listener) {
		synchronized (list) {
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i).listener == listener) {
					list.remove(i);
					return true;
				}
			}
		}
		return false;
	}

	public boolean addAccessibilityStateChangeListener(AccessibilityStateChangeListener listener) {
		return add(mAccessibilityStateChangeListeners, listener, mainHandler(), null);
	}

	public void addAccessibilityStateChangeListener(AccessibilityStateChangeListener listener, Handler handler) {
		add(mAccessibilityStateChangeListeners, listener, handler != null ? handler : mainHandler(), null);
	}

	public boolean removeAccessibilityStateChangeListener(AccessibilityStateChangeListener listener) {
		return remove(mAccessibilityStateChangeListeners, listener);
	}

	public boolean addTouchExplorationStateChangeListener(TouchExplorationStateChangeListener listener) {
		return add(mTouchExplorationStateChangeListeners, listener, mainHandler(), null);
	}

	public void addTouchExplorationStateChangeListener(TouchExplorationStateChangeListener listener, Handler handler) {
		add(mTouchExplorationStateChangeListeners, listener, handler != null ? handler : mainHandler(), null);
	}

	public boolean removeTouchExplorationStateChangeListener(TouchExplorationStateChangeListener listener) {
		return remove(mTouchExplorationStateChangeListeners, listener);
	}

	public void addAccessibilityServicesStateChangeListener(AccessibilityServicesStateChangeListener listener) {
		add(mServicesStateChangeListeners, listener, mainHandler(), null);
	}

	public void addAccessibilityServicesStateChangeListener(java.util.concurrent.Executor executor, AccessibilityServicesStateChangeListener listener) {
		add(mServicesStateChangeListeners, listener, null, executor);
	}

	public boolean removeAccessibilityServicesStateChangeListener(AccessibilityServicesStateChangeListener listener) {
		return remove(mServicesStateChangeListeners, listener);
	}

	/** @hide */
	public void addHighTextContrastStateChangeListener(HighTextContrastChangeListener listener, Handler handler) {
		add(mHighTextContrastStateChangeListeners, listener, handler != null ? handler : mainHandler(), null);
	}

	/** @hide */
	public void removeHighTextContrastStateChangeListener(HighTextContrastChangeListener listener) {
		remove(mHighTextContrastStateChangeListeners, listener);
	}

	public void addAudioDescriptionRequestedChangeListener(java.util.concurrent.Executor executor, AudioDescriptionRequestedChangeListener listener) {
		add(mAudioDescriptionRequestedChangeListeners, listener, null, executor);
	}

	public boolean removeAudioDescriptionRequestedChangeListener(AudioDescriptionRequestedChangeListener listener) {
		return remove(mAudioDescriptionRequestedChangeListeners, listener);
	}

	/**
	 * AOSP setStateLocked: a change of the accessibility state reaches the registered listeners on
	 * their handlers. With no services nothing changes it on this platform.
	 */
	private void setState(boolean enabled, boolean touchExploration) {
		final boolean wasEnabled, wasTouchExploration;
		synchronized (mLock) {
			wasEnabled = mIsEnabled;
			wasTouchExploration = mIsTouchExplorationEnabled;
			mIsEnabled = enabled;
			mIsTouchExplorationEnabled = touchExploration;
		}
		if (wasEnabled != enabled) {
			for (Registration<AccessibilityStateChangeListener> r : snapshot(mAccessibilityStateChangeListeners))
				r.post(() -> r.listener.onAccessibilityStateChanged(enabled));
		}
		if (wasTouchExploration != touchExploration) {
			for (Registration<TouchExplorationStateChangeListener> r : snapshot(mTouchExplorationStateChangeListeners))
				r.post(() -> r.listener.onTouchExplorationStateChanged(touchExploration));
		}
		if (wasEnabled != enabled || wasTouchExploration != touchExploration) {
			for (Registration<AccessibilityServicesStateChangeListener> r : snapshot(mServicesStateChangeListeners))
				r.post(() -> r.listener.onAccessibilityServicesStateChanged(this));
		}
	}

	private static <T> ArrayList<Registration<T>> snapshot(ArrayList<Registration<T>> list) {
		synchronized (list) {
			return new ArrayList<>(list);
		}
	}
}
