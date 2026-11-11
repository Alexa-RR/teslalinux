	/* teslalinux: AOSP's locale list (Configuration.mLocaleList); the public locale field stays its first
	 * entry: a direct write to locale replaces the list on the next read (AOSP fixUpLocaleList) */
	private LocaleList tlLocaleList = LocaleList.getEmptyLocaleList();

	private void tlFixUpLocaleList() {
		if ((locale == null && !tlLocaleList.isEmpty()) || (locale != null && !locale.equals(tlLocaleList.get(0))))
			tlLocaleList = locale == null ? LocaleList.getEmptyLocaleList() : new LocaleList(locale);
	}
