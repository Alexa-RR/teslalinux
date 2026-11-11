	/** AOSP: a copy of the array, or null for null. */
	public static <T> T[] cloneOrNull(T[] array) {
		return array != null ? array.clone() : null;
	}
