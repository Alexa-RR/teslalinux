	/** AOSP: returns the text without its NoCopySpan spans (SpannableStringBuilder drops them on copy). */
	public static CharSequence trimNoCopySpans(CharSequence text) {
		if (text != null && text instanceof Spanned) {
			// prune NoCopySpans
			return new SpannableStringBuilder(text);
		}
		return text;
	}

	/** Writes a CharSequence to a Parcel (AOSP writes its spans too; this Parcel carries the text). */
	public static void writeToParcel(CharSequence cs, android.os.Parcel p, int parcelableFlags) {
		p.writeString(cs == null ? null : cs.toString());
	}

	public static final android.os.Parcelable.Creator<CharSequence> CHAR_SEQUENCE_CREATOR = new android.os.Parcelable.Creator<CharSequence>() {
		public CharSequence createFromParcel(android.os.Parcel p) {
			return p.readString();
		}

		public CharSequence[] newArray(int size) {
			return new CharSequence[size];
		}
	};

	/** Span type ids used by writeToParcel/CHAR_SEQUENCE_CREATOR and ParcelableSpan (AOSP). */
	public static final int ALIGNMENT_SPAN = 1;
	public static final int FIRST_SPAN = ALIGNMENT_SPAN;
	public static final int FOREGROUND_COLOR_SPAN = 2;
	public static final int RELATIVE_SIZE_SPAN = 3;
	public static final int SCALE_X_SPAN = 4;
	public static final int STRIKETHROUGH_SPAN = 5;
	public static final int UNDERLINE_SPAN = 6;
	public static final int STYLE_SPAN = 7;
	public static final int BULLET_SPAN = 8;
	public static final int QUOTE_SPAN = 9;
	public static final int LEADING_MARGIN_SPAN = 10;
	public static final int URL_SPAN = 11;
	public static final int BACKGROUND_COLOR_SPAN = 12;
	public static final int TYPEFACE_SPAN = 13;
	public static final int SUPERSCRIPT_SPAN = 14;
	public static final int SUBSCRIPT_SPAN = 15;
	public static final int ABSOLUTE_SIZE_SPAN = 16;
	public static final int TEXT_APPEARANCE_SPAN = 17;
	public static final int ANNOTATION = 18;
	public static final int SUGGESTION_SPAN = 19;
	public static final int SPELL_CHECK_SPAN = 20;
	public static final int SUGGESTION_RANGE_SPAN = 21;
	public static final int EASY_EDIT_SPAN = 22;
	public static final int LOCALE_SPAN = 23;
	public static final int TTS_SPAN = 24;
	public static final int ACCESSIBILITY_CLICKABLE_SPAN = 25;
	public static final int ACCESSIBILITY_URL_SPAN = 26;
	public static final int LINE_BACKGROUND_SPAN = 27;
	public static final int LINE_HEIGHT_SPAN = 28;
	public static final int ACCESSIBILITY_REPLACEMENT_SPAN = 29;
	public static final int LAST_SPAN = ACCESSIBILITY_REPLACEMENT_SPAN;

	/** AOSP: a String for plain text, a SpannedString for spanned text (so copies are immutable). */
	public static CharSequence stringOrSpannedString(CharSequence source) {
		if (source == null)
			return null;
		if (source instanceof SpannedString)
			return source;
		if (source instanceof Spanned)
			return new SpannedString(source);
		return source.toString();
	}
	/** AOSP: prints every span of the text with its range and flags (debugging aid of the editors). */
	public static void dumpSpans(CharSequence cs, android.util.Printer printer, String prefix) {
		if (cs instanceof Spanned) {
			Spanned sp = (Spanned) cs;
			Object[] os = sp.getSpans(0, cs.length(), Object.class);
			for (int i = 0; i < os.length; i++) {
				Object o = os[i];
				printer.println(prefix + cs.subSequence(sp.getSpanStart(o), sp.getSpanEnd(o)) + ": "
						+ Integer.toHexString(System.identityHashCode(o)) + " " + o.getClass().getCanonicalName()
						+ " (" + sp.getSpanStart(o) + "-" + sp.getSpanEnd(o) + ") fl=#" + sp.getSpanFlags(o));
			}
		} else {
			printer.println(prefix + cs + ": (no spans)");
		}
	}
	/* AOSP: getCapsMode request bits */
	public static final int CAP_MODE_CHARACTERS = android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;
	public static final int CAP_MODE_WORDS = android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS;
	public static final int CAP_MODE_SENTENCES = android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
	/** AOSP (hidden): surrounds the builder's content with start and end. */
	public static void wrap(StringBuilder builder, String start, String end) {
		builder.insert(0, start);
		builder.append(end);
	}
	/**
	 * AOSP (hidden): upper-cases a CharSequence for the locale, keeping its spans when asked. AOSP maps
	 * span positions through ICU's case-mapping edits; here spans keep their offsets when the
	 * conversion does not change the length (the only case in which they could be mapped exactly).
	 */
	public static CharSequence toUpperCase(java.util.Locale locale, CharSequence source, boolean copySpans) {
		String upper = source.toString().toUpperCase(locale != null ? locale : java.util.Locale.getDefault());
		if (!copySpans || !(source instanceof android.text.Spanned) || upper.length() != source.length())
			return upper;
		android.text.SpannableStringBuilder result = new android.text.SpannableStringBuilder(upper);
		copySpansFrom((android.text.Spanned) source, 0, source.length(), Object.class, result, 0);
		return result;
	}
	/** AOSP: copies the spans of a range of source into dest, clipped to the range and shifted by destoff. */
	public static void copySpansFrom(android.text.Spanned source, int start, int end, Class kind, android.text.Spannable dest, int destoff) {
		if (kind == null) {
			kind = Object.class;
		}
		Object[] spans = source.getSpans(start, end, kind);
		for (int i = 0; i < spans.length; i++) {
			int st = source.getSpanStart(spans[i]);
			int en = source.getSpanEnd(spans[i]);
			int fl = source.getSpanFlags(spans[i]);
			if (st < start)
				st = start;
			if (en > end)
				en = end;
			dest.setSpan(spans[i], st - start + destoff, en - start + destoff, fl);
		}
	}
