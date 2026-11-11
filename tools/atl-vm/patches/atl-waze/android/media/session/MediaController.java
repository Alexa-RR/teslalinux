/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.session;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.VolumeProvider;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Allows an app to interact with an ongoing media session (AOSP API).
 *
 * teslalinux: the controller talks to the in-process {@link MediaSession} its token refers to
 * (see MediaSession): commands go to the session's Callback on the callback's handler, and the
 * session's state changes come back to the registered {@link Callback}s on their handlers.
 */
public final class MediaController {
    private static final String TAG = "MediaController";

    private final Context mContext;
    private final MediaSession.Token mToken;
    private final MediaSession mSession;
    private final TransportControls mTransportControls;
    private final ArrayList<CallbackStub> mCallbacks = new ArrayList<>();
    private final MediaSessionManager.RemoteUserInfo mSelf;

    public MediaController(@NonNull Context context, @NonNull MediaSession.Token token) {
        if (context == null) {
            throw new IllegalArgumentException("context shouldn't be null");
        }
        if (token == null) {
            throw new IllegalArgumentException("token shouldn't be null");
        }
        if (token.getSession() == null) {
            throw new IllegalArgumentException("Session token has no session");
        }
        mContext = context;
        mToken = token;
        mSession = token.getSession();
        mTransportControls = new TransportControls();
        mSelf = new MediaSessionManager.RemoteUserInfo(context.getPackageName(),
                android.os.Process.myPid(), android.os.Process.myUid());
    }

    public @NonNull TransportControls getTransportControls() {
        return mTransportControls;
    }

    public boolean dispatchMediaButtonEvent(@NonNull KeyEvent keyEvent) {
        if (keyEvent == null) {
            throw new IllegalArgumentException("KeyEvent may not be null");
        }
        if (!isMediaSessionKey(keyEvent.getKeyCode())) {
            return false;
        }
        if (mSession.isReleased())
            return false;
        return mSession.dispatchMediaButton(mSelf, keyEvent);
    }

