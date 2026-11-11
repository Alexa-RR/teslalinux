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

package android.hardware.display;

import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the properties of attached displays (AOSP API).
 *
 * teslalinux: the runtime has one display, the app's window on the compositor (tacomp streams it
 * to the car), with id Display.DEFAULT_DISPLAY (0); there are no secondary or presentation
 * displays. Display listeners are kept and told about changes of that display through
 * {@link #tlDefaultDisplayChanged()} (the window being resized), as AOSP's DisplayManagerGlobal
 * dispatches EVENT_DISPLAY_CHANGED.
 */
public final class DisplayManager {
    public static final String DISPLAY_CATEGORY_PRESENTATION = "android.hardware.display.category.PRESENTATION";
    public static final String DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED = "android.hardware.display.category.ALL_INCLUDING_DISABLED";

    public static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 << 0;
    public static final int VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 << 1;
    public static final int VIRTUAL_DISPLAY_FLAG_SECURE = 1 << 2;
    public static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 << 3;
    public static final int VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 << 4;

    private static final int DEFAULT_DISPLAY = 0;

    /** Listens for changes in available display devices. */
    public interface DisplayListener {
        void onDisplayAdded(int displayId);

        void onDisplayRemoved(int displayId);

        void onDisplayChanged(int displayId);
    }

    private static final class ListenerRecord {
        final DisplayListener listener;
        final Handler handler;

        ListenerRecord(DisplayListener listener, Handler handler) {
            this.listener = listener;
            this.handler = handler;
        }
    }

    private static final CopyOnWriteArrayList<ListenerRecord> sListeners = new CopyOnWriteArrayList<>();

    /** @hide */
    public DisplayManager() {
    }

    /** The display with the id, or null if there is none (only the default display exists). */
    public Display getDisplay(int displayId) {
        return displayId == DEFAULT_DISPLAY ? new Display() : null;
    }

    public Display[] getDisplays() {
        return new Display[] { getDisplay(DEFAULT_DISPLAY) };
    }

    /** AOSP: presentation displays are the secondary ones; the default display is never listed. */
    public Display[] getDisplays(String category) {
        if (DISPLAY_CATEGORY_PRESENTATION.equals(category)) {
            return new Display[0];
        }
        return getDisplays();
    }

    public void registerDisplayListener(DisplayListener listener, Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        for (ListenerRecord r : sListeners) {
            if (r.listener == listener)
                return;
        }
        Handler h = handler != null ? handler
                : new Handler(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper());
        sListeners.add(new ListenerRecord(listener, h));
    }

    public void unregisterDisplayListener(DisplayListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        for (ListenerRecord r : sListeners) {
            if (r.listener == listener)
                sListeners.remove(r);
        }
    }

    /** @hide the default display changed (size, density): EVENT_DISPLAY_CHANGED to every listener */
    public static void tlDefaultDisplayChanged() {
        for (final ListenerRecord r : sListeners) {
            r.handler.post(new Runnable() {
                @Override
                public void run() {
                    r.listener.onDisplayChanged(DEFAULT_DISPLAY);
                }
            });
        }
    }
}
