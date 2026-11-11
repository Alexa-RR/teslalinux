/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.content;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.text.TextUtils;

import java.util.ArrayList;

/**
 * Meta-data describing the contents of a {@link ClipData}. Provides enough information to know if
 * you can handle the ClipData, but not the data itself.
 *
 * teslalinux: AOSP minus the text-classification confidence map (TextClassifier is not part of this
 * runtime) and the proto dump.
 */
public class ClipDescription implements Parcelable {
	public static final String MIMETYPE_TEXT_PLAIN = "text/plain";
	public static final String MIMETYPE_TEXT_HTML = "text/html";
	public static final String MIMETYPE_TEXT_URILIST = "text/uri-list";
	public static final String MIMETYPE_TEXT_INTENT = "text/vnd.android.intent";
	public static final String MIMETYPE_APPLICATION_ACTIVITY = "application/vnd.android.activity";
	public static final String MIMETYPE_APPLICATION_SHORTCUT = "application/vnd.android.shortcut";
	public static final String MIMETYPE_APPLICATION_TASK = "application/vnd.android.task";
	/** @hide */
	public static final String MIMETYPE_UNKNOWN = "application/octet-stream";

	/** The name of the extra used to define a component name when copying/dragging an app icon from Launcher. */
	public static final String EXTRA_TARGET_COMPONENT_NAME = "android.content.extra.TARGET_COMPONENT_NAME";
	/** The name of the extra used to define a user serial number when copying/dragging an app icon from Launcher. */
	public static final String EXTRA_USER_SERIAL_NUMBER = "android.content.extra.USER_SERIAL_NUMBER";
	/** The name of the extra used to define an Activity options bundle. */
	public static final String EXTRA_ACTIVITY_OPTIONS = "android.content.extra.ACTIVITY_OPTIONS";
	/** The name of the extra used to define a logical group for drag and drop. */
	public static final String EXTRA_LOGICAL_DOCUMENT_ID = "android.content.extra.LOGICAL_DOCUMENT_ID";
	/** The name of the extra used to indicate that a package has not been drag-and-dropped into a surface. */
	public static final String EXTRA_HIDE_DRAG_SOURCE_TASK_ID = "android.content.extra.HIDE_DRAG_SOURCE_TASK_ID";
	/** The name of the extra used to indicate that a package is not allowed to be dropped onto a task. */
	public static final String EXTRA_PENDING_INTENT = "android.intent.extra.PENDING_INTENT";

	/** Classification constants (the text classifier that computes them is absent here). */
	public static final int CLASSIFICATION_NOT_COMPLETE = 1;
	public static final int CLASSIFICATION_NOT_PERFORMED = 2;
	public static final int CLASSIFICATION_COMPLETE = 3;

	final CharSequence mLabel;
	private final ArrayList<String> mMimeTypes;
	private PersistableBundle mExtras;
	private long mTimeStamp;
	private boolean mIsStyledText;
	private int mClassificationStatus = CLASSIFICATION_NOT_PERFORMED;

	/** Create a new clip. */
	public ClipDescription(CharSequence label, String[] mimeTypes) {
		if (mimeTypes == null) {
			throw new NullPointerException("mimeTypes is null");
		}
		mLabel = label;
		mMimeTypes = new ArrayList<String>();
		for (String mimeType : mimeTypes) {
			mMimeTypes.add(mimeType);
		}
	}

	/** Create a copy of a ClipDescription. */
	public ClipDescription(ClipDescription o) {
		mLabel = o.mLabel;
		mMimeTypes = new ArrayList<String>(o.mMimeTypes);
		mTimeStamp = o.mTimeStamp;
		mIsStyledText = o.mIsStyledText;
		mClassificationStatus = o.mClassificationStatus;
		mExtras = o.mExtras;
	}

