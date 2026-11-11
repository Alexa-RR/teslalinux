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
package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * teslalinux: the AOSP MediaCodec contract (states, synchronous and asynchronous buffer modes,
 * callbacks, exceptions, metrics) over ATL's FFmpeg decoders. There are decoders only: audio is
 * resampled to 16-bit interleaved PCM, video frames are rendered to the configured Surface by the
 * native side when an output buffer is released with render=true.
 */
public final class MediaCodec {
    private static final String TAG = "MediaCodec";
    private static final boolean DEBUG = System.getenv("ATL_DEBUG_MEDIA") != null;
    private int mDbgIn, mDbgOut;

    public static final int BUFFER_FLAG_SYNC_FRAME = 1;
    public static final int BUFFER_FLAG_KEY_FRAME = 1;
    public static final int BUFFER_FLAG_CODEC_CONFIG = 2;
    public static final int BUFFER_FLAG_END_OF_STREAM = 4;
    public static final int BUFFER_FLAG_PARTIAL_FRAME = 8;
    /** AOSP (hidden): muxer-only data. */
    public static final int BUFFER_FLAG_MUXER_DATA = 16;
    public static final int BUFFER_FLAG_DECODE_ONLY = 32;

    public static final int CONFIGURE_FLAG_ENCODE = 1;
    public static final int CONFIGURE_FLAG_USE_BLOCK_MODEL = 2;
    public static final int CONFIGURE_FLAG_USE_CRYPTO_ASYNC = 4;
    public static final int CONFIGURE_FLAG_DETACHED_SURFACE = 8;

    public static final int INFO_TRY_AGAIN_LATER = -1;
    public static final int INFO_OUTPUT_FORMAT_CHANGED = -2;
    @Deprecated
    public static final int INFO_OUTPUT_BUFFERS_CHANGED = -3;

    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT = 1;
    public static final int VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING = 2;

    public static final int CRYPTO_MODE_UNENCRYPTED = 0;
    public static final int CRYPTO_MODE_AES_CTR = 1;
    public static final int CRYPTO_MODE_AES_CBC = 2;

    public static final String PARAMETER_KEY_VIDEO_BITRATE = "video-bitrate";
    public static final String PARAMETER_KEY_SUSPEND = "drop-input-frames";
    public static final String PARAMETER_KEY_SUSPEND_TIME = "drop-start-time-us";
    public static final String PARAMETER_KEY_REQUEST_SYNC_FRAME = "request-sync";
    public static final String PARAMETER_KEY_OFFSET_TIME = "time-offset-us";
    public static final String PARAMETER_KEY_HDR10_PLUS_INFO = MediaFormat.KEY_HDR10_PLUS_INFO;
    public static final String PARAMETER_KEY_LOW_LATENCY = MediaFormat.KEY_LOW_LATENCY;
    public static final String PARAMETER_KEY_TUNNEL_PEEK = "tunnel-peek";

    public static final class MetricsConstants {
        private MetricsConstants() {}
        public static final String CODEC = "android.media.mediacodec.codec";
        public static final String MIME_TYPE = "android.media.mediacodec.mime";
        public static final String MODE = "android.media.mediacodec.mode";
        public static final String MODE_AUDIO = "audio";
        public static final String MODE_VIDEO = "video";
        public static final String ENCODER = "android.media.mediacodec.encoder";
        public static final String SECURE = "android.media.mediacodec.secure";
        public static final String WIDTH = "android.media.mediacodec.width";
        public static final String HEIGHT = "android.media.mediacodec.height";
        public static final String ROTATION = "android.media.mediacodec.rotation";
    }

    public static final class BufferInfo {
        public int offset;
        public int size;
        public long presentationTimeUs;
        public int flags;

        public void set(int newOffset, int newSize, long newTimeUs, int newFlags) {
            offset = newOffset;
            size = newSize;
            presentationTimeUs = newTimeUs;
            flags = newFlags;
        }

        /** AOSP (hidden): a copy. */
        public BufferInfo dup() {
            BufferInfo copy = new BufferInfo();
            copy.set(offset, size, presentationTimeUs, flags);
            return copy;
        }
    }

    public static final class CryptoInfo {
        public int numSubSamples;
        public int[] numBytesOfClearData;
        public int[] numBytesOfEncryptedData;
        public byte[] key;
        public byte[] iv;
        public int mode;

        public static final class Pattern {
            private int mEncryptBlocks;
            private int mSkipBlocks;

            public Pattern(int blocksToEncrypt, int blocksToSkip) {
                set(blocksToEncrypt, blocksToSkip);
            }

            public void set(int blocksToEncrypt, int blocksToSkip) {
                mEncryptBlocks = blocksToEncrypt;
                mSkipBlocks = blocksToSkip;
            }

            public int getSkipBlocks() {
                return mSkipBlocks;
            }

            public int getEncryptBlocks() {
                return mEncryptBlocks;
            }
        }

