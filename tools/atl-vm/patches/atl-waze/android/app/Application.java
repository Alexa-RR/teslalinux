package android.app;

import android.R;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.atl.ATLLoadedApp;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageParser;
import android.content.res.Configuration;
import android.os.Bundle;

import java.util.ArrayList;

/* teslalinux: ATL's Application plus the AOSP ActivityLifecycleCallbacks contract (Pre/Post
 * variants included) and the dispatch methods Activity.perform*() call. AndroidX's lifecycle,
 * ProcessLifecycleOwner and Compose's window recomposer all hang off these callbacks. */
public class Application extends ContextWrapper {
	public long native_window;

	private String get_app_icon_path() {
		String icon_path = null;
		try {
			icon_path = getString(this.get_atl_loaded_app().pkg.applicationInfo.icon);
		} catch (android.content.res.Resources.NotFoundException e) {
			e.printStackTrace();
		}
		if (icon_path == null) {
			icon_path = getString(R.mipmap.sym_def_app_icon);
		} else if (icon_path.endsWith(".xml")) {
			icon_path = null;
		}
		return icon_path;
	}

	private long get_app_icon_paintable() {
		return getPackageManager().getApplicationIcon(this.get_atl_loaded_app().pkg.applicationInfo).paintable;
	}

	String get_app_label() {
		return getString(this.get_atl_loaded_app().pkg.applicationInfo.labelRes);
	}

	String get_supported_mime_types() {
		StringBuilder mimeTypes = new StringBuilder();
		for (PackageParser.Activity activity : this.get_atl_loaded_app().pkg.activities) {
			if (activity.intents == null)
				continue;
			for (PackageParser.IntentInfo intent : activity.intents) {
				for (int i = 0; i < intent.countDataSchemes(); i++) {
					String scheme = intent.getDataScheme(i);
					// ignore http and https, as there is no way to only handle specific hosts in a .desktop file
					if (!"http".equals(scheme) && !"https".equals(scheme))
						mimeTypes.append("x-scheme-handler/").append(intent.getDataScheme(i)).append(";");
				}
			}
		}
		return (mimeTypes.length() == 0) ? null : mimeTypes.toString();
	}

