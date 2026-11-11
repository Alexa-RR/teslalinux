	/* AOSP fields ATL's Intent lacked */
	private android.util.ArraySet<String> tlCategories;
	private ClipData tlClipData;
	private Rect tlSourceBounds;
	private int tlContentUserHint = android.os.UserHandle.USER_CURRENT;

	/** AOSP: removes a category. */
	public void removeCategory(String category) {
		if (tlCategories != null) {
			tlCategories.remove(category);
			if (tlCategories.size() == 0)
				tlCategories = null;
		}
	}

	/** AOSP (API 33): the Parcelable array extra when every item is an instance of clazz, else null. */
	@SuppressWarnings("unchecked")
	public <T> T[] getParcelableArrayExtra(String name, Class<T> clazz) {
		return extras == null ? null : extras.getParcelableArray(name, clazz);
	}

	@Override
	public int describeContents() {
		return (extras != null) ? extras.describeContents() : 0;
	}

	/** AOSP Intent.writeToParcel (the original-intent slot is always empty here). */
	@Override
	public void writeToParcel(android.os.Parcel out, int flags) {
		out.writeString8(action);
		Uri.writeToParcel(out, data);
		out.writeString8(type);
		out.writeString8(tlIdentifier);
		out.writeInt(this.flags);
		out.writeString8(packageName);
		ComponentName.writeToParcel(component, out);

		if (tlSourceBounds != null) {
			out.writeInt(1);
			// Rect.writeToParcel
			out.writeInt(tlSourceBounds.left);
			out.writeInt(tlSourceBounds.top);
			out.writeInt(tlSourceBounds.right);
			out.writeInt(tlSourceBounds.bottom);
		} else {
			out.writeInt(0);
		}

		if (tlCategories != null) {
			final int N = tlCategories.size();
			out.writeInt(N);
			for (int i = 0; i < N; i++) {
				out.writeString8(tlCategories.valueAt(i));
			}
		} else {
			out.writeInt(0);
		}

		if (selector != null) {
			out.writeInt(1);
			selector.writeToParcel(out, flags);
		} else {
			out.writeInt(0);
		}

		if (tlClipData != null) {
			out.writeInt(1);
			tlClipData.writeToParcel(out, flags);
		} else {
			out.writeInt(0);
		}
		out.writeInt(tlContentUserHint);
		out.writeBundle(extras);
		// mOriginalIntent: intents here are never redirected, so there is none
		out.writeInt(0);
	}

	/** AOSP: an intent read from a parcel written by writeToParcel. */
	protected Intent(android.os.Parcel in) {
		readFromParcel(in);
	}

	/** AOSP Intent.readFromParcel. */
	public void readFromParcel(android.os.Parcel in) {
		setAction(in.readString8());
		data = Uri.CREATOR.createFromParcel(in);
		type = in.readString8();
		tlIdentifier = in.readString8();
		flags = in.readInt();
		packageName = in.readString8();
		component = ComponentName.readFromParcel(in);

		if (in.readInt() != 0) {
			tlSourceBounds = new Rect(in.readInt(), in.readInt(), in.readInt(), in.readInt());
		} else {
			tlSourceBounds = null;
		}

		int N = in.readInt();
		if (N > 0) {
			tlCategories = new android.util.ArraySet<String>();
			for (int i = 0; i < N; i++) {
				tlCategories.add(in.readString8().intern());
			}
		} else {
			tlCategories = null;
		}

		if (in.readInt() != 0) {
			selector = new Intent(in);
		} else {
			selector = null;
		}

		if (in.readInt() != 0) {
			tlClipData = ClipData.CREATOR.createFromParcel(in);
		} else {
			tlClipData = null;
		}
		tlContentUserHint = in.readInt();
		Bundle b = in.readBundle();
		extras = b != null ? b : new Bundle();
		if (in.readInt() != 0) {
			// an original intent written by AOSP: read past it
			new Intent(in);
		}
	}

	public static final android.os.Parcelable.Creator<Intent> CREATOR = new android.os.Parcelable.Creator<Intent>() {
		@Override
		public Intent createFromParcel(android.os.Parcel in) {
			return new Intent(in);
		}

		@Override
		public Intent[] newArray(int size) {
			return new Intent[size];
		}
	};
