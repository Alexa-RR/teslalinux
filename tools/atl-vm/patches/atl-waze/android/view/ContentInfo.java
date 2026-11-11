/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ClipData;
import android.content.ClipDescription;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pair;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Holds all the relevant data for a request to {@link View#performReceiveContent}.
 *
 * teslalinux: AOSP, minus the InputContentInfo / DragAndDropPermissions holders (both are
 * system-granted URI permissions that do not exist in this runtime).
 */
public final class ContentInfo implements Parcelable {
	/** Specifies the UI through which content is being inserted. Future versions of Android may support additional values. */
	public static final int SOURCE_APP = 0;
	public static final int SOURCE_CLIPBOARD = 1;
	public static final int SOURCE_INPUT_METHOD = 2;
	public static final int SOURCE_DRAG_AND_DROP = 3;
	public static final int SOURCE_AUTOFILL = 4;
	public static final int SOURCE_PROCESS_TEXT = 5;

	/** Returns the symbolic name of the given source. @hide */
	static String sourceToString(int source) {
		switch (source) {
			case SOURCE_APP: return "SOURCE_APP";
			case SOURCE_CLIPBOARD: return "SOURCE_CLIPBOARD";
			case SOURCE_INPUT_METHOD: return "SOURCE_INPUT_METHOD";
			case SOURCE_DRAG_AND_DROP: return "SOURCE_DRAG_AND_DROP";
			case SOURCE_AUTOFILL: return "SOURCE_AUTOFILL";
			case SOURCE_PROCESS_TEXT: return "SOURCE_PROCESS_TEXT";
		}
		return String.valueOf(source);
	}

	/** Flag requesting that the content should be converted to plain text prior to inserting. */
	public static final int FLAG_CONVERT_TO_PLAIN_TEXT = 1 << 0;

	/** Returns the symbolic names of the set flags or {@code "0"} if no flags are set. @hide */
	static String flagsToString(int flags) {
		if (flags != 0) {
			ArrayList<String> names = new ArrayList<>();
			if ((flags & FLAG_CONVERT_TO_PLAIN_TEXT) != 0) {
				names.add("FLAG_CONVERT_TO_PLAIN_TEXT");
			}
			return String.join("|", names);
		}
		return "0";
	}

	@NonNull
	private final ClipData mClip;
	private final int mSource;
	private final int mFlags;
	@Nullable
	private final Uri mLinkUri;
	@Nullable
	private final Bundle mExtras;

	private ContentInfo(Builder b) {
		this.mClip = Objects.requireNonNull(b.mClip);
		this.mSource = b.mSource;
		this.mFlags = b.mFlags;
		this.mLinkUri = b.mLinkUri;
		this.mExtras = b.mExtras;
	}

	/** Releases URI permissions; no system-granted permissions exist in this runtime, so nothing to release. */
	public void releasePermissions() {
	}

	@NonNull
	@Override
	public String toString() {
		return "ContentInfo{" + "clip=" + mClip + ", source=" + sourceToString(mSource) + ", flags=" + flagsToString(mFlags)
				+ ", linkUri=" + mLinkUri + ", extras=" + mExtras + "}";
	}

	/** The data to be inserted. */
	@NonNull
	public ClipData getClip() {
		return mClip;
	}

	/** The source of the operation. See {@code SOURCE_} constants. */
	public int getSource() {
		return mSource;
	}

	/** Optional flags that control the insertion behavior. See {@code FLAG_} constants. */
	public int getFlags() {
		return mFlags;
	}

	/** Optional http/https URI for the content that may be provided by the IME. */
	@Nullable
	public Uri getLinkUri() {
		return mLinkUri;
	}

	/** Optional additional metadata. */
	@Nullable
	public Bundle getExtras() {
		return mExtras;
	}

	/**
	 * Partitions this content based on the given predicate. The first element is the content that
	 * matched the predicate, the second the content that did not; either may be null.
	 */
	@NonNull
	public Pair<ContentInfo, ContentInfo> partition(@NonNull java.util.function.Predicate<ClipData.Item> itemPredicate) {
		if (mClip.getItemCount() == 1) {
			boolean matched = itemPredicate.test(mClip.getItemAt(0));
			return Pair.create(matched ? this : null, matched ? null : this);
		}
		ArrayList<ClipData.Item> acceptedItems = new ArrayList<>();
		ArrayList<ClipData.Item> remainingItems = new ArrayList<>();
		for (int i = 0; i < mClip.getItemCount(); i++) {
			ClipData.Item item = mClip.getItemAt(i);
			if (itemPredicate.test(item)) {
				acceptedItems.add(item);
			} else {
				remainingItems.add(item);
			}
		}
		if (acceptedItems.isEmpty()) {
			return Pair.create(null, this);
		}
		if (remainingItems.isEmpty()) {
			return Pair.create(this, null);
		}
		ContentInfo accepted = new Builder(this).setClip(buildClip(mClip.getDescription(), acceptedItems)).build();
		ContentInfo remaining = new Builder(this).setClip(buildClip(mClip.getDescription(), remainingItems)).build();
		return Pair.create(accepted, remaining);
	}

	private static ClipData buildClip(ClipDescription description, ArrayList<ClipData.Item> items) {
		ClipData clip = new ClipData(description, items.get(0));
		for (int i = 1; i < items.size(); i++) {
			clip.addItem(items.get(i));
		}
		return clip;
	}

	/** Builder for {@link ContentInfo}. */
	public static final class Builder {
		@NonNull
		private ClipData mClip;
		private int mSource;
		private int mFlags;
		@Nullable
		private Uri mLinkUri;
		@Nullable
		private Bundle mExtras;

		/** Creates a new builder initialized with the data from the given object (shallow copy). */
		public Builder(@NonNull ContentInfo other) {
			mClip = other.mClip;
			mSource = other.mSource;
			mFlags = other.mFlags;
			mLinkUri = other.mLinkUri;
			mExtras = other.mExtras;
		}

		/** Creates a new builder. */
		public Builder(@NonNull ClipData clip, int source) {
			mClip = clip;
			mSource = source;
		}

		@NonNull
		public Builder setClip(@NonNull ClipData clip) {
			mClip = clip;
			return this;
		}

		@NonNull
		public Builder setSource(int source) {
			mSource = source;
			return this;
		}

		@NonNull
		public Builder setFlags(int flags) {
			mFlags = flags;
			return this;
		}

		@NonNull
		public Builder setLinkUri(@Nullable Uri linkUri) {
			mLinkUri = linkUri;
			return this;
		}

		@NonNull
		public Builder setExtras(@Nullable Bundle extras) {
			mExtras = extras;
			return this;
		}

		@NonNull
		public ContentInfo build() {
			return new ContentInfo(this);
		}
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(@NonNull Parcel dest, int flags) {
		dest.writeParcelable(mClip, flags);
		dest.writeInt(mSource);
		dest.writeInt(mFlags);
		dest.writeString(mLinkUri == null ? null : mLinkUri.toString());
		dest.writeBundle(mExtras);
	}

	public static final Parcelable.Creator<ContentInfo> CREATOR = new Parcelable.Creator<ContentInfo>() {
		@NonNull
		@Override
		public ContentInfo createFromParcel(Parcel parcel) {
			ClipData clip;
			try {
				clip = (ClipData) parcel.readParcelable(ClipData.class.getClassLoader());
			} catch (ReflectiveOperationException e) {
				throw new android.os.BadParcelableException("Cannot unparcel ClipData: " + e);
			}
			int source = parcel.readInt();
			int flags = parcel.readInt();
			String link = parcel.readString();
			Bundle extras = parcel.readBundle();
			return new ContentInfo.Builder(clip, source).setFlags(flags).setLinkUri(link == null ? null : Uri.parse(link)).setExtras(extras).build();
		}

		@Override
		public ContentInfo[] newArray(int size) {
			return new ContentInfo[size];
		}
	};
}
