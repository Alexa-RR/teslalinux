	/* AOSP constructors and deepCopy (adding a constructor removes the implicit default one) */
	public PersistableBundle() { super(); }
	public PersistableBundle(PersistableBundle b) {
		super();
		mMap.putAll(b.mMap);
	}
	public PersistableBundle deepCopy() {
		PersistableBundle copy = new PersistableBundle();
		for (String key : keySet()) {
			Object v = get(key);
			if (v instanceof PersistableBundle) v = ((PersistableBundle) v).deepCopy();
			else if (v instanceof int[]) v = ((int[]) v).clone();
			else if (v instanceof long[]) v = ((long[]) v).clone();
			else if (v instanceof double[]) v = ((double[]) v).clone();
			else if (v instanceof boolean[]) v = ((boolean[]) v).clone();
			else if (v instanceof String[]) v = ((String[]) v).clone();
			copy.mMap.put(key, v);
		}
		return copy;
	}
