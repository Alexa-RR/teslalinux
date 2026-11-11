	/* teslalinux: Activity additions */
	public android.window.OnBackInvokedDispatcher getOnBackInvokedDispatcher() { return android.window.OnBackInvokedDispatcher.STUB; }
	public void addContentView(View view, ViewGroup.LayoutParams params) { setContentView(view, params); }
	public boolean isLaunchedFromBubble() { return false; }
	public void onUserInteraction() {}
	public boolean navigateUpTo(Intent upIntent) { return false; }
	public boolean shouldUpRecreateTask(Intent targetIntent) { return false; }
	public void setEnterSharedElementCallback(SharedElementCallback callback) {}
	public void setExitSharedElementCallback(SharedElementCallback callback) {}
	public boolean dispatchKeyShortcutEvent(android.view.KeyEvent event) { return false; }
	public void startIntentSenderForResult(android.content.IntentSender intent, int requestCode, Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags) {}
	public void startIntentSenderForResult(android.content.IntentSender intent, int requestCode, Intent fillInIntent, int flagsMask, int flagsValues, int extraFlags, android.os.Bundle options) {}
	public void onTrimMemory(int level) {}

	/* ---- AOSP activity-level lifecycle callbacks (API 29) and the perform*() entry points ----
	 * ATL's native side calls performCreate/Start/Resume/Pause/Stop/Destroy (handle_cache.c, patched by
	 * native-lifecycle.sh); each wraps the virtual on*() with the Pre/Post dispatch exactly as AOSP's
	 * Activity does. The plain Created/Started/... dispatch sits in the base on*() methods, as in AOSP. */
	private final java.util.ArrayList<Application.ActivityLifecycleCallbacks> mActivityLifecycleCallbacks = new java.util.ArrayList<>();

	public void registerActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks callback) {
		if (System.getenv("ATL_DEBUG_LIFECYCLE") != null)
			android.util.Log.i("Lifecycle", "Activity.registerActivityLifecycleCallbacks " + callback.getClass().getName() + " on " + getClass().getName());
		synchronized (mActivityLifecycleCallbacks) { mActivityLifecycleCallbacks.add(callback); }
	}
	public void unregisterActivityLifecycleCallbacks(Application.ActivityLifecycleCallbacks callback) {
		synchronized (mActivityLifecycleCallbacks) { mActivityLifecycleCallbacks.remove(callback); }
	}
	private Object[] collectActivityLifecycleCallbacks() {
		synchronized (mActivityLifecycleCallbacks) { return mActivityLifecycleCallbacks.isEmpty() ? null : mActivityLifecycleCallbacks.toArray(); }
	}
	private void dispatchActivityPreCreated(android.os.Bundle s) { getApplication().dispatchActivityPreCreated(this, s); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPreCreated(this, s); }
	private void dispatchActivityCreated(android.os.Bundle s) { getApplication().dispatchActivityCreated(this, s); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityCreated(this, s); }
	private void dispatchActivityPostCreated(android.os.Bundle s) { Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPostCreated(this, s); getApplication().dispatchActivityPostCreated(this, s); }
	private void dispatchActivityPreStarted() { getApplication().dispatchActivityPreStarted(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPreStarted(this); }
	private void dispatchActivityStarted() { getApplication().dispatchActivityStarted(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityStarted(this); }
	private void dispatchActivityPostStarted() { Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPostStarted(this); getApplication().dispatchActivityPostStarted(this); }
	private void dispatchActivityPreResumed() { getApplication().dispatchActivityPreResumed(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPreResumed(this); }
	private void dispatchActivityResumed() { getApplication().dispatchActivityResumed(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityResumed(this); }
	private void dispatchActivityPostResumed() { Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPostResumed(this); getApplication().dispatchActivityPostResumed(this); }
	private void dispatchActivityPrePaused() { getApplication().dispatchActivityPrePaused(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPrePaused(this); }
	private void dispatchActivityPaused() { getApplication().dispatchActivityPaused(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPaused(this); }
	private void dispatchActivityPostPaused() { Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPostPaused(this); getApplication().dispatchActivityPostPaused(this); }
	private void dispatchActivityPreStopped() { getApplication().dispatchActivityPreStopped(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPreStopped(this); }
	private void dispatchActivityStopped() { getApplication().dispatchActivityStopped(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityStopped(this); }
	private void dispatchActivityPostStopped() { Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPostStopped(this); getApplication().dispatchActivityPostStopped(this); }
	private void dispatchActivityPreSaveInstanceState(android.os.Bundle out) { getApplication().dispatchActivityPreSaveInstanceState(this, out); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPreSaveInstanceState(this, out); }
	private void dispatchActivitySaveInstanceState(android.os.Bundle out) { getApplication().dispatchActivitySaveInstanceState(this, out); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivitySaveInstanceState(this, out); }
	private void dispatchActivityPostSaveInstanceState(android.os.Bundle out) { Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPostSaveInstanceState(this, out); getApplication().dispatchActivityPostSaveInstanceState(this, out); }
	private void dispatchActivityPreDestroyed() { getApplication().dispatchActivityPreDestroyed(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPreDestroyed(this); }
	private void dispatchActivityDestroyed() { getApplication().dispatchActivityDestroyed(this); Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityDestroyed(this); }
	private void dispatchActivityPostDestroyed() { Object[] c = collectActivityLifecycleCallbacks(); if (c != null) for (Object o : c) ((Application.ActivityLifecycleCallbacks) o).onActivityPostDestroyed(this); getApplication().dispatchActivityPostDestroyed(this); }

	/** Native entry points: AOSP's perform*() ordering. */
	public final void performCreate(android.os.Bundle savedInstanceState) { dispatchActivityPreCreated(savedInstanceState); onCreate(savedInstanceState); dispatchActivityPostCreated(savedInstanceState); }
	/** AOSP: a stopped activity gets onRestart before onStart. */
	private boolean mStopped;
	public final void performStart() { if (mStopped) { mStopped = false; onRestart(); } dispatchActivityPreStarted(); onStart(); dispatchActivityPostStarted(); }
	public final void performResume() { dispatchActivityPreResumed(); onResume(); dispatchActivityPostResumed(); }
	public final void performPause() { dispatchActivityPrePaused(); onPause(); dispatchActivityPostPaused(); }
	public final void performStop() { dispatchActivityPreStopped(); onStop(); mStopped = true; dispatchActivityPostStopped(); }
	public final void performDestroy() { dispatchActivityPreDestroyed(); onDestroy(); dispatchActivityPostDestroyed(); }
	public final void performSaveInstanceState(android.os.Bundle outState) { dispatchActivityPreSaveInstanceState(outState); onSaveInstanceState(outState); dispatchActivitySaveInstanceState(outState); dispatchActivityPostSaveInstanceState(outState); }

	/* ---- lock screen / caller / locus (AOSP Activity, API 27-30) ----
	 * On Android these go to the activity task manager; the observable effect of the first three is
	 * the FLAG_SHOW_WHEN_LOCKED / FLAG_TURN_SCREEN_ON window state, which is what is kept here. There
	 * is no keyguard in this runtime, so an activity is always shown "when locked". */
	private boolean mInheritShowWhenLocked;

	public void setShowWhenLocked(boolean showWhenLocked) {
		if (showWhenLocked)
			getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
		else
			getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
	}

	public void setInheritShowWhenLocked(boolean inheritShowWhenLocked) {
		mInheritShowWhenLocked = inheritShowWhenLocked;
	}

	public void setTurnScreenOn(boolean turnScreenOn) {
		if (turnScreenOn)
			getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
		else
			getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
	}

	/** AOSP: the component that started this activity with startActivityForResult, else null.
	 * ATL does not attribute launches to a calling activity, so this is null, as for an activity
	 * launched without a result request. */
	public android.content.ComponentName getCallingActivity() {
		return null;
	}

	/** AOSP forwards the locus to the activity task manager for content capture / shortcuts; neither
	 * service exists here, so only the value is kept. */
	private android.content.LocusId mLocusId;
	public void setLocusContext(android.content.LocusId locusId, android.os.Bundle bundle) {
		mLocusId = locusId;
	}
	/** AOSP: the configuration changed while this activity is resumed (Application first, then the activity). */
	public final void performConfigurationChanged(android.content.res.Configuration newConfig) {
		android.app.Application app = getApplication();
		if (app != null)
			app.onConfigurationChanged(newConfig);
		onConfigurationChanged(newConfig);
	}
	/* AOSP default key handling */
	public static final int DEFAULT_KEYS_DISABLE = 0;
	public static final int DEFAULT_KEYS_DIALER = 1;
	public static final int DEFAULT_KEYS_SHORTCUT = 2;
	public static final int DEFAULT_KEYS_SEARCH_LOCAL = 3;
	public static final int DEFAULT_KEYS_SEARCH_GLOBAL = 4;
	private int mDefaultKeyMode = DEFAULT_KEYS_DISABLE;
	private final KeyEvent.DispatcherState tlKeyDispatchState = new KeyEvent.DispatcherState();

	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			if (getApplicationInfo().targetSdkVersion >= android.os.Build.VERSION_CODES.ECLAIR) {
				event.startTracking();
			} else {
				onBackPressed();
			}
			return true;
		}
		return false;
	}

	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		return false;
	}

	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if (getApplicationInfo().targetSdkVersion >= android.os.Build.VERSION_CODES.ECLAIR) {
			if (keyCode == KeyEvent.KEYCODE_BACK && event.isTracking() && !event.isCanceled()) {
				onBackPressed();
				return true;
			}
		}
		return false;
	}

	public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
		return false;
	}

	public boolean onKeyShortcut(int keyCode, KeyEvent event) {
		return false;
	}
	/** teslalinux: ATL's window focus entry (AOSP ViewRootImpl): the decor views first, then this activity. */
	public void tlWindowFocusChanged(boolean hasFocus) {
		View decor = window != null ? window.getDecorView() : null;
		if (decor != null)
			decor.dispatchWindowFocusChanged(hasFocus);
		onWindowFocusChanged(hasFocus);
	}
