	/** Every app loaded in this process (for configuration changes). */
	public static java.util.Collection<ATLLoadedApp> tlLoadedApps() {
		synchronized (load_lock) {
			return new java.util.ArrayList<>(loaded_apps.values());
		}
	}
