package android.app.usage;

import android.os.UserHandle;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

/* teslalinux: Waze's Application.onCreate queries storage stats for itself. Report the data dir's
 * real usage and the root filesystem's free/total space. */
public class StorageStatsManager {
	public StorageStatsManager() {}

	public long getTotalBytes(UUID storageUuid) throws IOException {
		return new File("/").getTotalSpace();
	}

	public long getFreeBytes(UUID storageUuid) throws IOException {
		return new File("/").getUsableSpace();
	}

	public StorageStats queryStatsForPackage(UUID storageUuid, String packageName, UserHandle user) throws IOException {
		return new StorageStats();
	}

	public StorageStats queryStatsForUid(UUID storageUuid, int uid) throws IOException {
		return new StorageStats();
	}

	public StorageStats queryStatsForUser(UUID storageUuid, UserHandle user) throws IOException {
		return new StorageStats();
	}

	public ExternalStorageStats queryExternalStatsForUser(UUID storageUuid, UserHandle user) throws IOException {
		return new ExternalStorageStats();
	}

	public static class ExternalStorageStats {
		public long getTotalBytes() { return 0; }
		public long getAudioBytes() { return 0; }
		public long getVideoBytes() { return 0; }
		public long getImageBytes() { return 0; }
		public long getAppBytes() { return 0; }
	}
}
