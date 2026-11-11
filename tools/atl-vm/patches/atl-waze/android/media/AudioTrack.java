/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.metrics.LogSessionId;
import android.os.Handler;
import android.os.Looper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * teslalinux: the AOSP AudioTrack contract over ATL's ALSA output (16-bit interleaved PCM on the
 * default device). Streaming mode writes straight into the ALSA ring buffer (blocking or, with
 * WRITE_NON_BLOCKING, as much as fits); static mode keeps the data and streams it on play(). The
 * head position and timestamps come from frames written minus ALSA's queued delay.
 */
public class AudioTrack implements AudioRouting {
    private static final String TAG = "AudioTrack";
    private static final boolean DEBUG = System.getenv("ATL_DEBUG_MEDIA") != null;

    public static final float VOLUME_MIN = 0.0f;
    public static final float VOLUME_MAX = 1.0f;
    public static final int PLAYSTATE_STOPPED = 1;
    public static final int PLAYSTATE_PAUSED = 2;
    public static final int PLAYSTATE_PLAYING = 3;
    public static final int MODE_STATIC = 0;
    public static final int MODE_STREAM = 1;
    public static final int STATE_UNINITIALIZED = 0;
    public static final int STATE_INITIALIZED = 1;
    public static final int STATE_NO_STATIC_DATA = 2;
    public static final int SUCCESS = 0;
    public static final int ERROR = -1;
    public static final int ERROR_BAD_VALUE = -2;
    public static final int ERROR_INVALID_OPERATION = -3;
    public static final int ERROR_DEAD_OBJECT = -6;
    public static final int WRITE_BLOCKING = 0;
    public static final int WRITE_NON_BLOCKING = 1;
    public static final int PERFORMANCE_MODE_NONE = 0;
    public static final int PERFORMANCE_MODE_LOW_LATENCY = 1;
    public static final int PERFORMANCE_MODE_POWER_SAVING = 2;
    public static final int ENCAPSULATION_MODE_NONE = 0;
    public static final int ENCAPSULATION_MODE_ELEMENTARY_STREAM = 1;
    public static final int ENCAPSULATION_METADATA_TYPE_NONE = 0;
    public static final int ENCAPSULATION_METADATA_TYPE_FRAMEWORK_TUNER = 1;
    public static final int ENCAPSULATION_METADATA_TYPE_DVB_AD_DESCRIPTOR = 2;
    public static final int ENCAPSULATION_METADATA_TYPE_SUPPLEMENTARY_AUDIO_PLACEMENT = 3;
    public static final int DUAL_MONO_MODE_OFF = 0;
    public static final int DUAL_MONO_MODE_LR = 1;
    public static final int DUAL_MONO_MODE_LL = 2;
    public static final int DUAL_MONO_MODE_RR = 3;
    public static final int DIRECT_PLAYBACK_NOT_SUPPORTED = 0;
    public static final int DIRECT_PLAYBACK_OFFLOAD_SUPPORTED = 1;
    public static final int DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED = 2;
    public static final int DIRECT_PLAYBACK_BITSTREAM_SUPPORTED = 4;

    /** The mixer rate of the host's default ALSA device; it resamples every track to this. */
    private static final int NATIVE_OUTPUT_SAMPLE_RATE = 48000;

    public interface OnPlaybackPositionUpdateListener {
        void onMarkerReached(AudioTrack track);
        void onPeriodicNotification(AudioTrack track);
    }

    public abstract static class StreamEventCallback {
        public void onTearDown(@NonNull AudioTrack track) {}
        public void onPresentationEnded(@NonNull AudioTrack track) {}
        public void onDataRequest(@NonNull AudioTrack track, int sizeInFrames) {}
    }

    public interface OnCodecFormatChangedListener {
        void onCodecFormatChanged(@NonNull AudioTrack audioTrack, @Nullable android.os.PersistableBundle info);
    }

    // ---- the fields the native side uses (names are part of the JNI contract) ----
    int streamType;
    int sampleRateInHz;
    int channelConfig;
    int audioFormat;
    int bufferSizeInBytes;
    int mode;
    long pcm_handle;
    long params;
    int channels;
    int period_time;
    int buffer_frames;
    OnPlaybackPositionUpdateListener periodic_update_listener;

    private final AudioAttributes mAttributes;
    private final AudioFormat mFormat;
    private int mSessionId;
    private int mState = STATE_UNINITIALIZED;
    private int mPlayState = PLAYSTATE_STOPPED;
    private long mFramesWritten;
    private int mUnderruns;
    private float mVolumeLeft = 1f, mVolumeRight = 1f;
    private int mPlaybackRate;
    private int mMarkerFrames;
    private int mPeriodFrames;
    private Handler mPositionHandler;
    private byte[] mStaticData;
    private int mStaticLoopStart, mStaticLoopEnd, mStaticLoops;
    private Thread mStaticThread;
    private int mPerformanceMode = PERFORMANCE_MODE_NONE;
    private int mDualMonoMode = DUAL_MONO_MODE_OFF;
    private AudioDeviceInfo mPreferredDevice;
    private final List<Object[]> mRoutingListeners = new ArrayList<>();
    private final List<Object[]> mStreamEventCallbacks = new ArrayList<>();
    private LogSessionId mLogSessionId = LogSessionId.LOG_SESSION_ID_NONE;
    private PlaybackParams mPlaybackParams;
    private int mStartThresholdFrames;

