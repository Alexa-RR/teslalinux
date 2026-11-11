/*
 * Copyright (C) 2021 The Android Open Source Project
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
import java.util.Objects;

/**
 * teslalinux: AOSP's PictureInPictureUiState (android15-release): the UI state of an activity in
 * picture-in-picture, given to Activity.onPictureInPictureUiStateChanged. This platform has no PiP,
 * so the system never creates one; the class exists for apps that read it.
 */
public final class PictureInPictureUiState implements Parcelable {

	private final boolean mIsStashed;
	private final boolean mIsTransitioningToPip;

	PictureInPictureUiState(Parcel in) {
		mIsStashed = in.readBoolean();
		mIsTransitioningToPip = in.readBoolean();
	}

	/** @hide */
	public PictureInPictureUiState(boolean isStashed) {
		this(isStashed, false);
	}

	private PictureInPictureUiState(boolean isStashed, boolean isTransitioningToPip) {
		mIsStashed = isStashed;
		mIsTransitioningToPip = isTransitioningToPip;
	}

	public boolean isStashed() {
		return mIsStashed;
	}

	public boolean isTransitioningToPip() {
		return mIsTransitioningToPip;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof PictureInPictureUiState))
			return false;
		PictureInPictureUiState that = (PictureInPictureUiState) o;
		return mIsStashed == that.mIsStashed && mIsTransitioningToPip == that.mIsTransitioningToPip;
	}

	@Override
	public int hashCode() {
		return Objects.hash(mIsStashed, mIsTransitioningToPip);
	}

	@Override
	public String toString() {
		return "PictureInPictureUiState(isStashed=" + mIsStashed + " isTransitioningToPip=" + mIsTransitioningToPip + ")";
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel out, int flags) {
		out.writeBoolean(mIsStashed);
		out.writeBoolean(mIsTransitioningToPip);
	}

	public static final Creator<PictureInPictureUiState> CREATOR = new Creator<PictureInPictureUiState>() {
		public PictureInPictureUiState createFromParcel(Parcel in) {
			return new PictureInPictureUiState(in);
		}

		public PictureInPictureUiState[] newArray(int size) {
			return new PictureInPictureUiState[size];
		}
	};

	/** AOSP (API 35): builds a state for tests and for the system. */
	public static final class Builder {
		private boolean mIsStashed;
		private boolean mIsTransitioningToPip;

		public Builder() {
		}

		public Builder setStashed(boolean isStashed) {
			mIsStashed = isStashed;
			return this;
		}

		public Builder setTransitioningToPip(boolean isTransitioningToPip) {
			mIsTransitioningToPip = isTransitioningToPip;
			return this;
		}

		public PictureInPictureUiState build() {
			return new PictureInPictureUiState(mIsStashed, mIsTransitioningToPip);
		}
	}
}
