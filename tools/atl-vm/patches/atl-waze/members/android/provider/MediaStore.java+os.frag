	/** AOSP: the query parameter that asks for the original media (location metadata not redacted). */
	private static final String PARAM_REQUIRE_ORIGINAL = "requireOriginal";

	/** AOSP: the URI asking for the original, unredacted media. */
	public static Uri setRequireOriginal(Uri uri) {
		return uri.buildUpon().appendQueryParameter(PARAM_REQUIRE_ORIGINAL, "1").build();
	}

	/** AOSP: whether setRequireOriginal was applied to the URI. */
	public static boolean getRequireOriginal(Uri uri) {
		return "1".equals(uri.getQueryParameter(PARAM_REQUIRE_ORIGINAL));
	}

	/** AOSP: the most images the photo picker lets a user select (MediaProvider's fixed limit). */
	public static int getPickImagesMaxLimit() {
		return 100;
	}

	/** AOSP (hidden): opens the media file through the resolver. */
	public static android.content.res.AssetFileDescriptor openAssetFileDescriptor(ContentResolver resolver, Uri uri,
	                                                                               String mode, android.os.CancellationSignal signal)
	    throws FileNotFoundException {
		return resolver.openAssetFileDescriptor(uri, mode, signal);
	}