        private Pattern mPattern = new Pattern(0, 0);

        public void set(int newNumSubSamples, @NonNull int[] newNumBytesOfClearData, @NonNull int[] newNumBytesOfEncryptedData,
                @NonNull byte[] newKey, @NonNull byte[] newIV, int newMode) {
            numSubSamples = newNumSubSamples;
            numBytesOfClearData = newNumBytesOfClearData;
            numBytesOfEncryptedData = newNumBytesOfEncryptedData;
            key = newKey;
            iv = newIV;
            mode = newMode;
            mPattern = new Pattern(0, 0);
        }

        public void setPattern(Pattern newPattern) {
            mPattern = newPattern;
        }

        /** AOSP (API 31): the pattern. */
        @NonNull
        public Pattern getPattern() {
            return new Pattern(mPattern.getEncryptBlocks(), mPattern.getSkipBlocks());
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(numSubSamples + " subsamples, key [");
            String hexdigits = "0123456789abcdef";
            for (int i = 0; key != null && i < key.length; i++) {
                builder.append(hexdigits.charAt((key[i] & 0xf0) >> 4));
                builder.append(hexdigits.charAt(key[i] & 0x0f));
            }
            builder.append("], iv [");
            for (int i = 0; iv != null && i < iv.length; i++) {
                builder.append(hexdigits.charAt((iv[i] & 0xf0) >> 4));
                builder.append(hexdigits.charAt(iv[i] & 0x0f));
            }
            builder.append("], clear ");
            builder.append(java.util.Arrays.toString(numBytesOfClearData));
            builder.append(", encrypted ");
            builder.append(java.util.Arrays.toString(numBytesOfEncryptedData));
            builder.append(", pattern (encrypt: ").append(mPattern.mEncryptBlocks).append(", skip: ").append(mPattern.mSkipBlocks).append(")");
            return builder.toString();
        }
    }

    public static final class CodecException extends IllegalStateException {
        public static final int ERROR_INSUFFICIENT_RESOURCE = 1100;
        public static final int ERROR_RECLAIMED = 1101;
        private static final int ACTION_TRANSIENT = 1;
        private static final int ACTION_RECOVERABLE = 2;
        private final String mDiagnosticInfo;
        private final int mErrorCode;
        private final int mActionCode;

        /** AOSP (hidden) */
        public CodecException(int errorCode, int actionCode, @Nullable String detailMessage) {
            super(detailMessage);
            mErrorCode = errorCode;
            mActionCode = actionCode;
            final String sign = errorCode < 0 ? "neg_" : "";
            mDiagnosticInfo = "android.media.MediaCodec.error_" + sign + Math.abs(errorCode);
        }

        public boolean isTransient() {
            return mActionCode == ACTION_TRANSIENT;
        }

        public boolean isRecoverable() {
            return mActionCode == ACTION_RECOVERABLE;
        }

        public int getErrorCode() {
            return mErrorCode;
        }

        @NonNull
        public String getDiagnosticInfo() {
            return mDiagnosticInfo;
        }
    }

    public static final class CryptoException extends RuntimeException {
        public static final int ERROR_NO_KEY = 1;
        public static final int ERROR_KEY_EXPIRED = 2;
        public static final int ERROR_RESOURCE_BUSY = 3;
        public static final int ERROR_INSUFFICIENT_OUTPUT_PROTECTION = 4;
        public static final int ERROR_SESSION_NOT_OPENED = 5;
        public static final int ERROR_UNSUPPORTED_OPERATION = 6;
        public static final int ERROR_INSUFFICIENT_SECURITY = 7;
        public static final int ERROR_FRAME_TOO_LARGE = 8;
        public static final int ERROR_LOST_STATE = 9;
        private final int mErrorCode;
        private final CryptoInfo mCryptoInfo;

        public CryptoException(int errorCode, @Nullable String detailMessage) {
            this(detailMessage, errorCode, null);
        }

        /** AOSP (API 31) */
        public CryptoException(String message, int errorCode, CryptoInfo cryptoInfo) {
            super(message);
            mErrorCode = errorCode;
            mCryptoInfo = cryptoInfo;
        }

        public int getErrorCode() {
            return mErrorCode;
        }

        @Nullable
        public CryptoInfo getCryptoInfo() {
            return mCryptoInfo;
        }
    }

    public static class IncompatibleWithBlockModelException extends RuntimeException {
        IncompatibleWithBlockModelException() {}

        IncompatibleWithBlockModelException(String message) {
            super(message);
        }
    }

    public static abstract class Callback {
        public abstract void onInputBufferAvailable(@NonNull MediaCodec codec, int index);

        public abstract void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull BufferInfo info);

        public abstract void onError(@NonNull MediaCodec codec, @NonNull CodecException e);

        /** AOSP (API 31): a cryptographic error; decoders here have no crypto path. */
        public void onCryptoError(@NonNull MediaCodec codec, @NonNull CryptoException e) {
            throw new IllegalStateException("Callback.onCryptoError is not implemented");
        }

