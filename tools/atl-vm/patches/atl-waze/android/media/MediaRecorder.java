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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.hardware.Camera;
import android.os.PersistableBundle;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Used to record audio and video (AOSP android14 API; the constant classes are copied from AOSP).
 *
 * teslalinux: AOSP drives the native StagefrightRecorder in the media server. This class keeps
 * AOSP's state machine and argument checks (IllegalStateException in the wrong state) and what the
 * recorder does on this device: there is no capture hardware (no microphone: the Tesla browser
 * does not forward one, and the Pi has no audio input; no camera), so prepare() fails with
 * IOException("prepare failed.") as AOSP's does when its AudioSource / CameraSource cannot be
 * opened, and the recorder never starts.
 */
public class MediaRecorder implements AudioRouting {
    private final static String TAG = "MediaRecorder";

    // AOSP's native recorder states (mediarecorder.h)
    private static final int MEDIA_RECORDER_IDLE = 1 << 0;
    private static final int MEDIA_RECORDER_INITIALIZED = 1 << 1;
    private static final int MEDIA_RECORDER_DATASOURCE_CONFIGURED = 1 << 2;
    private static final int MEDIA_RECORDER_PREPARED = 1 << 3;
    private static final int MEDIA_RECORDER_RECORDING = 1 << 4;
    private static final int MEDIA_RECORDER_RELEASED = 1 << 5;

    private int mState = MEDIA_RECORDER_IDLE;
    private int mAudioSource = AudioSource.AUDIO_SOURCE_INVALID;
    private int mVideoSource = -1;
    private int mOutputFormat = OutputFormat.DEFAULT;
    private String mPath;
    private FileDescriptor mFd;
    private File mFile;
    private Surface mSurface;
    private OnErrorListener mOnErrorListener;
    private OnInfoListener mOnInfoListener;
    private AudioDeviceInfo mPreferredDevice;
    private boolean mPrivacySensitive;
    private boolean mPrivacySensitiveSet;

    /** Default constructor. @deprecated use {@link #MediaRecorder(Context)} */
    @Deprecated
    public MediaRecorder() {
    }

    public MediaRecorder(@NonNull Context context) {
        if (context == null) {
            throw new NullPointerException("context cannot be null");
        }
    }

    /** No camera on this device; android.hardware.Camera.open() never returns one to pass here. */
    @Deprecated
    public void setCamera(Camera c) {
        checkState(MEDIA_RECORDER_IDLE | MEDIA_RECORDER_INITIALIZED, "setCamera");
    }

    /** The persistent input surface needs a video encoder session, which prepare() never opens. */
    public Surface getSurface() {
        throw new IllegalStateException("getSurface: recorder is not prepared with a surface source");
    }

    public void setInputSurface(@NonNull Surface surface) {
        if (surface == null) {
            throw new IllegalArgumentException("surface must not be null");
        }
        checkState(MEDIA_RECORDER_DATASOURCE_CONFIGURED, "setInputSurface");
        mSurface = surface;
    }

    public void setPreviewDisplay(Surface sv) {
        mSurface = sv;
    }

    private void checkState(int allowed, String what) {
        if ((mState & allowed) == 0) {
            throw new IllegalStateException(what + " called in an invalid state: " + mState);
        }
    }

    /**
     * Defines the audio source.
     * An audio source defines both a default physical source of audio signal, and a recording
     * configuration. These constants are for instance used
     * in {@link MediaRecorder#setAudioSource(int)} or
     * {@link AudioRecord.Builder#setAudioSource(int)}.
     */
    public final class AudioSource {

        private AudioSource() {}

        /** @hide */
        public final static int AUDIO_SOURCE_INVALID = -1;

      /* Do not change these values without updating their counterparts
       * in system/media/audio/include/system/audio.h!
       */

        /** Default audio source **/
        public static final int DEFAULT = 0;

        /** Microphone audio source */
        public static final int MIC = 1;

        /** Voice call uplink (Tx) audio source.
         * <p>
         * Capturing from <code>VOICE_UPLINK</code> source requires the
         * {@link android.Manifest.permission#CAPTURE_AUDIO_OUTPUT} permission.
         * This permission is reserved for use by system components and is not available to
         * third-party applications.
         * </p>
         */
        public static final int VOICE_UPLINK = 2;

        /** Voice call downlink (Rx) audio source.
         * <p>
         * Capturing from <code>VOICE_DOWNLINK</code> source requires the
         * {@link android.Manifest.permission#CAPTURE_AUDIO_OUTPUT} permission.
         * This permission is reserved for use by system components and is not available to
         * third-party applications.
         * </p>
         */
        public static final int VOICE_DOWNLINK = 3;

