	/* ==== teslalinux (+text): AOSP Layout behaviour on the PangoLayout (native-text-layout.sh) ==== */
	protected native void native_set_text_attrs(long layout, String text, int[] attrs, String[] strings);
	protected native void native_set_max_lines(long layout, int lines);
	protected native void native_set_alignment(long layout, int align);
	protected native int native_get_ellipsis_start(long layout, int line);

	/** @hide a Layout over an existing PangoLayout (a TextView's GtkLabel layout), which it owns */
	public static Layout tlWrapNative(long pangoLayout, CharSequence text, TextPaint paint, Alignment align, float spacingMult, float spacingAdd) {
		return new Layout(pangoLayout, text, paint, align, spacingMult, spacingAdd);
	}

	private Layout(long pangoLayout, CharSequence text, TextPaint paint, Alignment align, float spacingMult, float spacingAdd) {
		this.text = text;
		this.paint = paint;
		this.spacing_mult = spacingMult;
		this.spacing_add = spacingAdd;
		this.align = align;
		this.layout = pangoLayout;
	}

	/** the paint's face, the spans, the line spacing and the alignment as Pango attributes, like
	 * AOSP draws a layout's text with its TextPaint and the spans' updateDrawState */
	private void tlApplyStyle() {
		if (layout == 0)
			return;
		native_set_alignment(layout, align != null ? align.ordinal() : 0);
		final TlTextAttrs at = new TlTextAttrs();
		final TlTextAttrs.Face base = TlTextAttrs.Face.of(paint.getTypeface(), paint.isFakeBoldText(), paint.getTextSkewX());
		at.face(base, null, 0, -1);
		final float size = paint.getTextSize();
		final float ls = paint.getLetterSpacing();
		if (ls != 0 && size > 0)
			at.addFloat(TlTextAttrs.A_LETTERSP, 0, -1, ls * size);
		final int flags = paint.getFlags();
		if ((flags & android.graphics.Paint.UNDERLINE_TEXT_FLAG) != 0)
			at.add(TlTextAttrs.A_UNDERLINE, 0, -1, 1);
		if ((flags & android.graphics.Paint.STRIKE_THRU_TEXT_FLAG) != 0)
			at.add(TlTextAttrs.A_STRIKE, 0, -1, 1);
		if (spacing_mult != 1.0f || spacing_add != 0.0f) {
			final int lh = Math.round(paint.getFontMetricsInt(null) * spacing_mult + spacing_add);
			if (lh > 0)
				at.addFloat(TlTextAttrs.A_LINEHEIGHT, 0, -1, lh);
		}
		if (text instanceof Spanned) {
			at.spans((Spanned) text, base, paint.getTypeface(), size, paint.getColor(), paint.linkColor, ls, paint.density, paint.drawableState);
			at.lineHeights((Spanned) text, paint);
		}
		if (!at.isEmpty())
			native_set_text_attrs(layout, text != null ? text.toString() : "", at.attrs(), at.strings());
	}

	/** @hide the PangoEllipsizeMode for an AOSP truncation (START and MIDDLE need a single line) */
	protected static int tlPangoEllipsize(TextUtils.TruncateAt where, int maxLines) {
		if (where == null)
			return 0;
		switch (where) {
			case START:
				return maxLines == 1 ? 1 : 0;
			case MIDDLE:
				return maxLines == 1 ? 2 : 0;
			default:
				return 3; /* END, END_SMALL, MARQUEE (an end ellipsis while not scrolling) */
		}
	}

	/** AOSP (hidden): the extra space a line got from line spacing */
	public int getLineExtra(int line) {
		int extra = (getLineBottom(line) - getLineTop(line)) - (getLineDescent(line) - getLineAscent(line));
		return Math.max(0, extra);
	}

	private int tlBidiStart;

	private java.text.Bidi tlBidi(int line) {
		final CharSequence t = text != null ? text : "";
		final int start = Math.min(getLineStart(line), t.length());
		int pstart = start;
		while (pstart > 0 && t.charAt(pstart - 1) != '\n')
			pstart--;
		int pend = start;
		while (pend < t.length() && t.charAt(pend) != '\n')
			pend++;
		tlBidiStart = pstart;
		return new java.text.Bidi(t.subSequence(pstart, pend).toString(), java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
	}

	/** AOSP: the direction of the line's paragraph (first strong character, LTR by default) */
	public int getParagraphDirection(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return tlBidi(line).baseIsLeftToRight() ? DIR_LEFT_TO_RIGHT : DIR_RIGHT_TO_LEFT;
	}

	/** AOSP: whether the character at offset runs right to left (odd bidi level) */
	public boolean isRtlCharAt(int offset) {
		final CharSequence t = text != null ? text : "";
		if (offset < 0 || offset >= t.length())
			return false;
		final java.text.Bidi bidi = tlBidi(getLineForOffset(offset));
		final int i = offset - tlBidiStart;
		return i >= 0 && i < bidi.getLength() && (bidi.getLevelAt(i) & 1) != 0;
	}

	/** AOSP: where the ellipsis starts on the line, relative to the line start */
	public int getEllipsisStart(int line) {
		if (line < 0 || line >= getLineCount())
			throw new ArrayIndexOutOfBoundsException();
		return native_get_ellipsis_start(layout, line);
	}

	/** AOSP Layout: the base class has no font padding. Pango sizes lines by the fonts' ascent and
	 * descent and adds no top/bottom padding, so subclasses report 0 too (the values must match the
	 * layout that is drawn; Compose offsets every line by them). */
	public int getTopPadding() {
		return 0;
	}

	public int getBottomPadding() {
		return 0;
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			if (layout != 0) {
				native_free(layout);
				layout = 0;
			}
		} finally {
			super.finalize();
		}
	}

	public static final int DIR_LEFT_TO_RIGHT = 1;
	public static final int DIR_RIGHT_TO_LEFT = -1;

	/**
	 * AOSP (API 34): the range of text segments (from segmentFinder) inside area, judged by
	 * inclusionStrategy on each segment's bounds; null when there is none. Segment bounds are the
	 * cursor positions of its ends on its line, from the line top to the line bottom.
	 */
	public int[] getRangeForRect(android.graphics.RectF area, SegmentFinder segmentFinder, TextInclusionStrategy inclusionStrategy) {
		final int lineCount = getLineCount();
		if (lineCount == 0)
			return null;
		int startLine = getLineForVertical((int) area.top);
		if (area.top > getLineBottom(startLine)) {
			startLine++;
			if (startLine >= lineCount)
				return null;
		}
		int endLine = getLineForVertical((int) area.bottom);
		if (area.bottom < getLineTop(endLine)) {
			endLine--;
			if (endLine < 0)
				return null;
		}
		if (startLine > endLine)
			return null;
		int start = -1, end = -1;
		final android.graphics.RectF bounds = new android.graphics.RectF();
		for (int line = startLine; line <= endLine; line++) {
			final int lineStart = getLineStart(line);
			final int lineEnd = getLineEnd(line);
			int segStart = lineStart > 0 ? segmentFinder.nextStartBoundary(lineStart - 1) : segmentFinder.nextStartBoundary(-1);
			if (segStart == SegmentFinder.DONE || segStart < lineStart)
				segStart = segmentFinder.nextStartBoundary(lineStart);
			while (segStart != SegmentFinder.DONE && segStart < lineEnd) {
				int segEnd = segmentFinder.nextEndBoundary(segStart);
				if (segEnd == SegmentFinder.DONE)
					break;
				segEnd = Math.min(segEnd, lineEnd);
				final float x1 = getPrimaryHorizontal(segStart);
				final float x2 = segEnd >= lineEnd ? (isRtlCharAt(segStart) ? getLineLeft(line) : getLineRight(line)) : getPrimaryHorizontal(segEnd);
				bounds.set(Math.min(x1, x2), getLineTop(line), Math.max(x1, x2), getLineBottom(line));
				if (inclusionStrategy.isSegmentInside(bounds, area)) {
					if (start == -1 || segStart < start)
						start = segStart;
					if (segEnd > end)
						end = segEnd;
				}
				segStart = segmentFinder.nextStartBoundary(segStart);
			}
		}
		return start == -1 ? null : new int[] {start, end};
	}
