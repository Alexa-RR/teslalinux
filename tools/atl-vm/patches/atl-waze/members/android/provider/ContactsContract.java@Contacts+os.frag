		/** AOSP: the contact's _ID column. */
		public static final String _ID = "_id";

		/** AOSP Contacts.Photo: the photo sub-directory of a contact and its blob column. */
		public static final class Photo {
			public static final String CONTENT_DIRECTORY = "photo";
			public static final String DISPLAY_PHOTO = "display_photo";
			public static final String PHOTO = "data15";
		}

		/**
		 * AOSP: the content URI of the contact a lookup URI points to, or null. The contacts provider
		 * answers the query; with none on this device (no contacts app) the query has no result.
		 */
		public static Uri lookupContact(android.content.ContentResolver resolver, Uri lookupUri) {
			if (lookupUri == null) {
				return null;
			}
			android.database.Cursor c = resolver.query(lookupUri, new String[] {_ID}, null, null, null);
			if (c == null) {
				return null;
			}
			try {
				if (c.moveToFirst()) {
					long contactId = c.getLong(0);
					return android.content.ContentUris.withAppendedId(CONTENT_URI, contactId);
				}
			} finally {
				c.close();
			}
			return null;
		}

		/** AOSP: the contact's photo (the high-resolution one if asked and present), or null. */
		public static java.io.InputStream openContactPhotoInputStream(android.content.ContentResolver cr, Uri contactUri,
		                                                              boolean preferHighres) {
			if (preferHighres) {
				final Uri displayPhotoUri = Uri.withAppendedPath(contactUri, Photo.DISPLAY_PHOTO);
				try {
					android.content.res.AssetFileDescriptor fd = cr.openAssetFileDescriptor(displayPhotoUri, "r");
					if (fd != null) {
						return fd.createInputStream();
					}
				} catch (java.io.IOException e) {
					// fall back to the thumbnail
				}
			}
			Uri photoUri = Uri.withAppendedPath(contactUri, Photo.CONTENT_DIRECTORY);
			android.database.Cursor cursor = cr.query(photoUri, new String[] {Photo.PHOTO}, null, null, null);
			try {
				if (cursor == null || !cursor.moveToNext()) {
					return null;
				}
				byte[] data = cursor.getBlob(0);
				if (data == null) {
					return null;
				}
				return new java.io.ByteArrayInputStream(data);
			} finally {
				if (cursor != null) {
					cursor.close();
				}
			}
		}

		/** AOSP: the contact's photo thumbnail, or null. */
		public static java.io.InputStream openContactPhotoInputStream(android.content.ContentResolver cr, Uri contactUri) {
			return openContactPhotoInputStream(cr, contactUri, false);
		}
