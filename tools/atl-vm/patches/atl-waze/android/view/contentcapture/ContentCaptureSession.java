/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.view.contentcapture;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.util.Log;
import android.view.autofill.AutofillId;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Session used when notifying the Android system about events associated with views.
 *
 * teslalinux: AOSP's abstract base class. Sessions are created by the content capture system
 * service (via ContentCaptureManager), which this runtime does not have, so
 * View.getContentCaptureSession() returns null and no concrete subclass exists here. The methods
 * that build view structures (newViewStructure, newVirtualViewStructure, notifyViewAppeared,
 * notifyViewsAppeared, notifyViewsDisappeared, newAutofillId) depend on ViewNode / AssistStructure
 * and the full AutofillId, which are not ported; see TODO-real.md.
 */
public abstract class ContentCaptureSession implements AutoCloseable {

	private static final String TAG = ContentCaptureSession.class.getSimpleName();

	/** ContentCaptureManager.NO_SESSION_ID */
	static final int NO_SESSION_ID = 0;

	private static final SecureRandom ID_GENERATOR = new SecureRandom();

	/** Initial state, when there is no session. */
	public static final int UNKNOWN_STATE = 0x0;
	public static final int STATE_WAITING_FOR_SERVER = 0x1;
	public static final int STATE_ACTIVE = 0x2;
	public static final int STATE_DISABLED = 0x4;
	public static final int STATE_DUPLICATED_ID = 0x8;
	public static final int STATE_NO_SERVICE = 0x10;
	public static final int STATE_FLAG_SECURE = 0x20;
	public static final int STATE_BY_APP = 0x40;
	public static final int STATE_NO_RESPONSE = 0x80;
	public static final int STATE_INTERNAL_ERROR = 0x100;
	public static final int STATE_NOT_WHITELISTED = 0x200;
	public static final int STATE_SERVICE_DIED = 0x400;
	public static final int STATE_SERVICE_UPDATING = 0x800;
	public static final int STATE_SERVICE_RESURRECTED = 0x1000;

	private static final int INITIAL_CHILDREN_CAPACITY = 5;

	public static final int FLUSH_REASON_FULL = 1;
	public static final int FLUSH_REASON_VIEW_ROOT_ENTERED = 2;
	public static final int FLUSH_REASON_SESSION_STARTED = 3;
	public static final int FLUSH_REASON_SESSION_FINISHED = 4;
	public static final int FLUSH_REASON_IDLE_TIMEOUT = 5;
	public static final int FLUSH_REASON_TEXT_CHANGE_TIMEOUT = 6;
	public static final int FLUSH_REASON_SESSION_CONNECTED = 7;
	public static final int FLUSH_REASON_FORCE_FLUSH = 8;
	public static final int FLUSH_REASON_VIEW_TREE_APPEARING = 9;
	public static final int FLUSH_REASON_VIEW_TREE_APPEARED = 10;

	@IntDef(prefix = { "FLUSH_REASON_" }, value = {
			FLUSH_REASON_FULL,
			FLUSH_REASON_VIEW_ROOT_ENTERED,
			FLUSH_REASON_SESSION_STARTED,
			FLUSH_REASON_SESSION_FINISHED,
			FLUSH_REASON_IDLE_TIMEOUT,
			FLUSH_REASON_TEXT_CHANGE_TIMEOUT,
			FLUSH_REASON_SESSION_CONNECTED,
			FLUSH_REASON_FORCE_FLUSH,
			FLUSH_REASON_VIEW_TREE_APPEARING,
			FLUSH_REASON_VIEW_TREE_APPEARED
	})
	@Retention(RetentionPolicy.SOURCE)
	public @interface FlushReason{}

	private final Object mLock = new Object();

	private boolean mDestroyed;

	protected final int mId;

	private int mState = UNKNOWN_STATE;

	// Lazily created on demand.
	private ContentCaptureSessionId mContentCaptureSessionId;

	@Nullable
	private ContentCaptureContext mClientContext;

	@Nullable
	private ArrayList<ContentCaptureSession> mChildren;

	protected ContentCaptureSession() {
		this(getRandomSessionId());
	}

	public ContentCaptureSession(int id) {
		Preconditions.checkArgument(id != NO_SESSION_ID);
		mId = id;
	}

	ContentCaptureSession(@NonNull ContentCaptureContext initialContext) {
		this();
		mClientContext = Objects.requireNonNull(initialContext);
	}

	@NonNull
	public final ContentCaptureSessionId getContentCaptureSessionId() {
		if (mContentCaptureSessionId == null) {
			mContentCaptureSessionId = new ContentCaptureSessionId(mId);
		}
		return mContentCaptureSessionId;
	}

	public int getId() {
		return mId;
	}

	@NonNull
	public final ContentCaptureSession createContentCaptureSession(@NonNull ContentCaptureContext context) {
		final ContentCaptureSession child = newChild(context);
		synchronized (mLock) {
			if (mChildren == null) {
				mChildren = new ArrayList<>(INITIAL_CHILDREN_CAPACITY);
			}
			mChildren.add(child);
		}
		return child;
	}

