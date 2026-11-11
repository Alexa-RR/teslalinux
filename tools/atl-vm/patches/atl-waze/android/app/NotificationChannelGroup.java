/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * teslalinux: AOSP's NotificationChannelGroup (android14-release). NotificationManager keeps the
 * groups an app creates and fills getChannels() when it returns them.
 */
public final class NotificationChannelGroup implements Parcelable {
	/** AOSP: the maximum length of the id and name. */
	private static final int MAX_TEXT_LENGTH = 1000;

	private final String mId;
	private CharSequence mName;
	private String mDescription;
	private boolean mBlocked;
	private List<NotificationChannel> mChannels = new ArrayList<>();
	private int mUserLockedFields;

	public NotificationChannelGroup(String id, CharSequence name) {
		this.mId = getTrimmedString(id);
		this.mName = name != null ? getTrimmedString(name.toString()) : null;
	}

	protected NotificationChannelGroup(Parcel in) {
		mId = in.readString();
		mName = in.readCharSequence();
		mDescription = in.readString();
		mBlocked = in.readBoolean();
		mUserLockedFields = in.readInt();
	}

	private String getTrimmedString(String input) {
		if (input != null && input.length() > MAX_TEXT_LENGTH) {
			return input.substring(0, MAX_TEXT_LENGTH);
		}
		return input;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeString(mId);
		dest.writeCharSequence(mName);
		dest.writeString(mDescription);
		dest.writeBoolean(mBlocked);
		dest.writeInt(mUserLockedFields);
	}

	public String getId() {
		return mId;
	}

	public CharSequence getName() {
		return mName;
	}

	public String getDescription() {
		return mDescription;
	}

	/** AOSP: the channels of the group, filled in when the group comes back from NotificationManager. */
	public List<NotificationChannel> getChannels() {
		return mChannels;
	}

	/** AOSP: whether the user blocked the group; there is no settings UI to block it here. */
	public boolean isBlocked() {
		return mBlocked;
	}

	public void setDescription(String description) {
		mDescription = getTrimmedString(description);
	}

	/** @hide */
	public void setBlocked(boolean blocked) {
		mBlocked = blocked;
	}

	/** @hide */
	public void addChannel(NotificationChannel channel) {
		mChannels.add(channel);
	}

	/** @hide */
	public void setChannels(List<NotificationChannel> channels) {
		mChannels = channels;
	}

	/** @hide */
	public int getUserLockedFields() {
		return mUserLockedFields;
	}

	/** @hide */
	public NotificationChannelGroup clone() {
		NotificationChannelGroup cloned = new NotificationChannelGroup(getId(), getName());
		cloned.setDescription(getDescription());
		cloned.setBlocked(isBlocked());
		cloned.setChannels(new ArrayList<>(getChannels()));
		cloned.mUserLockedFields = mUserLockedFields;
		return cloned;
	}

	public static final Creator<NotificationChannelGroup> CREATOR = new Creator<NotificationChannelGroup>() {
		@Override
		public NotificationChannelGroup createFromParcel(Parcel in) {
			return new NotificationChannelGroup(in);
		}

		@Override
		public NotificationChannelGroup[] newArray(int size) {
			return new NotificationChannelGroup[size];
		}
	};

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		NotificationChannelGroup that = (NotificationChannelGroup) o;
		return isBlocked() == that.isBlocked()
		    && mUserLockedFields == that.mUserLockedFields
		    && Objects.equals(getId(), that.getId())
		    && TextUtils.equals(getName(), that.getName())
		    && Objects.equals(getDescription(), that.getDescription())
		    && Objects.equals(getChannels(), that.getChannels());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getId(), getName(), getDescription(), isBlocked(), getChannels(), mUserLockedFields);
	}

	@Override
	public String toString() {
		return "NotificationChannelGroup{mId='" + mId + '\'' + ", mName=" + mName
		    + ", mDescription=" + (!TextUtils.isEmpty(mDescription) ? "hasDescription " : "")
		    + ", mBlocked=" + mBlocked + ", mChannels=" + mChannels
		    + ", mUserLockedFields=" + mUserLockedFields + '}';
	}
}
