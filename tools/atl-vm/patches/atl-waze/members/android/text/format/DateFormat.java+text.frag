	/** AOSP (hidden): ICU's date format symbols for a locale (the Gregorian calendar's) */
	public static android.icu.text.DateFormatSymbols getIcuDateFormatSymbols(java.util.Locale locale) {
		return new android.icu.text.DateFormatSymbols(android.icu.util.GregorianCalendar.class, locale);
	}
