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

import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import java.io.PrintWriter;
import java.util.Objects;

/** teslalinux: AOSP's RemoteAction (android14-release). */
public final class RemoteAction implements Parcelable {

	private final Icon mIcon;
	private final CharSequence mTitle;
	private final CharSequence mContentDescription;
	private final PendingIntent mActionIntent;
	private boolean mEnabled;
	private boolean mShouldShowIcon;

	RemoteAction(Parcel in) {
		PendingIntent intent = null;
		Icon icon = null;
		try {
			ClassLoader cl = RemoteAction.class.getClassLoader();
			Object o = in.readParcelable(cl);
			icon = o instanceof Icon ? (Icon) o : null;
			o = in.readParcelable(cl);
			intent = o instanceof PendingIntent ? (PendingIntent) o : null;
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
		mIcon = icon;
		mTitle = in.readString();
		mContentDescription = in.readString();
		mActionIntent = intent;
		mEnabled = in.readBoolean();
		mShouldShowIcon = in.readBoolean();
	}

	public RemoteAction(Icon icon, CharSequence title, CharSequence contentDescription, PendingIntent intent) {
		if (icon == null || title == null || contentDescription == null || intent == null) {
			throw new IllegalArgumentException("Expected icon, title, content description and action callback");
		}
		mIcon = icon;
		mTitle = title;
		mContentDescription = contentDescription;
		mActionIntent = intent;
		mEnabled = true;
		mShouldShowIcon = true;
	}

	public void setEnabled(boolean enabled) {
		mEnabled = enabled;
	}

	public boolean isEnabled() {
		return mEnabled;
	}

	public void setShouldShowIcon(boolean shouldShowIcon) {
		mShouldShowIcon = shouldShowIcon;
	}

	public boolean shouldShowIcon() {
		return mShouldShowIcon;
	}

	public Icon getIcon() {
		return mIcon;
	}

	public CharSequence getTitle() {
		return mTitle;
	}

	public CharSequence getContentDescription() {
		return mContentDescription;
	}

	public PendingIntent getActionIntent() {
		return mActionIntent;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof RemoteAction))
			return false;
		RemoteAction that = (RemoteAction) o;
		return mEnabled == that.mEnabled
		    && mShouldShowIcon == that.mShouldShowIcon
		    && Objects.equals(mIcon, that.mIcon)
		    && TextUtils.equals(mTitle, that.mTitle)
		    && TextUtils.equals(mContentDescription, that.mContentDescription)
		    && Objects.equals(mActionIntent, that.mActionIntent);
	}

	@Override
	public int hashCode() {
		return Objects.hash(mIcon, mTitle, mContentDescription, mActionIntent, mEnabled, mShouldShowIcon);
	}

	@Override
	public RemoteAction clone() {
		RemoteAction action = new RemoteAction(mIcon, mTitle, mContentDescription, mActionIntent);
		action.setEnabled(mEnabled);
		action.setShouldShowIcon(mShouldShowIcon);
		return action;
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel out, int flags) {
		out.writeParcelable(mIcon instanceof Parcelable ? (Parcelable) mIcon : null, flags);
		out.writeParcelable(mActionIntent, flags);
		out.writeString(mTitle != null ? mTitle.toString() : null);
		out.writeString(mContentDescription != null ? mContentDescription.toString() : null);
		out.writeBoolean(mEnabled);
		out.writeBoolean(mShouldShowIcon);
	}

	public void dump(String prefix, PrintWriter pw) {
		pw.print(prefix);
		pw.print("title=" + mTitle);
		pw.print(" enabled=" + mEnabled);
		pw.print(" contentDescription=" + mContentDescription);
		pw.print(" icon=" + mIcon);
		pw.print(" action=" + mActionIntent);
		pw.print(" shouldShowIcon=" + mShouldShowIcon);
		pw.println();
	}

	public static final Parcelable.Creator<RemoteAction> CREATOR = new Parcelable.Creator<RemoteAction>() {
		public RemoteAction createFromParcel(Parcel in) {
			return new RemoteAction(in);
		}

		public RemoteAction[] newArray(int size) {
			return new RemoteAction[size];
		}
	};
}
