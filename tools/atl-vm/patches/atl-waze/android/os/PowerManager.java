/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.os;

import android.util.Log;

/**
 * This class lets you query and request control of aspects of the device's power state (AOSP).
 *
 * <p>teslalinux: wake locks keep AOSP's client-side state exactly (reference counting, timeouts,
 * held state, work source, tags). The power service they talk to on Android does not exist here:
 * the car computer has no suspend and no screen of its own (the display is the car's browser), so
 * the system is always awake and interactive, which is what an acquired lock asks for. Battery
 * saver, doze and idle modes are features of a battery-powered device this platform lacks, and
 * report "off" as on a device without them.
 */
public final class PowerManager {
    private static final String TAG = "PowerManager";

    /* Wake lock levels and flags (AOSP values) */
    public static final int WAKE_LOCK_LEVEL_MASK = 0x0000ffff;
    public static final int PARTIAL_WAKE_LOCK = 0x00000001;
    @Deprecated
    public static final int SCREEN_DIM_WAKE_LOCK = 0x00000006;
    @Deprecated
    public static final int SCREEN_BRIGHT_WAKE_LOCK = 0x0000000a;
    @Deprecated
    public static final int FULL_WAKE_LOCK = 0x0000001a;
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = 0x00000020;
    /** @hide */
    public static final int DOZE_WAKE_LOCK = 0x00000040;
    /** @hide */
    public static final int DRAW_WAKE_LOCK = 0x00000080;
    /** @hide */
    public static final int SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK = 0x00000100;
    @Deprecated
    public static final int ACQUIRE_CAUSES_WAKEUP = 0x10000000;
    public static final int ON_AFTER_RELEASE = 0x20000000;
    /** @hide */
    public static final int UNIMPORTANT_FOR_LOGGING = 0x40000000;
    /** @hide */
    public static final int SYSTEM_WAKELOCK = 0x80000000;

    public static final int RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY = 1 << 0;
    /** @hide */
    public static final int RELEASE_FLAG_TIMEOUT = 1 << 16;

    /* Thermal status (AOSP values) */
    public static final int THERMAL_STATUS_NONE = 0;
    public static final int THERMAL_STATUS_LIGHT = 1;
    public static final int THERMAL_STATUS_MODERATE = 2;
    public static final int THERMAL_STATUS_SEVERE = 3;
    public static final int THERMAL_STATUS_CRITICAL = 4;
    public static final int THERMAL_STATUS_EMERGENCY = 5;
    public static final int THERMAL_STATUS_SHUTDOWN = 6;

    /* Location power-save modes (AOSP values) */
    public static final int LOCATION_MODE_NO_CHANGE = 0;
    public static final int LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF = 1;
    public static final int LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF = 2;
    public static final int LOCATION_MODE_FOREGROUND_ONLY = 3;
    public static final int LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF = 4;

    /** Broadcast actions (AOSP) */
    public static final String ACTION_POWER_SAVE_MODE_CHANGED = "android.os.action.POWER_SAVE_MODE_CHANGED";
    public static final String ACTION_DEVICE_IDLE_MODE_CHANGED = "android.os.action.DEVICE_IDLE_MODE_CHANGED";
    public static final String ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED = "android.os.action.LIGHT_DEVICE_IDLE_MODE_CHANGED";
    public static final String ACTION_LOW_POWER_STANDBY_ENABLED_CHANGED = "android.os.action.LOW_POWER_STANDBY_ENABLED_CHANGED";

    private final Handler mHandler;

    public PowerManager() {
        mHandler = new Handler(Looper.getMainLooper());
    }

    /** AOSP: the wake lock levels this device supports (all of them: the system is always on). */
    public boolean isWakeLockLevelSupported(int level) {
        switch (level) {
            case PARTIAL_WAKE_LOCK:
            case SCREEN_DIM_WAKE_LOCK:
            case SCREEN_BRIGHT_WAKE_LOCK:
            case FULL_WAKE_LOCK:
            case DOZE_WAKE_LOCK:
            case DRAW_WAKE_LOCK:
                return true;
            case PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                // no proximity sensor in a car computer
                return false;
            default:
                return false;
        }
    }

    /** @hide AOSP: argument checks of newWakeLock. */
    public static void validateWakeLockParameters(int levelAndFlags, String tag) {
        switch (levelAndFlags & WAKE_LOCK_LEVEL_MASK) {
            case PARTIAL_WAKE_LOCK:
            case SCREEN_DIM_WAKE_LOCK:
            case SCREEN_BRIGHT_WAKE_LOCK:
            case FULL_WAKE_LOCK:
            case PROXIMITY_SCREEN_OFF_WAKE_LOCK:
            case DOZE_WAKE_LOCK:
            case DRAW_WAKE_LOCK:
            case SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK:
                break;
            default:
                throw new IllegalArgumentException("Must specify a valid wake lock level.");
        }
        if (tag == null) {
            throw new IllegalArgumentException("The tag must not be null.");
        }
    }

    /**
     * Creates a new wake lock with the specified level and flags.
     */
    public WakeLock newWakeLock(int levelAndFlags, String tag) {
        validateWakeLockParameters(levelAndFlags, tag);
        return new WakeLock(levelAndFlags, tag);
    }

    /** AOSP (hidden, deprecated): user activity resets the screen-off timer; there is none here. */
    public void userActivity(long when, boolean noChangeLights) {
        // no display timeout to reset: the car's browser shows the screen while it is connected
    }

