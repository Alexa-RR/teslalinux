	private static final String TAG = "DocumentsContract";
	/** AOSP (hidden): the target of copy/move/isChild calls */
	public static final String EXTRA_TARGET_URI = "android.content.extra.TARGET_URI";
	/* Document column names (AOSP DocumentsContract.Document) used in the call extras */
	private static final String COLUMN_MIME_TYPE = "mime_type";
	private static final String COLUMN_DISPLAY_NAME = "_display_name";

	/** AOSP: the URI of the children of a document of a provider. */
	public static Uri buildChildDocumentsUri(String authority, String parentDocumentId) {
		return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority)
		    .appendPath(PATH_DOCUMENT).appendPath(parentDocumentId).appendPath(PATH_CHILDREN).build();
	}

	/** AOSP: the URI of a document tree (granted with ACTION_OPEN_DOCUMENT_TREE). */
	public static Uri buildTreeDocumentUri(String authority, String documentId) {
		return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority)
		    .appendPath(PATH_TREE).appendPath(documentId).build();
	}

	/** AOSP: removes a document from a parent (a document may be in several). */
	public static boolean removeDocument(ContentResolver content, Uri documentUri, Uri parentDocumentUri) {
		try {
			final android.os.Bundle in = new android.os.Bundle();
			in.putParcelable(EXTRA_URI, documentUri);
			in.putParcelable(EXTRA_PARENT_URI, parentDocumentUri);
			tlCall(content, documentUri, METHOD_REMOVE_DOCUMENT, in);
			return true;
		} catch (Exception e) {
			android.util.Log.w(TAG, "Failed to remove document", e);
			tlRethrow(e);
			return false;
		}
	}