    native void native_constructor(int streamType, int sampleRateInHz, int num_channels, int audioFormat, int bufferSizeInBytes, int mode);
    private native int native_getPlaybackHeadPosition();
    public native void native_play();
    public native void native_pause();
    private native int native_write(byte[] audioData, int offsetInBytes, int framesToWrite, float volume);
    private native int native_write(short[] audioData, int offsetInShorts, int framesToWrite, float volume);
    public native void native_release();
    private native void native_stop();
    private native void native_flush();
    private native int native_avail();
    private native int native_takeUnderruns();
    public static native int getMinBufferSize(int sampleRateInHz, int channelConfig, int audioFormat);

    @Deprecated
    public AudioTrack(int streamType, int sampleRateInHz, int channelConfig, int audioFormat, int bufferSizeInBytes, int mode) throws IllegalArgumentException {
        this(streamType, sampleRateInHz, channelConfig, audioFormat, bufferSizeInBytes, mode, AudioManager.AUDIO_SESSION_ID_GENERATE);
    }

    @Deprecated
    public AudioTrack(int streamType, int sampleRateInHz, int channelConfig, int audioFormat, int bufferSizeInBytes, int mode, int sessionId) throws IllegalArgumentException {
        this(new AudioAttributes.Builder().setLegacyStreamType(streamType).build(),
                new AudioFormat.Builder().setChannelMask(channelConfig).setEncoding(audioFormat).setSampleRate(sampleRateInHz).build(),
                bufferSizeInBytes, mode, sessionId);
    }

    public AudioTrack(AudioAttributes attributes, AudioFormat format, int bufferSizeInBytes, int mode, int sessionId) throws IllegalArgumentException {
        if (attributes == null)
            throw new IllegalArgumentException("Illegal null AudioAttributes");
        if (format == null)
            throw new IllegalArgumentException("Illegal null AudioFormat");
        if (mode != MODE_STREAM && mode != MODE_STATIC)
            throw new IllegalArgumentException("Invalid transfer mode " + mode);
        int rate = (format.getPropertySetMask() & AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_SAMPLE_RATE) != 0 ? format.getSampleRate() : NATIVE_OUTPUT_SAMPLE_RATE;
        if (rate < AudioFormat.SAMPLE_RATE_HZ_MIN || rate > AudioFormat.SAMPLE_RATE_HZ_MAX)
            throw new IllegalArgumentException(rate + "Hz is not a supported sample rate.");
        int encoding = (format.getPropertySetMask() & AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_ENCODING) != 0 ? format.getEncoding() : AudioFormat.ENCODING_PCM_16BIT;
        if (encoding == AudioFormat.ENCODING_DEFAULT)
            encoding = AudioFormat.ENCODING_PCM_16BIT;
        if (!AudioFormat.isPublicEncoding(encoding) || !AudioFormat.isEncodingLinearPcm(encoding))
            throw new IllegalArgumentException("Unsupported audio encoding " + encoding + ": this output takes linear PCM");
        int mask = (format.getPropertySetMask() & AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_MASK) != 0 ? format.getChannelMask() : AudioFormat.CHANNEL_OUT_STEREO;
        if (mask == AudioFormat.CHANNEL_OUT_DEFAULT || mask == 0)
            mask = AudioFormat.CHANNEL_OUT_STEREO;
        int channelCount = Integer.bitCount(mask);
        if (channelCount < 1 || channelCount > 8)
            throw new IllegalArgumentException("Unsupported channel configuration.");
        if (bufferSizeInBytes <= 0 || bufferSizeInBytes % (channelCount * AudioFormat.getBytesPerSample(encoding)) != 0)
            throw new IllegalArgumentException("Invalid audio buffer size.");
        mAttributes = attributes;
        mFormat = new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(encoding).build();
        this.streamType = attributes.getVolumeControlStream();
        this.sampleRateInHz = rate;
        this.channelConfig = mask;
        this.audioFormat = encoding;
        this.bufferSizeInBytes = bufferSizeInBytes;
        this.mode = mode;
        this.mPlaybackRate = rate;
        mSessionId = sessionId == AudioManager.AUDIO_SESSION_ID_GENERATE ? AudioSystem.newAudioSessionId() : sessionId;
        // the native side plays 16-bit interleaved PCM: other PCM encodings are converted in write()
        native_constructor(this.streamType, rate, channelCount, AudioFormat.ENCODING_PCM_16BIT, bytesToNativeBytes(bufferSizeInBytes), mode);
        if (pcm_handle != 0) {
            mState = mode == MODE_STATIC ? STATE_NO_STATIC_DATA : STATE_INITIALIZED;
            mStartThresholdFrames = buffer_frames;
            // AOSP PlayerBase.baseRegisterPlayer: the player exists for AudioService from now on
            AudioManager.tlPlayerEvent(this, AudioPlaybackConfiguration.PLAYER_STATE_IDLE);
        }
        if (DEBUG)
            android.util.Log.i(TAG, "AudioTrack " + rate + "Hz mask=0x" + Integer.toHexString(mask) + " enc=" + encoding + " buffer=" + bufferSizeInBytes + " mode=" + mode + " -> state=" + mState + " frames=" + buffer_frames);
    }

    private AudioTrack(Builder b) {
        this(b.mAttributes, b.mFormat, b.mBufferSizeInBytes, b.mMode, b.mSessionId);
        mPerformanceMode = b.mPerformanceMode;
    }

