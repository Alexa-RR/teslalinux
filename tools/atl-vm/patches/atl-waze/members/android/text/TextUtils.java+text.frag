	/** AOSP (hidden): whether the code point is punctuation (teslalinux +text) */
	public static boolean isPunctuation(int codePoint) {
		int type = Character.getType(codePoint);
		return type == Character.CONNECTOR_PUNCTUATION
			|| type == Character.DASH_PUNCTUATION
			|| type == Character.END_PUNCTUATION
			|| type == Character.FINAL_QUOTE_PUNCTUATION
			|| type == Character.INITIAL_QUOTE_PUNCTUATION
			|| type == Character.OTHER_PUNCTUATION
			|| type == Character.START_PUNCTUATION;
	}
