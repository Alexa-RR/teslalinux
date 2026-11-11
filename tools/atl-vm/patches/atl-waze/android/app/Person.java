/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.Objects;

/**
 * teslalinux: AOSP's Person (android14-release): an entity (a message sender, a caller) shown in
 * notifications. The icon is kept in memory only: ATL's Icon is not Parcelable.
 */
public final class Person implements Parcelable {

	private CharSequence mName;
	private Icon mIcon;
	private String mUri;
	private String mKey;
	private boolean mIsBot;
	private boolean mIsImportant;

	private Person(Parcel in) {
		mName = in.readCharSequence();
		mUri = in.readString();
		mKey = in.readString();
		mIsImportant = in.readBoolean();
		mIsBot = in.readBoolean();
	}

	private Person(Builder builder) {
		mName = builder.mName;
		mIcon = builder.mIcon;
		mUri = builder.mUri;
		mKey = builder.mKey;
		mIsBot = builder.mIsBot;
		mIsImportant = builder.mIsImportant;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public String getUri() {
		return mUri;
	}

	public CharSequence getName() {
		return mName;
	}

	public Icon getIcon() {
		return mIcon;
	}

	public String getKey() {
		return mKey;
	}

	public boolean isBot() {
		return mIsBot;
	}

	public boolean isImportant() {
		return mIsImportant;
	}

	/** @hide AOSP: the person's URI for the legacy EXTRA_PEOPLE list (uri, else "name:" + name). */
	public String resolveToLegacyUri() {
		if (mUri != null) {
			return mUri;
		}
		if (mName != null) {
			return "name:" + mName;
		}
		return "";
	}

	/** @hide */
	public Uri getIconUri() {
		return null;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof Person) {
			final Person other = (Person) obj;
			return Objects.equals(mName, other.mName)
			    && (mIcon == null ? other.mIcon == null : (other.mIcon != null && mIcon.equals(other.mIcon)))
			    && Objects.equals(mUri, other.mUri)
			    && Objects.equals(mKey, other.mKey)
			    && mIsBot == other.mIsBot
			    && mIsImportant == other.mIsImportant;
		}
		return false;
	}

	@Override
	public int hashCode() {
		return Objects.hash(mName, mIcon, mUri, mKey, mIsBot, mIsImportant);
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeCharSequence(mName);
		dest.writeString(mUri);
		dest.writeString(mKey);
		dest.writeBoolean(mIsImportant);
		dest.writeBoolean(mIsBot);
	}

	@Override
	public String toString() {
		return "Person{name=" + mName + " uri=" + mUri + " key=" + mKey + " bot=" + mIsBot + " important=" + mIsImportant + "}";
	}

	public static class Builder {
		private CharSequence mName;
		private Icon mIcon;
		private String mUri;
		private String mKey;
		private boolean mIsBot;
		private boolean mIsImportant;

		public Builder() {
		}

		private Builder(Person person) {
			mName = person.mName;
			mIcon = person.mIcon;
			mUri = person.mUri;
			mKey = person.mKey;
			mIsBot = person.mIsBot;
			mIsImportant = person.mIsImportant;
		}

		public Person.Builder setName(CharSequence name) {
			this.mName = name;
			return this;
		}

		public Person.Builder setIcon(Icon icon) {
			this.mIcon = icon;
			return this;
		}

		public Person.Builder setUri(String uri) {
			mUri = uri;
			return this;
		}

		public Person.Builder setKey(String key) {
			mKey = key;
			return this;
		}

		public Person.Builder setImportant(boolean isImportant) {
			mIsImportant = isImportant;
			return this;
		}

		public Person.Builder setBot(boolean isBot) {
			mIsBot = isBot;
			return this;
		}

		public Person build() {
			return new Person(this);
		}
	}

	public static final Creator<Person> CREATOR = new Creator<Person>() {
		@Override
		public Person createFromParcel(Parcel in) {
			return new Person(in);
		}

		@Override
		public Person[] newArray(int size) {
			return new Person[size];
		}
	};
}
