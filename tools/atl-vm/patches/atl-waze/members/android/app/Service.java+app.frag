	/* ==== teslalinux (app): AOSP Service members ==== */
	public static final int START_CONTINUATION_MASK = 0xf;
	public static final int START_STICKY_COMPATIBILITY = 0;
	public static final int START_STICKY = 1;
	public static final int START_NOT_STICKY = 2;
	public static final int START_REDELIVER_INTENT = 3;
	public static final int START_TASK_REMOVED_COMPLETE = 1000;
	public static final int START_FLAG_REDELIVERY = 0x0001;
	public static final int START_FLAG_RETRY = 0x0002;
	public static final int STOP_FOREGROUND_LEGACY = 0;
	public static final int STOP_FOREGROUND_REMOVE = 1 << 0;
	public static final int STOP_FOREGROUND_DETACH = 1 << 1;
	/* ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST */
	private static final int FOREGROUND_SERVICE_TYPE_MANIFEST = -1;

	private boolean tlForeground;
	private boolean tlDetached;
	private boolean tlStarted;
	private int tlForegroundType;
	private long tlCreated;

	/* the services alive in this process (onCreate .. onDestroy) */
	private static final java.util.ArrayList<Service> tlLiveServices = new java.util.ArrayList<>();

	private void tlRegister(boolean alive) {
		synchronized (tlLiveServices) {
			tlLiveServices.remove(this);
			if (alive) {
				tlLiveServices.add(this);
				tlCreated = android.os.SystemClock.elapsedRealtime();
			}
		}
		if (!alive && tlForeground)
			stopForeground(STOP_FOREGROUND_REMOVE); /* AOSP: a destroyed service takes its foreground notification with it */
	}

	static int tlForegroundCount() {
		int n = 0;
		synchronized (tlLiveServices) {
			for (Service s : tlLiveServices)
				if (s.tlForeground)
					n++;
		}
		return n;
	}

	/** AOSP ActivityManager.getRunningServices: the app's own running services. */
	static java.util.List<ActivityManager.RunningServiceInfo> tlRunningServiceInfos(int maxNum) {
		java.util.List<ActivityManager.RunningServiceInfo> out = new java.util.ArrayList<>();
		synchronized (tlLiveServices) {
			for (Service s : tlLiveServices) {
				if (out.size() >= maxNum)
					break;
				ActivityManager.RunningServiceInfo info = new ActivityManager.RunningServiceInfo();
				info.service = new android.content.ComponentName(s, s.getClass());
				info.pid = android.os.Process.myPid();
				info.uid = android.os.Process.myUid();
				info.process = s.getApplicationInfo().processName != null ? s.getApplicationInfo().processName : s.getPackageName();
				info.foreground = s.tlForeground;
				info.started = s.tlStarted;
				info.activeSince = s.tlCreated;
				info.lastActivityTime = s.tlCreated;
				info.flags = (s.tlStarted ? ActivityManager.RunningServiceInfo.FLAG_STARTED : 0)
				    | (s.tlForeground ? ActivityManager.RunningServiceInfo.FLAG_FOREGROUND : 0);
				out.add(info);
			}
		}
		return out;
	}

	/** AOSP: the foreground service types the service runs with (0 when not in the foreground). */
	public final int getForegroundServiceType() {
		return tlForeground ? tlForegroundType : 0;
	}

	/** The android:foregroundServiceType of this service's manifest entry. */
	private int tlManifestForegroundType() {
		android.atl.ATLLoadedApp app = android.atl.ATLLoadedApp.getPrimaryApplication();
		if (app == null || app.pkg == null)
			return 0;
		for (android.content.pm.PackageParser.Service s : app.pkg.services) {
			if (getClass().getName().equals(s.className)) {
				try {
					return s.info.getClass().getField("foregroundServiceType").getInt(s.info);
				} catch (ReflectiveOperationException e) {
					return 0; /* ATL's ServiceInfo does not parse the attribute */
				}
			}
		}
		return 0;
	}

	/** AOSP (deprecated): called by the default onStartCommand. */
	@Deprecated
	public void onStart(Intent intent, int startId) {
	}

	/** AOSP: whether onRebind should be called for later binds; the default is no. */
	public boolean onUnbind(Intent intent) {
		return false;
	}

	public void onRebind(Intent intent) {
	}

	/** AOSP: the user removed the app's task from recents; there are no recents here, so it is never called. */
	public void onTaskRemoved(Intent rootIntent) {
	}

	public void onConfigurationChanged(android.content.res.Configuration newConfig) {
	}

	public void onLowMemory() {
	}

	public void onTrimMemory(int level) {
	}

	/** AOSP: the service's dumpsys output; the default has nothing to dump. */
	protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
		writer.println("nothing to dump");
	}
