/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.telephony;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;

/**
 * A listener class for monitoring changes in specific telephony states (AOSP API).
 *
 * teslalinux: callbacks run on the executor given, or on the Looper of the thread that created
 * the listener, as AOSP's. TelephonyManager.listen delivers the states of this device (no
 * telephony: idle, disconnected).
 */
@Deprecated
public class PhoneStateListener {
    public static final int LISTEN_NONE = 0;
    public static final int LISTEN_SERVICE_STATE = 0x00000001;
    @Deprecated
    public static final int LISTEN_SIGNAL_STRENGTH = 0x00000002;
    public static final int LISTEN_MESSAGE_WAITING_INDICATOR = 0x00000004;
    public static final int LISTEN_CALL_FORWARDING_INDICATOR = 0x00000008;
    public static final int LISTEN_CELL_LOCATION = 0x00000010;
    public static final int LISTEN_CALL_STATE = 0x00000020;
    public static final int LISTEN_DATA_CONNECTION_STATE = 0x00000040;
    public static final int LISTEN_DATA_ACTIVITY = 0x00000080;
    public static final int LISTEN_SIGNAL_STRENGTHS = 0x00000100;
    public static final int LISTEN_CELL_INFO = 0x00000400;
    public static final int LISTEN_USER_MOBILE_DATA_STATE = 0x00080000;
    public static final int LISTEN_DISPLAY_INFO_CHANGED = 0x00100000;
    public static final int LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE = 0x00400000;

    final Executor mExecutor;

    public PhoneStateListener() {
        this(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper());
    }

    /** @hide */
    public PhoneStateListener(Looper looper) {
        final Handler h = new Handler(looper);
        mExecutor = new Executor() {
            @Override
            public void execute(Runnable r) {
                h.post(r);
            }
        };
    }

    public PhoneStateListener(Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("PhoneStateListener Executor must be non-null");
        }
        mExecutor = executor;
    }

    public void onMessageWaitingIndicatorChanged(boolean mwi) {
    }

    public void onCallForwardingIndicatorChanged(boolean cfi) {
    }

    public void onCellLocationChanged(CellLocation location) {
    }

    public void onCallStateChanged(int state, String phoneNumber) {
    }

    public void onDataConnectionStateChanged(int state) {
    }

    public void onDataConnectionStateChanged(int state, int networkType) {
    }

    public void onDataActivity(int direction) {
    }

    public void onUserMobileDataStateChanged(boolean enabled) {
    }

    public void onDisplayInfoChanged(TelephonyDisplayInfo telephonyDisplayInfo) {
    }

    public void onActiveDataSubscriptionIdChanged(int subId) {
    }
}
