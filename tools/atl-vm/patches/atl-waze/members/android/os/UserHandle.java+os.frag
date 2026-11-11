	/** AOSP: the user id of the system user. */
	public static final int USER_SYSTEM = 0;

	/** AOSP: the system user's handle. */
	public static final UserHandle SYSTEM = new UserHandle(USER_SYSTEM);

	/** AOSP: the UserHandle of the user a uid belongs to. */
	public static UserHandle getUserHandleForUid(int uid) {
		return of(getUserId(uid));
	}

	/** AOSP: the UserHandle for a user id (the shared constants for the well-known ones). */
	public static UserHandle of(int userId) {
		if (userId == USER_SYSTEM) return SYSTEM;
		switch (userId) {
			case USER_ALL:
				return ALL;
			case USER_CURRENT:
				return CURRENT;
			case USER_CURRENT_OR_SELF:
				return CURRENT_OR_SELF;
		}
		return new UserHandle(userId);
	}

	/** AOSP: whether this is the system user. */
	public boolean isSystem() {
		return equals(SYSTEM);
	}

	/** AOSP: whether this is the owner (system user). */
	public boolean isOwner() {
		return equals(OWNER);
	}

	/** AOSP: the uid of an app id in this user. */
	public int getUid(int appId) {
		return getUid(getIdentifier(), appId);
	}

	@Override
	public void writeToParcel(Parcel out, int flags) {
		out.writeInt(mHandle);
	}

	/** AOSP: read from a parcel written by writeToParcel. */
	public UserHandle(Parcel in) {
		mHandle = in.readInt();
	}

	public static final Parcelable.Creator<UserHandle> CREATOR = new Parcelable.Creator<UserHandle>() {
		@Override
		public UserHandle createFromParcel(Parcel in) {
			// Try to avoid allocation; use of() here. Keep this and the constructor in sync.
			return of(in.readInt());
		}

		@Override
		public UserHandle[] newArray(int size) {
			return new UserHandle[size];
		}
	};
