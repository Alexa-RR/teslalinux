/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.speech.tts;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Synthesizes speech from text for immediate playback or to create a sound file (AOSP API; the
 * constants are AOSP's).
 *
 * teslalinux: AOSP binds a TTS engine service (an app implementing TextToSpeechService). No such
 * engine is installed on this device, so this is AOSP's behaviour without one: the OnInitListener
 * gets {@link #ERROR} once (posted to the main thread), every request that would go to the engine
 * fails as AOSP's runAction does when not bound ("not bound to TTS engine": ERROR, or
 * LANG_NOT_SUPPORTED for language calls), the engine lists are empty, and the local parameters
 * (rate, pitch, audio attributes, listeners) are kept as AOSP keeps them.
 */
public class TextToSpeech {
    private static final String TAG = "TextToSpeech";

    public static final int SUCCESS = 0;
    public static final int ERROR = -1;
    public static final int STOPPED = -2;
    public static final int ERROR_SYNTHESIS = -3;
    public static final int ERROR_SERVICE = -4;
    public static final int ERROR_OUTPUT = -5;
    public static final int ERROR_NETWORK = -6;
    public static final int ERROR_NETWORK_TIMEOUT = -7;
    public static final int ERROR_INVALID_REQUEST = -8;
    public static final int ERROR_NOT_INSTALLED_YET = -9;
    public static final int QUEUE_FLUSH = 0;
    public static final int QUEUE_ADD = 1;
    public static final int LANG_COUNTRY_VAR_AVAILABLE = 2;
    public static final int LANG_COUNTRY_AVAILABLE = 1;
    public static final int LANG_AVAILABLE = 0;
    public static final int LANG_MISSING_DATA = -1;
    public static final int LANG_NOT_SUPPORTED = -2;
    public static final String ACTION_TTS_QUEUE_PROCESSING_COMPLETED =
            "android.speech.tts.TTS_QUEUE_PROCESSING_COMPLETED";

    /** Constants and parameter names for controlling text-to-speech (AOSP). */
    public class Engine {
        public static final int DEFAULT_RATE = 100;
        public static final int DEFAULT_PITCH = 100;
        public static final float DEFAULT_VOLUME = 1.0f;
        public static final float DEFAULT_PAN = 0.0f;
        public static final int USE_DEFAULTS = 0;
        public static final String DEFAULT_ENGINE = "com.svox.pico";
        public static final int DEFAULT_STREAM = AudioManager.STREAM_MUSIC;
        public static final int CHECK_VOICE_DATA_PASS = 1;
        public static final int CHECK_VOICE_DATA_FAIL = 0;
        public static final int CHECK_VOICE_DATA_BAD_DATA = -1;
        public static final int CHECK_VOICE_DATA_MISSING_DATA = -2;
        public static final int CHECK_VOICE_DATA_MISSING_VOLUME = -3;
        public static final String INTENT_ACTION_TTS_SERVICE =
                "android.intent.action.TTS_SERVICE";
        public static final String SERVICE_META_DATA = "android.speech.tts";
        public static final String ACTION_INSTALL_TTS_DATA =
                "android.speech.tts.engine.INSTALL_TTS_DATA";
        public static final String ACTION_TTS_DATA_INSTALLED =
                "android.speech.tts.engine.TTS_DATA_INSTALLED";
        public static final String ACTION_CHECK_TTS_DATA =
                "android.speech.tts.engine.CHECK_TTS_DATA";
        public static final String ACTION_GET_SAMPLE_TEXT =
                "android.speech.tts.engine.GET_SAMPLE_TEXT";
        public static final String EXTRA_SAMPLE_TEXT = "sampleText";
        public static final String EXTRA_AVAILABLE_VOICES = "availableVoices";
        public static final String EXTRA_UNAVAILABLE_VOICES = "unavailableVoices";
        public static final String EXTRA_VOICE_DATA_ROOT_DIRECTORY = "dataRoot";
        public static final String EXTRA_VOICE_DATA_FILES = "dataFiles";
        public static final String EXTRA_VOICE_DATA_FILES_INFO = "dataFilesInfo";
        public static final String EXTRA_CHECK_VOICE_DATA_FOR = "checkVoiceDataFor";
        public static final String EXTRA_TTS_DATA_INSTALLED = "dataInstalled";
        public static final String KEY_PARAM_RATE = "rate";
        public static final String KEY_PARAM_VOICE_NAME = "voiceName";
        public static final String KEY_PARAM_LANGUAGE = "language";
        public static final String KEY_PARAM_COUNTRY = "country";
        public static final String KEY_PARAM_VARIANT = "variant";
        public static final String KEY_PARAM_ENGINE = "engine";
        public static final String KEY_PARAM_PITCH = "pitch";
        public static final String KEY_PARAM_STREAM = "streamType";
        public static final String KEY_PARAM_AUDIO_ATTRIBUTES = "audioAttributes";
        public static final String KEY_PARAM_UTTERANCE_ID = "utteranceId";
        public static final String KEY_PARAM_VOLUME = "volume";
        public static final String KEY_PARAM_PAN = "pan";
        public static final String KEY_FEATURE_NETWORK_SYNTHESIS = "networkTts";
        public static final String KEY_FEATURE_EMBEDDED_SYNTHESIS = "embeddedTts";
        public static final String KEY_PARAM_SESSION_ID = "sessionId";
        public static final String KEY_FEATURE_NOT_INSTALLED = "notInstalled";
        public static final String KEY_FEATURE_NETWORK_TIMEOUT_MS = "networkTimeoutMs";
        public static final String KEY_FEATURE_NETWORK_RETRIES_COUNT = "networkRetriesCount";
    }

    public interface OnInitListener {
        void onInit(int status);
    }

    @Deprecated
    public interface OnUtteranceCompletedListener {
        void onUtteranceCompleted(String utteranceId);
    }

    /** Information about an installed text-to-speech engine. */
    public static class EngineInfo {
        public String name;
        public String label;
        public int icon;
        public boolean system;
        public int priority;

        @Override
        public String toString() {
            return "EngineInfo{name=" + name + "}";
        }
    }

    private final Context mContext;
    private final Bundle mParams = new Bundle();
    private UtteranceProgressListener mUtteranceProgressListener;
    private AudioAttributes mAudioAttributes;
    private final String mRequestedEngine;

    public TextToSpeech(Context context, OnInitListener listener) {
        this(context, listener, null);
    }

    public TextToSpeech(Context context, final OnInitListener listener, String engine) {
        mContext = context;
        mRequestedEngine = engine;
        mParams.putFloat(Engine.KEY_PARAM_VOLUME, Engine.DEFAULT_VOLUME);
        mParams.putFloat(Engine.KEY_PARAM_PAN, Engine.DEFAULT_PAN);
        // AOSP initTts: no engine to connect to -> dispatchOnInit(ERROR)
        Log.w(TAG, "No TTS engine installed: initialisation failed");
        if (listener != null) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    listener.onInit(ERROR);
                }
            });
        }
    }

    private static int notBound(String method, int errorResult) {
        Log.w(TAG, method + " failed: not bound to TTS engine");
        return errorResult;
    }

    public void shutdown() {
        // AOSP: unbinds the engine; there is none to release
        mUtteranceProgressListener = null;
    }

    @Deprecated
    public int addSpeech(String text, String packagename, int resourceId) {
        return notBound("addSpeech", ERROR);
    }

    public int addSpeech(CharSequence text, String packagename, int resourceId) {
        return notBound("addSpeech", ERROR);
    }

    @Deprecated
    public int addSpeech(String text, String filename) {
        return notBound("addSpeech", ERROR);
    }

    public int addSpeech(CharSequence text, File file) {
        return notBound("addSpeech", ERROR);
    }

    public int addEarcon(String earcon, String packagename, int resourceId) {
        return notBound("addEarcon", ERROR);
    }

    @Deprecated
    public int addEarcon(String earcon, String filename) {
        return notBound("addEarcon", ERROR);
    }

    public int addEarcon(String earcon, File file) {
        return notBound("addEarcon", ERROR);
    }

    public int speak(final CharSequence text, final int queueMode, final Bundle params, final String utteranceId) {
        return notBound("speak", ERROR);
    }

    @Deprecated
    public int speak(final String text, final int queueMode, final HashMap<String, String> params) {
        return notBound("speak", ERROR);
    }

    public int playEarcon(final String earcon, final int queueMode, final Bundle params, final String utteranceId) {
        return notBound("playEarcon", ERROR);
    }

    @Deprecated
    public int playEarcon(final String earcon, final int queueMode, final HashMap<String, String> params) {
        return notBound("playEarcon", ERROR);
    }

    public int playSilentUtterance(final long durationInMs, final int queueMode, final String utteranceId) {
        return notBound("playSilentUtterance", ERROR);
    }

    @Deprecated
    public int playSilence(final long durationInMs, final int queueMode, final HashMap<String, String> params) {
        return notBound("playSilence", ERROR);
    }

    @Deprecated
    public Set<String> getFeatures(final Locale locale) {
        notBound("getFeatures", ERROR);
        return null;
    }

    public boolean isSpeaking() {
        notBound("isSpeaking", ERROR);
        return false;
    }

    public int stop() {
        return notBound("stop", ERROR);
    }

    /** AOSP keeps the rate locally (sent with each request) and returns SUCCESS. */
    public int setSpeechRate(float speechRate) {
        if (speechRate > 0.0f) {
            int intRate = (int) (speechRate * 100);
            if (intRate > 0) {
                synchronized (this) {
                    mParams.putInt(Engine.KEY_PARAM_RATE, intRate);
                }
                return SUCCESS;
            }
        }
        return ERROR;
    }

    /** AOSP keeps the pitch locally (sent with each request) and returns SUCCESS. */
    public int setPitch(float pitch) {
        if (pitch > 0.0f) {
            int intPitch = (int) (pitch * 100);
            if (intPitch > 0) {
                synchronized (this) {
                    mParams.putInt(Engine.KEY_PARAM_PITCH, intPitch);
                }
                return SUCCESS;
            }
        }
        return ERROR;
    }

    public int setAudioAttributes(AudioAttributes audioAttributes) {
        if (audioAttributes != null) {
            synchronized (this) {
                mAudioAttributes = audioAttributes;
            }
            return SUCCESS;
        }
        return ERROR;
    }

    public String getCurrentEngine() {
        return null; // AOSP: the engine of the connection, none
    }

    @Deprecated
    public Locale getDefaultLanguage() {
        notBound("getDefaultLanguage", ERROR);
        return null;
    }

    public int setLanguage(final Locale loc) {
        return notBound("setLanguage", LANG_NOT_SUPPORTED);
    }

    @Deprecated
    public Locale getLanguage() {
        notBound("getLanguage", ERROR);
        return null;
    }

    public Set<Locale> getAvailableLanguages() {
        notBound("getAvailableLanguages", ERROR);
        return null;
    }

    public Set<Voice> getVoices() {
        notBound("getVoices", ERROR);
        return null;
    }

    public int setVoice(final Voice voice) {
        return notBound("setVoice", ERROR);
    }

    public Voice getVoice() {
        notBound("getVoice", ERROR);
        return null;
    }

    public Voice getDefaultVoice() {
        notBound("getDefaultVoice", ERROR);
        return null;
    }

    public int isLanguageAvailable(final Locale loc) {
        return notBound("isLanguageAvailable", LANG_NOT_SUPPORTED);
    }

    public int synthesizeToFile(final CharSequence text, final Bundle params, final ParcelFileDescriptor fileDescriptor,
            final String utteranceId) {
        return notBound("synthesizeToFile", ERROR);
    }

    public int synthesizeToFile(final CharSequence text, final Bundle params, final File file, final String utteranceId) {
        if (file.exists() && !file.canWrite()) {
            Log.e(TAG, "Can't write to " + file);
            return ERROR;
        }
        return notBound("synthesizeToFile", ERROR);
    }

    @Deprecated
    public int synthesizeToFile(final String text, final HashMap<String, String> params, final String filename) {
        return notBound("synthesizeToFile", ERROR);
    }

    @Deprecated
    public int setOnUtteranceCompletedListener(final OnUtteranceCompletedListener listener) {
        mUtteranceProgressListener = UtteranceProgressListener.from(listener);
        return SUCCESS;
    }

    public int setOnUtteranceProgressListener(UtteranceProgressListener listener) {
        mUtteranceProgressListener = listener;
        return SUCCESS;
    }

    @Deprecated
    public int setEngineByPackageName(String enginePackageName) {
        return ERROR; // AOSP: the engine cannot be found / initialised
    }

    /** AOSP: the default engine from settings; none is installed. */
    public String getDefaultEngine() {
        return null;
    }

    @Deprecated
    public boolean areDefaultsEnforced() {
        return false;
    }

    /** The installed engines: none. */
    public List<EngineInfo> getEngines() {
        return new ArrayList<>();
    }

    public static int getMaxSpeechInputLength() {
        return 4000;
    }
}
