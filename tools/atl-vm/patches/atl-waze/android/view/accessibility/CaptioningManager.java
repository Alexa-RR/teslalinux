/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.view.accessibility;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Contains methods for accessing and monitoring preferred video captioning state and visual
 * properties.
 *
 * teslalinux: AOSP reads these from Settings.Secure (accessibility_captioning_*), which only the
 * Settings app's captions screen writes. The car has no such screen, so every value is the AOSP
 * default a fresh device reports (captions off, no preferred locale, scale 1.0, the default
 * style) and no change is ever announced to the listeners.
 */
public class CaptioningManager {
    /** Default captioning enabled value. */
    private static final int DEFAULT_ENABLED = 0;
    /** Default style preset as an index into CaptionStyle.PRESETS. */
    private static final int DEFAULT_PRESET = 0;
    /** Default scaling value for caption fonts. */
    private static final float DEFAULT_FONT_SCALE = 1;

    private final ArrayList<CaptioningChangeListener> mListeners = new ArrayList<>();

    /**
     * @return the user's preferred captioning enabled state
     */
    public final boolean isEnabled() {
        return DEFAULT_ENABLED == 1;
    }

    /**
     * @return the raw locale string for the user's preferred captioning language, or null
     */
    public final String getRawLocale() {
        return null;
    }

    /**
     * @return the locale for the user's preferred captioning language, or null if not specified
     */
    public final Locale getLocale() {
        final String rawLocale = getRawLocale();
        if (rawLocale != null && !rawLocale.isEmpty()) {
            final String[] splitLocale = rawLocale.split("_");
            switch (splitLocale.length) {
                case 3:
                    return new Locale(splitLocale[0], splitLocale[1], splitLocale[2]);
                case 2:
                    return new Locale(splitLocale[0], splitLocale[1]);
                case 1:
                    return new Locale(splitLocale[0]);
            }
        }
        return null;
    }

    /**
     * @return the user's preferred font scaling factor for video captions, or 1 if not specified
     */
    public final float getFontScale() {
        return DEFAULT_FONT_SCALE;
    }

    /** @hide the user's preferred style preset */
    public int getRawUserStyle() {
        return DEFAULT_PRESET;
    }

    /** AOSP: whether system audio captioning (Live Caption) is on; the car has none */
    public final boolean isSystemAudioCaptioningEnabled() {
        return false;
    }

    /** AOSP: whether the Live Caption toggle is shown in the volume dialog; there is none here */
    public final boolean isSystemAudioCaptioningUiEnabled() {
        return false;
    }

    /**
     * Adds a listener for changes in the user's preferred captioning enabled state and visual
     * properties.
     */
    public void addCaptioningChangeListener(CaptioningChangeListener listener) {
        synchronized (mListeners) {
            if (!mListeners.contains(listener))
                mListeners.add(listener);
        }
    }

    /**
     * Removes a listener previously added using {@link #addCaptioningChangeListener}.
     */
    public void removeCaptioningChangeListener(CaptioningChangeListener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }

    /**
     * Listener for changes in captioning properties, including enabled state and user style
     * preferences.
     */
    public static abstract class CaptioningChangeListener {
        /** Called when the captioning enabled state changes. */
        public void onEnabledChanged(boolean enabled) {}

        /** Called when the captioning locale changes. */
        public void onLocaleChanged(Locale locale) {}

        /** Called when the captioning font scaling factor changes. */
        public void onFontScaleChanged(float fontScale) {}

        /** Called when the system audio caption enabled state changes. */
        public void onSystemAudioCaptioningChanged(boolean enabled) {}

        /** Called when the system audio caption UI enabled state changes. */
        public void onSystemAudioCaptioningUiChanged(boolean enabled) {}
    }
}
