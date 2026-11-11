/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The download manager is a system service that handles long-running HTTP downloads (AOSP API).
 *
 * Android implements it as a system service plus the Downloads content provider. There is no
 * system server in this runtime, so the manager performs the downloads itself: {@link #enqueue}
 * starts the transfer on a background thread, the request's state is kept in an in-process table
 * with the same columns the Downloads provider exposes, {@link #query} returns it as a Cursor, and
 * completion is announced with the usual {@link #ACTION_DOWNLOAD_COMPLETE} broadcast carrying
 * {@link #EXTRA_DOWNLOAD_ID}. Destinations are the app's external files directory
 * ({@link Request#setDestinationInExternalFilesDir}) or any file URI; downloads without a
 * destination land in the external files directory under "Download/".
 */
public class DownloadManager {
	private static final String TAG = "DownloadManager";

	public static final String COLUMN_ID = "_id";
	public static final String COLUMN_TITLE = "title";
	public static final String COLUMN_DESCRIPTION = "description";
	public static final String COLUMN_URI = "uri";
	public static final String COLUMN_MEDIA_TYPE = "media_type";
	public static final String COLUMN_TOTAL_SIZE_BYTES = "total_size";
	public static final String COLUMN_LOCAL_URI = "local_uri";
	public static final String COLUMN_LOCAL_FILENAME = "local_filename";
	public static final String COLUMN_STATUS = "status";
	public static final String COLUMN_FILE_NAME_HINT = "hint";
	public static final String COLUMN_REASON = "reason";
	public static final String COLUMN_BYTES_DOWNLOADED_SO_FAR = "bytes_so_far";
	public static final String COLUMN_LAST_MODIFIED_TIMESTAMP = "last_modified_timestamp";
	public static final String COLUMN_MEDIAPROVIDER_URI = "mediaprovider_uri";
	public static final String COLUMN_DESTINATION = "destination";
	public static final String COLUMN_MEDIASTORE_URI = "mediastore_uri";
	public static final String COLUMN_ALLOW_WRITE = "allow_write";

	public static final int STATUS_PENDING = 1 << 0;
	public static final int STATUS_RUNNING = 1 << 1;
	public static final int STATUS_PAUSED = 1 << 2;
	public static final int STATUS_SUCCESSFUL = 1 << 3;
	public static final int STATUS_FAILED = 1 << 4;

	public static final int ERROR_UNKNOWN = 1000;
	public static final int ERROR_FILE_ERROR = 1001;
	public static final int ERROR_UNHANDLED_HTTP_CODE = 1002;
	public static final int ERROR_HTTP_DATA_ERROR = 1004;
	public static final int ERROR_TOO_MANY_REDIRECTS = 1005;
	public static final int ERROR_INSUFFICIENT_SPACE = 1006;
	public static final int ERROR_DEVICE_NOT_FOUND = 1007;
	public static final int ERROR_CANNOT_RESUME = 1008;
	public static final int ERROR_FILE_ALREADY_EXISTS = 1009;
	public static final int ERROR_BLOCKED = 1010;

	public static final int PAUSED_WAITING_TO_RETRY = 1;
	public static final int PAUSED_WAITING_FOR_NETWORK = 2;
	public static final int PAUSED_QUEUED_FOR_WIFI = 3;
	public static final int PAUSED_UNKNOWN = 4;

	public static final String ACTION_DOWNLOAD_COMPLETE = "android.intent.action.DOWNLOAD_COMPLETE";
	public static final String ACTION_NOTIFICATION_CLICKED = "android.intent.action.DOWNLOAD_NOTIFICATION_CLICKED";
	public static final String ACTION_VIEW_DOWNLOADS = "android.intent.action.VIEW_DOWNLOADS";
	public static final String INTENT_EXTRAS_SORT_BY_SIZE = "android.app.DownloadManager.extra_sortBySize";
	public static final String EXTRA_DOWNLOAD_ID = "extra_download_id";
	public static final String EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS = "extra_click_download_ids";

	public static final String[] UNDERLYING_COLUMNS = new String[] {
		COLUMN_ID, COLUMN_TITLE, COLUMN_DESCRIPTION, COLUMN_URI, COLUMN_MEDIA_TYPE, COLUMN_TOTAL_SIZE_BYTES,
		COLUMN_LOCAL_URI, COLUMN_LOCAL_FILENAME, COLUMN_STATUS, COLUMN_FILE_NAME_HINT, COLUMN_REASON,
		COLUMN_BYTES_DOWNLOADED_SO_FAR, COLUMN_LAST_MODIFIED_TIMESTAMP, COLUMN_MEDIAPROVIDER_URI,
		COLUMN_DESTINATION, COLUMN_MEDIASTORE_URI, COLUMN_ALLOW_WRITE,
	};

	/** One download's row of the Downloads table. */
	private static final class Download {
		final long id;
		final Request request;
		int status = STATUS_PENDING;
		int reason = 0;
		long totalSize = -1;
		long bytesSoFar = 0;
		long lastModified = System.currentTimeMillis();
		File file;
		String mediaType;
		volatile boolean cancelled;

		Download(long id, Request request) {
			this.id = id;
			this.request = request;
			this.mediaType = request.mMimeType;
		}
	}

	private static final Object sLock = new Object();
	private static final Map<Long, Download> sDownloads = new LinkedHashMap<>();
	private static long sNextId = 1;
	private static final ExecutorService sExecutor = Executors.newFixedThreadPool(3, new java.util.concurrent.ThreadFactory() {
		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "DownloadManager");
			t.setDaemon(true);
			return t;
		}
	});

	private final Context mContext;
	private boolean mAccessAllDownloads;
	private boolean mAccessFilename;

	/**
	 * This class contains all the information necessary to request a new download. The URI is the
	 * only required parameter.
	 */
	public static class Request {
		public static final int NETWORK_MOBILE = 1 << 0;
		public static final int NETWORK_WIFI = 1 << 1;
		public static final int NETWORK_BLUETOOTH = 1 << 2;
		public static final int VISIBILITY_VISIBLE = 0;
		public static final int VISIBILITY_VISIBLE_NOTIFY_COMPLETED = 1;
		public static final int VISIBILITY_HIDDEN = 2;
		public static final int VISIBILITY_VISIBLE_NOTIFY_ONLY_COMPLETION = 3;

		final Uri mUri;
		Uri mDestinationUri;
		final List<String[]> mRequestHeaders = new ArrayList<>();
		CharSequence mTitle;
		CharSequence mDescription;
		String mMimeType;
		int mAllowedNetworkTypes = ~0;
		boolean mRoamingAllowed = true;
		boolean mMeteredAllowed = true;
		int mFlags = 0;
		boolean mIsVisibleInDownloadsUi = true;
		boolean mScannable = false;
		int mNotificationVisibility = VISIBILITY_VISIBLE;

		/**
		 * @param uri the HTTP or HTTPS URI to download.
		 */
		public Request(Uri uri) {
			if (uri == null)
				throw new NullPointerException();
			String scheme = uri.getScheme();
			if (scheme == null || (!scheme.equals("http") && !scheme.equals("https")))
				throw new IllegalArgumentException("Can only download HTTP/HTTPS URIs: " + uri);
			mUri = uri;
		}

		Request(String uriString) {
			mUri = Uri.parse(uriString);
		}

		/** Set the local destination for the downloaded file (a file URI the app may write to). */
		public Request setDestinationUri(Uri uri) {
			mDestinationUri = uri;
			return this;
		}

		/** Destination in a public directory on external storage, e.g. Environment.DIRECTORY_DOWNLOADS. */
		public Request setDestinationInExternalPublicDir(String dirType, String subPath) {
			File file = new File(Environment.getExternalStorageDirectory(), dirType == null ? "" : dirType);
			if (!file.exists() && !file.mkdirs() && !file.isDirectory())
				throw new IllegalStateException("Unable to create directory: " + file.getAbsolutePath());
			setDestinationFromBase(file, subPath);
			return this;
		}

		/** Destination in the app's external files directory (Context.getExternalFilesDir(dirType)). */
		public Request setDestinationInExternalFilesDir(Context context, String dirType, String subPath) {
			File file = context.getExternalFilesDir(dirType);
			if (file == null)
				throw new IllegalStateException("Failed to get external storage files directory");
			if (!file.exists() && !file.mkdirs() && !file.isDirectory())
				throw new IllegalStateException("Unable to create directory: " + file.getAbsolutePath());
			setDestinationFromBase(file, subPath);
			return this;
		}

		private void setDestinationFromBase(File base, String subPath) {
			if (subPath == null)
				throw new NullPointerException("subPath cannot be null");
			mDestinationUri = Uri.fromFile(new File(base, subPath));
		}

		/** The media scanner is not part of this runtime; the flag is kept for the Downloads row. */
		public void allowScanningByMediaScanner() {
			mScannable = true;
		}

		public Request addRequestHeader(String header, String value) {
			if (header == null)
				throw new NullPointerException("header cannot be null");
			if (header.contains(":"))
				throw new IllegalArgumentException("header may not contain ':'");
			if (value == null)
				value = "";
			mRequestHeaders.add(new String[] { header, value });
			return this;
		}

		public Request setTitle(CharSequence title) {
			mTitle = title;
			return this;
		}

		public Request setDescription(CharSequence description) {
			mDescription = description;
			return this;
		}

		public Request setMimeType(String mimeType) {
			mMimeType = mimeType;
			return this;
		}

		@Deprecated
		public Request setShowRunningNotification(boolean show) {
			return setNotificationVisibility(show ? VISIBILITY_VISIBLE : VISIBILITY_HIDDEN);
		}

		/** Notifications for downloads are not shown by this runtime; the value is recorded. */
		public Request setNotificationVisibility(int visibility) {
			mNotificationVisibility = visibility;
			return this;
		}

		public Request setAllowedNetworkTypes(int flags) {
			mAllowedNetworkTypes = flags;
			return this;
		}

		public Request setAllowedOverRoaming(boolean allowed) {
			mRoamingAllowed = allowed;
			return this;
		}

		public Request setAllowedOverMetered(boolean allow) {
			mMeteredAllowed = allow;
			return this;
		}

		public Request setRequiresCharging(boolean requiresCharging) {
			if (requiresCharging)
				mFlags |= 1 << 0;
			else
				mFlags &= ~(1 << 0);
			return this;
		}

		public Request setRequiresDeviceIdle(boolean requiresDeviceIdle) {
			if (requiresDeviceIdle)
				mFlags |= 1 << 1;
			else
				mFlags &= ~(1 << 1);
			return this;
		}

		@Deprecated
		public Request setVisibleInDownloadsUi(boolean isVisible) {
			mIsVisibleInDownloadsUi = isVisible;
			return this;
		}
	}

	/** Used to filter and sort the downloads returned by {@link #query}. */
	public static class Query {
		public static final int ORDER_ASCENDING = 1;
		public static final int ORDER_DESCENDING = 2;

		long[] mIds = null;
		String mFilterString = null;
		int mStatusFlags = 0;
		String mOrderByColumn = COLUMN_LAST_MODIFIED_TIMESTAMP;
		int mOrderDirection = ORDER_DESCENDING;
		boolean mOnlyIncludeVisibleInDownloadsUi = false;

		public Query setFilterById(long... ids) {
			mIds = ids;
			return this;
		}

		public Query setFilterByString(String filter) {
			mFilterString = filter;
			return this;
		}

		public Query setFilterByStatus(int flags) {
			mStatusFlags = flags;
			return this;
		}

		public Query setOnlyIncludeVisibleInDownloadsUi(boolean value) {
			mOnlyIncludeVisibleInDownloadsUi = value;
			return this;
		}

		public Query orderBy(String column, int direction) {
			if (direction != ORDER_ASCENDING && direction != ORDER_DESCENDING)
				throw new IllegalArgumentException("Invalid direction: " + direction);
			if (column.equals(COLUMN_LAST_MODIFIED_TIMESTAMP) || column.equals(COLUMN_TOTAL_SIZE_BYTES)) {
				mOrderByColumn = column;
			} else {
				throw new IllegalArgumentException("Cannot order by " + column);
			}
			mOrderDirection = direction;
			return this;
		}

		boolean matches(Download d) {
			if (mIds != null) {
				boolean found = false;
				for (long id : mIds)
					found |= id == d.id;
				if (!found)
					return false;
			}
			if (mStatusFlags != 0 && (d.status & mStatusFlags) == 0)
				return false;
			if (mOnlyIncludeVisibleInDownloadsUi && !d.request.mIsVisibleInDownloadsUi)
				return false;
			if (mFilterString != null) {
				String t = d.request.mTitle == null ? "" : d.request.mTitle.toString();
				String desc = d.request.mDescription == null ? "" : d.request.mDescription.toString();
				if (!t.contains(mFilterString) && !desc.contains(mFilterString))
					return false;
			}
			return true;
		}
	}

	public DownloadManager(Context context) {
		mContext = context;
	}

	/** @hide */
	public void setAccessAllDownloads(boolean accessAllDownloads) {
		mAccessAllDownloads = accessAllDownloads;
	}

	/** @hide */
	public void setAccessFilename(boolean accessFilename) {
		mAccessFilename = accessFilename;
	}

	/**
	 * Enqueue a new download: the transfer starts right away on a background thread (there is no
	 * network/charging scheduling here). Returns the download id for {@link #query} and the
	 * completion broadcast.
	 */
	public long enqueue(Request request) {
		final Download d;
		synchronized (sLock) {
			d = new Download(sNextId++, request);
			sDownloads.put(d.id, d);
		}
		sExecutor.execute(new Runnable() {
			@Override
			public void run() {
				DownloadManager.this.run(d);
			}
		});
		return d.id;
	}

	/** Marks downloads as deleted; without a provider this is the same as {@link #remove}. */
	public int markRowDeleted(long... ids) {
		return remove(ids);
	}

	/** Cancels downloads and removes them from the download manager, deleting their files. */
	public int remove(long... ids) {
		if (ids == null || ids.length == 0)
			throw new IllegalArgumentException("input param 'ids' can't be null");
		int removed = 0;
		synchronized (sLock) {
			for (long id : ids) {
				Download d = sDownloads.remove(id);
				if (d != null) {
					d.cancelled = true;
					if (d.file != null)
						d.file.delete();
					removed++;
				}
			}
		}
		return removed;
	}

	public Cursor query(Query query) {
		return query(query, UNDERLYING_COLUMNS);
	}

	/** @hide */
	public Cursor query(Query query, String[] projection) {
		List<Download> rows = new ArrayList<>();
		synchronized (sLock) {
			for (Download d : sDownloads.values())
				if (query.matches(d))
					rows.add(d);
		}
		final boolean bySize = COLUMN_TOTAL_SIZE_BYTES.equals(query.mOrderByColumn);
		final int dir = query.mOrderDirection == Query.ORDER_ASCENDING ? 1 : -1;
		rows.sort(new java.util.Comparator<Download>() {
			@Override
			public int compare(Download a, Download b) {
				return dir * Long.compare(bySize ? a.totalSize : a.lastModified, bySize ? b.totalSize : b.lastModified);
			}
		});
		MatrixCursor cursor = new MatrixCursor(projection, rows.size());
		for (Download d : rows) {
			Object[] row = new Object[projection.length];
			for (int i = 0; i < projection.length; i++)
				row[i] = columnValue(d, projection[i]);
			cursor.addRow(row);
		}
		return cursor;
	}

	private Object columnValue(Download d, String column) {
		synchronized (sLock) {
			switch (column) {
				case COLUMN_ID: return d.id;
				case COLUMN_TITLE: return d.request.mTitle == null ? null : d.request.mTitle.toString();
				case COLUMN_DESCRIPTION: return d.request.mDescription == null ? null : d.request.mDescription.toString();
				case COLUMN_URI: return d.request.mUri.toString();
				case COLUMN_MEDIA_TYPE: return d.mediaType;
				case COLUMN_TOTAL_SIZE_BYTES: return d.totalSize;
				case COLUMN_LOCAL_URI: return d.file == null || d.status != STATUS_SUCCESSFUL ? null : Uri.fromFile(d.file).toString();
				case COLUMN_LOCAL_FILENAME: return d.file == null ? null : d.file.getAbsolutePath();
				case COLUMN_STATUS: return d.status;
				case COLUMN_FILE_NAME_HINT: return d.request.mDestinationUri == null ? null : d.request.mDestinationUri.toString();
				case COLUMN_REASON: return d.reason;
				case COLUMN_BYTES_DOWNLOADED_SO_FAR: return d.bytesSoFar;
				case COLUMN_LAST_MODIFIED_TIMESTAMP: return d.lastModified;
				case COLUMN_MEDIAPROVIDER_URI: return null;
				case COLUMN_DESTINATION: return d.request.mDestinationUri == null ? 0 : 4; // DESTINATION_FILE_URI
				case COLUMN_MEDIASTORE_URI: return null;
				case COLUMN_ALLOW_WRITE: return 0;
				default: return null;
			}
		}
	}

	/** Open a downloaded file for reading. */
	public ParcelFileDescriptor openDownloadedFile(long id) throws FileNotFoundException {
		File f = fileFor(id, STATUS_SUCCESSFUL);
		if (f == null)
			throw new FileNotFoundException("No successful download with id " + id);
		return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
	}

	/** The content URI of a successfully downloaded file (a file URI here), or null. */
	public Uri getUriForDownloadedFile(long id) {
		File f = fileFor(id, STATUS_SUCCESSFUL);
		return f == null ? null : Uri.fromFile(f);
	}

	public String getMimeTypeForDownloadedFile(long id) {
		synchronized (sLock) {
			Download d = sDownloads.get(id);
			return d == null || d.status != STATUS_SUCCESSFUL ? null : d.mediaType;
		}
	}

	private File fileFor(long id, int status) {
		synchronized (sLock) {
			Download d = sDownloads.get(id);
			return d == null || d.status != status ? null : d.file;
		}
	}

	/** Restart finished or failed downloads from scratch. */
	public void restartDownload(long... ids) {
		List<Download> restart = new ArrayList<>();
		synchronized (sLock) {
			for (long id : ids) {
				Download d = sDownloads.get(id);
				if (d == null)
					continue;
				if (d.status != STATUS_SUCCESSFUL && d.status != STATUS_FAILED)
					throw new IllegalArgumentException("Cannot restart incomplete download: " + id);
				d.status = STATUS_PENDING;
				d.reason = 0;
				d.bytesSoFar = 0;
				d.totalSize = -1;
				restart.add(d);
			}
		}
		for (Download d : restart)
			sExecutor.execute(new Runnable() {
			@Override
			public void run() {
				DownloadManager.this.run(d);
			}
		});
	}

	/** Downloads here always run as soon as enqueued; nothing is waiting on conditions. */
	public void forceDownload(long... ids) {
	}

	public static Long getMaxBytesOverMobile(Context context) {
		return null;
	}

	public static Long getRecommendedMaxBytesOverMobile(Context context) {
		return null;
	}

	/** Returns the download URI of the given id (a file URI once complete). */
	public Uri getDownloadUri(long id) {
		return Uri.parse("content://downloads/my_downloads/" + id);
	}

	// ---- the transfer -------------------------------------------------------------------------

	private File destinationFor(Download d) throws IOException {
		Uri dest = d.request.mDestinationUri;
		File f;
		if (dest != null) {
			f = new File(dest.getPath());
		} else {
			File dir = new File(mContext.getExternalFilesDir(null), "Download");
			if (!dir.isDirectory() && !dir.mkdirs())
				throw new IOException("cannot create " + dir);
			String name = d.request.mUri.getLastPathSegment();
			if (name == null || name.isEmpty())
				name = "download-" + d.id;
			f = new File(dir, d.id + "-" + name);
		}
		File parent = f.getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs())
			throw new IOException("cannot create " + parent);
		return f;
	}

	private void run(Download d) {
		synchronized (sLock) {
			if (d.cancelled)
				return;
			d.status = STATUS_RUNNING;
			d.lastModified = System.currentTimeMillis();
		}
		HttpURLConnection conn = null;
		try {
			File target = destinationFor(d);
			synchronized (sLock) {
				d.file = target;
			}
			URL url = new URL(d.request.mUri.toString());
			int redirects = 0;
			while (true) {
				conn = (HttpURLConnection) url.openConnection();
				conn.setInstanceFollowRedirects(false);
				conn.setConnectTimeout(30000);
				conn.setReadTimeout(60000);
				for (String[] h : d.request.mRequestHeaders)
					conn.addRequestProperty(h[0], h[1]);
				int code = conn.getResponseCode();
				if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
					String loc = conn.getHeaderField("Location");
					conn.disconnect();
					if (loc == null || ++redirects > 5) {
						fail(d, ERROR_TOO_MANY_REDIRECTS);
						return;
					}
					url = new URL(url, loc);
					continue;
				}
				if (code < 200 || code >= 300) {
					fail(d, code >= 400 && code < 600 ? code : ERROR_UNHANDLED_HTTP_CODE);
					return;
				}
				break;
			}
			long total = conn.getContentLengthLong();
			String type = conn.getContentType();
			synchronized (sLock) {
				d.totalSize = total;
				if (d.mediaType == null && type != null)
					d.mediaType = type.split(";")[0].trim();
			}
			try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
				byte[] buf = new byte[65536];
				int n;
				long sofar = 0;
				while ((n = in.read(buf)) > 0) {
					if (d.cancelled)
						return;
					out.write(buf, 0, n);
					sofar += n;
					synchronized (sLock) {
						d.bytesSoFar = sofar;
						d.lastModified = System.currentTimeMillis();
					}
				}
			}
			synchronized (sLock) {
				if (d.totalSize < 0)
					d.totalSize = d.bytesSoFar;
				d.status = STATUS_SUCCESSFUL;
				d.reason = 0;
				d.lastModified = System.currentTimeMillis();
			}
			announce(d);
		} catch (IOException e) {
			Log.w(TAG, "download " + d.id + " of " + d.request.mUri + " failed: " + e);
			fail(d, e instanceof FileNotFoundException ? ERROR_FILE_ERROR : ERROR_HTTP_DATA_ERROR);
		} finally {
			if (conn != null)
				conn.disconnect();
		}
	}

	private void fail(Download d, int reason) {
		synchronized (sLock) {
			d.status = STATUS_FAILED;
			d.reason = reason;
			d.lastModified = System.currentTimeMillis();
		}
		announce(d);
	}

	/** ACTION_DOWNLOAD_COMPLETE is sent for successful and failed downloads alike (AOSP). */
	private void announce(final Download d) {
		final Intent intent = new Intent(ACTION_DOWNLOAD_COMPLETE);
		intent.setPackage(mContext.getPackageName());
		intent.putExtra(EXTRA_DOWNLOAD_ID, d.id);
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				mContext.sendBroadcast(intent);
			}
		});
	}
}
