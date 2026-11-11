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

import java.util.UUID;

/**
 * AudioEffect is the base class for controlling audio effects provided by the android audio
 * framework (AOSP API; constants are AOSP's).
 *
 * teslalinux: AOSP's effects live in the audio server's effect libraries. This runtime has no
 * audio server and no effect library, so it is AOSP on a device whose effect configuration lists
 * no effects: {@link #queryEffects()} is empty, {@link #isEffectTypeAvailable} is false, and
 * creating an effect fails as AOSP's native setup does for a type no library implements
 * (IllegalArgumentException "Effect type: ... not supported."), which the create() helpers of
 * AcousticEchoCanceler, NoiseSuppressor and AutomaticGainControl turn into null.
 */
public class AudioEffect {
    private static final String TAG = "AudioEffect-JAVA";

    public static final UUID EFFECT_TYPE_ENV_REVERB = UUID.fromString("c2e5d5f0-94bd-4763-9cac-4e234d06839e");
    public static final UUID EFFECT_TYPE_PRESET_REVERB = UUID.fromString("47382d60-ddd8-11db-bf3a-0002a5d5c51b");
    public static final UUID EFFECT_TYPE_EQUALIZER = UUID.fromString("0bed4300-ddd6-11db-8f34-0002a5d5c51b");
    public static final UUID EFFECT_TYPE_BASS_BOOST = UUID.fromString("0634f220-ddd4-11db-a0fc-0002a5d5c51b");
    public static final UUID EFFECT_TYPE_VIRTUALIZER = UUID.fromString("37cc2c00-dddd-11db-8577-0002a5d5c51b");
    public static final UUID EFFECT_TYPE_AGC = UUID.fromString("0a8abfe0-654c-11e0-ba26-0002a5d5c51b");
    public static final UUID EFFECT_TYPE_AEC = UUID.fromString("7b491460-8d4d-11e0-bd61-0002a5d5c51b");
    public static final UUID EFFECT_TYPE_NS = UUID.fromString("58b4b260-8e06-11e0-aa8e-0002a5d5c51b");
    public static final UUID EFFECT_TYPE_LOUDNESS_ENHANCER = UUID.fromString("fe3199be-aed0-413f-87bb-11260eb63cf1");
    public static final UUID EFFECT_TYPE_DYNAMICS_PROCESSING = UUID.fromString("7261676f-6d75-7369-6364-28e2fd3ac39e");
    public static final UUID EFFECT_TYPE_HAPTIC_GENERATOR = UUID.fromString("1411e6d6-aecd-4021-a1cf-a6aceb0d71e5");
    /** @hide */
    public static final UUID EFFECT_TYPE_NULL = UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210");

    public static final int STATE_UNINITIALIZED = 0;
    public static final int STATE_INITIALIZED = 1;

    public static final int SUCCESS = 0;
    public static final int ERROR = -1;
    public static final int ALREADY_EXISTS = -2;
    public static final int ERROR_NO_INIT = -3;
    public static final int ERROR_BAD_VALUE = -4;
    public static final int ERROR_INVALID_OPERATION = -5;
    public static final int ERROR_NO_MEMORY = -6;
    public static final int ERROR_DEAD_OBJECT = -7;

    public static final String EFFECT_INSERT = "Insert";
    public static final String EFFECT_AUXILIARY = "Auxiliary";
    public static final String EFFECT_PRE_PROCESSING = "Pre Processing";
    public static final String EFFECT_POST_PROCESSING = "Post Processing";

    public static final String ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL = "android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL";
    public static final String ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION = "android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION";
    public static final String ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION = "android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION";
    public static final String EXTRA_AUDIO_SESSION = "android.media.extra.AUDIO_SESSION";
    public static final String EXTRA_PACKAGE_NAME = "android.media.extra.PACKAGE_NAME";
    public static final String EXTRA_CONTENT_TYPE = "android.media.extra.CONTENT_TYPE";
    public static final int CONTENT_TYPE_MUSIC = 0;
    public static final int CONTENT_TYPE_MOVIE = 1;
    public static final int CONTENT_TYPE_GAME = 2;
    public static final int CONTENT_TYPE_VOICE = 3;

