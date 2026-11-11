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

package android.content;

import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Log;

/**
 * Base class for code that receives and handles broadcast intents (AOSP).
 *
 * <p>teslalinux: the result state of a broadcast (code, data, extras, abort) and goAsync's
 * PendingResult are kept as in AOSP. ATL delivers broadcasts in the app's process without an
 * ActivityManager; finishing a result marks the broadcast done (there is no system queue to tell).
 * A receiver called by a dispatcher that set no pending result is treated as receiving a plain
 * (non-ordered) broadcast, which is what ATL's dispatchers deliver.
 */
public abstract class BroadcastReceiver {
    private PendingResult mPendingResult;
    private boolean mDebugUnregister;

    /**
     * State for a result that is pending for a broadcast receiver. Returned by
     * {@link BroadcastReceiver#goAsync() goAsync()} while in
     * {@link BroadcastReceiver#onReceive BroadcastReceiver.onReceive()}.
     */
    public static class PendingResult {
        /** @hide */
        public static final int TYPE_COMPONENT = 0;
        /** @hide */
        public static final int TYPE_REGISTERED = 1;
        /** @hide */
        public static final int TYPE_UNREGISTERED = 2;

        final int mType;
        final boolean mOrderedHint;
        final boolean mInitialStickyHint;
        final IBinder mToken;
        final int mSendingUser;
        final int mFlags;

        int mResultCode;
        String mResultData;
        Bundle mResultExtras;
        boolean mAbortBroadcast;
        boolean mFinished;

        /** @hide */
        public PendingResult(int resultCode, String resultData, Bundle resultExtras, int type,
                boolean ordered, boolean sticky, IBinder token, int userId, int flags) {
            mResultCode = resultCode;
            mResultData = resultData;
            mResultExtras = resultExtras;
            mType = type;
            mOrderedHint = ordered;
            mInitialStickyHint = sticky;
            mToken = token;
            mSendingUser = userId;
            mFlags = flags;
        }

        /** Version of {@link BroadcastReceiver#setResultCode BroadcastReceiver.setResultCode}. */
        public final void setResultCode(int code) {
            checkSynchronousHint();
            mResultCode = code;
        }

        /** Version of {@link BroadcastReceiver#getResultCode BroadcastReceiver.getResultCode}. */
        public final int getResultCode() {
            return mResultCode;
        }

        /** Version of {@link BroadcastReceiver#setResultData BroadcastReceiver.setResultData}. */
        public final void setResultData(String data) {
            checkSynchronousHint();
            mResultData = data;
        }

        /** Version of {@link BroadcastReceiver#getResultData BroadcastReceiver.getResultData}. */
        public final String getResultData() {
            return mResultData;
        }

        /** Version of {@link BroadcastReceiver#setResultExtras BroadcastReceiver.setResultExtras}. */
        public final void setResultExtras(Bundle extras) {
            checkSynchronousHint();
            mResultExtras = extras;
        }

        /** Version of {@link BroadcastReceiver#getResultExtras BroadcastReceiver.getResultExtras}. */
        public final Bundle getResultExtras(boolean makeMap) {
            Bundle e = mResultExtras;
            if (!makeMap) return e;
            if (e == null) mResultExtras = e = new Bundle();
            return e;
        }

        /** Version of {@link BroadcastReceiver#setResult BroadcastReceiver.setResult}. */
        public final void setResult(int code, String data, Bundle extras) {
            checkSynchronousHint();
            mResultCode = code;
            mResultData = data;
            mResultExtras = extras;
        }

        /** Version of {@link BroadcastReceiver#getAbortBroadcast BroadcastReceiver.getAbortBroadcast}. */
        public final boolean getAbortBroadcast() {
            return mAbortBroadcast;
        }

        /** Version of {@link BroadcastReceiver#abortBroadcast BroadcastReceiver.abortBroadcast}. */
        public final void abortBroadcast() {
            checkSynchronousHint();
            mAbortBroadcast = true;
        }

        /** Version of {@link BroadcastReceiver#clearAbortBroadcast BroadcastReceiver.clearAbortBroadcast}. */
        public final void clearAbortBroadcast() {
            mAbortBroadcast = false;
        }

        /**
         * Finish the broadcast. The current result will be sent and the next broadcast will
         * proceed.
         */
        public final void finish() {
            synchronized (this) {
                if (mFinished) {
                    throw new IllegalStateException("Broadcast already finished");
                }
                mFinished = true;
            }
        }

        /** @hide whether finish() has been called */
        public final boolean isFinished() {
            synchronized (this) {
                return mFinished;
            }
        }

        /** @hide */
        public int getSendingUserId() {
            return mSendingUser;
        }

        /** @hide */
        public int getSentFromUid() {
            return android.os.Process.myUid();
        }

        /** @hide */
        public String getSentFromPackage() {
            return null;
        }

        void checkSynchronousHint() {
            // Note that we don't assert when receiving the initial sticky value,
            // since that may have come from an ordered broadcast.  We'll catch
            // them later when the real broadcast happens again.
            if (mOrderedHint || mInitialStickyHint) {
                return;
            }
            RuntimeException e = new RuntimeException(
                    "BroadcastReceiver trying to return result during a non-ordered broadcast");
            e.fillInStackTrace();
            Log.e("BroadcastReceiver", e.getMessage(), e);
        }
    }

