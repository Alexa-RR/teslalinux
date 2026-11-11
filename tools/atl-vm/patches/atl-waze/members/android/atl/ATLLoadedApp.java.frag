	/* teslalinux: the Resources created for this app and the override each was created with */
	private final java.util.ArrayList<Object[]> tl_resources = new java.util.ArrayList<>();

	/** AOSP ResourcesManager.applyConfigurationToResources: a system configuration change reaches every Resources, each keeping its override. */
	public void tlApplyConfiguration(Configuration sys, DisplayMetrics metrics) {
		this.default_resources.updateConfiguration(sys, metrics);
		synchronized (tl_resources) {
			for (int i = tl_resources.size() - 1; i >= 0; i--) {
				Object[] entry = tl_resources.get(i);
				Resources res = ((java.lang.ref.WeakReference<Resources>) entry[0]).get();
				if (res == null) {
					tl_resources.remove(i);
					continue;
				}
				Configuration effective = new Configuration(sys);
				effective.updateFrom((Configuration) entry[1]);
				res.updateConfiguration(effective, metrics);
			}
		}
	}
