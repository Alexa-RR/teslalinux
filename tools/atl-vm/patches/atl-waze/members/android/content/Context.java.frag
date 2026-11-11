import java.util.concurrent.Executor;
	/* teslalinux: API 28-34 Context additions Waze touches during Application.onCreate */
	public Executor getMainExecutor() {
		final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
		return new Executor() {
			public void execute(Runnable r) { h.post(r); }
		};
	}
	/** AOSP: a context whose operations are attributed to the tag (getAttributionTag/Source report it). */
	public Context createAttributionContext(final String attributionTag) {
		return new ContextWrapper(this) {
			@Override
			public String getAttributionTag() {
				return attributionTag;
			}

			@Override
			public android.content.AttributionSource getAttributionSource() {
				return new android.content.AttributionSource(android.os.Process.myUid(), getPackageName(), attributionTag);
			}
		};
	}
	public Context createWindowContext(int type, android.os.Bundle options) { return this; }
	public Context createWindowContext(android.view.Display display, int type, android.os.Bundle options) { return this; }
	public Context createDeviceContext(int deviceId) { return this; }
	/** AOSP Context.DEVICE_ID_DEFAULT: there are no virtual devices, every context is on the default one. */
	public static final int DEVICE_ID_DEFAULT = 0;
	/** AOSP Context.DEVICE_ID_INVALID */
	public static final int DEVICE_ID_INVALID = -1;
	public int getDeviceId() { return DEVICE_ID_DEFAULT; }
	/**
	 * AOSP: whether this context can show UI: activities (and input method services), and wrappers of
	 * them; the application and service contexts are not UI contexts.
	 */
	public boolean isUiContext() {
		if (this instanceof android.app.Activity || this instanceof android.inputmethodservice.InputMethodService)
			return true;
		if (this instanceof ContextWrapper) {
			Context base = ((ContextWrapper) this).getBaseContext();
			return base != null && base != this && base.isUiContext();
		}
		return false;
	}
	public String getOpPackageName() { return getPackageName(); }
	public java.io.File getCodeCacheDir() { java.io.File f = new java.io.File(getCacheDir(), "code_cache"); f.mkdirs(); return f; }
	public String getSystemServiceName(Class<?> serviceClass) { return serviceClass.getSimpleName(); }
	public ComponentName startForegroundService(Intent service) { return startService(service); }
	public void startActivities(Intent[] intents) { for (Intent i : intents) startActivity(i); }
	public void startActivities(Intent[] intents, android.os.Bundle options) { startActivities(intents); }
	public boolean deleteFile(String name) { return getFileStreamPath(name).delete(); }
	public int checkCallingOrSelfUriPermission(android.net.Uri uri, int modeFlags) { return android.content.pm.PackageManager.PERMISSION_GRANTED; }
	public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags, android.os.UserHandle user) { return bindService(service, conn, flags); }
	public android.view.Display getDisplay() { return ((android.view.WindowManager) getSystemService("window")).getDefaultDisplay(); }
	/*
	 * AOSP: listeners for this context moving to another (virtual) device. ATL has no virtual
	 * devices, so the device id never changes and registered listeners are never called; the
	 * registration rules (no null, no duplicates) are AOSP's.
	 */
	private final java.util.ArrayList<java.util.function.IntConsumer> tlDeviceIdListeners = new java.util.ArrayList<>();
	public void registerDeviceIdChangeListener(Executor executor, java.util.function.IntConsumer listener) {
		if (executor == null)
			throw new NullPointerException("executor must not be null");
		if (listener == null)
			throw new NullPointerException("listener must not be null");
		synchronized (tlDeviceIdListeners) {
			if (tlDeviceIdListeners.contains(listener))
				throw new IllegalArgumentException("attempt to call registerDeviceIdChangeListener() on a previously registered listener");
			tlDeviceIdListeners.add(listener);
		}
	}
	public void unregisterDeviceIdChangeListener(java.util.function.IntConsumer listener) {
		if (listener == null)
			throw new NullPointerException("listener must not be null");
		synchronized (tlDeviceIdListeners) {
			tlDeviceIdListeners.remove(listener);
		}
	}
	/* API 29/33 overloads: the executor variant dispatches the connection callbacks on that executor;
	 * receiver export flags are meaningless in a single-process runtime and are ignored like options. */
	public boolean bindService(Intent service, int flags, final java.util.concurrent.Executor executor, final ServiceConnection conn) {
		ServiceConnection onExecutor = new ServiceConnection() {
			public void onServiceConnected(final ComponentName name, final android.os.IBinder binder) { executor.execute(new Runnable() { public void run() { conn.onServiceConnected(name, binder); } }); }
			public void onServiceDisconnected(final ComponentName name) { executor.execute(new Runnable() { public void run() { conn.onServiceDisconnected(name); } }); }
		};
		return bindService(service, onExecutor, flags);
	}
	public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) { return registerReceiver(receiver, filter); }
	public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, String broadcastPermission, android.os.Handler scheduler, int flags) { return registerReceiver(receiver, filter, broadcastPermission, scheduler); }
	public void sendBroadcast(Intent intent, String receiverPermission, android.os.Bundle options) { sendBroadcast(intent); }
	/** AOSP: name of the content capture service; absent in this runtime, so getSystemService returns null. */
	public static final String CONTENT_CAPTURE_MANAGER_SERVICE = "content_capture";
	/** AOSP (hidden): whether resources of other packages may be loaded; true for the app's own package. */
	public boolean canLoadUnsafeResources() {
		return getPackageName().equals(getOpPackageName());
	}
	/**
	 * teslalinux: the window size changed (native-config.sh updated sys_config and the Display);
	 * refresh the system resources' metrics and configuration like AOSP's ResourcesManager does.
	 */
	public static void tlOnConfigurationChanged() {
		android.content.res.Resources sys = android.content.res.Resources.getSystem();
		android.util.DisplayMetrics metrics = sys.getDisplayMetrics();
		metrics.setToDefaults();
		sys.updateConfiguration(sys_config, metrics);
		for (android.atl.ATLLoadedApp app : android.atl.ATLLoadedAppManager.tlLoadedApps())
			app.tlApplyConfiguration(sys_config, metrics);
	}
	/** AOSP: the name of the connectivity service (getSystemService). */
	public static final String CONNECTIVITY_SERVICE = "connectivity";
	/** AOSP: the autofill service name; this platform has no autofill service, so getSystemService returns null for it. */
	public static final String AUTOFILL_MANAGER_SERVICE = "autofill";
