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

package android.net.wifi;

/**
 * This class provides the primary API for managing all aspects of Wi-Fi connectivity (AOSP API
 * subset).
 *
 * teslalinux: the Pi's Wi-Fi radio runs the car's access point for the host; the app has no
 * Wi-Fi client (its data goes over the LTE modem), so the station state stays unknown as before.
 * WifiLock keeps AOSP's reference counting: the lock only records what the app holds, because
 * there is no Wi-Fi power save of a client connection to keep off.
 */
public class WifiManager {

    public static final int WIFI_STATE_DISABLING = 0;
    public static final int WIFI_STATE_DISABLED = 1;
    public static final int WIFI_STATE_ENABLING = 2;
    public static final int WIFI_STATE_ENABLED = 3;
    public static final int WIFI_STATE_UNKNOWN = 4;

    @Deprecated
    public static final int WIFI_MODE_FULL = 1;
    @Deprecated
    public static final int WIFI_MODE_SCAN_ONLY = 2;
    public static final int WIFI_MODE_FULL_HIGH_PERF = 3;
    public static final int WIFI_MODE_FULL_LOW_LATENCY = 4;

    private static final int MAX_ACTIVE_LOCKS = 50;
    private static int sActiveLockCount;

    /** Allows an application to keep the Wi-Fi radio awake (AOSP reference counting). */
    public class WifiLock {
        private final String mTag;
        private final int mLockType;
        private int mRefCount;
        private boolean mRefCounted = true;
        private boolean mHeld;

        private WifiLock(int lockType, String tag) {
            mTag = tag;
            mLockType = lockType;
        }

        public void acquire() {
            synchronized (WifiManager.class) {
                if (mRefCounted ? (++mRefCount == 1) : (!mHeld)) {
                    if (sActiveLockCount >= MAX_ACTIVE_LOCKS) {
                        mRefCount--;
                        throw new UnsupportedOperationException("Exceeded maximum number of wifi locks");
                    }
                    sActiveLockCount++;
                    mHeld = true;
                }
            }
        }

        public void release() {
            synchronized (WifiManager.class) {
                if (mRefCounted ? (--mRefCount == 0) : (mHeld)) {
                    sActiveLockCount--;
                    mHeld = false;
                }
                if (mRefCount < 0) {
                    throw new RuntimeException("WifiLock under-locked " + mTag);
                }
            }
        }

        public void setReferenceCounted(boolean refCounted) {
            mRefCounted = refCounted;
        }

        public boolean isHeld() {
            synchronized (WifiManager.class) {
                return mHeld;
            }
        }

        @Override
        public String toString() {
            return "WifiLock{ " + mTag + "; type=" + mLockType + "; held=" + mHeld
                    + (mRefCounted ? "; refcounted: refcount = " + mRefCount : "; not refcounted") + " }";
        }
    }

    public WifiLock createWifiLock(int lockType, String tag) {
        return new WifiLock(lockType, tag);
    }

    @Deprecated
    public WifiLock createWifiLock(String tag) {
        return new WifiLock(WIFI_MODE_FULL, tag);
    }

    public WifiInfo getConnectionInfo() {
        return new WifiInfo();
    }

    public int getWifiState() {
        return WIFI_STATE_UNKNOWN;
    }

    public boolean isWifiEnabled() {
        return false;
    }
}
