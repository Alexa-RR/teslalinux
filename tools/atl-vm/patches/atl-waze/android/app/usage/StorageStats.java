package android.app.usage;

import java.io.File;

public final class StorageStats {
	private static long dirSize(File f) {
		if (f == null || !f.exists())
			return 0;
		if (f.isFile())
			return f.length();
		long total = 0;
		File[] children = f.listFiles();
		if (children != null)
			for (File c : children)
				total += dirSize(c);
		return total;
	}

	private long dataBytes() {
		String dir = System.getenv("ANDROID_APP_DATA_DIR");
		return dir != null ? dirSize(new File(dir)) : 0;
	}

	public long getAppBytes() { return 0; }
	public long getDataBytes() { return dataBytes(); }
	public long getCacheBytes() { return 0; }
	public long getExternalCacheBytes() { return 0; }
	public long getAppBytesByDataType(int dataType) { return 0; }
}
