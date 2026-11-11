	/** Break strategy, hyphenation and justification constants (AOSP Layout, API 23/26). */
	public static final int BREAK_STRATEGY_SIMPLE = 0;
	public static final int BREAK_STRATEGY_HIGH_QUALITY = 1;
	public static final int BREAK_STRATEGY_BALANCED = 2;
	public static final int HYPHENATION_FREQUENCY_NONE = 0;
	public static final int HYPHENATION_FREQUENCY_NORMAL = 1;
	public static final int HYPHENATION_FREQUENCY_FULL = 2;
	public static final int HYPHENATION_FREQUENCY_NORMAL_FAST = 3;
	public static final int HYPHENATION_FREQUENCY_FULL_FAST = 4;
	public static final int JUSTIFICATION_MODE_NONE = 0;
	public static final int JUSTIFICATION_MODE_INTER_WORD = 1;
	/** Default text-direction heuristic for layouts (AOSP). */
	public static final TextDirectionHeuristic DEFAULT_TEXT_DIRECTION = TextDirectionHeuristics.FIRSTSTRONG_LTR;

	/**
	 * AOSP (API 34): decides whether a text segment counts as inside an area, for
	 * {@link #getRangeForRect} and the text-bounds queries of input methods.
	 */
	public interface TextInclusionStrategy {
		boolean isSegmentInside(android.graphics.RectF segmentBounds, android.graphics.RectF area);
	}

	/** AOSP: a segment is inside when its bounds overlap the area at all. */
	public static final TextInclusionStrategy INCLUSION_STRATEGY_ANY_OVERLAP = new TextInclusionStrategy() {
		@Override
		public boolean isSegmentInside(android.graphics.RectF segmentBounds, android.graphics.RectF area) {
			return android.graphics.RectF.intersects(segmentBounds, area);
		}
	};

	/** AOSP: a segment is inside when its centre point is inside the area. */
	public static final TextInclusionStrategy INCLUSION_STRATEGY_CONTAINS_CENTER = new TextInclusionStrategy() {
		@Override
		public boolean isSegmentInside(android.graphics.RectF segmentBounds, android.graphics.RectF area) {
			return area.contains(segmentBounds.centerX(), segmentBounds.centerY());
		}
	};

	/** AOSP: a segment is inside when the area contains all of it. */
	public static final TextInclusionStrategy INCLUSION_STRATEGY_CONTAINS_ALL = new TextInclusionStrategy() {
		@Override
		public boolean isSegmentInside(android.graphics.RectF segmentBounds, android.graphics.RectF area) {
			return area.contains(segmentBounds);
		}
	};
	/** AOSP: the width of a run of text; the direction heuristic changes shaping only for bidi text, which Pango resolves itself. */
	public static float getDesiredWidth(CharSequence source, int start, int end, TextPaint paint, TextDirectionHeuristic textDir) {
		return getDesiredWidth(source, start, end, paint);
	}