    /** AOSP: whether the device is interactive (always: no screen of its own to turn off). */
    public boolean isInteractive() {
        return true;
    }

    /** @deprecated use {@link #isInteractive}. */
    @Deprecated
    public boolean isScreenOn() {
        return isInteractive();
    }

    /** AOSP: battery saver; no battery, so never on. */
    public boolean isPowerSaveMode() {
        return false;
    }

    /** AOSP: location behaviour under battery saver; no battery saver, so no change. */
    public int getLocationPowerSaveMode() {
        return LOCATION_MODE_NO_CHANGE;
    }

    /** AOSP: battery optimisations do not exist here, so every app is exempt from them. */
    public boolean isIgnoringBatteryOptimizations(String packageName) {
        return true;
    }

    /** AOSP: the remaining battery discharge estimate is unknown (no battery). */
    public java.time.Duration getBatteryDischargePrediction() {
        return null;
    }

    /** AOSP: whether getBatteryDischargePrediction is personalized (never available). */
    public boolean isBatteryDischargePredictionPersonalized() {
        return false;
    }

    /**
     * A wake lock is a mechanism to indicate that your application needs to have the device stay
     * on (AOSP client side).
     */
    public final class WakeLock {
        private int mFlags;
        private String mTag;
        private final Object mToken = new Object();
        private int mInternalCount;
        private int mExternalCount;
        private boolean mRefCounted = true;
        private boolean mHeld;
        private WorkSource mWorkSource;
        private String mHistoryTag;
        private final Runnable mReleaser = new Runnable() {
            @Override
            public void run() {
                release(RELEASE_FLAG_TIMEOUT);
            }
        };

        WakeLock(int flags, String tag) {
            mFlags = flags;
            mTag = tag;
        }

        @Override
        protected void finalize() throws Throwable {
            synchronized (mToken) {
                if (mHeld) {
                    Log.wtf(TAG, "WakeLock finalized while still held: " + mTag);
                }
            }
        }

        /**
         * Sets whether this WakeLock is reference counted. Wake locks are reference counted by
         * default.
         */
        public void setReferenceCounted(boolean value) {
            synchronized (mToken) {
                mRefCounted = value;
            }
        }

        /** Acquires the wake lock. */
        public void acquire() {
            synchronized (mToken) {
                acquireLocked();
            }
        }

        /** Acquires the wake lock with a timeout after which it is released. */
        public void acquire(long timeout) {
            synchronized (mToken) {
                acquireLocked();
                mHandler.postDelayed(mReleaser, timeout);
            }
        }

        private void acquireLocked() {
            mInternalCount++;
            mExternalCount++;
            if (!mRefCounted || mInternalCount == 1) {
                // Do this even if the wake lock is already thought to be held (mHeld == true)
                // because non-reference counted wake locks are not always properly released.
                mHandler.removeCallbacks(mReleaser);
                mHeld = true;
            }
        }

        /** Releases the wake lock. */
        public void release() {
            release(0);
        }

        /** Releases the wake lock with flags. */
        public void release(int flags) {
            synchronized (mToken) {
                if (mInternalCount > 0) {
                    // internal count must only be decreased if it is > 0 or state of
                    // the WakeLock object is broken.
                    mInternalCount--;
                }
                if ((flags & RELEASE_FLAG_TIMEOUT) == 0) {
                    mExternalCount--;
                }
                if (!mRefCounted || mInternalCount == 0) {
                    mHandler.removeCallbacks(mReleaser);
                    if (mHeld) {
                        mHeld = false;
                    }
                }
                if (mRefCounted && mExternalCount < 0) {
                    throw new RuntimeException("WakeLock under-locked " + mTag);
                }
            }
        }

        /** Returns true if the wake lock has been acquired but not yet released. */
        public boolean isHeld() {
            synchronized (mToken) {
                return mHeld;
            }
        }

        /** Sets the work source associated with the wake lock. */
        public void setWorkSource(WorkSource ws) {
            synchronized (mToken) {
                if (ws != null && ws.isEmpty()) {
                    ws = null;
                }
                if (ws == null) {
                    mWorkSource = null;
                } else if (mWorkSource == null) {
                    mWorkSource = new WorkSource(ws);
                } else if (!mWorkSource.equals(ws)) {
                    mWorkSource.set(ws);
                }
            }
        }

        /** @hide */
        public void setTag(String tag) {
            mTag = tag;
        }

        /** @hide */
        public String getTag() {
            return mTag;
        }

        /** @hide */
        public void setHistoryTag(String tag) {
            mHistoryTag = tag;
        }

        /** @hide */
        public void setUnimportantForLogging(boolean state) {
            if (state) mFlags |= UNIMPORTANT_FOR_LOGGING;
            else mFlags &= ~UNIMPORTANT_FOR_LOGGING;
        }

        @Override
        public String toString() {
            synchronized (mToken) {
                return "WakeLock{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " held=" + mHeld + ", refCount=" + mInternalCount + "}";
            }
        }

        /**
         * Wraps a Runnable such that this method immediately acquires the wake lock and then once
         * the Runnable is done the wake lock is released.
         */
        public Runnable wrap(final Runnable r) {
            acquire();
            return new Runnable() {
                @Override
                public void run() {
                    try {
                        r.run();
                    } finally {
                        release();
                    }
                }
            };
        }
    }
}
