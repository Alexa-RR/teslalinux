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

package android.media.browse;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.MediaDescription;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Browses media content offered by a MediaBrowserService (AOSP API).
 *
 * teslalinux: AOSP binds the service (action {@link #SERVICE_INTERFACE}) and talks to it over
 * the IMediaBrowserService binder protocol. Here the bind goes through Context.bindService: the
 * services of other apps (the music apps Waze offers to control) do not exist on this device, so
 * the bind fails and the browser reports {@link ConnectionCallback#onConnectionFailed()}, exactly
 * AOSP's path for a component that cannot be bound. A service that does bind but does not answer
 * the browser protocol (this runtime's MediaBrowserService has no binder side) is treated as AOSP
 * treats a service that never calls back: the connection is closed and reported failed.
 */
public final class MediaBrowser {
    private static final String TAG = "MediaBrowser";

    /** The intent action a MediaBrowserService declares (MediaBrowserService.SERVICE_INTERFACE). */
    public static final String SERVICE_INTERFACE = "android.media.browse.MediaBrowserService";

    public static final String EXTRA_PAGE = "android.media.browse.extra.PAGE";
    public static final String EXTRA_PAGE_SIZE = "android.media.browse.extra.PAGE_SIZE";

    private static final int CONNECT_STATE_DISCONNECTING = 0;
    private static final int CONNECT_STATE_DISCONNECTED = 1;
    private static final int CONNECT_STATE_CONNECTING = 2;
    private static final int CONNECT_STATE_CONNECTED = 3;
    private static final int CONNECT_STATE_SUSPENDED = 4;

    private final Context mContext;
    private final ComponentName mServiceComponent;
    private final ConnectionCallback mCallback;
    private final Bundle mRootHints;
    private final Handler mHandler = new Handler(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper());
    private final Map<String, SubscriptionCallback> mSubscriptions = new HashMap<>();

    private volatile int mState = CONNECT_STATE_DISCONNECTED;
    private volatile String mRootId;
    private volatile MediaSession.Token mMediaSessionToken;
    private volatile Bundle mExtras;
    private ServiceConnection mServiceConnection;

    public MediaBrowser(Context context, ComponentName serviceComponent, ConnectionCallback callback,
            Bundle rootHints) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (serviceComponent == null) {
            throw new IllegalArgumentException("service component must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("connection callback must not be null");
        }
        mContext = context;
        mServiceComponent = serviceComponent;
        mCallback = callback;
        mRootHints = rootHints == null ? null : new Bundle(rootHints);
    }

    public void connect() {
        if (mState == CONNECT_STATE_DISCONNECTING || mState == CONNECT_STATE_SUSPENDED) {
            throw new IllegalStateException("connect() called while neither disconnecting nor "
                    + "disconnected (state=" + getStateLabel(mState) + ")");
        }
        mState = CONNECT_STATE_CONNECTING;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mState == CONNECT_STATE_DISCONNECTING) {
                    return;
                }
                mState = CONNECT_STATE_CONNECTING;
                final Intent intent = new Intent(SERVICE_INTERFACE);
                intent.setComponent(mServiceComponent);
                mServiceConnection = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder binder) {
                        // no IMediaBrowserService protocol behind the binder: the service never
                        // answers connect(), which AOSP ends by closing and failing the connection
                        Log.w(TAG, "service " + name + " does not speak the media browser protocol");
                        failConnection();
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        if (mState == CONNECT_STATE_CONNECTED) {
                            mState = CONNECT_STATE_SUSPENDED;
                            mCallback.onConnectionSuspended();
                        }
                    }
                };
                boolean bound = false;
                try {
                    bound = mContext.bindService(intent, mServiceConnection, 1 /* Context.BIND_AUTO_CREATE */);
                } catch (Exception ex) {
                    Log.e(TAG, "Failed binding to service " + mServiceComponent);
                }
                if (!bound) {
                    failConnection();
                }
            }
        });
    }

    private void failConnection() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mState == CONNECT_STATE_DISCONNECTED)
                    return;
                forceCloseConnection();
                mCallback.onConnectionFailed();
            }
        });
    }

    public void disconnect() {
        mState = CONNECT_STATE_DISCONNECTING;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                forceCloseConnection();
            }
        });
    }

    private void forceCloseConnection() {
        if (mServiceConnection != null) {
            try {
                mContext.unbindService(mServiceConnection);
            } catch (IllegalArgumentException ex) {
            }
        }
        mState = CONNECT_STATE_DISCONNECTED;
        mServiceConnection = null;
        mRootId = null;
        mMediaSessionToken = null;
    }

    public boolean isConnected() {
        return mState == CONNECT_STATE_CONNECTED;
    }

    public @NonNull ComponentName getServiceComponent() {
        if (!isConnected()) {
            throw new IllegalStateException("getServiceComponent() called while not connected"
                    + " (state=" + mState + ")");
        }
        return mServiceComponent;
    }

    public @NonNull String getRoot() {
        if (!isConnected()) {
            throw new IllegalStateException("getRoot() called while not connected (state="
                    + getStateLabel(mState) + ")");
        }
        return mRootId;
    }

    public @Nullable Bundle getExtras() {
        if (!isConnected()) {
            throw new IllegalStateException("getExtras() called while not connected (state="
                    + getStateLabel(mState) + ")");
        }
        return mExtras;
    }

    public @NonNull MediaSession.Token getSessionToken() {
        if (!isConnected()) {
            throw new IllegalStateException("getSessionToken() called while not connected (state="
                    + mState + ")");
        }
        return mMediaSessionToken;
    }

    public void subscribe(@NonNull String parentId, @NonNull SubscriptionCallback callback) {
        subscribeInternal(parentId, null, callback);
    }

    public void subscribe(@NonNull String parentId, @NonNull Bundle options,
            @NonNull SubscriptionCallback callback) {
        if (options == null) {
            throw new IllegalArgumentException("options cannot be null");
        }
        subscribeInternal(parentId, new Bundle(options), callback);
    }

    private void subscribeInternal(String parentId, Bundle options, SubscriptionCallback callback) {
        if (TextUtils.isEmpty(parentId)) {
            throw new IllegalArgumentException("parentId cannot be empty.");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        // AOSP records the subscription and sends it to the service once connected
        synchronized (mSubscriptions) {
            mSubscriptions.put(parentId, callback);
        }
    }

    public void unsubscribe(@NonNull String parentId) {
        if (TextUtils.isEmpty(parentId)) {
            throw new IllegalArgumentException("parentId cannot be empty.");
        }
        synchronized (mSubscriptions) {
            mSubscriptions.remove(parentId);
        }
    }

    public void unsubscribe(@NonNull String parentId, @NonNull SubscriptionCallback callback) {
        if (TextUtils.isEmpty(parentId)) {
            throw new IllegalArgumentException("parentId cannot be empty.");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        synchronized (mSubscriptions) {
            if (mSubscriptions.get(parentId) == callback)
                mSubscriptions.remove(parentId);
        }
    }

    /** AOSP: without a connection the item cannot be fetched and the callback gets onError. */
    public void getItem(@NonNull final String mediaId, @NonNull final ItemCallback cb) {
        if (TextUtils.isEmpty(mediaId)) {
            throw new IllegalArgumentException("mediaId cannot be empty.");
        }
        if (cb == null) {
            throw new IllegalArgumentException("cb cannot be null.");
        }
        if (mState != CONNECT_STATE_CONNECTED) {
            Log.i(TAG, "Not connected, unable to retrieve the MediaItem.");
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    cb.onError(mediaId);
                }
            });
        }
    }

    private static String getStateLabel(int state) {
        switch (state) {
            case CONNECT_STATE_DISCONNECTING:
                return "CONNECT_STATE_DISCONNECTING";
            case CONNECT_STATE_DISCONNECTED:
                return "CONNECT_STATE_DISCONNECTED";
            case CONNECT_STATE_CONNECTING:
                return "CONNECT_STATE_CONNECTING";
            case CONNECT_STATE_CONNECTED:
                return "CONNECT_STATE_CONNECTED";
            case CONNECT_STATE_SUSPENDED:
                return "CONNECT_STATE_SUSPENDED";
            default:
                return "UNKNOWN/" + state;
        }
    }

    /** A class with information on a single media item for use in browsing/searching media. */
    public static class MediaItem implements Parcelable {
        private final int mFlags;
        private final MediaDescription mDescription;

        public static final int FLAG_BROWSABLE = 1 << 0;
        public static final int FLAG_PLAYABLE = 1 << 1;

        public MediaItem(@NonNull MediaDescription description, int flags) {
            if (description == null) {
                throw new IllegalArgumentException("description cannot be null");
            }
            if (TextUtils.isEmpty(description.getMediaId())) {
                throw new IllegalArgumentException("description must have a non-empty media id");
            }
            mFlags = flags;
            mDescription = description;
        }

        private MediaItem(Parcel in) {
            mFlags = in.readInt();
            mDescription = MediaDescription.CREATOR.createFromParcel(in);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(mFlags);
            mDescription.writeToParcel(out, flags);
        }

        @Override
        public String toString() {
            return "MediaItem{mFlags=" + mFlags + ", mDescription=" + mDescription + '}';
        }

        public static final @android.annotation.NonNull Parcelable.Creator<MediaItem> CREATOR =
                new Parcelable.Creator<MediaItem>() {
                    @Override
                    public MediaItem createFromParcel(Parcel in) {
                        return new MediaItem(in);
                    }

                    @Override
                    public MediaItem[] newArray(int size) {
                        return new MediaItem[size];
                    }
                };

        public int getFlags() {
            return mFlags;
        }

        public boolean isBrowsable() {
            return (mFlags & FLAG_BROWSABLE) != 0;
        }

        public boolean isPlayable() {
            return (mFlags & FLAG_PLAYABLE) != 0;
        }

        public @NonNull MediaDescription getDescription() {
            return mDescription;
        }

        public @Nullable String getMediaId() {
            return mDescription.getMediaId();
        }
    }

    /** Callbacks for connection related events. */
    public static class ConnectionCallback {
        public void onConnected() {
        }

        public void onConnectionSuspended() {
        }

        public void onConnectionFailed() {
        }
    }

    /** Callbacks for subscription related events. */
    public abstract static class SubscriptionCallback {
        public SubscriptionCallback() {
        }

        public void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaItem> children) {
        }

        public void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaItem> children,
                @NonNull Bundle options) {
        }

        public void onError(@NonNull String parentId) {
        }

        public void onError(@NonNull String parentId, @NonNull Bundle options) {
        }
    }

    /** Callback for receiving the result of {@link #getItem}. */
    public abstract static class ItemCallback {
        public void onItemLoaded(MediaItem item) {
        }

        public void onError(@NonNull String mediaId) {
        }
    }
}
