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

package android.service.notification;

import android.app.Notification;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

/**
 * teslalinux: AOSP's StatusBarNotification (android14-release): a posted notification with its
 * package, id, tag, user and post time, as NotificationManager.getActiveNotifications returns it.
 */
public class StatusBarNotification implements Parcelable {
	private final String pkg;
	private final int id;
	private final String tag;
	private final String key;
	private String groupKey;
	private String overrideGroupKey;
	private final int uid;
	private final String opPkg;
	private final int initialPid;
	private final Notification notification;
	private final UserHandle user;
	private final long postTime;

	/** @hide */
	public StatusBarNotification(String pkg, String opPkg, int id, String tag, int uid, int initialPid,
	                             Notification notification, UserHandle user, String overrideGroupKey, long postTime) {
		if (pkg == null)
			throw new NullPointerException();
		if (notification == null)
			throw new NullPointerException();
		this.pkg = pkg;
		this.opPkg = opPkg;
		this.id = id;
		this.tag = tag;
		this.uid = uid;
		this.initialPid = initialPid;
		this.notification = notification;
		this.user = user;
		this.postTime = postTime;
		this.overrideGroupKey = overrideGroupKey;
		this.key = key();
		this.groupKey = groupKey();
	}

	@Deprecated
	public StatusBarNotification(String pkg, String opPkg, int id, String tag, int uid, int initialPid,
	                             int score, Notification notification, UserHandle user, long postTime) {
		this(pkg, opPkg, id, tag, uid, initialPid, notification, user, null, postTime);
	}

	private int userIdentifier() {
		return uid / UserHandle.PER_USER_RANGE;
	}

	private String key() {
		return userIdentifier() + "|" + pkg + "|" + id + "|" + tag + "|" + uid;
	}

	private String groupKey() {
		if (overrideGroupKey != null) {
			return userIdentifier() + "|" + pkg + "|" + "g:" + overrideGroupKey;
		}
		final String group = getNotification().getGroup();
		final String sortKey = getNotification().getSortKey();
		if (group == null && sortKey == null) {
			// a group of one
			return key;
		}
		return userIdentifier() + "|" + pkg + "|" + (group == null ? "c:" + notification.getChannelId() : "g:" + group);
	}

	public boolean isGroup() {
		return overrideGroupKey != null || getNotification().getGroup() != null;
	}

	public boolean isAppGroup() {
		return getNotification().getGroup() != null || getNotification().getSortKey() != null;
	}

	@Override
	public void writeToParcel(Parcel out, int flags) {
		out.writeString(this.pkg);
		out.writeString(this.opPkg);
		out.writeInt(this.id);
		out.writeString(this.tag);
		out.writeInt(this.uid);
		out.writeInt(this.initialPid);
		this.notification.writeToParcel(out, flags);
		out.writeString(this.overrideGroupKey);
		out.writeLong(this.postTime);
	}

	@Override
	public int describeContents() {
		return 0;
	}

	public static final Parcelable.Creator<StatusBarNotification> CREATOR = new Parcelable.Creator<StatusBarNotification>() {
		public StatusBarNotification createFromParcel(Parcel in) {
			String pkg = in.readString();
			String opPkg = in.readString();
			int id = in.readInt();
			String tag = in.readString();
			int uid = in.readInt();
			int initialPid = in.readInt();
			Notification n = new Notification(in);
			String override = in.readString();
			long postTime = in.readLong();
			return new StatusBarNotification(pkg, opPkg, id, tag, uid, initialPid, n,
			                                 new UserHandle(uid / UserHandle.PER_USER_RANGE), override, postTime);
		}

		public StatusBarNotification[] newArray(int size) {
			return new StatusBarNotification[size];
		}
	};

	@Override
	public StatusBarNotification clone() {
		return new StatusBarNotification(this.pkg, this.opPkg, this.id, this.tag, this.uid,
		                                 this.initialPid, this.notification.clone(), this.user, this.overrideGroupKey, this.postTime);
	}

	@Override
	public String toString() {
		return String.format("StatusBarNotification(pkg=%s user=%s id=%d tag=%s key=%s: %s)",
		                     this.pkg, this.user, this.id, this.tag, this.key, this.notification);
	}

	public boolean isOngoing() {
		return (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0;
	}

	public boolean isClearable() {
		return ((notification.flags & Notification.FLAG_ONGOING_EVENT) == 0)
		    && ((notification.flags & Notification.FLAG_NO_CLEAR) == 0);
	}

	@Deprecated
	public int getUserId() {
		return userIdentifier();
	}

	public String getPackageName() {
		return pkg;
	}

	public int getId() {
		return id;
	}

	public String getTag() {
		return tag;
	}

	public int getUid() {
		return uid;
	}

	public String getOpPkg() {
		return opPkg;
	}

	/** @hide */
	public int getInitialPid() {
		return initialPid;
	}

	public Notification getNotification() {
		return notification;
	}

	public UserHandle getUser() {
		return user;
	}

	public long getPostTime() {
		return postTime;
	}

	public String getKey() {
		return key;
	}

	public String getGroupKey() {
		return groupKey;
	}

	public String getOverrideGroupKey() {
		return overrideGroupKey;
	}

	public void setOverrideGroupKey(String overrideGroupKey) {
		this.overrideGroupKey = overrideGroupKey;
		groupKey = groupKey();
	}
}
