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

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * MediaPlayer class can be used to control playback of audio/video files and streams (AOSP API).
 *
 * teslalinux: playback is ATL's GtkMediaStream (GStreamer) behind the natives; this class keeps
 * AOSP's state machine (IllegalStateException in the wrong state, error callbacks), the
 * listeners, and applies the player volume times the stream volume of its audio attributes
 * (AudioManager), as AOSP's mixer does. File-descriptor sources with an offset (raw resources
 * inside the APK) are copied to a cache file first, because GtkMediaStream plays whole files.
 */
public class MediaPlayer implements AudioRouting {
    private static final String TAG = "MediaPlayer";

    public static final int MEDIA_ERROR_UNKNOWN = 1;
    public static final int MEDIA_ERROR_SERVER_DIED = 100;
    public static final int MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK = 200;
    public static final int MEDIA_ERROR_IO = -1004;
    public static final int MEDIA_ERROR_MALFORMED = -1007;
    public static final int MEDIA_ERROR_UNSUPPORTED = -1010;
    public static final int MEDIA_ERROR_TIMED_OUT = -110;

    public static final int MEDIA_INFO_UNKNOWN = 1;
    public static final int MEDIA_INFO_VIDEO_RENDERING_START = 3;
    public static final int MEDIA_INFO_BUFFERING_START = 701;
    public static final int MEDIA_INFO_BUFFERING_END = 702;
    public static final int MEDIA_INFO_NOT_SEEKABLE = 801;

    public static final int SEEK_PREVIOUS_SYNC = 0x00;
    public static final int SEEK_NEXT_SYNC = 0x01;
    public static final int SEEK_CLOSEST_SYNC = 0x02;
    public static final int SEEK_CLOSEST = 0x03;

    public interface OnCompletionListener {
        void onCompletion(MediaPlayer mp);
    }

    public interface OnErrorListener {
        boolean onError(MediaPlayer mp, int what, int extra);
    }

    public interface OnPreparedListener {
        void onPrepared(MediaPlayer mp);
    }

    public interface OnBufferingUpdateListener {
        void onBufferingUpdate(MediaPlayer mp, int percent);
    }

    public interface OnInfoListener {
        boolean onInfo(MediaPlayer mp, int what, int extra);
    }

    public interface OnSeekCompleteListener {
        void onSeekComplete(MediaPlayer mp);
    }

    public interface OnVideoSizeChangedListener {
        void onVideoSizeChanged(MediaPlayer mp, int width, int height);
    }

    // AOSP mediaplayer.h states
    private static final int STATE_ERROR = 0;
    private static final int STATE_IDLE = 1 << 0;
    private static final int STATE_INITIALIZED = 1 << 1;
    private static final int STATE_PREPARING = 1 << 2;
    private static final int STATE_PREPARED = 1 << 3;
    private static final int STATE_STARTED = 1 << 4;
    private static final int STATE_PAUSED = 1 << 5;
    private static final int STATE_STOPPED = 1 << 6;
    private static final int STATE_PLAYBACK_COMPLETE = 1 << 7;
    private static final int STATE_END = 1 << 8;

    private long gtk_media_stream;
    private int mState = STATE_IDLE;
    private final Handler mHandler = new Handler(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper());
    private OnCompletionListener mOnCompletionListener;
    private OnErrorListener mOnErrorListener;
    private OnPreparedListener mOnPreparedListener;
    private OnBufferingUpdateListener mOnBufferingUpdateListener;
    private OnInfoListener mOnInfoListener;
    private OnSeekCompleteListener mOnSeekCompleteListener;
    private OnVideoSizeChangedListener mOnVideoSizeChangedListener;
    private AudioAttributes mAudioAttributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
    private float mLeftVolume = 1f, mRightVolume = 1f;
    private boolean mLooping;
    private int mSessionId;
    private File mTempCopy;
    private AudioDeviceInfo mPreferredDevice;

