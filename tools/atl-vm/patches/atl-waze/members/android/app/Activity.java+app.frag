	/* ==== teslalinux (app): AOSP Activity behaviour ATL lacked ====
	 * Task bookkeeping (ActivityManager.AppTask), results delivered on finish, recreate() with saved
	 * state, window token visibility for dialogs, activity transitions, PiP/multi-window reporting. */

	private static final String WINDOW_HIERARCHY_TAG = "android:viewHierarchyState";
	private static final String LAST_AUTOFILL_ID = "android:lastAutofillId";
	private static final String VIEWS_TAG = "android:views";
	private static final String FOCUSED_ID_TAG = "android:focusedViewId";

	/* ---- the one task: activities alive in this process, in launch order (root first) ---- */
	static final ArrayList<Activity> tlLiveActivities = new ArrayList<>();
	/** activities between onStart and onStop (ActivityManager importance of this process) */
	static int tlStartedCount;
	/** the task's description (Activity.setTaskDescription), for AppTask.getTaskInfo */
	static ActivityManager.TaskDescription tlTaskDescription;
	static long tlTaskLastActiveTime;

	static List<Activity> tlActivities() {
		synchronized (tlLiveActivities) {
			return new ArrayList<>(tlLiveActivities);
		}
	}

	/** between onStart and onStop: the windows of this activity's token (its dialogs) are shown */
	boolean tlTokenVisible;
	boolean tlLaunchedByApp;
	ActivityOptions tlLaunchOptions;
	private long tlCreateUptime;
	private long tlResumeUptime;
	private boolean tlFullyDrawnReported;

	private boolean mChangingConfigurations;
	Object tlLastNonConfigurationInstance;
	Bundle tlPendingSavedState;
	private int mLastAutofillId = 0x3fffffff; /* AOSP View.LAST_APP_AUTOFILL_ID */

	private boolean mTitleReady;
	private int mTitleColor;
	private boolean mVisibleFromClient = true;
	private boolean mFinishOnTouchOutside;
	private int mVolumeControlStream = Integer.MIN_VALUE; /* AudioManager.USE_DEFAULT_STREAM_TYPE */
	private TlBackDispatcher mBackDispatcher;
	private SharedElementCallback mEnterSharedElementCallback;
	private SharedElementCallback mExitSharedElementCallback;

	/* AOSP: the result this activity returns to the one that started it for a result */
	private int mResultCode = RESULT_CANCELED;
	private Intent mResultData;
	/* results of activities this one started, waiting for its next resume */
	private final ArrayList<Object[]> tlPendingResults = new ArrayList<>();

	private void tlOnCreate() {
		tlCreateUptime = android.os.SystemClock.uptimeMillis();
		synchronized (tlLiveActivities) {
			if (!tlLiveActivities.isEmpty())
				tlLaunchedByApp = true;
			if (!tlLiveActivities.contains(this))
				tlLiveActivities.add(this);
		}
	}

	private void tlAfterStart() {
		synchronized (tlLiveActivities) {
			if (!tlTokenVisible) {
				tlTokenVisible = true;
				tlStartedCount++;
			}
			tlTaskLastActiveTime = System.currentTimeMillis();
		}
		Dialog.tlTokenVisibility(this, true);
		/* AOSP performRestoreInstanceState: after onStart of a recreated instance */
		if (tlPendingSavedState != null) {
			Bundle state = tlPendingSavedState;
			tlPendingSavedState = null;
			onRestoreInstanceState(state);
		}
	}

	private void tlAfterStop() {
		synchronized (tlLiveActivities) {
			if (tlTokenVisible) {
				tlTokenVisible = false;
				tlStartedCount--;
			}
		}
		Dialog.tlTokenVisibility(this, false);
		/* AOSP: managed cursors are deactivated while the activity is stopped */
		synchronized (mManagedCursors) {
			for (android.database.Cursor c : mManagedCursors)
				c.deactivate();
		}
	}

	private void tlAfterDestroy() {
		synchronized (tlLiveActivities) {
			if (tlTokenVisible) {
				tlTokenVisible = false;
				tlStartedCount--;
			}
			tlLiveActivities.remove(this);
		}
		Dialog.tlTokenDestroyed(this);
		synchronized (mManagedCursors) {
			for (android.database.Cursor c : mManagedCursors)
				c.close();
			mManagedCursors.clear();
		}
		tlReportUsageTime();
	}

	/** finish(): the result goes to the caller (before its next onResume) and a close override becomes the pending transition */
	private void tlOnFinish() {
		if (resultActivity != null && resultRequestCode >= 0 && !mChangingConfigurations) {
			int code;
			Intent data;
			synchronized (this) {
				code = mResultCode;
				data = mResultData;
			}
			resultActivity.tlQueueActivityResult(resultRequestCode, code, data);
		}
		if (!tlHasPendingTransition && mOverrideCloseTransition != null)
			tlSetPendingTransition(this, mOverrideCloseTransition[0], mOverrideCloseTransition[1]);
	}

	void tlQueueActivityResult(int requestCode, int resultCode, Intent data) {
		synchronized (tlPendingResults) {
			tlPendingResults.add(new Object[] {requestCode, resultCode, data});
		}
	}

	private void tlDispatchPendingResults() {
		Object[][] results;
		synchronized (tlPendingResults) {
			if (tlPendingResults.isEmpty())
				return;
			results = tlPendingResults.toArray(new Object[0][]);
			tlPendingResults.clear();
		}
		for (Object[] r : results)
			onActivityResult((Integer) r[0], (Integer) r[1], (Intent) r[2]);
	}

	/** AOSP AppTimeTracker: the PendingIntent of ActivityOptions.requestUsageTimeReport gets the time spent here. */
	private void tlReportUsageTime() {
		PendingIntent receiver = tlLaunchOptions != null ? tlLaunchOptions.getUsageTimeReport() : null;
		if (receiver == null || tlResumeUptime == 0)
			return;
		long ms = android.os.SystemClock.uptimeMillis() - tlResumeUptime;
		Intent fillIn = new Intent();
		fillIn.putExtra(ActivityOptions.EXTRA_USAGE_TIME_REPORT, ms);
		Bundle pkgs = new Bundle();
		pkgs.putLong(getPackageName(), ms);
		fillIn.putExtra(ActivityOptions.EXTRA_USAGE_TIME_REPORT_PACKAGES, pkgs);
		try {
			receiver.send(this, 0, fillIn);
		} catch (Exception e) {
			android.util.Log.w(TAG, "usage time report: " + e);
		}
	}

	/* ---- AOSP instance state ---- */

	/** AOSP: what this instance passes to the one recreate() makes (getLastNonConfigurationInstance there). */
	public Object onRetainNonConfigurationInstance() {
		return null;
	}

	/** AOSP: restores the window's view hierarchy state saved by onSaveInstanceState. */
	protected void onRestoreInstanceState(Bundle savedInstanceState) {
		Bundle windowState = savedInstanceState.getBundle(WINDOW_HIERARCHY_TAG);
		if (windowState != null)
			tlRestoreHierarchyState(windowState);
	}

	public void onRestoreInstanceState(Bundle savedInstanceState, android.os.PersistableBundle persistentState) {
		if (savedInstanceState != null) {
			onRestoreInstanceState(savedInstanceState);
		}
	}

	public void onSaveInstanceState(Bundle outState, android.os.PersistableBundle outPersistentState) {
		onSaveInstanceState(outState);
	}

	public void onCreate(Bundle savedInstanceState, android.os.PersistableBundle persistentState) {
		onCreate(savedInstanceState);
	}

	public void onPostCreate(Bundle savedInstanceState, android.os.PersistableBundle persistentState) {
		onPostCreate(savedInstanceState);
	}

	/* AOSP PhoneWindow.saveHierarchyState / restoreHierarchyState */
	private Bundle tlSaveHierarchyState() {
		Bundle outState = new Bundle();
		View decor = window != null ? window.peekDecorView() : null;
		if (decor == null)
			return outState;
		android.util.SparseArray<android.os.Parcelable> states = new android.util.SparseArray<>();
		decor.saveHierarchyState(states);
		outState.putSparseParcelableArray(VIEWS_TAG, states);
		final View focusedView = decor.findFocus();
		if (focusedView != null && focusedView.getId() != View.NO_ID)
			outState.putInt(FOCUSED_ID_TAG, focusedView.getId());
		return outState;
	}

	/** The focused view comes back; the views' own states need View.restoreHierarchyState, which ATL's View lacks. */
	private void tlRestoreHierarchyState(Bundle savedInstanceState) {
		View decor = window != null ? window.peekDecorView() : null;
		if (decor == null)
			return;
		int focusedViewId = savedInstanceState.getInt(FOCUSED_ID_TAG, View.NO_ID);
		if (focusedViewId != View.NO_ID) {
			View needsFocus = decor.findViewById(focusedViewId);
			if (needsFocus != null)
				needsFocus.requestFocus();
			else
				android.util.Log.w(TAG, "Previously focused view reported id " + focusedViewId + " during save, but can't be found during restore.");
		}
	}

	/* ---- title ---- */

	/** AOSP: the title reaches the window once the activity is started (mTitleReady). */
	protected void onTitleChanged(CharSequence title, int color) {
		if (mTitleReady && window != null) {
			window.setTitle(title);
		}
	}

	protected void onChildTitleChanged(Activity childActivity, CharSequence title) {
	}

	@Deprecated
	public void setTitleColor(int textColor) {
		mTitleColor = textColor;
		onTitleChanged(title, textColor);
	}

	@Deprecated
	public final int getTitleColor() {
		return mTitleColor;
	}

	/* ---- input that reaches the activity (Window.Callback) ---- */

	/** AOSP: the window's views first, then onTouchEvent. */
	public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
		if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
			onUserInteraction();
		}
		if (getWindow().superDispatchTouchEvent(ev)) {
			return true;
		}
		return onTouchEvent(ev);
	}

	/** AOSP: a touch outside the window of an activity with setFinishOnTouchOutside(true) finishes it. */
	public boolean onTouchEvent(android.view.MotionEvent event) {
		if (tlShouldCloseOnTouch(event)) {
			finish();
			return true;
		}
		return false;
	}

	private boolean tlShouldCloseOnTouch(android.view.MotionEvent event) {
		View decor = window != null ? window.peekDecorView() : null;
		if (!mFinishOnTouchOutside || decor == null)
			return false;
		final int action = event.getAction();
		if (action == android.view.MotionEvent.ACTION_OUTSIDE)
			return true;
		if (action != android.view.MotionEvent.ACTION_UP)
			return false;
		final int x = (int) event.getX();
		final int y = (int) event.getY();
		final int slop = android.view.ViewConfiguration.get(this).getScaledWindowTouchSlop();
		return (x < -slop) || (y < -slop) || (x > (decor.getWidth() + slop)) || (y > (decor.getHeight() + slop));
	}

	public boolean dispatchTrackballEvent(android.view.MotionEvent ev) {
		onUserInteraction();
		if (getWindow().superDispatchTrackballEvent(ev)) {
			return true;
		}
		return onTrackballEvent(ev);
	}

	public boolean onTrackballEvent(android.view.MotionEvent event) {
		return false;
	}

	public boolean dispatchGenericMotionEvent(android.view.MotionEvent ev) {
		onUserInteraction();
		if (getWindow().superDispatchGenericMotionEvent(ev)) {
			return true;
		}
		return onGenericMotionEvent(ev);
	}

	public boolean onGenericMotionEvent(android.view.MotionEvent event) {
		return false;
	}

	/** AOSP: called when the activity goes to the background because of the user (HOME); never on this kiosk, which has no other task to go to. */
	protected void onUserLeaveHint() {
	}

	/** AOSP View.OnCreateContextMenuListener: the base implementation adds nothing. */
	public void onCreateContextMenu(android.view.ContextMenu menu, View v, android.view.ContextMenu.ContextMenuInfo menuInfo) {
	}

	/* ---- managed cursors (AOSP, deprecated) ---- */
	private final ArrayList<android.database.Cursor> mManagedCursors = new ArrayList<>();

	@Deprecated
	public final android.database.Cursor managedQuery(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		android.database.Cursor c = getContentResolver().query(uri, projection, selection, selectionArgs, sortOrder);
		if (c != null) {
			startManagingCursor(c);
		}
		return c;
	}

	@Deprecated
	public void startManagingCursor(android.database.Cursor c) {
		synchronized (mManagedCursors) {
			mManagedCursors.add(c);
		}
	}

	@Deprecated
	public void stopManagingCursor(android.database.Cursor c) {
		synchronized (mManagedCursors) {
			mManagedCursors.remove(c);
		}
	}

	/** AOSP performRestart: managed cursors are requeried when a stopped activity starts again. */
	private void tlRequeryManagedCursors() {
		synchronized (mManagedCursors) {
			for (android.database.Cursor c : mManagedCursors) {
				if (!c.isClosed() && !c.requery() && getApplicationInfo().targetSdkVersion >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
					throw new IllegalStateException("trying to requery an already closed cursor  " + c);
				}
			}
		}
	}

	/* ---- task ---- */

	/** AOSP: finishes every activity of the task (this runtime has the one task). */
	public void finishAndRemoveTask() {
		List<Activity> all = tlActivities();
		for (int i = all.size() - 1; i >= 0; i--)
			all.get(i).finish();
		if (!all.contains(this))
			finish();
	}

	/* ---- multi-window and picture-in-picture ----
	 * This platform shows one app fullscreen on one display: no split screen, no freeform windows and
	 * no PiP (PackageManager has no android.software.picture_in_picture), so the mode callbacks never
	 * fire and the requests are refused the way AOSP refuses them on such a device. */
	private PictureInPictureParams mPictureInPictureParams;

	public void onMultiWindowModeChanged(boolean isInMultiWindowMode, android.content.res.Configuration newConfig) {
		onMultiWindowModeChanged(isInMultiWindowMode);
	}

	@Deprecated
	public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
	}

	public boolean isInPictureInPictureMode() {
		return false;
	}

	public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, android.content.res.Configuration newConfig) {
		onPictureInPictureModeChanged(isInPictureInPictureMode);
	}

	@Deprecated
	public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
	}

	public void onPictureInPictureUiStateChanged(PictureInPictureUiState pipState) {
	}

	/** AOSP: called when the system asks the activity to enter PiP; the default does not enter. */
	public boolean onPictureInPictureRequested() {
		return false;
	}

	private boolean tlDeviceSupportsPictureInPictureMode() {
		return getPackageManager().hasSystemFeature("android.software.picture_in_picture");
	}

	@Deprecated
	public void enterPictureInPictureMode() {
		enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
	}

	public boolean enterPictureInPictureMode(PictureInPictureParams params) {
		if (!tlDeviceSupportsPictureInPictureMode()) {
			return false;
		}
		if (params == null) {
			throw new IllegalArgumentException("Expected non-null picture-in-picture params");
		}
		throw new IllegalStateException("Activity must be resumed to enter picture-in-picture");
	}

	public void setPictureInPictureParams(PictureInPictureParams params) {
		if (!tlDeviceSupportsPictureInPictureMode()) {
			return;
		}
		if (params == null) {
			throw new IllegalArgumentException("Expected non-null picture-in-picture params");
		}
		mPictureInPictureParams = params;
	}

	public int getMaxNumPictureInPictureActions() {
		return 0;
	}

	/* ---- launching ---- */

	/** AOSP: like startActivity(intent), with the launch options (custom animation, usage time report). */
	@Override
	public void startActivity(Intent intent, Bundle options) {
		tlApplyLaunchOptions(this, options);
		Bundle previous = ContextImpl.tlNextLaunchOptions;
		ContextImpl.tlNextLaunchOptions = options;
		try {
			startActivity(intent);
		} finally {
			ContextImpl.tlNextLaunchOptions = previous;
		}
	}

	/** The launch options' custom animation (ActivityOptions.makeCustomAnimation) is the next activity transition. */
	static void tlApplyLaunchOptions(Context context, Bundle options) {
		ActivityOptions opts = ActivityOptions.fromBundle(options);
		if (opts != null && opts.getAnimationType() == ActivityOptions.ANIM_CUSTOM)
			tlSetPendingTransition(context, opts.getCustomEnterResId(), opts.getCustomExitResId());
	}

	/* ---- tl_activity_transition: AOSP app transitions on ATL's page stack (native-app-transition.sh) ----
	 * AOSP plays the enter animation on the incoming activity and the exit animation on the outgoing
	 * one. Here every activity is a page of the main window's GtkStack, and GTK animates the page
	 * change natively; the anim resources are mapped to the GtkStackTransitionType that moves the
	 * pages the same way (slide / cover / uncover in the animations' direction, a crossfade for
	 * fades and for scale or rotate animations, which GtkStack cannot do) for the animations' length.
	 * Without an override ATL's activity switch has no animation (the theme's default activity
	 * animations are not played). */
	public static final int OVERRIDE_TRANSITION_OPEN = 0;
	public static final int OVERRIDE_TRANSITION_CLOSE = 1;

	private static boolean tlHasPendingTransition;
	private static int tlPendingEnterAnim, tlPendingExitAnim;
	private static Context tlPendingTransitionContext;
	private static long tlTransitionEndsAt;
	private int[] mOverrideOpenTransition;
	private int[] mOverrideCloseTransition;
	private boolean tlOpenTransitionUsed;

	private static native void nativeSetPageTransition(long nativeWindow, int gtkTransitionType, int durationMs);

	static void tlSetPendingTransition(Context context, int enterAnim, int exitAnim) {
		tlHasPendingTransition = true;
		tlPendingEnterAnim = enterAnim;
		tlPendingExitAnim = exitAnim;
		tlPendingTransitionContext = context;
	}

	/** AOSP (API 34): the animations of this activity's own open or close transitions. */
	public void overrideActivityTransition(int overrideType, int enterAnim, int exitAnim) {
		overrideActivityTransition(overrideType, enterAnim, exitAnim, 0);
	}

	public void overrideActivityTransition(int overrideType, int enterAnim, int exitAnim, int backgroundColor) {
		if (overrideType != OVERRIDE_TRANSITION_OPEN && overrideType != OVERRIDE_TRANSITION_CLOSE) {
			throw new IllegalArgumentException("Override type must be either open or close");
		}
		if (overrideType == OVERRIDE_TRANSITION_OPEN)
			mOverrideOpenTransition = new int[] {enterAnim, exitAnim};
		else
			mOverrideCloseTransition = new int[] {enterAnim, exitAnim};
	}

	public void clearOverrideActivityTransition(int overrideType) {
		if (overrideType != OVERRIDE_TRANSITION_OPEN && overrideType != OVERRIDE_TRANSITION_CLOSE) {
			throw new IllegalArgumentException("Override type must be either open or close");
		}
		if (overrideType == OVERRIDE_TRANSITION_OPEN)
			mOverrideOpenTransition = null;
		else
			mOverrideCloseTransition = null;
	}

	/** {GtkStackTransitionType, duration ms} for the page change this onStart makes, or null for none. */
	private int[] tlTakePageTransition() {
		int enter, exit;
		Context ctx;
		if (tlHasPendingTransition) {
			enter = tlPendingEnterAnim;
			exit = tlPendingExitAnim;
			ctx = tlPendingTransitionContext != null ? tlPendingTransitionContext : this;
			tlHasPendingTransition = false;
			tlPendingTransitionContext = null;
		} else if (!tlOpenTransitionUsed && mOverrideOpenTransition != null) {
			enter = mOverrideOpenTransition[0];
			exit = mOverrideOpenTransition[1];
			ctx = this;
		} else {
			tlOpenTransitionUsed = true;
			return null;
		}
		tlOpenTransitionUsed = true;
		int[] t = tlPageTransitionFor(ctx, enter, exit);
		tlTransitionEndsAt = t[0] != 0 ? android.os.SystemClock.uptimeMillis() + t[1] : 0;
		return t;
	}

	/* GtkStackTransitionType */
	private static final int GTK_NONE = 0, GTK_CROSSFADE = 1, GTK_SLIDE_RIGHT = 2, GTK_SLIDE_LEFT = 3,
	    GTK_SLIDE_UP = 4, GTK_SLIDE_DOWN = 5, GTK_OVER_UP = 8, GTK_OVER_DOWN = 9, GTK_OVER_LEFT = 10,
	    GTK_OVER_RIGHT = 11, GTK_UNDER_UP = 12, GTK_UNDER_DOWN = 13, GTK_UNDER_LEFT = 14, GTK_UNDER_RIGHT = 15;

	static int[] tlPageTransitionFor(Context ctx, int enterRes, int exitRes) {
		if (enterRes == 0 && exitRes == 0)
			return new int[] {GTK_NONE, 0};
		float[] in = tlAnimMotion(ctx, enterRes, true);
		float[] out = tlAnimMotion(ctx, exitRes, false);
		int duration = (int) Math.max(in[2], out[2]);
		if (duration <= 0)
			return new int[] {GTK_NONE, 0};
		int type;
		/* in[0]/in[1] > 0: the entering page comes from the right / from below;
		 * out[0]/out[1] > 0: the leaving page goes to the right / downwards */
		if (in[0] > 0)
			type = out[0] < 0 ? GTK_SLIDE_LEFT : GTK_OVER_LEFT;
		else if (in[0] < 0)
			type = out[0] > 0 ? GTK_SLIDE_RIGHT : GTK_OVER_RIGHT;
		else if (in[1] > 0)
			type = out[1] < 0 ? GTK_SLIDE_UP : GTK_OVER_UP;
		else if (in[1] < 0)
			type = out[1] > 0 ? GTK_SLIDE_DOWN : GTK_OVER_DOWN;
		else if (out[0] > 0)
			type = GTK_UNDER_RIGHT;
		else if (out[0] < 0)
			type = GTK_UNDER_LEFT;
		else if (out[1] > 0)
			type = GTK_UNDER_DOWN;
		else if (out[1] < 0)
			type = GTK_UNDER_UP;
		else
			type = GTK_CROSSFADE;
		return new int[] {type, duration};
	}

	/** {dx sign, dy sign, duration ms} of an anim resource: the translate motion (from -> to) and its total length. */
	private static float[] tlAnimMotion(Context ctx, int res, boolean entering) {
		float[] m = new float[3];
		if (res == 0)
			return m;
		try {
			android.view.animation.Animation anim = android.view.animation.AnimationUtils.loadAnimation(ctx, res);
			if (anim != null)
				m[2] = anim.computeDurationHint();
		} catch (RuntimeException e) {
			android.util.Log.w(TAG, "activity transition: cannot load animation 0x" + Integer.toHexString(res) + ": " + e);
			return m;
		}
		final String ns = "http://schemas.android.com/apk/res/android";
		android.content.res.XmlResourceParser p = null;
		try {
			p = ctx.getResources().getAnimation(res);
			int ev;
			while ((ev = p.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
				if (ev != org.xmlpull.v1.XmlPullParser.START_TAG || !"translate".equals(p.getName()))
					continue;
				float fx = tlDelta(p.getAttributeValue(ns, "fromXDelta")), tx = tlDelta(p.getAttributeValue(ns, "toXDelta"));
				float fy = tlDelta(p.getAttributeValue(ns, "fromYDelta")), ty = tlDelta(p.getAttributeValue(ns, "toYDelta"));
				/* entering: where it comes from (from - to); leaving: where it goes (to - from) */
				m[0] += entering ? fx - tx : tx - fx;
				m[1] += entering ? fy - ty : ty - fy;
			}
		} catch (Exception e) {
			android.util.Log.w(TAG, "activity transition: cannot read animation 0x" + Integer.toHexString(res) + ": " + e);
		} finally {
			if (p != null)
				p.close();
		}
		m[0] = Math.signum(m[0]);
		m[1] = Math.signum(m[1]);
		return m;
	}

	/** A translate delta as AAPT writes it ("100.0%", "-50.0%p", "48.0dip", "12.0"); 0 for references or nothing. */
	private static float tlDelta(String v) {
		if (v == null || v.isEmpty() || v.charAt(0) == '@' || v.charAt(0) == '?')
			return 0;
		int end = 0;
		while (end < v.length() && "+-.0123456789eE".indexOf(v.charAt(end)) >= 0)
			end++;
		try {
			return end > 0 ? Float.parseFloat(v.substring(0, end)) : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** onDestroy: the page leaves the stack; while a transition still draws it, once that has run. */
	private void tlRemoveDecorPage(final Window w, final long nativeWindow, final long widget) {
		long delay = tlTransitionEndsAt - android.os.SystemClock.uptimeMillis();
		boolean othersAlive;
		synchronized (tlLiveActivities) {
			othersAlive = tlLiveActivities.size() > 1;
		}
		if (delay > 0 && othersAlive) {
			new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
				@Override
				public void run() {
					synchronized (tlLiveActivities) {
						if (tlLiveActivities.isEmpty())
							return; /* the window is gone with the last activity */
					}
					w.tlRemoveRoot(nativeWindow, widget);
				}
			}, delay);
		} else {
			w.tlRemoveRoot(nativeWindow, widget);
		}
	}

	/* ---- manifest ---- */

	/** This activity's manifest entry. */
	android.content.pm.ActivityInfo tlActivityInfo() {
		return tlActivityInfo(getClass().getName());
	}

	android.content.pm.ActivityInfo tlActivityInfo(String className) {
		android.atl.ATLLoadedApp app = android.atl.ATLLoadedApp.getPrimaryApplication();
		if (app == null || app.pkg == null || className == null)
			return null;
		if (className.startsWith("."))
			className = app.pkg.packageName + className;
		for (PackageParser.Activity a : app.pkg.activities) {
			if (className.equals(a.className) || className.equals(a.info.name))
				return a.info;
		}
		return null;
	}

	/* ---- dump ---- */

	/** AOSP: the activity's state and its view hierarchy (dumpsys activity). */
	public void dump(String prefix, java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
		writer.print(prefix);
		writer.print("Local Activity ");
		writer.print(Integer.toHexString(System.identityHashCode(this)));
		writer.println(" State:");
		String innerPrefix = prefix + "  ";
		writer.print(innerPrefix);
		writer.print("mResumed=");
		writer.print(!paused);
		writer.print(" mStopped=");
		writer.print(mStopped);
		writer.print(" mFinished=");
		writer.println(finishing);
		writer.print(innerPrefix);
		writer.print("mIsInMultiWindowMode=");
		writer.print(isInMultiWindowMode());
		writer.print(" mIsInPictureInPictureMode=");
		writer.println(isInPictureInPictureMode());
		writer.print(innerPrefix);
		writer.print("mChangingConfigurations=");
		writer.println(mChangingConfigurations);
		writer.print(innerPrefix);
		writer.print("mCurrentConfig=");
		writer.println(getResources().getConfiguration());
		View decor = window != null ? window.peekDecorView() : null;
		if (decor != null) {
			writer.print(prefix);
			writer.println("View Hierarchy:");
			tlDumpView(innerPrefix, writer, decor);
		}
	}

	private static void tlDumpView(String prefix, java.io.PrintWriter writer, View view) {
		writer.print(prefix);
		writer.println(view);
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++)
				tlDumpView(prefix + "  ", writer, group.getChildAt(i));
		}
	}