	/** AOSP interface; the Pre/Post methods were added in API 29 as defaults. */
	public interface ActivityLifecycleCallbacks {
		default void onActivityPreCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}
		void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState);
		default void onActivityPostCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}
		default void onActivityPreStarted(@NonNull Activity activity) {}
		void onActivityStarted(@NonNull Activity activity);
		default void onActivityPostStarted(@NonNull Activity activity) {}
		default void onActivityPreResumed(@NonNull Activity activity) {}
		void onActivityResumed(@NonNull Activity activity);
		default void onActivityPostResumed(@NonNull Activity activity) {}
		default void onActivityPrePaused(@NonNull Activity activity) {}
		void onActivityPaused(@NonNull Activity activity);
		default void onActivityPostPaused(@NonNull Activity activity) {}
		default void onActivityPreStopped(@NonNull Activity activity) {}
		void onActivityStopped(@NonNull Activity activity);
		default void onActivityPostStopped(@NonNull Activity activity) {}
		default void onActivityPreSaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}
		void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState);
		default void onActivityPostSaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}
		default void onActivityPreDestroyed(@NonNull Activity activity) {}
		void onActivityDestroyed(@NonNull Activity activity);
		default void onActivityPostDestroyed(@NonNull Activity activity) {}
		default void onActivityConfigurationChanged(@NonNull Activity activity) {}
	}

	/**
	 * Callback interface for use with {@link Application#registerOnProvideAssistDataListener}
	 * and {@link Application#unregisterOnProvideAssistDataListener}.
	 */
	public interface OnProvideAssistDataListener {
		public void onProvideAssistData(Activity activity, Bundle data);
	}

	private final ArrayList<ActivityLifecycleCallbacks> mActivityLifecycleCallbacks = new ArrayList<>();
	private final ArrayList<OnProvideAssistDataListener> mAssistCallbacks = new ArrayList<>();

	public Application() {
		super(null);
	}

	public void onCreate() {
	}

	public void onTerminate() {
	}

	/* AOSP: the registered ComponentCallbacks get the application's configuration and memory callbacks */
	private final ArrayList<android.content.ComponentCallbacks> mComponentCallbacks = new ArrayList<>();

	private Object[] collectComponentCallbacks() {
		synchronized (mComponentCallbacks) {
			return mComponentCallbacks.isEmpty() ? null : mComponentCallbacks.toArray();
		}
	}

	public void onConfigurationChanged(Configuration newConfig) {
		Object[] callbacks = collectComponentCallbacks();
		if (callbacks != null)
			for (Object c : callbacks)
				tlInvoke(c, "onConfigurationChanged", Configuration.class, newConfig);
	}

	public void onLowMemory() {
		Object[] callbacks = collectComponentCallbacks();
		if (callbacks != null)
			for (Object c : callbacks)
				tlInvoke(c, "onLowMemory", null, null);
	}

	public void onTrimMemory(int level) {
		Object[] callbacks = collectComponentCallbacks();
		if (callbacks != null)
			for (Object c : callbacks)
				if (c instanceof android.content.ComponentCallbacks2)
					tlInvoke(c, "onTrimMemory", int.class, level);
	}

	/* ATL's ComponentCallbacks interfaces do not declare their methods (android.content), so the
	 * callbacks are called by name, as the interface methods AOSP calls */
	private static void tlInvoke(Object target, String name, Class<?> type, Object arg) {
		try {
			java.lang.reflect.Method m = type == null ? target.getClass().getMethod(name) : target.getClass().getMethod(name, type);
			if (type == null)
				m.invoke(target);
			else
				m.invoke(target, arg);
		} catch (NoSuchMethodException e) {
			/* the callback object does not implement this callback */
		} catch (java.lang.reflect.InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException)
				throw (RuntimeException) cause;
			if (cause instanceof Error)
				throw (Error) cause;
			throw new RuntimeException(cause);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	public void registerComponentCallbacks(android.content.ComponentCallbacks callback) {
		synchronized (mComponentCallbacks) {
			mComponentCallbacks.add(callback);
		}
	}

	public void unregisterComponentCallbacks(android.content.ComponentCallbacks callback) {
		synchronized (mComponentCallbacks) {
			mComponentCallbacks.remove(callback);
		}
	}

	public void registerActivityLifecycleCallbacks(@NonNull ActivityLifecycleCallbacks callback) {
		synchronized (mActivityLifecycleCallbacks) {
			mActivityLifecycleCallbacks.add(callback);
		}
	}

	public void unregisterActivityLifecycleCallbacks(@NonNull ActivityLifecycleCallbacks callback) {
		synchronized (mActivityLifecycleCallbacks) {
			mActivityLifecycleCallbacks.remove(callback);
		}
	}

	public void registerOnProvideAssistDataListener(OnProvideAssistDataListener callback) {
		synchronized (mAssistCallbacks) {
			mAssistCallbacks.add(callback);
		}
	}

	public void unregisterOnProvideAssistDataListener(OnProvideAssistDataListener callback) {
		synchronized (mAssistCallbacks) {
			mAssistCallbacks.remove(callback);
		}
	}

	public static String getProcessName() {
		return ATLLoadedApp.getPrimaryApplication().pkg.packageName;
	}

	private Object[] collectActivityLifecycleCallbacks() {
		Object[] callbacks = null;
		synchronized (mActivityLifecycleCallbacks) {
			if (mActivityLifecycleCallbacks.size() > 0) {
				callbacks = mActivityLifecycleCallbacks.toArray();
			}
		}
		return callbacks;
	}

	/* The dispatch methods below mirror AOSP's; Activity.perform*() calls them in AOSP's order. */
	public void dispatchActivityPreCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPreCreated(activity, savedInstanceState);
	}
	public void dispatchActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
		if (System.getenv("ATL_DEBUG_LIFECYCLE") != null)
			android.util.Log.i("Lifecycle", "dispatchActivityCreated " + activity.getClass().getName() + " callbacks=" + mActivityLifecycleCallbacks.size());
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityCreated(activity, savedInstanceState);
	}
	public void dispatchActivityPostCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPostCreated(activity, savedInstanceState);
	}
	public void dispatchActivityPreStarted(@NonNull Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPreStarted(activity);
	}
	public void dispatchActivityStarted(@NonNull Activity activity) {
		if (System.getenv("ATL_DEBUG_LIFECYCLE") != null)
			android.util.Log.i("Lifecycle", "dispatchActivityStarted " + activity.getClass().getName() + " callbacks=" + mActivityLifecycleCallbacks.size());
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityStarted(activity);
	}
	public void dispatchActivityPostStarted(@NonNull Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPostStarted(activity);
	}
	public void dispatchActivityPreResumed(@NonNull Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPreResumed(activity);
	}
	public void dispatchActivityResumed(@NonNull Activity activity) {
		if (System.getenv("ATL_DEBUG_LIFECYCLE") != null)
			android.util.Log.i("Lifecycle", "dispatchActivityResumed " + activity.getClass().getName() + " callbacks=" + mActivityLifecycleCallbacks.size());
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityResumed(activity);
	}
	public void dispatchActivityPostResumed(@NonNull Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPostResumed(activity);
	}
	public void dispatchActivityPrePaused(@NonNull Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPrePaused(activity);
	}
	public void dispatchActivityPaused(@NonNull Activity activity) {
		if (System.getenv("ATL_DEBUG_LIFECYCLE") != null)
			android.util.Log.i("Lifecycle", "dispatchActivityPaused " + activity.getClass().getName() + " callbacks=" + mActivityLifecycleCallbacks.size());
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPaused(activity);
	}
	public void dispatchActivityPostPaused(@NonNull Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPostPaused(activity);
	}
	public void dispatchActivityPreStopped(@NonNull Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPreStopped(activity);
	}
	public void dispatchActivityStopped(@NonNull Activity activity) {
		if (System.getenv("ATL_DEBUG_LIFECYCLE") != null)
			android.util.Log.i("Lifecycle", "dispatchActivityStopped " + activity.getClass().getName() + " callbacks=" + mActivityLifecycleCallbacks.size());
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityStopped(activity);
	}
	public void dispatchActivityPostStopped(@NonNull Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPostStopped(activity);
	}
	public void dispatchActivityPreSaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPreSaveInstanceState(activity, outState);
	}
	public void dispatchActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivitySaveInstanceState(activity, outState);
	}
	public void dispatchActivityPostSaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPostSaveInstanceState(activity, outState);
	}
	public void dispatchActivityPreDestroyed(@NonNull Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPreDestroyed(activity);
	}
	public void dispatchActivityDestroyed(@NonNull Activity activity) {
		if (System.getenv("ATL_DEBUG_LIFECYCLE") != null)
			android.util.Log.i("Lifecycle", "dispatchActivityDestroyed " + activity.getClass().getName() + " callbacks=" + mActivityLifecycleCallbacks.size());
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityDestroyed(activity);
	}
	public void dispatchActivityPostDestroyed(@NonNull Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityPostDestroyed(activity);
	}
	public void dispatchActivityConfigurationChanged(@NonNull Activity activity) {
		Object[] callbacks = collectActivityLifecycleCallbacks();
		if (callbacks != null) for (Object cb : callbacks) ((ActivityLifecycleCallbacks) cb).onActivityConfigurationChanged(activity);
	}
}
