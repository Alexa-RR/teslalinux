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

import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Rational;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * teslalinux: AOSP's PictureInPictureParams (android14-release), the parameters an activity gives
 * for picture-in-picture. This platform has no PiP (see Activity.enterPictureInPictureMode), but the
 * parameters are built, compared and read back as on AOSP.
 */
public final class PictureInPictureParams implements Parcelable {

	public static class Builder {
		private Rational mAspectRatio;
		private Rational mExpandedAspectRatio;
		private List<RemoteAction> mUserActions;
		private RemoteAction mCloseAction;
		private Rect mSourceRectHint;
		private Boolean mAutoEnterEnabled;
		private Boolean mSeamlessResizeEnabled;
		private CharSequence mTitle;
		private CharSequence mSubtitle;
		private Boolean mIsLaunchIntoPip;

		public Builder() {
		}

		public Builder(PictureInPictureParams original) {
			mAspectRatio = original.mAspectRatio;
			mUserActions = original.mUserActions;
			mCloseAction = original.mCloseAction;
			mSourceRectHint = original.mSourceRectHint;
			mAutoEnterEnabled = original.mAutoEnterEnabled;
			mSeamlessResizeEnabled = original.mSeamlessResizeEnabled;
			mTitle = original.mTitle;
			mSubtitle = original.mSubtitle;
			mIsLaunchIntoPip = original.mIsLaunchIntoPip;
			mExpandedAspectRatio = original.mExpandedAspectRatio;
		}

		public Builder setAspectRatio(Rational aspectRatio) {
			mAspectRatio = aspectRatio;
			return this;
		}

		public Builder setExpandedAspectRatio(Rational expandedAspectRatio) {
			mExpandedAspectRatio = expandedAspectRatio;
			return this;
		}

		public Builder setActions(List<RemoteAction> actions) {
			if (mUserActions != null) {
				mUserActions = null;
			}
			if (actions != null) {
				mUserActions = new ArrayList<>(actions);
			}
			return this;
		}

		public Builder setCloseAction(RemoteAction action) {
			mCloseAction = action;
			return this;
		}

		public Builder setSourceRectHint(Rect launchBounds) {
			if (launchBounds == null) {
				mSourceRectHint = null;
			} else {
				mSourceRectHint = new Rect(launchBounds);
			}
			return this;
		}

		public Builder setAutoEnterEnabled(boolean autoEnterEnabled) {
			mAutoEnterEnabled = autoEnterEnabled;
			return this;
		}

		public Builder setSeamlessResizeEnabled(boolean seamlessResizeEnabled) {
			mSeamlessResizeEnabled = seamlessResizeEnabled;
			return this;
		}

		public Builder setTitle(CharSequence title) {
			mTitle = title;
			return this;
		}

		public Builder setSubtitle(CharSequence subtitle) {
			mSubtitle = subtitle;
			return this;
		}

		/** @hide */
		public Builder setIsLaunchIntoPip(boolean isLaunchIntoPip) {
			mIsLaunchIntoPip = isLaunchIntoPip;
			return this;
		}

		public PictureInPictureParams build() {
			return new PictureInPictureParams(mAspectRatio, mExpandedAspectRatio, mUserActions,
			                                  mCloseAction, mSourceRectHint, mAutoEnterEnabled, mSeamlessResizeEnabled,
			                                  mTitle, mSubtitle, mIsLaunchIntoPip);
		}
	}

	private Rational mAspectRatio;
	private Rational mExpandedAspectRatio;
	private List<RemoteAction> mUserActions;
	private RemoteAction mCloseAction;
	private Rect mSourceRectHint;
	private Boolean mAutoEnterEnabled;
	private Boolean mSeamlessResizeEnabled;
	private CharSequence mTitle;
	private CharSequence mSubtitle;
	private Boolean mIsLaunchIntoPip;

	PictureInPictureParams() {
	}

	PictureInPictureParams(Rational aspectRatio, Rational expandedAspectRatio,
	                       List<RemoteAction> actions, RemoteAction closeAction, Rect sourceRectHint,
	                       Boolean autoEnterEnabled, Boolean seamlessResizeEnabled, CharSequence title,
	                       CharSequence subtitle, Boolean isLaunchIntoPip) {
		mAspectRatio = aspectRatio;
		mExpandedAspectRatio = expandedAspectRatio;
		mUserActions = actions;
		mCloseAction = closeAction;
		mSourceRectHint = sourceRectHint;
		mAutoEnterEnabled = autoEnterEnabled;
		mSeamlessResizeEnabled = seamlessResizeEnabled;
		mTitle = title;
		mSubtitle = subtitle;
		mIsLaunchIntoPip = isLaunchIntoPip;
	}

