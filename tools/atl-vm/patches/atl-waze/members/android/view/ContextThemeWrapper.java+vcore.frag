import android.content.res.Configuration;
import android.content.res.AssetManager;
	// ---- teslalinux (vcore): AOSP override configuration (AppCompat applies its night mode / locale this way) ----
	private Configuration tlOverrideConfiguration;
	private Resources tlOverrideResources;
	private boolean tlResourcesRequested;

	/**
	 * AOSP: the resources of this context use the base configuration with overrideConfiguration
	 * applied. Must be called before getResources()/getAssets(), and only once.
	 */
	public void applyOverrideConfiguration(Configuration overrideConfiguration) {
		if (tlResourcesRequested) {
			throw new IllegalStateException("getResources() or getAssets() has already been called");
		}
		if (tlOverrideConfiguration != null) {
			throw new IllegalStateException("Override configuration has already been set");
		}
		tlOverrideConfiguration = new Configuration(overrideConfiguration);
	}

	/** AOSP: the configuration applied by applyOverrideConfiguration, null when none */
	public Configuration getOverrideConfiguration() {
		return tlOverrideConfiguration;
	}

	/** AOSP getResourcesInternal: the override configuration's resources, else the base context's */
	@Override
	public Resources getResources() {
		tlResourcesRequested = true;
		if (tlOverrideConfiguration == null)
			return super.getResources();
		if (tlOverrideResources == null)
			tlOverrideResources = createConfigurationContext(tlOverrideConfiguration).getResources();
		return tlOverrideResources;
	}

	@Override
	public AssetManager getAssets() {
		// AOSP: the assets of getResources(), so an override configuration is honoured here too
		return getResources().getAssets();
	}
