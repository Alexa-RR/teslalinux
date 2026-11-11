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
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Provides support for interacting with media sessions (AOSP API).
 *
 * teslalinux: AOSP's MediaSessionService keeps the sessions of all apps. This device runs one
 * app, so the session registry is the list of this process's {@link MediaSession}s, most recently
 * active first, as AOSP's priority order. Listing other apps' sessions needs
 * MEDIA_CONTENT_CONTROL or an enabled notification listener; neither can be granted on this
 * device (no system settings), so those calls fail with AOSP's SecurityException. Media keys from
 * {@link android.media.AudioManager#dispatchMediaKeyEvent} go to the highest-priority active
 * session that handles media buttons, as MediaSessionService routes them.
 */
public final class MediaSessionManager {
    private static final String TAG = "SessionManager";

    private static final Object sLock = new Object();
    /** active-priority order: index 0 is the most recently activated session */
    private static final ArrayList<MediaSession> sSessions = new ArrayList<>();
    private static final Map<Integer, MediaSession> sHandles = new HashMap<>();
    private static int sNextHandle = 1;
    private static final CopyOnWriteArrayList<ListenerRecord> sListeners = new CopyOnWriteArrayList<>();

    private final Context mContext;

    /** @hide */
    public MediaSessionManager(Context context) {
        mContext = context;
    }

    /** @hide used when the service is looked up by class without a context */
    public MediaSessionManager() {
        this(null);
    }

    // ---- registry, used by MediaSession ----

    static void tlSessionCreated(MediaSession s) {
        synchronized (sLock) {
            sSessions.add(s);
            sHandles.put(sNextHandle++, s);
        }
    }

    static void tlSessionReleased(MediaSession s) {
        synchronized (sLock) {
            sSessions.remove(s);
            sHandles.values().remove(s);
        }
        tlActiveSessionsChanged();
    }

    static int tlHandleOf(MediaSession s) {
        synchronized (sLock) {
            for (Map.Entry<Integer, MediaSession> e : sHandles.entrySet()) {
                if (e.getValue() == s)
                    return e.getKey();
            }
        }
        return 0;
    }

    static MediaSession tlSessionOf(int handle) {
        synchronized (sLock) {
            return sHandles.get(handle);
        }
    }

    /** A session became (in)active: it moves to the front, listeners learn the new list. */
    static void tlActiveSessionsChanged() {
        synchronized (sLock) {
            ArrayList<MediaSession> active = new ArrayList<>();
            ArrayList<MediaSession> inactive = new ArrayList<>();
            for (MediaSession s : sSessions)
                (s.isActive() ? active : inactive).add(s);
            sSessions.clear();
            sSessions.addAll(active);
            sSessions.addAll(inactive);
        }
        for (final ListenerRecord r : sListeners) {
            final List<MediaController> controllers = activeControllers(r.mContext);
            r.mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    r.mListener.onActiveSessionsChanged(controllers);
                }
            });
        }
    }

    private static List<MediaController> activeControllers(Context context) {
        List<MediaController> out = new ArrayList<>();
        synchronized (sLock) {
            for (MediaSession s : sSessions) {
                if (s.isActive())
                    out.add(new MediaController(context, s.getSessionToken()));
            }
        }
        return out;
    }

    /** @hide AudioManager.dispatchMediaKeyEvent: to the media button session (MediaSessionService). */
    public static void tlDispatchMediaKeyEvent(KeyEvent keyEvent) {
        MediaSession target = null;
        synchronized (sLock) {
            for (MediaSession s : sSessions) {
                if (s.isActive() && (s.mFlags & MediaSession.FLAG_HANDLES_MEDIA_BUTTONS) != 0) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                // AOSP falls back to the last session that played (here: the most recent active one)
                for (MediaSession s : sSessions) {
                    if (s.isActive()) {
                        target = s;
                        break;
                    }
                }
            }
        }
        if (target != null) {
            target.dispatchMediaButton(new RemoteUserInfo("android", android.os.Process.myPid(),
                    android.os.Process.myUid()), keyEvent);
        }
    }

    // ---- public API ----

    private static void checkMediaControlAccess(ComponentName notificationListener) {
        // AOSP MediaSessionService.verifySessionsRequest: MEDIA_CONTENT_CONTROL (system only) or
        // an enabled notification listener of the caller; this device has no way to enable one
        throw new SecurityException("Missing permission to control media.");
    }

    public @NonNull List<MediaController> getActiveSessions(@Nullable ComponentName notificationListener) {
        checkMediaControlAccess(notificationListener);
        return activeControllers(mContext);
    }

    /** @hide */
    public @NonNull List<MediaController> getActiveSessionsForUser(@Nullable ComponentName notificationListener,
            android.os.UserHandle userHandle) {
        checkMediaControlAccess(notificationListener);
        return activeControllers(mContext);
    }

    public void addOnActiveSessionsChangedListener(@NonNull OnActiveSessionsChangedListener sessionListener,
            @Nullable ComponentName notificationListener) {
        addOnActiveSessionsChangedListener(sessionListener, notificationListener, null);
    }

    public void addOnActiveSessionsChangedListener(@NonNull OnActiveSessionsChangedListener sessionListener,
            @Nullable ComponentName notificationListener, @Nullable Handler handler) {
        Objects.requireNonNull(sessionListener, "sessionListener shouldn't be null");
        checkMediaControlAccess(notificationListener);
        final Handler h = handler != null ? handler
                : new Handler(Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper());
        Executor e = new Executor() {
            @Override
            public void execute(Runnable r) {
                h.post(r);
            }
        };
        sListeners.add(new ListenerRecord(mContext, sessionListener, e));
    }

    public void removeOnActiveSessionsChangedListener(@NonNull OnActiveSessionsChangedListener sessionListener) {
        Objects.requireNonNull(sessionListener, "sessionListener shouldn't be null");
        for (ListenerRecord r : sListeners) {
            if (r.mListener == sessionListener)
                sListeners.remove(r);
        }
    }

    /** AOSP: whether the remote user is a trusted controller; true only for the caller itself here. */
    public boolean isTrustedForMediaControl(@NonNull RemoteUserInfo userInfo) {
        Objects.requireNonNull(userInfo, "userInfo shouldn't be null");
        return userInfo.getUid() == android.os.Process.myUid();
    }

    public interface OnActiveSessionsChangedListener {
        void onActiveSessionsChanged(@Nullable List<MediaController> controllers);
    }

    private static final class ListenerRecord {
        final Context mContext;
        final OnActiveSessionsChangedListener mListener;
        final Executor mExecutor;

        ListenerRecord(Context context, OnActiveSessionsChangedListener listener, Executor executor) {
            mContext = context;
            mListener = listener;
            mExecutor = executor;
        }
    }

    /** Information of a remote user of MediaSession or MediaBrowserService (AOSP). */
    public static final class RemoteUserInfo {
        private final String mPackageName;
        private final int mPid;
        private final int mUid;

        public RemoteUserInfo(@NonNull String packageName, int pid, int uid) {
            if (packageName == null) {
                throw new NullPointerException("packageName shouldn't be null");
            }
            if (packageName.isEmpty()) {
                throw new IllegalArgumentException("packageName shouldn't be empty");
            }
            mPackageName = packageName;
            mPid = pid;
            mUid = uid;
        }

        public String getPackageName() {
            return mPackageName;
        }

        public int getPid() {
            return mPid;
        }

        public int getUid() {
            return mUid;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof RemoteUserInfo)) {
                return false;
            }
            if (this == obj) {
                return true;
            }
            RemoteUserInfo otherUserInfo = (RemoteUserInfo) obj;
            return TextUtils.equals(mPackageName, otherUserInfo.mPackageName)
                    && mPid == otherUserInfo.mPid
                    && mUid == otherUserInfo.mUid;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPackageName, mPid, mUid);
        }
    }
}