	/** @hide */
	public PictureInPictureParams(PictureInPictureParams other) {
		this(other.mAspectRatio, other.mExpandedAspectRatio, other.mUserActions,
		     other.mCloseAction, other.hasSourceBoundsHint() ? new Rect(other.getSourceRectHint()) : null,
		     other.mAutoEnterEnabled, other.mSeamlessResizeEnabled, other.mTitle,
		     other.mSubtitle, other.mIsLaunchIntoPip);
	}

	/** @hide AOSP: values set in otherArgs replace this object's. */
	public void copyOnlySet(PictureInPictureParams otherArgs) {
		if (otherArgs.hasSetAspectRatio()) {
			mAspectRatio = otherArgs.mAspectRatio;
		}
		mExpandedAspectRatio = otherArgs.mExpandedAspectRatio;
		if (otherArgs.hasSetActions()) {
			mUserActions = otherArgs.mUserActions;
		}
		if (otherArgs.hasSetCloseAction()) {
			mCloseAction = otherArgs.mCloseAction;
		}
		if (otherArgs.hasSourceBoundsHint()) {
			mSourceRectHint = new Rect(otherArgs.getSourceRectHint());
		}
		if (otherArgs.mAutoEnterEnabled != null) {
			mAutoEnterEnabled = otherArgs.mAutoEnterEnabled;
		}
		if (otherArgs.mSeamlessResizeEnabled != null) {
			mSeamlessResizeEnabled = otherArgs.mSeamlessResizeEnabled;
		}
		if (otherArgs.hasSetTitle()) {
			mTitle = otherArgs.mTitle;
		}
		if (otherArgs.hasSetSubtitle()) {
			mSubtitle = otherArgs.mSubtitle;
		}
		if (otherArgs.mIsLaunchIntoPip != null) {
			mIsLaunchIntoPip = otherArgs.mIsLaunchIntoPip;
		}
	}

	public float getAspectRatioFloat() {
		if (mAspectRatio != null) {
			return mAspectRatio.floatValue();
		}
		return 0f;
	}

	public Rational getAspectRatio() {
		return mAspectRatio;
	}

	/** @hide */
	public boolean hasSetAspectRatio() {
		return mAspectRatio != null;
	}

	public float getExpandedAspectRatioFloat() {
		if (mExpandedAspectRatio != null) {
			return mExpandedAspectRatio.floatValue();
		}
		return 0f;
	}

	public Rational getExpandedAspectRatio() {
		return mExpandedAspectRatio;
	}

	/** @hide */
	public boolean hasSetExpandedAspectRatio() {
		return mExpandedAspectRatio != null;
	}

	public List<RemoteAction> getActions() {
		if (mUserActions == null) {
			return new ArrayList<>();
		}
		return mUserActions;
	}

	/** @hide */
	public boolean hasSetActions() {
		return mUserActions != null;
	}

	/** @hide AOSP: at most maxActions actions are kept. */
	public void truncateActions(int size) {
		if (hasSetActions()) {
			mUserActions = mUserActions.subList(0, Math.min(mUserActions.size(), size));
		}
	}

	public RemoteAction getCloseAction() {
		return mCloseAction;
	}

	/** @hide */
	public boolean hasSetCloseAction() {
		return mCloseAction != null;
	}

	public Rect getSourceRectHint() {
		return mSourceRectHint;
	}

	/** @hide */
	public boolean hasSourceBoundsHint() {
		return mSourceRectHint != null && !mSourceRectHint.isEmpty();
	}

	public boolean isAutoEnterEnabled() {
		return mAutoEnterEnabled == null ? false : mAutoEnterEnabled;
	}

	public boolean isSeamlessResizeEnabled() {
		return mSeamlessResizeEnabled == null ? true : mSeamlessResizeEnabled;
	}

	/** @hide */
	public boolean hasSetTitle() {
		return mTitle != null;
	}

	public CharSequence getTitle() {
		return mTitle;
	}

	/** @hide */
	public boolean hasSetSubtitle() {
		return mSubtitle != null;
	}

	public CharSequence getSubtitle() {
		return mSubtitle;
	}

	/** @hide */
	public boolean isLaunchIntoPip() {
		return mIsLaunchIntoPip == null ? false : mIsLaunchIntoPip;
	}

