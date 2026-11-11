	/**
	 * Returns the cookie of the asset path (APK) with the given file system path, or 0 when it is not
	 * one of this manager's asset paths (AOSP {@code findCookieForPath}). Cookies are 1-based in the
	 * order the paths were added.
	 */
	public int findCookieForPath(String path) {
		if (path == null)
			return 0;
		synchronized (this) {
			int idx = asset_paths.indexOf(path);
			if (idx < 0) {
				String abs = new java.io.File(path).getAbsolutePath();
				for (int i = 0; i < asset_paths.size(); i++) {
					if (new java.io.File(asset_paths.get(i)).getAbsolutePath().equals(abs)) {
						idx = i;
						break;
					}
				}
			}
			return idx < 0 ? 0 : idx + 1;
		}
	}

	/** Entry names present in more than one APK asset path (e.g. AndroidManifest.xml of a base APK and its splits). */
	private java.util.Set<String> mSharedEntries;

	private java.util.Set<String> sharedEntries() {
		synchronized (this) {
			if (mSharedEntries == null) {
				java.util.Set<String> seen = new java.util.HashSet<>();
				java.util.Set<String> shared = new java.util.HashSet<>();
				for (String p : asset_paths) {
					if (p.endsWith("/"))
						continue;
					try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(p)) {
						java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zip.entries();
						while (e.hasMoreElements()) {
							String name = e.nextElement().getName();
							if (!seen.add(name))
								shared.add(name);
						}
					} catch (IOException e) {
						Log.w(TAG, "could not index " + p + ": " + e);
					}
				}
				mSharedEntries = shared;
			}
			return mSharedEntries;
		}
	}

	/** Reads a compiled XML entry from the APK a cookie names; null when that APK has no such entry. */
	private byte[] readXmlFromCookie(int cookie, String fileName) throws IOException {
		String path;
		synchronized (this) {
			if (cookie < 1 || cookie > asset_paths.size())
				return null;
			path = asset_paths.get(cookie - 1);
		}
		if (path.endsWith("/"))
			return null;
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path)) {
			java.util.zip.ZipEntry entry = zip.getEntry(fileName);
			if (entry == null)
				return null;
			try (InputStream in = zip.getInputStream(entry)) {
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) Math.max(entry.getSize(), 1024));
				byte[] buf = new byte[65536];
				int n;
				while ((n = in.read(buf)) > 0)
					out.write(buf, 0, n);
				return out.toByteArray();
			}
		}
	}

	private native final long openXmlAssetFromBufferNative(byte[] data);
	/* teslalinux: AOSP gives every Resources its own AssetManager over the shared loaded APKs */
	long tlApkAssets;      // native array of the loaded ApkAssets (set by native_setApkAssets)
	int tlApkAssetsCount;

	private native void native_shareApkAssets(long apkAssets, int count);

	private AssetManager(AssetManager sharing, boolean sharingMarker) {
		synchronized (this) {
			init(android.os.Build.VERSION.RESOURCES_SDK_INT);
			asset_paths.addAll(sharing.asset_paths);
			native_shareApkAssets(sharing.tlApkAssets, sharing.tlApkAssetsCount);
		}
	}

	/** A manager over the same APKs with its own configuration and caches (AOSP: one per ResourcesImpl). */
	public AssetManager tlNewSharing() {
		return new AssetManager(this, true);
	}
