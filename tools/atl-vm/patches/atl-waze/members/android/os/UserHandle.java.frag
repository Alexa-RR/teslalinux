	/** AOSP: writes a handle or USER_NULL for null. */
	public static void writeToParcel(UserHandle h, Parcel out) {
		out.writeInt(h != null ? h.mHandle : USER_NULL);
	}

	public static UserHandle readFromParcel(Parcel in) {
		int h = in.readInt();
		return h != USER_NULL ? new UserHandle(h) : null;
	}
