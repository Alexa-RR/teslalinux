	/* ---- teslalinux (app): AOSP Context.startActivity(Intent, Bundle) ----
	 * The options bundle of the launch in progress: the started activity gets its ActivityOptions
	 * (usage time report) and a custom animation becomes the pending activity transition. */
	static android.os.Bundle tlNextLaunchOptions;

	@Override
	public void startActivity(Intent intent, android.os.Bundle options) {
		Activity.tlApplyLaunchOptions(this, options);
		android.os.Bundle previous = tlNextLaunchOptions;
		tlNextLaunchOptions = options;
		try {
			startActivity(intent);
		} finally {
			tlNextLaunchOptions = previous;
		}
	}