	/** Helper to compare two MIME types, where one may be a pattern. */
	public static boolean compareMimeTypes(String concreteType, String desiredType) {
		final int typeLength = desiredType.length();
		if (typeLength == 3 && desiredType.equals("*/*")) {
			return true;
		}

		final int slashpos = desiredType.indexOf('/');
		if (slashpos > 0) {
			if (typeLength == slashpos + 2 && desiredType.charAt(slashpos + 1) == '*') {
				if (desiredType.regionMatches(0, concreteType, 0, slashpos + 1)) {
					return true;
				}
			} else if (desiredType.equals(concreteType)) {
				return true;
			}
		}

		return false;
	}

	/** Used for setting the timestamp at which the associated {@link ClipData} is copied to global clipboard. @hide */
	public void setTimestamp(long timeStamp) {
		mTimeStamp = timeStamp;
	}

	/** Return the timestamp at which the associated {@link ClipData} is copied to global clipboard, in milliseconds since epoch. */
	public long getTimestamp() {
		return mTimeStamp;
	}

	/** Return the label for this clip. */
	public CharSequence getLabel() {
		return mLabel;
	}

	/** Check whether the clip description contains the given MIME type. */
	public boolean hasMimeType(String mimeType) {
		final int size = mMimeTypes.size();
		for (int i = 0; i < size; i++) {
			if (compareMimeTypes(mMimeTypes.get(i), mimeType)) {
				return true;
			}
		}
		return false;
	}

	/** Check whether the clip description contains any of the given MIME types. @hide */
	public boolean hasMimeType(@NonNull String[] targetMimeTypes) {
		for (String targetMimeType : targetMimeTypes) {
			if (hasMimeType(targetMimeType)) {
				return true;
			}
		}
		return false;
	}

	/** Filter the clip description MIME types by the given MIME type. */
	public String[] filterMimeTypes(String mimeType) {
		ArrayList<String> array = null;
		final int size = mMimeTypes.size();
		for (int i = 0; i < size; i++) {
			if (compareMimeTypes(mMimeTypes.get(i), mimeType)) {
				if (array == null) {
					array = new ArrayList<String>();
				}
				array.add(mMimeTypes.get(i));
			}
		}
		if (array == null) {
			return null;
		}
		String[] rawArray = new String[array.size()];
		array.toArray(rawArray);
		return rawArray;
	}

	/** Return the number of MIME types the clip is available in. */
	public int getMimeTypeCount() {
		return mMimeTypes.size();
	}

	/** Return one of the possible clip MIME types. */
	public String getMimeType(int index) {
		return mMimeTypes.get(index);
	}

	/** Add MIME types to the clip description. @hide */
	public void addMimeTypes(String[] mimeTypes) {
		for (int i = 0; i != mimeTypes.length; i++) {
			final String mimeType = mimeTypes[i];
			if (!mMimeTypes.contains(mimeType)) {
				mMimeTypes.add(mimeType);
			}
		}
	}

	/** Retrieve extended data from the clip description. */
	public PersistableBundle getExtras() {
		return mExtras;
	}

	/** Add extended data to the clip description. */
	public void setExtras(PersistableBundle extras) {
		mExtras = new PersistableBundle(extras);
	}

	/** Return whether the clip contains styled text. */
	public boolean isStyledText() {
		return mIsStyledText;
	}

	/** @hide */
	public void setIsStyledText(boolean isStyledText) {
		mIsStyledText = isStyledText;
	}

	/** Returns whether the text classification (never run here) is complete. */
	public int getClassificationStatus() {
		return mClassificationStatus;
	}

	/** @hide */
	public void setClassificationStatus(int status) {
		mClassificationStatus = status;
	}

	/** Returns the confidence that the clip belongs to the given entity type; no classifier ran, so 0. */
	public float getConfidenceScore(@NonNull String entity) {
		if (mClassificationStatus != CLASSIFICATION_COMPLETE) {
			throw new IllegalStateException("Classification not complete");
		}
		return 0f;
	}

