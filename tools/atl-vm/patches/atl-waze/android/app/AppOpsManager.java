package android.app;

/* teslalinux: replaces ATL's two-method AppOpsManager. One app, one user: every op is allowed. */
public class AppOpsManager {
	public static final int MODE_ALLOWED = 0, MODE_IGNORED = 1, MODE_ERRORED = 2, MODE_DEFAULT = 3, MODE_FOREGROUND = 4;
	public static final String OPSTR_COARSE_LOCATION = "android:coarse_location", OPSTR_FINE_LOCATION = "android:fine_location",
	                           OPSTR_GET_USAGE_STATS = "android:get_usage_stats", OPSTR_SYSTEM_ALERT_WINDOW = "android:system_alert_window",
	                           OPSTR_RECORD_AUDIO = "android:record_audio", OPSTR_CAMERA = "android:camera", OPSTR_READ_PHONE_STATE = "android:read_phone_state",
	                           OPSTR_PICTURE_IN_PICTURE = "android:picture_in_picture", OPSTR_MOCK_LOCATION = "android:mock_location";

	public interface OnOpChangedListener { void onOpChanged(String op, String packageName); }

	public static String permissionToOp(String permission) { return permission; }
	public int checkOp(String op, int uid, String packageName) { return MODE_ALLOWED; }
	public int checkOpNoThrow(String op, int uid, String packageName) { return MODE_ALLOWED; }
	public int unsafeCheckOp(String op, int uid, String packageName) { return MODE_ALLOWED; }
	public int unsafeCheckOpNoThrow(String op, int uid, String packageName) { return MODE_ALLOWED; }
	public int unsafeCheckOpRaw(String op, int uid, String packageName) { return MODE_ALLOWED; }
	public int unsafeCheckOpRawNoThrow(String op, int uid, String packageName) { return MODE_ALLOWED; }
	public int noteOp(String op, int uid, String packageName) { return MODE_ALLOWED; }
	public int noteOp(String op, int uid, String packageName, String attributionTag, String message) { return MODE_ALLOWED; }
	public int noteOpNoThrow(String op, int uid, String packageName) { return MODE_ALLOWED; }
	public int noteOpNoThrow(String op, int uid, String packageName, String attributionTag, String message) { return MODE_ALLOWED; }
	public int noteProxyOp(String op, String proxiedPackageName) { return MODE_ALLOWED; }
	public int noteProxyOp(String op, String proxiedPackageName, int proxiedUid, String proxiedAttributionTag, String message) { return MODE_ALLOWED; }
	public int noteProxyOpNoThrow(String op, String proxiedPackageName) { return MODE_ALLOWED; }
	public int noteProxyOpNoThrow(String op, String proxiedPackageName, int proxiedUid) { return MODE_ALLOWED; }
	public int noteProxyOpNoThrow(String op, String proxiedPackageName, int proxiedUid, String proxiedAttributionTag, String message) { return MODE_ALLOWED; }
	public int startOp(String op, int uid, String packageName) { return MODE_ALLOWED; }
	public int startOpNoThrow(String op, int uid, String packageName) { return MODE_ALLOWED; }
	public void finishOp(String op, int uid, String packageName) {}
	/** AOSP: throws SecurityException unless the package belongs to the uid (here: the app's own package and uid). */
	public void checkPackage(int uid, String packageName) {
		android.atl.ATLLoadedApp app = android.atl.ATLLoadedApp.getPrimaryApplication();
		String own = app != null && app.pkg != null ? app.pkg.packageName : null;
		if (packageName == null || !packageName.equals(own) || uid != android.os.Process.myUid())
			throw new SecurityException("Package " + packageName + " does not belong to " + uid);
	}
	public void startWatchingMode(String op, String packageName, OnOpChangedListener callback) {}
	public void startWatchingMode(String op, String packageName, int flags, OnOpChangedListener callback) {}
	public void stopWatchingMode(OnOpChangedListener callback) {}
}
