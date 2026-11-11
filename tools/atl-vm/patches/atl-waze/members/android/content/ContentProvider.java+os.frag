	/* AOSP ContentProvider state set by attachInfo */
	private Context tlContext;
	private String tlReadPermission;
	private String tlWritePermission;
	private android.content.pm.PathPermission[] tlPathPermissions;
	private boolean tlExported;
	private boolean tlSingleUser;
	private String[] tlAuthorities;
	private int tlMyUid;

	/** AOSP: the provider's own uid (its process's). */
	final int tlGetMyUid() {
		return tlMyUid;
	}

	/** AOSP: the context and the manifest data of the provider (once). */
	void tlAttachInfo(Context context, ProviderInfo info) {
		if (tlContext == null) {
			tlContext = context;
			tlMyUid = android.os.Process.myUid();
			if (info != null) {
				setReadPermission(info.readPermission);
				setWritePermission(info.writePermission);
				setPathPermissions(info.pathPermissions);
				tlExported = info.exported;
				tlSingleUser = (info.flags & ProviderInfo.FLAG_SINGLE_USER) != 0;
				setAuthorities(info.authority);
			}
		}
	}

	/** AOSP: sets the authorities (';' separated) this provider serves. */
	protected final void setAuthorities(String authorities) {
		if (authorities != null) {
			if (authorities.indexOf(';') == -1) {
				tlAuthorities = new String[] {authorities};
			} else {
				tlAuthorities = authorities.split(";");
			}
		} else {
			tlAuthorities = null;
		}
	}

	/** AOSP (hidden): whether this provider serves the authority. */
	protected final boolean matchesOurAuthorities(String authority) {
		if (tlAuthorities != null) {
			for (String a : tlAuthorities) {
				if (a.equals(authority))
					return true;
			}
		}
		return false;
	}

	/** AOSP: the permission readers need, from the manifest or setReadPermission. */
	public final String getReadPermission() {
		return tlReadPermission;
	}

	protected final void setReadPermission(String permission) {
		tlReadPermission = permission;
	}

	/** AOSP: the permission writers need. */
	public final String getWritePermission() {
		return tlWritePermission;
	}

	protected final void setWritePermission(String permission) {
		tlWritePermission = permission;
	}

	/** AOSP: the path-specific permissions. */
	public final android.content.pm.PathPermission[] getPathPermissions() {
		return tlPathPermissions;
	}

	protected final void setPathPermissions(android.content.pm.PathPermission[] permissions) {
		tlPathPermissions = permissions;
	}

	/** AOSP: whether this provider runs in a single user only (FLAG_SINGLE_USER). */
	public boolean isSingleUser() {
		return tlSingleUser;
	}

	/** AOSP: whether the manifest exports the provider. */
	public boolean isExported() {
		return tlExported;
	}

	/** AOSP: the context the provider was attached to (null before attachInfo). */
	public final Context requireContext() {
		final Context ctx = getContext();
		if (ctx == null) {
			throw new IllegalStateException("Cannot find context from the provider.");
		}
		return ctx;
	}

	/** AOSP: inserts each value with insert(); providers override it to batch. */
	public int bulkInsert(Uri uri, ContentValues[] values) {
		int numValues = values.length;
		for (int i = 0; i < numValues; i++) {
			insert(uri, values[i]);
		}
		return numValues;
	}

	/** AOSP: insert with extras; the default ignores them. */
	public Uri insert(Uri uri, ContentValues values, android.os.Bundle extras) {
		return insert(uri, values);
	}

	/** AOSP: the query of the Bundle arguments with the SQL ones taken from them. */
	public Cursor query(Uri uri, String[] projection, android.os.Bundle queryArgs,
	                    android.os.CancellationSignal cancellationSignal) {
		queryArgs = queryArgs != null ? queryArgs : android.os.Bundle.EMPTY;
		String sortClause = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER);
		return query(uri, projection, queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
		             queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS), sortClause, cancellationSignal);
	}

	/** AOSP: the cancellable query; the default ignores the signal. */
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
	                    String sortOrder, android.os.CancellationSignal cancellationSignal) {
		return query(uri, projection, selection, selectionArgs, sortOrder);
	}

	/** AOSP: provider-defined calls; the default implements none and returns null. */
	public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
		return null;
	}

	/** AOSP: call with the authority it was addressed to; the default drops it. */
	public android.os.Bundle call(String authority, String method, String arg, android.os.Bundle extras) {
		return call(method, arg, extras);
	}

	/** AOSP: the MIME types of streams openTypedAssetFile can give; none by default. */
	public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
		return null;
	}

	/** AOSP: providers without canonical URIs return null. */
	public Uri canonicalize(Uri url) {
		return null;
	}

	/** AOSP: the inverse of canonicalize; the default returns the URI unchanged. */
	public Uri uncanonicalize(Uri url) {
		return url;
	}

	/** AOSP: a provider that cannot refresh returns false. */
	public boolean refresh(Uri uri, android.os.Bundle extras, android.os.CancellationSignal cancellationSignal) {
		return false;
	}

	/** AOSP: openFile with a cancellation signal; the default ignores the signal. */
	public ParcelFileDescriptor openFile(Uri uri, String mode, android.os.CancellationSignal signal)
	    throws FileNotFoundException {
		return openFile(uri, mode);
	}

	/** AOSP: openAssetFile with a cancellation signal; the default ignores the signal. */
	public AssetFileDescriptor openAssetFile(Uri uri, String mode, android.os.CancellationSignal signal)
	    throws FileNotFoundException {
		return openAssetFile(uri, mode);
	}

	/**
	 * AOSP: a stream of the given MIME type. "*" + "/" + "*" or a filter the provider's type of the
	 * URI matches opens it as is; anything else is not available.
	 */
	public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, android.os.Bundle opts)
	    throws FileNotFoundException {
		if ("*/*".equals(mimeTypeFilter)) {
			// If they can take anything, the untyped open call is good enough.
			return openAssetFile(uri, "r");
		}
		String baseType = getType(uri);
		if (baseType != null && ClipDescription.compareMimeTypes(baseType, mimeTypeFilter)) {
			// Use old untyped open call if this provider has a type for this
			// URI and it matches the request.
			return openAssetFile(uri, "r");
		}
		throw new FileNotFoundException("Can't open " + uri + " as type " + mimeTypeFilter);
	}

	/** AOSP: openTypedAssetFile with a cancellation signal; the default ignores the signal. */
	public AssetFileDescriptor openTypedAssetFile(Uri uri, String mimeTypeFilter, android.os.Bundle opts,
	                                              android.os.CancellationSignal signal) throws FileNotFoundException {
		return openTypedAssetFile(uri, mimeTypeFilter, opts);
	}

	/** AOSP: called when the provider is shut down (never in a running app); the default logs. */
	public void shutdown() {
		android.util.Log.w("ContentProvider", "implement ContentProvider shutdown() to make sure all database "
		                                      + "connections are gracefully shutdown");
	}

	/** AOSP: memory trim callback; the default does nothing (ComponentCallbacks2 contract). */
	public void onTrimMemory(int level) {
	}

	/** AOSP: low-memory callback; the default does nothing. */
	public void onLowMemory() {
	}