	/** @hide */
	public void validate() {
		if (mMimeTypes == null) {
			throw new NullPointerException("null mime types");
		}
		final int size = mMimeTypes.size();
		if (size <= 0) {
			throw new IllegalArgumentException("must have at least 1 mime type");
		}
		for (int i = 0; i < size; i++) {
			if (mMimeTypes.get(i) == null) {
				throw new NullPointerException("mime type at " + i + " is null");
			}
		}
	}

	@Override
	public String toString() {
		StringBuilder b = new StringBuilder(128);

		b.append("ClipDescription { ");
		toShortString(b, true);
		b.append(" }");

		return b.toString();
	}

	/** @hide */
	public boolean toShortString(StringBuilder b, boolean redactContent) {
		boolean first = !toShortStringTypesOnly(b);
		if (mLabel != null) {
			if (!first) {
				b.append(' ');
			}
			first = false;
			if (redactContent) {
				b.append("hasLabel(").append(mLabel.length()).append(')');
			} else {
				b.append('"').append(mLabel).append('"');
			}
		}
		if (mExtras != null) {
			if (!first) {
				b.append(' ');
			}
			first = false;
			if (redactContent) {
				b.append("hasExtras");
			} else {
				b.append(mExtras.toString());
			}
		}
		if (mTimeStamp > 0) {
			if (!first) {
				b.append(' ');
			}
			first = false;
			b.append('<');
			b.append(mTimeStamp);
			b.append('>');
		}
		return !first;
	}

	/** @hide */
	public boolean toShortStringTypesOnly(StringBuilder b) {
		boolean first = true;
		final int size = mMimeTypes.size();
		for (int i = 0; i < size; i++) {
			if (!first) {
				b.append(' ');
			}
			first = false;
			b.append(mMimeTypes.get(i));
		}
		return !first;
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeString(mLabel == null ? null : mLabel.toString());
		dest.writeStringList(mMimeTypes);
		dest.writeBundle(mExtras == null ? null : toBundle(mExtras));
		dest.writeLong(mTimeStamp);
		dest.writeBoolean(mIsStyledText);
		dest.writeInt(mClassificationStatus);
	}

	ClipDescription(Parcel in) {
		mLabel = in.readString();
		mMimeTypes = in.createStringArrayList();
		android.os.Bundle extras = in.readBundle();
		mExtras = extras == null ? null : toPersistable(extras);
		mTimeStamp = in.readLong();
		mIsStyledText = in.readBoolean();
		mClassificationStatus = in.readInt();
	}

	private static android.os.Bundle toBundle(PersistableBundle p) {
		android.os.Bundle b = new android.os.Bundle();
		for (String key : p.keySet()) {
			Object v = p.get(key);
			if (v instanceof String) b.putString(key, (String) v);
			else if (v instanceof Integer) b.putInt(key, (Integer) v);
			else if (v instanceof Long) b.putLong(key, (Long) v);
			else if (v instanceof Boolean) b.putBoolean(key, (Boolean) v);
			else if (v instanceof String[]) b.putStringArray(key, (String[]) v);
		}
		return b;
	}

	/** ATL parcels extras as a Bundle; rebuild the persistable subset (AOSP PersistableBundle only holds these types). */
	private static PersistableBundle toPersistable(android.os.Bundle b) {
		PersistableBundle p = new PersistableBundle();
		for (String key : b.keySet()) {
			Object v = b.get(key);
			if (v instanceof String) p.putString(key, (String) v);
			else if (v instanceof Integer) p.putInt(key, (Integer) v);
			else if (v instanceof Long) p.putLong(key, (Long) v);
			else if (v instanceof Boolean) p.putBoolean(key, (Boolean) v);
			else if (v instanceof String[]) p.putStringArray(key, (String[]) v);
		}
		return p;
	}

	public static final @NonNull Parcelable.Creator<ClipDescription> CREATOR = new Parcelable.Creator<ClipDescription>() {
		public ClipDescription createFromParcel(Parcel source) {
			return new ClipDescription(source);
		}

		public ClipDescription[] newArray(int size) {
			return new ClipDescription[size];
		}
	};
}
