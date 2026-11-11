/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.media.audiofx;

/**
 * An Equalizer is used to alter the frequency response of a particular music source (AOSP API).
 *
 * teslalinux: there is no effect library on this device (see AudioEffect), so creating one fails
 * as AOSP's does for an effect type no library implements; no Equalizer object can exist, and its
 * methods keep AOSP's uninitialized-effect behaviour.
 */
public class Equalizer extends AudioEffect {
    public static final int PARAM_NUM_BANDS = 0;
    public static final int PARAM_LEVEL_RANGE = 1;
    public static final int PARAM_BAND_LEVEL = 2;
    public static final int PARAM_CENTER_FREQ = 3;
    public static final int PARAM_BAND_FREQ_RANGE = 4;
    public static final int PARAM_GET_BAND = 5;
    public static final int PARAM_CURRENT_PRESET = 6;
    public static final int PARAM_GET_NUM_OF_PRESETS = 7;
    public static final int PARAM_GET_PRESET_NAME = 8;
    public static final int PARAM_STRING_SIZE_MAX = 32;

    public Equalizer(int priority, int audioSession)
            throws IllegalStateException, IllegalArgumentException, UnsupportedOperationException, RuntimeException {
        super(EFFECT_TYPE_EQUALIZER, EFFECT_TYPE_NULL, priority, audioSession);
    }

    public void usePreset(short preset) throws IllegalStateException, IllegalArgumentException,
            UnsupportedOperationException {
        throw new IllegalStateException("usePreset() called on uninitialized AudioEffect.");
    }

    public short getNumberOfBands() throws IllegalStateException {
        throw new IllegalStateException("getNumberOfBands() called on uninitialized AudioEffect.");
    }

    public short getNumberOfPresets() throws IllegalStateException {
        throw new IllegalStateException("getNumberOfPresets() called on uninitialized AudioEffect.");
    }
}
