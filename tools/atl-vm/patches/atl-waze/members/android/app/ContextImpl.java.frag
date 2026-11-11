	/** AOSP PackageManager resolution of an implicit intent against this app's own activities (intent filters). */
	private String tlResolveViewIntent(Intent intent) {
		if (atl_loaded_app == null || atl_loaded_app.pkg == null)
			return null;
		String type = intent.resolveTypeIfNeeded(getContentResolver());
		for (PackageParser.Activity activity : atl_loaded_app.pkg.activities) {
			for (PackageParser.ActivityIntentInfo info : activity.intents) {
				if (info.match(intent.getAction(), type, intent.getScheme(), intent.getData(), intent.getCategories(), TAG) >= 0)
					return activity.className;
			}
		}
		return null;
	}

	/** AOSP: singleTask/singleInstance activities and FLAG_ACTIVITY_SINGLE_TOP reuse a running instance (onNewIntent). */
	private boolean tlResumesExisting(String className, Intent intent) {
		if (className == null || atl_loaded_app == null || atl_loaded_app.pkg == null)
			return false;
		if ((intent.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0)
			return true;
		for (PackageParser.Activity activity : atl_loaded_app.pkg.activities) {
			if (className.equals(activity.className) && activity.info != null)
				return activity.info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK
				    || activity.info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
		}
		return false;
	}