    /** AOSP KeyEvent.isMediaSessionKey (hidden there, missing from this runtime's KeyEvent). */
    private static boolean isMediaSessionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return true;
        }
        return false;
    }

    public @Nullable PlaybackState getPlaybackState() {
        return mSession.mPlaybackState;
    }

    public @Nullable MediaMetadata getMetadata() {
        return mSession.mMetadata;
    }

    public @Nullable List<MediaSession.QueueItem> getQueue() {
        List<MediaSession.QueueItem> q = mSession.mQueue;
        return q == null ? null : new ArrayList<>(q);
    }

    public @Nullable CharSequence getQueueTitle() {
        return mSession.mQueueTitle;
    }

    public @Nullable Bundle getExtras() {
        return mSession.mExtras;
    }

    public int getRatingType() {
        return mSession.mRatingType;
    }

    public long getFlags() {
        return mSession.mFlags;
    }

    public @NonNull Bundle getSessionInfo() {
        return new Bundle(mSession.mSessionInfo);
    }

    public @Nullable PlaybackInfo getPlaybackInfo() {
        return mSession.getPlaybackInfo();
    }

    public @Nullable PendingIntent getSessionActivity() {
        return mSession.mSessionActivity;
    }

    public @NonNull MediaSession.Token getSessionToken() {
        return mToken;
    }

    public void setVolumeTo(int value, int flags) {
        mSession.dispatchSetVolumeTo(value);
    }

    public void adjustVolume(int direction, int flags) {
        mSession.dispatchAdjustVolume(direction);
    }

    public void registerCallback(@NonNull Callback callback) {
        registerCallback(callback, null);
    }

    public void registerCallback(@NonNull Callback callback, @Nullable Handler handler) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        if (handler == null) {
            Looper l = Looper.myLooper();
            handler = new Handler(l != null ? l : Looper.getMainLooper());
        }
        synchronized (mCallbacks) {
            for (CallbackStub s : mCallbacks) {
                if (s.mCallback == callback)
                    return; // AOSP: already registered
            }
            CallbackStub stub = new CallbackStub(callback, handler);
            mCallbacks.add(stub);
            mSession.mControllerCallbacks.add(stub);
        }
    }

    public void unregisterCallback(@NonNull Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        synchronized (mCallbacks) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                CallbackStub s = mCallbacks.get(i);
                if (s.mCallback == callback) {
                    mCallbacks.remove(i);
                    mSession.mControllerCallbacks.remove(s);
                    s.mHandler.removeCallbacksAndMessages(s);
                }
            }
        }
    }

    public void sendCommand(@NonNull final String command, @Nullable final Bundle args,
            @Nullable final ResultReceiver cb) {
        if (TextUtils.isEmpty(command)) {
            throw new IllegalArgumentException("command cannot be null or empty");
        }
        mSession.dispatch(mSelf, new MediaSession.Dispatch() {
            @Override
            public void run(MediaSession.Callback c) {
                c.onCommand(command, args, cb);
            }
        });
    }

    public String getPackageName() {
        return mSession.getPackageName();
    }

    /** @hide */
    public String getTag() {
        return mSession.getTag();
    }

    /** @hide */
    public boolean controlsSameSession(MediaController other) {
        return other != null && other.mSession == mSession;
    }

    /** One registration of a Callback: delivers the session's changes on the callback's handler. */
    final class CallbackStub {
        final Callback mCallback;
        final Handler mHandler;

        CallbackStub(Callback callback, Handler handler) {
            mCallback = callback;
            mHandler = handler;
        }

        private void post(Runnable r) {
            android.os.Message m = android.os.Message.obtain(mHandler, r);
            m.obj = this;
            mHandler.sendMessage(m);
        }

        void postSessionDestroyed() {
            post(new Runnable() {
                public void run() {
                    mCallback.onSessionDestroyed();
                }
            });
        }

        void postEvent(final String event, final Bundle extras) {
            post(new Runnable() {
                public void run() {
                    mCallback.onSessionEvent(event, extras);
                }
            });
        }

        void postPlaybackState(final PlaybackState state) {
            post(new Runnable() {
                public void run() {
                    mCallback.onPlaybackStateChanged(state);
                }
            });
        }

        void postMetadata(final MediaMetadata metadata) {
            post(new Runnable() {
                public void run() {
                    mCallback.onMetadataChanged(metadata);
                }
            });
        }

        void postQueue(final List<MediaSession.QueueItem> queue) {
            post(new Runnable() {
                public void run() {
                    mCallback.onQueueChanged(queue == null ? null : new ArrayList<>(queue));
                }
            });
        }

        void postQueueTitle(final CharSequence title) {
            post(new Runnable() {
                public void run() {
                    mCallback.onQueueTitleChanged(title);
                }
            });
        }

        void postExtras(final Bundle extras) {
            post(new Runnable() {
                public void run() {
                    mCallback.onExtrasChanged(extras);
                }
            });
        }

        void postAudioInfo(final PlaybackInfo info) {
            post(new Runnable() {
                public void run() {
                    mCallback.onAudioInfoChanged(info);
                }
            });
        }
    }

    /** Callback for receiving updates from the session. */
    public abstract static class Callback {
        public void onSessionDestroyed() {
        }

        public void onSessionEvent(@NonNull String event, @Nullable Bundle extras) {
        }

        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
        }

        public void onMetadataChanged(@Nullable MediaMetadata metadata) {
        }

        public void onQueueChanged(@Nullable List<MediaSession.QueueItem> queue) {
        }

        public void onQueueTitleChanged(@Nullable CharSequence title) {
        }

        public void onExtrasChanged(@Nullable Bundle extras) {
        }

        public void onAudioInfoChanged(PlaybackInfo info) {
        }
    }

    /** Interface for controlling media playback on a session. */
    public final class TransportControls {
        private TransportControls() {
        }

        private void send(MediaSession.Dispatch d) {
            mSession.dispatch(mSelf, d);
        }

        public void prepare() {
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onPrepare();
                }
            });
        }

        public void prepareFromMediaId(@NonNull final String mediaId, @Nullable final Bundle extras) {
            if (TextUtils.isEmpty(mediaId)) {
                throw new IllegalArgumentException("You must specify a non-empty String for prepareFromMediaId.");
            }
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onPrepareFromMediaId(mediaId, extras);
                }
            });
        }

        public void prepareFromSearch(@NonNull String query, @Nullable final Bundle extras) {
            final String q = query == null ? "" : query;
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onPrepareFromSearch(q, extras);
                }
            });
        }

        public void prepareFromUri(@NonNull final Uri uri, @Nullable final Bundle extras) {
            if (uri == null || Uri.EMPTY.equals(uri)) {
                throw new IllegalArgumentException("You must specify a non-empty Uri for prepareFromUri.");
            }
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onPrepareFromUri(uri, extras);
                }
            });
        }

        public void play() {
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onPlay();
                }
            });
        }

        public void playFromMediaId(@NonNull final String mediaId, @Nullable final Bundle extras) {
            if (TextUtils.isEmpty(mediaId)) {
                throw new IllegalArgumentException("You must specify a non-empty String for playFromMediaId.");
            }
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onPlayFromMediaId(mediaId, extras);
                }
            });
        }

        public void playFromSearch(@NonNull String query, @Nullable final Bundle extras) {
            final String q = query == null ? "" : query;
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onPlayFromSearch(q, extras);
                }
            });
        }

        public void playFromUri(@NonNull final Uri uri, @Nullable final Bundle extras) {
            if (uri == null || Uri.EMPTY.equals(uri)) {
                throw new IllegalArgumentException("You must specify a non-empty Uri for playFromUri.");
            }
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onPlayFromUri(uri, extras);
                }
            });
        }

        public void skipToQueueItem(final long id) {
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onSkipToQueueItem(id);
                }
            });
        }

        public void pause() {
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onPause();
                }
            });
        }

        public void stop() {
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onStop();
                }
            });
        }

        public void seekTo(final long pos) {
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onSeekTo(pos);
                }
            });
        }

        public void fastForward() {
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onFastForward();
                }
            });
        }

        public void skipToNext() {
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onSkipToNext();
                }
            });
        }

        public void rewind() {
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onRewind();
                }
            });
        }

        public void skipToPrevious() {
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onSkipToPrevious();
                }
            });
        }

        public void setRating(final Rating rating) {
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onSetRating(rating);
                }
            });
        }

        public void setPlaybackSpeed(final float speed) {
            if (speed == 0.0f) {
                throw new IllegalArgumentException("speed must not be zero");
            }
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onSetPlaybackSpeed(speed);
                }
            });
        }

        public void sendCustomAction(@NonNull PlaybackState.CustomAction customAction, @Nullable Bundle args) {
            if (customAction == null) {
                throw new IllegalArgumentException("CustomAction cannot be null.");
            }
            sendCustomAction(customAction.getAction(), args);
        }

        public void sendCustomAction(@NonNull final String action, @Nullable final Bundle args) {
            if (TextUtils.isEmpty(action)) {
                throw new IllegalArgumentException("CustomAction cannot be null.");
            }
            send(new MediaSession.Dispatch() {
                public void run(MediaSession.Callback c) {
                    c.onCustomAction(action, args);
                }
            });
        }
    }

    /** Holds information about the current playback and how audio is handled for this session. */
    public static final class PlaybackInfo implements Parcelable {
        public static final int PLAYBACK_TYPE_LOCAL = 1;
        public static final int PLAYBACK_TYPE_REMOTE = 2;

        private final int mPlaybackType;
        private final int mVolumeControl;
        private final int mMaxVolume;
        private final int mCurrentVolume;
        private final AudioAttributes mAudioAttrs;
        private final String mVolumeControlId;

        /** @hide */
        public PlaybackInfo(int type, int control, int max, int current, @NonNull AudioAttributes attrs,
                @Nullable String volumeControlId) {
            mPlaybackType = type;
            mVolumeControl = control;
            mMaxVolume = max;
            mCurrentVolume = current;
            mAudioAttrs = attrs;
            mVolumeControlId = volumeControlId;
        }

        PlaybackInfo(Parcel in) {
            mPlaybackType = in.readInt();
            mVolumeControl = in.readInt();
            mMaxVolume = in.readInt();
            mCurrentVolume = in.readInt();
            mAudioAttrs = AudioAttributes.CREATOR.createFromParcel(in);
            mVolumeControlId = in.readString();
        }

        public int getPlaybackType() {
            return mPlaybackType;
        }

        public @NonNull AudioAttributes getAudioAttributes() {
            return mAudioAttrs;
        }

        public int getVolumeControl() {
            return mVolumeControl;
        }

        public int getMaxVolume() {
            return mMaxVolume;
        }

        public int getCurrentVolume() {
            return mCurrentVolume;
        }

        public @Nullable String getVolumeControlId() {
            return mVolumeControlId;
        }

        @Override
        public String toString() {
            return "playbackType=" + mPlaybackType + ", volumeControlType=" + mVolumeControl
                    + ", maxVolume=" + mMaxVolume + ", currentVolume=" + mCurrentVolume
                    + ", audioAttrs=" + mAudioAttrs + ", volumeControlId=" + mVolumeControlId;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mPlaybackType);
            dest.writeInt(mVolumeControl);
            dest.writeInt(mMaxVolume);
            dest.writeInt(mCurrentVolume);
            mAudioAttrs.writeToParcel(dest, flags);
            dest.writeString(mVolumeControlId);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<PlaybackInfo> CREATOR =
                new Parcelable.Creator<PlaybackInfo>() {
            @Override
            public PlaybackInfo createFromParcel(Parcel in) {
                return new PlaybackInfo(in);
            }

            @Override
            public PlaybackInfo[] newArray(int size) {
                return new PlaybackInfo[size];
            }
        };
    }
}
