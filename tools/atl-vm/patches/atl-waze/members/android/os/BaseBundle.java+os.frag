	/** AOSP: magic number of a bundle parcelled by Java code ('B' 'N' 'D' 'L') */
	static final int BUNDLE_MAGIC_JAVA = 0x4C444E42;
	/** AOSP: magic number of a bundle parcelled by libbinder's PersistableBundle ('B' 'N' 'D' 'N') */
	static final int BUNDLE_MAGIC_NATIVE = 0x4C444E44;

	/** AOSP: the class loader for Parcelables read from a parcel (Bundle keeps its own). */
	private ClassLoader mBaseClassLoader;

	void setClassLoader(ClassLoader loader) {
		mBaseClassLoader = loader;
	}

	ClassLoader getClassLoader() {
		return mBaseClassLoader;
	}

	/**
	 * teslalinux: the loader values of a parcelled bundle are resolved with. AOSP unparcels lazily,
	 * after the reader has had the chance to call setClassLoader; ATL unparcels when the bundle is
	 * read, so a bundle whose loader is still the framework's resolves through the app's class
	 * loader (which delegates to the framework's first): what the later setClassLoader would give.
	 */
	static ClassLoader parcelClassLoader(ClassLoader loader) {
		if (loader != null && loader != BaseBundle.class.getClassLoader())
			return loader;
		try {
			ClassLoader app = android.atl.ATLLoadedApp.getPrimaryApplication().class_loader;
			if (app != null)
				return app;
		} catch (Throwable e) {
			// no application loaded (ATL's test runner): the framework loader
		}
		return loader != null ? loader : BaseBundle.class.getClassLoader();
	}

	/** AOSP BaseBundle.writeToParcelInner: length, magic, then the map as Parcel.writeArrayMapInternal. */
	void writeToParcelInner(Parcel parcel, int flags) {
		final ArrayMap<String, Object> map = mMap;
		// Special case for empty bundles.
		if (map == null || map.size() <= 0) {
			parcel.writeInt(0);
			return;
		}
		int lengthPos = parcel.dataPosition();
		parcel.writeInt(-1); // placeholder, will hold length
		parcel.writeInt(BUNDLE_MAGIC_JAVA);

		int startPos = parcel.dataPosition();
		parcel.writeArrayMapInternal(map);
		int endPos = parcel.dataPosition();

		// Backpatch length
		parcel.setDataPosition(lengthPos);
		int length = endPos - startPos;
		parcel.writeInt(length);
		parcel.setDataPosition(endPos);
	}

	/**
	 * AOSP BaseBundle.readFromParcelInner, unparcelling at once (see parcelClassLoader). The parcel
	 * ends up after the bundle's length whatever its values consumed, as in AOSP.
	 */
	void readFromParcelInner(Parcel parcel, int length) {
		if (length < 0) {
			throw new RuntimeException("Bad length in parcel: " + length);
		} else if (length == 0) {
			// Empty Bundle or end of data.
			mMap = new ArrayMap<String, Object>();
			return;
		} else if (length % 4 != 0) {
			throw new IllegalStateException("Bundle length is not aligned by 4: " + length);
		}
		final int magic = parcel.readInt();
		final boolean isJavaBundle = magic == BUNDLE_MAGIC_JAVA;
		final boolean isNativeBundle = magic == BUNDLE_MAGIC_NATIVE;
		if (!isJavaBundle && !isNativeBundle) {
			throw new IllegalStateException("Bad magic number for Bundle: 0x" + Integer.toHexString(magic));
		}
		final int offset = parcel.dataPosition();
		final int end = android.util.MathUtils.addOrThrow(offset, length);
		try {
			int count = parcel.readInt();
			if (count < 0) {
				mMap = new ArrayMap<String, Object>();
				return;
			}
			ArrayMap<String, Object> map = new ArrayMap<String, Object>(count);
			parcel.readArrayMap(map, count, /* sorted */ isJavaBundle, /* lazy */ false,
			                    parcelClassLoader(getClassLoader()));
			mMap = map;
		} finally {
			parcel.setDataPosition(end);
		}
	}

	/** AOSP: whether the values hold file descriptors (Parcel.hasFileDescriptors on the map). */
	boolean mapHasFileDescriptors() {
		return mMap != null && Parcel.hasFileDescriptors(mMap);
	}
