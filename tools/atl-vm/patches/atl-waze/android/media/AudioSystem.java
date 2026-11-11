/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.media;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The hidden AOSP class the public media classes share their constants and audio-system calls
 * through.
 *
 * teslalinux: AOSP's AudioSystem is a JNI front end to the native AudioSystem/AudioFlinger. This
 * runtime has no audio server: AudioTrack writes straight to ALSA (PipeWire's ALSA plugin on the
 * Pi), so this class carries the AOSP constants the ported classes (AudioAttributes, AudioManager,
 * AudioTrack) use and the one audio-server service they need, unique audio session ids.
 * @hide
 */
public class AudioSystem {
    private AudioSystem() {}

    /* These values must be kept in sync with system/audio.h */
    public static final int STREAM_DEFAULT = -1;
    public static final int STREAM_VOICE_CALL = 0;
    public static final int STREAM_SYSTEM = 1;
    public static final int STREAM_RING = 2;
    public static final int STREAM_MUSIC = 3;
    public static final int STREAM_ALARM = 4;
    public static final int STREAM_NOTIFICATION = 5;
    public static final int STREAM_BLUETOOTH_SCO = 6;
    public static final int STREAM_SYSTEM_ENFORCED = 7;
    public static final int STREAM_DTMF = 8;
    public static final int STREAM_TTS = 9;
    public static final int STREAM_ACCESSIBILITY = 10;
    public static final int STREAM_ASSISTANT = 11;
    /** Number of stream types (AOSP AudioSystem.getNumStreamTypes()). */
    public static final int NUM_STREAM_TYPES = 12;

    public static final String[] STREAM_NAMES = new String[] {
        "STREAM_VOICE_CALL", "STREAM_SYSTEM", "STREAM_RING", "STREAM_MUSIC", "STREAM_ALARM",
        "STREAM_NOTIFICATION", "STREAM_BLUETOOTH_SCO", "STREAM_SYSTEM_ENFORCED", "STREAM_DTMF",
        "STREAM_TTS", "STREAM_ACCESSIBILITY", "STREAM_ASSISTANT"
    };

    public static int getNumStreamTypes() {
        return NUM_STREAM_TYPES;
    }

    /** AOSP AudioSystem.DEFAULT_STREAM_VOLUME: the initial volume index of each stream. */
    public static final int[] DEFAULT_STREAM_VOLUME = new int[] {
        4,  // STREAM_VOICE_CALL
        7,  // STREAM_SYSTEM
        5,  // STREAM_RING
        5,  // STREAM_MUSIC
        6,  // STREAM_ALARM
        5,  // STREAM_NOTIFICATION
        7,  // STREAM_BLUETOOTH_SCO
        7,  // STREAM_SYSTEM_ENFORCED
        5,  // STREAM_DTMF
        5,  // STREAM_TTS
        5,  // STREAM_ACCESSIBILITY
        5,  // STREAM_ASSISTANT
    };

    // audio modes (AudioManager.MODE_*)
    public static final int MODE_INVALID = -2;
    public static final int MODE_CURRENT = -1;
    public static final int MODE_NORMAL = 0;
    public static final int MODE_RINGTONE = 1;
    public static final int MODE_IN_CALL = 2;
    public static final int MODE_IN_COMMUNICATION = 3;
    public static final int MODE_CALL_SCREENING = 4;
    public static final int NUM_MODES = 5;

    // AOSP audio_unique_id_use_t: the low bits of every unique id say what it is used for
    private static final int AUDIO_UNIQUE_ID_USE_SESSION = 1;
    private static final int AUDIO_UNIQUE_ID_USE_MAX = 8;
    private static final AtomicInteger sNextUniqueId = new AtomicInteger(1);

    /**
     * AOSP AudioSystem.newAudioSessionId(): a new process-unique audio session id, built like
     * AudioFlinger::nextUniqueId(AUDIO_UNIQUE_ID_USE_SESSION) (a counter shifted past the use bits,
     * or'ed with the use). Never AudioManager.AUDIO_SESSION_ID_GENERATE (0).
     */
    public static int newAudioSessionId() {
        return (sNextUniqueId.getAndIncrement() * AUDIO_UNIQUE_ID_USE_MAX) | AUDIO_UNIQUE_ID_USE_SESSION;
    }
}
