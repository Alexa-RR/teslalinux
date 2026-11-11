	/* AOSP: parcelling; the in-process parcel carries the string form, which Uri.parse restores */
	private static final int NULL_TYPE_ID = 0;
	private static final int STRING_TYPE_ID = 1;

	public static void writeToParcel(android.os.Parcel out, Uri uri) {
		if (uri == null) {
			out.writeInt(NULL_TYPE_ID);
		} else {
			out.writeInt(STRING_TYPE_ID);
			out.writeString(uri.toString());
		}
	}

	@Override
	public void writeToParcel(android.os.Parcel dest, int flags) {
		dest.writeInt(STRING_TYPE_ID);
		dest.writeString(toString());
	}

	@Override
	public int describeContents() {
		return 0;
	}

	public static final android.os.Parcelable.Creator<Uri> CREATOR = new android.os.Parcelable.Creator<Uri>() {
		@Override
		public Uri createFromParcel(android.os.Parcel in) {
			int type = in.readInt();
			if (type == NULL_TYPE_ID)
				return null;
			return Uri.parse(in.readString());
		}

		@Override
		public Uri[] newArray(int size) {
			return new Uri[size];
		}
	};
