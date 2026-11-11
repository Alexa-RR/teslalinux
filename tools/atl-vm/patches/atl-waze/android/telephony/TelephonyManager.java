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

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Provides access to information about the telephony services on the device (AOSP API).
 *
 * teslalinux: this device has no telephony stack (no FEATURE_TELEPHONY; the Pi's LTE modem is a
 * data interface of the host, not an Android radio). Every answer is AOSP's on such a device, where
 * the phone service (ITelephony) does not exist: PHONE_TYPE_NONE, no modem, SIM_STATE_ABSENT, empty
 * operator strings, null identifiers and cell data, call state idle. listen() and
 * registerTelephonyCallback() keep the registrations and deliver the initial states the telephony
 * registry sends on registration (idle, data disconnected); nothing ever changes them.
 */
public class TelephonyManager {
    public static final String ACTION_PHONE_STATE_CHANGED = "android.intent.action.PHONE_STATE";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_STATE_IDLE = "IDLE";
    public static final String EXTRA_STATE_RINGING = "RINGING";
    public static final String EXTRA_STATE_OFFHOOK = "OFFHOOK";
    public static final String EXTRA_INCOMING_NUMBER = "incoming_number";

    public static final int PHONE_TYPE_NONE = 0;
    public static final int PHONE_TYPE_GSM = 1;
    public static final int PHONE_TYPE_CDMA = 2;
    public static final int PHONE_TYPE_SIP = 3;

    public static final int CALL_STATE_IDLE = 0;
    public static final int CALL_STATE_RINGING = 1;
    public static final int CALL_STATE_OFFHOOK = 2;

    public static final int DATA_UNKNOWN = -1;
    public static final int DATA_DISCONNECTED = 0;
    public static final int DATA_CONNECTING = 1;
    public static final int DATA_CONNECTED = 2;
    public static final int DATA_SUSPENDED = 3;
    public static final int DATA_DISCONNECTING = 4;
    public static final int DATA_HANDOVER_IN_PROGRESS = 5;

    public static final int DATA_ACTIVITY_NONE = 0;
    public static final int DATA_ACTIVITY_IN = 1;
    public static final int DATA_ACTIVITY_OUT = 2;
    public static final int DATA_ACTIVITY_INOUT = 3;
    public static final int DATA_ACTIVITY_DORMANT = 4;

    public static final int NETWORK_TYPE_UNKNOWN = 0;
    public static final int NETWORK_TYPE_GPRS = 1;
    public static final int NETWORK_TYPE_EDGE = 2;
    public static final int NETWORK_TYPE_UMTS = 3;
    public static final int NETWORK_TYPE_CDMA = 4;
    public static final int NETWORK_TYPE_EVDO_0 = 5;
    public static final int NETWORK_TYPE_EVDO_A = 6;
    public static final int NETWORK_TYPE_1xRTT = 7;
    public static final int NETWORK_TYPE_HSDPA = 8;
    public static final int NETWORK_TYPE_HSUPA = 9;
    public static final int NETWORK_TYPE_HSPA = 10;
    public static final int NETWORK_TYPE_IDEN = 11;
    public static final int NETWORK_TYPE_EVDO_B = 12;
    public static final int NETWORK_TYPE_LTE = 13;
    public static final int NETWORK_TYPE_EHRPD = 14;
    public static final int NETWORK_TYPE_HSPAP = 15;
    public static final int NETWORK_TYPE_GSM = 16;
    public static final int NETWORK_TYPE_TD_SCDMA = 17;
    public static final int NETWORK_TYPE_IWLAN = 18;
    public static final int NETWORK_TYPE_NR = 20;

    public static final int SIM_STATE_UNKNOWN = 0;
    public static final int SIM_STATE_ABSENT = 1;
    public static final int SIM_STATE_PIN_REQUIRED = 2;
    public static final int SIM_STATE_PUK_REQUIRED = 3;
    public static final int SIM_STATE_NETWORK_LOCKED = 4;
    public static final int SIM_STATE_READY = 5;
    public static final int SIM_STATE_NOT_READY = 6;
    public static final int SIM_STATE_PERM_DISABLED = 7;
    public static final int SIM_STATE_CARD_IO_ERROR = 8;
    public static final int SIM_STATE_CARD_RESTRICTED = 9;

