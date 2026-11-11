	/**
	 * AOSP (hidden): the check after a run of {@link #append} calls that no key occurs twice
	 * (Parcel.readArrayMap uses it for sorted bundles).
	 */
	public void validate() {
		final int N = mSize;
		if (N <= 1) {
			// There can't be dups.
			return;
		}
		int basehash = mHashes[0];
		int basei = 0;
		for (int i = 1; i < N; i++) {
			int hash = mHashes[i];
			if (hash != basehash) {
				basehash = hash;
				basei = i;
				continue;
			}
			// We are in a run of entries with the same hash code.  Go backwards through
			// the array to see if any keys are the same.
			final Object cur = mArray[i << 1];
			for (int j = i - 1; j >= basei; j--) {
				final Object prev = mArray[j << 1];
				if (cur == prev) {
					throw new IllegalArgumentException("Duplicate key in ArrayMap: " + cur);
				}
				if (cur != null && prev != null && cur.equals(prev)) {
					throw new IllegalArgumentException("Duplicate key in ArrayMap: " + cur);
				}
			}
		}
	}
