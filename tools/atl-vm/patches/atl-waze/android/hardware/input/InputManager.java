/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.input;

import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides information about input devices and available key layouts (AOSP API).
 *
 * teslalinux: the input devices are the ones InputDevice reports for the compositor seat (the
 * car's touchscreen through tacomp, keyboard); the seat does not hot-plug devices, so registered
 * listeners are kept (AOSP's InputDeviceListenerDelegate list) and
 * {@link #tlInputDevicesChanged} tells them when the device list changes.
 */
public final class InputManager {
    public interface InputDeviceListener {
        void onInputDeviceAdded(int deviceId);

        void onInputDeviceRemoved(int deviceId);

        void onInputDeviceChanged(int deviceId);
    }

    private static final class ListenerRecord {
        final InputDeviceListener listener;
        final Handler handler;

        ListenerRecord(InputDeviceListener listener, Handler handler) {
            this.listener = listener;
            this.handler = handler;
        }
    }

    private static final CopyOnWriteArrayList<ListenerRecord> sListeners = new CopyOnWriteArrayList<>();
    private static int[] sKnownIds;

    /** @hide */
    public InputManager() {
    }

    public int[] getInputDeviceIds() {
        return InputDevice.getDeviceIds();
    }

    public InputDevice getInputDevice(int id) {
        return InputDevice.getDevice(id);
    }

    public void registerInputDeviceListener(InputDeviceListener listener, Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        synchronized (sListeners) {
            if (sKnownIds == null)
                sKnownIds = InputDevice.getDeviceIds();
            for (ListenerRecord r : sListeners) {
                if (r.listener == listener)
                    return;
            }
            Handler h = handler != null ? handler
                    : new Handler(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper());
            sListeners.add(new ListenerRecord(listener, h));
        }
    }

    public void unregisterInputDeviceListener(InputDeviceListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        for (ListenerRecord r : sListeners) {
            if (r.listener == listener)
                sListeners.remove(r);
        }
    }

    private static boolean contains(int[] ids, int id) {
        for (int i : ids) {
            if (i == id)
                return true;
        }
        return false;
    }

    /** @hide the device list may have changed: added / removed events, as AOSP's onInputDevicesChanged */
    public static void tlInputDevicesChanged() {
        final int[] now = InputDevice.getDeviceIds();
        final int[] before;
        synchronized (sListeners) {
            before = sKnownIds == null ? new int[0] : sKnownIds;
            sKnownIds = now;
        }
        for (final ListenerRecord r : sListeners) {
            for (final int id : before) {
                if (!contains(now, id)) {
                    r.handler.post(new Runnable() {
                        public void run() {
                            r.listener.onInputDeviceRemoved(id);
                        }
                    });
                }
            }
            for (final int id : now) {
                if (!contains(before, id)) {
                    r.handler.post(new Runnable() {
                        public void run() {
                            r.listener.onInputDeviceAdded(id);
                        }
                    });
                }
            }
        }
    }
}