    /** bytes of this track's encoding -> bytes of 16-bit PCM for the same frames */
    private int bytesToNativeBytes(int bytes) {
        int bps = AudioFormat.getBytesPerSample(audioFormat);
        return bytes / bps * 2;
    }

    public static class Builder {
        private AudioAttributes mAttributes;
        private AudioFormat mFormat;
        private int mBufferSizeInBytes;
        private int mSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE;
        private int mMode = MODE_STREAM;
        private int mPerformanceMode = PERFORMANCE_MODE_NONE;
        private boolean mOffload;
        private int mEncapsulationMode = ENCAPSULATION_MODE_NONE;

        public Builder() {}

        public @NonNull Builder setContext(@NonNull android.content.Context context) {
            return this;
        }

        public @NonNull Builder setAudioAttributes(@NonNull AudioAttributes attributes) throws IllegalArgumentException {
            if (attributes == null)
                throw new IllegalArgumentException("Illegal null AudioAttributes argument");
            mAttributes = attributes;
            return this;
        }

        public @NonNull Builder setAudioFormat(@NonNull AudioFormat format) throws IllegalArgumentException {
            if (format == null)
                throw new IllegalArgumentException("Illegal null AudioFormat argument");
            mFormat = format;
            return this;
        }

        public @NonNull Builder setBufferSizeInBytes(int bufferSizeInBytes) throws IllegalArgumentException {
            if (bufferSizeInBytes <= 0)
                throw new IllegalArgumentException("Invalid buffer size " + bufferSizeInBytes);
            mBufferSizeInBytes = bufferSizeInBytes;
            return this;
        }

        public @NonNull Builder setEncapsulationMode(int encapsulationMode) {
            switch (encapsulationMode) {
                case ENCAPSULATION_MODE_NONE:
                case ENCAPSULATION_MODE_ELEMENTARY_STREAM:
                    mEncapsulationMode = encapsulationMode;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid encapsulation mode " + encapsulationMode);
            }
            return this;
        }

        public @NonNull Builder setTransferMode(int mode) throws IllegalArgumentException {
            switch (mode) {
                case MODE_STREAM:
                case MODE_STATIC:
                    mMode = mode;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid transfer mode " + mode);
            }
            return this;
        }

        public @NonNull Builder setSessionId(int sessionId) throws IllegalArgumentException {
            if ((sessionId != AudioManager.AUDIO_SESSION_ID_GENERATE) && (sessionId < 1))
                throw new IllegalArgumentException("Invalid audio session ID " + sessionId);
            mSessionId = sessionId;
            return this;
        }

        public @NonNull Builder setPerformanceMode(int performanceMode) {
            switch (performanceMode) {
                case PERFORMANCE_MODE_NONE:
                case PERFORMANCE_MODE_LOW_LATENCY:
                case PERFORMANCE_MODE_POWER_SAVING:
                    mPerformanceMode = performanceMode;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid performance mode " + performanceMode);
            }
            return this;
        }

        public @NonNull Builder setOffloadedPlayback(boolean offload) {
            mOffload = offload;
            return this;
        }

        public @NonNull AudioTrack build() throws UnsupportedOperationException {
            if (mAttributes == null)
                mAttributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
            if (mFormat == null)
                mFormat = new AudioFormat.Builder().setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setSampleRate(NATIVE_OUTPUT_SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_DEFAULT).build();
            if (mOffload) {
                // AOSP: offloading needs a compressed-format capable output; this one has none
                throw new UnsupportedOperationException("Offload playback is not supported on this device");
            }
            if (mEncapsulationMode != ENCAPSULATION_MODE_NONE)
                throw new UnsupportedOperationException("Encapsulation mode " + mEncapsulationMode + " needs an offloaded track");
            if (mBufferSizeInBytes == 0) {
                int encoding = (mFormat.getPropertySetMask() & AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_ENCODING) != 0 ? mFormat.getEncoding() : AudioFormat.ENCODING_PCM_16BIT;
                int mask = (mFormat.getPropertySetMask() & AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_MASK) != 0 ? mFormat.getChannelMask() : AudioFormat.CHANNEL_OUT_STEREO;
                int rate = (mFormat.getPropertySetMask() & AudioFormat.AUDIO_FORMAT_HAS_PROPERTY_SAMPLE_RATE) != 0 ? mFormat.getSampleRate() : NATIVE_OUTPUT_SAMPLE_RATE;
                mBufferSizeInBytes = getMinBufferSize(rate, mask, encoding == AudioFormat.ENCODING_DEFAULT ? AudioFormat.ENCODING_PCM_16BIT : encoding);
                if (mBufferSizeInBytes <= 0)
                    mBufferSizeInBytes = Integer.bitCount(mask) * AudioFormat.getBytesPerSample(encoding) * 4096;
            }
            final AudioTrack track = new AudioTrack(this);
            if (track.getState() == STATE_UNINITIALIZED) {
                // AOSP: the native track could not be created (here: the PCM device cannot be opened)
                throw new UnsupportedOperationException("Cannot create AudioTrack");
            }
            return track;
        }
    }

    // ---- static queries ----

    public static int getNativeOutputSampleRate(int streamType) {
        return NATIVE_OUTPUT_SAMPLE_RATE;
    }

    public static float getMinVolume() {
        return VOLUME_MIN;
    }

    public static float getMaxVolume() {
        return VOLUME_MAX;
    }

