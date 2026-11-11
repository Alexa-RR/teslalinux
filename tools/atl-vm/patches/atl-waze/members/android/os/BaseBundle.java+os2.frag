	/** AOSP: inserts every mapping of bundle. */
	public void putAll(PersistableBundle bundle) {
		mMap.putAll(bundle.mMap);
	}

	/** AOSP: inserts a double value, replacing any existing value for the key. */
	public void putDouble(String key, double value) {
		mMap.put(key, value);
	}

	/** AOSP: inserts a boolean array value, replacing any existing value for the key. */
	public void putBooleanArray(String key, boolean[] value) {
		mMap.put(key, value);
	}

	/** AOSP: inserts a double array value, replacing any existing value for the key. */
	public void putDoubleArray(String key, double[] value) {
		mMap.put(key, value);
	}

	/** AOSP: the double for key, 0.0 when there is none or it is of another type. */
	public double getDouble(String key) {
		return getDouble(key, 0.0);
	}

	/** AOSP: the double for key, defaultValue when there is none or it is of another type. */
	public double getDouble(String key, double defaultValue) {
		Object o = mMap.get(key);
		if (o == null)
			return defaultValue;
		try {
			return (Double) o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "Double", defaultValue, e);
			return defaultValue;
		}
	}

	/** AOSP: the boolean array for key, or null. */
	public boolean[] getBooleanArray(String key) {
		Object o = mMap.get(key);
		if (o == null)
			return null;
		try {
			return (boolean[]) o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "byte[]", e);
			return null;
		}
	}

	/** AOSP: the double array for key, or null. */
	public double[] getDoubleArray(String key) {
		Object o = mMap.get(key);
		if (o == null)
			return null;
		try {
			return (double[]) o;
		} catch (ClassCastException e) {
			typeWarning(key, o, "double[]", e);
			return null;
		}
	}