        public abstract void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format);
    }

    public interface OnFrameRenderedListener {
        void onFrameRendered(@NonNull MediaCodec codec, long presentationTimeUs, long nanoTime);
    }

    // ---- state ----
    private static final int STATE_UNINITIALIZED = 0;
    private static final int STATE_CONFIGURED = 1;
    private static final int STATE_STARTED = 2;   // running (or flushed, in asynchronous mode)
    private static final int STATE_RELEASED = 3;

    private final Object mLock = new Object();
    private final String mName;
    private final String mFfmpegName;
    private final boolean mVideo;
    private long mNative;
    private int mState = STATE_UNINITIALIZED;
    private boolean mFlushedAsync; // asynchronous mode: flushed, waiting for start()

    private MediaFormat mInputFormat;
    private MediaFormat mOutputFormat;
    private boolean mOutputFormatAnnounced;
    private Surface mSurface;
    private int mVideoScalingMode = VIDEO_SCALING_MODE_SCALE_TO_FIT;
    private final Bundle mParameters = new Bundle();

    private ByteBuffer[] mInputBuffers;
    private ByteBuffer[] mOutputBuffers;
    private long[] mInputTimes;
    private int[] mInputFlags;
    private final ArrayDeque<Integer> mFreeInputs = new ArrayDeque<>();
    private final ArrayDeque<Integer> mQueuedInputs = new ArrayDeque<>(); // -1 marks the end of stream
    private final ArrayDeque<Integer> mFreeOutputs = new ArrayDeque<>();
    private final BufferInfo[] mOutputInfos;
    private boolean mEosQueued;
    private boolean mEosDelivered;

    private Callback mCallback;
    private Handler mCallbackHandler;
    private Thread mWorker;
    private OnFrameRenderedListener mFrameRenderedListener;
    private Handler mFrameRenderedHandler;

    private static final int INPUT_BUFFER_COUNT = 4;
    private static final int OUTPUT_BUFFER_COUNT = 4;
    private static final int DEFAULT_MAX_INPUT_SIZE = 262144;
    private static final int AUDIO_OUTPUT_BUFFER_SIZE = 192 * 1024; // 8 channels of 16-bit PCM for the longest Opus frame

    private native long native_constructor(String codecName);
    private native void native_configure_audio(long codec, ByteBuffer extradata, int sampleRate, int channelCount);
    private native void native_configure_video(long codec, ByteBuffer csd0, ByteBuffer csd1, Surface surface);
    private native void native_start(long codec);
    private native int native_queueInputBuffer(long codec, ByteBuffer buffer, long presentationTimeUs);
    private native int native_dequeueOutputBuffer(long codec, ByteBuffer buffer, BufferInfo info);
    private native void native_releaseOutputBuffer(long codec, ByteBuffer buffer, boolean render);
    private native void native_release(long codec);
    private native void native_flush(long codec);
    private native void native_setOutputSurface(long codec, Surface surface);

    private MediaCodec(String name) throws IOException {
        String ffmpegName = MediaCodecList.tlFfmpegName(name);
        if (ffmpegName == null) {
            throw new IOException("Failed to initialize " + name + ", error 0xfffffffe");
        }
        mName = MediaCodecList.tlCodecName(ffmpegName);
        mFfmpegName = ffmpegName;
        mVideo = MediaCodecList.tlIsVideo(ffmpegName);
        mNative = native_constructor(ffmpegName);
        if (mNative == 0) {
            throw new IOException("Failed to initialize " + name + ", error 0xfffffffe");
        }
        mOutputInfos = new BufferInfo[OUTPUT_BUFFER_COUNT];
        for (int i = 0; i < OUTPUT_BUFFER_COUNT; i++)
            mOutputInfos[i] = new BufferInfo();
    }

    @NonNull
    public static MediaCodec createDecoderByType(@NonNull String type) throws IOException {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, type);
        String name = new MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format);
        if (name == null) {
            throw new IOException("Failed to initialize decoder for " + type);
        }
        return new MediaCodec(name);
    }

    @NonNull
    public static MediaCodec createEncoderByType(@NonNull String type) throws IOException {
        // there are no encoders on this platform; AOSP fails the same way when none is registered for the type
        throw new IOException("Failed to initialize encoder for " + type);
    }

    @NonNull
    public static MediaCodec createByCodecName(@NonNull String name) throws IOException {
        return new MediaCodec(name);
    }

    // ---- configuration ----

    public void configure(@Nullable MediaFormat format, @Nullable Surface surface, @Nullable MediaCrypto crypto, int flags) {
        synchronized (mLock) {
            if (mState == STATE_RELEASED)
                throw new IllegalStateException("codec released");
            if (mState != STATE_UNINITIALIZED)
                throw new IllegalStateException("configure() in state " + mState);
            if ((flags & CONFIGURE_FLAG_ENCODE) != 0)
                throw new IllegalArgumentException(mName + " is a decoder");
            if (crypto != null)
                throw new CryptoException(CryptoException.ERROR_UNSUPPORTED_OPERATION, "secure decoding is not available on this device");
            if (format == null)
                throw new IllegalArgumentException("null format");
            mInputFormat = new MediaFormat(format);
            int maxInputSize = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : DEFAULT_MAX_INPUT_SIZE;
            if (maxInputSize <= 0)
                maxInputSize = DEFAULT_MAX_INPUT_SIZE;
            mInputBuffers = new ByteBuffer[INPUT_BUFFER_COUNT];
            mInputTimes = new long[INPUT_BUFFER_COUNT];
            mInputFlags = new int[INPUT_BUFFER_COUNT];
            mFreeInputs.clear();
            mQueuedInputs.clear();
            for (int i = 0; i < INPUT_BUFFER_COUNT; i++) {
                mInputBuffers[i] = ByteBuffer.allocateDirect(maxInputSize).order(ByteOrder.nativeOrder());
                mFreeInputs.add(i);
            }
            int outSize;
            if (mVideo) {
                outSize = 64; // the native side hands a frame pointer through the buffer and renders on release
            } else {
                outSize = AUDIO_OUTPUT_BUFFER_SIZE;
            }
            mOutputBuffers = new ByteBuffer[OUTPUT_BUFFER_COUNT];
            mFreeOutputs.clear();
            for (int i = 0; i < OUTPUT_BUFFER_COUNT; i++) {
                mOutputBuffers[i] = ByteBuffer.allocateDirect(outSize).order(ByteOrder.nativeOrder());
                mFreeOutputs.add(i);
            }
            mOutputFormat = buildOutputFormat(format);
            mOutputFormatAnnounced = false;
            mEosQueued = false;
            mEosDelivered = false;
            mSurface = surface;
            if (mVideo) {
                native_configure_video(mNative, format.getByteBuffer("csd-0"), format.getByteBuffer("csd-1"), surface);
            } else {
                int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
                int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
                native_configure_audio(mNative, format.getByteBuffer("csd-0"), sampleRate, channels);
            }
            mState = STATE_CONFIGURED;
            if (DEBUG)
                Log.i(TAG, mName + " configure " + format + " surface=" + surface + " -> out " + mOutputFormat);
        }
    }

    public void configure(@Nullable MediaFormat format, @Nullable Surface surface, int flags, @Nullable MediaCrypto crypto) {
        configure(format, surface, crypto, flags);
    }

    private MediaFormat buildOutputFormat(MediaFormat input) {
        MediaFormat out = new MediaFormat();
        if (mVideo) {
            out.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_RAW);
            int w = input.containsKey(MediaFormat.KEY_WIDTH) ? input.getInteger(MediaFormat.KEY_WIDTH) : 0;
            int h = input.containsKey(MediaFormat.KEY_HEIGHT) ? input.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            out.setInteger(MediaFormat.KEY_WIDTH, w);
            out.setInteger(MediaFormat.KEY_HEIGHT, h);
            out.setInteger(MediaFormat.KEY_STRIDE, w);
            out.setInteger(MediaFormat.KEY_SLICE_HEIGHT, h);
            out.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            out.setInteger("crop-left", 0);
            out.setInteger("crop-top", 0);
            out.setInteger("crop-right", Math.max(0, w - 1));
            out.setInteger("crop-bottom", Math.max(0, h - 1));
            if (input.containsKey(MediaFormat.KEY_ROTATION))
                out.setInteger(MediaFormat.KEY_ROTATION, input.getInteger(MediaFormat.KEY_ROTATION));
        } else {
            out.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_RAW);
            out.setInteger(MediaFormat.KEY_SAMPLE_RATE, input.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? input.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000);
            out.setInteger(MediaFormat.KEY_CHANNEL_COUNT, input.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? input.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2);
            out.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        }
        return out;
    }

    public void setCallback(@Nullable Callback cb, @Nullable Handler handler) {
        synchronized (mLock) {
            if (mState == STATE_STARTED)
                throw new IllegalStateException("setCallback() while running");
            mCallback = cb;
            if (cb == null) {
                mCallbackHandler = null;
            } else if (handler != null) {
                mCallbackHandler = handler;
            } else {
                Looper looper = Looper.myLooper();
                mCallbackHandler = new Handler(looper != null ? looper : Looper.getMainLooper());
            }
        }
    }

    public void setCallback(@Nullable Callback cb) {
        setCallback(cb, null);
    }

    public void setOnFrameRenderedListener(@Nullable OnFrameRenderedListener listener, @Nullable Handler handler) {
        synchronized (mLock) {
            mFrameRenderedListener = listener;
            if (listener == null) {
                mFrameRenderedHandler = null;
            } else if (handler != null) {
                mFrameRenderedHandler = handler;
            } else {
                Looper looper = Looper.myLooper();
                mFrameRenderedHandler = new Handler(looper != null ? looper : Looper.getMainLooper());
            }
        }
    }

    // ---- lifecycle ----

    public final void start() {
        synchronized (mLock) {
            if (mState == STATE_STARTED && mFlushedAsync) {
                mFlushedAsync = false;
                announceInputs();
                mLock.notifyAll();
                return;
            }
            if (mState != STATE_CONFIGURED)
                throw new IllegalStateException("start() in state " + mState);
            native_start(mNative);
            mState = STATE_STARTED;
            if (DEBUG)
                Log.i(TAG, mName + " start async=" + (mCallback != null));
            mFlushedAsync = false;
            if (mCallback != null) {
                startWorker();
                announceInputs();
            }
        }
    }

    public final void stop() {
        synchronized (mLock) {
            if (mState == STATE_RELEASED)
                throw new IllegalStateException("codec released");
            if (mState == STATE_UNINITIALIZED)
                return;
            stopWorkerLocked();
            // FFmpeg contexts are opened once: a stopped codec gets a fresh context for the next configure()
            native_release(mNative);
            mNative = native_constructor(mFfmpegName);
            mState = STATE_UNINITIALIZED;
            mFlushedAsync = false;
            mInputBuffers = null;
            mOutputBuffers = null;
            mFreeInputs.clear();
            mQueuedInputs.clear();
            mFreeOutputs.clear();
        }
    }

    public final void reset() {
        stop();
        synchronized (mLock) {
            mParameters.clear();
            mVideoScalingMode = VIDEO_SCALING_MODE_SCALE_TO_FIT;
        }
    }

    public final void flush() {
        synchronized (mLock) {
            if (mState != STATE_STARTED)
                throw new IllegalStateException("flush() in state " + mState);
            if (DEBUG)
                Log.i(TAG, mName + " flush");
            native_flush(mNative);
            for (Integer i : mQueuedInputs) {
                if (i >= 0)
                    mFreeInputs.add(i);
            }
            mQueuedInputs.clear();
            for (int i = 0; i < OUTPUT_BUFFER_COUNT; i++) {
                if (!mFreeOutputs.contains(i))
                    mFreeOutputs.add(i);
            }
            mEosQueued = false;
            mEosDelivered = false;
            if (mCallback != null)
                mFlushedAsync = true; // AOSP: callbacks resume after start()
            mLock.notifyAll();
        }
    }

    public final void release() {
        synchronized (mLock) {
            if (mState == STATE_RELEASED)
                return;
            if (DEBUG)
                Log.i(TAG, mName + " release after " + mDbgIn + " inputs / " + mDbgOut + " outputs");
            stopWorkerLocked();
            if (mNative != 0)
                native_release(mNative);
            mNative = 0;
            mState = STATE_RELEASED;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void finalize() throws Throwable {
        try {
            release();
        } finally {
            super.finalize();
        }
    }

    // ---- buffers ----

    private void checkRunning() {
        if (mState != STATE_STARTED)
            throw new IllegalStateException("codec not running (state " + mState + ")");
    }

    @NonNull
    @Deprecated
    public ByteBuffer[] getInputBuffers() {
        synchronized (mLock) {
            if (mInputBuffers == null)
                throw new IllegalStateException("codec not configured");
            return mInputBuffers.clone();
        }
    }

    @NonNull
    @Deprecated
    public ByteBuffer[] getOutputBuffers() {
        synchronized (mLock) {
            if (mOutputBuffers == null)
                throw new IllegalStateException("codec not configured");
            return mOutputBuffers.clone();
        }
    }

    @Nullable
    public ByteBuffer getInputBuffer(int index) {
        synchronized (mLock) {
            if (mInputBuffers == null || index < 0 || index >= mInputBuffers.length)
                throw new IllegalStateException("bad input buffer index " + index);
            ByteBuffer b = mInputBuffers[index];
            b.clear();
            return b;
        }
    }

    @Nullable
    public ByteBuffer getOutputBuffer(int index) {
        synchronized (mLock) {
            if (mOutputBuffers == null || index < 0 || index >= mOutputBuffers.length)
                throw new IllegalStateException("bad output buffer index " + index);
            ByteBuffer b = mOutputBuffers[index];
            BufferInfo info = mOutputInfos[index];
            b.limit(Math.min(b.capacity(), info.offset + info.size));
            b.position(info.offset);
            return b;
        }
    }

    public final int dequeueInputBuffer(long timeoutUs) {
        long deadline = timeoutUs < 0 ? Long.MAX_VALUE : System.nanoTime() + timeoutUs * 1000;
        synchronized (mLock) {
            checkRunning();
            if (mCallback != null)
                throw new IllegalStateException("dequeueInputBuffer() in asynchronous mode");
            while (true) {
                drainInputsLocked();
                Integer index = mFreeInputs.poll();
                if (index != null)
                    return index;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    return INFO_TRY_AGAIN_LATER;
                waitLocked(remaining);
            }
        }
    }

    public final void queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags) throws CryptoException {
        synchronized (mLock) {
            checkRunning();
            if (mInputBuffers == null || index < 0 || index >= mInputBuffers.length)
                throw new IllegalStateException("bad input buffer index " + index);
            if (mFreeInputs.contains(index) || mQueuedInputs.contains(index))
                throw new IllegalStateException("input buffer " + index + " is owned by the codec");
            ByteBuffer b = mInputBuffers[index];
            if (offset < 0 || size < 0 || offset + size > b.capacity())
                throw new IllegalArgumentException("offset/size outside the buffer");
            b.limit(offset + size);
            b.position(offset);
            mInputTimes[index] = presentationTimeUs;
            mInputFlags[index] = flags;
            if ((flags & BUFFER_FLAG_END_OF_STREAM) != 0)
                mEosQueued = true;
            mQueuedInputs.add(index);
            if (DEBUG && (++mDbgIn <= 3 || (flags & BUFFER_FLAG_END_OF_STREAM) != 0))
                Log.i(TAG, mName + " queueInput #" + mDbgIn + " idx=" + index + " size=" + size + " pts=" + presentationTimeUs + " flags=" + flags);
            drainInputsLocked();
            mLock.notifyAll();
        }
    }

    public final void queueSecureInputBuffer(int index, int offset, @NonNull CryptoInfo info, long presentationTimeUs, int flags) throws CryptoException {
        throw new CryptoException(CryptoException.ERROR_UNSUPPORTED_OPERATION, "secure decoding is not available on this device");
    }

    public final int dequeueOutputBuffer(@NonNull BufferInfo info, long timeoutUs) {
        long deadline = timeoutUs < 0 ? Long.MAX_VALUE : System.nanoTime() + timeoutUs * 1000;
        synchronized (mLock) {
            checkRunning();
            if (mCallback != null)
                throw new IllegalStateException("dequeueOutputBuffer() in asynchronous mode");
            while (true) {
                if (!mOutputFormatAnnounced) {
                    mOutputFormatAnnounced = true;
                    return INFO_OUTPUT_FORMAT_CHANGED;
                }
                drainInputsLocked();
                int index = pollOutputLocked();
                if (index >= 0) {
                    BufferInfo mine = mOutputInfos[index];
                    info.set(mine.offset, mine.size, mine.presentationTimeUs, mine.flags);
                    return index;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                    return INFO_TRY_AGAIN_LATER;
                waitLocked(Math.min(remaining, 5_000_000L));
            }
        }
    }

    public final void releaseOutputBuffer(int index, boolean render) {
        releaseOutput(index, render, Long.MIN_VALUE);
    }

    public final void releaseOutputBuffer(int index, long renderTimestampNs) {
        releaseOutput(index, true, renderTimestampNs);
    }

    private void releaseOutput(int index, boolean render, long renderTimestampNs) {
        OnFrameRenderedListener listener = null;
        Handler handler = null;
        long pts = 0;
        synchronized (mLock) {
            if (mState != STATE_STARTED)
                throw new IllegalStateException("releaseOutputBuffer() in state " + mState);
            if (mOutputBuffers == null || index < 0 || index >= mOutputBuffers.length)
                throw new IllegalStateException("bad output buffer index " + index);
            if (mFreeOutputs.contains(index))
                throw new IllegalStateException("output buffer " + index + " was not dequeued");
            native_releaseOutputBuffer(mNative, mOutputBuffers[index], render && mVideo);
            mFreeOutputs.add(index);
            if (render && mVideo && mFrameRenderedListener != null) {
                listener = mFrameRenderedListener;
                handler = mFrameRenderedHandler;
                pts = mOutputInfos[index].presentationTimeUs;
            }
            mLock.notifyAll();
        }
        if (listener != null) {
            final OnFrameRenderedListener l = listener;
            final long p = pts;
            final long when = renderTimestampNs == Long.MIN_VALUE ? System.nanoTime() : renderTimestampNs;
            handler.post(() -> l.onFrameRendered(this, p, when));
        }
    }

    /** Sends queued packets to the decoder; buffers it accepted become free again. */
    private boolean drainInputsLocked() {
        boolean progressed = false;
        while (!mQueuedInputs.isEmpty()) {
            int index = mQueuedInputs.peek();
            int ret;
            if (index < 0 || (mInputFlags[index] & BUFFER_FLAG_END_OF_STREAM) != 0 && mInputBuffers[index].remaining() == 0) {
                ret = native_queueInputBuffer(mNative, null, 0);
            } else {
                ret = native_queueInputBuffer(mNative, mInputBuffers[index], mInputTimes[index]);
                if (ret == 0 && (mInputFlags[index] & BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // data and the end of stream in one buffer: the end marker follows the data
                    native_queueInputBuffer(mNative, null, 0);
                }
            }
            if (ret != 0)
                break; // the decoder wants its frames read first (EAGAIN)
            mQueuedInputs.poll();
            if (index >= 0) {
                if (mCallback != null && !mFlushedAsync) {
                    postInputAvailable(index); // asynchronous mode: the client owns the buffer again
                } else {
                    mFreeInputs.add(index);
                }
            }
            progressed = true;
        }
        return progressed;
    }

    /** Pulls one decoded frame into a free output buffer; returns its index or -1. */
    private int pollOutputLocked() {
        if (mEosDelivered)
            return -1;
        Integer index = mFreeOutputs.poll();
        if (index == null)
            return -1;
        ByteBuffer b = mOutputBuffers[index];
        b.clear();
        BufferInfo info = mOutputInfos[index];
        info.set(0, 0, 0, 0);
        int ret = native_dequeueOutputBuffer(mNative, b, info);
        if (ret != 0) {
            mFreeOutputs.addFirst(index);
            return -1;
        }
        if ((info.flags & BUFFER_FLAG_END_OF_STREAM) != 0)
            mEosDelivered = true;
        if (DEBUG && (++mDbgOut <= 3 || mEosDelivered))
            Log.i(TAG, mName + " output #" + mDbgOut + " idx=" + index + " size=" + info.size + " pts=" + info.presentationTimeUs + " flags=" + info.flags);
        return index;
    }

    private void waitLocked(long nanos) {
        try {
            long ms = nanos / 1_000_000L;
            int ns = (int) (nanos % 1_000_000L);
            if (ms == 0 && ns == 0)
                ns = 1;
            mLock.wait(ms, ns);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- asynchronous mode ----

    /** Asynchronous mode: hands every free input buffer to the client (it owns them until queued). */
    private void announceInputs() {
        Integer i;
        while ((i = mFreeInputs.poll()) != null)
            postInputAvailable(i);
    }

    private void postInputAvailable(final int index) {
        final Callback cb = mCallback;
        mCallbackHandler.post(() -> {
            if (cb == mCallback)
                cb.onInputBufferAvailable(this, index);
        });
    }

    private void startWorker() {
        mWorker = new Thread(this::workerLoop, "MediaCodec_loop");
        mWorker.setDaemon(true);
        mWorker.start();
    }

    private void stopWorkerLocked() {
        Thread worker = mWorker;
        mWorker = null;
        mLock.notifyAll();
        if (worker != null && worker != Thread.currentThread()) {
            // the worker checks mWorker under the lock, so release it while joining
            mLock.notifyAll();
        }
    }

    private void workerLoop() {
        final Thread me = Thread.currentThread();
        while (true) {
            Runnable delivery = null;
            synchronized (mLock) {
                if (mWorker != me || mState != STATE_STARTED)
                    return;
                if (mFlushedAsync) {
                    waitLocked(50_000_000L);
                    continue;
                }
                boolean progressed = drainInputsLocked();
                if (!mOutputFormatAnnounced) {
                    mOutputFormatAnnounced = true;
                    final MediaFormat f = new MediaFormat(mOutputFormat);
                    final Callback cb = mCallback;
                    delivery = () -> cb.onOutputFormatChanged(this, f);
                } else {
                    int index = pollOutputLocked();
                    if (index >= 0) {
                        final BufferInfo info = mOutputInfos[index].dup();
                        final int idx = index;
                        final Callback cb = mCallback;
                        delivery = () -> cb.onOutputBufferAvailable(this, idx, info);
                        progressed = true;
                    }
                }
                if (delivery == null && !progressed) {
                    // nothing to decode: wait for input, a freed output buffer, or a stop
                    waitLocked(mEosQueued && !mEosDelivered ? 2_000_000L : 20_000_000L);
                }
            }
            if (delivery != null)
                mCallbackHandler.post(delivery);
        }
    }

    // ---- formats, parameters, info ----

    @NonNull
    public final MediaFormat getOutputFormat() {
        synchronized (mLock) {
            if (mState == STATE_UNINITIALIZED || mState == STATE_RELEASED)
                throw new IllegalStateException("getOutputFormat() in state " + mState);
            return new MediaFormat(mOutputFormat);
        }
    }

    @NonNull
    public final MediaFormat getInputFormat() {
        synchronized (mLock) {
            if (mState == STATE_UNINITIALIZED || mState == STATE_RELEASED)
                throw new IllegalStateException("getInputFormat() in state " + mState);
            return new MediaFormat(mInputFormat);
        }
    }

    @NonNull
    public final MediaFormat getOutputFormat(int index) {
        synchronized (mLock) {
            if (mOutputBuffers == null || index < 0 || index >= mOutputBuffers.length)
                throw new IllegalStateException("bad output buffer index " + index);
            return new MediaFormat(mOutputFormat);
        }
    }

    public final void setParameters(@Nullable Bundle params) {
        if (params == null)
            return;
        synchronized (mLock) {
            if (mState == STATE_RELEASED)
                throw new IllegalStateException("codec released");
            mParameters.putAll(params);
            if (params.containsKey(PARAMETER_KEY_VIDEO_BITRATE) || params.containsKey(PARAMETER_KEY_REQUEST_SYNC_FRAME))
                Log.w(TAG, "encoder parameters ignored by decoder " + mName);
        }
    }

    public void setVideoScalingMode(int mode) {
        if (mode != VIDEO_SCALING_MODE_SCALE_TO_FIT && mode != VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            throw new IllegalArgumentException("bad scaling mode " + mode);
        synchronized (mLock) {
            mVideoScalingMode = mode;
        }
    }

    public void setOutputSurface(@NonNull Surface surface) {
        if (surface == null)
            throw new IllegalArgumentException("null surface");
        synchronized (mLock) {
            if (!mVideo || mState == STATE_UNINITIALIZED || mState == STATE_RELEASED)
                throw new IllegalStateException("setOutputSurface() needs a configured video decoder");
            mSurface = surface;
            native_setOutputSurface(mNative, surface);
        }
    }

    /** AOSP (API 35): stops rendering until a surface is set again. */
    public void detachOutputSurface() {
        synchronized (mLock) {
            if (!mVideo || mState == STATE_UNINITIALIZED || mState == STATE_RELEASED)
                throw new IllegalStateException("detachOutputSurface() needs a configured video decoder");
            mSurface = null;
            native_setOutputSurface(mNative, null);
        }
    }

    @NonNull
    public Surface createInputSurface() {
        throw new IllegalStateException(mName + " is a decoder; input surfaces belong to encoders");
    }

    public void setInputSurface(@NonNull Surface surface) {
        throw new IllegalStateException(mName + " is a decoder; input surfaces belong to encoders");
    }

    public final void signalEndOfInputStream() {
        throw new IllegalStateException(mName + " is a decoder; use BUFFER_FLAG_END_OF_STREAM");
    }

    @NonNull
    public final String getName() {
        return mName;
    }

    @NonNull
    public final String getCanonicalName() {
        return mName;
    }

    @NonNull
    public MediaCodecInfo getCodecInfo() {
        MediaCodecInfo info = MediaCodecList.getInfoFor(mName);
        if (info == null)
            throw new IllegalStateException("no info for " + mName);
        return info;
    }

    @Nullable
    public PersistableBundle getMetrics() {
        synchronized (mLock) {
            PersistableBundle b = new PersistableBundle();
            b.putString(MetricsConstants.CODEC, mName);
            b.putString(MetricsConstants.MIME_TYPE, MediaCodecList.tlMimeOf(mName));
            b.putString(MetricsConstants.MODE, mVideo ? MetricsConstants.MODE_VIDEO : MetricsConstants.MODE_AUDIO);
            b.putInt(MetricsConstants.ENCODER, 0);
            b.putInt(MetricsConstants.SECURE, 0);
            if (mVideo && mInputFormat != null) {
                if (mInputFormat.containsKey(MediaFormat.KEY_WIDTH))
                    b.putInt(MetricsConstants.WIDTH, mInputFormat.getInteger(MediaFormat.KEY_WIDTH));
                if (mInputFormat.containsKey(MediaFormat.KEY_HEIGHT))
                    b.putInt(MetricsConstants.HEIGHT, mInputFormat.getInteger(MediaFormat.KEY_HEIGHT));
                if (mInputFormat.containsKey(MediaFormat.KEY_ROTATION))
                    b.putInt(MetricsConstants.ROTATION, mInputFormat.getInteger(MediaFormat.KEY_ROTATION));
            }
            return b;
        }
    }

    /** AOSP (API 31): vendor parameters; FFmpeg decoders expose none. */
    @NonNull
    public List<String> getSupportedVendorParameters() {
        return new ArrayList<>();
    }

    /** AOSP (API 31) */
    public static final class ParameterDescriptor {
        private final String mName;
        private final int mType;

        ParameterDescriptor(String name, int type) {
            mName = name;
            mType = type;
        }

        @NonNull
        public String getName() {
            return mName;
        }

        public int getType() {
            return mType;
        }
    }

    @Nullable
    public ParameterDescriptor getParameterDescriptor(@NonNull String name) {
        return null; // no vendor parameters
    }

    public void subscribeToVendorParameters(@NonNull List<String> names) {
        synchronized (mLock) {
            if (mState == STATE_RELEASED)
                throw new IllegalStateException("codec released");
        }
        // none of the names are vendor parameters of an FFmpeg decoder: nothing to subscribe to
    }

    public void unsubscribeFromVendorParameters(@NonNull List<String> names) {
        synchronized (mLock) {
            if (mState == STATE_RELEASED)
                throw new IllegalStateException("codec released");
        }
    }
}