        /** Voice call uplink + downlink audio source
         * <p>
         * Capturing from <code>VOICE_CALL</code> source requires the
         * {@link android.Manifest.permission#CAPTURE_AUDIO_OUTPUT} permission.
         * This permission is reserved for use by system components and is not available to
         * third-party applications.
         * </p>
         */
        public static final int VOICE_CALL = 4;

        /** Microphone audio source tuned for video recording, with the same orientation
         *  as the camera if available. */
        public static final int CAMCORDER = 5;

        /** Microphone audio source tuned for voice recognition. */
        public static final int VOICE_RECOGNITION = 6;

        /** Microphone audio source tuned for voice communications such as VoIP. It
         *  will for instance take advantage of echo cancellation or automatic gain control
         *  if available.
         */
        public static final int VOICE_COMMUNICATION = 7;

        /**
         * Audio source for a submix of audio streams to be presented remotely.
         * <p>
         * An application can use this audio source to capture a mix of audio streams
         * that should be transmitted to a remote receiver such as a Wifi display.
         * While recording is active, these audio streams are redirected to the remote
         * submix instead of being played on the device speaker or headset.
         * </p><p>
         * Certain streams are excluded from the remote submix, including
         * {@link AudioManager#STREAM_RING}, {@link AudioManager#STREAM_ALARM},
         * and {@link AudioManager#STREAM_NOTIFICATION}.  These streams will continue
         * to be presented locally as usual.
         * </p><p>
         * Capturing the remote submix audio requires the
         * {@link android.Manifest.permission#CAPTURE_AUDIO_OUTPUT} permission.
         * This permission is reserved for use by system components and is not available to
         * third-party applications.
         * </p>
         */
        public static final int REMOTE_SUBMIX = 8;

        /** Microphone audio source tuned for unprocessed (raw) sound if available, behaves like
         *  {@link #DEFAULT} otherwise. */
        public static final int UNPROCESSED = 9;


        /**
         * Source for capturing audio meant to be processed in real time and played back for live
         * performance (e.g karaoke).
         * <p>
         * The capture path will minimize latency and coupling with
         * playback path.
         * </p>
         */
        public static final int VOICE_PERFORMANCE = 10;


        /**
         * Source for an echo canceller to capture the reference signal to be cancelled.
         * <p>
         * The echo reference signal will be captured as close as possible to the DAC in order
         * to include all post processing applied to the playback path.
         * </p><p>
         * Capturing the echo reference requires the
         * {@link android.Manifest.permission#CAPTURE_AUDIO_OUTPUT} permission.
         * This permission is reserved for use by system components and is not available to
         * third-party applications.
         * </p>
         * @hide
         */
        /** @SystemApi */
        public static final int ECHO_REFERENCE = 1997;

        /**
         * Audio source for capturing broadcast radio tuner output.
         * Capturing the radio tuner output requires the
         * {@link android.Manifest.permission#CAPTURE_AUDIO_OUTPUT} permission.
         * This permission is reserved for use by system components and is not available to
         * third-party applications.
         * @hide
         */
        /** @SystemApi */
        public static final int RADIO_TUNER = 1998;

        /**
         * Audio source for preemptible, low-priority software hotword detection
         * It presents the same gain and pre-processing tuning as {@link #VOICE_RECOGNITION}.
         * <p>
         * An application should use this audio source when it wishes to do
         * always-on software hotword detection, while gracefully giving in to any other application
         * that might want to read from the microphone.
         * </p>
         * This is a hidden audio source.
         * @hide
         */
        /** @SystemApi */
        public static final int HOTWORD = 1999;

        /** Microphone audio source for ultrasound sound if available, behaves like
         *  {@link #DEFAULT} otherwise.
         * @hide
         */
        /** @SystemApi */
        public static final int ULTRASOUND = 2000;

    }

