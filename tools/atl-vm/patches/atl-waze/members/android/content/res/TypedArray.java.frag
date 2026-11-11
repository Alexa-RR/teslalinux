	/* teslalinux: AOSP TypedArray.getFont */
	public android.graphics.Typeface getFont(int index) {
		final TypedValue value = mValue;
		if (getValueAt(index, value)) {
			if (value.type == TypedValue.TYPE_ATTRIBUTE) {
				throw new UnsupportedOperationException("Failed to resolve attribute at index " + index + ": " + value);
			}
			return mResources.getFont(value, value.resourceId);
		}
		return null;
	}
