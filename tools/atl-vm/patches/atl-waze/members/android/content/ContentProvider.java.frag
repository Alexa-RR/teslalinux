	/* AOSP (hidden): user id prefixes of content URIs ("userId@authority") */
	public static int getUserIdFromAuthority(String auth, int defaultUserId) {
		if (auth == null)
			return defaultUserId;
		int end = auth.lastIndexOf(64);
		if (end == -1)
			return defaultUserId;
		String userIdString = auth.substring(0, end);
		try {
			return Integer.parseInt(userIdString);
		} catch (NumberFormatException e) {
			android.util.Log.w("ContentProvider", "Error parsing userId.", e);
			return android.os.UserHandle.USER_NULL;
		}
	}

	public static int getUserIdFromAuthority(String auth) {
		return getUserIdFromAuthority(auth, android.os.UserHandle.USER_CURRENT);
	}

	public static int getUserIdFromUri(android.net.Uri uri, int defaultUserId) {
		if (uri == null)
			return defaultUserId;
		return getUserIdFromAuthority(uri.getAuthority(), defaultUserId);
	}

	public static int getUserIdFromUri(android.net.Uri uri) {
		return getUserIdFromUri(uri, android.os.UserHandle.USER_CURRENT);
	}

	public static boolean uriHasUserId(android.net.Uri uri) {
		if (uri == null)
			return false;
		return !android.text.TextUtils.isEmpty(uri.getUserInfo());
	}

	public static android.net.Uri maybeAddUserId(android.net.Uri uri, int userId) {
		if (uri == null)
			return null;
		if (userId != android.os.UserHandle.USER_CURRENT && "content".equals(uri.getScheme())) {
			if (!uriHasUserId(uri)) {
				android.net.Uri.Builder builder = uri.buildUpon();
				builder.encodedAuthority("" + userId + "@" + uri.getEncodedAuthority());
				return builder.build();
			}
		}
		return uri;
	}
