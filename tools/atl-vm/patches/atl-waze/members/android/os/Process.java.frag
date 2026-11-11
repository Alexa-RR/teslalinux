	/* teslalinux: Process additions */
	public static boolean isApplicationUid(int uid) { return uid >= 10000; }
	public static boolean isSdkSandbox() { return false; }
	/** AOSP: the name of this process: the application's process name (its package name by default). */
	public static String myProcessName() {
		android.content.pm.ApplicationInfo ai = android.atl.ATLLoadedApp.getPrimaryApplication().pkg.applicationInfo;
		return ai.processName != null ? ai.processName : ai.packageName;
	}
	public static boolean isSdkSandboxUid(int uid) { return false; }
	/** AOSP (hidden): an invalid uid. */
	public static final int INVALID_UID = -1;
