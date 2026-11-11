	/** AOSP (API 31): whether other maps the same keys to equal values. */
	public boolean contentEquals(SparseArray<?> other) {
		if (other == null) {
			return false;
		}
		int size = size();
		if (size != other.size()) {
			return false;
		}
		// size() calls gc(), so we don't need to worry about deleted entries
		for (int index = 0; index < size; index++) {
			int key = keyAt(index);
			if (!java.util.Objects.equals(valueAt(index), other.get(key))) {
				return false;
			}
		}
		return true;
	}

	/** AOSP (API 31): a hash code over the keys and values. */
	public int contentHashCode() {
		int hash = 0;
		int size = size();
		for (int index = 0; index < size; index++) {
			int key = keyAt(index);
			E value = valueAt(index);
			hash = 31 * hash + key;
			hash = 31 * hash + java.util.Objects.hashCode(value);
		}
		return hash;
	}
