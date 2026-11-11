/*
 * Copyright (C) 2014 The Android Open Source Project
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

/**
 * SubscriptionManager is the application interface to SubscriptionController (AOSP API).
 *
 * teslalinux: this device has no telephony and no SIM (the Pi's LTE modem is a data interface of
 * the host, not an Android radio), so there are no subscriptions: the ids and slot indexes are
 * AOSP's invalid values, as on a Wi-Fi-only device.
 */
public class SubscriptionManager {
    public static final int INVALID_SUBSCRIPTION_ID = -1;
    /** @hide */
    public static final int DEFAULT_SUBSCRIPTION_ID = Integer.MAX_VALUE;
    public static final int INVALID_SIM_SLOT_INDEX = -1;
    public static final String ACTION_DEFAULT_SUBSCRIPTION_CHANGED = "android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED";
    public static final String ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED = "android.telephony.action.DEFAULT_SMS_SUBSCRIPTION_CHANGED";
    public static final String EXTRA_SUBSCRIPTION_INDEX = "android.telephony.extra.SUBSCRIPTION_INDEX";
    public static final String EXTRA_SLOT_INDEX = "android.telephony.extra.SLOT_INDEX";

    private final Context mContext;

    /** @hide */
    public SubscriptionManager(Context context) {
        mContext = context;
    }

    /** @hide */
    public SubscriptionManager() {
        this(null);
    }

    @Deprecated
    public static SubscriptionManager from(Context context) {
        return new SubscriptionManager(context);
    }

    public static int getSlotIndex(int subscriptionId) {
        return INVALID_SIM_SLOT_INDEX;
    }

    public static int getDefaultSubscriptionId() {
        return INVALID_SUBSCRIPTION_ID;
    }

    public static int getDefaultVoiceSubscriptionId() {
        return INVALID_SUBSCRIPTION_ID;
    }

    public static int getDefaultSmsSubscriptionId() {
        return INVALID_SUBSCRIPTION_ID;
    }

    public static int getDefaultDataSubscriptionId() {
        return INVALID_SUBSCRIPTION_ID;
    }

    public static int getActiveDataSubscriptionId() {
        return INVALID_SUBSCRIPTION_ID;
    }

    public static boolean isValidSubscriptionId(int subscriptionId) {
        return subscriptionId > INVALID_SUBSCRIPTION_ID;
    }

    public static boolean isUsableSubscriptionId(int subscriptionId) {
        return subscriptionId > INVALID_SUBSCRIPTION_ID && subscriptionId < DEFAULT_SUBSCRIPTION_ID;
    }

    public int getActiveSubscriptionInfoCount() {
        return 0;
    }

    public int getActiveSubscriptionInfoCountMax() {
        return 0;
    }

    public boolean isNetworkRoaming(int subId) {
        return false;
    }
}
