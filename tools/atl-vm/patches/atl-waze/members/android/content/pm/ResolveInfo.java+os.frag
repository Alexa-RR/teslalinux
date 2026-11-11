	/** AOSP: the provider that matched (queryIntentContentProviders), else null. */
	public ProviderInfo providerInfo;
	/** AOSP: the label of the matched filter, if it has one. */
	public int labelRes;
	public CharSequence nonLocalizedLabel;
	/** AOSP: the icon of the matched filter, if it has one. */
	public int icon;

	/**
	 * The component this resolution is for: the provider or service when one was resolved, else the
	 * activity (ATL keeps an ActivityInfo/ServiceInfo object in every ResolveInfo, so the one with a
	 * class name is the resolved one).
	 */
	ComponentInfo tlGetComponentInfo() {
		if (providerInfo != null)
			return providerInfo;
		if (serviceInfo != null && serviceInfo.name != null)
			return serviceInfo;
		return activityInfo;
	}
