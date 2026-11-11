/*
 * Copyright 2009-2016 The Android Open Source Project
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

package android.bluetooth;

import android.bluetooth.le.BluetoothLeScanner;

import java.util.Collections;
import java.util.Set;

/**
 * Represents the local device Bluetooth adapter (AOSP API).
 *
 * teslalinux: this device has no Bluetooth radio available to apps (the Pi image keeps it off,
 * the VM has none), so {@link #getDefaultAdapter()} returns null, as AOSP does on hardware without
 * Bluetooth (and BluetoothManager.getAdapter() with it). No adapter object can exist; the instance
 * methods are AOSP's answers for an adapter that is off (STATE_OFF, no scanner, no bonded devices),
 * and the static helpers (address checks) are AOSP's.
 */
public final class BluetoothAdapter {
    public static final int ERROR = Integer.MIN_VALUE;

    public static final String ACTION_STATE_CHANGED = "android.bluetooth.adapter.action.STATE_CHANGED";
    public static final String EXTRA_STATE = "android.bluetooth.adapter.extra.STATE";
    public static final String EXTRA_PREVIOUS_STATE = "android.bluetooth.adapter.extra.PREVIOUS_STATE";
    public static final String ACTION_REQUEST_ENABLE = "android.bluetooth.adapter.action.REQUEST_ENABLE";
    public static final String ACTION_REQUEST_DISCOVERABLE = "android.bluetooth.adapter.action.REQUEST_DISCOVERABLE";
    public static final String ACTION_SCAN_MODE_CHANGED = "android.bluetooth.adapter.action.SCAN_MODE_CHANGED";
    public static final String ACTION_DISCOVERY_STARTED = "android.bluetooth.adapter.action.DISCOVERY_STARTED";
    public static final String ACTION_DISCOVERY_FINISHED = "android.bluetooth.adapter.action.DISCOVERY_FINISHED";
    public static final String ACTION_LOCAL_NAME_CHANGED = "android.bluetooth.adapter.action.LOCAL_NAME_CHANGED";
    public static final String ACTION_CONNECTION_STATE_CHANGED = "android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED";
    public static final String EXTRA_CONNECTION_STATE = "android.bluetooth.adapter.extra.CONNECTION_STATE";
    public static final String EXTRA_PREVIOUS_CONNECTION_STATE = "android.bluetooth.adapter.extra.PREVIOUS_CONNECTION_STATE";
    public static final String EXTRA_SCAN_MODE = "android.bluetooth.adapter.extra.SCAN_MODE";
    public static final String EXTRA_PREVIOUS_SCAN_MODE = "android.bluetooth.adapter.extra.PREVIOUS_SCAN_MODE";
    public static final String EXTRA_DISCOVERABLE_DURATION = "android.bluetooth.adapter.extra.DISCOVERABLE_DURATION";
    public static final String EXTRA_LOCAL_NAME = "android.bluetooth.adapter.extra.LOCAL_NAME";

    public static final int STATE_OFF = 10;
    public static final int STATE_TURNING_ON = 11;
    public static final int STATE_ON = 12;
    public static final int STATE_TURNING_OFF = 13;

    public static final int SCAN_MODE_NONE = 20;
    public static final int SCAN_MODE_CONNECTABLE = 21;
    public static final int SCAN_MODE_CONNECTABLE_DISCOVERABLE = 23;

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_DISCONNECTING = 3;

    private static final int ADDRESS_LENGTH = 17;

    private BluetoothAdapter() {
    }

    /** No Bluetooth hardware: AOSP returns null. */
    public static synchronized BluetoothAdapter getDefaultAdapter() {
        return null;
    }

    /** AOSP BluetoothAdapter.checkBluetoothAddress: "00:11:22:AA:BB:CC", upper case hex. */
    public static boolean checkBluetoothAddress(String address) {
        if (address == null || address.length() != ADDRESS_LENGTH) {
            return false;
        }
        for (int i = 0; i < ADDRESS_LENGTH; i++) {
            char c = address.charAt(i);
            switch (i % 3) {
                case 0:
                case 1:
                    if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) {
                        break;
                    }
                    return false;
                case 2:
                    if (c == ':') {
                        break;
                    }
                    return false;
            }
        }
        return true;
    }

    public BluetoothDevice getRemoteDevice(String address) {
        return new BluetoothDevice(address);
    }

    public boolean isEnabled() {
        return false;
    }

    public int getState() {
        return STATE_OFF;
    }

    /** AOSP: null while Bluetooth is off. */
    public BluetoothLeScanner getBluetoothLeScanner() {
        return null;
    }

    /** AOSP: an empty set while Bluetooth is off. */
    public Set<BluetoothDevice> getBondedDevices() {
        return Collections.emptySet();
    }

    public String getName() {
        return null;
    }

    public String getAddress() {
        return "02:00:00:00:00:00"; // AOSP's value for apps without LOCAL_MAC_ADDRESS
    }

    public int getScanMode() {
        return SCAN_MODE_NONE;
    }

    public boolean isDiscovering() {
        return false;
    }

    public boolean startDiscovery() {
        return false;
    }

    public boolean cancelDiscovery() {
        return false;
    }

    @Deprecated
    public boolean enable() {
        return false;
    }

    @Deprecated
    public boolean disable() {
        return false;
    }

    public int getProfileConnectionState(int profile) {
        return STATE_DISCONNECTED;
    }

    public boolean isLeEnabled() {
        return false;
    }

    public boolean isMultipleAdvertisementSupported() {
        return false;
    }

    public boolean isOffloadedFilteringSupported() {
        return false;
    }

    public boolean isOffloadedScanBatchingSupported() {
        return false;
    }

    public boolean isLe2MPhySupported() {
        return false;
    }

    public boolean isLeCodedPhySupported() {
        return false;
    }

    public boolean isLeExtendedAdvertisingSupported() {
        return false;
    }

    public boolean isLePeriodicAdvertisingSupported() {
        return false;
    }

    public int getLeMaximumAdvertisingDataLength() {
        return 0;
    }
}
