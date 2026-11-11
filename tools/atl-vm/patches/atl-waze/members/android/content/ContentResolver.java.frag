	/* teslalinux: provider clients. ATL's providers are the app's own, in this process; a client is
	 * handed out for an authority that has one and null otherwise ("no such provider"), as on
	 * Android, which callers (GMS Phenotype, Play Core, Firebase) are written to handle. */
	public final ContentProviderClient acquireContentProviderClient(Uri uri) {
		if (uri == null)
			throw new NullPointerException("uri");
		return SCHEME_CONTENT.equals(uri.getScheme()) ? acquireContentProviderClient(uri.getAuthority()) : null;
	}

	public final ContentProviderClient acquireContentProviderClient(String name) {
		if (name == null)
			throw new NullPointerException("name");
		ContentProvider provider = ContentProvider.atl_get_content_provider(name);
		return provider != null ? new ContentProviderClient(this, provider, name, true) : null;
	}

	public final ContentProviderClient acquireUnstableContentProviderClient(Uri uri) {
		if (uri == null)
			throw new NullPointerException("uri");
		return SCHEME_CONTENT.equals(uri.getScheme()) ? acquireUnstableContentProviderClient(uri.getAuthority()) : null;
	}

	public final ContentProviderClient acquireUnstableContentProviderClient(String name) {
		if (name == null)
			throw new NullPointerException("name");
		ContentProvider provider = ContentProvider.atl_get_content_provider(name);
		return provider != null ? new ContentProviderClient(this, provider, name, false) : null;
	}
