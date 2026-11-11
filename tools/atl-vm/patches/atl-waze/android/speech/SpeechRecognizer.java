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

package android.speech;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.Executor;

/**
 * This class provides access to the speech recognition service (AOSP API).
 *
 * teslalinux: AOSP binds the RecognitionService selected in Settings.Secure
 * VOICE_RECOGNITION_SERVICE (normally Google's). This device has no recognition service (and no
 * microphone), so this is AOSP's path without one: {@link #isRecognitionAvailable} is false, and
 * {@link #startListening} reports {@link RecognitionListener#onError} with
 * {@link #ERROR_CLIENT} on the main thread ("no selected voice recognition service"). An
 * explicitly named service component is bound through Context.bindService; one that cannot be
 * bound ends the same way, as in AOSP ("bind to recognition service failed").
 */
public class SpeechRecognizer {
    private static final String TAG = "SpeechRecognizer";

    public static final String RESULTS_RECOGNITION = "results_recognition";
    public static final String RESULTS_ALTERNATIVES = "results_alternatives";
    public static final String CONFIDENCE_SCORES = "confidence_scores";
    public static final String DETECTED_LANGUAGE = "detected_language";
    public static final String LANGUAGE_DETECTION_CONFIDENCE_LEVEL = "language_detection_confidence_level";
    public static final String TOP_LOCALE_ALTERNATIVES = "top_locale_alternatives";
    public static final String LANGUAGE_SWITCH_RESULT = "language_switch_result";

    public static final int ERROR_NETWORK_TIMEOUT = 1;
    public static final int ERROR_NETWORK = 2;
    public static final int ERROR_AUDIO = 3;
    public static final int ERROR_SERVER = 4;
    public static final int ERROR_CLIENT = 5;
    public static final int ERROR_SPEECH_TIMEOUT = 6;
    public static final int ERROR_NO_MATCH = 7;
    public static final int ERROR_RECOGNIZER_BUSY = 8;
    public static final int ERROR_INSUFFICIENT_PERMISSIONS = 9;
    public static final int ERROR_TOO_MANY_REQUESTS = 10;
    public static final int ERROR_SERVER_DISCONNECTED = 11;
    public static final int ERROR_LANGUAGE_NOT_SUPPORTED = 12;
    public static final int ERROR_LANGUAGE_UNAVAILABLE = 13;
    public static final int ERROR_CANNOT_CHECK_SUPPORT = 14;
    public static final int ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15;

    /** The RecognitionService intent action (RecognitionService.SERVICE_INTERFACE). */
    private static final String SERVICE_INTERFACE = "android.speech.RecognitionService";

    private final Context mContext;
    private final ComponentName mServiceComponent;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private RecognitionListener mListener;
    private ServiceConnection mConnection;

    private SpeechRecognizer(Context context, ComponentName serviceComponent) {
        mContext = context;
        mServiceComponent = serviceComponent;
    }

    /** No RecognitionService is installed on this device. */
    public static boolean isRecognitionAvailable(@NonNull final Context context) {
        return false;
    }

    /** No on-device recognition service is installed on this device. */
    public static boolean isOnDeviceRecognitionAvailable(@NonNull final Context context) {
        return false;
    }

    @NonNull
    public static SpeechRecognizer createSpeechRecognizer(final Context context) {
        return createSpeechRecognizer(context, null);
    }

    @NonNull
    public static SpeechRecognizer createSpeechRecognizer(final Context context,
            final ComponentName serviceComponent) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        checkIsCalledFromMainThread();
        return new SpeechRecognizer(context, serviceComponent);
    }

    @NonNull
    public static SpeechRecognizer createOnDeviceSpeechRecognizer(@NonNull final Context context) {
        if (!isOnDeviceRecognitionAvailable(context)) {
            throw new UnsupportedOperationException("On-device recognition is not supported");
        }
        return createSpeechRecognizer(context, null);
    }

    public void setRecognitionListener(RecognitionListener listener) {
        checkIsCalledFromMainThread();
        mListener = listener;
    }

    public void startListening(final Intent recognizerIntent) {
        if (recognizerIntent == null) {
            throw new IllegalArgumentException("intent must not be null");
        }
        checkIsCalledFromMainThread();
        if (mServiceComponent == null) {
            Log.e(TAG, "no selected voice recognition service");
            postError(ERROR_CLIENT);
            return;
        }
        Intent serviceIntent = new Intent(SERVICE_INTERFACE);
        serviceIntent.setComponent(mServiceComponent);
        mConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                // no IRecognitionService protocol behind the binder: the request cannot be sent
                Log.e(TAG, "recognition service " + name + " does not answer the recognition protocol");
                disconnect();
                postError(ERROR_CLIENT);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mConnection = null;
            }
        };
        boolean bound = false;
        try {
            bound = mContext.bindService(serviceIntent, mConnection, 1 /* Context.BIND_AUTO_CREATE */);
        } catch (Exception e) {
            Log.e(TAG, "bind to recognition service failed: " + e);
        }
        if (!bound) {
            Log.e(TAG, "bind to recognition service failed");
            mConnection = null;
            postError(ERROR_CLIENT);
        }
    }

    /** Nothing is listening (no request reaches a service, see the class comment). */
    public void stopListening() {
        checkIsCalledFromMainThread();
    }

    public void cancel() {
        checkIsCalledFromMainThread();
        mHandler.removeCallbacksAndMessages(null);
    }

    public void destroy() {
        disconnect();
        mHandler.removeCallbacksAndMessages(null);
        mListener = null;
    }

    private void disconnect() {
        if (mConnection != null) {
            try {
                mContext.unbindService(mConnection);
            } catch (Exception e) {
            }
            mConnection = null;
        }
    }

    private void postError(final int error) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                RecognitionListener l = mListener;
                if (l != null)
                    l.onError(error);
            }
        });
    }

    private static void checkIsCalledFromMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new RuntimeException(
                    "SpeechRecognizer should be used only from the application's main thread");
        }
    }
}