    public static final int UNKNOWN_CARRIER_ID = -1;

    private final Context mContext;
    private final int mSubId;

    private static final CopyOnWriteArrayList<PhoneStateListener> sListeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<TelephonyCallback> sCallbacks = new CopyOnWriteArrayList<>();

    /** @hide */
    public TelephonyManager(Context context) {
        this(context, SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
    }

    /** @hide */
    public TelephonyManager(Context context, int subId) {
        mContext = context;
        mSubId = subId;
    }

    /** @hide */
    public TelephonyManager() {
        this(null);
    }

    /** @hide */
    @Deprecated
    public static TelephonyManager getDefault() {
        return new TelephonyManager();
    }

    public TelephonyManager createForSubscriptionId(int subId) {
        return new TelephonyManager(mContext, subId);
    }

    // ---- device ----

    public int getPhoneType() {
        return PHONE_TYPE_NONE;
    }

    @Deprecated
    public int getPhoneCount() {
        return getActiveModemCount();
    }

    /** No modem: AOSP's MODEM_COUNT_NO_MODEM. */
    public int getActiveModemCount() {
        return 0;
    }

    public int getSupportedModemCount() {
        return 0;
    }

    public boolean isVoiceCapable() {
        return false;
    }

    public boolean isSmsCapable() {
        return false;
    }

    public boolean isDataCapable() {
        return false;
    }

    // AOSP: identifiers come from the phone service; without it they are null
    @Deprecated
    public String getDeviceId() {
        return null;
    }

    @Deprecated
    public String getDeviceId(int slotIndex) {
        return null;
    }

    public String getImei() {
        return null;
    }

    public String getImei(int slotIndex) {
        return null;
    }

    public String getMeid() {
        return null;
    }

    public String getSubscriberId() {
        return null;
    }

    public String getLine1Number() {
        return null;
    }

    public String getVoiceMailNumber() {
        return null;
    }

    public String getSimSerialNumber() {
        return null;
    }

    public String getDeviceSoftwareVersion() {
        return null;
    }

    public int getSubscriptionId() {
        return SubscriptionManager.getDefaultSubscriptionId();
    }

    // ---- network and SIM ----

    public String getNetworkOperator() {
        return "";
    }

    public String getNetworkOperatorName() {
        return "";
    }

    public String getNetworkCountryIso() {
        return "";
    }

    public String getSimOperator() {
        return "";
    }

    public String getSimOperatorName() {
        return "";
    }

    public String getSimCountryIso() {
        return "";
    }

    public int getSimCarrierId() {
        return UNKNOWN_CARRIER_ID;
    }

    public CharSequence getSimCarrierIdName() {
        return null;
    }

    public int getCarrierIdFromSimMccMnc() {
        return UNKNOWN_CARRIER_ID;
    }

    /** No SIM slot: AOSP finds no slot that is not absent. */
    public int getSimState() {
        return SIM_STATE_ABSENT;
    }

    public int getSimState(int slotIndex) {
        return SIM_STATE_ABSENT;
    }

    public boolean hasIccCard() {
        return false;
    }

    public boolean isNetworkRoaming() {
        return false;
    }

    @Deprecated
    public int getNetworkType() {
        return NETWORK_TYPE_UNKNOWN;
    }

    public int getDataNetworkType() {
        return NETWORK_TYPE_UNKNOWN;
    }

    public int getVoiceNetworkType() {
        return NETWORK_TYPE_UNKNOWN;
    }

    public int getDataState() {
        return DATA_DISCONNECTED;
    }

    public int getDataActivity() {
        return DATA_ACTIVITY_NONE;
    }

    public boolean isDataEnabled() {
        return false;
    }

    public boolean isDataRoamingEnabled() {
        return false;
    }

    @Deprecated
    public int getCallState() {
        return CALL_STATE_IDLE;
    }

    public int getCallStateForSubscription() {
        return CALL_STATE_IDLE;
    }

    /** AOSP: null without the phone service. */
    @Deprecated
    public CellLocation getCellLocation() {
        return null;
    }

    /** AOSP (Q+): always an empty list. */
    @Deprecated
    public List<Object> getNeighboringCellInfo() {
        return new ArrayList<>();
    }

    /** AOSP: null without the phone service. */
    public List<Object> getAllCellInfo() {
        return null;
    }

    // ---- listeners ----

    @Deprecated
    public void listen(final PhoneStateListener listener, final int events) {
        if (listener == null)
            return;
        if (events == PhoneStateListener.LISTEN_NONE) {
            sListeners.remove(listener);
            return;
        }
        if (!sListeners.contains(listener))
            sListeners.add(listener);
        // the telephony registry's initial notifications for the requested events
        listener.mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if ((events & PhoneStateListener.LISTEN_CALL_STATE) != 0)
                    listener.onCallStateChanged(CALL_STATE_IDLE, "");
                if ((events & PhoneStateListener.LISTEN_DATA_CONNECTION_STATE) != 0) {
                    listener.onDataConnectionStateChanged(DATA_DISCONNECTED);
                    listener.onDataConnectionStateChanged(DATA_DISCONNECTED, NETWORK_TYPE_UNKNOWN);
                }
                if ((events & PhoneStateListener.LISTEN_DATA_ACTIVITY) != 0)
                    listener.onDataActivity(DATA_ACTIVITY_NONE);
                if ((events & PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR) != 0)
                    listener.onMessageWaitingIndicatorChanged(false);
                if ((events & PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR) != 0)
                    listener.onCallForwardingIndicatorChanged(false);
                if ((events & PhoneStateListener.LISTEN_USER_MOBILE_DATA_STATE) != 0)
                    listener.onUserMobileDataStateChanged(false);
                if ((events & PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE) != 0)
                    listener.onActiveDataSubscriptionIdChanged(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
            }
        });
    }

    public void registerTelephonyCallback(Executor executor, final TelephonyCallback callback) {
        if (executor == null) {
            throw new IllegalStateException("telephony service is null.");
        }
        if (callback == null) {
            throw new NullPointerException("callback");
        }
        if (!sCallbacks.contains(callback))
            sCallbacks.add(callback);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (callback instanceof TelephonyCallback.CallStateListener)
                    ((TelephonyCallback.CallStateListener) callback).onCallStateChanged(CALL_STATE_IDLE);
                if (callback instanceof TelephonyCallback.DataConnectionStateListener)
                    ((TelephonyCallback.DataConnectionStateListener) callback).onDataConnectionStateChanged(DATA_DISCONNECTED, NETWORK_TYPE_UNKNOWN);
                if (callback instanceof TelephonyCallback.DataActivityListener)
                    ((TelephonyCallback.DataActivityListener) callback).onDataActivity(DATA_ACTIVITY_NONE);
                if (callback instanceof TelephonyCallback.UserMobileDataStateListener)
                    ((TelephonyCallback.UserMobileDataStateListener) callback).onUserMobileDataStateChanged(false);
                if (callback instanceof TelephonyCallback.ActiveDataSubscriptionIdListener)
                    ((TelephonyCallback.ActiveDataSubscriptionIdListener) callback).onActiveDataSubscriptionIdChanged(SubscriptionManager.INVALID_SUBSCRIPTION_ID);
            }
        });
    }

    public void registerTelephonyCallback(int includeLocationData, Executor executor, TelephonyCallback callback) {
        registerTelephonyCallback(executor, callback);
    }

    public void unregisterTelephonyCallback(TelephonyCallback callback) {
        if (callback == null) {
            throw new NullPointerException("callback");
        }
        sCallbacks.remove(callback);
    }
}