    public MediaPlayer() {
    }

    public MediaPlayer(Context context) {
    }

    public static MediaPlayer create(Context context, Uri uri) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(context, uri);
            mp.prepare();
            return mp;
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException ex) {
            Log.d(TAG, "create failed:", ex);
        }
        return null;
    }

    public static MediaPlayer create(Context context, int resid) {
        try {
            AssetFileDescriptor afd = context.getResources().openRawResourceFd(resid);
            if (afd == null)
                return null;
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.prepare();
            return mp;
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException ex) {
            Log.d(TAG, "create failed:", ex);
        }
        return null;
    }

    private void checkState(int allowed, String what) {
        if ((mState & allowed) == 0)
            throw new IllegalStateException(what + " called in state " + mState);
    }

    // ---- data sources ----

    public void setDataSource(String path) throws IOException, IllegalArgumentException, IllegalStateException {
        checkState(STATE_IDLE, "setDataSource");
        if (path == null)
            throw new IllegalArgumentException("path is null");
        if (path.startsWith("file://"))
            path = Uri.parse(path).getPath();
        if (!path.contains("://") && !new File(path).canRead())
            throw new IOException("setDataSource failed.: status=0x80000000");
        gtk_media_stream = native_setDataSource(path);
        // one native "ended" hook per stream; it drives the state machine and the app's listener
        native_setOnCompletionListener(gtk_media_stream, new OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    onNativeCompletion();
                }
            });
        mState = STATE_INITIALIZED;
    }

    public void setDataSource(Context context, Uri uri) throws IOException {
        setDataSource(context, uri, null);
    }

    public void setDataSource(Context context, Uri uri, Map<String, String> headers) throws IOException {
        String scheme = uri.getScheme();
        if (scheme == null || "file".equals(scheme)) {
            setDataSource(uri.getPath());
            return;
        }
        if ("http".equals(scheme) || "https".equals(scheme)) {
            setDataSource(uri.toString());
            return;
        }
        AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r");
        if (afd == null)
            throw new IOException("Unable to open " + uri);
        try {
            setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        } finally {
            afd.close();
        }
    }

    public void setDataSource(AssetFileDescriptor afd) throws IOException {
        setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
    }

    public void setDataSource(FileDescriptor fd) throws IOException {
        setDataSource(fd, 0, 0x7ffffffffffffffL);
    }

    public void setDataSource(FileDescriptor fd, long offset, long length) throws IOException {
        if (fd == null || !fd.valid())
            throw new IllegalArgumentException("invalid file descriptor");
        String path = "/proc/self/fd/" + fd.getInt$();
        if (offset == 0 && length >= new File(path).length()) {
            setDataSource(path);
            return;
        }
        // a range of a larger file (a raw resource in the APK): copy it out
        File dir = new File(System.getProperty("java.io.tmpdir", "/tmp"));
        File tmp = File.createTempFile("mediaplayer", ".media", dir);
        tmp.deleteOnExit();
        try (FileInputStream in = new FileInputStream(path); FileOutputStream out = new FileOutputStream(tmp)) {
            in.getChannel().position(offset);
            byte[] buf = new byte[65536];
            long left = length;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0)
                    break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
        mTempCopy = tmp;
        setDataSource(tmp.getPath());
    }

    // ---- preparation and transport ----

    public void prepare() throws IOException, IllegalStateException {
        checkState(STATE_INITIALIZED | STATE_STOPPED, "prepare");
        native_prepare(gtk_media_stream);
        native_setLooping(gtk_media_stream, mLooping);
        mState = STATE_PREPARED;
    }

    public void prepareAsync() throws IllegalStateException {
        checkState(STATE_INITIALIZED | STATE_STOPPED, "prepareAsync");
        mState = STATE_PREPARING;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mState != STATE_PREPARING)
                    return;
                native_prepare(gtk_media_stream);
                native_setLooping(gtk_media_stream, mLooping);
                mState = STATE_PREPARED;
                if (mOnPreparedListener != null)
                    mOnPreparedListener.onPrepared(MediaPlayer.this);
            }
        });
    }

    public void start() throws IllegalStateException {
        checkState(STATE_PREPARED | STATE_STARTED | STATE_PAUSED | STATE_PLAYBACK_COMPLETE, "start");
        native_start(gtk_media_stream);
        applyVolume();
        mState = STATE_STARTED;
    }

    public void stop() throws IllegalStateException {
        checkState(STATE_PREPARED | STATE_STARTED | STATE_STOPPED | STATE_PAUSED | STATE_PLAYBACK_COMPLETE, "stop");
        native_pause(gtk_media_stream);
        native_seekTo(gtk_media_stream, 0);
        mState = STATE_STOPPED;
    }

    public void pause() throws IllegalStateException {
        checkState(STATE_STARTED | STATE_PAUSED | STATE_PLAYBACK_COMPLETE, "pause");
        native_pause(gtk_media_stream);
        mState = STATE_PAUSED;
    }

    public boolean isPlaying() {
        if (mState == STATE_END)
            throw new IllegalStateException("isPlaying called after release");
        return mState == STATE_STARTED && gtk_media_stream != 0 && native_isPlaying(gtk_media_stream);
    }

    public void seekTo(int msec) throws IllegalStateException {
        seekTo((long) msec, SEEK_PREVIOUS_SYNC);
    }

    public void seekTo(long msec, int mode) {
        checkState(STATE_PREPARED | STATE_STARTED | STATE_PAUSED | STATE_PLAYBACK_COMPLETE, "seekTo");
        native_seekTo(gtk_media_stream, Math.max(0, msec));
        if (mOnSeekCompleteListener != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mOnSeekCompleteListener != null)
                        mOnSeekCompleteListener.onSeekComplete(MediaPlayer.this);
                }
            });
        }
    }

    public int getDuration() {
        if ((mState & (STATE_PREPARED | STATE_STARTED | STATE_PAUSED | STATE_STOPPED | STATE_PLAYBACK_COMPLETE)) == 0)
            return -1; // AOSP: unknown before preparation
        return native_getDuration(gtk_media_stream);
    }

    public int getCurrentPosition() {
        if (gtk_media_stream == 0)
            return 0;
        return native_getCurrentPosition(gtk_media_stream);
    }

    /** No video output here: an audio-only stream reports 0 x 0, as AOSP does. */
    public int getVideoWidth() {
        return 0;
    }

    public int getVideoHeight() {
        return 0;
    }

    public void reset() {
        if (gtk_media_stream != 0) {
            native_release(gtk_media_stream);
            gtk_media_stream = 0;
        }
        mHandler.removeCallbacksAndMessages(null);
        deleteTempCopy();
        mState = STATE_IDLE;
    }

    public void release() {
        reset();
        mOnCompletionListener = null;
        mOnErrorListener = null;
        mOnPreparedListener = null;
        mOnBufferingUpdateListener = null;
        mOnInfoListener = null;
        mOnSeekCompleteListener = null;
        mOnVideoSizeChangedListener = null;
        mState = STATE_END;
    }

    private void deleteTempCopy() {
        if (mTempCopy != null) {
            mTempCopy.delete();
            mTempCopy = null;
        }
    }

    /** Called (on the GTK main thread) when the stream ended. */
    private void onNativeCompletion() {
        if (mLooping)
            return;
        mState = STATE_PLAYBACK_COMPLETE;
        if (mOnCompletionListener != null)
            mOnCompletionListener.onCompletion(this);
    }

    // ---- parameters ----

    public void setLooping(boolean looping) {
        mLooping = looping;
        if (gtk_media_stream != 0)
            native_setLooping(gtk_media_stream, looping);
    }

    public boolean isLooping() {
        return mLooping;
    }

    public void setVolume(float leftVolume, float rightVolume) {
        mLeftVolume = Math.max(0f, Math.min(1f, leftVolume));
        mRightVolume = Math.max(0f, Math.min(1f, rightVolume));
        applyVolume();
    }

    public void setVolume(float volume) {
        setVolume(volume, volume);
    }

    /** The player volume times the stream volume of the attributes (AudioManager's curve). */
    private void applyVolume() {
        if (gtk_media_stream == 0 || mState == STATE_IDLE)
            return;
        float gain = Math.max(mLeftVolume, mRightVolume) * AudioManager.tlStreamGain(mAudioAttributes.getVolumeControlStream());
        native_setVolume(gtk_media_stream, gain);
    }

    public void setAudioAttributes(AudioAttributes attributes) throws IllegalArgumentException {
        if (attributes == null)
            throw new IllegalArgumentException("Cannot set AudioAttributes to null");
        mAudioAttributes = attributes;
        applyVolume();
    }

    @Deprecated
    public void setAudioStreamType(int streamtype) {
        mAudioAttributes = new AudioAttributes.Builder().setInternalLegacyStreamType(streamtype).build();
        applyVolume();
    }

    public void setAudioSessionId(int sessionId) throws IllegalArgumentException, IllegalStateException {
        checkState(STATE_IDLE, "setAudioSessionId");
        mSessionId = sessionId;
    }

    public int getAudioSessionId() {
        if (mSessionId == 0)
            mSessionId = AudioSystem.newAudioSessionId();
        return mSessionId;
    }

    /** AOSP: a wake lock held while playing; this device does not sleep, so there is none to take. */
    public void setWakeMode(Context context, int mode) {
    }

    /** AOSP: keeps the screen on through the SurfaceHolder; there is no video surface here. */
    public void setScreenOnWhilePlaying(boolean screenOn) {
    }

    // ---- listeners ----

    public void setOnCompletionListener(OnCompletionListener listener) {
        mOnCompletionListener = listener;
    }

    public void setOnErrorListener(OnErrorListener listener) {
        mOnErrorListener = listener;
    }

    public void setOnPreparedListener(OnPreparedListener listener) {
        mOnPreparedListener = listener;
    }

    public void setOnBufferingUpdateListener(OnBufferingUpdateListener listener) {
        mOnBufferingUpdateListener = listener;
    }

    public void setOnInfoListener(OnInfoListener listener) {
        mOnInfoListener = listener;
    }

    public void setOnSeekCompleteListener(OnSeekCompleteListener listener) {
        mOnSeekCompleteListener = listener;
    }

    public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener listener) {
        mOnVideoSizeChangedListener = listener;
    }

    // ---- AudioRouting ----

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
        return mState == STATE_STARTED ? AudioManager.tlDefaultOutputDevice() : null;
    }

    @Override
    public void addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener, Handler handler) {
        // the output never changes (one host output), so no routing change will be reported
    }

    @Override
    public void removeOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener listener) {
    }

    @Override
    protected void finalize() {
        if (mState != STATE_END)
            release();
    }

    public static native void native_prepare(long gtk_media_stream);
    public native long native_setDataSource(String path);
    public static native void native_setOnCompletionListener(long gtk_media_stream, MediaPlayer.OnCompletionListener listener);
    public static native void native_start(long gtk_media_stream);
    public static native int native_getDuration(long gtk_media_stream);
    public static native int native_getCurrentPosition(long gtk_media_stream);
    // teslalinux natives (native-media-mediaplayer.sh)
    private static native void native_pause(long gtk_media_stream);
    private static native void native_seekTo(long gtk_media_stream, long msec);
    private static native void native_setVolume(long gtk_media_stream, float volume);
    private static native void native_setLooping(long gtk_media_stream, boolean looping);
    private static native boolean native_isPlaying(long gtk_media_stream);
    private static native void native_release(long gtk_media_stream);
}
