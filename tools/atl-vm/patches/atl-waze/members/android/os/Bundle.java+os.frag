	/** AOSP (hidden): a bundle read from a parcel whose length int has been read already. */
	Bundle(Parcel parcelledData, int length) {
		super();
		mClassLoader = getClass().getClassLoader();
		readFromParcelInner(parcelledData, length);
		mFdsKnown = false;
	}

	/** AOSP (hidden): a bundle read from a parcel (length, magic, map). */
	public Bundle(Parcel parcelledData) {
		this(parcelledData, parcelledData.readInt());
	}

	/** AOSP: writes the bundle (length, magic, map); file descriptors only when this bundle allows them. */
	@Override
	public void writeToParcel(Parcel parcel, int flags) {
		final boolean oldAllowFds = parcel.pushAllowFds(mAllowFds);
		try {
			writeToParcelInner(parcel, flags);
		} finally {
			parcel.restoreAllowFds(oldAllowFds);
		}
	}

	public static final Parcelable.Creator<Bundle> CREATOR = new Parcelable.Creator<Bundle>() {
		@Override
		public Bundle createFromParcel(Parcel in) {
			return in.readBundle();
		}

		@Override
		public Bundle[] newArray(int size) {
			return new Bundle[size];
		}
	};

	/** AOSP (API 21): a Size value (Parcel VAL_SIZE). */
	public void putSize(String key, android.util.Size value) {
		mMap.put(key, value);
	}

	/** AOSP (API 21): a SizeF value (Parcel VAL_SIZEF). */
	public void putSizeF(String key, android.util.SizeF value) {
		mMap.put(key, value);
	}

	/** AOSP: the Size for key, or null when there is none or it is of another type. */
	public android.util.Size getSize(String key) {
		final Object o = mMap.get(key);
		try {
			return (android.util.Size) o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "Size", e);
			return null;
		}
	}

	/** AOSP: the SizeF for key, or null when there is none or it is of another type. */
	public android.util.SizeF getSizeF(String key) {
		final Object o = mMap.get(key);
		try {
			return (android.util.SizeF) o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "SizeF", e);
			return null;
		}
	}

	/** AOSP (API 33): the SparseArray for key when every value is an instance of clazz, else null. */
	@SuppressWarnings("unchecked")
	public <T> SparseArray<T> getSparseParcelableArray(String key, Class<? extends T> clazz) {
		final Object o = mMap.get(key);
		if (!(o instanceof SparseArray))
			return null;
		SparseArray<?> array = (SparseArray<?>) o;
		for (int i = 0; i < array.size(); i++) {
			Object v = array.valueAt(i);
			if (v != null && !clazz.isInstance(v)) {
				typeWarning(key, o, "SparseArray<" + clazz.getName() + ">", new ClassCastException());
				return null;
			}
		}
		return (SparseArray<T>) array;
	}
