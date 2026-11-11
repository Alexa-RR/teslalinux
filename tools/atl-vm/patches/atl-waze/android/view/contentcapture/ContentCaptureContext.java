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
package android.view.contentcapture;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.LocusId;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Display;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * Context associated with a {@link ContentCaptureSession}.
 *
 * teslalinux: the app-side (public SDK) part of the AOSP class. The @SystemApi part (activity
 * component, task/activity id, window token, flags) is filled in by the content capture system
 * service when a session starts; this runtime has no such service, so those fields keep the
 * "client context only" values and the service-side constructor is not provided. Parcelling is
 * limited to the client fields for the same reason.
 */
public final class ContentCaptureContext implements Parcelable {

	/** @hide */
	public static final int FLAG_DISABLED_BY_APP = 0x1;
	/** @hide */
	public static final int FLAG_DISABLED_BY_FLAG_SECURE = 0x2;
	/** @hide */
	public static final int FLAG_RECONNECTED = 0x4;
	/** @hide */
	public static final int FLAG_DISABLED_FLUSH_FOR_VIEW_TREE_APPEARING = 1 << 3;

	private final boolean mHasClientContext;

	// Fields below are set by app on Builder
	private final @Nullable Bundle mExtras;
	private final @Nullable LocusId mId;

	// Fields below are set by server when the session starts
	private final @Nullable ComponentName mComponentName;
	private final int mFlags;
	private final int mDisplayId;

	// Fields below are set by the service upon "delivery" and are not marshalled in the parcel
	private int mParentSessionId = ContentCaptureSession.NO_SESSION_ID;

	private ContentCaptureContext(@NonNull Builder builder) {
		mHasClientContext = true;
		mExtras = builder.mExtras;
		mId = builder.mId;

		mComponentName  = null;
		mFlags = 0;
		mDisplayId = Display.INVALID_DISPLAY;
	}

	/** @hide */
	public ContentCaptureContext(@Nullable ContentCaptureContext original, int extraFlags) {
		mHasClientContext = original.mHasClientContext;
		mExtras = original.mExtras;
		mId = original.mId;
		mComponentName = original.mComponentName;
		mFlags = original.mFlags | extraFlags;
		mDisplayId = original.mDisplayId;
	}

	@Nullable
	public Bundle getExtras() {
		return mExtras;
	}

	@Nullable
	public LocusId getLocusId() {
		return mId;
	}

	/** @hide */
	public @Nullable ComponentName getActivityComponent() {
		return mComponentName;
	}

	/** @hide */
	public @Nullable ContentCaptureSessionId getParentSessionId() {
		return mParentSessionId == ContentCaptureSession.NO_SESSION_ID ? null
				: new ContentCaptureSessionId(mParentSessionId);
	}

	/** @hide */
	public void setParentSessionId(int parentSessionId) {
		mParentSessionId = parentSessionId;
	}

	/** @hide */
	public int getDisplayId() {
		return mDisplayId;
	}

	/** @hide */
	public int getFlags() {
		return mFlags;
	}

	@NonNull
	public static ContentCaptureContext forLocusId(@NonNull String id) {
		return new Builder(new LocusId(id)).build();
	}

	/** Builder for {@link ContentCaptureContext} objects. */
	public static final class Builder {
		private Bundle mExtras;
		private final LocusId mId;
		private boolean mDestroyed;

		public Builder(@NonNull LocusId id) {
			mId = Objects.requireNonNull(id);
		}

		@NonNull
		public Builder setExtras(@NonNull Bundle extras) {
			mExtras =  Objects.requireNonNull(extras);
			throwIfDestroyed();
			return this;
		}

		@NonNull
		public ContentCaptureContext build() {
			throwIfDestroyed();
			mDestroyed = true;
			return new ContentCaptureContext(this);
		}

		private void throwIfDestroyed() {
			Preconditions.checkState(!mDestroyed, "Already called #build()");
		}
	}

	/** @hide */
	public void dump(PrintWriter pw) {
		if (mComponentName != null) {
			pw.print("activity="); pw.print(mComponentName.flattenToShortString());
		}
		if (mId != null) {
			pw.print(", id="); mId.dump(pw);
		}
		pw.print(", displayId="); pw.print(mDisplayId);
		if (mParentSessionId != ContentCaptureSession.NO_SESSION_ID) {
			pw.print(", parentId="); pw.print(mParentSessionId);
		}
		if (mFlags > 0) {
			pw.print(", flags="); pw.print(mFlags);
		}
		if (mExtras != null) {
			// NOTE: cannot dump because it could contain PII
			pw.print(", hasExtras");
		}
	}

	private boolean fromServer() {
		return mComponentName != null;
	}

	@Override
	public String toString() {
		final StringBuilder builder = new StringBuilder("Context[");

		if (fromServer()) {
			builder.append("act=").append(mComponentName.flattenToShortString())
				.append(", displayId=").append(mDisplayId)
				.append(", flags=").append(mFlags);
		} else {
			builder.append("id=").append(mId);
			if (mExtras != null) {
				// NOTE: cannot print because it could contain PII
				builder.append(", hasExtras");
			}
		}
		if (mParentSessionId != ContentCaptureSession.NO_SESSION_ID) {
			builder.append(", parentId=").append(mParentSessionId);
		}
		return builder.append(']').toString();
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel parcel, int flags) {
		parcel.writeInt(mHasClientContext ? 1 : 0);
		if (mHasClientContext) {
			parcel.writeString(mId == null ? null : mId.getId());
			parcel.writeBundle(mExtras);
		}
	}

	public static final @NonNull Parcelable.Creator<ContentCaptureContext> CREATOR = new Parcelable.Creator<ContentCaptureContext>() {
		@Override
		@NonNull
		public ContentCaptureContext createFromParcel(Parcel parcel) {
			final boolean hasClientContext = parcel.readInt() == 1;
			if (!hasClientContext)
				return null;
			// Must reconstruct the client context using the Builder API
			final LocusId id = new LocusId(parcel.readString());
			final Bundle extras = parcel.readBundle();
			final Builder builder = new Builder(id);
			if (extras != null) builder.setExtras(extras);
			return new ContentCaptureContext(builder);
		}

		@Override
		@NonNull
		public ContentCaptureContext[] newArray(int size) {
			return new ContentCaptureContext[size];
		}
	};
}