	abstract ContentCaptureSession newChild(@NonNull ContentCaptureContext context);

	abstract void flush(@FlushReason int reason);

	public final void setContentCaptureContext(@Nullable ContentCaptureContext context) {
		if (!isContentCaptureEnabled()) return;

		mClientContext = context;
		updateContentCaptureContext(context);
	}

	abstract void updateContentCaptureContext(@Nullable ContentCaptureContext context);

	@Nullable
	public final ContentCaptureContext getContentCaptureContext() {
		return mClientContext;
	}

	public final void destroy() {
		synchronized (mLock) {
			if (mDestroyed) {
				return;
			}
			mDestroyed = true;

			// Finish children first
			if (mChildren != null) {
				final int numberChildren = mChildren.size();
				for (int i = 0; i < numberChildren; i++) {
					final ContentCaptureSession child = mChildren.get(i);
					try {
						child.destroy();
					} catch (Exception e) {
						Log.w(TAG, "exception destroying child session #" + i + ": " + e);
					}
				}
			}
		}

		onDestroy();
	}

	abstract void onDestroy();

	@Override
	public void close() {
		destroy();
	}

	public final void notifyViewDisappeared(@NonNull AutofillId id) {
		Objects.requireNonNull(id);
		if (!isContentCaptureEnabled()) return;

		internalNotifyViewDisappeared(id);
	}

	abstract void internalNotifyViewDisappeared(@NonNull AutofillId id);

	public final void notifyViewTextChanged(@NonNull AutofillId id, @Nullable CharSequence text) {
		Objects.requireNonNull(id);

		if (!isContentCaptureEnabled()) return;

		internalNotifyViewTextChanged(id, text);
	}

	abstract void internalNotifyViewTextChanged(@NonNull AutofillId id, @Nullable CharSequence text);

	public final void notifyViewInsetsChanged(@NonNull Insets viewInsets) {
		Objects.requireNonNull(viewInsets);

		if (!isContentCaptureEnabled()) return;

		internalNotifyViewInsetsChanged(viewInsets);
	}

	abstract void internalNotifyViewInsetsChanged(@NonNull Insets viewInsets);

	/** @hide */
	public abstract void internalNotifyViewTreeEvent(boolean started);

	public final void notifySessionResumed() {
		if (!isContentCaptureEnabled()) return;

		internalNotifySessionResumed();
	}

	abstract void internalNotifySessionResumed();

	public final void notifySessionPaused() {
		if (!isContentCaptureEnabled()) return;

		internalNotifySessionPaused();
	}

	abstract void internalNotifySessionPaused();

	boolean isContentCaptureEnabled() {
		synchronized (mLock) {
			return !mDestroyed;
		}
	}

	void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
		pw.print(prefix); pw.print("id: "); pw.println(mId);
		if (mClientContext != null) {
			pw.print(prefix); mClientContext.dump(pw); pw.println();
		}
		synchronized (mLock) {
			pw.print(prefix); pw.print("destroyed: "); pw.println(mDestroyed);
			if (mChildren != null && !mChildren.isEmpty()) {
				final String prefix2 = prefix + "  ";
				final int numberChildren = mChildren.size();
				pw.print(prefix); pw.print("number children: "); pw.println(numberChildren);
				for (int i = 0; i < numberChildren; i++) {
					final ContentCaptureSession child = mChildren.get(i);
					pw.print(prefix); pw.print(i); pw.println(": "); child.dump(prefix2, pw);
				}
			}
		}
	}

	@Override
	public String toString() {
		return Integer.toString(mId);
	}

	@NonNull
	protected static String getStateAsString(int state) {
		return state + " (" + (state == UNKNOWN_STATE ? "UNKNOWN" : "0x" + Integer.toHexString(state)) + ")";
	}

	@NonNull
	public static String getFlushReasonAsString(@FlushReason int reason) {
		switch (reason) {
			case FLUSH_REASON_FULL:
				return "FULL";
			case FLUSH_REASON_VIEW_ROOT_ENTERED:
				return "VIEW_ROOT";
			case FLUSH_REASON_SESSION_STARTED:
				return "STARTED";
			case FLUSH_REASON_SESSION_FINISHED:
				return "FINISHED";
			case FLUSH_REASON_IDLE_TIMEOUT:
				return "IDLE";
			case FLUSH_REASON_TEXT_CHANGE_TIMEOUT:
				return "TEXT_CHANGE";
			case FLUSH_REASON_SESSION_CONNECTED:
				return "CONNECTED";
			case FLUSH_REASON_FORCE_FLUSH:
				return "FORCE_FLUSH";
			case FLUSH_REASON_VIEW_TREE_APPEARING:
				return "VIEW_TREE_APPEARING";
			case FLUSH_REASON_VIEW_TREE_APPEARED:
				return "VIEW_TREE_APPEARED";
			default:
				return "UNKOWN-" + reason;
		}
	}

	private static int getRandomSessionId() {
		int id;
		do {
			id = ID_GENERATOR.nextInt();
		} while (id == NO_SESSION_ID);
		return id;
	}
}