    public static boolean isDirectPlaybackSupported(@NonNull AudioFormat format, @NonNull AudioAttributes attributes) {
        return false; // the ALSA output takes linear PCM only
    }

    public static int getDirectPlaybackSupport(@NonNull AudioFormat format, @NonNull AudioAttributes attributes) {
        return DIRECT_PLAYBACK_NOT_SUPPORTED;
    }

    // ---- getters ----

    public int getState() {
        return mState;
    }

    public int getPlayState() {
        synchronized (this) {
            return mPlayState;
        }
    }

    public int getSampleRate() {
        return sampleRateInHz;
    }

    public int getPlaybackRate() {
        return mPlaybackRate;
    }

    public int getAudioFormat() {
        return audioFormat;
    }

    public int getStreamType() {
        return streamType;
    }

    public int getChannelConfiguration() {
        return channelConfig;
    }

    public int getChannelCount() {
        return Integer.bitCount(channelConfig);
    }

    @NonNull
    public AudioFormat getFormat() {
        return mFormat;
    }

    @NonNull
    public AudioAttributes getAudioAttributes() {
        return mAttributes;
    }

    public int getAudioSessionId() {
        return mSessionId;
    }

    public int getBufferSizeInFrames() {
        return buffer_frames;
    }

    public int getBufferCapacityInFrames() {
        return buffer_frames;
    }

    public int setBufferSizeInFrames(int bufferSizeInFrames) {
        if (mState == STATE_UNINITIALIZED)
            return ERROR_INVALID_OPERATION;
        if (bufferSizeInFrames < 0)
            return ERROR_BAD_VALUE;
        // the ALSA ring buffer is fixed at open: the effective size stays the capacity (AOSP clamps too)
        return buffer_frames;
    }

    public int getStartThresholdInFrames() {
        if (mState == STATE_UNINITIALIZED)
            throw new IllegalStateException("AudioTrack is not initialized");
        return mStartThresholdFrames;
    }

    public int setStartThresholdInFrames(int startThresholdInFrames) {
        if (mState == STATE_UNINITIALIZED)
            throw new IllegalStateException("AudioTrack is not initialized");
        if (startThresholdInFrames < 1)
            throw new IllegalArgumentException("startThresholdInFrames " + startThresholdInFrames + " must be positive");
        mStartThresholdFrames = Math.min(startThresholdInFrames, buffer_frames);
        return mStartThresholdFrames;
    }

    public int getUnderrunCount() {
        mUnderruns += native_takeUnderruns();
        return mUnderruns;
    }

    public int getPerformanceMode() {
        return mPerformanceMode;
    }

    /** AOSP (hidden): the output latency in milliseconds. */
    public int getLatency() {
        return sampleRateInHz == 0 ? 0 : (int) ((long) buffer_frames * 1000 / sampleRateInHz);
    }

    public int getNotificationMarkerPosition() {
        return mMarkerFrames;
    }

    public int getPositionNotificationPeriod() {
        return mPeriodFrames;
    }

    public int getPlaybackHeadPosition() {
        if (mState == STATE_UNINITIALIZED)
            return ERROR_INVALID_OPERATION;
        synchronized (this) {
            long played = mFramesWritten - (mPlayState == PLAYSTATE_PLAYING ? native_getPlaybackHeadPosition() : 0);
            if (played < 0)
                played = 0;
            return (int) played; // AOSP: a 32-bit frame counter that wraps
        }
    }

    public boolean getTimestamp(AudioTimestamp timestamp) {
        if (timestamp == null)
            throw new IllegalArgumentException();
        if (mState == STATE_UNINITIALIZED || mPlayState != PLAYSTATE_PLAYING || mFramesWritten == 0)
            return false;
        synchronized (this) {
            long played = mFramesWritten - native_getPlaybackHeadPosition();
            timestamp.framePosition = played < 0 ? 0 : played;
            timestamp.nanoTime = System.nanoTime();
        }
        return true;
    }

    public int getTimestampWithStatus(AudioTimestamp timestamp) {
        if (timestamp == null)
            throw new IllegalArgumentException();
        if (mState == STATE_UNINITIALIZED)
            return ERROR_INVALID_OPERATION;
        return getTimestamp(timestamp) ? SUCCESS : ERROR_INVALID_OPERATION;
    }

    @Nullable
    public AudioTimestamp getMetrics() {
        return null;
    }

    // ---- setters ----

    public int setStereoVolume(float leftGain, float rightGain) {
        if (mState == STATE_UNINITIALIZED)
            return ERROR_INVALID_OPERATION;
        mVolumeLeft = clampGain(leftGain);
        mVolumeRight = clampGain(rightGain);
        return SUCCESS;
    }

    public int setVolume(float gain) {
        return setStereoVolume(gain, gain);
    }

    private static float clampGain(float gain) {
        return gain < VOLUME_MIN ? VOLUME_MIN : (gain > VOLUME_MAX ? VOLUME_MAX : gain);
    }

    public int setPlaybackRate(int sampleRateInHz) {
        if (mState != STATE_INITIALIZED)
            return ERROR_INVALID_OPERATION;
        if (sampleRateInHz <= 0)
            return ERROR_BAD_VALUE;
        if (sampleRateInHz != this.sampleRateInHz) {
            // the ALSA stream was opened at the track's rate; AOSP resamples in the mixer, this output cannot
            return ERROR_INVALID_OPERATION;
        }
        mPlaybackRate = sampleRateInHz;
        return SUCCESS;
    }

