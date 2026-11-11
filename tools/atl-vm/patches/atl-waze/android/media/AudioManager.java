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

package android.media;

import android.app.ActivityThread;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * AudioManager provides access to volume and ringer mode control (AOSP android14 API).
 *
 * teslalinux: AOSP's AudioManager is a client of AudioService in the system server. This runtime
 * runs one app and no audio server, so the service state lives here, in-process, shared by every
 * AudioManager instance, with AudioService's rules:
 * <ul>
 * <li>Stream volumes: AOSP's index ranges and defaults, the stream aliasing of a device that is
 *     not voice capable (STREAM_VOLUME_ALIAS_NONE), mute state, ringer-mode muting. The volume of a
 *     track's stream is applied by AudioTrack as AOSP's AudioFlinger applies it, through AOSP's
 *     default speaker volume curve (index to dB).</li>
 * <li>Audio focus: AOSP's MediaFocusControl focus stack (loss types from gain types, permanent loss
 *     removes the loser, the next owner regains focus on abandon), dispatched on the request's
 *     handler.</li>
 * <li>Audio mode and communication routing with their listeners. The host has one output, so the
 *     communication device is always that speaker; speakerphone is the requested route (AOSP 12+);
 *     there is no Bluetooth, so SCO never connects.</li>
 * <li>Activity: isMusicActive / isStreamActive from the AudioTracks that are playing.</li>
 * <li>Devices: the host output (and a capture device only if ALSA lists one), see
 *     {@link #getDevices(int)}.</li>
 * </ul>
 * There is no system volume panel and no UI sound files, so FLAG_SHOW_UI shows nothing and sound
 * effects have nothing to play, as on AOSP when the effect files fail to load.
 */
public class AudioManager {
    private static final String TAG = "AudioManager";

    // ---- intents and extras ----
    public static final String ACTION_AUDIO_BECOMING_NOISY = "android.media.AUDIO_BECOMING_NOISY";
    public static final String RINGER_MODE_CHANGED_ACTION = "android.media.RINGER_MODE_CHANGED";
    /** @hide */
    public static final String INTERNAL_RINGER_MODE_CHANGED_ACTION = "android.media.INTERNAL_RINGER_MODE_CHANGED_ACTION";
    public static final String EXTRA_RINGER_MODE = "android.media.EXTRA_RINGER_MODE";
    @Deprecated
    public static final String VIBRATE_SETTING_CHANGED_ACTION = "android.media.VIBRATE_SETTING_CHANGED";
    /** @hide */
    public static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    /** @hide */
    public static final String STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION";
    /** @hide */
    public static final String MASTER_MUTE_CHANGED_ACTION = "android.media.MASTER_MUTE_CHANGED_ACTION";
    /** @hide */
    public static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    /** @hide */
    public static final String EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    /** @hide */
    public static final String EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";
    /** @hide */
    public static final String EXTRA_STREAM_VOLUME_MUTED = "android.media.EXTRA_STREAM_VOLUME_MUTED";
    @Deprecated
    public static final String EXTRA_VIBRATE_SETTING = "android.media.EXTRA_VIBRATE_SETTING";
    @Deprecated
    public static final String EXTRA_VIBRATE_TYPE = "android.media.EXTRA_VIBRATE_TYPE";
    public static final String ACTION_HEADSET_PLUG = "android.intent.action.HEADSET_PLUG";
    public static final String ACTION_HDMI_AUDIO_PLUG = "android.media.action.HDMI_AUDIO_PLUG";
    public static final String EXTRA_AUDIO_PLUG_STATE = "android.media.extra.AUDIO_PLUG_STATE";
    public static final String EXTRA_MAX_CHANNEL_COUNT = "android.media.extra.MAX_CHANNEL_COUNT";
    public static final String EXTRA_ENCODINGS = "android.media.extra.ENCODINGS";
    public static final String ACTION_MICROPHONE_MUTE_CHANGED = "android.media.action.MICROPHONE_MUTE_CHANGED";
    public static final String ACTION_SPEAKERPHONE_STATE_CHANGED = "android.media.action.SPEAKERPHONE_STATE_CHANGED";
    @Deprecated
    public static final String ACTION_SCO_AUDIO_STATE_CHANGED = "android.media.SCO_AUDIO_STATE_CHANGED";
    public static final String ACTION_SCO_AUDIO_STATE_UPDATED = "android.media.ACTION_SCO_AUDIO_STATE_UPDATED";
    public static final String EXTRA_SCO_AUDIO_STATE = "android.media.extra.SCO_AUDIO_STATE";
    public static final String EXTRA_SCO_AUDIO_PREVIOUS_STATE = "android.media.extra.SCO_AUDIO_PREVIOUS_STATE";
    public static final String ACTION_AUDIO_SESSION_OPENED = "android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION";
    public static final String ACTION_AUDIO_SESSION_CLOSED = "android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION";

    public static final String PROPERTY_OUTPUT_SAMPLE_RATE = "android.media.property.OUTPUT_SAMPLE_RATE";
    public static final String PROPERTY_OUTPUT_FRAMES_PER_BUFFER = "android.media.property.OUTPUT_FRAMES_PER_BUFFER";
    public static final String PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND = "android.media.property.SUPPORT_MIC_NEAR_ULTRASOUND";
    public static final String PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND = "android.media.property.SUPPORT_SPEAKER_NEAR_ULTRASOUND";
    public static final String PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED = "android.media.property.SUPPORT_AUDIO_SOURCE_UNPROCESSED";

    // ---- streams ----
    public static final int STREAM_VOICE_CALL = AudioSystem.STREAM_VOICE_CALL;
    public static final int STREAM_SYSTEM = AudioSystem.STREAM_SYSTEM;
    public static final int STREAM_RING = AudioSystem.STREAM_RING;
    public static final int STREAM_MUSIC = AudioSystem.STREAM_MUSIC;
    public static final int STREAM_ALARM = AudioSystem.STREAM_ALARM;
    public static final int STREAM_NOTIFICATION = AudioSystem.STREAM_NOTIFICATION;
    /** @hide */
    public static final int STREAM_BLUETOOTH_SCO = AudioSystem.STREAM_BLUETOOTH_SCO;
    /** @hide */
    public static final int STREAM_SYSTEM_ENFORCED = AudioSystem.STREAM_SYSTEM_ENFORCED;
    public static final int STREAM_DTMF = AudioSystem.STREAM_DTMF;
    /** @hide */
    public static final int STREAM_TTS = AudioSystem.STREAM_TTS;
    public static final int STREAM_ACCESSIBILITY = AudioSystem.STREAM_ACCESSIBILITY;
    /** @hide */
    public static final int STREAM_ASSISTANT = AudioSystem.STREAM_ASSISTANT;
    @Deprecated
    public static final int NUM_STREAMS = AudioSystem.NUM_STREAM_TYPES;
    public static final int USE_DEFAULT_STREAM_TYPE = Integer.MIN_VALUE;

    public static final int ADJUST_RAISE = 1;
    public static final int ADJUST_LOWER = -1;
    public static final int ADJUST_SAME = 0;
    public static final int ADJUST_MUTE = -100;
    public static final int ADJUST_UNMUTE = 100;
    public static final int ADJUST_TOGGLE_MUTE = 101;

    public static final int FLAG_SHOW_UI = 1 << 0;
    public static final int FLAG_ALLOW_RINGER_MODES = 1 << 1;
    public static final int FLAG_PLAY_SOUND = 1 << 2;
    public static final int FLAG_REMOVE_SOUND_AND_VIBRATE = 1 << 3;
    public static final int FLAG_VIBRATE = 1 << 4;
    /** @hide */
    public static final int FLAG_FIXED_VOLUME = 1 << 5;
    /** @hide */
    public static final int FLAG_BLUETOOTH_ABS_VOLUME = 1 << 6;
    /** @hide */
    public static final int FLAG_SHOW_SILENT_HINT = 1 << 7;
    /** @hide */
    public static final int FLAG_HDMI_SYSTEM_AUDIO_VOLUME = 1 << 8;
    /** @hide */
    public static final int FLAG_ACTIVE_MEDIA_ONLY = 1 << 9;
    /** @hide */
    public static final int FLAG_SHOW_UI_WARNINGS = 1 << 10;
    /** @hide */
    public static final int FLAG_SHOW_VIBRATE_HINT = 1 << 11;
    /** @hide */
    public static final int FLAG_FROM_KEY = 1 << 12;

    public static final int RINGER_MODE_SILENT = 0;
    public static final int RINGER_MODE_VIBRATE = 1;
    public static final int RINGER_MODE_NORMAL = 2;
    /** @hide */
    public static final int RINGER_MODE_MAX = RINGER_MODE_NORMAL;

    @Deprecated
    public static final int VIBRATE_TYPE_RINGER = 0;
    @Deprecated
    public static final int VIBRATE_TYPE_NOTIFICATION = 1;
    @Deprecated
    public static final int VIBRATE_SETTING_OFF = 0;
    @Deprecated
    public static final int VIBRATE_SETTING_ON = 1;
    @Deprecated
    public static final int VIBRATE_SETTING_ONLY_SILENT = 2;

    public static final int MODE_INVALID = AudioSystem.MODE_INVALID;
    public static final int MODE_CURRENT = AudioSystem.MODE_CURRENT;
    public static final int MODE_NORMAL = AudioSystem.MODE_NORMAL;
    public static final int MODE_RINGTONE = AudioSystem.MODE_RINGTONE;
    public static final int MODE_IN_CALL = AudioSystem.MODE_IN_CALL;
    public static final int MODE_IN_COMMUNICATION = AudioSystem.MODE_IN_COMMUNICATION;
    public static final int MODE_CALL_SCREENING = AudioSystem.MODE_CALL_SCREENING;
    public static final int MODE_CALL_REDIRECT = 5;
    public static final int MODE_COMMUNICATION_REDIRECT = 6;

    @Deprecated
    public static final int ROUTE_EARPIECE = 1 << 0;
    @Deprecated
    public static final int ROUTE_SPEAKER = 1 << 1;
    @Deprecated
    public static final int ROUTE_BLUETOOTH = 1 << 2;
    @Deprecated
    public static final int ROUTE_BLUETOOTH_SCO = 1 << 2;
    @Deprecated
    public static final int ROUTE_HEADSET = 1 << 3;
    @Deprecated
    public static final int ROUTE_BLUETOOTH_A2DP = 1 << 4;
    @Deprecated
    public static final int ROUTE_ALL = 0xFFFFFFFF;

    public static final int FX_KEY_CLICK = 0;
    public static final int FX_FOCUS_NAVIGATION_UP = 1;
    public static final int FX_FOCUS_NAVIGATION_DOWN = 2;
    public static final int FX_FOCUS_NAVIGATION_LEFT = 3;
    public static final int FX_FOCUS_NAVIGATION_RIGHT = 4;
    public static final int FX_KEYPRESS_STANDARD = 5;
    public static final int FX_KEYPRESS_SPACEBAR = 6;
    public static final int FX_KEYPRESS_DELETE = 7;
    public static final int FX_KEYPRESS_RETURN = 8;
    public static final int FX_KEYPRESS_INVALID = 9;
    public static final int FX_BACK = 10;
    /** @hide */
    public static final int FX_HOME = 11;
    /** @hide */
    public static final int NUM_SOUND_EFFECTS = 16;

    public static final int AUDIOFOCUS_NONE = 0;
    public static final int AUDIOFOCUS_GAIN = 1;
    public static final int AUDIOFOCUS_GAIN_TRANSIENT = 2;
    public static final int AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK = 3;
    public static final int AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE = 4;
    public static final int AUDIOFOCUS_LOSS = -1 * AUDIOFOCUS_GAIN;
    public static final int AUDIOFOCUS_LOSS_TRANSIENT = -1 * AUDIOFOCUS_GAIN_TRANSIENT;
    public static final int AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -1 * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
    public static final int AUDIOFOCUS_REQUEST_FAILED = 0;
    public static final int AUDIOFOCUS_REQUEST_GRANTED = 1;
    public static final int AUDIOFOCUS_REQUEST_DELAYED = 2;
    /** @hide */
    public static final int AUDIOFOCUS_REQUEST_WAITING_FOR_EXT_POLICY = 100;
    /** @hide */
    public static final int AUDIOFOCUS_FLAG_DELAY_OK = 0x1 << 0;
    /** @hide */
    public static final int AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS = 0x1 << 1;
    /** @hide */
    public static final int AUDIOFOCUS_FLAG_LOCK = 0x1 << 2;
    /** @hide */
    public static final int AUDIOFOCUS_FLAG_TEST = 0x1 << 3;

    public static final int SCO_AUDIO_STATE_DISCONNECTED = 0;
    public static final int SCO_AUDIO_STATE_CONNECTED = 1;
    public static final int SCO_AUDIO_STATE_CONNECTING = 2;
    public static final int SCO_AUDIO_STATE_ERROR = -1;

    public static final int GET_DEVICES_INPUTS = 0x0001;
    public static final int GET_DEVICES_OUTPUTS = 0x0002;
    public static final int GET_DEVICES_ALL = GET_DEVICES_OUTPUTS | GET_DEVICES_INPUTS;

    public static final int AUDIO_SESSION_ID_GENERATE = 0;
    /** @hide */
    public static final int SUCCESS = 0;
    public static final int ERROR = -1;
    public static final int ERROR_DEAD_OBJECT = -6;

    public static final int PLAYBACK_OFFLOAD_NOT_SUPPORTED = 0;
    public static final int PLAYBACK_OFFLOAD_SUPPORTED = 1;
    public static final int PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED = 2;
    public static final int DIRECT_PLAYBACK_NOT_SUPPORTED = 0;
    public static final int DIRECT_PLAYBACK_OFFLOAD_SUPPORTED = 1;
    public static final int DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED = 3;
    public static final int DIRECT_PLAYBACK_BITSTREAM_SUPPORTED = 4;

    public static final int ENCODED_SURROUND_OUTPUT_UNKNOWN = -1;
    public static final int ENCODED_SURROUND_OUTPUT_AUTO = 0;
    public static final int ENCODED_SURROUND_OUTPUT_NEVER = 1;
    public static final int ENCODED_SURROUND_OUTPUT_ALWAYS = 2;
    public static final int ENCODED_SURROUND_OUTPUT_MANUAL = 3;

    // ---- listener types ----

    public interface OnAudioFocusChangeListener {
        void onAudioFocusChange(int focusChange);
    }

    public interface OnModeChangedListener {
        void onModeChanged(int mode);
    }

    public interface OnCommunicationDeviceChangedListener {
        void onCommunicationDeviceChanged(AudioDeviceInfo device);
    }

    public static abstract class AudioPlaybackCallback {
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
        }
    }

    public static abstract class AudioRecordingCallback {
        public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
        }
    }

    // ================================================================================================
    // the in-process "AudioService"
    // ================================================================================================

    /** AOSP AudioService.MAX_STREAM_VOLUME */
    private static final int[] MAX_STREAM_VOLUME = new int[] {
        5,  // STREAM_VOICE_CALL
        7,  // STREAM_SYSTEM
        7,  // STREAM_RING
        15, // STREAM_MUSIC
        7,  // STREAM_ALARM
        7,  // STREAM_NOTIFICATION
        15, // STREAM_BLUETOOTH_SCO
        7,  // STREAM_SYSTEM_ENFORCED
        15, // STREAM_DTMF
        15, // STREAM_TTS
        15, // STREAM_ACCESSIBILITY
        15, // STREAM_ASSISTANT
    };

    /** AOSP AudioService.MIN_STREAM_VOLUME */
    private static final int[] MIN_STREAM_VOLUME = new int[] {
        1,  // STREAM_VOICE_CALL
        0,  // STREAM_SYSTEM
        0,  // STREAM_RING
        0,  // STREAM_MUSIC
        1,  // STREAM_ALARM
        0,  // STREAM_NOTIFICATION
        0,  // STREAM_BLUETOOTH_SCO
        0,  // STREAM_SYSTEM_ENFORCED
        0,  // STREAM_DTMF
        0,  // STREAM_TTS
        1,  // STREAM_ACCESSIBILITY
        0,  // STREAM_ASSISTANT
    };

    /**
     * AOSP AudioService.STREAM_VOLUME_ALIAS_NONE: the aliasing of a platform that is neither a
     * phone (voice capable) nor a TV, which is what this car unit is.
     */
    private static final int[] STREAM_VOLUME_ALIAS = new int[] {
        STREAM_VOICE_CALL,      // STREAM_VOICE_CALL
        STREAM_MUSIC,           // STREAM_SYSTEM
        STREAM_RING,            // STREAM_RING
        STREAM_MUSIC,           // STREAM_MUSIC
        STREAM_ALARM,           // STREAM_ALARM
        STREAM_RING,            // STREAM_NOTIFICATION
        STREAM_BLUETOOTH_SCO,   // STREAM_BLUETOOTH_SCO
        STREAM_MUSIC,           // STREAM_SYSTEM_ENFORCED
        STREAM_MUSIC,           // STREAM_DTMF
        STREAM_MUSIC,           // STREAM_TTS
        STREAM_MUSIC,           // STREAM_ACCESSIBILITY
        STREAM_MUSIC,           // STREAM_ASSISTANT
    };

    /** AOSP default ringer-mode-affected streams (Settings.System.MODE_RINGER_STREAMS_AFFECTED). */
    private static final int RINGER_MODE_AFFECTED_STREAMS = (1 << STREAM_RING) | (1 << STREAM_NOTIFICATION)
            | (1 << STREAM_SYSTEM) | (1 << STREAM_SYSTEM_ENFORCED);

    /** AOSP default mute-affected streams (Settings.System.MUTE_STREAMS_AFFECTED). */
    private static final int MUTE_AFFECTED_STREAMS = (1 << STREAM_MUSIC) | (1 << STREAM_RING)
            | (1 << STREAM_NOTIFICATION) | (1 << STREAM_SYSTEM) | (1 << STREAM_VOICE_CALL)
            | (1 << STREAM_BLUETOOTH_SCO) | (1 << STREAM_ASSISTANT);

    /**
     * AOSP default_volume_tables.xml, DEFAULT_DEVICE_CATEGORY_SPEAKER_VOLUME_CURVE (index in
     * percent of the range, attenuation in millibels): the curve of media-like streams on a
     * speaker.
     */
    private static final int[][] SPEAKER_VOLUME_CURVE = { {1, -5620}, {20, -4170}, {60, -1880}, {100, 0} };
    /** AOSP DEFAULT_SYSTEM_VOLUME_CURVE (system sounds, DTMF). */
    private static final int[][] SYSTEM_VOLUME_CURVE = { {1, -2400}, {33, -1800}, {66, -1200}, {100, -600} };
    /** AOSP Volume::VOLUME_MIN_DB. */
    private static final float VOLUME_MIN_DB = -758.0f;

    private static final Object sLock = new Object();
    /** index of each alias stream (indexed by stream type; only alias entries are used) */
    private static final int[] sStreamIndex = defaultIndexes();

    /**
     * AOSP's defaults, with this product's media default (AOSP's ro.config.media_vol_default
     * product setting): the loudness is set by the car downstream, so media starts at full scale,
     * which is what every track played before stream volumes existed here.
     */
    private static int[] defaultIndexes() {
        int[] idx = AudioSystem.DEFAULT_STREAM_VOLUME.clone();
        idx[STREAM_MUSIC] = MAX_STREAM_VOLUME[STREAM_MUSIC];
        return idx;
    }
    /** user mute (adjustStreamVolume ADJUST_MUTE), per alias stream */
    private static final boolean[] sStreamMuted = new boolean[AudioSystem.NUM_STREAM_TYPES];
    private static int sRingerMode = RINGER_MODE_NORMAL;
    private static int sMode = MODE_NORMAL;
    private static boolean sMicMuted;
    /** the device the app asked for communication (setSpeakerphoneOn / setCommunicationDevice) */
    private static AudioDeviceInfo sRequestedCommDevice;
    private static boolean sBluetoothScoRequested;
    private static int sAllowedCapturePolicy = AudioAttributes.ALLOW_CAPTURE_BY_ALL;
    private static int sEncodedSurroundMode = ENCODED_SURROUND_OUTPUT_AUTO;

    private static final CopyOnWriteArrayList<ModeListenerInfo> sModeListeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<CommDevListenerInfo> sCommDevListeners = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<DeviceCallbackInfo> sDeviceCallbacks = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<PlaybackCallbackInfo> sPlaybackCallbacks = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<RecordingCallbackInfo> sRecordingCallbacks = new CopyOnWriteArrayList<>();

    private static final class ModeListenerInfo {
        final Executor executor;
        final OnModeChangedListener listener;

        ModeListenerInfo(Executor e, OnModeChangedListener l) {
            executor = e;
            listener = l;
        }
    }

    private static final class CommDevListenerInfo {
        final Executor executor;
        final OnCommunicationDeviceChangedListener listener;

        CommDevListenerInfo(Executor e, OnCommunicationDeviceChangedListener l) {
            executor = e;
            listener = l;
        }
    }

    private static final class DeviceCallbackInfo {
        final AudioDeviceCallback callback;
        final Handler handler;

        DeviceCallbackInfo(AudioDeviceCallback c, Handler h) {
            callback = c;
            handler = h;
        }
    }

    private static final class PlaybackCallbackInfo {
        final AudioPlaybackCallback callback;
        final Handler handler;

        PlaybackCallbackInfo(AudioPlaybackCallback c, Handler h) {
            callback = c;
            handler = h;
        }
    }

    private static final class RecordingCallbackInfo {
        final AudioRecordingCallback callback;
        final Handler handler;

        RecordingCallbackInfo(AudioRecordingCallback c, Handler h) {
            callback = c;
            handler = h;
        }
    }

    private final Context mContext;

    public AudioManager() {
        this(null);
    }

    /** @hide */
    public AudioManager(Context context) {
        mContext = context;
    }

    private static Handler handlerOrMain(Handler h) {
        if (h != null)
            return h;
        Looper l = Looper.myLooper();
        return new Handler(l != null ? l : Looper.getMainLooper());
    }

    /** Sends a broadcast to the app's receivers from the main thread, as the system server would. */
    private static void broadcast(final Intent intent) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Application app = ActivityThread.currentApplication();
                if (app != null)
                    app.sendBroadcast(intent);
            }
        });
    }

    // ---- volume ---------------------------------------------------------------------------------

    private static void checkStream(int streamType) {
        if (streamType < 0 || streamType >= AudioSystem.NUM_STREAM_TYPES)
            throw new IllegalArgumentException("Bad stream type " + streamType);
    }

    /** AOSP AudioService.rescaleIndex between the ranges of two streams. */
    private static int rescaleIndex(int index, int srcStream, int dstStream) {
        int srcRange = MAX_STREAM_VOLUME[srcStream] - MIN_STREAM_VOLUME[srcStream];
        int dstRange = MAX_STREAM_VOLUME[dstStream] - MIN_STREAM_VOLUME[dstStream];
        if (srcRange == 0)
            return MIN_STREAM_VOLUME[dstStream];
        return MIN_STREAM_VOLUME[dstStream]
                + ((index - MIN_STREAM_VOLUME[srcStream]) * dstRange + srcRange / 2) / srcRange;
    }

    private static boolean mutedByRingerMode(int streamType) {
        return sRingerMode != RINGER_MODE_NORMAL && (RINGER_MODE_AFFECTED_STREAMS & (1 << streamType)) != 0;
    }

    private static boolean isMutedLocked(int streamType) {
        return sStreamMuted[STREAM_VOLUME_ALIAS[streamType]] || mutedByRingerMode(streamType);
    }

    /** The volume index of a stream in its own range (0 while muted, as AOSP reports it). */
    private static int streamIndexLocked(int streamType) {
        if (isMutedLocked(streamType))
            return 0;
        int alias = STREAM_VOLUME_ALIAS[streamType];
        return alias == streamType ? sStreamIndex[alias] : rescaleIndex(sStreamIndex[alias], alias, streamType);
    }

    /** Sets a stream (through its alias) and broadcasts VOLUME_CHANGED_ACTION like AudioService. */
    private static void setStreamIndex(int streamType, int index, int flags) {
        int alias = STREAM_VOLUME_ALIAS[streamType];
        int oldIndex, newIndex;
        synchronized (sLock) {
            oldIndex = streamIndexLocked(streamType);
            int aliasIndex = alias == streamType ? index : rescaleIndex(index, streamType, alias);
            aliasIndex = Math.max(MIN_STREAM_VOLUME[alias], Math.min(MAX_STREAM_VOLUME[alias], aliasIndex));
            sStreamIndex[alias] = aliasIndex;
            if (aliasIndex > 0 && sStreamMuted[alias])
                sStreamMuted[alias] = false; // AOSP: setting a non-zero volume unmutes the stream
            newIndex = streamIndexLocked(streamType);
        }
        if (oldIndex != newIndex)
            sendVolumeChanged(streamType, oldIndex, newIndex);
    }

    private static void sendVolumeChanged(int streamType, int oldIndex, int newIndex) {
        // AOSP sends one for the stream and for every stream aliased to the same one
        int alias = STREAM_VOLUME_ALIAS[streamType];
        for (int s = 0; s < AudioSystem.NUM_STREAM_TYPES; s++) {
            if (STREAM_VOLUME_ALIAS[s] != alias)
                continue;
            Intent intent = new Intent(VOLUME_CHANGED_ACTION);
            intent.putExtra(EXTRA_VOLUME_STREAM_TYPE, s);
            intent.putExtra(EXTRA_VOLUME_STREAM_VALUE, s == streamType ? newIndex : getStreamVolumeStatic(s));
            intent.putExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, s == streamType ? oldIndex : getStreamVolumeStatic(s));
            broadcast(intent);
        }
    }

    private static void sendMuteChanged(int streamType, boolean muted) {
        Intent intent = new Intent(STREAM_MUTE_CHANGED_ACTION);
        intent.putExtra(EXTRA_VOLUME_STREAM_TYPE, streamType);
        intent.putExtra(EXTRA_STREAM_VOLUME_MUTED, muted);
        broadcast(intent);
    }

    private static int getStreamVolumeStatic(int streamType) {
        synchronized (sLock) {
            return streamIndexLocked(streamType);
        }
    }

    public int getStreamVolume(int streamType) {
        checkStream(streamType);
        return getStreamVolumeStatic(streamType);
    }

    public int getStreamMaxVolume(int streamType) {
        checkStream(streamType);
        return MAX_STREAM_VOLUME[streamType];
    }

    public int getStreamMinVolume(int streamType) {
        checkStream(streamType);
        return MIN_STREAM_VOLUME[streamType];
    }

    /** @hide the minimum index that can be set, the same as getStreamMinVolume here (no privileged callers) */
    public int getStreamMinVolumeInt(int streamType) {
        return getStreamMinVolume(streamType);
    }

    public void setStreamVolume(int streamType, int index, int flags) {
        checkStream(streamType);
        // AOSP ignores volume changes of ringer-muted streams unless the ringer mode can change
        if (mutedByRingerMode(streamType) && index != 0 && (flags & FLAG_ALLOW_RINGER_MODES) == 0) {
            return;
        }
        setStreamIndex(streamType, index, flags);
    }

    public void adjustStreamVolume(int streamType, int direction, int flags) {
        checkStream(streamType);
        int alias = STREAM_VOLUME_ALIAS[streamType];
        switch (direction) {
            case ADJUST_MUTE:
            case ADJUST_UNMUTE:
            case ADJUST_TOGGLE_MUTE: {
                if ((MUTE_AFFECTED_STREAMS & (1 << alias)) == 0)
                    return; // AOSP: streams not affected by mute ignore mute requests
                boolean changed;
                boolean muted;
                synchronized (sLock) {
                    boolean old = sStreamMuted[alias];
                    muted = direction == ADJUST_MUTE ? true : direction == ADJUST_UNMUTE ? false : !old;
                    sStreamMuted[alias] = muted;
                    changed = old != muted;
                }
                if (changed)
                    sendMuteChanged(streamType, muted);
                return;
            }
            case ADJUST_SAME:
                return; // AOSP: only shows the volume UI, and this device has none
            case ADJUST_RAISE:
            case ADJUST_LOWER: {
                int index;
                synchronized (sLock) {
                    if (sStreamMuted[alias]) {
                        if (direction == ADJUST_LOWER)
                            return; // AOSP: lowering a muted stream does nothing
                        sStreamMuted[alias] = false; // raising unmutes
                    }
                    if (mutedByRingerMode(streamType) && (flags & FLAG_ALLOW_RINGER_MODES) == 0)
                        return;
                    int alIndex = sStreamIndex[alias] + direction;
                    alIndex = Math.max(MIN_STREAM_VOLUME[alias], Math.min(MAX_STREAM_VOLUME[alias], alIndex));
                    index = alias == streamType ? alIndex : rescaleIndex(alIndex, alias, streamType);
                }
                setStreamIndex(streamType, index, flags);
                return;
            }
            default:
                throw new IllegalArgumentException("Bad direction " + direction);
        }
    }

    /** AOSP getActiveStreamType on a non-voice platform: communication, else music. */
    private static int activeStreamType() {
        synchronized (sLock) {
            if (sMode == MODE_IN_COMMUNICATION || sMode == MODE_IN_CALL)
                return STREAM_VOICE_CALL;
        }
        return STREAM_MUSIC;
    }

    public void adjustVolume(int direction, int flags) {
        adjustStreamVolume(activeStreamType(), direction, flags);
    }

    public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags) {
        adjustStreamVolume(suggestedStreamType == USE_DEFAULT_STREAM_TYPE ? activeStreamType() : suggestedStreamType,
                direction, flags);
    }

    public boolean isStreamMute(int streamType) {
        checkStream(streamType);
        synchronized (sLock) {
            return isMutedLocked(streamType);
        }
    }

    @Deprecated
    public void setStreamMute(int streamType, boolean state) {
        adjustStreamVolume(streamType, state ? ADJUST_MUTE : ADJUST_UNMUTE, 0);
    }

    /** AOSP: deprecated and without effect since API 23 ("Do not use"), kept so here. */
    @Deprecated
    public void setStreamSolo(int streamType, boolean state) {
        Log.w(TAG, "setStreamSolo has been deprecated. Do not use.");
    }

    /** False: volume changes are applied (AOSP config_useFixedVolume is false). */
    public boolean isVolumeFixed() {
        return false;
    }

    /** AOSP AudioManager.getStreamVolumeDb: the attenuation of an index through the volume curve. */
    public float getStreamVolumeDb(int streamType, int index, int deviceType) {
        checkStream(streamType);
        if (index < MIN_STREAM_VOLUME[streamType] || index > MAX_STREAM_VOLUME[streamType])
            throw new IllegalArgumentException("Invalid stream volume index " + index);
        return volIndexToDb(streamType, index);
    }

    private static int[][] curveFor(int streamType) {
        switch (streamType) {
            case STREAM_SYSTEM:
            case STREAM_SYSTEM_ENFORCED:
            case STREAM_DTMF:
                return SYSTEM_VOLUME_CURVE;
            default:
                return SPEAKER_VOLUME_CURVE;
        }
    }

    /** AOSP Volume::volIndexToDb (frameworks/av VolumeCurve.cpp). */
    private static float volIndexToDb(int streamType, int indexInUi) {
        int[][] curve = curveFor(streamType);
        int volIndexMin = MIN_STREAM_VOLUME[streamType];
        int volIndexMax = MAX_STREAM_VOLUME[streamType];
        if (indexInUi < volIndexMin)
            return VOLUME_MIN_DB;
        int nbSteps = 1 + curve[curve.length - 1][0] - curve[0][0];
        int volIdx = volIndexMax == volIndexMin ? curve[curve.length - 1][0]
                : (nbSteps * (indexInUi - volIndexMin)) / (volIndexMax - volIndexMin);
        int pos = 0;
        while (pos < curve.length && curve[pos][0] < volIdx)
            pos++;
        if (pos >= curve.length)
            return curve[curve.length - 1][1] / 100.0f;
        if (pos == 0) {
            if (volIdx != curve[0][0])
                return VOLUME_MIN_DB;
            return curve[0][1] / 100.0f;
        }
        float dbA = curve[pos - 1][1] / 100.0f, dbB = curve[pos][1] / 100.0f;
        return dbA + ((float) (volIdx - curve[pos - 1][0])) * (dbB - dbA) / ((float) (curve[pos][0] - curve[pos - 1][0]));
    }

    /**
     * @hide the linear gain AudioTrack applies for its stream: the stream's volume through the
     * volume curve, 0 while muted (AudioFlinger's stream volume).
     */
    public static float tlStreamGain(int streamType) {
        if (streamType < 0 || streamType >= AudioSystem.NUM_STREAM_TYPES)
            streamType = STREAM_MUSIC;
        int index;
        synchronized (sLock) {
            index = streamIndexLocked(streamType);
            if (isMutedLocked(streamType))
                return 0f;
        }
        float db = volIndexToDb(streamType, index);
        if (db <= VOLUME_MIN_DB)
            return 0f;
        return (float) Math.pow(10.0, db / 20.0);
    }

    // ---- ringer mode ----------------------------------------------------------------------------

    public int getRingerMode() {
        synchronized (sLock) {
            return sRingerMode;
        }
    }

    public static boolean isValidRingerMode(int ringerMode) {
        return ringerMode >= RINGER_MODE_SILENT && ringerMode <= RINGER_MODE_MAX;
    }

    public void setRingerMode(int ringerMode) {
        if (!isValidRingerMode(ringerMode))
            throw new IllegalArgumentException("Bad ringer mode " + ringerMode);
        synchronized (sLock) {
            if (sRingerMode == ringerMode)
                return;
            sRingerMode = ringerMode;
        }
        Intent intent = new Intent(RINGER_MODE_CHANGED_ACTION);
        intent.putExtra(EXTRA_RINGER_MODE, ringerMode);
        broadcast(intent);
        Intent internal = new Intent(INTERNAL_RINGER_MODE_CHANGED_ACTION);
        internal.putExtra(EXTRA_RINGER_MODE, ringerMode);
        broadcast(internal);
    }

    public boolean isStreamAffectedByRingerMode(int streamType) {
        return (RINGER_MODE_AFFECTED_STREAMS & (1 << streamType)) != 0;
    }

    /** @hide */
    public boolean isStreamAffectedByMute(int streamType) {
        return (MUTE_AFFECTED_STREAMS & (1 << streamType)) != 0;
    }

    @Deprecated
    public boolean shouldVibrate(int vibrateType) {
        return getRingerMode() == RINGER_MODE_VIBRATE;
    }

    @Deprecated
    public int getVibrateSetting(int vibrateType) {
        return VIBRATE_SETTING_ONLY_SILENT;
    }

    // ---- audio mode -----------------------------------------------------------------------------

    public int getMode() {
        synchronized (sLock) {
            return sMode;
        }
    }

    public void setMode(int mode) {
        if (mode < MODE_CURRENT || mode > MODE_COMMUNICATION_REDIRECT)
            return; // AOSP AudioService: invalid modes are ignored
        if (mode == MODE_CURRENT)
            return;
        if (mode == MODE_IN_CALL || mode == MODE_CALL_REDIRECT || mode == MODE_COMMUNICATION_REDIRECT) {
            // AOSP: these modes need MODIFY_PHONE_STATE, which no regular app holds
            Log.w(TAG, "MODIFY_PHONE_STATE Permission Denial: setMode(" + mode + ") from pid="
                    + android.os.Process.myPid() + ", uid=" + android.os.Process.myUid());
            return;
        }
        final int newMode = mode;
        synchronized (sLock) {
            if (sMode == mode)
                return;
            sMode = mode;
        }
        for (final ModeListenerInfo li : sModeListeners) {
            li.executor.execute(new Runnable() {
                @Override
                public void run() {
                    li.listener.onModeChanged(newMode);
                }
            });
        }
    }

    public void addOnModeChangedListener(Executor executor, OnModeChangedListener listener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        for (ModeListenerInfo li : sModeListeners) {
            if (li.listener == listener)
                throw new IllegalArgumentException("attempt to call addOnModeChangedListener() on a previously registered listener");
        }
        sModeListeners.add(new ModeListenerInfo(executor, listener));
    }

    public void removeOnModeChangedListener(OnModeChangedListener listener) {
        Objects.requireNonNull(listener);
        for (ModeListenerInfo li : sModeListeners) {
            if (li.listener == listener) {
                sModeListeners.remove(li);
                return;
            }
        }
        throw new IllegalArgumentException("attempt to call removeOnModeChangedListener() on an unregistered listener");
    }

    public boolean isCallScreeningModeSupported() {
        return false; // AOSP: needs a telephony stack with call screening; this device has none
    }

    // ---- routing --------------------------------------------------------------------------------

    /** The host's single output: every track plays to it. */
    private static AudioDeviceInfo sSpeaker;
    /** A capture device, only when ALSA lists one (the Pi has none). */
    private static AudioDeviceInfo sMic;
    private static boolean sDevicesProbed;

    private static void probeDevices() {
        synchronized (sLock) {
            if (sDevicesProbed)
                return;
            sDevicesProbed = true;
            // ALSA plug devices (PipeWire's on the Pi) convert rate and format: arbitrary rates
            // (an empty array, as in AOSP); the PCM encodings AudioTrack converts from
            int[] outFormats = { AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_8BIT,
                    AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_32BIT };
            sSpeaker = new AudioDeviceInfo(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, true, "", "",
                    new int[0], new int[] { AudioFormat.CHANNEL_OUT_MONO, AudioFormat.CHANNEL_OUT_STEREO },
                    new int[0], outFormats);
            if (alsaHasCapture()) {
                sMic = new AudioDeviceInfo(2, AudioDeviceInfo.TYPE_BUILTIN_MIC, false, "", "bottom",
                        new int[0], new int[] { AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO },
                        new int[0], new int[] { AudioFormat.ENCODING_PCM_16BIT });
            }
        }
    }

    private static boolean alsaHasCapture() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/asound/pcm"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("capture"))
                    return true;
            }
        } catch (IOException e) {
        }
        return false;
    }

    /** @hide The output device every track is routed to. */
    public static AudioDeviceInfo tlDefaultOutputDevice() {
        probeDevices();
        return sSpeaker;
    }

    /** @hide */
    public static AudioDeviceInfo tlDefaultInputDevice() {
        probeDevices();
        return sMic;
    }

    public AudioDeviceInfo[] getDevices(int flags) {
        if ((flags & ~GET_DEVICES_ALL) != 0)
            throw new IllegalArgumentException("Bad flags " + flags);
        probeDevices();
        List<AudioDeviceInfo> l = new ArrayList<>();
        if ((flags & GET_DEVICES_OUTPUTS) != 0)
            l.add(sSpeaker);
        if ((flags & GET_DEVICES_INPUTS) != 0 && sMic != null)
            l.add(sMic);
        return l.toArray(new AudioDeviceInfo[0]);
    }

    public List<AudioDeviceInfo> getAudioDevicesForAttributes(AudioAttributes attributes) {
        Objects.requireNonNull(attributes);
        probeDevices();
        return Collections.singletonList(sSpeaker);
    }

    public void registerAudioDeviceCallback(final AudioDeviceCallback callback, Handler handler) {
        if (callback == null)
            return;
        for (DeviceCallbackInfo i : sDeviceCallbacks) {
            if (i.callback == callback)
                return;
        }
        final Handler h = handlerOrMain(handler);
        sDeviceCallbacks.add(new DeviceCallbackInfo(callback, h));
        // AOSP: a new callback is told about the devices present
        final AudioDeviceInfo[] devices = getDevices(GET_DEVICES_ALL);
        h.post(new Runnable() {
            @Override
            public void run() {
                callback.onAudioDevicesAdded(devices);
            }
        });
    }

    public void unregisterAudioDeviceCallback(AudioDeviceCallback callback) {
        for (DeviceCallbackInfo i : sDeviceCallbacks) {
            if (i.callback == callback)
                sDeviceCallbacks.remove(i);
        }
    }

    /** No wired headset can be plugged into this device's output (the car). */
    @Deprecated
    public boolean isWiredHeadsetOn() {
        return false;
    }

    /** No Bluetooth on this device. */
    @Deprecated
    public boolean isBluetoothA2dpOn() {
        return false;
    }

    @Deprecated
    public void setBluetoothA2dpOn(boolean on) {
        // AOSP: deprecated, "no-op since API 26"
    }

    @Deprecated
    public void setWiredHeadsetOn(boolean on) {
        // AOSP: deprecated, has no effect
    }

    @Deprecated
    public void setRouting(int mode, int routes, int mask) {
        // AOSP: deprecated, "Do not set audio routing directly", has no effect
    }

    @Deprecated
    public int getRouting(int mode) {
        return -1; // AOSP returns -1 (deprecated)
    }

    /** AOSP 12+: on when the speaker is the requested communication route. */
    public boolean isSpeakerphoneOn() {
        synchronized (sLock) {
            return sRequestedCommDevice != null && sRequestedCommDevice.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        }
    }

    @Deprecated
    public void setSpeakerphoneOn(boolean on) {
        boolean changed;
        synchronized (sLock) {
            boolean was = isSpeakerphoneOn();
            if (on) {
                probeDevices();
                sRequestedCommDevice = sSpeaker;
            } else if (was) {
                sRequestedCommDevice = null;
            }
            changed = was != on;
        }
        if (changed)
            broadcast(new Intent(ACTION_SPEAKERPHONE_STATE_CHANGED));
    }

    public boolean isBluetoothScoAvailableOffCall() {
        return false; // no Bluetooth
    }

    /**
     * No Bluetooth headset can connect (no Bluetooth on this device): AOSP's SCO request stays
     * disconnected, the state ACTION_SCO_AUDIO_STATE_UPDATED keeps reporting.
     */
    @Deprecated
    public void startBluetoothSco() {
        synchronized (sLock) {
            sBluetoothScoRequested = true;
        }
    }

    @Deprecated
    public void startBluetoothScoVirtualCall() {
        startBluetoothSco();
    }

    @Deprecated
    public void stopBluetoothSco() {
        synchronized (sLock) {
            sBluetoothScoRequested = false;
        }
    }

    /** AOSP 12+: SCO ON is only accepted while SCO audio is connected, which never happens here. */
    @Deprecated
    public void setBluetoothScoOn(boolean on) {
    }

    @Deprecated
    public boolean isBluetoothScoOn() {
        return false;
    }

    public void setMicrophoneMute(boolean on) {
        boolean changed;
        synchronized (sLock) {
            changed = sMicMuted != on;
            sMicMuted = on;
        }
        if (changed)
            broadcast(new Intent(ACTION_MICROPHONE_MUTE_CHANGED));
    }

    public boolean isMicrophoneMute() {
        synchronized (sLock) {
            return sMicMuted;
        }
    }

    public List<AudioDeviceInfo> getAvailableCommunicationDevices() {
        probeDevices();
        return Collections.singletonList(sSpeaker);
    }

    /** AOSP: the device communication audio is routed to; the speaker is the only one. */
    public AudioDeviceInfo getCommunicationDevice() {
        probeDevices();
        return sSpeaker;
    }

    public boolean setCommunicationDevice(AudioDeviceInfo device) {
        Objects.requireNonNull(device);
        if (device.getId() == 0)
            throw new IllegalArgumentException("In valid device: " + device);
        probeDevices();
        if (!device.isSink() || !getAvailableCommunicationDevices().contains(device))
            throw new IllegalArgumentException("invalid portID " + device.getId());
        synchronized (sLock) {
            sRequestedCommDevice = device;
        }
        return true; // the route was already that device: no change to report
    }

    public void clearCommunicationDevice() {
        synchronized (sLock) {
            sRequestedCommDevice = null;
        }
    }

    public void addOnCommunicationDeviceChangedListener(Executor executor, OnCommunicationDeviceChangedListener listener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        for (CommDevListenerInfo li : sCommDevListeners) {
            if (li.listener == listener)
                throw new IllegalArgumentException("attempt to call addOnCommunicationDeviceChangedListener() on a previously registered listener");
        }
        sCommDevListeners.add(new CommDevListenerInfo(executor, listener));
    }

    public void removeOnCommunicationDeviceChangedListener(OnCommunicationDeviceChangedListener listener) {
        Objects.requireNonNull(listener);
        for (CommDevListenerInfo li : sCommDevListeners) {
            if (li.listener == listener) {
                sCommDevListeners.remove(li);
                return;
            }
        }
        throw new IllegalArgumentException("attempt to call removeOnCommunicationDeviceChangedListener() on an unregistered listener");
    }

    // ---- audio focus (AOSP MediaFocusControl, one client process) -------------------------------

    private static final class FocusRequester {
        final AudioFocusRequest request;
        final OnAudioFocusChangeListener listener;
        final Handler handler;
        int lossReceived = AUDIOFOCUS_NONE;

        FocusRequester(AudioFocusRequest request) {
            this.request = request;
            this.listener = request.getOnAudioFocusChangeListener();
            this.handler = request.getOnAudioFocusChangeListenerHandler();
        }

        int gainRequest() {
            return request.getFocusGain();
        }

        void dispatch(final int focusChange) {
            if (listener == null)
                return;
            handlerOrMain(handler).post(new Runnable() {
                @Override
                public void run() {
                    listener.onAudioFocusChange(focusChange);
                }
            });
        }

        /** AOSP FocusRequester.focusLossForGainRequest */
        int focusLossForGainRequest(int gainRequest) {
            switch (gainRequest) {
                case AUDIOFOCUS_GAIN:
                    return AUDIOFOCUS_LOSS;
                case AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE:
                case AUDIOFOCUS_GAIN_TRANSIENT:
                    return lossReceived == AUDIOFOCUS_LOSS ? AUDIOFOCUS_LOSS : AUDIOFOCUS_LOSS_TRANSIENT;
                case AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
                    switch (lossReceived) {
                        case AUDIOFOCUS_NONE:
                        case AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            return AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK;
                        case AUDIOFOCUS_LOSS_TRANSIENT:
                            return AUDIOFOCUS_LOSS_TRANSIENT;
                        default:
                            return AUDIOFOCUS_LOSS;
                    }
                default:
                    return AUDIOFOCUS_NONE;
            }
        }

        /**
         * AOSP handleFocusLossFromGain: records and dispatches the loss; true when it is permanent
         * (the requester then leaves the stack). Ducking is never done by the system here: the
         * winner and the loser are the same app (one uid), and AOSP does not duck a uid's players
         * for its own focus requests, it dispatches the loss.
         */
        boolean handleFocusLossFromGain(int gainRequest) {
            int loss = focusLossForGainRequest(gainRequest);
            if (loss == AUDIOFOCUS_NONE || loss == lossReceived)
                return loss == AUDIOFOCUS_LOSS;
            lossReceived = loss;
            dispatch(loss);
            return loss == AUDIOFOCUS_LOSS;
        }

        void handleFocusGain() {
            if (lossReceived != AUDIOFOCUS_NONE) {
                lossReceived = AUDIOFOCUS_NONE;
                dispatch(AUDIOFOCUS_GAIN);
            }
        }
    }

    /** The focus stack, top = last. */
    private static final LinkedList<FocusRequester> sFocusStack = new LinkedList<>();
    /** Legacy listener API: the AudioFocusRequest built for each listener. */
    private static final Map<OnAudioFocusChangeListener, AudioFocusRequest> sLegacyFocusRequests = new HashMap<>();

    private static boolean sameClient(FocusRequester fr, AudioFocusRequest request) {
        OnAudioFocusChangeListener l = request.getOnAudioFocusChangeListener();
        return fr.request == request || (l != null && fr.listener == l);
    }

    public int requestAudioFocus(AudioFocusRequest focusRequest) {
        if (focusRequest == null)
            throw new NullPointerException("Illegal null AudioFocusRequest");
        synchronized (sFocusStack) {
            FocusRequester top = sFocusStack.peekLast();
            if (top != null && sameClient(top, focusRequest) && top.gainRequest() == focusRequest.getFocusGain()
                    && top.lossReceived == AUDIOFOCUS_NONE) {
                return AUDIOFOCUS_REQUEST_GRANTED; // AOSP: the top already holds this focus
            }
            // the client's previous entry goes, then everyone below learns about the new gain
            for (Iterator<FocusRequester> it = sFocusStack.iterator(); it.hasNext();) {
                if (sameClient(it.next(), focusRequest))
                    it.remove();
            }
            for (Iterator<FocusRequester> it = sFocusStack.iterator(); it.hasNext();) {
                FocusRequester loser = it.next();
                if (loser.handleFocusLossFromGain(focusRequest.getFocusGain()))
                    it.remove();
            }
            sFocusStack.addLast(new FocusRequester(focusRequest));
        }
        return AUDIOFOCUS_REQUEST_GRANTED;
    }

    public int abandonAudioFocusRequest(AudioFocusRequest focusRequest) {
        if (focusRequest == null)
            throw new IllegalArgumentException("Illegal null AudioFocusRequest");
        abandon(focusRequest);
        return AUDIOFOCUS_REQUEST_GRANTED;
    }

    private static void abandon(AudioFocusRequest request) {
        synchronized (sFocusStack) {
            FocusRequester top = sFocusStack.peekLast();
            boolean wasTop = top != null && sameClient(top, request);
            for (Iterator<FocusRequester> it = sFocusStack.iterator(); it.hasNext();) {
                if (sameClient(it.next(), request))
                    it.remove();
            }
            if (wasTop) {
                FocusRequester next = sFocusStack.peekLast();
                if (next != null)
                    next.handleFocusGain();
            }
        }
    }

    @Deprecated
    public int requestAudioFocus(OnAudioFocusChangeListener l, int streamType, int durationHint) {
        if (durationHint < AUDIOFOCUS_GAIN || durationHint > AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            throw new IllegalArgumentException("Invalid duration hint");
        AudioFocusRequest.Builder b = new AudioFocusRequest.Builder(durationHint)
                .setAudioAttributes(new AudioAttributes.Builder().setInternalLegacyStreamType(streamType).build());
        if (l != null)
            b.setOnAudioFocusChangeListener(l);
        AudioFocusRequest afr = b.build();
        if (l != null) {
            synchronized (sLegacyFocusRequests) {
                sLegacyFocusRequests.put(l, afr);
            }
        }
        return requestAudioFocus(afr);
    }

    @Deprecated
    public int abandonAudioFocus(OnAudioFocusChangeListener l) {
        AudioFocusRequest afr;
        synchronized (sLegacyFocusRequests) {
            afr = l == null ? null : sLegacyFocusRequests.remove(l);
        }
        if (afr != null) {
            abandon(afr);
        } else if (l != null) {
            // a listener registered through an AudioFocusRequest
            synchronized (sFocusStack) {
                for (FocusRequester fr : new ArrayList<>(sFocusStack)) {
                    if (fr.listener == l) {
                        abandon(fr.request);
                        break;
                    }
                }
            }
        }
        return AUDIOFOCUS_REQUEST_GRANTED;
    }

    // ---- activity and playback configurations ---------------------------------------------------

    /** A player in the registry (AOSP PlaybackActivityMonitor's player record). */
    private static final class Player {
        final AudioTrack track;
        final int piid;
        int state = AudioPlaybackConfiguration.PLAYER_STATE_IDLE;

        Player(AudioTrack track, int piid) {
            this.track = track;
            this.piid = piid;
        }

        AudioPlaybackConfiguration config() {
            AudioDeviceInfo out = tlDefaultOutputDevice();
            return new AudioPlaybackConfiguration(piid, AudioPlaybackConfiguration.PLAYER_TYPE_JAM_AUDIOTRACK,
                    android.os.Process.myUid(), android.os.Process.myPid(), track.getAudioSessionId(),
                    track.getAudioAttributes(), state, out != null ? out.getId() : 0);
        }
    }

    private static final List<Player> sPlayers = new ArrayList<>();
    private static int sNextPiid = 1;

    /**
     * @hide called by AudioTrack with an AudioPlaybackConfiguration.PLAYER_STATE_*: IDLE when
     * created, STARTED / PAUSED / STOPPED as it plays, RELEASED when released. Playback callbacks
     * get the new configurations, as from AOSP's PlaybackActivityMonitor.
     */
    public static void tlPlayerEvent(AudioTrack track, int state) {
        synchronized (sPlayers) {
            Player p = null;
            for (Player q : sPlayers) {
                if (q.track == track) {
                    p = q;
                    break;
                }
            }
            if (p == null) {
                if (state == AudioPlaybackConfiguration.PLAYER_STATE_RELEASED)
                    return;
                p = new Player(track, sNextPiid++);
                sPlayers.add(p);
            } else if (p.state == state) {
                return;
            }
            p.state = state;
            if (state == AudioPlaybackConfiguration.PLAYER_STATE_RELEASED)
                sPlayers.remove(p);
        }
        if (sPlaybackCallbacks.isEmpty())
            return;
        final List<AudioPlaybackConfiguration> configs = playbackConfigurations();
        for (final PlaybackCallbackInfo i : sPlaybackCallbacks) {
            i.handler.post(new Runnable() {
                @Override
                public void run() {
                    i.callback.onPlaybackConfigChanged(configs);
                }
            });
        }
    }

    private static List<AudioPlaybackConfiguration> playbackConfigurations() {
        List<AudioPlaybackConfiguration> l = new ArrayList<>();
        synchronized (sPlayers) {
            for (Player p : sPlayers)
                l.add(p.config());
        }
        return l;
    }

    /** @hide AOSP AudioSystem.isStreamActive (inPastMs ignored: the current state) */
    public static boolean isStreamActive(int streamType, int inPastMs) {
        synchronized (sPlayers) {
            for (Player p : sPlayers) {
                if (p.state == AudioPlaybackConfiguration.PLAYER_STATE_STARTED && p.track.getStreamType() == streamType)
                    return true;
            }
        }
        return false;
    }

    public boolean isMusicActive() {
        return isStreamActive(STREAM_MUSIC, 0);
    }

    /** @hide */
    public boolean isMusicActiveRemotely() {
        return false; // no remote submix
    }

    public void registerAudioPlaybackCallback(AudioPlaybackCallback cb, Handler handler) {
        if (cb == null)
            throw new IllegalArgumentException("Illegal null AudioPlaybackCallback argument");
        sPlaybackCallbacks.add(new PlaybackCallbackInfo(cb, handlerOrMain(handler)));
    }

    public void unregisterAudioPlaybackCallback(AudioPlaybackCallback cb) {
        if (cb == null)
            throw new IllegalArgumentException("Illegal null AudioPlaybackCallback argument");
        for (PlaybackCallbackInfo i : sPlaybackCallbacks) {
            if (i.callback == cb)
                sPlaybackCallbacks.remove(i);
        }
    }

    /** The registered players (this process's AudioTracks, from creation to release). */
    public List<AudioPlaybackConfiguration> getActivePlaybackConfigurations() {
        return playbackConfigurations();
    }

    public void registerAudioRecordingCallback(AudioRecordingCallback cb, Handler handler) {
        if (cb == null)
            throw new IllegalArgumentException("Illegal null AudioRecordingCallback argument");
        sRecordingCallbacks.add(new RecordingCallbackInfo(cb, handlerOrMain(handler)));
    }

    public void unregisterAudioRecordingCallback(AudioRecordingCallback cb) {
        if (cb == null)
            throw new IllegalArgumentException("Illegal null AudioRecordingCallback argument");
        for (RecordingCallbackInfo i : sRecordingCallbacks) {
            if (i.callback == cb)
                sRecordingCallbacks.remove(i);
        }
    }

    /** No capture is possible on this device (no input device), so nothing records. */
    public List<AudioRecordingConfiguration> getActiveRecordingConfigurations() {
        return new ArrayList<>();
    }

    // ---- sessions, properties, parameters, capabilities -----------------------------------------

    public int generateAudioSessionId() {
        return AudioSystem.newAudioSessionId();
    }

    /**
     * AOSP: the primary output's native rate and period. AudioTrack opens ALSA's default device,
     * which (PipeWire, and dmix) runs at 48 kHz with 1024-frame periods by default.
     */
    public String getProperty(String key) {
        if (PROPERTY_OUTPUT_SAMPLE_RATE.equals(key)) {
            return Integer.toString(AudioTrack.getNativeOutputSampleRate(STREAM_MUSIC));
        } else if (PROPERTY_OUTPUT_FRAMES_PER_BUFFER.equals(key)) {
            return "1024";
        } else if (PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND.equals(key)
                || PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND.equals(key)
                || PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED.equals(key)) {
            return "false";
        }
        return null; // AOSP: unknown properties are null
    }

    /**
     * AOSP passes these to the audio HAL. There is no HAL here; an empty reply is what a HAL
     * answers for keys it does not know.
     */
    public String getParameters(String keys) {
        return "";
    }

    /** No audio HAL to configure (see getParameters). */
    public void setParameters(String keyValuePairs) {
        Log.i(TAG, "setParameters(" + keyValuePairs + "): no audio HAL to receive them");
    }

    public static boolean isOffloadedPlaybackSupported(AudioFormat format, AudioAttributes attributes) {
        if (format == null)
            throw new NullPointerException("Illegal null AudioFormat");
        if (attributes == null)
            throw new NullPointerException("Illegal null AudioAttributes");
        return false; // no compressed offload output (the host plays PCM through ALSA)
    }

    public static int getPlaybackOffloadSupport(AudioFormat format, AudioAttributes attributes) {
        isOffloadedPlaybackSupported(format, attributes);
        return PLAYBACK_OFFLOAD_NOT_SUPPORTED;
    }

    /** No direct (bit-exact / compressed passthrough) output: everything is mixed PCM. */
    public static int getDirectPlaybackSupport(AudioFormat format, AudioAttributes attributes) {
        Objects.requireNonNull(format);
        Objects.requireNonNull(attributes);
        return DIRECT_PLAYBACK_NOT_SUPPORTED;
    }

    /** No direct outputs, so no direct profiles (see getDirectPlaybackSupport). */
    public List<AudioProfile> getDirectProfilesForAttributes(AudioAttributes attributes) {
        Objects.requireNonNull(attributes);
        return new ArrayList<>();
    }

    public Spatializer getSpatializer() {
        return new Spatializer();
    }

    public static boolean isHapticPlaybackSupported() {
        return false; // no haptic channels on the output
    }

    public int getEncodedSurroundMode() {
        synchronized (sLock) {
            return sEncodedSurroundMode;
        }
    }

    public boolean setEncodedSurroundMode(int mode) {
        if (mode < ENCODED_SURROUND_OUTPUT_AUTO || mode > ENCODED_SURROUND_OUTPUT_MANUAL)
            return false;
        synchronized (sLock) {
            sEncodedSurroundMode = mode;
        }
        return true;
    }

    /** The output is PCM only: no surround format is enabled. */
    public boolean isSurroundFormatEnabled(int audioFormat) {
        return false;
    }

    public Map<Integer, Boolean> getSurroundFormats() {
        return new HashMap<>();
    }

    public List<Integer> getReportedSurroundFormats() {
        return new ArrayList<>();
    }

    public void setAllowedCapturePolicy(int capturePolicy) {
        if (capturePolicy < AudioAttributes.ALLOW_CAPTURE_BY_ALL || capturePolicy > AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            throw new IllegalArgumentException("Invalid capture policy " + capturePolicy);
        synchronized (sLock) {
            sAllowedCapturePolicy = capturePolicy;
        }
    }

    public int getAllowedCapturePolicy() {
        synchronized (sLock) {
            return sAllowedCapturePolicy;
        }
    }

    public boolean isRampingRingerEnabled() {
        return false; // a telephony ringer feature; no telephony
    }

    // ---- sound effects and keys -----------------------------------------------------------------

    /**
     * AOSP plays the UI sound of the effect from /system/media/audio/ui when sound effects are
     * enabled. This runtime ships no UI sound files, so there is nothing to play, as on AOSP when
     * SoundEffectsHelper fails to load them.
     */
    public void playSoundEffect(int effectType) {
        if (effectType < 0 || effectType >= NUM_SOUND_EFFECTS)
            return;
    }

    /** See {@link #playSoundEffect(int)}. */
    public void playSoundEffect(int effectType, float volume) {
        if (effectType < 0 || effectType >= NUM_SOUND_EFFECTS)
            return;
    }

    /** See {@link #playSoundEffect(int)}: there are no effect files to load. */
    public void loadSoundEffects() {
    }

    /** See {@link #playSoundEffect(int)}: nothing was loaded. */
    public void unloadSoundEffects() {
    }

    /**
     * AOSP routes the media key to the active media session (MediaSessionManager). The sessions of
     * this process are dispatched by MediaSessionManager.
     */
    public void dispatchMediaKeyEvent(KeyEvent keyEvent) {
        if (keyEvent == null)
            throw new NullPointerException("keyEvent");
        android.media.session.MediaSessionManager.tlDispatchMediaKeyEvent(keyEvent);
    }
}