    public BroadcastReceiver() {
    }

    /**
     * This method is called when the BroadcastReceiver is receiving an Intent broadcast.
     */
    public abstract void onReceive(Context context, Intent intent);

    /**
     * This can be called by an application in {@link #onReceive} to allow it to keep the
     * broadcast active after returning from that function.
     */
    public final PendingResult goAsync() {
        PendingResult res = mPendingResult;
        if (res == null) {
            // delivered by a dispatcher that set no result: a plain broadcast to this receiver
            res = new PendingResult(0, null, null, PendingResult.TYPE_REGISTERED, false, false,
                    null, UserHandle.myUserId(), 0);
        }
        mPendingResult = null;
        return res;
    }

    /**
     * Provide a binder to an already-bound service. ATL's bound services are delivered to their
     * ServiceConnection only, so there is no binder to peek at, as for a service that is not
     * bound on Android.
     */
    public IBinder peekService(Context myContext, Intent service) {
        return null;
    }

    /** Change the current result code of this broadcast. */
    public final void setResultCode(int code) {
        checkSynchronousHint();
        mPendingResult.mResultCode = code;
    }

    /** Retrieve the current result code, as set by the previous receiver. */
    public final int getResultCode() {
        return mPendingResult != null ? mPendingResult.mResultCode : 0;
    }

    /** Change the current result data of this broadcast. */
    public final void setResultData(String data) {
        checkSynchronousHint();
        mPendingResult.mResultData = data;
    }

    /** Retrieve the current result data, as set by the previous receiver. */
    public final String getResultData() {
        return mPendingResult != null ? mPendingResult.mResultData : null;
    }

    /** Change the current result extras of this broadcast. */
    public final void setResultExtras(Bundle extras) {
        checkSynchronousHint();
        mPendingResult.mResultExtras = extras;
    }

    /** Retrieve the current result extra data, as set by the previous receiver. */
    public final Bundle getResultExtras(boolean makeMap) {
        if (mPendingResult == null) {
            return null;
        }
        Bundle e = mPendingResult.mResultExtras;
        if (!makeMap) return e;
        if (e == null) mPendingResult.mResultExtras = e = new Bundle();
        return e;
    }

    /** Change all of the result data returned from this broadcasts. */
    public final void setResult(int code, String data, Bundle extras) {
        checkSynchronousHint();
        mPendingResult.mResultCode = code;
        mPendingResult.mResultData = data;
        mPendingResult.mResultExtras = extras;
    }

    /** Returns the flag indicating whether or not this receiver should abort the current broadcast. */
    public final boolean getAbortBroadcast() {
        return mPendingResult != null ? mPendingResult.mAbortBroadcast : false;
    }

    /** Sets the flag indicating that this receiver should abort the current broadcast. */
    public final void abortBroadcast() {
        checkSynchronousHint();
        mPendingResult.mAbortBroadcast = true;
    }

    /** Clears the flag indicating that this receiver should abort the current broadcast. */
    public final void clearAbortBroadcast() {
        if (mPendingResult != null) {
            mPendingResult.mAbortBroadcast = false;
        }
    }

    /** Returns true if the receiver is currently processing an ordered broadcast. */
    public final boolean isOrderedBroadcast() {
        return mPendingResult != null ? mPendingResult.mOrderedHint : false;
    }

    /**
     * Returns true if the receiver is currently processing the initial value of a sticky
     * broadcast.
     */
    public final boolean isInitialStickyBroadcast() {
        return mPendingResult != null ? mPendingResult.mInitialStickyHint : false;
    }

    /** For internal use, sets the hint about whether this BroadcastReceiver is running in ordered mode. */
    public final void setOrderedHint(boolean isOrdered) {
        // Accessed by the dispatcher through setPendingResult.
    }

    /** For internal use to set the result data that is active. @hide */
    public final void setPendingResult(PendingResult result) {
        mPendingResult = result;
    }

    /** For internal use to set the result data that is active. @hide */
    public final PendingResult getPendingResult() {
        return mPendingResult;
    }

    /** @hide */
    public int getSendingUserId() {
        return mPendingResult != null ? mPendingResult.mSendingUser : UserHandle.myUserId();
    }

    /** Control inclusion of debugging help for mismatched calls to registerReceiver/unregisterReceiver. */
    public final void setDebugUnregister(boolean debug) {
        mDebugUnregister = debug;
    }

    /** Return the last value given to {@link #setDebugUnregister}. */
    public final boolean getDebugUnregister() {
        return mDebugUnregister;
    }

    void checkSynchronousHint() {
        if (mPendingResult == null) {
            throw new IllegalStateException("Call while result is not pending");
        }

        // Note that we don't assert when receiving the initial sticky value,
        // since that may have come from an ordered broadcast.  We'll catch
        // them later when the real broadcast happens again.
        if (mPendingResult.mOrderedHint || mPendingResult.mInitialStickyHint) {
            return;
        }
        RuntimeException e = new RuntimeException(
                "BroadcastReceiver trying to return result during a non-ordered broadcast");
        e.fillInStackTrace();
        Log.e("BroadcastReceiver", e.getMessage(), e);
    }
}