    public void setPlaybackParams(@NonNull PlaybackParams params) {
        if (params == null)
            throw new IllegalArgumentException("params is null");
        if (mState == STATE_UNINITIALIZED)
            throw new IllegalStateException("AudioTrack is not initialized");
        if (Math.abs(params.getSpeed() - 1f) > 1e-4f || Math.abs(params.getPitch() - 1f) > 1e-4f)
            throw new IllegalArgumentException("speed/pitch other than 1.0 are not supported by this output");
        mPlaybackParams = params;
    }

    @NonNull
    public PlaybackParams getPlaybackParams() {
        if (mState == STATE_UNINITIALIZED)
            throw new IllegalStateException("AudioTrack is not initialized");
        return mPlaybackParams != null ? mPlaybackParams : new PlaybackParams().setSpeed(1f).setPitch(1f);
    }

    public int setNotificationMarkerPosition(int markerInFrames) {
        if (markerInFrames < 0)
            return ERROR_BAD_VALUE;
        if (mState == STATE_UNINITIALIZED)
            return ERROR_INVALID_OPERATION;
        mMarkerFrames = markerInFrames;
        return SUCCESS;
    }

    public int setPositionNotificationPeriod(int periodInFrames) {
        if (periodInFrames < 0)
            return ERROR_BAD_VALUE;
        if (mState == STATE_UNINITIALIZED)
            return ERROR_INVALID_OPERATION;
        mPeriodFrames = periodInFrames;
        return SUCCESS;
    }

    public int setPlaybackHeadPosition(int positionInFrames) {
        if (mode == MODE_STREAM || mState == STATE_UNINITIALIZED || getPlayState() == PLAYSTATE_PLAYING)
            return ERROR_INVALID_OPERATION;
        if (positionInFrames < 0 || mStaticData == null || positionInFrames > mStaticData.length / frameBytes())
            return ERROR_BAD_VALUE;
        mFramesWritten = positionInFrames;
        return SUCCESS;
    }

    public int setLoopPoints(int startInFrames, int endInFrames, int loopCount) {
        if (mode == MODE_STREAM || mState == STATE_UNINITIALIZED || getPlayState() == PLAYSTATE_PLAYING)
            return ERROR_INVALID_OPERATION;
        if (loopCount == 0) {
            // explicitly allowed
        } else if (mStaticData == null || startInFrames < 0 || endInFrames > mStaticData.length / frameBytes() || startInFrames >= endInFrames) {
            return ERROR_BAD_VALUE;
        }
        mStaticLoopStart = startInFrames;
        mStaticLoopEnd = endInFrames;
        mStaticLoops = loopCount;
        return SUCCESS;
    }

    public void setPlaybackPositionUpdateListener(OnPlaybackPositionUpdateListener listener) {
        setPlaybackPositionUpdateListener(listener, null);
    }

    public void setPlaybackPositionUpdateListener(OnPlaybackPositionUpdateListener listener, Handler handler) {
        periodic_update_listener = listener;
        if (listener == null) {
            mPositionHandler = null;
        } else if (handler != null) {
            mPositionHandler = handler;
        } else {
            Looper looper = Looper.myLooper();
            mPositionHandler = new Handler(looper != null ? looper : Looper.getMainLooper());
        }
    }

    public int attachAuxEffect(int effectId) {
        if (mState == STATE_UNINITIALIZED)
            return ERROR_INVALID_OPERATION;
        // AOSP: 0 detaches; there are no audio effects on this output, so any other id is unknown
        return effectId == 0 ? SUCCESS : ERROR_BAD_VALUE;
    }

    public int setAuxEffectSendLevel(float level) {
        if (mState == STATE_UNINITIALIZED)
            return ERROR_INVALID_OPERATION;
        return level >= 0f && level <= 1f ? SUCCESS : ERROR_BAD_VALUE;
    }

    public boolean setDualMonoMode(int dualMonoMode) {
        // AOSP: false when the output does not support dual mono processing
        return false;
    }

    public int getDualMonoMode() {
        return mDualMonoMode;
    }

    public void setLogSessionId(@NonNull LogSessionId logSessionId) {
        mLogSessionId = logSessionId;
    }

    @NonNull
    public LogSessionId getLogSessionId() {
        return mLogSessionId;
    }

    public boolean isOffloadedPlayback() {
        return false;
    }

    public void setOffloadDelayPadding(int delayInFrames, int paddingInFrames) {
        throw new IllegalStateException("Not an offloaded AudioTrack");
    }

    public int getOffloadDelay() {
        throw new IllegalStateException("Not an offloaded AudioTrack");
    }

    public int getOffloadPadding() {
        throw new IllegalStateException("Not an offloaded AudioTrack");
    }

    public void setOffloadEndOfStream() {
        throw new IllegalStateException("Not an offloaded AudioTrack");
    }

    public void registerStreamEventCallback(@NonNull Executor executor, @NonNull StreamEventCallback eventCallback) {
        if (eventCallback == null || executor == null)
            throw new IllegalArgumentException("Illegal null argument");
        synchronized (mStreamEventCallbacks) {
            for (Object[] e : mStreamEventCallbacks) {
                if (e[1] == eventCallback)
                    throw new IllegalArgumentException("StreamEventCallback already registered");
            }
            mStreamEventCallbacks.add(new Object[] {executor, eventCallback});
        }
    }