    /** The effect descriptor (AOSP). */
    public static class Descriptor {
        public UUID type;
        public UUID uuid;
        public String connectMode;
        public String name;
        public String implementor;

        public Descriptor() {
        }

        public Descriptor(String type, String uuid, String connectMode, String name, String implementor) {
            this.type = UUID.fromString(type);
            this.uuid = UUID.fromString(uuid);
            this.connectMode = connectMode;
            this.name = name;
            this.implementor = implementor;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Descriptor)) return false;
            Descriptor that = (Descriptor) o;
            return java.util.Objects.equals(type, that.type) && java.util.Objects.equals(uuid, that.uuid)
                    && java.util.Objects.equals(connectMode, that.connectMode)
                    && java.util.Objects.equals(name, that.name)
                    && java.util.Objects.equals(implementor, that.implementor);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(type, uuid, connectMode, name, implementor);
        }
    }

    /** Listener for the enable status of an effect. */
    public interface OnEnableStatusChangeListener {
        void onEnableStatusChange(AudioEffect effect, boolean enabled);
    }

    /** Listener for the control status of an effect. */
    public interface OnControlStatusChangeListener {
        void onControlStatusChange(AudioEffect effect, boolean controlGranted);
    }

    private int mState = STATE_UNINITIALIZED;

    /** @hide AOSP: creates the effect engine of the type (or the exact implementation uuid). */
    public AudioEffect(UUID type, UUID uuid, int priority, int audioSession)
            throws IllegalArgumentException, UnsupportedOperationException, RuntimeException {
        if (type == null || uuid == null) {
            throw new IllegalArgumentException("Effect type: " + type + " not supported.");
        }
        // AOSP native_setup returns ERROR_BAD_VALUE when no effect library implements the type
        throw new IllegalArgumentException("Effect type: " + type + " not supported.");
    }

    /** No effect library: no effects. */
    public static Descriptor[] queryEffects() {
        return new Descriptor[0];
    }

    /** @hide */
    public static Descriptor[] queryPreProcessings(int audioSession) {
        return new Descriptor[0];
    }

    /** @hide */
    public static boolean isEffectTypeAvailable(UUID type) {
        for (Descriptor d : queryEffects()) {
            if (d.type.equals(type)) {
                return true;
            }
        }
        return false;
    }

    private void checkState(String methodName) throws IllegalStateException {
        if (mState != STATE_INITIALIZED) {
            throw new IllegalStateException(methodName + " called on uninitialized AudioEffect.");
        }
    }

    public void release() {
        mState = STATE_UNINITIALIZED;
    }

    public Descriptor getDescriptor() throws IllegalStateException {
        checkState("getDescriptor()");
        return null;
    }

    public int setEnabled(boolean enabled) throws IllegalStateException {
        checkState("setEnabled()");
        return ERROR_NO_INIT;
    }

    public boolean getEnabled() throws IllegalStateException {
        checkState("getEnabled()");
        return false;
    }

    public int getId() throws IllegalStateException {
        checkState("getId()");
        return 0;
    }

    public boolean hasControl() throws IllegalStateException {
        checkState("hasControl()");
        return false;
    }

    private OnEnableStatusChangeListener mEnableStatusChangeListener;
    private OnControlStatusChangeListener mControlChangeStatusListener;

    public void setEnableStatusListener(OnEnableStatusChangeListener listener) {
        synchronized (this) {
            mEnableStatusChangeListener = listener;
        }
    }

    public void setControlStatusListener(OnControlStatusChangeListener listener) {
        synchronized (this) {
            mControlChangeStatusListener = listener;
        }
    }
}
