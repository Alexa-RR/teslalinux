	/* AOSP Bundle.deepCopy(): a copy whose nested bundles and arrays are copied too */
	public Bundle deepCopy() {
		Bundle copy = new Bundle();
		for (String key : keySet()) {
			Object v = get(key);
			if (v instanceof Bundle) v = ((Bundle) v).deepCopy();
			else if (v instanceof PersistableBundle) v = new PersistableBundle((PersistableBundle) v);
			else if (v instanceof int[]) v = ((int[]) v).clone();
			else if (v instanceof long[]) v = ((long[]) v).clone();
			else if (v instanceof float[]) v = ((float[]) v).clone();
			else if (v instanceof double[]) v = ((double[]) v).clone();
			else if (v instanceof byte[]) v = ((byte[]) v).clone();
			else if (v instanceof boolean[]) v = ((boolean[]) v).clone();
			else if (v instanceof char[]) v = ((char[]) v).clone();
			else if (v instanceof short[]) v = ((short[]) v).clone();
			else if (v instanceof String[]) v = ((String[]) v).clone();
			else if (v instanceof CharSequence[]) v = ((CharSequence[]) v).clone();
			else if (v instanceof java.util.ArrayList) v = new java.util.ArrayList<>((java.util.ArrayList<?>) v);
			copy.mMap.put(key, v);
		}
		return copy;
	}

	/** API 33 typed getters: the value when it is an instance of clazz, else null (AOSP semantics). */
	public <T> T getParcelable(String key, Class<T> clazz) {
		Object o = getParcelable(key);
		return clazz.isInstance(o) ? clazz.cast(o) : null;
	}

	public <T extends java.io.Serializable> T getSerializable(String key, Class<T> clazz) {
		Object o = getSerializable(key);
		return clazz.isInstance(o) ? clazz.cast(o) : null;
	}

	public <T> T[] getParcelableArray(String key, Class<T> clazz) {
		Parcelable[] arr = getParcelableArray(key);
		if (arr == null)
			return null;
		@SuppressWarnings("unchecked")
		T[] out = (T[]) java.lang.reflect.Array.newInstance(clazz, arr.length);
		for (int i = 0; i < arr.length; i++) {
			if (arr[i] != null && !clazz.isInstance(arr[i]))
				return null;
			out[i] = clazz.cast(arr[i]);
		}
		return out;
	}

	public <T> ArrayList<T> getParcelableArrayList(String key, Class<? extends T> clazz) {
		ArrayList<? extends Parcelable> list = getParcelableArrayList(key);
		if (list == null)
			return null;
		ArrayList<T> out = new ArrayList<>(list.size());
		for (Parcelable p : list) {
			if (p != null && !clazz.isInstance(p))
				return null;
			out.add(clazz.cast(p));
		}
		return out;
	}
	/** AOSP (hidden): a loose equality used by EditorInfo; entries are compared with equals(). */
	public boolean kindofEquals(Bundle other) {
		return other != null && mMap.equals(other.mMap);
	}
