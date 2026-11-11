/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * The AudioPlaybackConfiguration class collects the information describing an audio playback
 * session (AOSP API).
 *
 * teslalinux: AOSP's PlaybackActivityMonitor (in AudioService) builds these from the players that
 * register with it. Here AudioManager keeps that registry for this process's AudioTracks (the
 * only players that render through this runtime's audio path), with AOSP's player states.
 */
public final class AudioPlaybackConfiguration implements Parcelable {
    /** @hide */
    public static final int PLAYER_PIID_INVALID = -1;
    /** @hide */
    public static final int PLAYER_UPID_INVALID = -1;
    /** @hide */
    public static final int PLAYER_DEVICEID_INVALID = 0;

    /** @hide */
    public static final int PLAYER_TYPE_UNKNOWN = -1;
    /** @hide */
    public static final int PLAYER_TYPE_JAM_AUDIOTRACK = 1;
    /** @hide */
    public static final int PLAYER_TYPE_JAM_MEDIAPLAYER = 2;
    /** @hide */
    public static final int PLAYER_TYPE_JAM_SOUNDPOOL = 3;
    /** @hide */
    public static final int PLAYER_TYPE_SLES_AUDIOPLAYER_BUFFERQUEUE = 11;
    /** @hide */
    public static final int PLAYER_TYPE_SLES_AUDIOPLAYER_URI_FD = 12;
    /** @hide */
    public static final int PLAYER_TYPE_AAUDIO = 13;
    /** @hide */
    public static final int PLAYER_TYPE_HW_SOURCE = 14;
    /** @hide */
    public static final int PLAYER_TYPE_EXTERNAL_PROXY = 15;

    /** @hide */
    public static final int PLAYER_STATE_UNKNOWN = -1;
    /** @hide */
    public static final int PLAYER_STATE_RELEASED = 0;
    /** @hide */
    public static final int PLAYER_STATE_IDLE = 1;
    /** @hide */
    public static final int PLAYER_STATE_STARTED = 2;
    /** @hide */
    public static final int PLAYER_STATE_PAUSED = 3;
    /** @hide */
    public static final int PLAYER_STATE_STOPPED = 4;

    private final int mPlayerIId;
    private final int mPlayerType;
    private final int mClientUid;
    private final int mClientPid;
    private final int mSessionId;
    private int mPlayerState;
    private AudioAttributes mPlayerAttr;
    private int mDeviceId;

    /** @hide */
    public AudioPlaybackConfiguration(int piid, int playerType, int uid, int pid, int sessionId,
            AudioAttributes attr, int state, int deviceId) {
        mPlayerIId = piid;
        mPlayerType = playerType;
        mClientUid = uid;
        mClientPid = pid;
        mSessionId = sessionId;
        mPlayerAttr = attr;
        mPlayerState = state;
        mDeviceId = deviceId;
    }

    public AudioAttributes getAudioAttributes() {
        return mPlayerAttr;
    }

    /** @hide */
    public int getClientUid() {
        return mClientUid;
    }

    /** @hide */
    public int getClientPid() {
        return mClientPid;
    }

    /** The device this player renders to while it plays, else null (API 33). */
    public AudioDeviceInfo getAudioDeviceInfo() {
        if (mDeviceId == PLAYER_DEVICEID_INVALID || mPlayerState != PLAYER_STATE_STARTED) {
            return null;
        }
        AudioDeviceInfo out = AudioManager.tlDefaultOutputDevice();
        return out != null && out.getId() == mDeviceId ? out : null;
    }

    /** @hide */
    public int getSessionId() {
        return mSessionId;
    }

    /** @hide */
    public int getPlayerType() {
        return mPlayerType;
    }

    /** @hide */
    public int getPlayerState() {
        return mPlayerState;
    }

    /** @hide */
    public int getPlayerInterfaceId() {
        return mPlayerIId;
    }

    /** @hide */
    public boolean isActive() {
        return mPlayerState == PLAYER_STATE_STARTED;
    }

    /** @hide */
    public static String toLogFriendlyPlayerState(int state) {
        switch (state) {
            case PLAYER_STATE_UNKNOWN: return "unknown";
            case PLAYER_STATE_RELEASED: return "released";
            case PLAYER_STATE_IDLE: return "idle";
            case PLAYER_STATE_STARTED: return "started";
            case PLAYER_STATE_PAUSED: return "paused";
            case PLAYER_STATE_STOPPED: return "stopped";
            default: return "unknown player state";
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPlayerIId, mDeviceId, mPlayerType, mClientUid, mClientPid, mSessionId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof AudioPlaybackConfiguration)) return false;
        AudioPlaybackConfiguration that = (AudioPlaybackConfiguration) o;
        return mPlayerIId == that.mPlayerIId && mPlayerType == that.mPlayerType
                && mClientUid == that.mClientUid && mClientPid == that.mClientPid
                && mSessionId == that.mSessionId && mDeviceId == that.mDeviceId;
    }

    @Override
    public String toString() {
        return "AudioPlaybackConfiguration piid:" + mPlayerIId + " deviceId:" + mDeviceId
                + " type:" + mPlayerType + " u/pid:" + mClientUid + "/" + mClientPid
                + " state:" + toLogFriendlyPlayerState(mPlayerState) + " attr:" + mPlayerAttr
                + " sessionId:" + mSessionId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mPlayerIId);
        dest.writeInt(mDeviceId);
        dest.writeInt(mPlayerType);
        dest.writeInt(mClientUid);
        dest.writeInt(mClientPid);
        dest.writeInt(mPlayerState);
        dest.writeInt(mSessionId);
        mPlayerAttr.writeToParcel(dest, 0);
    }

    public static final Parcelable.Creator<AudioPlaybackConfiguration> CREATOR =
            new Parcelable.Creator<AudioPlaybackConfiguration>() {
        public AudioPlaybackConfiguration createFromParcel(Parcel p) {
            int piid = p.readInt();
            int deviceId = p.readInt();
            int type = p.readInt();
            int uid = p.readInt();
            int pid = p.readInt();
            int state = p.readInt();
            int session = p.readInt();
            AudioAttributes attr = AudioAttributes.CREATOR.createFromParcel(p);
            return new AudioPlaybackConfiguration(piid, type, uid, pid, session, attr, state, deviceId);
        }

        public AudioPlaybackConfiguration[] newArray(int size) {
            return new AudioPlaybackConfiguration[size];
        }
    };
}