    /** @hide */
    @IntDef({
        AudioSource.DEFAULT,
        AudioSource.MIC,
        AudioSource.VOICE_UPLINK,
        AudioSource.VOICE_DOWNLINK,
        AudioSource.VOICE_CALL,
        AudioSource.CAMCORDER,
        AudioSource.VOICE_RECOGNITION,
        AudioSource.VOICE_COMMUNICATION,
        AudioSource.UNPROCESSED,
        AudioSource.VOICE_PERFORMANCE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Source {}

    /** @hide */
    @IntDef({
        AudioSource.DEFAULT,
        AudioSource.MIC,
        AudioSource.VOICE_UPLINK,
        AudioSource.VOICE_DOWNLINK,
        AudioSource.VOICE_CALL,
        AudioSource.CAMCORDER,
        AudioSource.VOICE_RECOGNITION,
        AudioSource.VOICE_COMMUNICATION,
        AudioSource.REMOTE_SUBMIX,
        AudioSource.UNPROCESSED,
        AudioSource.VOICE_PERFORMANCE,
        AudioSource.ECHO_REFERENCE,
        AudioSource.RADIO_TUNER,
        AudioSource.HOTWORD,
        AudioSource.ULTRASOUND,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SystemSource {}

    // TODO make AudioSource static (API change) and move this method inside the AudioSource class
    /**
     * @hide
     * @param source An audio source to test
     * @return true if the source is only visible to system components
     */
    public static boolean isSystemOnlyAudioSource(int source) {
        switch(source) {
            case AudioSource.DEFAULT:
            case AudioSource.MIC:
            case AudioSource.VOICE_UPLINK:
            case AudioSource.VOICE_DOWNLINK:
            case AudioSource.VOICE_CALL:
            case AudioSource.CAMCORDER:
            case AudioSource.VOICE_RECOGNITION:
            case AudioSource.VOICE_COMMUNICATION:
            //case REMOTE_SUBMIX:  considered "system" as it requires system permissions
            case AudioSource.UNPROCESSED:
            case AudioSource.VOICE_PERFORMANCE:
                return false;
            default:
                return true;
        }
    }

    /**
     * @hide
     * @param source An audio source to test
     * @return true if the source is a valid one
     */
    public static boolean isValidAudioSource(int source) {
        switch(source) {
            case AudioSource.MIC:
            case AudioSource.VOICE_UPLINK:
            case AudioSource.VOICE_DOWNLINK:
            case AudioSource.VOICE_CALL:
            case AudioSource.CAMCORDER:
            case AudioSource.VOICE_RECOGNITION:
            case AudioSource.VOICE_COMMUNICATION:
            case AudioSource.REMOTE_SUBMIX:
            case AudioSource.UNPROCESSED:
            case AudioSource.VOICE_PERFORMANCE:
            case AudioSource.ECHO_REFERENCE:
            case AudioSource.RADIO_TUNER:
            case AudioSource.HOTWORD:
            case AudioSource.ULTRASOUND:
                return true;
            default:
                return false;
        }
    }

    /** @hide */
    public static final String toLogFriendlyAudioSource(int source) {
        switch(source) {
            case AudioSource.DEFAULT:
                return "DEFAULT";
            case AudioSource.MIC:
                return "MIC";
            case AudioSource.VOICE_UPLINK:
                return "VOICE_UPLINK";
            case AudioSource.VOICE_DOWNLINK:
                return "VOICE_DOWNLINK";
            case AudioSource.VOICE_CALL:
                return "VOICE_CALL";
            case AudioSource.CAMCORDER:
                return "CAMCORDER";
            case AudioSource.VOICE_RECOGNITION:
                return "VOICE_RECOGNITION";
            case AudioSource.VOICE_COMMUNICATION:
                return "VOICE_COMMUNICATION";
            case AudioSource.REMOTE_SUBMIX:
                return "REMOTE_SUBMIX";
            case AudioSource.UNPROCESSED:
                return "UNPROCESSED";
            case AudioSource.ECHO_REFERENCE:
                return "ECHO_REFERENCE";
            case AudioSource.VOICE_PERFORMANCE:
                return "VOICE_PERFORMANCE";
            case AudioSource.RADIO_TUNER:
                return "RADIO_TUNER";
            case AudioSource.HOTWORD:
                return "HOTWORD";
            case AudioSource.ULTRASOUND:
                return "ULTRASOUND";
            case AudioSource.AUDIO_SOURCE_INVALID:
                return "AUDIO_SOURCE_INVALID";
            default:
                return "unknown source " + source;
        }
    }

    /**
     * Defines the video source. These constants are used with
     * {@link MediaRecorder#setVideoSource(int)}.
     */
    public final class VideoSource {
      /* Do not change these values without updating their counterparts
       * in include/media/mediarecorder.h!
       */
        private VideoSource() {}
        public static final int DEFAULT = 0;
        /** Camera video source
         * <p>
         * Using the {@link android.hardware.Camera} API as video source.
         * </p>
         */
        public static final int CAMERA = 1;
        /** Surface video source
         * <p>
         * Using a Surface as video source.
         * </p><p>
         * This flag must be used when recording from an
         * {@link android.hardware.camera2} API source.
         * </p><p>
         * When using this video source type, use {@link MediaRecorder#getSurface()}
         * to retrieve the surface created by MediaRecorder.
         */
        public static final int SURFACE = 2;
    }

    /**
     * Defines the output format. These constants are used with
     * {@link MediaRecorder#setOutputFormat(int)}.
     */
    public final class OutputFormat {
      /* Do not change these values without updating their counterparts
       * in include/media/mediarecorder.h!
       */
        private OutputFormat() {}
        public static final int DEFAULT = 0;
        /** 3GPP media file format*/
        public static final int THREE_GPP = 1;
        /** MPEG4 media file format*/
        public static final int MPEG_4 = 2;

        /** The following formats are audio only .aac or .amr formats */

        /**
         * AMR NB file format
         * @deprecated  Deprecated in favor of MediaRecorder.OutputFormat.AMR_NB
         */
        public static final int RAW_AMR = 3;

        /** AMR NB file format */
        public static final int AMR_NB = 3;

        /** AMR WB file format */
        public static final int AMR_WB = 4;

        /** @hide AAC ADIF file format */
        public static final int AAC_ADIF = 5;

        /** AAC ADTS file format */
        public static final int AAC_ADTS = 6;

        /** @hide Stream over a socket, limited to a single stream */
        public static final int OUTPUT_FORMAT_RTP_AVP = 7;

        /** H.264/AAC data encapsulated in MPEG2/TS */
        public static final int MPEG_2_TS = 8;

        /** VP8/VORBIS data in a WEBM container */
        public static final int WEBM = 9;

        /** @hide HEIC data in a HEIF container */
        public static final int HEIF = 10;

        /** Opus data in a Ogg container */
        public static final int OGG = 11;
    };

    /**
     * @hide
     */
    @IntDef({
        OutputFormat.DEFAULT,
        OutputFormat.THREE_GPP,
        OutputFormat.MPEG_4,
        OutputFormat.AMR_NB,
        OutputFormat.AMR_WB,
        OutputFormat.AAC_ADIF,
        OutputFormat.AAC_ADTS,
        OutputFormat.MPEG_2_TS,
        OutputFormat.WEBM,
        OutputFormat.HEIF,
        OutputFormat.OGG,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OutputFormatValues {}

    /**
     * Defines the audio encoding. These constants are used with
     * {@link MediaRecorder#setAudioEncoder(int)}.
     */
    public final class AudioEncoder {
      /* Do not change these values without updating their counterparts
       * in include/media/mediarecorder.h!
       */
        private AudioEncoder() {}
        public static final int DEFAULT = 0;
        /** AMR (Narrowband) audio codec */
        public static final int AMR_NB = 1;
        /** AMR (Wideband) audio codec */
        public static final int AMR_WB = 2;
        /** AAC Low Complexity (AAC-LC) audio codec */
        public static final int AAC = 3;
        /** High Efficiency AAC (HE-AAC) audio codec */
        public static final int HE_AAC = 4;
        /** Enhanced Low Delay AAC (AAC-ELD) audio codec */
        public static final int AAC_ELD = 5;
        /** Ogg Vorbis audio codec (Support is optional) */
        public static final int VORBIS = 6;
        /** Opus audio codec */
        public static final int OPUS = 7;
    }

    /**
     * @hide
     */
    @IntDef({
        AudioEncoder.DEFAULT,
        AudioEncoder.AMR_NB,
        AudioEncoder.AMR_WB,
        AudioEncoder.AAC,
        AudioEncoder.HE_AAC,
        AudioEncoder.AAC_ELD,
        AudioEncoder.VORBIS,
        AudioEncoder.OPUS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioEncoderValues {}

    /**
     * Defines the video encoding. These constants are used with
     * {@link MediaRecorder#setVideoEncoder(int)}.
     */
    public final class VideoEncoder {
      /* Do not change these values without updating their counterparts
       * in include/media/mediarecorder.h!
       */
        private VideoEncoder() {}
        public static final int DEFAULT = 0;
        public static final int H263 = 1;
        public static final int H264 = 2;
        public static final int MPEG_4_SP = 3;
        public static final int VP8 = 4;
        public static final int HEVC = 5;
        public static final int VP9 = 6;
        public static final int DOLBY_VISION = 7;
        public static final int AV1 = 8;
    }

    /**
     * @hide
     */
    @IntDef({
        VideoEncoder.DEFAULT,
        VideoEncoder.H263,
        VideoEncoder.H264,
        VideoEncoder.MPEG_4_SP,
        VideoEncoder.VP8,
        VideoEncoder.HEVC,
        VideoEncoder.VP9,
        VideoEncoder.DOLBY_VISION,
        VideoEncoder.AV1,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VideoEncoderValues {}

    public void setAudioSource(@Source int audioSource) throws IllegalStateException {
        checkState(MEDIA_RECORDER_IDLE | MEDIA_RECORDER_INITIALIZED, "setAudioSource");
        if (audioSource < AudioSource.DEFAULT
                || (audioSource > AudioSource.VOICE_PERFORMANCE && !isSystemOnlyAudioSource(audioSource))) {
            throw new IllegalArgumentException("Invalid audio source " + audioSource);
        }
        mAudioSource = audioSource;
        mState = MEDIA_RECORDER_INITIALIZED;
    }

    public static final int getAudioSourceMax() {
        return AudioSource.VOICE_PERFORMANCE;
    }

    public void setPrivacySensitive(boolean privacySensitive) {
        mPrivacySensitive = privacySensitive;
        mPrivacySensitiveSet = true;
    }

    public boolean isPrivacySensitive() {
        if (mPrivacySensitiveSet) {
            return mPrivacySensitive;
        }
        return mAudioSource == AudioSource.VOICE_COMMUNICATION
                || mAudioSource == AudioSource.CAMCORDER;
    }

    public void setVideoSource(int video_source) throws IllegalStateException {
        checkState(MEDIA_RECORDER_IDLE | MEDIA_RECORDER_INITIALIZED, "setVideoSource");
        if (video_source != VideoSource.DEFAULT && video_source != VideoSource.CAMERA
                && video_source != VideoSource.SURFACE) {
            throw new IllegalArgumentException("Invalid video source " + video_source);
        }
        mVideoSource = video_source;
        mState = MEDIA_RECORDER_INITIALIZED;
    }

    public void setCaptureRate(double fps) {
        checkState(MEDIA_RECORDER_DATASOURCE_CONFIGURED, "setCaptureRate");
    }

    public void setOrientationHint(int degrees) {
        if (degrees != 0 && degrees != 90 && degrees != 180 && degrees != 270) {
            throw new IllegalArgumentException("Unsupported angle: " + degrees);
        }
    }

    public void setLocation(float latitude, float longitude) {
        int latitudex10000 = (int) (latitude * 10000 + 0.5);
        int longitudex10000 = (int) (longitude * 10000 + 0.5);
        if (latitudex10000 > 900000 || latitudex10000 < -900000) {
            throw new IllegalArgumentException("Latitude: " + latitude + " out of range.");
        }
        if (longitudex10000 > 1800000 || longitudex10000 < -1800000) {
            throw new IllegalArgumentException("Longitude: " + longitude + " out of range");
        }
    }

    public void setOutputFormat(int output_format) throws IllegalStateException {
        checkState(MEDIA_RECORDER_INITIALIZED, "setOutputFormat");
        if (output_format < OutputFormat.DEFAULT || output_format > OutputFormat.OGG) {
            throw new IllegalArgumentException("Invalid output format " + output_format);
        }
        mOutputFormat = output_format;
        mState = MEDIA_RECORDER_DATASOURCE_CONFIGURED;
    }

    public void setVideoSize(int width, int height) throws IllegalStateException {
        checkState(MEDIA_RECORDER_DATASOURCE_CONFIGURED, "setVideoSize");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid video size " + width + "x" + height);
        }
    }

    public void setVideoFrameRate(int rate) throws IllegalStateException {
        checkState(MEDIA_RECORDER_DATASOURCE_CONFIGURED, "setVideoFrameRate");
        if (rate <= 0) {
            throw new IllegalArgumentException("Invalid video frame rate " + rate);
        }
    }

    public void setMaxDuration(int max_duration_ms) throws IllegalArgumentException {
        checkState(MEDIA_RECORDER_DATASOURCE_CONFIGURED | MEDIA_RECORDER_INITIALIZED, "setMaxDuration");
    }

    public void setMaxFileSize(long max_filesize_bytes) throws IllegalArgumentException {
        checkState(MEDIA_RECORDER_DATASOURCE_CONFIGURED | MEDIA_RECORDER_INITIALIZED, "setMaxFileSize");
    }

    public void setAudioEncoder(int audio_encoder) throws IllegalStateException {
        checkState(MEDIA_RECORDER_DATASOURCE_CONFIGURED, "setAudioEncoder");
        if (audio_encoder < AudioEncoder.DEFAULT || audio_encoder > AudioEncoder.OPUS) {
            throw new IllegalArgumentException("Invalid audio encoder " + audio_encoder);
        }
    }

    public void setVideoEncoder(int video_encoder) throws IllegalStateException {
        checkState(MEDIA_RECORDER_DATASOURCE_CONFIGURED, "setVideoEncoder");
        if (video_encoder < VideoEncoder.DEFAULT || video_encoder > VideoEncoder.AV1) {
            throw new IllegalArgumentException("Invalid video encoder " + video_encoder);
        }
    }

    public void setAudioSamplingRate(int samplingRate) {
        if (samplingRate <= 0) {
            throw new IllegalArgumentException("Audio sampling rate is not positive");
        }
    }

    public void setAudioChannels(int numChannels) {
        if (numChannels <= 0) {
            throw new IllegalArgumentException("Number of channels is not positive");
        }
    }

    public void setAudioEncodingBitRate(int bitRate) {
        if (bitRate <= 0) {
            throw new IllegalArgumentException("Audio encoding bit rate is not positive");
        }
    }

    public void setVideoEncodingBitRate(int bitRate) {
        if (bitRate <= 0) {
            throw new IllegalArgumentException("Video encoding bit rate is not positive");
        }
    }

    public void setVideoEncodingProfileLevel(int profile, int level) {
        if (profile <= 0) {
            throw new IllegalArgumentException("Video encoding profile is not positive");
        }
        if (level <= 0) {
            throw new IllegalArgumentException("Video encoding level is not positive");
        }
    }

    public void setOutputFile(FileDescriptor fd) throws IllegalStateException {
        mPath = null;
        mFile = null;
        mFd = fd;
    }

    public void setOutputFile(File file) {
        mPath = null;
        mFd = null;
        mFile = file;
    }

    public void setNextOutputFile(FileDescriptor fd) throws IOException {
        throw new IllegalStateException("setNextOutputFile: the recorder is not recording");
    }

    public void setOutputFile(String path) throws IllegalStateException {
        mFd = null;
        mFile = null;
        mPath = path;
    }

    public void setNextOutputFile(File file) throws IOException {
        throw new IllegalStateException("setNextOutputFile: the recorder is not recording");
    }

    /** True when ALSA lists a capture PCM (/proc/asound/pcm "capture" entries). */
    private static boolean hasCaptureDevice() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/asound/pcm"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("capture"))
                    return true;
            }
        } catch (IOException e) {
        }
        return false;
    }

    public void prepare() throws IllegalStateException, IOException {
        checkState(MEDIA_RECORDER_DATASOURCE_CONFIGURED, "prepare");
        if (mPath == null && mFd == null && mFile == null) {
            throw new IOException("No valid output file");
        }
        if (mVideoSource == VideoSource.CAMERA || mVideoSource == VideoSource.DEFAULT) {
            // AOSP: the CameraSource cannot connect to a camera
            throw new IOException("prepare failed.");
        }
        if (mAudioSource != AudioSource.AUDIO_SOURCE_INVALID) {
            // AOSP: StagefrightRecorder's AudioSource fails initCheck() when the input cannot open
            if (hasCaptureDevice()) {
                Log.w(TAG, "a capture device exists, but this runtime has no recording pipeline");
            }
            throw new IOException("prepare failed.");
        }
        // only a surface video source would remain: it needs a video encoder session
        throw new IOException("prepare failed.");
    }

    public void start() throws IllegalStateException {
        checkState(MEDIA_RECORDER_PREPARED, "start");
        mState = MEDIA_RECORDER_RECORDING;
    }

    public void stop() throws IllegalStateException {
        checkState(MEDIA_RECORDER_RECORDING, "stop");
        mState = MEDIA_RECORDER_IDLE;
    }

    public void pause() throws IllegalStateException {
        checkState(MEDIA_RECORDER_RECORDING, "pause");
    }

    public void resume() throws IllegalStateException {
        checkState(MEDIA_RECORDER_RECORDING, "resume");
    }

    public void reset() {
        mState = MEDIA_RECORDER_IDLE;
        mAudioSource = AudioSource.AUDIO_SOURCE_INVALID;
        mVideoSource = -1;
        mOutputFormat = OutputFormat.DEFAULT;
        mPath = null;
        mFd = null;
        mFile = null;
        mSurface = null;
    }

    public void release() {
        reset();
        mState = MEDIA_RECORDER_RELEASED;
    }

    /** AOSP: 0 until recording produced a sample; IllegalStateException without an audio source. */
    public int getMaxAmplitude() throws IllegalStateException {
        if (mAudioSource == AudioSource.AUDIO_SOURCE_INVALID) {
            throw new IllegalStateException("getMaxAmplitude called before setAudioSource");
        }
        return 0;
    }

    // ---- AudioRouting: the recorder would capture from the input device, of which there is none ----

    @Override
    public boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
        if (deviceInfo != null && !deviceInfo.isSource()) {
            return false;
        }
        mPreferredDevice = deviceInfo;
        return true;
    }

    @Override
    public AudioDeviceInfo getPreferredDevice() {
        return mPreferredDevice;
    }

    /** AOSP: null while not recording. */
    @Override
    public AudioDeviceInfo getRoutedDevice() {
        return null;
    }

    private final ArrayList<AudioRouting.OnRoutingChangedListener> mRoutingListeners = new ArrayList<>();

    @Override
    public void addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener, android.os.Handler handler) {
        synchronized (mRoutingListeners) {
            if (listener != null && !mRoutingListeners.contains(listener)) {
                mRoutingListeners.add(listener);
            }
        }
    }

    @Override
    public void removeOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener) {
        synchronized (mRoutingListeners) {
            mRoutingListeners.remove(listener);
        }
    }

    /** AOSP: the active configuration while recording, else null. */
    public AudioRecordingConfiguration getActiveRecordingConfiguration() {
        return null;
    }

    /** AOSP: the input microphones in use while recording; none here. */
    public List<Object> getActiveMicrophones() throws IOException {
        return new ArrayList<>();
    }

    /** AOSP: a metrics bundle of the current recording session (empty without one). */
    public PersistableBundle getMetrics() {
        return new PersistableBundle();
    }

    /* Do not change this value without updating its counterpart
     * in include/media/mediarecorder.h or mediaplayer.h!
     */
    /** Unspecified media recorder error.
     * @see android.media.MediaRecorder.OnErrorListener
     */
    public static final int MEDIA_RECORDER_ERROR_UNKNOWN = 1;
    /** Media server died. In this case, the application must release the
     * MediaRecorder object and instantiate a new one.
     * @see android.media.MediaRecorder.OnErrorListener
     */
    public static final int MEDIA_ERROR_SERVER_DIED = 100;

    /**
     * Interface definition for a callback to be invoked when an error
     * occurs while recording.
     */
    public interface OnErrorListener
    {
        /**
         * Called when an error occurs while recording.
         *
         * @param mr the MediaRecorder that encountered the error
         * @param what    the type of error that has occurred:
         * <ul>
         * <li>{@link #MEDIA_RECORDER_ERROR_UNKNOWN}
         * <li>{@link #MEDIA_ERROR_SERVER_DIED}
         * </ul>
         * @param extra   an extra code, specific to the error type
         */
        void onError(MediaRecorder mr, int what, int extra);
    }

    /**
     * Register a callback to be invoked when an error occurs while
     * recording.
     *
     * @param l the callback that will be run
     */
    public void setOnErrorListener(OnErrorListener l)
    {
        mOnErrorListener = l;
    }

    /* Do not change these values without updating their counterparts
     * in include/media/mediarecorder.h!
     */
    /** Unspecified media recorder info.
     * @see android.media.MediaRecorder.OnInfoListener
     */
    public static final int MEDIA_RECORDER_INFO_UNKNOWN              = 1;
    /** A maximum duration had been setup and has now been reached.
     * @see android.media.MediaRecorder.OnInfoListener
     */
    public static final int MEDIA_RECORDER_INFO_MAX_DURATION_REACHED = 800;
    /** A maximum filesize had been setup and has now been reached.
     * Note: This event will not be sent if application already set
     * next output file through {@link #setNextOutputFile}.
     * @see android.media.MediaRecorder.OnInfoListener
     */
    public static final int MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED = 801;
    /** A maximum filesize had been setup and current recorded file size
     * has reached 90% of the limit. This is sent once per file upon
     * reaching/passing the 90% limit. To continue the recording, applicaiton
     * should use {@link #setNextOutputFile} to set the next output file.
     * Otherwise, recording will stop when reaching maximum file size.
     * @see android.media.MediaRecorder.OnInfoListener
     */
    public static final int MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING = 802;
    /** A maximum filesize had been reached and MediaRecorder has switched
     * output to a new file set by application {@link #setNextOutputFile}.
     * For best practice, application should use this event to keep track
     * of whether the file previously set has been used or not.
     * @see android.media.MediaRecorder.OnInfoListener
     */
    public static final int MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED = 803;

    /** informational events for individual tracks, for testing purpose.
     * The track informational event usually contains two parts in the ext1
     * arg of the onInfo() callback: bit 31-28 contains the track id; and
     * the rest of the 28 bits contains the informational event defined here.
     * For example, ext1 = (1 << 28 | MEDIA_RECORDER_TRACK_INFO_TYPE) if the
     * track id is 1 for informational event MEDIA_RECORDER_TRACK_INFO_TYPE;
     * while ext1 = (0 << 28 | MEDIA_RECORDER_TRACK_INFO_TYPE) if the track
     * id is 0 for informational event MEDIA_RECORDER_TRACK_INFO_TYPE. The
     * application should extract the track id and the type of informational
     * event from ext1, accordingly.
     *
     * FIXME:
     * Please update the comment for onInfo also when these
     * events are unhidden so that application knows how to extract the track
     * id and the informational event type from onInfo callback.
     *
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_LIST_START        = 1000;
    /** Signal the completion of the track for the recording session.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_COMPLETION_STATUS = 1000;
    /** Indicate the recording progress in time (ms) during recording.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_PROGRESS_IN_TIME  = 1001;
    /** Indicate the track type: 0 for Audio and 1 for Video.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_TYPE              = 1002;
    /** Provide the track duration information.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_DURATION_MS       = 1003;
    /** Provide the max chunk duration in time (ms) for the given track.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_MAX_CHUNK_DUR_MS  = 1004;
    /** Provide the total number of recordd frames.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_ENCODED_FRAMES    = 1005;
    /** Provide the max spacing between neighboring chunks for the given track.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INTER_CHUNK_TIME_MS    = 1006;
    /** Provide the elapsed time measuring from the start of the recording
     * till the first output frame of the given track is received, excluding
     * any intentional start time offset of a recording session for the
     * purpose of eliminating the recording sound in the recorded file.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_INITIAL_DELAY_MS  = 1007;
    /** Provide the start time difference (delay) betweeen this track and
     * the start of the movie.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_START_OFFSET_MS   = 1008;
    /** Provide the total number of data (in kilo-bytes) encoded.
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_DATA_KBYTES       = 1009;
    /**
     * {@hide}
     */
    public static final int MEDIA_RECORDER_TRACK_INFO_LIST_END          = 2000;


    /**
     * Interface definition of a callback to be invoked to communicate some
     * info and/or warning about the recording.
     */
    public interface OnInfoListener
    {
        /**
         * Called to indicate an info or a warning during recording.
         *
         * @param mr   the MediaRecorder the info pertains to
         * @param what the type of info or warning that has occurred
         * <ul>
         * <li>{@link #MEDIA_RECORDER_INFO_UNKNOWN}
         * <li>{@link #MEDIA_RECORDER_INFO_MAX_DURATION_REACHED}
         * <li>{@link #MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED}
         * </ul>
         * @param extra   an extra code, specific to the info type
         */
        void onInfo(MediaRecorder mr, int what, int extra);
    }

    /**
     * Register a callback to be invoked when an informational event occurs while
     * recording.
     *
     * @param listener the callback that will be run
     */
    public void setOnInfoListener(OnInfoListener listener)
    {
        mOnInfoListener = listener;
    }
    public final static class MetricsConstants
    {
        private MetricsConstants() {}

        /**
         * Key to extract the audio bitrate
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String AUDIO_BITRATE = "android.media.mediarecorder.audio-bitrate";

        /**
         * Key to extract the number of audio channels
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String AUDIO_CHANNELS = "android.media.mediarecorder.audio-channels";

        /**
         * Key to extract the audio samplerate
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String AUDIO_SAMPLERATE = "android.media.mediarecorder.audio-samplerate";

        /**
         * Key to extract the audio timescale
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String AUDIO_TIMESCALE = "android.media.mediarecorder.audio-timescale";

        /**
         * Key to extract the video capture frame rate
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is a double.
         */
        public static final String CAPTURE_FPS = "android.media.mediarecorder.capture-fps";

        /**
         * Key to extract the video capture framerate enable value
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String CAPTURE_FPS_ENABLE = "android.media.mediarecorder.capture-fpsenable";

        /**
         * Key to extract the intended playback frame rate
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String FRAMERATE = "android.media.mediarecorder.frame-rate";

        /**
         * Key to extract the height (in pixels) of the captured video
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String HEIGHT = "android.media.mediarecorder.height";

        /**
         * Key to extract the recorded movies time units
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         * A value of 1000 indicates that the movie's timing is in milliseconds.
         */
        public static final String MOVIE_TIMESCALE = "android.media.mediarecorder.movie-timescale";

        /**
         * Key to extract the rotation (in degrees) to properly orient the video
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String ROTATION = "android.media.mediarecorder.rotation";

        /**
         * Key to extract the video bitrate from being used
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String VIDEO_BITRATE = "android.media.mediarecorder.video-bitrate";

        /**
         * Key to extract the value for how often video iframes are generated
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String VIDEO_IFRAME_INTERVAL = "android.media.mediarecorder.video-iframe-interval";

        /**
         * Key to extract the video encoding level
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String VIDEO_LEVEL = "android.media.mediarecorder.video-encoder-level";

        /**
         * Key to extract the video encoding profile
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String VIDEO_PROFILE = "android.media.mediarecorder.video-encoder-profile";

        /**
         * Key to extract the recorded video time units
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         * A value of 1000 indicates that the video's timing is in milliseconds.
         */
        public static final String VIDEO_TIMESCALE = "android.media.mediarecorder.video-timescale";

        /**
         * Key to extract the width (in pixels) of the captured video
         * from the {@link MediaRecorder#getMetrics} return.
         * The value is an integer.
         */
        public static final String WIDTH = "android.media.mediarecorder.width";

    }
}
