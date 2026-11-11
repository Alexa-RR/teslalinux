	/* AOSP: parcelling (package then class name) */
	@Override
	public void writeToParcel(android.os.Parcel out, int flags) {
		out.writeString(mPackage);
		out.writeString(mClass);
	}

	/** AOSP: writes a ComponentName or null (a null package name) to a parcel. */
	public static void writeToParcel(ComponentName c, android.os.Parcel out) {
		if (c != null) {
			c.writeToParcel(out, 0);
		} else {
			out.writeString(null);
		}
	}

	/** AOSP: reads what writeToParcel(ComponentName, Parcel) wrote. */
	public static ComponentName readFromParcel(android.os.Parcel in) {
		String pkg = in.readString();
		return pkg != null ? new ComponentName(pkg, in) : null;
	}

	/** AOSP: the component written by writeToParcel. */
	public ComponentName(android.os.Parcel in) {
		mPackage = in.readString();
		if (mPackage == null)
			throw new NullPointerException("package name is null");
		mClass = in.readString();
		if (mClass == null)
			throw new NullPointerException("class name is null");
	}

	private ComponentName(String pkg, android.os.Parcel in) {
		mPackage = pkg;
		mClass = in.readString();
	}

	public static final android.os.Parcelable.Creator<ComponentName> CREATOR = new android.os.Parcelable.Creator<ComponentName>() {
		@Override
		public ComponentName createFromParcel(android.os.Parcel in) {
			return new ComponentName(in);
		}

		@Override
		public ComponentName[] newArray(int size) {
			return new ComponentName[size];
		}
	};