    public void unregisterStreamEventCallback(@NonNull StreamEventCallback eventCallback) {
        synchronized (mStreamEventCallbacks) {
            for (int i = mStreamEventCallbacks.size() - 1; i >= 0; i--) {
                if (mStreamEventCallbacks.get(i)[1] == eventCallback)
                    mStreamEventCallbacks.remove(i);
            }
        }
    }

    // ---- routing (one output device) ----

    @Override
    public boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
        if (deviceInfo != null && !deviceInfo.isSink())
            return false;
        mPreferredDevice = deviceInfo;
        return true;
    }

    @Override
    public AudioDeviceInfo getPreferredDevice() {
        return mPreferredDevice;
    }

    @Override
    public AudioDeviceInfo getRoutedDevice() {
        if (mState == STATE_UNINITIALIZED || getPlayState() != PLAYSTATE_PLAYING)
            return null; // AOSP: unknown until the track is playing
        return AudioManager.tlDefaultOutputDevice();
    }

    @Override
    public void addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener, Handler handler) {
        if (listener == null)
            return;
        synchronized (mRoutingListeners) {
            mRoutingListeners.add(new Object[] {listener, handler});
        }
    }

    @Override
    public void removeOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener) {
        synchronized (mRoutingListeners) {
            for (int i = mRoutingListeners.size() - 1; i >= 0; i--) {
                if (mRoutingListeners.get(i)[0] == listener)
                    mRoutingListeners.remove(i);
            }
        }
    }

    // ---- transport ----

    public void play() throws IllegalStateException {
        if (mState != STATE_INITIALIZED)
            throw new IllegalStateException("play() called on uninitialized AudioTrack.");
        synchronized (this) {
            if (mPlayState == PLAYSTATE_PLAYING)
                return;
            mPlayState = PLAYSTATE_PLAYING;
            if (DEBUG)
                android.util.Log.i(TAG, "play() written=" + mFramesWritten);
            native_play();
            if (mode == MODE_STATIC)
                startStaticPlayback();
        }
        AudioManager.tlPlayerEvent(this, AudioPlaybackConfiguration.PLAYER_STATE_STARTED);
    }

    public void stop() throws IllegalStateException {
        if (mState != STATE_INITIALIZED)
            throw new IllegalStateException("stop() called on uninitialized AudioTrack.");
        synchronized (this) {
            if (mPlayState == PLAYSTATE_STOPPED)
                return;
            mPlayState = PLAYSTATE_STOPPED;
            if (DEBUG)
                android.util.Log.i(TAG, "stop() written=" + mFramesWritten);
            native_stop();
            mFramesWritten = 0;
        }
        AudioManager.tlPlayerEvent(this, AudioPlaybackConfiguration.PLAYER_STATE_STOPPED);
    }

    public void pause() throws IllegalStateException {
        if (mState != STATE_INITIALIZED)
            throw new IllegalStateException("pause() called on uninitialized AudioTrack.");
        synchronized (this) {
            if (mPlayState != PLAYSTATE_PLAYING)
                return;
            mPlayState = PLAYSTATE_PAUSED;
            native_pause();
        }
        AudioManager.tlPlayerEvent(this, AudioPlaybackConfiguration.PLAYER_STATE_PAUSED);
    }

    public void flush() {
        if (mState != STATE_INITIALIZED)
            return;
        synchronized (this) {
            if (mPlayState == PLAYSTATE_PLAYING)
                return; // AOSP: flush() is ignored while playing
            native_flush();
            mFramesWritten = 0;
        }
    }

    public void release() {
        synchronized (this) {
            if (mState == STATE_UNINITIALIZED)
                return;
            try {
                if (mPlayState != PLAYSTATE_STOPPED)
                    native_stop();
            } catch (IllegalStateException ise) {
            }
            mPlayState = PLAYSTATE_STOPPED;
            native_release();
            pcm_handle = 0;
            mState = STATE_UNINITIALIZED;
        }
        AudioManager.tlPlayerEvent(this, AudioPlaybackConfiguration.PLAYER_STATE_RELEASED);
    }

    @Override
    protected void finalize() {
        release();
    }

    // ---- data ----

    private int frameBytes() {
        return Integer.bitCount(channelConfig) * AudioFormat.getBytesPerSample(audioFormat);
    }

    /**
     * The gain the samples get: the track volume times its stream's volume (AudioManager's stream
     * index through the volume curve, 0 while muted), as AudioFlinger combines them.
     */
    private float effectiveVolume() {
        return Math.max(mVolumeLeft, mVolumeRight) * AudioManager.tlStreamGain(streamType);
    }

    /** Converts one chunk of this track's encoding to the 16-bit interleaved PCM the output takes. */
    private byte[] toPcm16(byte[] data, int offset, int bytes) {
        switch (audioFormat) {
            case AudioFormat.ENCODING_PCM_16BIT:
                if (offset == 0 && bytes == data.length)
                    return data;
                byte[] copy = new byte[bytes];
                System.arraycopy(data, offset, copy, 0, bytes);
                return copy;
            case AudioFormat.ENCODING_PCM_8BIT: {
                byte[] out = new byte[bytes * 2];
                for (int i = 0; i < bytes; i++) {
                    short s = (short) (((data[offset + i] & 0xff) - 128) << 8);
                    out[2 * i] = (byte) s;
                    out[2 * i + 1] = (byte) (s >> 8);
                }
                return out;
            }
            case AudioFormat.ENCODING_PCM_FLOAT: {
                int samples = bytes / 4;
                byte[] out = new byte[samples * 2];
                ByteBuffer in = ByteBuffer.wrap(data, offset, bytes).order(ByteOrder.nativeOrder());
                for (int i = 0; i < samples; i++) {
                    float f = in.getFloat();
                    int s = Math.round(Math.max(-1f, Math.min(1f, f)) * 32767f);
                    out[2 * i] = (byte) s;
                    out[2 * i + 1] = (byte) (s >> 8);
                }
                return out;
            }
            case AudioFormat.ENCODING_PCM_24BIT_PACKED: {
                int samples = bytes / 3;
                byte[] out = new byte[samples * 2];
                for (int i = 0; i < samples; i++) {
                    out[2 * i] = data[offset + 3 * i + 1];
                    out[2 * i + 1] = data[offset + 3 * i + 2];
                }
                return out;
            }
            case AudioFormat.ENCODING_PCM_32BIT: {
                int samples = bytes / 4;
                byte[] out = new byte[samples * 2];
                for (int i = 0; i < samples; i++) {
                    out[2 * i] = data[offset + 4 * i + 2];
                    out[2 * i + 1] = data[offset + 4 * i + 3];
                }
                return out;
            }
            default:
                throw new IllegalStateException("unsupported encoding " + audioFormat);
        }
    }

    /** Writes frames of 16-bit PCM; returns frames written. Non-blocking writes stop at the free ring-buffer space. */
    private int writePcm16(byte[] pcm, int offset, int bytes, boolean blocking) {
        int channelCount = Integer.bitCount(channelConfig);
        int frames = bytes / (channelCount * 2);
        if (frames == 0)
            return 0;
        if (!blocking) {
            int avail = native_avail();
            if (avail <= 0)
                return 0;
            frames = Math.min(frames, avail);
        }
        int written = native_write(pcm, offset, frames, effectiveVolume());
        if (DEBUG && (mFramesWritten == 0 || written <= 0))
            android.util.Log.i(TAG, "write " + frames + " frames blocking=" + blocking + " -> " + written);
        if (written > 0) {
            synchronized (this) {
                mFramesWritten += written;
            }
            notifyPositions();
        }
        return written;
    }

    private void notifyPositions() {
        final OnPlaybackPositionUpdateListener l = periodic_update_listener;
        final Handler h = mPositionHandler;
        if (l == null || h == null)
            return;
        long written = mFramesWritten;
        if (mMarkerFrames > 0 && written >= mMarkerFrames && written - mMarkerFrames < buffer_frames)
            h.post(() -> l.onMarkerReached(this));
        if (mPeriodFrames > 0 && (written / mPeriodFrames) != ((written - buffer_frames) / mPeriodFrames))
            h.post(() -> l.onPeriodicNotification(this));
    }

    public int write(@NonNull byte[] audioData, int offsetInBytes, int sizeInBytes) {
        return write(audioData, offsetInBytes, sizeInBytes, WRITE_BLOCKING);
    }

    public int write(@NonNull byte[] audioData, int offsetInBytes, int sizeInBytes, int writeMode) {
        if (mState == STATE_UNINITIALIZED || audioFormat == AudioFormat.ENCODING_PCM_FLOAT)
            return ERROR_INVALID_OPERATION;
        if (writeMode != WRITE_BLOCKING && writeMode != WRITE_NON_BLOCKING)
            return ERROR_BAD_VALUE;
        if (audioData == null || offsetInBytes < 0 || sizeInBytes < 0 || offsetInBytes + sizeInBytes < 0 || offsetInBytes + sizeInBytes > audioData.length)
            return ERROR_BAD_VALUE;
        if (mode == MODE_STATIC) {
            return writeStatic(audioData, offsetInBytes, sizeInBytes);
        }
        int bps = AudioFormat.getBytesPerSample(audioFormat);
        byte[] pcm = toPcm16(audioData, offsetInBytes, sizeInBytes);
        int frames = writePcm16(pcm, pcm == audioData ? offsetInBytes : 0, pcm == audioData ? sizeInBytes : pcm.length, writeMode == WRITE_BLOCKING);
        return frames < 0 ? ERROR : frames * Integer.bitCount(channelConfig) * bps;
    }

    public int write(@NonNull short[] audioData, int offsetInShorts, int sizeInShorts) {
        return write(audioData, offsetInShorts, sizeInShorts, WRITE_BLOCKING);
    }

    public int write(@NonNull short[] audioData, int offsetInShorts, int sizeInShorts, int writeMode) {
        if (mState == STATE_UNINITIALIZED || audioFormat == AudioFormat.ENCODING_PCM_FLOAT)
            return ERROR_INVALID_OPERATION;
        if (writeMode != WRITE_BLOCKING && writeMode != WRITE_NON_BLOCKING)
            return ERROR_BAD_VALUE;
        if (audioData == null || offsetInShorts < 0 || sizeInShorts < 0 || offsetInShorts + sizeInShorts < 0 || offsetInShorts + sizeInShorts > audioData.length)
            return ERROR_BAD_VALUE;
        byte[] bytes = new byte[sizeInShorts * 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer().put(audioData, offsetInShorts, sizeInShorts);
        if (mode == MODE_STATIC)
            return writeStatic(bytes, 0, bytes.length) / 2;
        int frames = writePcm16(bytes, 0, bytes.length, writeMode == WRITE_BLOCKING);
        return frames < 0 ? ERROR : frames * Integer.bitCount(channelConfig);
    }

    public int write(@NonNull float[] audioData, int offsetInFloats, int sizeInFloats, int writeMode) {
        if (mState == STATE_UNINITIALIZED || audioFormat != AudioFormat.ENCODING_PCM_FLOAT)
            return ERROR_INVALID_OPERATION;
        if (writeMode != WRITE_BLOCKING && writeMode != WRITE_NON_BLOCKING)
            return ERROR_BAD_VALUE;
        if (audioData == null || offsetInFloats < 0 || sizeInFloats < 0 || offsetInFloats + sizeInFloats < 0 || offsetInFloats + sizeInFloats > audioData.length)
            return ERROR_BAD_VALUE;
        byte[] bytes = new byte[sizeInFloats * 4];
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().put(audioData, offsetInFloats, sizeInFloats);
        if (mode == MODE_STATIC)
            return writeStatic(bytes, 0, bytes.length) / 4;
        byte[] pcm = toPcm16(bytes, 0, bytes.length);
        int frames = writePcm16(pcm, 0, pcm.length, writeMode == WRITE_BLOCKING);
        return frames < 0 ? ERROR : frames * Integer.bitCount(channelConfig);
    }

    public int write(@NonNull ByteBuffer audioData, int sizeInBytes, int writeMode) {
        if (mState == STATE_UNINITIALIZED)
            return ERROR_INVALID_OPERATION;
        if (writeMode != WRITE_BLOCKING && writeMode != WRITE_NON_BLOCKING)
            return ERROR_BAD_VALUE;
        if (audioData == null || sizeInBytes < 0 || sizeInBytes > audioData.remaining())
            return ERROR_BAD_VALUE;
        byte[] data;
        int offset;
        if (audioData.hasArray()) {
            data = audioData.array();
            offset = audioData.arrayOffset() + audioData.position();
        } else {
            data = new byte[sizeInBytes];
            int pos = audioData.position();
            audioData.get(data);
            audioData.position(pos);
            offset = 0;
        }
        int ret;
        if (mode == MODE_STATIC) {
            ret = writeStatic(data, offset, sizeInBytes);
        } else {
            int bps = AudioFormat.getBytesPerSample(audioFormat);
            byte[] pcm = toPcm16(data, offset, sizeInBytes);
            int frames = writePcm16(pcm, pcm == data ? offset : 0, pcm == data ? sizeInBytes : pcm.length, writeMode == WRITE_BLOCKING);
            ret = frames < 0 ? ERROR : frames * Integer.bitCount(channelConfig) * bps;
        }
        if (ret > 0)
            audioData.position(audioData.position() + ret);
        return ret;
    }

    public int write(@NonNull ByteBuffer audioData, int sizeInBytes, int writeMode, long timestamp) {
        // AOSP: the timestamp drives A/V sync of HW_AV_SYNC tracks; this output has no sync header
        return write(audioData, sizeInBytes, writeMode);
    }

    // ---- static mode: the whole clip is kept and streamed from a thread on play() ----

    private int writeStatic(byte[] data, int offset, int bytes) {
        if (mPlayState == PLAYSTATE_PLAYING)
            return ERROR_INVALID_OPERATION;
        int old = mStaticData == null ? 0 : mStaticData.length;
        if (old + bytes > bufferSizeInBytes)
            bytes = bufferSizeInBytes - old;
        if (bytes <= 0)
            return 0;
        byte[] grown = new byte[old + bytes];
        if (old > 0)
            System.arraycopy(mStaticData, 0, grown, 0, old);
        System.arraycopy(data, offset, grown, old, bytes);
        mStaticData = grown;
        if (mState == STATE_NO_STATIC_DATA)
            mState = STATE_INITIALIZED;
        return bytes;
    }

    public int reloadStaticData() {
        if (mode == MODE_STREAM || mState != STATE_INITIALIZED)
            return ERROR_INVALID_OPERATION;
        mFramesWritten = 0;
        return SUCCESS;
    }

    private void startStaticPlayback() {
        final byte[] data = mStaticData;
        if (data == null)
            return;
        final int fb = frameBytes();
        mStaticThread = new Thread(() -> {
            int loops = mStaticLoops;
            int frame = (int) mFramesWritten;
            int end = mStaticLoopEnd > 0 ? Math.min(mStaticLoopEnd, data.length / fb) : data.length / fb;
            while (getPlayState() == PLAYSTATE_PLAYING) {
                if (frame >= end) {
                    if (loops == 0)
                        break;
                    if (loops > 0)
                        loops--;
                    frame = mStaticLoopStart;
                }
                int chunk = Math.min(1024, end - frame);
                byte[] pcm = toPcm16(data, frame * fb, chunk * fb);
                int written = writePcm16(pcm, pcm == data ? frame * fb : 0, pcm == data ? chunk * fb : pcm.length, true);
                if (written <= 0)
                    break;
                frame += written;
            }
            boolean ended = false;
            synchronized (AudioTrack.this) {
                if (mPlayState == PLAYSTATE_PLAYING) {
                    mPlayState = PLAYSTATE_STOPPED;
                    ended = true;
                }
            }
            if (ended)
                AudioManager.tlPlayerEvent(AudioTrack.this, AudioPlaybackConfiguration.PLAYER_STATE_STOPPED);
        }, "AudioTrack static");
        mStaticThread.setDaemon(true);
        mStaticThread.start();
    }

}
