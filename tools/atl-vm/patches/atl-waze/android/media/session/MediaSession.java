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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.VolumeProvider;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Allows interaction with media controllers, volume keys, media buttons, and transport controls
 * (AOSP API).
 *
 * teslalinux: AOSP's session lives in the system server (MediaSessionRecord) and talks to
 * controllers over binder. In this single-app runtime the session record is this object: its
 * {@link Token} refers to it, every {@link MediaController} built from the token talks to it
 * directly, and {@link MediaSessionManager} lists the active sessions of the process (all the
 * sessions of the device). Controller commands reach the {@link Callback} on its handler, state
 * changes reach the controllers' callbacks on theirs, as with AOSP. The playback state is also
 * published to the desktop (MPRIS, ATL's native side) as before.
 */
public final class MediaSession {
    static final String TAG = "MediaSession";

    public static final int FLAG_HANDLES_MEDIA_BUTTONS = 1 << 0;
    public static final int FLAG_HANDLES_TRANSPORT_CONTROLS = 1 << 1;
    /** @hide */
    public static final int FLAG_EXCLUSIVE_GLOBAL_PRIORITY = 1 << 16;
    /** @hide */
    public static final int INVALID_UID = -1;
    /** @hide */
    public static final int INVALID_PID = -1;

    final Object mLock = new Object();
    private final Context mContext;
    private final String mTag;
    private final Token mSessionToken;
    private final MediaController mController;

    private CallbackRecord mCallback;
    private VolumeProvider mVolumeProvider;
    PlaybackState mPlaybackState;
    MediaMetadata mMetadata;
    List<QueueItem> mQueue;
    CharSequence mQueueTitle;
    Bundle mExtras;
    int mRatingType = Rating.RATING_NONE;
    int mFlags;
    PendingIntent mSessionActivity;
    PendingIntent mMediaButtonReceiver;
    ComponentName mMediaButtonBroadcastReceiver;
    AudioAttributes mAudioAttributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
    int mVolumeType = MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL;
    boolean mActive;
    boolean mReleased;
    final Bundle mSessionInfo;
    final CopyOnWriteArrayList<MediaController.CallbackStub> mControllerCallbacks = new CopyOnWriteArrayList<>();
    private MediaSessionManager.RemoteUserInfo mCurrentControllerInfo;

    public MediaSession(@NonNull Context context, @NonNull String tag) {
        this(context, tag, null);
    }

    public MediaSession(@NonNull Context context, @NonNull String tag, @Nullable Bundle sessionInfo) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null.");
        }
        if (TextUtils.isEmpty(tag)) {
            throw new IllegalArgumentException("tag cannot be null or empty");
        }
        mContext = context;
        mTag = tag;
        mSessionInfo = sessionInfo == null ? new Bundle() : new Bundle(sessionInfo);
        mSessionToken = new Token(this);
        mController = new MediaController(context, mSessionToken);
        MediaSessionManager.tlSessionCreated(this);
    }

    String getTag() {
        return mTag;
    }

    String getPackageName() {
        return mContext.getPackageName();
    }

    public void setCallback(@Nullable Callback callback) {
        setCallback(callback, null);
    }

    public void setCallback(@Nullable Callback callback, @Nullable Handler handler) {
        synchronized (mLock) {
            if (mCallback != null) {
                // We're updating the callback, clear the session from the old one.
                mCallback.mCallback.mSession = null;
                mCallback.mHandler.removeCallbacksAndMessages(null);
            }
            if (callback == null) {
                mCallback = null;
                nativeSetCallback(null);
                return;
            }
            Looper looper = handler != null ? handler.getLooper()
                    : Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper();
            callback.mSession = this;
            CallbackRecord record = new CallbackRecord(callback, new Handler(looper));
            callback.mHandler = record.mHandler;
            mCallback = record;
        }
        nativeSetCallback(callback);
    }

    public void setSessionActivity(@Nullable PendingIntent pi) {
        mSessionActivity = pi;
    }

    @Deprecated
    public void setMediaButtonReceiver(@Nullable PendingIntent mbr) {
        mMediaButtonReceiver = mbr;
    }

    public void setMediaButtonBroadcastReceiver(@Nullable ComponentName broadcastReceiver) {
        mMediaButtonBroadcastReceiver = broadcastReceiver;
    }

    public void setFlags(int flags) {
        mFlags = flags;
    }

    public void setPlaybackToLocal(AudioAttributes attributes) {
        if (attributes == null) {
            throw new IllegalArgumentException("Attributes cannot be null for local playback.");
        }
        synchronized (mLock) {
            if (mVolumeProvider != null)
                mVolumeProvider.setCallback(null);
            mVolumeProvider = null;
            mAudioAttributes = attributes;
            mVolumeType = MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL;
        }
        dispatchAudioInfoChanged();
    }

    public void setPlaybackToRemote(@NonNull VolumeProvider volumeProvider) {
        if (volumeProvider == null) {
            throw new IllegalArgumentException("volumeProvider may not be null!");
        }
        synchronized (mLock) {
            mVolumeProvider = volumeProvider;
            mVolumeType = MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE;
        }
        volumeProvider.setCallback(new VolumeProvider.Callback() {
            @Override
            public void onVolumeChanged(VolumeProvider volumeProvider) {
                dispatchAudioInfoChanged();
            }
        });
        dispatchAudioInfoChanged();
    }

    VolumeProvider getVolumeProvider() {
        synchronized (mLock) {
            return mVolumeProvider;
        }
    }

    MediaController.PlaybackInfo getPlaybackInfo() {
        synchronized (mLock) {
            if (mVolumeType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE && mVolumeProvider != null) {
                return new MediaController.PlaybackInfo(mVolumeType, mVolumeProvider.getVolumeControl(),
                        mVolumeProvider.getMaxVolume(), mVolumeProvider.getCurrentVolume(), mAudioAttributes,
                        mVolumeProvider.getVolumeControlId());
            }
        }
        AudioManager am = new AudioManager(mContext);
        int stream = mAudioAttributes.getVolumeControlStream();
        int control = am.isVolumeFixed() ? VolumeProvider.VOLUME_CONTROL_FIXED : VolumeProvider.VOLUME_CONTROL_ABSOLUTE;
        return new MediaController.PlaybackInfo(MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL, control,
                am.getStreamMaxVolume(stream), am.getStreamVolume(stream), mAudioAttributes, null);
    }

    public void setActive(boolean active) {
        boolean changed;
        synchronized (mLock) {
            changed = mActive != active;
            mActive = active;
        }
        if (changed)
            MediaSessionManager.tlActiveSessionsChanged();
    }

    public boolean isActive() {
        synchronized (mLock) {
            return mActive;
        }
    }

    public void sendSessionEvent(@NonNull String event, @Nullable Bundle extras) {
        if (TextUtils.isEmpty(event)) {
            throw new IllegalArgumentException("event cannot be null or empty");
        }
        for (MediaController.CallbackStub cb : mControllerCallbacks)
            cb.postEvent(event, extras);
    }

    public void release() {
        synchronized (mLock) {
            if (mReleased)
                return;
            mReleased = true;
            mActive = false;
            if (mCallback != null) {
                mCallback.mCallback.mSession = null;
                mCallback.mHandler.removeCallbacksAndMessages(null);
                mCallback = null;
            }
        }
        nativeSetCallback(null);
        for (MediaController.CallbackStub cb : mControllerCallbacks)
            cb.postSessionDestroyed();
        mControllerCallbacks.clear();
        MediaSessionManager.tlSessionReleased(this);
    }

    boolean isReleased() {
        synchronized (mLock) {
            return mReleased;
        }
    }

    public @NonNull Token getSessionToken() {
        return mSessionToken;
    }

    public @NonNull MediaController getController() {
        return mController;
    }

    public void setPlaybackState(@Nullable PlaybackState state) {
        mPlaybackState = state;
        for (MediaController.CallbackStub cb : mControllerCallbacks)
            cb.postPlaybackState(state);
        if (state != null)
            publishToDesktop(state);
    }

    /** ATL's MPRIS bridge: title, artist and art of the active queue item, or of the metadata. */
    private void publishToDesktop(PlaybackState state) {
        String title = null;
        String subTitle = null;
        String artUrl = null;
        List<QueueItem> queue = mQueue;
        if (queue != null) {
            for (QueueItem item : queue) {
                if (item.getQueueId() == state.getActiveQueueItemId()) {
                    MediaDescription d = item.getDescription();
                    title = d.getTitle() == null ? null : d.getTitle().toString();
                    subTitle = d.getSubtitle() == null ? null : d.getSubtitle().toString();
                    artUrl = d.getIconUri() == null ? null : d.getIconUri().toString();
                    break;
                }
            }
        }
        MediaMetadata md = mMetadata;
        if (title == null && md != null) {
            MediaDescription d = md.getDescription();
            title = d.getTitle() == null ? null : d.getTitle().toString();
            subTitle = d.getSubtitle() == null ? null : d.getSubtitle().toString();
            artUrl = d.getIconUri() == null ? null : d.getIconUri().toString();
        }
        try {
            nativeSetState(state.getState(), state.getActions(), state.getPosition(),
                    state.getLastPositionUpdateTime(), title, subTitle, artUrl);
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "desktop media bridge not available: " + e);
        }
    }

    public void setMetadata(@Nullable MediaMetadata metadata) {
        mMetadata = metadata;
        for (MediaController.CallbackStub cb : mControllerCallbacks)
            cb.postMetadata(metadata);
    }

    public void setQueue(@Nullable List<QueueItem> queue) {
        mQueue = queue == null ? null : new ArrayList<>(queue);
        for (MediaController.CallbackStub cb : mControllerCallbacks)
            cb.postQueue(mQueue);
    }

    public void setQueueTitle(@Nullable CharSequence title) {
        mQueueTitle = title;
        for (MediaController.CallbackStub cb : mControllerCallbacks)
            cb.postQueueTitle(title);
    }

    public void setRatingType(int type) {
        mRatingType = type;
    }

    public void setExtras(@Nullable Bundle extras) {
        mExtras = extras;
        for (MediaController.CallbackStub cb : mControllerCallbacks)
            cb.postExtras(extras);
    }

    private void dispatchAudioInfoChanged() {
        MediaController.PlaybackInfo info = getPlaybackInfo();
        for (MediaController.CallbackStub cb : mControllerCallbacks)
            cb.postAudioInfo(info);
    }

    public final @NonNull MediaSessionManager.RemoteUserInfo getCurrentControllerInfo() {
        synchronized (mLock) {
            if (mCurrentControllerInfo == null) {
                throw new IllegalStateException(
                        "This should be called inside of MediaSession.Callback methods");
            }
            return mCurrentControllerInfo;
        }
    }

    /** @hide */
    public String getCallingPackage() {
        synchronized (mLock) {
            return mCurrentControllerInfo == null ? null : mCurrentControllerInfo.getPackageName();
        }
    }

    /** @hide */
    public static boolean isActiveState(int state) {
        switch (state) {
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_PLAYING:
                return true;
        }
        return false;
    }

    // ---- commands from controllers (AOSP: CallbackStub -> CallbackMessageHandler) ----

    interface Dispatch {
        void run(Callback cb);
    }

    /** Runs a controller command on the callback's handler with the controller as caller. */
    void dispatch(final MediaSessionManager.RemoteUserInfo caller, final Dispatch d) {
        final CallbackRecord record;
        synchronized (mLock) {
            record = mCallback;
        }
        if (record == null)
            return;
        record.mHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (mLock) {
                    if (mCallback != record)
                        return;
                    mCurrentControllerInfo = caller;
                }
                try {
                    d.run(record.mCallback);
                } finally {
                    synchronized (mLock) {
                        mCurrentControllerInfo = null;
                    }
                }
            }
        });
    }

    void dispatchMediaButtonDelayed(final MediaSessionManager.RemoteUserInfo info, final Intent mediaButtonIntent, long delay) {
        final CallbackRecord record;
        synchronized (mLock) {
            record = mCallback;
        }
        if (record == null)
            return;
        Message msg = Message.obtain(record.mHandler, new Runnable() {
            @Override
            public void run() {
                synchronized (mLock) {
                    mCurrentControllerInfo = info;
                }
                try {
                    record.mCallback.handleMediaPlayPauseKeySingleTapIfPending();
                } finally {
                    synchronized (mLock) {
                        mCurrentControllerInfo = null;
                    }
                }
            }
        });
        msg.what = Callback.MSG_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT;
        record.mHandler.sendMessageDelayed(msg, delay);
    }

    /** A media key from a controller or the system (MediaSessionManager), as AOSP delivers it. */
    boolean dispatchMediaButton(final MediaSessionManager.RemoteUserInfo caller, KeyEvent keyEvent) {
        final Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        synchronized (mLock) {
            if (mCallback == null)
                return false;
        }
        dispatch(caller, new Dispatch() {
            @Override
            public void run(Callback cb) {
                cb.onMediaButtonEvent(intent);
            }
        });
        return true;
    }

    void dispatchAdjustVolume(int direction) {
        VolumeProvider vp = getVolumeProvider();
        if (vp != null) {
            vp.onAdjustVolume(direction);
            return;
        }
        new AudioManager(mContext).adjustStreamVolume(mAudioAttributes.getVolumeControlStream(), direction, 0);
        dispatchAudioInfoChanged();
    }

    void dispatchSetVolumeTo(int value) {
        VolumeProvider vp = getVolumeProvider();
        if (vp != null) {
            vp.onSetVolumeTo(value);
            return;
        }
        new AudioManager(mContext).setStreamVolume(mAudioAttributes.getVolumeControlStream(), value, 0);
        dispatchAudioInfoChanged();
    }

    private static final class CallbackRecord {
        final Callback mCallback;
        final Handler mHandler;

        CallbackRecord(Callback callback, Handler handler) {
            mCallback = callback;
            mHandler = handler;
        }
    }

    // ---- desktop (MPRIS) bridge in ATL's native code ----
    protected native void nativeSetState(int state, long actions, long position, long updateTime, String title, String subTitle, String artUrl);
    protected native void nativeSetCallback(Callback callback);

    /**
     * Represents an ongoing session. This may be passed to apps by the session owner to allow
     * them to create a {@link MediaController} to communicate with the session.
     */
    public static final class Token implements Parcelable {
        private final MediaSession mSession;
        private final int mUid;

        Token(MediaSession session) {
            mSession = session;
            mUid = android.os.Process.myUid();
        }

        MediaSession getSession() {
            return mSession;
        }

        /** @hide */
        public int getUid() {
            return mUid;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        /**
         * AOSP parcels the session binder. The session lives in this process, so the token is
         * parcelled as its registry handle (MediaSessionManager) and comes back as the same token.
         */
        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mUid);
            dest.writeInt(MediaSessionManager.tlHandleOf(mSession));
        }

        @Override
        public int hashCode() {
            return 31 * mUid + System.identityHashCode(mSession);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (!(obj instanceof Token))
                return false;
            Token other = (Token) obj;
            return mUid == other.mUid && mSession == other.mSession;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<Token> CREATOR =
                new Parcelable.Creator<Token>() {
            @Override
            public Token createFromParcel(Parcel in) {
                in.readInt();
                MediaSession s = MediaSessionManager.tlSessionOf(in.readInt());
                return s != null ? s.getSessionToken() : null;
            }

            @Override
            public Token[] newArray(int size) {
                return new Token[size];
            }
        };
    }

    /**
     * Receives media buttons, transport controls, and commands from controllers and the system.
     * A callback may be set using {@link #setCallback}.
     */
    public abstract static class Callback {
        static final int MSG_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT = 23;

        MediaSession mSession;
        Handler mHandler;
        private boolean mMediaPlayPauseKeyPending;

        public Callback() {
        }

        public void onCommand(@NonNull String command, @Nullable Bundle args,
                @Nullable ResultReceiver cb) {
        }

        public boolean onMediaButtonEvent(@NonNull Intent mediaButtonIntent) {
            if (mSession != null && mHandler != null
                    && Intent.ACTION_MEDIA_BUTTON.equals(mediaButtonIntent.getAction())) {
                KeyEvent ke = (KeyEvent) mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (ke != null && ke.getAction() == KeyEvent.ACTION_DOWN) {
                    PlaybackState state = mSession.mPlaybackState;
                    long validActions = state == null ? 0 : state.getActions();
                    switch (ke.getKeyCode()) {
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        case KeyEvent.KEYCODE_HEADSETHOOK:
                            if (ke.getRepeatCount() > 0) {
                                // Consider long-press as a single tap.
                                handleMediaPlayPauseKeySingleTapIfPending();
                            } else if (mMediaPlayPauseKeyPending) {
                                // Consider double tap as the next.
                                mHandler.removeMessages(MSG_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT);
                                mMediaPlayPauseKeyPending = false;
                                if ((validActions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) {
                                    onSkipToNext();
                                }
                            } else {
                                mMediaPlayPauseKeyPending = true;
                                mSession.dispatchMediaButtonDelayed(
                                        mSession.getCurrentControllerInfo(),
                                        mediaButtonIntent, ViewConfiguration.getDoubleTapTimeout());
                            }
                            return true;
                        default:
                            // If another key is pressed within double tap timeout, consider the
                            // pending play/pause as a single tap to handle media keys in order.
                            handleMediaPlayPauseKeySingleTapIfPending();
                            break;
                    }

                    switch (ke.getKeyCode()) {
                        case KeyEvent.KEYCODE_MEDIA_PLAY:
                            if ((validActions & PlaybackState.ACTION_PLAY) != 0) {
                                onPlay();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_PAUSE:
                            if ((validActions & PlaybackState.ACTION_PAUSE) != 0) {
                                onPause();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_NEXT:
                            if ((validActions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) {
                                onSkipToNext();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                            if ((validActions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) {
                                onSkipToPrevious();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_STOP:
                            if ((validActions & PlaybackState.ACTION_STOP) != 0) {
                                onStop();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                            if ((validActions & PlaybackState.ACTION_FAST_FORWARD) != 0) {
                                onFastForward();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_REWIND:
                            if ((validActions & PlaybackState.ACTION_REWIND) != 0) {
                                onRewind();
                                return true;
                            }
                            break;
                    }
                }
            }
            return false;
        }

        void handleMediaPlayPauseKeySingleTapIfPending() {
            if (!mMediaPlayPauseKeyPending) {
                return;
            }
            mMediaPlayPauseKeyPending = false;
            mHandler.removeMessages(MSG_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT);
            PlaybackState state = mSession.mPlaybackState;
            long validActions = state == null ? 0 : state.getActions();
            boolean isPlaying = state != null
                    && state.getState() == PlaybackState.STATE_PLAYING;
            boolean canPlay = (validActions & (PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_PLAY)) != 0;
            boolean canPause = (validActions & (PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_PAUSE)) != 0;
            if (isPlaying && canPause) {
                onPause();
            } else if (!isPlaying && canPlay) {
                onPlay();
            }
        }

        public void onPrepare() {
        }

        public void onPrepareFromMediaId(String mediaId, Bundle extras) {
        }

        public void onPrepareFromSearch(String query, Bundle extras) {
        }

        public void onPrepareFromUri(Uri uri, Bundle extras) {
        }

        public void onPlay() {
        }

        public void onPlayFromSearch(String query, Bundle extras) {
        }

        public void onPlayFromMediaId(String mediaId, Bundle extras) {
        }

        public void onPlayFromUri(Uri uri, Bundle extras) {
        }

        public void onSkipToQueueItem(long id) {
        }

        public void onPause() {
        }

        public void onSkipToNext() {
        }

        public void onSkipToPrevious() {
        }

        public void onFastForward() {
        }

        public void onRewind() {
        }

        public void onStop() {
        }

        public void onSeekTo(long pos) {
        }

        public void onSetRating(@NonNull Rating rating) {
        }

        public void onSetPlaybackSpeed(float speed) {
        }

        public void onCustomAction(@NonNull String action, @Nullable Bundle extras) {
        }
    }

    /**
     * A single item that is part of the play queue. It contains a description of the item and
     * its id in the queue.
     */
    public static final class QueueItem implements Parcelable {
        public static final int UNKNOWN_ID = -1;

        private final MediaDescription mDescription;
        private final long mId;

        public QueueItem(MediaDescription description, long id) {
            if (description == null) {
                throw new IllegalArgumentException("Description cannot be null.");
            }
            if (id == UNKNOWN_ID) {
                throw new IllegalArgumentException("Id cannot be QueueItem.UNKNOWN_ID");
            }
            mDescription = description;
            mId = id;
        }

        private QueueItem(Parcel in) {
            mDescription = MediaDescription.CREATOR.createFromParcel(in);
            mId = in.readLong();
        }

        public MediaDescription getDescription() {
            return mDescription;
        }

        public long getQueueId() {
            return mId;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            mDescription.writeToParcel(dest, flags);
            dest.writeLong(mId);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @android.annotation.NonNull Creator<MediaSession.QueueItem> CREATOR =
                new Creator<MediaSession.QueueItem>() {

                    @Override
                    public MediaSession.QueueItem createFromParcel(Parcel p) {
                        return new MediaSession.QueueItem(p);
                    }

                    @Override
                    public MediaSession.QueueItem[] newArray(int size) {
                        return new MediaSession.QueueItem[size];
                    }
                };

        @Override
        public String toString() {
            return "MediaSession.QueueItem {" + "Description=" + mDescription + ", Id=" + mId
                    + " }";
        }

        @Override
        public boolean equals(Object o) {
            if (o == null) {
                return false;
            }
            if (!(o instanceof QueueItem)) {
                return false;
            }
            final QueueItem item = (QueueItem) o;
            if (mId != item.mId) {
                return false;
            }
            return Objects.equals(mDescription, item.mDescription);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mDescription, mId);
        }
    }
}
