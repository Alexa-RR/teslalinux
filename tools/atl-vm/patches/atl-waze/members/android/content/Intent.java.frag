	/* teslalinux: Intent additions */
	private String tlIdentifier;
	public boolean hasCategory(String category) { java.util.Set<String> c = getCategories(); return c != null && c.contains(category); }
	public CharSequence getCharSequenceExtra(String name) { android.os.Bundle b = getExtras(); return b == null ? null : b.getCharSequence(name); }
	public Intent setIdentifier(String identifier) { tlIdentifier = identifier; return this; }
	public String getIdentifier() { return tlIdentifier; }
	/** AOSP: a copy of only the fields intent matching uses (action, data, type, identifier, package, component, categories). */
	public Intent cloneFilter() {
		Intent i = new Intent(action, data);
		i.type = type; i.tlIdentifier = tlIdentifier; i.packageName = packageName; i.component = component;
		if (tlCategories != null) i.tlCategories = new android.util.ArraySet<String>(tlCategories);
		return i;
	}
	@Override public Object clone() { return new Intent(this); }
	public static Intent makeMainActivity(ComponentName mainActivity) {
		Intent i = new Intent("android.intent.action.MAIN"); i.setComponent(mainActivity); i.addCategory("android.intent.category.LAUNCHER"); return i;
	}
	public static Intent makeMainSelectorActivity(String selectorAction, String selectorCategory) {
		Intent i = new Intent("android.intent.action.MAIN"); i.addCategory(selectorCategory); return i;
	}
	public static Intent makeRestartActivityTask(ComponentName mainActivity) {
		Intent i = makeMainActivity(mainActivity); i.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP); return i;
	}
	public static Intent parseUri(String uri, int flags) throws java.net.URISyntaxException {
		if (uri == null) throw new java.net.URISyntaxException("null", "null uri");
		return new Intent("android.intent.action.VIEW", android.net.Uri.parse(uri));
	}
	public String toUri(int flags) { return getDataString() != null ? getDataString() : ""; }
	/** AOSP toUri flags */
	public static final int URI_INTENT_SCHEME = 1 << 0;
	public static final int URI_ANDROID_APP_SCHEME = 1 << 1;
	public static final int URI_ALLOW_UNSAFE = 1 << 2;

	/** API 33 typed extras: the value when it is an instance of clazz, else null (AOSP semantics). */
	public <T> T getParcelableExtra(String name, Class<T> clazz) {
		Object o = getParcelableExtra(name);
		return clazz.isInstance(o) ? clazz.cast(o) : null;
	}

	public <T extends java.io.Serializable> T getSerializableExtra(String name, Class<T> clazz) {
		Object o = getSerializableExtra(name);
		return clazz.isInstance(o) ? clazz.cast(o) : null;
	}

	public <T> java.util.ArrayList<T> getParcelableArrayListExtra(String name, Class<? extends T> clazz) {
		android.os.Bundle extras = getExtras();
		return extras == null ? null : extras.getParcelableArrayList(name, clazz);
	}

	/** API 22: a chooser with a sender notified of the chosen component; no chooser UI exists here, so the target is used directly. */
	public static Intent createChooser(Intent target, CharSequence title, IntentSender sender) {
		return createChooser(target, title);
	}
	/* AOSP: URI permission grant flags */
	public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;
	public static final int FLAG_GRANT_WRITE_URI_PERMISSION = 0x00000002;
	public static final int FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 0x00000040;
	public static final int FLAG_GRANT_PREFIX_URI_PERMISSION = 0x00000080;
	/** AOSP: deliver to an existing top instance through onNewIntent instead of a new one. */
	public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
