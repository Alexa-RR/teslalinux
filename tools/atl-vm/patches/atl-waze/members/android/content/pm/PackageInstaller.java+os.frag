	/*
	 * teslalinux: install sessions. Apps reach this device through the host's installer
	 * (tools/apps/install-app.sh), never through PackageInstaller sessions, so there are no
	 * sessions to list: the lists are empty and getSessionInfo finds nothing, as on a device with
	 * no install in progress.
	 */

	/** AOSP: all install sessions on the device (none). */
	public java.util.List<SessionInfo> getAllSessions() {
		return java.util.Collections.emptyList();
	}

	/** AOSP: the session with this id, or null. */
	public SessionInfo getSessionInfo(int sessionId) {
		return null;
	}

	/** AOSP: the staged sessions (none). */
	public java.util.List<SessionInfo> getStagedSessions() {
		return java.util.Collections.emptyList();
	}

	/** AOSP: removes a registered session callback. */
	public void unregisterSessionCallback(SessionCallback callback) {
	}

	/** AOSP: details of an install session. */
	public static class SessionInfo implements android.os.Parcelable {
		public static final int INVALID_ID = -1;
		int sessionId = INVALID_ID;
		String installerPackageName;
		String appPackageName;
		float progress;
		boolean active;
		boolean sealed;
		int mode;
		long sizeBytes;
		CharSequence appLabel;
		int installReason;

		public int getSessionId() { return sessionId; }
		public String getInstallerPackageName() { return installerPackageName; }
		public String getAppPackageName() { return appPackageName; }
		public float getProgress() { return progress; }
		public boolean isActive() { return active; }
		public boolean isSealed() { return sealed; }
		public int getMode() { return mode; }
		public long getSize() { return sizeBytes; }
		public CharSequence getAppLabel() { return appLabel; }
		public int getInstallReason() { return installReason; }

		@Override
		public int describeContents() {
			return 0;
		}

		@Override
		public void writeToParcel(android.os.Parcel dest, int flags) {
			dest.writeInt(sessionId);
			dest.writeString(installerPackageName);
			dest.writeString(appPackageName);
			dest.writeFloat(progress);
			dest.writeBoolean(active);
			dest.writeBoolean(sealed);
			dest.writeInt(mode);
			dest.writeLong(sizeBytes);
			dest.writeCharSequence(appLabel);
			dest.writeInt(installReason);
		}

		public static final android.os.Parcelable.Creator<SessionInfo> CREATOR = new android.os.Parcelable.Creator<SessionInfo>() {
			@Override
			public SessionInfo createFromParcel(android.os.Parcel in) {
				SessionInfo s = new SessionInfo();
				s.sessionId = in.readInt();
				s.installerPackageName = in.readString();
				s.appPackageName = in.readString();
				s.progress = in.readFloat();
				s.active = in.readBoolean();
				s.sealed = in.readBoolean();
				s.mode = in.readInt();
				s.sizeBytes = in.readLong();
				s.appLabel = in.readCharSequence();
				s.installReason = in.readInt();
				return s;
			}

			@Override
			public SessionInfo[] newArray(int size) {
				return new SessionInfo[size];
			}
		};
	}
