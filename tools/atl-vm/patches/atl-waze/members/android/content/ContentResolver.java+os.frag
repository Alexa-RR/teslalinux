	/* AOSP query-argument keys (Bundle form of the SQL arguments) */
	public static final String QUERY_ARG_SQL_SELECTION = "android:query-arg-sql-selection";
	public static final String QUERY_ARG_SQL_SELECTION_ARGS = "android:query-arg-sql-selection-args";
	public static final String QUERY_ARG_SQL_SORT_ORDER = "android:query-arg-sql-sort-order";
	public static final String QUERY_ARG_SQL_GROUP_BY = "android:query-arg-sql-group-by";
	public static final String QUERY_ARG_SQL_HAVING = "android:query-arg-sql-having";
	public static final String QUERY_ARG_SQL_LIMIT = "android:query-arg-sql-limit";

	/* AOSP notifyChange flags */
	public static final int NOTIFY_SYNC_TO_NETWORK = 1 << 0;
	public static final int NOTIFY_SKIP_NOTIFY_FOR_DESCENDANTS = 1 << 1;
	public static final int NOTIFY_INSERT = 1 << 2;
	public static final int NOTIFY_UPDATE = 1 << 3;
	public static final int NOTIFY_DELETE = 1 << 4;
	public static final int NOTIFY_NO_DELAY = 1 << 15;

	/** AOSP (hidden): the Bundle query arguments for SQL selection, arguments and sort order. */
	public static android.os.Bundle createSqlQueryBundle(String selection, String[] selectionArgs, String sortOrder) {
		if (selection == null && selectionArgs == null && sortOrder == null) {
			return null;
		}
		android.os.Bundle queryArgs = new android.os.Bundle();
		if (selection != null) {
			queryArgs.putString(QUERY_ARG_SQL_SELECTION, selection);
		}
		if (selectionArgs != null) {
			queryArgs.putStringArray(QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
		}
		if (sortOrder != null) {
			queryArgs.putString(QUERY_ARG_SQL_SORT_ORDER, sortOrder);
		}
		return queryArgs;
	}

	/**
	 * teslalinux: the observer registry of AOSP's ContentService, for this process (the only one
	 * ATL runs an app in). Registrations are kept per observer; a change to a URI reaches observers
	 * of that URI, of its ancestors that asked for descendants, and of its descendants, exactly as
	 * ContentService.ObserverNode collects them.
	 */
	private static final class TlObserverEntry {
		final java.util.List<String> segments;
		final boolean notifyForDescendants;
		final ContentObserver observer;
		final int userHandle;

		TlObserverEntry(Uri uri, boolean notifyForDescendants, ContentObserver observer, int userHandle) {
			this.segments = tlUriSegments(uri);
			this.notifyForDescendants = notifyForDescendants;
			this.observer = observer;
			this.userHandle = userHandle;
		}
	}

	private static final java.util.ArrayList<TlObserverEntry> tlObservers = new java.util.ArrayList<>();

	/** ContentService.ObserverNode: the authority, then each path segment */
	private static java.util.List<String> tlUriSegments(Uri uri) {
		java.util.ArrayList<String> s = new java.util.ArrayList<>();
		s.add(uri.getAuthority());
		s.addAll(uri.getPathSegments());
		return s;
	}

	private static void tlRegister(Uri uri, boolean notifyForDescendants, ContentObserver observer, int userHandle) {
		if (uri == null)
			throw new NullPointerException("uri");
		if (observer == null)
			throw new NullPointerException("observer");
		synchronized (tlObservers) {
			tlObservers.add(new TlObserverEntry(uri, notifyForDescendants, observer, userHandle));
		}
	}

	private static void tlUnregister(ContentObserver observer) {
		if (observer == null)
			throw new IllegalArgumentException("You must pass a valid observer");
		synchronized (tlObservers) {
			java.util.Iterator<TlObserverEntry> it = tlObservers.iterator();
			while (it.hasNext()) {
				if (it.next().observer == observer)
					it.remove();
			}
		}
	}

	private static void tlNotify(java.util.Collection<Uri> uris, ContentObserver source, int flags, int userHandle) {
		// ContentService.notifyChange: collect per observer, then dispatch each its URIs once
		java.util.LinkedHashMap<ContentObserver, java.util.ArrayList<Uri>> targets = new java.util.LinkedHashMap<>();
		java.util.HashMap<ContentObserver, Boolean> selfChange = new java.util.HashMap<>();
		synchronized (tlObservers) {
			for (Uri uri : uris) {
				java.util.List<String> us = tlUriSegments(uri);
				for (TlObserverEntry e : tlObservers) {
					if (e.userHandle != android.os.UserHandle.USER_ALL && userHandle != android.os.UserHandle.USER_ALL
					    && e.userHandle != userHandle && e.userHandle != android.os.UserHandle.USER_CURRENT
					    && userHandle != android.os.UserHandle.USER_CURRENT)
						continue;
					boolean self = e.observer == source;
					if (self && !source.deliverSelfNotifications())
						continue;
					java.util.List<String> rs = e.segments;
					boolean leaf;
					if (rs.size() <= us.size() && us.subList(0, rs.size()).equals(rs)) {
						leaf = rs.size() == us.size();
					} else if (rs.size() > us.size() && rs.subList(0, us.size()).equals(us)) {
						leaf = true; // registered below the changed URI: every observer there hears it
					} else {
						continue;
					}
					if (leaf) {
						if ((flags & NOTIFY_SKIP_NOTIFY_FOR_DESCENDANTS) != 0 && e.notifyForDescendants)
							continue;
					} else if (!e.notifyForDescendants) {
						continue;
					}
					java.util.ArrayList<Uri> list = targets.get(e.observer);
					if (list == null) {
						list = new java.util.ArrayList<>();
						targets.put(e.observer, list);
					}
					if (!list.contains(uri))
						list.add(uri);
					selfChange.put(e.observer, self);
				}
			}
		}
		for (java.util.Map.Entry<ContentObserver, java.util.ArrayList<Uri>> t : targets.entrySet()) {
			t.getKey().dispatchChange(selfChange.get(t.getKey()), t.getValue(), flags);
		}
	}

	/** AOSP: notify with a sync-to-network request (no sync adapters in ATL: only observers). */
	public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork) {
		notifyChange(uri, observer, syncToNetwork ? NOTIFY_SYNC_TO_NETWORK : 0);
	}

	/** AOSP: notify registered observers that a row was updated. */
	public void notifyChange(Uri uri, ContentObserver observer, int flags) {
		if (uri == null)
			throw new NullPointerException("uri");
		tlNotify(java.util.Collections.singletonList(uri), observer, flags, getUserId());
	}

	/** AOSP: notify registered observers that several rows changed. */
	public void notifyChange(java.util.Collection<Uri> uris, ContentObserver observer, int flags) {
		if (uris == null)
			throw new NullPointerException("uris");
		tlNotify(uris, observer, flags, getUserId());
	}

	/** AOSP: calls a provider-defined method of the provider serving uri's authority. */
	public final android.os.Bundle call(Uri uri, String method, String arg, android.os.Bundle extras) {
		if (uri == null)
			throw new NullPointerException("uri");
		return call(uri.getAuthority(), method, arg, extras);
	}

	/** AOSP: calls a provider-defined method; no provider for the authority is an IllegalArgumentException. */
	public final android.os.Bundle call(String authority, String method, String arg, android.os.Bundle extras) {
		if (authority == null)
			throw new NullPointerException("authority");
		if (method == null)
			throw new NullPointerException("method");
		ContentProvider provider = ContentProvider.atl_get_content_provider(authority);
		if (provider == null)
			throw new IllegalArgumentException("Unknown authority " + authority);
		return provider.call(authority, method, arg, extras);
	}

	/** AOSP: inserts several rows through the provider (0 when there is no provider). */
	public final int bulkInsert(Uri url, ContentValues[] values) {
		ContentProvider provider = ContentProvider.atl_get_content_provider(url.getAuthority());
		if (provider == null)
			throw new IllegalArgumentException("Unknown URL " + url);
		return provider.bulkInsert(url, values);
	}

	/** AOSP: the MIME types the provider can stream for the URI matching the filter, or null. */
	public String[] getStreamTypes(Uri url, String mimeTypeFilter) {
		ContentProvider provider = ContentProvider.atl_get_content_provider(url.getAuthority());
		return provider != null ? provider.getStreamTypes(url, mimeTypeFilter) : null;
	}

	/** AOSP: the provider's canonical form of a URI, or null. */
	public final Uri canonicalize(Uri url) {
		ContentProvider provider = ContentProvider.atl_get_content_provider(url.getAuthority());
		return provider != null ? provider.canonicalize(url) : null;
	}

	/** AOSP: the inverse of canonicalize, or null. */
	public final Uri uncanonicalize(Uri url) {
		ContentProvider provider = ContentProvider.atl_get_content_provider(url.getAuthority());
		return provider != null ? provider.uncanonicalize(url) : null;
	}