	/** @hide */
	public boolean empty() {
		return !hasSourceBoundsHint() && !hasSetActions() && !hasSetCloseAction()
		    && !hasSetAspectRatio() && !hasSetExpandedAspectRatio() && mAutoEnterEnabled == null
		    && mSeamlessResizeEnabled == null && !hasSetTitle()
		    && !hasSetSubtitle() && mIsLaunchIntoPip == null;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof PictureInPictureParams))
			return false;
		PictureInPictureParams that = (PictureInPictureParams) o;
		return Objects.equals(mAutoEnterEnabled, that.mAutoEnterEnabled)
		    && Objects.equals(mSeamlessResizeEnabled, that.mSeamlessResizeEnabled)
		    && Objects.equals(mAspectRatio, that.mAspectRatio)
		    && Objects.equals(mExpandedAspectRatio, that.mExpandedAspectRatio)
		    && Objects.equals(mUserActions, that.mUserActions)
		    && Objects.equals(mCloseAction, that.mCloseAction)
		    && Objects.equals(mSourceRectHint, that.mSourceRectHint)
		    && TextUtils.equals(mTitle, that.mTitle)
		    && TextUtils.equals(mSubtitle, that.mSubtitle)
		    && Objects.equals(mIsLaunchIntoPip, that.mIsLaunchIntoPip);
	}

	@Override
	public int hashCode() {
		return Objects.hash(mAspectRatio, mExpandedAspectRatio, mUserActions, mCloseAction,
		                    mSourceRectHint, mAutoEnterEnabled, mSeamlessResizeEnabled, mTitle, mSubtitle,
		                    mIsLaunchIntoPip);
	}

	@Override
	public int describeContents() {
		return 0;
	}

	private static void writeRational(Parcel out, Rational r) {
		out.writeBoolean(r != null);
		if (r != null) {
			out.writeInt(r.getNumerator());
			out.writeInt(r.getDenominator());
		}
	}

	private static Rational readRational(Parcel in) {
		return in.readBoolean() ? new Rational(in.readInt(), in.readInt()) : null;
	}

	private static void writeBoolean(Parcel out, Boolean b) {
		out.writeInt(b == null ? -1 : (b ? 1 : 0));
	}

	private static Boolean readBoolean(Parcel in) {
		int v = in.readInt();
		return v < 0 ? null : v == 1;
	}

	@Override
	public void writeToParcel(Parcel out, int flags) {
		writeRational(out, mAspectRatio);
		writeRational(out, mExpandedAspectRatio);
		out.writeInt(mUserActions != null ? mUserActions.size() : -1);
		if (mUserActions != null) {
			for (RemoteAction a : mUserActions)
				a.writeToParcel(out, flags);
		}
		out.writeBoolean(mCloseAction != null);
		if (mCloseAction != null)
			mCloseAction.writeToParcel(out, flags);
		out.writeBoolean(mSourceRectHint != null);
		if (mSourceRectHint != null) {
			out.writeInt(mSourceRectHint.left);
			out.writeInt(mSourceRectHint.top);
			out.writeInt(mSourceRectHint.right);
			out.writeInt(mSourceRectHint.bottom);
		}
		writeBoolean(out, mAutoEnterEnabled);
		writeBoolean(out, mSeamlessResizeEnabled);
		out.writeString(mTitle != null ? mTitle.toString() : null);
		out.writeString(mSubtitle != null ? mSubtitle.toString() : null);
		writeBoolean(out, mIsLaunchIntoPip);
	}

	private PictureInPictureParams(Parcel in) {
		mAspectRatio = readRational(in);
		mExpandedAspectRatio = readRational(in);
		int n = in.readInt();
		if (n >= 0) {
			mUserActions = new ArrayList<>(n);
			for (int i = 0; i < n; i++)
				mUserActions.add(RemoteAction.CREATOR.createFromParcel(in));
		}
		if (in.readBoolean())
			mCloseAction = RemoteAction.CREATOR.createFromParcel(in);
		if (in.readBoolean())
			mSourceRectHint = new Rect(in.readInt(), in.readInt(), in.readInt(), in.readInt());
		mAutoEnterEnabled = readBoolean(in);
		mSeamlessResizeEnabled = readBoolean(in);
		mTitle = in.readString();
		mSubtitle = in.readString();
		mIsLaunchIntoPip = readBoolean(in);
	}

	@Override
	public String toString() {
		return "PictureInPictureParams("
		    + " aspectRatio=" + getAspectRatio()
		    + " expandedAspectRatio=" + mExpandedAspectRatio
		    + " sourceRectHint=" + getSourceRectHint()
		    + " hasSetActions=" + hasSetActions()
		    + " hasSetCloseAction=" + hasSetCloseAction()
		    + " isAutoPipEnabled=" + isAutoEnterEnabled()
		    + " isSeamlessResizeEnabled=" + isSeamlessResizeEnabled()
		    + " title=" + getTitle()
		    + " subtitle=" + getSubtitle()
		    + " isLaunchIntoPip=" + isLaunchIntoPip()
		    + ")";
	}

	public static final Creator<PictureInPictureParams> CREATOR = new Creator<PictureInPictureParams>() {
		public PictureInPictureParams createFromParcel(Parcel in) {
			return new PictureInPictureParams(in);
		}

		public PictureInPictureParams[] newArray(int size) {
			return new PictureInPictureParams[size];
		}
	};
}
